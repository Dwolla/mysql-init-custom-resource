package com.dwolla.mysql.init

import cats.data.Kleisli
import cats.effect._
import cats.syntax.all._
import munit.CatsEffectSuite
import natchez.Span
import natchez.noop.NoopSpan
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.WriterAppender
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.{Level, LogManager}
import org.http4s._
import org.http4s.client.Client
import org.http4s.syntax.all._
import org.typelevel.ci._
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.io.StringWriter

class HttpClientLoggingSpec extends CatsEffectSuite {
  private val requestHeaderMarker = "made-up-request-header-marker"
  private val requestBodyMarker = "made-up-request-body-marker"
  private val responseHeaderMarker = "made-up-response-header-marker"
  private val responseBodyMarker = "made-up-response-body-marker"
  private val controlMarker = "made-up-control-log-marker"

  test("the HTTP client's middleware logs neither the headers nor the bodies of requests and responses") {
    val span = NoopSpan[IO]()

    CapturedLogs.atProductionLevel.use { logs =>
      for {
        _ <- Slf4jLogger.getLoggerFromName[IO]("HttpClientLoggingSpec").info(controlMarker)
        responseBody <- MySqlDatabaseInitHandlerF.decorate(inMemoryClient, span).expect[String] {
          Request[Kleisli[IO, Span[IO], *]](Method.POST, uri"https://made-up.example/")
            .withEntity(requestBodyMarker)
            .putHeaders(Header.Raw(ci"X-Made-Up-Request", requestHeaderMarker))
        }.run(span)
        output <- logs
      } yield {
        assertEquals(responseBody, responseBodyMarker)
        assert(output.contains(controlMarker), "the log capture did not record the control line")
        assertEquals(
          List(requestHeaderMarker, requestBodyMarker, responseHeaderMarker, responseBodyMarker).filter(output.contains),
          List.empty[String],
        )
      }
    }
  }

  private val inMemoryClient: Client[IO] =
    Client.fromHttpApp {
      HttpApp[IO] { req =>
        req.as[String].as {
          Response[IO](Status.Ok)
            .withEntity(responseBodyMarker)
            .putHeaders(Header.Raw(ci"X-Made-Up-Response", responseHeaderMarker))
        }
      }
    }
}

/**
 * Records everything logged through log4j2 while the resource is open, with the
 * root logger at INFO, the level `src/main/resources/log4j2.xml` sets for the Lambda.
 */
object CapturedLogs {
  private val appenderName = "HttpClientLoggingSpecCapture"

  /**
   * Another suite can start log4j2 at the same moment, and `getContext` then returns
   * a context that is still loading its configuration; an appender added to it now
   * would be dropped when that configuration replaces the current one.
   */
  private def startedContext: IO[LoggerContext] =
    IO(LogManager.getContext(false).asInstanceOf[LoggerContext]).flatMap { context =>
      if (context.isStarted) context.pure[IO]
      else IO.cede >> startedContext
    }

  def atProductionLevel: Resource[IO, IO[String]] =
    Resource
      .make {
        startedContext.map { context =>
          val root = context.getConfiguration.getRootLogger
          val previousLevel = root.getLevel
          val writer = new StringWriter()
          val appender = WriterAppender.createAppender(PatternLayout.createDefaultLayout(), null, writer, appenderName, false, true)
          appender.start()
          root.addAppender(appender, Level.ALL, null)
          root.setLevel(Level.INFO)
          context.updateLoggers()
          (context, appender, previousLevel, writer)
        }
      } { case (context, appender, previousLevel, _) =>
        IO {
          val root = context.getConfiguration.getRootLogger
          root.removeAppender(appenderName)
          root.setLevel(previousLevel)
          context.updateLoggers()
          appender.stop()
        }
      }
      .map { case (_, _, _, writer) => IO(writer.toString) }
}
