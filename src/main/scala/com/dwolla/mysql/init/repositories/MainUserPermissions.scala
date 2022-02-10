package com.dwolla.mysql.init
package repositories

import cats._
import cats.data._
import cats.effect.std.Dispatcher
import cats.syntax.all._
import doobie._
import doobie.syntax.all._
import org.typelevel.log4cats.Logger
import eu.timepit.refined.auto._

trait MainUserPermissions[F[_]] {
  def ensureMainUserHasGrantPermissions(user: MasterDatabaseUsername): F[Unit]
  def getGrantsForUser(user: MasterDatabaseUsername): F[Grants]
}

object MainUserPermissions {
  private val ROLE_ADMIN = Grant("ROLE_ADMIN")
  private val SUPER = Grant("SUPER")

  def apply[F[_] : Logger : Dispatcher](implicit logHandler: LogHandler): MainUserPermissions[ConnectionIO] = new MainUserPermissions[ConnectionIO] {
    override def getGrantsForUser(user: MasterDatabaseUsername): doobie.ConnectionIO[Grants] =
      MainUserPermissionQueries
        .existingGrants(user)
        .to[Vector]
        .map(_.foldA[Id, Grants])

    override def ensureMainUserHasGrantPermissions(user: MasterDatabaseUsername): ConnectionIO[Unit] =
      getGrantsForUser(user)
        .map(_.containsAny(ROLE_ADMIN, SUPER))
        .ifM(
          Logger[ConnectionIO].info(s"$user already has the privileges needed to GRANT roles to users"),
          addRoleAdminTo(user).void
        )

    private def addRoleAdminTo(user: MasterDatabaseUsername): ConnectionIO[Int] =
      Logger[ConnectionIO].info(s"GRANTing ROLE_ADMIN to $user") >>
        MainUserPermissionQueries.addRoleAdminTo(user).run
  }
}

sealed trait Grants {
  def containsAny(grants: Grant*): Boolean
}
case object NoGrants extends Grants {
  override def containsAny(grants: Grant*): Boolean = false
}
case class HasGrants(value: NonEmptyList[Grant]) extends Grants {
  override def containsAny(grants: Grant*): Boolean = grants.exists(value.contains_(_))
  override def toString: String =
    s"""Grants:
       |${value.mkString_(" - ", "\n - ", "")}
       |""".stripMargin
}
object Grants {
  implicit val eqGrants: Eq[Grants] = Eq.by {
    case HasGrants(grants) => grants.toList
    case NoGrants => List.empty
  }

  implicit val grantsMonoid: Monoid[Grants] = Monoid.instance(NoGrants, {
    case (HasGrants(a), HasGrants(b)) => HasGrants(a |+| b)
    case (a: HasGrants, NoGrants) => a
    case (NoGrants, a: HasGrants) => a
    case (NoGrants, NoGrants) => NoGrants
  })

  implicit val getGrants: Get[Grants] = Get[String].temap {
    GrantParser.fullParser
      .parse(_)
      .map(_._2)
      .map(HasGrants(_))
      .leftMap(_.toString)
  }
}

object MainUserPermissionQueries {
  def existingGrants(user: MasterDatabaseUsername)
                    (implicit logHandler: LogHandler): Query0[Grants] =
    sql"SHOW GRANTS FOR ${user.value}".query[Grants]

  // TODO could we use RoleRepository for this?
  def addRoleAdminTo(user: MasterDatabaseUsername)
                    (implicit logHandler: LogHandler): Update0 =
    (fr"GRANT ROLE_ADMIN ON *.* TO" ++ Fragment.const(user.value))
      .update
}
