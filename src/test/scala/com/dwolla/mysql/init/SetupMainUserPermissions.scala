package com.dwolla.mysql.init
package repositories

import cats._
import cats.effect.std.Dispatcher
import cats.syntax.all._
import doobie._
import doobie.syntax.all._
import org.typelevel.log4cats.Logger
import eu.timepit.refined.auto._

trait SetupMainUserPermissions[F[_]] {
  def makeDatabasePermissionsEmulateRds(user: MasterDatabaseUsername): F[Unit]
}

object SetupMainUserPermissions {
  def apply[F[_] : Logger : Dispatcher](implicit logHandler: LogHandler): SetupMainUserPermissions[ConnectionIO] = new SetupMainUserPermissions[ConnectionIO] {
    private implicit def connectionIoMonoid[A: Monoid]: Monoid[ConnectionIO[A]] = Applicative.monoid
    private val queries = new SetupMainUserPermissionsQueries
    import queries._

    override def makeDatabasePermissionsEmulateRds(user: MasterDatabaseUsername): ConnectionIO[Unit] = {
      MainUserPermissions[F]
        .getGrantsForUser(user)
        .flatMap((revokeRoleAdmin |+| revokeSuper) (user)(_))
    }
  }
}

class SetupMainUserPermissionsQueries(implicit logHandler: LogHandler) {
  val revokeRoleAdmin: MasterDatabaseUsername => Grants => ConnectionIO[Unit] = user => grants =>
    sql"REVOKE ROLE_ADMIN ON *.* FROM ${user.value}".update.run.whenA(grants.containsAny(Grant("ROLE_ADMIN")))

  val revokeSuper: MasterDatabaseUsername => Grants => ConnectionIO[Unit] = user => grants =>
    sql"REVOKE SUPER ON *.* FROM ${user.value}".update.run.whenA(grants.containsAny(Grant("SUPER")))
}
