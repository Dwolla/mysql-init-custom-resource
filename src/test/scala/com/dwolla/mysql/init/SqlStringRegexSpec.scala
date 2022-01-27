package com.dwolla.mysql.init

import eu.timepit.refined.refineV
import org.scalacheck.Prop.forAll

class SqlStringRegexSpec extends munit.ScalaCheckSuite with ArbitraryRefinedTypes {
  test("strings containing semicolons don't validate") {
    assert(refineV[GeneratedPasswordPredicate](";").isLeft)
  }

  test("strings containing apostrophes don't validate") {
    assert(refineV[GeneratedPasswordPredicate]("'").isLeft)
  }

  property("sql identifiers match [A-Za-z][A-Za-z0-9_]*") {
    forAll { s: SqlIdentifier =>
      assert(refineV[SqlIdentifierPredicate](s.value).map(_.value) == Right(s.value))
    }
  }

  property("passwords contain the allowed characters") {
    forAll { s: GeneratedPassword =>
      assert(refineV[GeneratedPasswordPredicate](s.value).map(_.value) == Right(s.value))
    }
  }
}
