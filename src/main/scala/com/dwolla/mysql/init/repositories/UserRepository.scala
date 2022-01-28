package com.dwolla.mysql.init
package repositories

import cats.effect.std.Dispatcher
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import doobie._
import doobie.implicits._
import eu.timepit.refined.api.Refined
import org.typelevel.log4cats._

trait UserRepository[F[_]] {
  def addOrUpdateUser(userConnectionInfo: UserConnectionInfo): F[Username]
  def removeUser(username: Username): F[Username]
}

object UserRepository {
  implicit val UserRepositoryInstrument: Instrument[UserRepository] = Derive.instrument

  def usernameForDatabase(database: Database): Username =
    Username(Refined.unsafeApply(database.value))

  def apply[F[_] : Logger : Dispatcher](implicit logHandler: LogHandler): UserRepository[ConnectionIO] = new UserRepository[ConnectionIO] {
    override def addOrUpdateUser(userConnectionInfo: UserConnectionInfo): ConnectionIO[Username] =
      UserQueries.checkUserExists(userConnectionInfo.user)
        .unique
        .flatMap {
          case 0 => Logger[ConnectionIO].info(s"Creating user ${userConnectionInfo.user}") >> UserQueries.createUser(userConnectionInfo.user, userConnectionInfo.password).run
          case count => Logger[ConnectionIO].info(s"Found and updating $count user named ${userConnectionInfo.user}") >> UserQueries.updateUser(userConnectionInfo.user, userConnectionInfo.password).run
        }
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"upserted ${userConnectionInfo.user} with status $completion")
        }
        .as(userConnectionInfo.user)

    override def removeUser(user: Username): ConnectionIO[Username] =
      UserQueries.removeUser(user)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"removed user $user with status $completion")
        }
        .as(user)
        .recoverUndefinedAs(user)
  }
}

object UserQueries {
  // I think SELECT EXISTS(SELECT 1 FROM mysql.user WHERE user = ${username.value}") is more efficient but not sure it matters
  def checkUserExists(username: Username)
                     (implicit logHandler: LogHandler): Query0[Long] =
    sql"SELECT count(*) as count FROM mysql.user WHERE user = ${username.value}"
      .query[Long]

  def createUser(username: Username,
                 password: Password)
                (implicit logHandler: LogHandler): Update0 =
    (fr"CREATE USER" ++ quotedIdentifier(username.value) ++ fr"IDENTIFIED BY '" ++ Fragment.const(password.value) ++ fr"'")
      .update

  def updateUser(username: Username,
                 password: Password)
                (implicit logHandler: LogHandler): Update0 =
    (fr"ALTER USER" ++ quotedIdentifier(username.value) ++ fr"IDENTIFIED BY '" ++ Fragment.const(password.value) ++ fr"'")
      .update

  def removeUser(username: Username)
                (implicit logHandler: LogHandler): Update0 =
    (fr"DROP USER IF EXISTS" ++ quotedIdentifier(username.value))
      .update
}
