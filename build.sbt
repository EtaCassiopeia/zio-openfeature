import xerial.sbt.Sonatype.sonatypeCentralHost
import net.nmoncho.sbt.dependencycheck.settings._

val scala213Version       = "2.13.16"
val scala3Version         = "3.3.4"
val zioVersion            = "2.1.14"
val openFeatureSdkVersion = "1.20.2"

// OpenFeature Specification Compatibility
// Spec version: v0.8.0 (https://github.com/open-feature/spec)
// This library implements the dynamic-context (server-side) paradigm

ThisBuild / scalaVersion       := scala3Version
ThisBuild / crossScalaVersions := Seq(scala213Version, scala3Version)
ThisBuild / organization       := "io.github.etacassiopeia"

// Version is derived from git tags by sbt-dynver
// Tags should follow SemVer: v0.1.0, v1.0.0, etc.
// Snapshots are automatically versioned as: 0.1.0+3-abcd1234-SNAPSHOT

ThisBuild / homepage := Some(url("https://github.com/EtaCassiopeia/zio-openfeature"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    id = "EtaCassiopeia",
    name = "Mohsen Zainalpour",
    email = "zainalpour@gmail.com",
    url = url("https://github.com/EtaCassiopeia")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/EtaCassiopeia/zio-openfeature"),
    "scm:git:git@github.com:EtaCassiopeia/zio-openfeature.git"
  )
)

// Publishing to Sonatype Central
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
ThisBuild / versionScheme          := Some("semver-spec")

// Common scalac options for both versions
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds"
)

// Version-specific scalac options
ThisBuild / scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq("-Xsource:3", "-Wconf:cat=scala3-migration:w")
    case Some((3, _)) => Seq("-Xfatal-warnings", "-Yretain-trees")
    case _            => Seq()
  }
}

ThisBuild / coverageEnabled          := false
ThisBuild / coverageMinimumStmtTotal := 80
ThisBuild / coverageFailOnMinimum    := true

// Binary-compatibility check via sbt-mima. `mimaPreviousArtifacts` is intentionally empty across all modules until
// the first post-mima tag exists; the first 1.0 release sets a baseline (typically the previous patch version of
// the same module) and `sbt mimaReportBinaryIssues` then catches accidental breaking changes on every PR.
// To intentionally break binary compatibility on a major version bump, add a `mimaBinaryIssueFilters` rule scoped
// to the specific symbol — see https://github.com/lightbend/mima for the filter API.
ThisBuild / mimaFailOnNoPrevious := false

// Version-specific source directories
lazy val crossVersionSourceDirs = Seq(
  Compile / unmanagedSourceDirectories ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(sourceDir / "scala-2")
      case Some((3, _)) => Seq(sourceDir / "scala-3")
      case _            => Seq()
    }
  },
  Test / unmanagedSourceDirectories ++= {
    val sourceDir = (Test / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(sourceDir / "scala-2")
      case Some((3, _)) => Seq(sourceDir / "scala-3")
      case _            => Seq()
    }
  }
)

lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  ),
  // Empty by default — populated by the first published module per major release line. See ThisBuild
  // `mimaFailOnNoPrevious := false` above.
  mimaPreviousArtifacts := Set.empty
) ++ crossVersionSourceDirs

lazy val root = (project in file("."))
  // `conformance` is intentionally NOT aggregated: it is Scala 3 only, so aggregating it would make `++2.13.16 test`
  // try (and fail) to compile it. CI runs it explicitly on Scala 3 (see ci.yml), the same way `examples` are handled.
  .aggregate(core, testkit, extras, ofrep, optimizely)
  .settings(
    name                           := "zio-openfeature",
    publish / skip                 := true,
    dependencyCheckFailBuildOnCVSS := 7,
    dependencyCheckNvdApi          := NvdApiSettings(apiKey = sys.env.getOrElse("NVD_API_KEY", "")),
    dependencyCheckDataDirectory   := Some(new File(Path.userHome.absolutePath, ".dependency-check/data"))
  )

// Core module - ZIO wrapper around OpenFeature SDK
lazy val core = (project in file("core"))
  .settings(
    name := "zio-openfeature-core",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"         %% "zio"         % zioVersion,
      "dev.zio"         %% "zio-streams" % zioVersion,
      "dev.openfeature"  % "sdk"         % openFeatureSdkVersion
    )
  )

// Extras module - built-in providers (HOCON, env vars, caching wrapper)
lazy val extras = (project in file("extras"))
  .dependsOn(core)
  .settings(
    name := "zio-openfeature-extras",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio"       % zioVersion,
      "dev.zio"      %% "zio-cache" % "0.2.3",
      "com.typesafe"  % "config"    % "1.4.3"
    )
  )

// OFREP module - OpenFeature Remote Evaluation Protocol provider.
// Kept separate from `extras` so callers who only want HOCON/env vars don't pull in the OFREP contrib provider's
// transitive HTTP-client stack (Jackson, Guava, Commons Validator, SLF4J).
lazy val ofrep = (project in file("ofrep"))
  .dependsOn(core)
  .settings(
    name := "zio-openfeature-ofrep",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"                            %% "zio"       % zioVersion,
      "dev.openfeature.contrib.providers"   % "ofrep"     % "0.0.1",
      "org.wiremock"                        % "wiremock"  % "3.10.0" % Test
    ),
    // GHSA-72hv-8253-57qq (jackson-core <2.18.0) is patched in 2.18+; the OFREP contrib provider pulls 2.21.2, so we
    // override jackson-core to that version to avoid a split Jackson family (core 2.18 / databind 2.21 → runtime
    // NoSuchMethodError). Scoped to this module so other modules aren't dragged into Jackson alignment they don't need.
    dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-core" % "2.21.2"
  )

// Optimizely module - direct OpenFeature provider on top of the Optimizely Java SDK.
// The unofficial `dev.openfeature.contrib.providers:optimizely` artifact is not published to Maven Central
// at the time of this writing, so this module integrates with Optimizely directly via `com.optimizely.ab:core-api`
// (decision engine) and `core-httpclient-impl` (datafile poller for the Optimizely CDN / self-hosted Agent).
lazy val optimizely = (project in file("optimizely"))
  .dependsOn(core)
  .settings(
    name := "zio-openfeature-optimizely",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"          %% "zio"                  % zioVersion,
      "com.optimizely.ab" % "core-api"             % "4.2.2",
      "com.optimizely.ab" % "core-httpclient-impl" % "4.2.2",
      "org.wiremock"      % "wiremock"             % "3.10.0" % Test
    )
  )

// Testkit module - testing utilities
lazy val testkit = (project in file("testkit"))
  .dependsOn(core)
  .settings(
    name := "zio-openfeature-testkit",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"      % zioVersion,
      "dev.zio" %% "zio-test" % zioVersion
    )
  )

// Conformance module - runs the OpenFeature specification's canonical gherkin feature files verbatim (via Cucumber on
// JUnit) against the ZIO `FeatureFlags` API. Not published; Scala 3 only. Kept as a separate module so the Cucumber /
// JUnit test stack stays out of the published `testkit` artifact's dependency surface. The `.feature` files are
// vendored under src/test/resources (pinned to an upstream spec commit) so the build is hermetic.
lazy val conformance = (project in file("conformance"))
  .dependsOn(core, testkit)
  .settings(
    name               := "zio-openfeature-conformance",
    publish / skip     := true,
    crossScalaVersions := Seq(scala3Version),
    libraryDependencies ++= Seq(
      "dev.openfeature" % "sdk"             % openFeatureSdkVersion % Test,
      "io.cucumber"    %% "cucumber-scala"  % "8.39.1"              % Test,
      "io.cucumber"     % "cucumber-junit"  % "7.34.3"              % Test,
      "junit"           % "junit"           % "4.13.2"              % Test,
      "com.github.sbt"  % "junit-interface" % "0.13.3"              % Test
    )
  )

// The same OpenFeature gherkin suite run via `zio-bdd` (a ZIO-native BDD framework, now a stable 1.0.0 release)
// instead of Cucumber. Not published, not aggregated, Scala 3 only; gated in CI alongside the Cucumber conformance
// suite.
lazy val conformanceZioBdd = (project in file("conformance-zio-bdd"))
  .dependsOn(core, testkit)
  .settings(
    name               := "zio-openfeature-conformance-zio-bdd",
    publish / skip     := true,
    crossScalaVersions := Seq(scala3Version),
    libraryDependencies ++= Seq(
      "dev.openfeature"         % "sdk"                    % openFeatureSdkVersion % Test,
      "io.github.etacassiopeia" %% "zio-bdd"               % "1.0.0"               % Test,
      "dev.zio"                 %% "zio-schema"            % "1.6.6"               % Test,
      "dev.zio"                 %% "zio-schema-derivation" % "1.6.6"               % Test
    ),
    Test / testFrameworks += new TestFramework("zio.bdd.ZIOBDDFramework")
  )

// Reference applications. Not published; their value is staying compilable so the README and `examples/README.md`
// snippets are guaranteed to work against the current public API.
lazy val examplesCommon = Seq(
  publish / skip := true,
  // Examples are illustrative; the strict source-cat warnings that gate the library don't add value here.
  scalacOptions := scalacOptions.value.filterNot(o => o == "-Xfatal-warnings"),
  // Don't bother cross-publishing examples; we just need them to compile on the primary Scala version.
  crossScalaVersions := Seq(scala3Version)
)

lazy val examplesOfrepInitTimeout = (project in file("examples/ofrep-init-timeout"))
  .dependsOn(core, ofrep, extras)
  .settings(examplesCommon)
  .settings(
    name := "zio-openfeature-example-ofrep-init-timeout",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion
    )
  )

lazy val examplesTestkitApp = (project in file("examples/testkit-app"))
  .dependsOn(core, testkit)
  .settings(examplesCommon)
  .settings(
    name := "zio-openfeature-example-testkit-app",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"           % zioVersion,
      "dev.zio" %% "zio-test"      % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"  % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
