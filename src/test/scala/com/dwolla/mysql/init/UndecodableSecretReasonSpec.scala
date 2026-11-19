package com.dwolla.mysql.init

import cats.effect._
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.dwolla.mysql.init.aws.SecretsManagerAlg
import doobie.LogHandler
import eu.timepit.refined.auto._
import feral.lambda.cloudformation._
import feral.lambda.{LambdaEnv, TestContext}
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.syntax.all._
import org.http4s.{HttpApp, Response, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model.{GetSecretValueRequest, GetSecretValueResponse}

import java.util.concurrent.CompletableFuture

class UndecodableSecretReasonSpec extends CatsEffectSuite {
  private implicit val logger: Logger[IO] = NoOpLogger[IO]
  private implicit val logHandler: LogHandler = LogHandler.nop
  private implicit val transactorFactory: TransactorFactory[IO] = _ => InMemoryJdbc.transactor(_ => 0)

  private val passwordMarker = "made-up-password-marker"

  private def secretWithPassword(password: String): String =
    Json.obj(
      "database" -> "made_up_db".asJson,
      "host" -> "made-up-host.example".asJson,
      "port" -> 3306.asJson,
      "user" -> "made_up_user".asJson,
      "password" -> password.asJson,
    ).noSpaces

  // A single quote is outside the characters a generated password may hold, so this fails to decode.
  private val secretWithRejectedPassword: String = secretWithPassword(s"$passwordMarker'")

  private val secretThatIsNotJson: String = s"user=made_up_user password=$passwordMarker"

  test("a user secret that decodes is read in full") {
    SecretsManagerAlg[IO](clientReturning(secretWithPassword(passwordMarker)))
      .getSecretAs[UserConnectionInfo](SecretId("made-up-secret-id"))
      .map { info =>
        assertEquals(
          (info.database.value, info.host.value, info.port.value, info.user.value, info.password.value),
          ("made_up_db", "made-up-host.example", 3306, "made_up_user", passwordMarker)
        )
      }
  }

  List(
    CloudFormationRequestType.CreateRequest,
    CloudFormationRequestType.UpdateRequest,
    CloudFormationRequestType.DeleteRequest,
  ).foreach { requestType =>
    test(s"$requestType: a user secret whose password does not decode is reported by secret id and field, without its value") {
      responseFor(requestType, secretWithRejectedPassword).map { response =>
        assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
        assertEquals(
          response.hcursor.get[String]("Reason"),
          Right("Could not decode secret made-up-secret-id: the value at .password is missing or invalid")
        )
        assert(!response.noSpaces.contains(passwordMarker), "the response holds the secret's password")
      }
    }

    test(s"$requestType: a user secret that is not JSON is reported by secret id, without its contents") {
      responseFor(requestType, secretThatIsNotJson).map { response =>
        assertEquals(response.hcursor.get[String]("Status"), Right("FAILED"))
        assertEquals(
          response.hcursor.get[String]("Reason"),
          Right("Could not decode secret made-up-secret-id: the secret is not valid JSON")
        )
        assert(!response.noSpaces.contains(passwordMarker), "the response holds the secret's contents")
      }
    }
  }

  private def responseFor(requestType: CloudFormationRequestType, secretString: String): IO[Json] =
    Dispatcher[IO].use { implicit dispatcher =>
      for {
        captured <- Deferred[IO, Json]
        handler = MySqlDatabaseInitHandlerImpl[IO](SecretsManagerAlg[IO](clientReturning(secretString)))
        _ <- {
          implicit val env: LambdaEnv[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] =
            LambdaEnv.pure(request(requestType), TestContext[IO])
          CloudFormationCustomResource(capturingClient(captured), handler)
        }
        response <- captured.get
      } yield response
    }

  private def clientReturning(secretString: String): SecretsManagerAsyncClient = new SecretsManagerAsyncClient {
    override def serviceName(): String = SecretsManagerAsyncClient.SERVICE_NAME
    override def close(): Unit = ()
    override def getSecretValue(request: GetSecretValueRequest): CompletableFuture[GetSecretValueResponse] =
      CompletableFuture.completedFuture(GetSecretValueResponse.builder().secretString(secretString).build())
  }

  private def capturingClient(captured: Deferred[IO, Json]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      req.as[String]
        .flatMap(io.circe.parser.parse(_).liftTo[IO])
        .flatMap(captured.complete)
        .as(Response[IO](Status.Ok))
    })

  private def request(requestType: CloudFormationRequestType) =
    CloudFormationCustomResourceRequest(
      requestType,
      uri"https://made-up.example/response",
      StackId("made-up-stack"),
      RequestId("made-up-request"),
      ResourceType("Custom::MySqlDatabase"),
      LogicalResourceId("MadeUpResource"),
      None,
      DatabaseMetadata(
        Host("made-up-host.example"),
        Port(3306),
        Database("made_up_db"),
        MasterDatabaseUsername("made_up_master"),
        MasterDatabasePassword("made-up-master-password"),
        List(SecretId("made-up-secret-id")),
      ),
      None,
    )
}
