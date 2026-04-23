val scala3Version = "3.8.3"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  Test / testFrameworks += new TestFramework("org.specs2.runner.Specs2Framework")
)

val specs2 = "org.specs2" %% "specs2-core" % "5.5.1"
val izumi = "dev.zio" %% "izumi-reflect" % "3.0.3"
val scalaCheckDep = "org.scalacheck" %% "scalacheck" % "1.18.1"
val circeCore = "io.circe" %% "circe-core" % "0.14.10"
val circeParser = "io.circe" %% "circe-parser" % "0.14.10"

lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "registry",
    libraryDependencies ++= Seq(
      izumi,
      specs2 % Test
    )
  )

lazy val Bench = config("bench").extend(Test)

lazy val bench = (project in file("bench"))
  .configs(Bench)
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    inConfig(Bench)(Defaults.testSettings),
    name := "registry-bench",
    publish / skip := true,
    libraryDependencies += specs2 % "test,bench",
    Bench / testFrameworks += new TestFramework("org.specs2.runner.Specs2Framework")
  )

lazy val scalacheck = (project in file("scalacheck"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "registry-scalacheck",
    libraryDependencies ++= Seq(
      scalaCheckDep,
      specs2 % Test
    )
  )

lazy val circe = (project in file("circe"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "registry-circe",
    libraryDependencies ++= Seq(
      circeCore,
      circeParser % Test,
      specs2 % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, bench, scalacheck, circe)
  .settings(commonSettings)
  .settings(
    name := "registry-scala",
    publish / skip := true
  )
