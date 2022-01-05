//package com.dwolla.mysql.init
//package repositories
//
//import cats.MonadThrow
//import cats.data._
//import cats.effect._
//import cats.effect.std.Console
//import cats.syntax.all._
//import fs2.io.net.Network
//import natchez.Trace
//import skunk._
//import skunk.util.Typer
//
//trait CreateSkunkSession[F[_]] {
//  def single(host: String,
//             port: Int = 5432,
//             user: String,
//             database: String,
//             password: Option[String] = none,
//             debug: Boolean = false,
//             strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
//             ssl: SSL = SSL.None,
//             parameters: Map[String, String] = Session.DefaultConnectionParameters,
//             commandCache: Int = 1024,
//             queryCache: Int = 1024,
//            ): Resource[F, Session[F]]
//}
//
//object CreateSkunkSession {
//  type InSession[F[_], A] = Kleisli[F, Session[F], A]
//
//  implicit class InSessionOps[F[_], A](val kleisli: Kleisli[F, Session[F], A]) extends AnyVal {
//    def inSession(host: Host,
//                  port: Port,
//                  username: MasterDatabaseUsername,
//                  password: MasterDatabasePassword,
//                 )
//                 (implicit
//                  `🦨`: CreateSkunkSession[F],
//                  `[]`: MonadCancelThrow[F]): F[A] =
//      CreateSkunkSession[F].single(
//        host = host.value,
//        port = port.value,
//        user = username.value,
//        database = "postgres",
//        password = password.value.some,
//        ssl = SSL.System,
//      ).use(kleisli.run)
//  }
//
//  implicit class IgnoreErrorOps[F[_], A](val fa: F[A]) extends AnyVal {
//    def recoverUndefinedAs(a: A)
//                          (implicit `[]`: MonadThrow[F]): F[A] =
//      fa.recover {
//        case SqlState.UndefinedObject(_) => a
//        case SqlState.InvalidCatalogName(_) => a
//      }
//  }
//
//  def apply[F[_] : CreateSkunkSession]: CreateSkunkSession[F] = implicitly
//
//  implicit def instance[F[_] : Concurrent : Trace : Network : Console]: CreateSkunkSession[F] =
//    Session.single _
//}
