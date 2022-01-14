package com.dwolla.mysql.init

import cats.data._
import cats.effect.std._
import cats.effect.{Trace => _, _}
import cats.tagless.FunctorK.ops.toAllFunctorKOps
import com.dwolla.mysql.init.aws.SecretsManagerAlg
import com.dwolla.tracing._
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

class MySqlDatabaseInitHandler
  extends IOLambda[CloudFormationCustomResourceRequest[DatabaseMetadata], INothing] {

  private def resources[F[_] : Async]: Resource[F, LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] => F[Option[INothing]]] =
    for {
      implicit0(logger: Logger[F]) <- Resource.eval(Slf4jLogger.create[F])
      implicit0(random: Random[F]) <- Resource.eval(Random.scalaUtilRandom[F])
      implicit0(dispatcher: Dispatcher[Kleisli[F, Span[F], *]]) <- Dispatcher[Kleisli[F, Span[F], *]].mapK(Kleisli.applyK(NoopSpan()))
      client <- httpClient[F]
      entryPoint <- XRayEnvironment[Resource[F, *]].daemonAddress.flatMap {
        case Some(addr) => XRay.entryPoint(addr)
        case None => XRay.entryPoint[F]()
      }
      secretsManager <- SecretsManagerAlg.resource[F].map(_.mapK(Kleisli.liftK[F, Span[F]]).withTracing)
    } yield { implicit env: LambdaEnv[F, CloudFormationCustomResourceRequest[DatabaseMetadata]] =>
      implicit val transactorFactory: TransactorFactory[Kleisli[F, Span[F], *]] = TransactorFactory.tracedInstance[F]("MySqlDatabaseInitHandler")

      TracedHandler(entryPoint, Kleisli { (span: Span[F]) =>
        CloudFormationCustomResource(tracedHttpClient(client, span), MySqlDatabaseInitHandlerImpl(secretsManager)).run(span)
      })
    }

  override def handler: Resource[IO, LambdaEnv[IO, CloudFormationCustomResourceRequest[DatabaseMetadata]] => IO[Option[INothing]]] =
    resources[IO]

  private implicit def kleisliLogger[F[_] : Logger, A]: Logger[Kleisli[F, A, *]] = Logger[F].mapK(Kleisli.liftK)

  /**
   * The XRay kernel comes from environment variables, so we don't need to extract anything from the incoming event
   */
  private implicit def kernelSource[Event]: KernelSource[Event] = KernelSource.emptyKernelSource

  private def httpClient[F[_] : Async]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = false))

  private def tracedHttpClient[F[_] : MonadCancelThrow](client: Client[F], span: Span[F]): Client[Kleisli[F, Span[F], *]] =
    NatchezMiddleware.client(client.translate(Kleisli.liftK[F, Span[F]])(Kleisli.applyK(span)))

}
