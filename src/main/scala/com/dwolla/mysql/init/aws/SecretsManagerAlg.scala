package com.dwolla.mysql.init
package aws

import cats.syntax.all._
import cats.effect.{Trace => _, _}
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import com.dwolla.fs2aws.AwsEval
import io.circe.{Decoder, parser}
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model._

trait SecretsManagerAlg[F[_]] {
  def getSecret(secretId: SecretId): F[String]
  def getSecretAs[A : Decoder](secretId: SecretId): F[A]
}

object SecretsManagerAlg {
  implicit val SecretsManagerAlgInstrumentation: Instrument[SecretsManagerAlg] = Derive.instrument

  def resource[F[_] : Async : Logger]: Resource[F, SecretsManagerAlg[F]] =
    Resource.fromAutoCloseable(Sync[F].delay(SecretsManagerAsyncClient.builder().build()))
      .map(SecretsManagerAlg[F](_))

  def apply[F[_] : Async : Logger](client: SecretsManagerAsyncClient): SecretsManagerAlg[F] = new SecretsManagerAlg[F] {
    override def getSecret(secretId: SecretId): F[String] =
      Logger[F].info(s"retrieving secret id $secretId") >>
        AwsEval.eval[F](GetSecretValueRequest.builder().secretId(secretId.value).build())(client.getSecretValue)(_.secretString())

    override def getSecretAs[A: Decoder](secretId: SecretId): F[A] =
      for {
        secretString <- getSecret(secretId)
        secretJson <- parser.parse(secretString).liftTo[F]
        a <- secretJson.as[A].liftTo[F]
      } yield a
  }
}

case class ResourceNotFoundException(resource: String, cause: Option[Throwable]) extends RuntimeException(resource, cause.orNull)
