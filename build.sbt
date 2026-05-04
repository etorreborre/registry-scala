val scala3Version = "3.3.6"

val REGISTRY = "REGISTRY-"

ThisBuild / scalaVersion := scala3Version

lazy val publishSettings = Seq(
  organization := "org.atnos",
  homepage := Some(url("https://github.com/etorreborre/registry-scala")),
  licenses := List("MIT" -> url("https://opensource.org/licenses/MIT")),
  developers := List(
    Developer("etorreborre", "Eric Torreborre", "etorreborre@yahoo.com", url("https://github.com/etorreborre"))
  ),
  versionScheme := Some("early-semver"),
  // MIMA is wired up but disabled until a 1.0.0 baseline is published.
  mimaPreviousArtifacts := Set.empty,
  mimaFailOnNoPrevious := false
)

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  Test / testFrameworks += new TestFramework("org.specs2.runner.Specs2Framework")
)

val specs2 = "org.specs2" %% "specs2-core" % "5.5.1"
val izumi = "dev.zio" %% "izumi-reflect" % "3.0.3"
val scalaCheckDep = "org.scalacheck" %% "scalacheck" % "1.18.1"
val circeCore = "io.circe" %% "circe-core" % "0.14.10"
val circeParser = "io.circe" %% "circe-parser" % "0.14.10"
val catsCore   = "org.typelevel" %% "cats-core"   % "2.12.0"
val catsEffect = "org.typelevel" %% "cats-effect" % "3.5.7"

lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(publishSettings)
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
  .settings(publishSettings)
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
  .settings(publishSettings)
  .settings(
    name := "registry-circe",
    libraryDependencies ++= Seq(
      circeCore,
      circeParser,
      specs2 % Test
    )
  )

lazy val catsInterop = (project in file("cats"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "registry-cats",
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect % Test,
      specs2     % Test
    )
  )

lazy val docs = (project in file("docs"))
  .enablePlugins(MdocPlugin)
  .dependsOn(core, scalacheck, circe, catsInterop)
  .settings(commonSettings)
  .settings(
    name := "registry-docs",
    publish / skip := true,
    mdocIn  := baseDirectory.value / "mdoc",
    mdocOut := baseDirectory.value / "target" / "mdoc",
    scalacOptions ++= Seq("-Wunused:nowarn")
  )

lazy val root = (project in file("."))
  .aggregate(core, bench, scalacheck, circe, catsInterop, docs)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    name := "registry-scala",
    publish / skip := true
  )

/* RELEASE
 *
 * Tag a commit `REGISTRY-X.Y.Z` and push to publish to Maven Central via
 * sbt-ci-release (Sonatype Central Portal). The same workflow renders the
 * mdoc site and deploys it to the gh-pages branch.
 *
 * Required GitHub Actions secrets:
 *   - SONATYPE_USERNAME / SONATYPE_PASSWORD  (user token from central.sonatype.com)
 *   - PGP_KEY_ID / PGP_PASSPHRASE / PGP_SECRET  (PGP_SECRET is base64-encoded)
 */
lazy val releaseSettings: Seq[Setting[_]] = Seq(
  ThisBuild / dynverTagPrefix := REGISTRY,
  ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21")),
  ThisBuild / githubWorkflowArtifactUpload := false,
  // gh-pages deploy needs write access for GITHUB_TOKEN.
  ThisBuild / githubWorkflowPermissions := Some(
    Permissions.Specify(Map(PermissionScope.Contents -> PermissionValue.Write))
  ),
  ThisBuild / githubWorkflowBuildPreamble ++= List(
    WorkflowStep.Sbt(List("scalafmtCheckAll"), name = Some("Check formatting"))
  ),
  ThisBuild / githubWorkflowBuild := Seq(
    WorkflowStep.Sbt(List("test"), name = Some("Build and test"))
  ),
  ThisBuild / githubWorkflowTargetTags ++= Seq(REGISTRY + "*"),
  ThisBuild / githubWorkflowPublishTargetBranches :=
    Seq(RefPredicate.StartsWith(Ref.Tag(REGISTRY))),
  ThisBuild / githubWorkflowPublish := Seq(
    WorkflowStep.Run(
      name = Some("Import GPG key"),
      commands = List(importGpgCommand),
      env = Map(
        "PGP_KEY_ID" -> "${{ secrets.PGP_KEY_ID }}",
        "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
        "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}"
      )
    ),
    WorkflowStep.Run(
      name = Some("Release to Sonatype"),
      commands = List(ciReleaseCommand),
      env = Map(
        "PGP_KEY_ID" -> "${{ secrets.PGP_KEY_ID }}",
        "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
        "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
        "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}",
        "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}"
      )
    ),
    WorkflowStep.Sbt(
      name = Some("Render mdoc"),
      commands = List("docs/mdoc")
    ),
    WorkflowStep.Run(
      name = Some("Copy Jekyll config"),
      commands = List(
        "cp docs/_config.yml  docs/target/mdoc/_config.yml",
        "cp docs/Gemfile      docs/target/mdoc/Gemfile",
        "cp docs/favicon.svg  docs/target/mdoc/favicon.svg",
        "rm -rf docs/target/mdoc/_sass docs/target/mdoc/_includes",
        "cp -R docs/_sass     docs/target/mdoc/_sass",
        "cp -R docs/_includes docs/target/mdoc/_includes"
      )
    ),
    WorkflowStep.Use(
      name = Some("Deploy docs to gh-pages"),
      ref = UseRef.Public("JamesIves", "github-pages-deploy-action", "v4"),
      params = Map(
        "branch" -> "gh-pages",
        "folder" -> "docs/target/mdoc",
        "clean"  -> "true"
      )
    )
  )
)

val importGpgCommand = """echo "$PGP_SECRET" | base64 --decode | gpg --batch --yes --import
printf "pinentry-mode loopback\n" >> ~/.gnupg/gpg.conf
printf "allow-loopback-pinentry\n" >> ~/.gnupg/gpg-agent.conf
gpgconf --kill gpg-agent
gpg --list-secret-keys --keyid-format LONG
gpg --batch --yes -u "$PGP_KEY_ID" --dry-run --pinentry-mode loopback --passphrase "$PGP_PASSPHRASE" --sign <<<"test"
"""

val ciReleaseCommand =
  """VERSION="${GITHUB_REF#refs/tags/REGISTRY-}"
if [ -n "$VERSION" ] && [ "$VERSION" != "$GITHUB_REF" ]; then
  POM_URL="https://repo1.maven.org/maven2/org/atnos/registry_3/${VERSION}/registry_3-${VERSION}.pom"
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$POM_URL")
  if [ "$HTTP_CODE" = "200" ]; then
    echo "org.atnos:registry_3:${VERSION} is already in Maven Central — skipping publish."
    exit 0
  fi
fi

sbt ci-release 2>&1 | tee /tmp/sonatype-output.txt
EXIT_CODE=${PIPESTATUS[0]}
if [ "$EXIT_CODE" -ne 0 ]; then
  if grep -qiE "already (been )?(published|deployed|exists)|already.*exists|component.*already|version.*already|409 Conflict|cannot redeploy|redeployment" /tmp/sonatype-output.txt; then
    echo "Artifact already published to Sonatype, continuing..."
    exit 0
  else
    exit "$EXIT_CODE"
  fi
fi
"""
