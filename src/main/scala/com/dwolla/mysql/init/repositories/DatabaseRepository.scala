package com.dwolla.mysql.init
package repositories

import cats.effect.std.Dispatcher
import cats.effect.{Trace => _}
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import doobie._
import doobie.implicits._
import org.typelevel.log4cats.Logger

trait DatabaseRepository[F[_]] {
  def createDatabase(db: DatabaseMetadata): F[Database]
  def removeDatabase(database: Database): F[Database]
}

object DatabaseRepository {
  implicit val DatabaseRepositoryInstrument: Instrument[DatabaseRepository] = Derive.instrument

  def apply[F[_] : Logger : Dispatcher]: DatabaseRepository[ConnectionIO] = new DatabaseRepository[ConnectionIO] {
    override def createDatabase(db: DatabaseMetadata): ConnectionIO[Database] =
      checkDatabaseExists(db)
        .ifM(createDatabase(db.name), Logger[ConnectionIO].info(s"No-op: database ${db.name} already exists"))
        .as(db.name)

    private def checkDatabaseExists(db: DatabaseMetadata): ConnectionIO[Boolean] =
      DatabaseQueries.checkDatabaseExists(db.name)
        .unique
        .flatTap { count =>
          Logger[ConnectionIO].info(s"Found $count databases matching ${db.name} on ${db.username}@${db.host}:${db.port}")
        }
        .map(_ == 0)

    private def createDatabase(database: Database): ConnectionIO[Unit] =
      DatabaseQueries.createDatabase(database)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"created database $database with status $completion")
        }
        .void

    override def removeDatabase(database: Database): ConnectionIO[Database] =
      DatabaseQueries.dropDatabase(database)
        .run
        .flatTap { completion =>
          Logger[ConnectionIO].info(s"dropped database $database with status $completion")
        }
        .as(database)
        .recoverUndefinedAs(database)
  }
}

object DatabaseQueries {
  def checkDatabaseExists(db: Database): Query0[Long] =
    sql"SELECT count(*) as count FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ${db.id.value}"
      .query[Long]

  // I don't think mysql has the concept of owners; access to the db is through roles/privileges alone
  def createDatabase(database: Database): Update0 =
    (fr"CREATE DATABASE" ++ Fragment.const(database.value))
      .update

  def dropDatabase(database: Database): Update0 =
    (fr"DROP DATABASE IF EXISTS" ++ Fragment.const(database.value))
      .update
}
