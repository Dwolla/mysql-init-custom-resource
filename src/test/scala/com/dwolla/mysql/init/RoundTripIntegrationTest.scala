package com.dwolla.mysql.init

import cats._
import cats.data._
import cats.effect._
import cats.syntax.all._
import cats.tagless.syntax.all._
import com.dwolla.mysql.init.FakeS3.CapturedRequests
import com.dwolla.mysql.init.aws.SecretDeletionRecoveryTime.Immediate
import com.dwolla.mysql.init.aws.SecretsManagerAlg.{SecretName, SecretNamePredicate}
import com.dwolla.mysql.init.aws.{SecretDeletionRecoveryTime, SecretsManagerAlg}
import com.eed3si9n.expecty.Expecty.expect
import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import feral.lambda.cloudformation.RequestResponseStatus._
import feral.lambda.cloudformation.{CloudFormationCustomResourceArbitraries, CloudFormationCustomResourceRequest, CloudFormationCustomResourceResponse, CloudFormationRequestType, RequestResponseStatus}
import feral.lambda.{LambdaEnv, TestContext}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, parser}
import monocle._
import monocle.macros._
import monocle.syntax.all._
import munit._
import natchez.Span
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, Request}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.effect.PropF
import org.scalacheck.util.Pretty
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.jawn.Parser
import org.typelevel.log4cats.Logger
import _root_.io.circe.optics.JsonPath._
import com.dwolla.mysql.init.repositories.ReservedWords
import com.dwolla.testutils.IntegrationTest
import doobie.LogHandler

import scala.concurrent.duration._

class RoundTripIntegrationTest
  extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with CatsEffectFixtures
    with CloudFormationCustomResourceArbitraries
    with ArbitraryRefinedTypes {

  override def munitTimeout: Duration = 10.minutes

  private def genResources[F[_] : Monad, A](max: Int = 10)
                                           (implicit R: Arbitrary[Resource[F, A]]): Gen[Resource[F, List[A]]] =
    for {
      size <- Gen.chooseNum(0, max)
      resources <- Gen.listOfN(size, R.arbitrary)
    } yield resources.sequence

  private def genSecret[F[_] : Functor, A : Encoder : Arbitrary](secretsAlg: SecretsManagerAlg[F]): Gen[Resource[F, SecretId]] =
    for {
      nameSuffix <- Gen.stringOfN(20, Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('-', '/', '_', '+', '=', '.', '@', '!')))
      a <- arbitrary[A]
      name <- refineV[SecretNamePredicate](s"integrationTestSecret-$nameSuffix").fold(_ => Gen.fail, Gen.const)
    } yield Resource.make(secretsAlg.createJsonSecret(name, a))(secretsAlg.deleteSecret(_, Immediate))

  private val genUserConnectionInfo: Option[Database] => Gen[UserConnectionInfo] = maybeDatabase =>
    for {
      database <- maybeDatabase.fold(arbitrary[SqlIdentifier].map(Database(_)))(Gen.const)
      host <- Gen.const(Host(BuildInfo.test_databaseAddress))
      port <- Gen.const(Port(BuildInfo.test_databasePort.toInt))
      user <- arbitrary[MySqlUser].map(Username(_))
      password <- arbitrary[GeneratedPassword].map(Password(_))
    } yield UserConnectionInfo(database, host, port, user, password)

  private def genDatabaseMetadata[F[_] : Monad, A : Encoder](secretsAlg: SecretsManagerAlg[F],
                                                             genA: Option[Database] => Gen[A]): Gen[Resource[F, DatabaseMetadata]] = {
    for {
      host <- Gen.const(Host(BuildInfo.test_databaseAddress))
      port <- Gen.const(Port(BuildInfo.test_databasePort.toInt))
      database <- arbitrary[SqlIdentifier].map(Database(_))
      username <- refinedConst[MySqlUser](BuildInfo.test_databaseUser).map(MasterDatabaseUsername(_))
      password <- Gen.const(BuildInfo.test_databasePassword).map(MasterDatabasePassword(_))
      secrets <- {
        implicit val arbA: Arbitrary[A] = Arbitrary(genA(database.some))
        implicit val arbSecret: Arbitrary[Resource[F, SecretId]] = Arbitrary(genSecret[F, A](secretsAlg))

        genResources[F, SecretId]()
      }
    } yield secrets.map(DatabaseMetadata(host, port, database, username, password, _))
  }

  /**
   * Secrets Manager secrets cost $0.40 / month, so it could be expensive to run this using the real
   * Secrets Manager service. That said, if you want to run the test that way, replace the
   * {{{FakeSecretsManagerAlg}}} with {{{SecretsManagerAlg.resource[IO]}}}.
   */
  private val secretsManagerAlg: Fixture[SecretsManagerAlg[IO]] = ResourceSuiteLocalFixture(
    "SecretsManagerAlg",
    Ref.in[Resource[IO, *], IO, Map[SecretId, String]](Map.empty).map(new FakeSecretsManagerAlg(_))
  )

  private implicit val prettyRequestResource: Resource[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] => Pretty = {
    case Resource.Pure(req) => Pretty(_ => req.asJson.spaces2)
    case other => Pretty { _ =>
      other.use { req =>
        req
          .ResourceProperties
          .secretIds
          .traverse(secretsManagerAlg().getSecret(_).flatMap(parser.parse(_).liftTo[IO]))
          .map { secrets =>
            req.applyLens(requestTypeLens).set(CloudFormationRequestType.OtherRequestType("template"))
              .asJson
              .mapObject(_.add("Materialized Secrets (this key is synthetic and not part of the AWS protocol)", secrets.asJson))
              .spaces2
          }
      }.unsafeRunSync() // this would normally be illegal, but it's probably fine here since this only happens when a test failed anyway
    }
  }

  override def munitFixtures = List(secretsManagerAlg)

  private def requestTypeLens[T]: Lens[CloudFormationCustomResourceRequest[T], CloudFormationRequestType] = GenLens[CloudFormationCustomResourceRequest[T]](_.RequestType)

  private val logger = org.slf4j.LoggerFactory.getLogger("Repositories")
  private implicit val logHandler: LogHandler = LogHandler { event =>
    logger.info(s"🔮 ${AnsiColorCodes.red}${event.sql}${AnsiColorCodes.reset}")
  }

  test("Handler can create and destroy a database with users".tag(IntegrationTest)) {
    implicit val arbDatabaseMetadata: Arbitrary[Resource[IO, DatabaseMetadata]] = Arbitrary(genDatabaseMetadata[IO, UserConnectionInfo](secretsManagerAlg(), genUserConnectionInfo))
    implicit val arbReq: Arbitrary[Resource[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]]] = Arbitrary(genWrappedCloudFormationCustomResourceRequest[Resource[IO, *], DatabaseMetadata])

    PropF.forAllF { input: Resource[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
      val r = for {
        requestCapture <- Ref.in[Resource[IO, *], IO, CapturedRequests[IO]](List.empty)
        client = FakeS3(requestCapture)
        requestTemplate <- input
        handler <- new MySqlDatabaseInitHandlerF[IO] {
          override protected def httpClient: Resource[IO, Client[IO]] = client.pure[Resource[IO, *]]
          override protected def secretsManagerResource(implicit L: Logger[IO]): Resource[IO, SecretsManagerAlg[Kleisli[IO, Span[IO], *]]] =
            secretsManagerAlg().mapK(Kleisli.liftK[IO, Span[IO]]).pure[Resource[IO, *]]
        }.handler
        secrets <- Resource.eval(requestTemplate.ResourceProperties.secretIds.traverse(secretsManagerAlg().getSecretAs[UserConnectionInfo]))
      } yield (requestCapture, requestTemplate.applyLens(requestTypeLens).set(_), handler, secrets)

      r.use { case (requestCapture, requestTemplate, handler, secrets) =>
        if (!containsInputError(secrets))
          for {
            _ <- handler(LambdaEnv.pure(requestTemplate(CloudFormationRequestType.CreateRequest), TestContext[IO]))
            _ <- requestCapture.get.flatMap(expectNSuccessfulResponses(1))
            _ <- handler(LambdaEnv.pure(requestTemplate(CloudFormationRequestType.DeleteRequest), TestContext[IO]))
            _ <- requestCapture.get.flatMap(expectNSuccessfulResponses(2))
          } yield ()
        else
          for {
            _ <- handler(LambdaEnv.pure(requestTemplate(CloudFormationRequestType.CreateRequest), TestContext[IO]))
            _ <- requestCapture.get.flatMap { req =>
              expectDuplicateUsersError(req).whenA(containsDuplicateUsers(secrets)) >>
                expectReservedIdentifiersError(req).whenA(containsReservedIdentifiers(secrets))
            }
          } yield ()
      }
    }
  }

  private def containsInputError(secrets: List[UserConnectionInfo]): Boolean =
    List(
      containsDuplicateUsers,
      containsReservedIdentifiers,
    ).foldMap(_(secrets))(Monoid.instance(false, _ || _))

  private val containsReservedIdentifiers: List[UserConnectionInfo] => Boolean =
    _.map(_.user.value).exists(ReservedWords.contains)

  private val containsDuplicateUsers: List[UserConnectionInfo] => Boolean =
    _.groupBy(_.user).values.exists(_.length > 1)

  def expectDuplicateUsersError: CapturedRequests[IO] => IO[Unit] =
    expectErrorMessageStartsWith("The specified secrets refer to users that share database and usernames. Deduplicate the input and try again.")

  def expectReservedIdentifiersError: CapturedRequests[IO] => IO[Unit] =
    expectErrorMessageStartsWith("The specified secrets refer to usernames that are MySQL reserved words. Change or remove those users from the input and try again.")

  def expectErrorMessageStartsWith(msg: String)
                                  (requests: CapturedRequests[IO]): IO[Unit] = IO {
    val requestInTuple = GenLens[(Request[IO], Json)](_._2)
    val findRequest = Optional[CapturedRequests[IO], (Request[IO], Json)](_.headOption)(t => l => t :: l.tail).composeLens(requestInTuple)

    val status = root.Status.as[RequestResponseStatus]
    val reason = root.Reason.as[String]

    expect((findRequest composeOptional status).getOption(requests) == Failed.some)
    expect((reason compose findRequest).getOption(requests).exists(_.startsWith(msg)))
  }

  def expectNSuccessfulResponses(n: Int)
                                (requests: CapturedRequests[IO]): IO[Unit] = IO {
    expect(requests.length == n)
    expect(requests.forall { case (_, json) => json.as[CloudFormationCustomResourceResponse].map(_.Status) == Right(Success) })
  }

}

class FakeS3[F[_] : MonadThrow](capture: Ref[F, CapturedRequests[F]])
                               (implicit SC: fs2.Compiler[F, F]) extends Http4sDsl[F] {
  import io.circe.jawn.CirceSupportParser.facade

  private[FakeS3] val captureRequestsAndRespondWithOk: HttpApp[F] =
    HttpApp { req =>
      for {
        body <- req.body.compile.to(Array).flatMap(Parser.parseFromByteArray(_).liftTo[F])
        _ <- capture.update(_ :+ (req, body))
        resp <- Ok()
      } yield resp
    }
}

object FakeS3 {
  type CapturedRequests[F[_]] = List[(Request[F], Json)]

  def apply[F[_] : Async](capture: Ref[F, CapturedRequests[F]]): Client[F] =
    Client.fromHttpApp(new FakeS3(capture).captureRequestsAndRespondWithOk)
}

class FakeSecretsManagerAlg[F[_] : MonadThrow](secrets: Ref[F, Map[SecretId, String]]) extends SecretsManagerAlg[F] {
  override def getSecret(secretId: SecretId): F[String] =
    secrets.get.map(_(secretId))

  override def getSecretAs[A: Decoder](secretId: SecretId): F[A] =
    for {
      secretString <- getSecret(secretId)
      secretJson <- parser.parse(secretString).liftTo[F]
      a <- secretJson.as[A].liftTo[F]
    } yield a

  override def createSecret(name: SecretName, secret: String): F[SecretId] =
    secrets.update(_ + (SecretId(name.value) -> secret)).as(SecretId(name.value))

  override def deleteSecret(id: SecretId, deletionTimeFrame: SecretDeletionRecoveryTime): F[Unit] =
    secrets.update(_ - id)
}

object AnsiColorCodes {
  val red = "\u001b[31m"
  val reset = "\u001b[0m"
}
