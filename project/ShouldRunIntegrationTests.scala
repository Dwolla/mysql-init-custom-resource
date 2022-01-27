import SbtLogger._
import ShouldRunIntegrationTestsEitherTOps.noDatabaseWarning
import cats._
import cats.data._
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.{Host, Port, SocketAddress}
import org.typelevel.log4cats.Logger
import sbt.{TestFrameworks, Tests, Logger => SbtUtilLogger}

import java.net.Socket
import scala.concurrent.duration._
import scala.language.{higherKinds, implicitConversions}

sealed trait ShouldRunIntegrationTests extends Product with Serializable {
  val testArguments: Option[Tests.Argument]

  final def orElse(next: => ShouldRunIntegrationTests): ShouldRunIntegrationTests =
    orElse[Id](next)

  final def orElse[F[_] : Applicative](next: => F[ShouldRunIntegrationTests]): F[ShouldRunIntegrationTests] =
    this match {
      case Yes => Yes.pure[F].widen
      case No => next
    }
}
case object Yes extends ShouldRunIntegrationTests {
  override val testArguments: Option[Tests.Argument] = None
}
case object No extends ShouldRunIntegrationTests {
  override val testArguments: Option[Tests.Argument] = Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=IntegrationTest").some
}

object ShouldRunIntegrationTests {
  def apply[F[_] : Async](db: Host, port: Port)
                         (implicit log: SbtUtilLogger): F[ShouldRunIntegrationTests] =
    OptionT(Sync[F].delay(sys.env.get("CI")))
      .subflatMap(ci => Either.catchNonFatal(ci.toBoolean).toOption)
      .value
      .map(_.isCI)
      .flatMap {
        _.orElse {
          Resource.fromAutoCloseable {
            SocketAddress(db, port)
              .resolve[F]
              .map(_.toInetSocketAddress)
              .flatMap { sa =>
                Sync[F].delay(new Socket()).flatTap(s => Sync[F].delay(s.connect(sa)))
              }
          }
            .use_
            .timeout(2.seconds)
            .attemptT
            .runIntegrationTestsIfSuccessful
        }
      }

  implicit def toShouldRunIntegrationTestsBooleanOptionOps(maybeBool: Option[Boolean]): ShouldRunIntegrationTestsBooleanOptionOps =
    new ShouldRunIntegrationTestsBooleanOptionOps(maybeBool)

  implicit def toShouldRunIntegrationTestsEitherTOps[F[_], A, B](et: EitherT[F, A, B]): ShouldRunIntegrationTestsEitherTOps[F, A, B] =
    new ShouldRunIntegrationTestsEitherTOps(et)
}

class ShouldRunIntegrationTestsBooleanOptionOps(private val maybeBool: Option[Boolean]) extends AnyVal {
  def isCI: ShouldRunIntegrationTests =
    maybeBool match {
      case None => No
      case Some(true) => Yes
      case Some(false) => No
    }
}

object ShouldRunIntegrationTestsEitherTOps {
  private[ShouldRunIntegrationTestsEitherTOps] val noDatabaseWarning: String =
    """No MySQL database instance was detected, so integration tests are disabled.
      |
      |You can start a local Docker container using MariaDB by running the following command:
      |
      |   docker run \
      |     --detach \
      |     --rm \
      |     --interactive \
      |     --tty \
      |     --name mariadb \
      |     --env MARIADB_ROOT_PASSWORD=password \
      |     --publish 3306:3306 \
      |     mariadb:latest
      |
      |""".stripMargin
}

class ShouldRunIntegrationTestsEitherTOps[F[_], A, B](private val et: EitherT[F, A, B]) extends AnyVal {
  def runIntegrationTestsIfSuccessful(implicit F: Monad[F], L: Logger[F]): F[ShouldRunIntegrationTests] =
    et
      .map(_ => Yes)
      .getOrElseF(Logger[F].warn(noDatabaseWarning).as(No))

}
