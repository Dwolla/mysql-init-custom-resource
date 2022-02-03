addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.7")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
libraryDependencies ++= Seq(
  "com.comcast" %% "ip4s-core" % "3.1.2",
  "org.typelevel" %% "cats-effect" % "3.3.4",
  "org.typelevel" %% "log4cats-core" % "2.1.1",
)
