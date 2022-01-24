package com.dwolla.mysql.init

import cats._
import cats.effect._
import cats.syntax.all._
import com.dwolla.mysql.init.FakeS3.CapturedRequests
import com.dwolla.mysql.init.aws.SecretDeletionRecoveryTime.Immediate
import com.dwolla.mysql.init.aws.SecretsManagerAlg
import com.dwolla.mysql.init.aws.SecretsManagerAlg.SecretNamePredicate
import com.dwolla.testutils.IntegrationTest
import com.eed3si9n.expecty.Expecty.expect
import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import feral.lambda.cloudformation.RequestResponseStatus.Success
import feral.lambda.cloudformation.{CloudFormationCustomResourceArbitraries, CloudFormationCustomResourceRequest, CloudFormationCustomResourceResponse, CloudFormationRequestType}
import feral.lambda.{LambdaEnv, TestContext}
import io.circe.{Decoder, Encoder, Json}
import monocle.syntax.all._
import munit._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, Request}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.jawn.Parser
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import scala.concurrent.duration._

class RoundTripIntegrationTest
  extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with CatsEffectFixtures
    with CloudFormationCustomResourceArbitraries
    with ArbitraryRefinedTypes {

  override def munitTimeout: Duration = 10.minutes

  private implicit def logger[F[_] : Applicative]: Logger[F] = NoOpLogger[F]

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
      host <- Gen.const(Host("localhost"))
      port <- Gen.const(Port(3306))
      user <- arbitrary[SqlIdentifier].map(Username(_))
      password <- arbitrary[GeneratedPassword].map(Password(_))
    } yield UserConnectionInfo(database, host, port, user, password)

  private def genDatabaseMetadata[F[_] : Monad, A : Encoder](secretsAlg: SecretsManagerAlg[F],
                                                             genA: Option[Database] => Gen[A]): Gen[Resource[F, DatabaseMetadata]] = {
    for {
      host <- Gen.const(Host("localhost"))
      port <- Gen.const(Port(3306))
      database <- arbitrary[SqlIdentifier].map(Database(_))
      username <- Gen.const[SqlIdentifier]("root").map(MasterDatabaseUsername(_))
      password <- Gen.const("password").map(MasterDatabasePassword(_))
      secrets <- {
        implicit val arbA: Arbitrary[A] = Arbitrary(genA(database.some))
        implicit val arbSecret: Arbitrary[Resource[F, SecretId]] = Arbitrary(genSecret[F, A](secretsAlg))

        genResources[F, SecretId]()
      }
    } yield secrets.map(DatabaseMetadata(host, port, database, username, password, _))
  }

  private val secretsManagerAlg: Fixture[SecretsManagerAlg[IO]] = ResourceSuiteLocalFixture(
    "SecretsManagerAlg",
    SecretsManagerAlg.resource[IO]
  )

  override def munitFixtures = List(secretsManagerAlg)

  test("Handler can create and destroy a database with users".tag(IntegrationTest)) {
    implicit val arbDatabaseMetadata: Arbitrary[Resource[IO, DatabaseMetadata]] = Arbitrary(genDatabaseMetadata[IO, UserConnectionInfo](secretsManagerAlg(), genUserConnectionInfo))
    implicit val arbReq: Arbitrary[Resource[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]]] = Arbitrary(genWrappedCloudFormationCustomResourceRequest[Resource[IO, *], DatabaseMetadata])

    PropF.forAllF { secrets: Resource[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
      val r = for {
        requestCapture <- Ref.in[Resource[IO, *], IO, CapturedRequests[IO]](List.empty)
        client = FakeS3(requestCapture)
        requestTemplate <- secrets
        handler <- new MySqlDatabaseInitHandlerF[IO] {
          override protected def httpClient: Resource[IO, Client[IO]] = client.pure[Resource[IO, *]]
        }.handler
      } yield (requestCapture, requestTemplate.focus(_.RequestType).replace(_), handler)

      r.use { case (requestCapture, requestTemplate, handler) =>
        for {
          _ <- handler(LambdaEnv.pure(requestTemplate(CloudFormationRequestType.CreateRequest), TestContext[IO]))
          _ <- requestCapture.get.flatMap(expectNSuccessfulResponses(1))
          _ <- handler(LambdaEnv.pure(requestTemplate(CloudFormationRequestType.DeleteRequest), TestContext[IO]))
          _ <- requestCapture.get.flatMap(expectNSuccessfulResponses(2))
        } yield ()
      }
    }
  }

  def expectNSuccessfulResponses(n: Int)
                                (requests: CapturedRequests[IO]): IO[Unit] = IO {
    // TODO remove this when it's fixed upstream
    implicit val decoder: Decoder[CloudFormationCustomResourceResponse] =
      CloudFormationCustomResourceResponse.CloudFormationCustomResourceResponseDecoder
        .prepare {
          _.withFocus {
            _.mapObject { obj =>
              if (obj.contains("Data")) obj
              else obj.add("Data", Json.Null)
            }
          }
        }

    expect(requests.length == n)
    if (n == 1) expect(requests.head._2.as[CloudFormationCustomResourceResponse].map(_.Status) == Right(Success))
    else expect(requests.forall { case (_, json) => json.as[CloudFormationCustomResourceResponse].map(_.Status) == Right(Success) })
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
