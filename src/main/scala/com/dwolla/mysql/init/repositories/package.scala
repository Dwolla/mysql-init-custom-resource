package com.dwolla.mysql.init

import cats._
import cats.effect.std.Dispatcher
import cats.effect.syntax.all._
import cats.syntax.all._
import doobie.ConnectionIO
import doobie.free.connection
import org.typelevel.log4cats.Logger

package object repositories {
  implicit def freeLogger[F[_] : Logger](implicit dispatcher: Dispatcher[F]): Logger[ConnectionIO] =
    Logger[F].mapK(new (F ~> ConnectionIO) {
      override def apply[A](fa: F[A]): ConnectionIO[A] =
        connection.delay(dispatcher.unsafeToFutureCancelable(fa))
          .flatMap { case (running, cancel) =>
            connection.fromFuture(running.pure[ConnectionIO])
              .onCancel(connection.fromFuture(connection.delay(cancel())))
          }
    })

  implicit class IgnoreErrorOps[F[_], A](val fa: F[A]) extends AnyVal {
    def recoverUndefinedAs(a: A)
                          (implicit `[]`: MonadThrow[F]): F[A] =
      fa.recover {
        case _: OutOfMemoryError => a // TODO placeholder
//        case SqlState.UndefinedObject(_) => a
//        case SqlState.InvalidCatalogName(_) => a
      }
  }
}
