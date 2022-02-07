package com.dwolla.mysql.init.repositories

import cats._
import cats.data._
import cats.parse.Parser
import cats.parse.Parser._
import eu.timepit.refined.auto._
import eu.timepit.refined.predicates.all._
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString

object GrantParser {
  private val sp: Parser[Unit] = char(' ')
  private val whitespace = sp.rep.void
  private val whitespace0 = sp.rep0.void

  private val grant = string("GRANT")
  private val on = string("ON").surroundedBy(whitespace)
  private val commaSeparator = char(',') *> whitespace0

  val privileges: Parser[NonEmptyList[Grant]] =
    anyChar
      .repUntil(commaSeparator | on)
      .string
      .mapFilter(refineV[NonEmpty](_).toOption)
      .map(Grant(_))
      .repSep(commaSeparator)

  val fullParser: Parser[NonEmptyList[Grant]] =
    grant ~ whitespace *> privileges
}

case class Grant(value: NonEmptyString)
object Grant {
  implicit val eqGrant: Eq[Grant] = Eq.instance { case (Grant(a), Grant(b)) => a.toUpperCase == b.toUpperCase }
  implicit val showGrant: Show[Grant] = Show.fromToString
}
