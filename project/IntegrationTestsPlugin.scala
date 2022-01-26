import cats.effect._
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s._
import sbt.Keys.streams
import sbt.{IO => _, _}
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport._

object IntegrationTestsPlugin extends AutoPlugin {
  object autoImport {
    val shouldRunIntegrationTests = taskKey[ShouldRunIntegrationTests]("true if this build is running on CI")
    val databaseAddress = taskKey[Host]("address where the database is available")
    val databasePort = taskKey[Port]("address where the database is available")
    val databaseUser = taskKey[String]("user with which to connect to the database, which should have privileges to create databases, users, and roles, and assign permissions thereto")
    val databasePassword = taskKey[String]("password for the `databaseUser` user")
  }
  import autoImport._

  override def trigger = NoTrigger

  override def requires: Plugins = BuildInfoPlugin

  override lazy val projectSettings = Seq(
    Test / databaseAddress := ipv4"127.0.0.1",
    Test / databasePort := port"3306",
    Test / databaseUser := "root",
    Test / databasePassword := "password",
    Test / shouldRunIntegrationTests := Def.task[ShouldRunIntegrationTests] {
      val db = (Test / databaseAddress).value
      val port = (Test / databasePort).value
      implicit val log: Logger = streams.value.log

      ShouldRunIntegrationTests[IO](db, port).unsafeRunSync()
    }.value,
    Test / buildInfoKeys := Seq[BuildInfoKey](
      Test / databaseAddress,
      Test / databasePort,
      Test / databaseUser,
      Test / databasePassword,
    ),
    Test / buildInfoPackage := "com.dwolla.mysql.init",
  )
}
