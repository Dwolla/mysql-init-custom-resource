package com.dwolla.mysql.init

import cats.effect._
import doobie.util.transactor.Transactor

import java.lang.reflect.{Method, Proxy}
import java.sql.{Connection, PreparedStatement}
import scala.reflect.ClassTag

/**
 * A JDBC connection that never leaves the JVM: each statement's `executeUpdate` is answered
 * by `executeUpdate`, given the statement's SQL text, and every other call returns its type's
 * default value. Enough to run doobie updates through a transactor without a database.
 */
object InMemoryJdbc {
  def transactor(executeUpdate: String => Int): Transactor[IO] =
    Transactor.fromConnection[IO](connection(executeUpdate))

  def connection(executeUpdate: String => Int): Connection =
    proxy[Connection] {
      case ("prepareStatement", Array(sql: String, _*)) => statement(sql, executeUpdate)
    }

  private def statement(sql: String, executeUpdate: String => Int): PreparedStatement =
    proxy[PreparedStatement] {
      case ("executeUpdate", _) => Int.box(executeUpdate(sql))
    }

  private def proxy[A](answer: PartialFunction[(String, Array[AnyRef]), AnyRef])
                      (implicit A: ClassTag[A]): A =
    Proxy.newProxyInstance(
      getClass.getClassLoader,
      Array(A.runtimeClass),
      (_: AnyRef, method: Method, args: Array[AnyRef]) =>
        answer.applyOrElse((method.getName, Option(args).getOrElse(Array.empty[AnyRef])), (_: (String, Array[AnyRef])) => defaultValue(method))
    ).asInstanceOf[A]

  private def defaultValue(method: Method): AnyRef =
    method.getReturnType match {
      case java.lang.Boolean.TYPE => java.lang.Boolean.FALSE
      case java.lang.Integer.TYPE => Int.box(0)
      case java.lang.Long.TYPE => Long.box(0L)
      case _ => null
    }
}
