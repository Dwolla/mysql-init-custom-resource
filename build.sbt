ThisBuild / organization := "com.dwolla"
ThisBuild / description := "CloudFormation custom resource to initialize a MySQL database with a new user"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/mysql-init-custom-resource"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.8"
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
ThisBuild / resolvers += Resolver.sonatypeRepo("snapshots")

lazy val `mysql-init-custom-resource` = (project in file("."))
  .settings(
    maintainer := developers.value.head.email,
    topLevelDirectory := None,
    libraryDependencies ++= {
      val natchezVersion = "0.1.6"
      val feralVersion = "0.1.0-M9"
      val doobieVersion = "1.0.0-RC2"
      val munitVersion = "0.7.29"
      val circeVersion = "0.14.2"
      val scalacheckEffectVersion = "1.0.4"
      val log4catsVersion = "2.3.1"
      val monocleVersion = "2.1.0"
      val http4sVersion = "0.23.12"
      val awsSdkVersion = "2.17.190"
      val refinedV = "0.9.29"
      val catsRetryVersion = "3.1.0"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-noop" % natchezVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.3.2",
        "org.typelevel" %% "cats-tagless-macros" % "0.14.0",
        "org.http4s" %% "http4s-ember-client" % http4sVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-refined" % circeVersion,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.5.1" % Runtime,
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.19.0" % Runtime,
        "com.chuusai" %% "shapeless" % "2.3.9",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC1",
        "software.amazon.awssdk" % "secretsmanager" % awsSdkVersion,
        "org.tpolecat" %% "doobie-core" % doobieVersion,
        "org.tpolecat" %% "doobie-refined" % doobieVersion,
        "mysql" % "mysql-connector-java" % "8.0.29" % Runtime,
        "com.ovoenergy" %% "natchez-extras-doobie" % "6.1.0",
        "com.github.cb372" %% "cats-retry" % catsRetryVersion,
        "org.typelevel" %% "cats-parse" % "0.3.7",
        "org.scalameta" %% "munit" % munitVersion % Test,
        "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
        "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
        "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "log4cats-noop" % log4catsVersion % Test,
        "io.circe" %% "circe-testing" % circeVersion % Test,
        "io.circe" %% "circe-optics" % "0.14.1" % Test,
        "com.github.julien-truffaut" %% "monocle-core" % monocleVersion % Test,
        "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion % Test,
        "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
        "com.eed3si9n.expecty" %% "expecty" % "0.15.4" % Test,
        "software.amazon.awssdk" % "sts" % awsSdkVersion % Test,
        "eu.timepit" %% "refined-scalacheck" % refinedV % Test,
        "org.typelevel" %% "cats-laws" % "2.7.0" % Test,
        "org.typelevel" %% "discipline-munit" % "1.0.9" % Test,
      )
    },
    addBuildInfoToConfig(Test),
    Test / testOptions ++= (Test / shouldRunIntegrationTests).value.testArguments,
  )
  .enablePlugins(
    UniversalPlugin,
    JavaAppPackaging,
    IntegrationTestsPlugin,
    BuildInfoPlugin,
    ServerlessDeployPlugin,
  )
