package com.dwolla.mysql.init

import cats.effect._
import eu.timepit.refined.auto._
import feral.lambda.cloudformation._
import feral.lambda.{LambdaEnv, TestContext}
import org.http4s.syntax.all._

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
            Port(9999),
            Database("test"),
            MasterDatabaseUsername("dba"),
            MasterDatabasePassword("password"),
            List.empty,
          ),
          None
        )

      val env = LambdaEnv.pure[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]](input, TestContext[IO])

      handler(env)
    }.as(ExitCode.Success)
}
