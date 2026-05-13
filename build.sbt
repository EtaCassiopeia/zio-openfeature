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

// Local-only Optimizely integration suite. Drives the real Optimizely Java SDK against a docker-compose stack
// (nginx + Toxiproxy) for real-decision, targeting, transport-fault, and datafile-churn scenarios that can't be
// covered by the in-process WireMock spec in the `optimizely` module. Intentionally NOT aggregated into the root
// project so `sbt test` (and CI) stay fast and Docker-free; run explicitly with `sbt optimizelyIt/test`.
lazy val optimizelyIt = (project in file("optimizely-it"))
  .dependsOn(optimizely, core, extras)
  .settings(
    name := "zio-openfeature-optimizely-it",
    commonSettings,
    publish / skip        := true,
    crossScalaVersions    := Seq(scala3Version),
    Test / fork           := true,
    coverageEnabled       := false,
    coverageFailOnMinimum := false,
    libraryDependencies ++= Seq(
      "dev.zio"             %% "zio"                       % zioVersion,
      "com.dimafeng"        %% "testcontainers-scala-core" % "0.43.0" % Test,
      "eu.rekawek.toxiproxy" % "toxiproxy-java"            % "2.1.7"  % Test,
      // SLF4J binding so testcontainers' Docker-discovery + container-lifecycle logs surface in the test output.
      // Without it, testcontainers fails silently when it can't reach the Docker daemon.
      "org.slf4j"            % "slf4j-simple"              % "2.0.13" % Test
    ),
    // testcontainers-scala 0.43.0 pulls testcontainers-java 1.20.2, which bundles a docker-java client that
    // negotiates Docker API v1.32 — Docker Desktop 29.x rejects anything below v1.40 with a 400. Force the
    // transitive testcontainers-java to a 1.21.x that uses a newer docker-java.
    dependencyOverrides ++= Seq(
      "org.testcontainers" % "testcontainers" % "1.21.3" % Test
    ),
    Test / javaOptions ++= Seq(
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
      // docker-java (transitive of testcontainers) defaults to Docker API v1.32, which Docker Desktop 29.x
      // rejects (minimum v1.40). Pin to a recent version both Linux and Docker Desktop accept.
      "-Dapi.version=1.43"
    ),
    // Pin DOCKER_HOST for the forked test JVM. On macOS with Docker Desktop, the symlinked sockets at
    // /var/run/docker.sock and ~/.docker/run/docker.sock return a stubbed /info response with a redirect label to
    // testcontainers' docker-java client (probably User-Agent-based filtering); the raw Docker API on Desktop is
    // exposed at ~/Library/Containers/com.docker.docker/Data/docker.raw.sock and behaves identically to a Linux
    // /var/run/docker.sock. Prefer raw.sock when present, fall back to the user's DOCKER_HOST, then to the symlinked
    // socket.
    Test / envVars := {
      val existing = sys.env
      val explicit = existing.get("DOCKER_HOST")
      val derived = {
        val home    = sys.props.getOrElse("user.home", "")
        val rawSock = new java.io.File(s"$home/Library/Containers/com.docker.docker/Data/docker.raw.sock")
        val runSock = new java.io.File(s"$home/.docker/run/docker.sock")
        if (rawSock.exists()) Some(s"unix://${rawSock.getAbsolutePath}")
        else if (runSock.exists()) Some(s"unix://${runSock.getAbsolutePath}")
        else None
      }
      // Ryuk (testcontainers' cleanup sidecar) won't launch on Docker Desktop's docker.raw.sock — the daemon
      // disallows the privileged container Ryuk asks for. Disable it; the JVM shutdown hook on the
      // DockerComposeContainer handles teardown for this suite.
      existing ++
        explicit.orElse(derived).map("DOCKER_HOST" -> _).toMap +
        ("TESTCONTAINERS_RYUK_DISABLED" -> "true")
    }
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
