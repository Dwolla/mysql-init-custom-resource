import com.typesafe.sbt.packager.universal.UniversalPlugin
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import sbt.Keys.{baseDirectory, packageBin}
import sbt.internal.util.complete.DefaultParsers._
import sbt.internal.util.complete.Parser
import sbt.{Def, settingKey, IO => _, _}

object ServerlessDeployPlugin extends AutoPlugin {
  object autoImport {
    val serverlessDeployCommand = settingKey[Seq[String]]("serverless command to deploy the application")
    val deploy = inputKey[Int]("deploy to AWS")
  }

  import autoImport._

  override def trigger: PluginTrigger = NoTrigger

  override def requires: Plugins = UniversalPlugin

  override lazy val projectSettings = Seq(
    serverlessDeployCommand := "serverless deploy --verbose".split(' ').toSeq,
    deploy := Def.inputTask {
      import scala.sys.process._

      val baseCommand = serverlessDeployCommand.value
      val exitCode = Process(
        baseCommand ++ Seq("--stage", Stage.parser.parsed.name),
        Option((ThisBuild / baseDirectory).value),
        "DATABASE_ARTIFACT_PATH" -> (Universal / packageBin).value.toString,
      ).!

      if (exitCode == 0) exitCode
      else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
    }.evaluated
  )

  sealed abstract class Stage(val name: String) {
    val parser: Parser[this.type] = (Space ~> token(this.toString)).map(_ => this)
  }

  object Stage {
    val parser: Parser[Stage] =
      token(Stage.Sandbox.parser) |
        token(Stage.DevInt.parser) |
        token(Stage.Uat.parser) |
        token(Stage.Prod.parser) |
        token(Stage.Admin.parser)

    case object Sandbox extends Stage("Sandbox")
    case object DevInt extends Stage("DevInt")
    case object Uat extends Stage("Uat")
    case object Prod extends Stage("Prod")
    case object Admin extends Stage("Admin")
  }
}
