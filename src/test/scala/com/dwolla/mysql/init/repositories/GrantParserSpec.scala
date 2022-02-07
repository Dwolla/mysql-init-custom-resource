package com.dwolla.mysql.init.repositories

import cats.data.NonEmptyList
import munit.FunSuite
import eu.timepit.refined.auto._
import eu.timepit.refined.types.all._

class GrantParserSpec extends FunSuite {
  test("simple simple") {
    val input = """SELECT, DROP ROLE ON """

    assertEquals(GrantParser.privileges.parse(input), Right((" ON ", NonEmptyList.of[NonEmptyString]("SELECT", "DROP ROLE").map(Grant(_)))))
  }

  test("simple example") {
    val input = """GRANT SELECT, DROP ROLE ON *.*"""

    assertEquals(GrantParser.fullParser.parse(input), Right((" ON *.*", NonEmptyList.of[NonEmptyString]("SELECT", "DROP ROLE").map(Grant(_)))))
  }

  test("MySQL default privileges") {
    val input =
      """GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, RELOAD, SHUTDOWN, PROCESS, FILE, REFERENCES, INDEX, ALTER, SHOW DATABASES, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, REPLICATION SLAVE, REPLICATION CLIENT, CREATE VIEW, SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, CREATE USER, EVENT, TRIGGER, CREATE TABLESPACE, CREATE ROLE, DROP ROLE ON *.* TO `root`@`%` WITH GRANT OPTION"""

    val expectedPrivileges = NonEmptyList.of[NonEmptyString](
      "SELECT",
      "INSERT",
      "UPDATE",
      "DELETE",
      "CREATE",
      "DROP",
      "RELOAD",
      "SHUTDOWN",
      "PROCESS",
      "FILE",
      "REFERENCES",
      "INDEX",
      "ALTER",
      "SHOW DATABASES",
      "CREATE TEMPORARY TABLES",
      "LOCK TABLES",
      "EXECUTE",
      "REPLICATION SLAVE",
      "REPLICATION CLIENT",
      "CREATE VIEW",
      "SHOW VIEW",
      "CREATE ROUTINE",
      "ALTER ROUTINE",
      "CREATE USER",
      "EVENT",
      "TRIGGER",
      "CREATE TABLESPACE",
      "CREATE ROLE",
      "DROP ROLE",
    ).map(Grant(_))
    val expectedRemainder = " ON *.* TO `root`@`%` WITH GRANT OPTION"
    
    assertEquals(GrantParser.fullParser.parse(input), Right((expectedRemainder, expectedPrivileges)))
  }

  test("MySQL default roles") {
    val input =
      """GRANT APPLICATION_PASSWORD_ADMIN,AUDIT_ABORT_EXEMPT,AUDIT_ADMIN,AUTHENTICATION_POLICY_ADMIN,BACKUP_ADMIN,BINLOG_ADMIN,BINLOG_ENCRYPTION_ADMIN,CLONE_ADMIN,CONNECTION_ADMIN,ENCRYPTION_KEY_ADMIN,FLUSH_OPTIMIZER_COSTS,FLUSH_STATUS,FLUSH_TABLES,FLUSH_USER_RESOURCES,GROUP_REPLICATION_ADMIN,GROUP_REPLICATION_STREAM,INNODB_REDO_LOG_ARCHIVE,INNODB_REDO_LOG_ENABLE,PASSWORDLESS_USER_ADMIN,PERSIST_RO_VARIABLES_ADMIN,REPLICATION_APPLIER,REPLICATION_SLAVE_ADMIN,RESOURCE_GROUP_ADMIN,RESOURCE_GROUP_USER,SERVICE_CONNECTION_ADMIN,SESSION_VARIABLES_ADMIN,SET_USER_ID,SHOW_ROUTINE,SYSTEM_USER,SYSTEM_VARIABLES_ADMIN,TABLE_ENCRYPTION_ADMIN,XA_RECOVER_ADMIN ON *.* TO `root`@`%` WITH GRANT OPTION"""

    val expectedPrivileges = NonEmptyList.of[NonEmptyString](
      "APPLICATION_PASSWORD_ADMIN",
      "AUDIT_ABORT_EXEMPT",
      "AUDIT_ADMIN",
      "AUTHENTICATION_POLICY_ADMIN",
      "BACKUP_ADMIN",
      "BINLOG_ADMIN",
      "BINLOG_ENCRYPTION_ADMIN",
      "CLONE_ADMIN",
      "CONNECTION_ADMIN",
      "ENCRYPTION_KEY_ADMIN",
      "FLUSH_OPTIMIZER_COSTS",
      "FLUSH_STATUS",
      "FLUSH_TABLES",
      "FLUSH_USER_RESOURCES",
      "GROUP_REPLICATION_ADMIN",
      "GROUP_REPLICATION_STREAM",
      "INNODB_REDO_LOG_ARCHIVE",
      "INNODB_REDO_LOG_ENABLE",
      "PASSWORDLESS_USER_ADMIN",
      "PERSIST_RO_VARIABLES_ADMIN",
      "REPLICATION_APPLIER",
      "REPLICATION_SLAVE_ADMIN",
      "RESOURCE_GROUP_ADMIN",
      "RESOURCE_GROUP_USER",
      "SERVICE_CONNECTION_ADMIN",
      "SESSION_VARIABLES_ADMIN",
      "SET_USER_ID",
      "SHOW_ROUTINE",
      "SYSTEM_USER",
      "SYSTEM_VARIABLES_ADMIN",
      "TABLE_ENCRYPTION_ADMIN",
      "XA_RECOVER_ADMIN",
    ).map(Grant(_))
    val expectedRemainder = " ON *.* TO `root`@`%` WITH GRANT OPTION"
    
    assertEquals(GrantParser.fullParser.parse(input), Right((expectedRemainder, expectedPrivileges)))
  }

  test("MySQL privileges on RDS") {
    val input = 
      """GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, RELOAD, PROCESS, REFERENCES, INDEX, ALTER, SHOW DATABASES, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, REPLICATION SLAVE, REPLICATION CLIENT, CREATE VIEW, SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, CREATE USER, EVENT, TRIGGER ON *.* TO `admin`@`%` WITH GRANT OPTION"""

    val expectedPrivileges = NonEmptyList.of[NonEmptyString](
      "SELECT",
      "INSERT",
      "UPDATE",
      "DELETE",
      "CREATE",
      "DROP",
      "RELOAD",
      "PROCESS",
      "REFERENCES",
      "INDEX",
      "ALTER",
      "SHOW DATABASES",
      "CREATE TEMPORARY TABLES",
      "LOCK TABLES",
      "EXECUTE",
      "REPLICATION SLAVE",
      "REPLICATION CLIENT",
      "CREATE VIEW",
      "SHOW VIEW",
      "CREATE ROUTINE",
      "ALTER ROUTINE",
      "CREATE USER",
      "EVENT",
      "TRIGGER",
    ).map(Grant(_))
    val expectedRemainder = " ON *.* TO `admin`@`%` WITH GRANT OPTION"

    assertEquals(GrantParser.fullParser.parse(input), Right((expectedRemainder, expectedPrivileges)))
  }

  test("MySQL roles on RDS") {
    val input =
      """GRANT ROLE_ADMIN ON *.* TO `admin`@`%` WITH GRANT OPTION"""

    val expectedPrivileges = NonEmptyList.of[NonEmptyString](
      "ROLE_ADMIN",
    ).map(Grant(_))
    val expectedRemainder = " ON *.* TO `admin`@`%` WITH GRANT OPTION"

    assertEquals(GrantParser.fullParser.parse(input), Right((expectedRemainder, expectedPrivileges)))
  }
}
