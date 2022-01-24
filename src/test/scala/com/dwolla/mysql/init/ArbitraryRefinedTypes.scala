package com.dwolla.mysql.init

import cats._
import cats.syntax.all._
import eu.timepit.refined.refineV
import org.scalacheck.{Arbitrary, Gen, Shrink}

trait ArbitraryRefinedTypes {
  implicit val shrinkSqlIdentifier: Shrink[SqlIdentifier] = Shrink.shrinkAny
  def genSqlIdentifier[F[_] : Applicative]: Gen[F[SqlIdentifier]] =
    for {
      initial <- Gen.alphaChar
      len <- Gen.chooseNum(0, 63)
      tail <- Gen.stringOfN(len, Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
      refined <- refineV[SqlIdentifierPredicate](s"$initial$tail").fold(_ => Gen.fail, Gen.const)
    } yield refined.pure[F]
  implicit val arbSqlIdentifier: Arbitrary[SqlIdentifier] = Arbitrary(genSqlIdentifier[Id])

  implicit val shrinkGeneratedPassword: Shrink[GeneratedPassword] = Shrink.shrinkAny
  def genGeneratedPassword[F[_] : Applicative]: Gen[F[GeneratedPassword]] = {
    val allowedPunctuation: Gen[Char] = Gen.oneOf("""! " # $ % & ( ) * + , - . / : < = > ? @ [ \ ] ^ _ { | } ~ """.replaceAll(" ", "").toList)
    val allowedCharacters: Gen[Char] = Gen.oneOf(Gen.alphaChar, Gen.numChar, allowedPunctuation)

    for {
      initial <- allowedCharacters
      tail <- Gen.stringOf(allowedCharacters)
      refined <- refineV[GeneratedPasswordPredicate](s"$initial$tail").fold(_ => Gen.fail, Gen.const)
    } yield refined.pure[F]
  }
  implicit val arbGeneratedPassword: Arbitrary[GeneratedPassword] = Arbitrary(genGeneratedPassword[Id])
}
