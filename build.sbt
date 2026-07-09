import xerial.sbt.Sonatype.sonatypeCentralHost
import net.nmoncho.sbt.dependencycheck.settings._

val scala213Version       = "2.13.16"
val scala3Version         = "3.3.4"
val zioVersion            = "2.1.14"
val zioBddVersion         = "1.4.1"
val openFeatureSdkVersion = "1.21.0"

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

// Binary-compatibility check via sbt-mima. `mimaPreviousArtifacts` is intentionally empty across all modules while
// the project is pre-1.0: the `1.0.0-RCx` line is still making deliberate breaking changes, so baselining against an
// RC would fail CI on intended breakage rather than catching accidental breakage. The baseline is set as part of
// cutting `v1.0.0` — see `RELEASING.md` step "enable MiMa". After that, `sbt mimaReportBinaryIssues` catches
// accidental breaking changes on every PR, and an intentional break is whitelisted with a `mimaBinaryIssueFilters`
// rule scoped to the specific symbol — see https://github.com/lightbend/mima for the filter API.
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
  // Run tests in a forked JVM. A forked test run is a child process that sbt force-terminates when the `test` task
  // completes, so a leaked non-daemon thread (a poller, a WireMock/Jetty pool, anything) can't block exit the way it
  // can in-process. This is the categorical fix for #217 and #229: both issues are repeated instances of "some
  // non-daemon thread outlived the test run and hung the JVM," and chasing each individual leak source (already done
  // across several merged PRs) only closes the specific case found, not the class of bug.
  Test / fork := true,
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  ),
  // Empty while pre-1.0 (see the ThisBuild `mimaFailOnNoPrevious := false` note above). When cutting `v1.0.0`,
  // set this to `Set(organization.value %% moduleName.value % "<last-release>")` per `RELEASING.md`.
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

lazy val conformanceZioBdd = (project in file("conformance-zio-bdd"))
  .dependsOn(core, testkit)
  // "test->test" on optimizely additionally pulls its test classpath (not just main), so the
  // Optimizely flag-matrix suite below can reuse `RecommendationService`/`RecommendationResult`
  // from optimizely's own test sources instead of duplicating that toy SUT here.
  .dependsOn(optimizely % "test->test;compile->compile")
  .settings(
    name               := "zio-openfeature-conformance-zio-bdd",
    publish / skip     := true,
    crossScalaVersions := Seq(scala3Version),
    libraryDependencies ++= Seq(
      "dev.openfeature"         % "sdk"                    % openFeatureSdkVersion % Test,
      "io.github.etacassiopeia" %% "zio-bdd"               % zioBddVersion         % Test,
      // Rift's in-process HTTP mock engine (native, no Docker) replaces the WireMock + mitmproxy
      // container the matrix suites used to fake the Optimizely CDN. `-natives` bundles the engine
      // binaries; `-jdk21` is the JDK21 FFM binding (CI runs conformance on JDK 21).
      "io.github.etacassiopeia" %% "zio-bdd-rift-embedded-jdk21"   % zioBddVersion % Test,
      "io.github.etacassiopeia"  % "zio-bdd-rift-embedded-natives" % zioBddVersion % Test,
      "dev.zio"                 %% "zio-schema"            % "1.6.6"               % Test,
      "dev.zio"                 %% "zio-schema-derivation" % "1.6.6"               % Test
    ),
    // Run tests in a forked JVM, like every module that uses `commonSettings`. This module doesn't
    // (it deliberately uses only the zio-bdd framework, not zio-test), so it was missing the fork —
    // and its Optimizely suites leaked non-daemon threads that, in-process, hung the sbt JVM after
    // the tests pass (#278). Forking lets sbt force-terminate the test JVM on completion, the same
    // categorical fix for #217/#229 that `commonSettings` documents.
    Test / fork := true,
    // The zio-bdd `@Suite(featureDirs = ...)` paths are relative to the repo root (they carry the
    // `conformance-zio-bdd/` prefix). A forked test JVM otherwise defaults its working directory to
    // this module's baseDirectory, so the feature files wouldn't resolve and every suite would run
    // zero scenarios — pin the fork's working directory back to the build root.
    Test / baseDirectory := (LocalRootProject / baseDirectory).value,
    // Rift's embedded native engine uses the JDK 21 Foreign Function & Memory API (a preview feature
    // in 21), so this module's tests require JDK 21 and these flags. CI runs conformance on JDK 21;
    // run local tests (and the pre-push gate) on JDK 21 too.
    Test / javaOptions ++= Seq("--enable-preview", "--enable-native-access=ALL-UNNAMED"),
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
