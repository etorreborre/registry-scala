val scala3Version = "3.8.3"

lazy val root = project
  .in(file("."))
  .settings(
    name := "registry",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "dev.zio"   %% "izumi-reflect" % "3.0.3",
      "org.specs2" %% "specs2-core"  % "5.5.1" % Test
    ),
    Test / testFrameworks += new TestFramework("org.specs2.runner.Specs2Framework")
  )
