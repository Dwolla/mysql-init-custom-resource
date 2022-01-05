package com.dwolla.mysql.init
package repositories

import cats.effect.std.Dispatcher
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import doobie._
import doobie.implicits._
import eu.timepit.refined.api.Refined
import org.typelevel.log4cats.Logger

trait RoleRepository[F[_]] {
  def createRole(database: Database): F[Unit]
  def removeRole(database: Database): F[Unit]
  def addUserToRole(username: Username, database: Database): F[Unit]
  def removeUserFromRole(username: Username, database: Database): F[Unit]
}

object RoleRepository {
  implicit val RoleRepositoryInstrument: Instrument[RoleRepository] = Derive.instrument

  def roleNameForDatabase(database: Database): RoleName =
    RoleName(Refined.unsafeApply(database.value + "_role"))

  def apply[F[_] : Logger : Dispatcher]: RoleRepository[ConnectionIO] = new RoleRepository[ConnectionIO] {
    override def createRole(database: Database): ConnectionIO[Unit] = {
      val role = roleNameForDatabase(database)

      checkRoleExists(role)
        .ifM(createEmptyRole(role) >> grantPrivileges(role, database), Logger[ConnectionIO].info(s"No-op: role $role already exists"))
    }

    private def checkRoleExists(role: RoleName): ConnectionIO[Boolean] =
      RoleQueries.countRoleByName(role)
        .unique
        .flatTap { count =>
          Logger[ConnectionIO].info(s"found $count roles named $role")
        }
        .map(_ == 0)

    private def createEmptyRole(role: RoleName): ConnectionIO[Unit] =
      RoleQueries.createRole(role)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"created role $role with status $completion")
        }
        .void

    private def grantPrivileges(role: RoleName, database: Database): ConnectionIO[Unit] =
      RoleQueries.grantPrivilegesToRole(database, role)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"granted privileges to $role on $database with status $completion")
        }
        .void

    private def revokePrivileges(role: RoleName, database: Database): ConnectionIO[Unit] =
      RoleQueries.revokePrivilegesFromRole(database, role)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"revoked privileges from $role on $database with status $completion")
        }
        .void
        .recoverUndefinedAs(())

    override def removeRole(database: Database): ConnectionIO[Unit] = {
      val roleName = roleNameForDatabase(database)

      revokePrivileges(roleName, database) >> dropRole(roleName)
    }

    private def dropRole(roleName: RoleName): ConnectionIO[Unit] =
      RoleQueries.dropRole(roleName)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"dropped role $roleName with status $completion")
        }
        .void
        .recoverUndefinedAs(())

    override def addUserToRole(username: Username, database: Database): ConnectionIO[Unit] =
      RoleQueries.grantRole(username, roleNameForDatabase(database))
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"added $username to role ${roleNameForDatabase(database)} with status $completion")
        }
        .void

    override def removeUserFromRole(username: Username, database: Database): ConnectionIO[Unit] =
      RoleQueries.revokeRole(username, roleNameForDatabase(database))
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"revoked role ${roleNameForDatabase(database)} from $username with status $completion")
        }
        .void
        .recoverUndefinedAs(())
  }
}

// TODO MySQL queries
object RoleQueries {
  def grantRole(userName: Username,
                role: RoleName): Update0 =
    sql"GRANT #${userName.value} TO #${role.value}"
      .update

  def revokeRole(userName: Username,
                 role: RoleName): Update0 =
    sql"REVOKE #${userName.value} FROM #${role.value}"
      .update

  def countRoleByName(roleName: RoleName): Query0[Long] =
    sql"SELECT count(*) as count FROM pg_catalog.pg_roles WHERE rolname = ${roleName.value}"
      .query[Long]

  def createRole(role: RoleName): Update0 =
    sql"CREATE ROLE #${role.value}"
      .update

  def grantPrivilegesToRole(database: Database, role: RoleName): Update0 =
    sql"GRANT ALL PRIVILEGES ON DATABASE #${database.value} TO #${role.value}"
      .update

  def revokePrivilegesFromRole(database: Database, role: RoleName): Update0 =
    sql"REVOKE ALL PRIVILEGES ON DATABASE #${database.value} FROM #${role.value}"
      .update

  def dropRole(role: RoleName): Update0 =
    sql"DROP ROLE IF EXISTS #${role.value}"
      .update

}
