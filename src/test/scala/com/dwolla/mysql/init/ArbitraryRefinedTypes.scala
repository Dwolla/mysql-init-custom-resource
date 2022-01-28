package com.dwolla.mysql.init

import cats._
import cats.syntax.all._
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.refineV
import org.scalacheck.{Arbitrary, Gen, Shrink}

trait ArbitraryRefinedTypes {
  def refinedConst[A] = new PartiallyAppliedRefinedConst[A]

  implicit val shrinkSqlIdentifier: Shrink[SqlIdentifier] = Shrink.shrinkAny
  def genSqlIdentifier[F[_] : Applicative]: Gen[F[SqlIdentifier]] =
    for {
      initial <- Gen.alphaChar
      len <- Gen.chooseNum(0, 27) // see comment on SqlIdentifier
      tail <- Gen.stringOfN(len, Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
      refined <- refineV[SqlIdentifierPredicate](s"$initial$tail").fold(_ => Gen.fail, Gen.const)
    } yield refined.pure[F]
  implicit val arbSqlIdentifier: Arbitrary[SqlIdentifier] = Arbitrary(genSqlIdentifier[Id])

  implicit val shrinkMySqlUser: Shrink[MySqlUser] = Shrink.shrinkAny
  def genMySqlUser[F[_] : Applicative]: Gen[F[MySqlUser]] =
    for {
      initial <- Gen.alphaChar
      len <- Gen.chooseNum(0, 63)
      tail <- Gen.stringOfN(len, Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
      refined <- refineV[MySqlUserPredicate](s"$initial$tail").fold(_ => Gen.fail, Gen.const)
    } yield refined.pure[F]
  implicit val arbMySqlUser: Arbitrary[MySqlUser] = Arbitrary(genMySqlUser[Id])

  implicit val shrinkGeneratedPassword: Shrink[GeneratedPassword] = Shrink.shrinkAny
  def genGeneratedPassword[F[_] : Applicative]: Gen[F[GeneratedPassword]] = {
    val allowedPunctuation: Gen[Char] = Gen.oneOf("""! " # $ % & ( ) * + , - . / : < = > ? @ [ \ ] ^ _ { | } ~ """.replaceAll(" ", "").toList)
    val allowedCharacters: Gen[Char] = Gen.oneOf(Gen.alphaChar, Gen.numChar, allowedPunctuation)

    for {
      initial <- allowedCharacters
      length <- Gen.chooseNum(0, 255) // max length 256 (255 + initial character)
      tail <- Gen.stringOfN(length, allowedCharacters)
      refined <- refineV[GeneratedPasswordPredicate](s"$initial$tail").fold(_ => Gen.fail, Gen.const)
    } yield refined.pure[F]
  }
  implicit val arbGeneratedPassword: Arbitrary[GeneratedPassword] = Arbitrary(genGeneratedPassword[Id])
}

class PartiallyAppliedRefinedConst[A](private val unit: Unit = ()) extends AnyVal {
  def apply[T, P](t: T)
                 (implicit ev: Refined[T, P] =:= A,
                  V: Validate[T, P]): Gen[Refined[T, P]] =
    refineV[P](t).fold(_ |: Gen.fail, Gen.const)
}
