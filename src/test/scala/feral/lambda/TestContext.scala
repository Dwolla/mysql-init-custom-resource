package feral.lambda

import cats._
import cats.syntax.all._

import scala.concurrent.duration._

object TestContext {
  def apply[F[_] : Applicative]: Context[F] =
    new Context(
      "",
      "",
      "",
      Int.MaxValue,
      "",
      "",
      "",
      None,
      None,
      1.minute.pure[F],
    )
}
