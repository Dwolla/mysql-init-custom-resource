package com.dwolla.mysql.init

import cats._
import cats.effect.std.Dispatcher
import cats.effect.syntax.all._
import cats.syntax.all._
import com.dwolla.mysql.init.repositories.MySqlErrorCodes.ER_NONEXISTING_GRANT
import doobie._
import doobie.syntax.all._
import doobie.free.connection
import org.typelevel.log4cats.Logger

package object repositories {
  def quotedIdentifier(s: String): Fragment =
    fr0"`" ++ Fragment.const0(s) ++ fr"`"

  def quotedIdentifier0(s: String): Fragment =
    fr0"`" ++ Fragment.const0(s) ++ fr0"`"

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
      fa.exceptSql {
        case ex if ex.getErrorCode == ER_NONEXISTING_GRANT => a.pure[F]
        case ex => ex.raiseError
      }
  }
}

package repositories {
  object MySqlErrorCodes {
    val ER_NONEXISTING_GRANT = 1141
  }
}
