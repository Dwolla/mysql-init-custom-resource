package com.dwolla.mysql.init

import cats.data._
import cats.effect.std._
import cats.effect.{Trace => _, _}
import cats.tagless.FunctorK.ops.toAllFunctorKOps
import com.dwolla.mysql.init.aws.SecretsManagerAlg
import com.dwolla.tracing._
import doobie.util.log.LogHandler
import feral.lambda.cloudformation._
import feral.lambda.{INothing, IOLambda, KernelSource, LambdaEnv, TracedHandler}
import natchez._
import natchez.http4s.NatchezMiddleware
import natchez.noop.NoopSpan
import natchez.xray.{XRay, XRayEnvironment}
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class MySqlDatabaseInitHandler extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], INothing] {
  override def handler: Resource[IO, LambdaEnv[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] => IO[Option[INothing]]] =
    new MySqlDatabaseInitHandlerF[IO].handler
}

class MySqlDatabaseInitHandlerF[F[_] : Async](implicit logHandler: LogHandler = LogHandler.nop) {
  def handler: Resource[F, LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] => F[Option[INothing]]] =
    for {
      implicit0(logger: Logger[F]) <- Resource.eval(Slf4jLogger.create[F])
      implicit0(random: Random[F]) <- Resource.eval(Random.scalaUtilRandom[F])
      implicit0(dispatcher: Dispatcher[Kleisli[F, Span[F], *]]) <- Dispatcher[Kleisli[F, Span[F], *]].mapK(Kleisli.applyK(NoopSpan()))
      client <- httpClient
      entryPoint <- XRayEnvironment[Resource[F, *]].daemonAddress.flatMap {
        case Some(addr) => XRay.entryPoint(addr)
        case None => XRay.entryPoint[F]()
      }
      secretsManager <- secretsManagerResource
    } yield { implicit env: LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
      implicit val transactorFactory: TransactorFactory[Kleisli[F, Span[F], *]] = TransactorFactory.tracedInstance[F]("MySqlDatabaseInitHandler")

      TracedHandler(entryPoint, Kleisli { (span: Span[F]) =>
        CloudFormationCustomResource(tracedHttpClient(client, span), MySqlDatabaseInitHandlerImpl(secretsManager)).run(span)
      })
    }

  /**
   * The XRay kernel comes from environment variables, so we don't need to extract anything from the incoming event
   */
  private implicit def kernelSource[Event]: KernelSource[Event] = KernelSource.emptyKernelSource

  protected def secretsManagerResource(implicit L: Logger[F]): Resource[F, SecretsManagerAlg[Kleisli[F, Span[F], *]]] =
    SecretsManagerAlg.resource[F].map(_.mapK(Kleisli.liftK[F, Span[F]]).withTracing)

  protected def httpClient: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = true))

  private def tracedHttpClient(client: Client[F], span: Span[F]): Client[Kleisli[F, Span[F], *]] =
    NatchezMiddleware.client(client.translate(Kleisli.liftK[F, Span[F]])(Kleisli.applyK(span)))

}
