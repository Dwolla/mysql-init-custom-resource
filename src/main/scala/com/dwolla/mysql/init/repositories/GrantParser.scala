package com.dwolla.mysql.init.repositories

import cats._
import cats.data._
import cats.syntax.all._
import cats.parse.Parser
import cats.parse.Parser._

object GrantParser {
  private val sp: Parser[Unit] = char(' ')
  private val whitespace = sp.rep.void
  private val whitespace0 = sp.rep0.void

  private val grant = string("GRANT")
  private val on = string("ON").surroundedBy(whitespace)
  private val commaSeparator = char(',') *> whitespace0

  val privileges: Parser[NonEmptyList[String]] =
    anyChar
      .repUntil(commaSeparator | on)
      .repSep(commaSeparator)
      .map(_.map(_.mkString_("")))

  val fullParser: Parser[NonEmptyList[String]] =
    grant ~ whitespace *> privileges
}

case class Grant(value: String)
object Grant {
  implicit val eqGrant: Eq[Grant] = Eq.instance { case (Grant(a), Grant(b)) => a.toUpperCase == b.toUpperCase }
}
