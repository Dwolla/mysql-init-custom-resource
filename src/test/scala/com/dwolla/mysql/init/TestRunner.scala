package com.dwolla.mysql.init

import cats.effect._
import eu.timepit.refined.auto._
import feral.lambda.cloudformation._
import feral.lambda.{LambdaEnv, TestContext}
import org.http4s.syntax.all._

/**
 * Start a MySQL or MariaDB docker container first:
 * {{{
 *docker run \
 *  --detach \
 *  --rm \
 *  --interactive \
 *  --tty \
 *  --name mariadb \
 *  --env MARIADB_ROOT_PASSWORD=password \
 *  --publish 3306:3306 \
 *  mariadb:latest
 * }}}
 */
object TestRunner extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    new MySqlDatabaseInitHandler().handler.use { handler =>
      val input =
        CloudFormationCustomResourceRequest(
          CloudFormationRequestType.DeleteRequest,
          uri"https://webhook.site/", // TODO go to webhook.site and generate a new URL for catching the responses. Don't use this for anything sensitive!
          StackId("stack-id"),
          RequestId("request-id"),
          ResourceType("Custom::MySqlDatabase"),
          LogicalResourceId("Test"),
          None,
          DatabaseMetadata(
            Host("localhost"),
            Port(3306),
            Database("test"),
            MasterDatabaseUsername("root"),
            MasterDatabasePassword("password"),
            List.empty,
          ),
          None
        )

      val env = LambdaEnv.pure[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]](input, TestContext[IO])

      handler(env)
    }.as(ExitCode.Success)
}
