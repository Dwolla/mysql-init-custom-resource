package com.dwolla.mysql.init.repositories

import cats.data.NonEmptyList
import cats.kernel.laws.discipline._
import cats.laws.discipline.arbitrary.catsLawsArbitraryForNonEmptyList
import eu.timepit.refined.scalacheck.all.nonEmptyStringArbitrary
import eu.timepit.refined.types.all._
import munit.DisciplineSuite
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

class GrantsMonoidLaws extends DisciplineSuite {
  private val genGrant: Gen[Grant] =
    arbitrary[NonEmptyString].map(Grant(_))
  private implicit val arbGrant: Arbitrary[Grant] = Arbitrary(genGrant)

  private val genGrants: Gen[Grants] =
    arbitrary[Option[NonEmptyList[Grant]]]
      .map(_.fold[Grants](NoGrants)(HasGrants(_)))
  private implicit val arbGrants: Arbitrary[Grants] = Arbitrary(genGrants)

  checkAll("Grants.MonoidLaws", MonoidTests[Grants].monoid)
}
