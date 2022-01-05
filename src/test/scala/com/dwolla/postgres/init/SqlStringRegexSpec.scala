package com.dwolla.mysql.init

import eu.timepit.refined.refineV
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Shrink}

class SqlStringRegexSpec extends munit.ScalaCheckSuite {
  implicit val shrinkString: Shrink[String] = Shrink.shrinkAny

  test("strings containing semicolons don't validate") {
    assert(refineV[GeneratedPasswordPredicate](";").isLeft)
  }

  test("strings containing apostrophes don't validate") {
    assert(refineV[GeneratedPasswordPredicate]("'").isLeft)
  }

  property("sql identifiers match [A-Za-z][A-Za-z0-9_]*") {
    implicit val arbString: Arbitrary[String] = Arbitrary {
      for {
        initial <- Gen.alphaChar
        tail <- Gen.stringOf(Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
      } yield s"$initial$tail"
    }

    forAll { s: String =>
      assert(refineV[SqlIdentifierPredicate](s).map(_.value) == Right(s))
    }
  }

  property("passwords contain the allowed characters") {
    implicit val arbString: Arbitrary[String] = Arbitrary {
      val allowedPunctuation: Gen[Char] = Gen.oneOf("""! " # $ % & ( ) * + , - . / : < = > ? @ [ \ ] ^ _ { | } ~ """.replaceAll(" ", "").toList)
      val allowedCharacters: Gen[Char] = Gen.oneOf(Gen.alphaChar, Gen.numChar, allowedPunctuation)

      for {
        initial <- allowedCharacters
        tail <- Gen.stringOf(allowedCharacters)
      } yield s"$initial$tail"
    }

    forAll { s: String =>
      assert(refineV[GeneratedPasswordPredicate](s).map(_.value) == Right(s))
    }
  }
}
