package com.dwolla.mysql.init
package repositories

import cats.ApplicativeThrow
import cats.effect.std.Dispatcher
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import doobie._
import doobie.implicits._
import eu.timepit.refined.refineV
import org.typelevel.log4cats.Logger

trait RoleRepository[F[_]] {
  def createRole(database: Database): F[Unit]
  def removeRole(database: Database): F[Unit]
  def addUserToRole(username: Username, database: Database): F[Unit]
  def removeUserFromRole(username: Username, database: Database): F[Unit]
}

object RoleRepository {
  implicit val RoleRepositoryInstrument: Instrument[RoleRepository] = Derive.instrument

  def roleNameForDatabase[F[_] : ApplicativeThrow](database: Database): F[RoleName] =
    refineV[MySqlUserPredicate](database.value + "_role")
      .map(RoleName(_))
      .leftMap(new RuntimeException(_))
      .liftTo[F]

  def apply[F[_] : Logger : Dispatcher]: RoleRepository[ConnectionIO] = new RoleRepository[ConnectionIO] {
    override def createRole(database: Database): ConnectionIO[Unit] =
      roleNameForDatabase[ConnectionIO](database).flatMap { role =>
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

    override def removeRole(database: Database): ConnectionIO[Unit] =
      roleNameForDatabase[ConnectionIO](database).flatMap { roleName =>

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
      roleNameForDatabase[ConnectionIO](database).flatMap { role =>
        RoleQueries.grantRole(username, role)
          .run
          .flatTap { completion =>
            Logger[ConnectionIO].info(s"added $username to role $role with status $completion")
          }
          .void
      }

    override def removeUserFromRole(username: Username, database: Database): ConnectionIO[Unit] =
      roleNameForDatabase[ConnectionIO](database).flatMap { role =>
        RoleQueries.revokeRole(username, role)
          .run
          .flatTap { completion =>
            Logger[ConnectionIO].info(s"revoked role $role from $username with status $completion")
          }
          .void
          .recoverUndefinedAs(())
      }
  }
}

object RoleQueries {
  def grantRole(userName: Username,
                role: RoleName): Update0 =
    (fr"GRANT" ++ Fragment.const(role.value) ++ fr"TO" ++ Fragment.const(userName.value))
      .update

  def revokeRole(userName: Username,
                 role: RoleName): Update0 =
    (fr"REVOKE" ++ Fragment.const(role.value) ++ fr"FROM" ++ Fragment.const(userName.value))
      .update

  // Pretty sure roles and users are stored in the same table
  def countRoleByName(roleName: RoleName): Query0[Long] =
    sql"SELECT count(*) as count FROM mysql.user WHERE user = ${roleName.value}"
      .query[Long]

  def createRole(role: RoleName): Update0 =
    (fr"CREATE ROLE" ++ Fragment.const(role.value))
      .update

  // I think we need to run FLUSH PRIVILEGES to save these changes without restarting mysql
  def grantPrivilegesToRole(database: Database, role: RoleName): Update0 =
    (fr"GRANT ALL PRIVILEGES ON" ++ quotedIdentifier0(database.value) ++ fr".* TO" ++ Fragment.const(role.value))
      .update

  def revokePrivilegesFromRole(database: Database, role: RoleName): Update0 =
    (fr"REVOKE ALL PRIVILEGES ON" ++ quotedIdentifier0(database.value) ++ fr".* FROM" ++ Fragment.const(role.value))
      .update

  def dropRole(role: RoleName): Update0 =
    (fr"DROP ROLE IF EXISTS" ++ Fragment.const(role.value))
      .update

}
