package com.dwolla

import cats._
import cats.effect.{Trace => _}
import cats.tagless.aop._
import cats.tagless.syntax.all._
import natchez._

object tracing {
  class WithTracingSyntax[Alg[_[_]] : Instrument, F[_] : Trace](f: Alg[F]) {
    private def toTraceFunctionK: Instrumentation[F, *] ~> F = new (Instrumentation[F, *] ~> F) {
      override def apply[A](fa: Instrumentation[F, A]): F[A] = Trace[F].span(s"${fa.algebraName}.${fa.methodName}")(fa.value)
    }

    def withTracing: Alg[F] =
      Instrument[Alg].instrument(f).mapK(toTraceFunctionK)
  }

  implicit def toWithTracingSyntax[Alg[_[_]] : Instrument, F[_] : Trace](f: Alg[F]): WithTracingSyntax[Alg, F] =
    new WithTracingSyntax(f)
}
