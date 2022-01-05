ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a MySQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/mysql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.7"
ThisBuild / scalacOptions += "-Ymacro-annotations"
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+mysql-init-custom-resource@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2021)
ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"), JavaSpec.temurin("11"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty

lazy val `mysql-init-custom-resource` = (project in file("."))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.1.6"
      val feralVersion = "0.1.0-M1"
      val doobieVersion = "1.0.0-RC1"
      val munitVersion = "0.7.29"
      val circeVersion = "0.14.1"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-noop" % natchezVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.2.0",
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
        "org.http4s" %% "http4s-ember-client" % "0.23.7",
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-refined" % circeVersion,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.typelevel" %% "log4cats-slf4j" % "2.1.1",
        "com.chuusai" %% "shapeless" % "2.3.7",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC1",
        "software.amazon.awssdk" % "secretsmanager" % "2.17.102",
        "org.tpolecat" %% "doobie-core" % doobieVersion,
        "org.tpolecat" %% "doobie-refined" % doobieVersion,
        "com.ovoenergy" %% "natchez-extras-doobie" % "6.0.0",
        "org.scalameta" %% "munit" % munitVersion % Test,
        "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
      )
    },
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val serverlessDeployCommand = settingKey[Seq[String]]("serverless command to deploy the application")
serverlessDeployCommand := "serverless deploy --verbose".split(' ').toSeq

lazy val deploy = inputKey[Int]("deploy to AWS")
deploy := Def.inputTask {
  import scala.sys.process._

  val baseCommand = serverlessDeployCommand.value
  val exitCode = Process(
    baseCommand ++ Seq("--stage", Stage.parser.parsed.name),
    Option((`mysql-init-custom-resource` / baseDirectory).value),
    "DATABASE_ARTIFACT_PATH" -> (`mysql-init-custom-resource` / Universal / packageBin).value.toString,
  ).!

  if (exitCode == 0) exitCode
  else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
}.evaluated
