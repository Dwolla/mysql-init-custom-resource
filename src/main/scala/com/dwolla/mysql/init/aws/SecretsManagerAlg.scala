package com.dwolla.mysql.init
package aws

import cats.MonadThrow
import cats.syntax.all._
import cats.effect.{Trace => _, _}
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import com.dwolla.fs2aws.AwsEval
import com.dwolla.mysql.init.aws.SecretDeletionRecoveryTime._
import com.dwolla.mysql.init.aws.SecretsManagerAlg.SecretName
import eu.timepit.refined.api.Refined
import eu.timepit.refined.predicates.all._
import eu.timepit.refined.auto._
import io.circe.syntax._
import io.circe.{CursorOp, Decoder, DecodingFailure, Encoder, parser}
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model._

import scala.util.control.NoStackTrace

trait SecretsManagerAlg[F[_]] {
  def getSecret(secretId: SecretId): F[String]
  def getSecretAs[A : Decoder](secretId: SecretId): F[A]

  def createSecret(name: SecretName, secret: String): F[SecretId]
  def deleteSecret(id: SecretId, deletionTimeFrame: SecretDeletionRecoveryTime = Delayed(30)): F[Unit]

  final def createJsonSecret[A : Encoder](name: SecretName, a: A): F[SecretId] =
    createSecret(name, a.asJson.noSpaces)
}

object SecretsManagerAlg {
  val SecretNameRegex = """[-A-Za-z0-9/_+=.@]+"""
  type SecretNamePredicate = MatchesRegex[SecretNameRegex.type]
  type SecretName = String Refined SecretNamePredicate

  implicit val SecretsManagerAlgInstrumentation: Instrument[SecretsManagerAlg] = Derive.instrument

  def resource[F[_] : Async : Logger]: Resource[F, SecretsManagerAlg[F]] =
    Resource.fromAutoCloseable(Sync[F].delay(SecretsManagerAsyncClient.builder().build()))
      .map(SecretsManagerAlg[F](_))

  def apply[F[_] : Async : Logger](client: SecretsManagerAsyncClient): SecretsManagerAlg[F] = new SecretsManagerAlg[F] {
    override def getSecret(secretId: SecretId): F[String] =
      Logger[F].info(s"retrieving secret id $secretId") >>
        AwsEval.eval[F](GetSecretValueRequest.builder().secretId(secretId.value).build())(client.getSecretValue)(_.secretString())

    override def getSecretAs[A: Decoder](secretId: SecretId): F[A] =
      getSecret(secretId).flatMap(decodeSecret[F, A](secretId))

    override def createSecret(name: SecretName, secret: String): F[SecretId] =
      AwsEval.eval[F](CreateSecretRequest.builder().name(name.value).secretString(secret).build())(client.createSecret)(_.arn())
        .map(SecretId(_))

    override def deleteSecret(id: SecretId, deletionTimeFrame: SecretDeletionRecoveryTime): F[Unit] =
      AwsEval.eval[F](DeleteSecretRequest.builder().secretId(id.value).withCoolDownPeriod(deletionTimeFrame).build())(client.deleteSecret)(_ => ())
  }

  /**
   * Decoding errors quote the input they failed on, so a failure is reported by the secret's
   * id and the path that failed, never by the decoder's own message.
   */
  private[init] def decodeSecret[F[_] : MonadThrow, A : Decoder](secretId: SecretId)
                                                                (secretString: String): F[A] =
    parser.decode[A](secretString)
      .leftMap {
        case failure: DecodingFailure =>
          UndecodableSecretException(secretId, s"the value at ${CursorOp.opsToPath(failure.history)} is missing or invalid")
        case _ =>
          UndecodableSecretException(secretId, "the secret is not valid JSON")
      }
      .liftTo[F]
}

case class ResourceNotFoundException(resource: String, cause: Option[Throwable]) extends RuntimeException(resource, cause.orNull)

case class UndecodableSecretException(secretId: SecretId, problem: String)
  extends RuntimeException(s"Could not decode secret ${secretId.value}: $problem")
    with NoStackTrace

sealed trait SecretDeletionRecoveryTime

object SecretDeletionRecoveryTime {
  type CoolDownPeriodInDays = Int Refined (GreaterEqual[7] And LessEqual[30])

  case object Immediate extends SecretDeletionRecoveryTime
  case class Delayed(days: CoolDownPeriodInDays) extends SecretDeletionRecoveryTime

  implicit class DeleteSecretRequestBuilderOps(val builder: DeleteSecretRequest.Builder) extends AnyVal {
    def withCoolDownPeriod(recoveryTime: SecretDeletionRecoveryTime): DeleteSecretRequest.Builder =
      recoveryTime match {
        case Immediate => builder.forceDeleteWithoutRecovery(true)
        case Delayed(cooldown) => builder.recoveryWindowInDays(cooldown.value)
      }
  }
}
