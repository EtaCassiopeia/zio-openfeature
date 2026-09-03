import net.nmoncho.sbt.dependencycheck.settings._
import com.typesafe.tools.mima.core._

val scala213Version       = "2.13.16"
val scala3Version         = "3.3.4"
val zioVersion            = "2.1.14"
val zioBddVersion         = "1.4.4"
val openFeatureSdkVersion = "1.22.1"

// Test-only HTTP stubbing for the ofrep and optimizely provider suites. Declared once rather than per module:
// the two had drifted apart before, and a WireMock bump is the single cheapest lever on this build's advisory
// count — it drags in the jetty / httpclient5 / handlebars / commons-* stack that most test-scope alerts sit in.
val wiremockVersion = "3.13.2"

// Jackson reaches us only through the OFREP contrib provider; see `jacksonPins` above the `ofrep` module.
// `jackson-annotations` is versioned WITHOUT a patch component from 2.20 onwards (jackson-bom 2.22.2 pins it
// to `2.22`), so it cannot share `jacksonVersion` — `2.22.2` does not exist for that artifact.
val jacksonVersion            = "2.22.2"
val jacksonAnnotationsVersion = "2.22"

// OpenFeature Specification Compatibility
// Spec version: v0.9.0 (https://github.com/open-feature/spec)
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

// Publishing to Sonatype Central. sbt-ci-release 1.12.0 dropped sbt-sonatype in favour of sbt 1.11+'s built-in Central
// Portal support, so there is no `sonatypeCredentialHost` to set: `publishTo` resolves to the Central snapshots repo
// for a `-SNAPSHOT` version and to local staging (uploaded by `sonaRelease`) for a release, and sbt reads the
// `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` env vars into credentials for `central.sonatype.com` on its own.
ThisBuild / versionScheme := Some("semver-spec")

// Stable moving snapshot coordinate. Between releases every `main` commit publishes to a SINGLE `<next>-SNAPSHOT`
// version — the most recent tag with its final number bumped (e.g. after `v1.0.0-RC2` → `1.0.0-RC3-SNAPSHOT`) — so
// consumers can pin one coordinate instead of sbt-dynver's per-commit `x.y.z+<n>-<sha>-SNAPSHOT`. A commit that sits
// exactly on a `v*` tag keeps that exact release version (no `-SNAPSHOT`), so `sbt ci-release` still publishes a real
// release on tags and a snapshot everywhere else. Computed from git directly (not `version.value`, which would be a
// circular self-reference); CI checks out full history so the tags are present.
ThisBuild / version := {
  import scala.sys.process._
  def git(cmd: String): Option[String] =
    scala.util.Try(cmd.!!).toOption.map(_.trim).filter(_.nonEmpty)
  git("git describe --tags --exact-match") match {
    case Some(tag) => tag.stripPrefix("v") // on a release tag → exact release version
    case None =>
      val lastTag = git("git describe --tags --abbrev=0").map(_.stripPrefix("v")).getOrElse("0.0.0")
      // bump the final numeric run of the last tag (RC2 -> RC3, 0.5.2 -> 0.5.3, 1.0.0 -> 1.0.1)
      val next = """(\d+)(\D*)$""".r.replaceAllIn(lastTag, m => (m.group(1).toInt + 1).toString + m.group(2))
      s"$next-SNAPSHOT"
  }
}

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

// Coverage is opt-in (`sbt coverage test coverageReport`), never wired into CI. No fail-on-minimum gate is set:
// under the pinned sbt-scoverage 2.0.9, coverage instrumentation fails to compile the testkit module on Scala 3.3.4
// (the instrumenter cannot rewrite `<FromJavaObject>` terms from the OpenFeature Java SDK), so an enforced 80% gate
// would be an unrunnable, always-red trap. The Scala Steward automation (.github/workflows/scala-steward.yml) will
// surface the sbt-scoverage 2.3.x bump; once instrumentation compiles again, a coverage CI job can be reintroduced.
ThisBuild / coverageEnabled := false

// Binary-compatibility check via sbt-mima. The API is frozen as of `1.0.0`, so each module baselines against its
// own most recent release — currently `1.1.0` (see `mimaPreviousArtifacts` in `commonSettings`):
// `sbt mimaReportBinaryIssues` catches
// accidental breaking changes on every PR, and an intentional break is whitelisted with a `mimaBinaryIssueFilters`
// rule scoped to the specific symbol — see https://github.com/lightbend/mima for the filter API. Bump the baseline
// to the previous release when cutting each subsequent version, per `RELEASING.md`, and delete the filters that
// existed only to excuse breaks the new baseline now contains: a filter kept past its release stops excusing
// anything and instead silently pre-authorises a future accidental break on the same symbol.
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

// A `dependencyOverrides` entry is resolution-only: sbt never writes it into the published POM, so a version pinned
// only that way protects this build and no consumer of the artifact (#402, where a `jackson-core` security pin
// reached zero consumers). Nothing could observe that — the weekly OWASP scan reads each module's *resolved*
// classpath, which is post-override, so it is green in exactly the state that is broken, and it is scheduled rather
// than PR-gating. This task reads the POM sbt would actually publish and asserts every override is declared in it.
// Deriving the expectation from `dependencyOverrides` rather than a hand-maintained "security pins" list is
// deliberate: an override that does not reach the POM has this bug's shape whatever its motive, and a list that must
// be maintained is a list that goes stale. See the `published-pom` CI job (#405).
val checkPublishedPins = taskKey[Unit](
  "Fail unless every dependencyOverrides entry is declared in this module's published POM"
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
  // Baseline against each module's last release (see the ThisBuild MiMa note above). Bump `"1.0.0"` to the previous
  // release version when cutting a new one, per `RELEASING.md`.
  mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "1.1.0"),
  checkPublishedPins    := checkPublishedPinsTask.value
) ++ crossVersionSourceDirs

// Shared body for `checkPublishedPins`, so the published modules and the aggregating root cannot drift.
// `Def.taskIf`, not `Def.task`: a `.value` is a dependency declaration rather than a call, so under `Def.task`
// both branches' inputs are forced and an unpublished project would still build a POM nobody reads.
lazy val checkPublishedPinsTask = Def.taskIf {
  if ((publish / skip).value)
    streams.value.log.info(s"${name.value}: not published, no POM to check")
  else {
    val log       = streams.value.log
    val module    = name.value
    val overrides = dependencyOverrides.value
    // Path-scoped, not a descendant search: a `<dependencyManagement>` entry is not a dependency a consumer
    // inherits, and must never be read as satisfying a pin.
    val declared = (scala.xml.XML.loadFile(makePom.value) \ "dependencies" \ "dependency").map { d =>
      val scope = (d \ "scope").text.trim
      // Absent scope means `compile`. `inherited` is the only thing that matters here: whether a consumer of
      // this artifact resolves this coordinate through us.
      val inherited = (d \ "optional").text.trim != "true" && scope != "test" && scope != "provided"
      ((d \ "groupId").text.trim, (d \ "artifactId").text.trim) -> ((d \ "version").text.trim, inherited)
    }.toMap
    val (sv, sbv) = (scalaVersion.value, scalaBinaryVersion.value)
    val problems = overrides.flatMap { m =>
      val artifact = CrossVersion(m.crossVersion, sv, sbv).fold(m.name)(rename => rename(m.name))
      declared.get((m.organization, artifact)) match {
        // Declared at a scope no consumer inherits. The pin then governs this build alone and there is nothing
        // downstream left resolving the old version — pinning a CVE out of the test-only HTTP stack is exactly
        // this shape, and "fixing" it by publishing a test dependency would be strictly worse.
        case Some((_, false))                                => None
        case Some((published, _)) if published == m.revision => None
        // Defence in depth, not the case that bites: sbt rewrites a declared dependency's version with its
        // override before writing the POM, so today this arm is unreachable for anything `libraryDependencies`
        // declares. It is the arm that would catch that behaviour changing.
        case Some((published, _)) =>
          Some(s"${m.organization}:$artifact is pinned to ${m.revision} but the POM declares $published")
        // The real #402 shape: an override on a coordinate that only arrives transitively writes nothing to the
        // POM at all, so the consumer resolves the vulnerable version this build never used.
        case None =>
          Some(s"${m.organization}:$artifact:${m.revision} is overrides-only — it is absent from the POM")
      }
    }
    // Report every violation: a first-failure-only report turns one bad edit into a fix-one-rerun loop.
    if (problems.nonEmpty)
      sys.error(
        s"$module: ${problems.size} dependencyOverrides pin(s) do not reach the published POM. An override is " +
          s"resolution-only. Declare the same version in libraryDependencies too (see `jacksonPins`) — or, if the " +
          s"pin is only needed for this build's own test classpath, declare it there at `% Test`, which the POM " +
          s"records without publishing it to consumers:" +
          problems.mkString("\n  - ", "\n  - ", "")
      )
    log.info(s"$module: ${overrides.size} dependencyOverrides pin(s), all reach the published POM")
  }
}

lazy val root = (project in file("."))
  // `conformance` is intentionally NOT aggregated: it is Scala 3 only, so aggregating it would make `++2.13.16 test`
  // try (and fail) to compile it. CI runs it explicitly on Scala 3 (see ci.yml), the same way `examples` are handled.
  .aggregate(core, testkit, extras, ofrep, optimizely)
  .settings(
    name           := "zio-openfeature",
    publish / skip := true,
    // Root does not take `commonSettings`, so it needs the key defined here for the bare aggregate invocation
    // (`sbt +checkPublishedPins`) to resolve; `publish / skip` above makes it a no-op on root itself.
    checkPublishedPins             := checkPublishedPinsTask.value,
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
      "dev.zio"        %% "zio"         % zioVersion,
      "dev.zio"        %% "zio-streams" % zioVersion,
      "dev.openfeature" % "sdk"         % openFeatureSdkVersion
    )
  )

// Extras module - built-in providers (HOCON, env vars, caching wrapper)
lazy val extras = (project in file("extras"))
  .dependsOn(core)
  .settings(
    name := "zio-openfeature-extras",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"     %% "zio"       % zioVersion,
      "dev.zio"     %% "zio-cache" % "0.2.3",
      "com.typesafe" % "config"    % "1.4.3"
    )
  )

// Patched, aligned Jackson family for the OFREP provider's transitive HTTP stack. Applied to `ofrep` both as
// `libraryDependencies` and as `dependencyOverrides` — the two do different jobs and only the pair is a fix:
//   - `dependencyOverrides` is resolution-only. sbt never writes it into the published POM, so on its own it
//     pins this build and leaves every consumer of `zio-openfeature-ofrep` resolving the provider's versions.
//   - `libraryDependencies` IS published, so consumers get the patched versions by nearest-wins (Maven; our
//     declaration outranks the provider's deeper one) and newest-wins (coursier/Gradle).
// Keeping the overrides as well guarantees no split family inside this build: Jackson supports only an aligned
// core/databind/jsr310 trio, and the previous core-only pin had produced exactly the skew it meant to prevent
// (core 2.21.2 against databind 2.19.2).
//
// MAINTENANCE: an override forces in BOTH directions, so these versions are a floor that must be raised when
// the OFREP provider bumps its own Jackson — otherwise a newer transitive Jackson is silently pulled back down
// to whatever is written here, visible only as an `(evicted by:)` line in a dependency tree nobody reads.
// Dropping the `libraryDependencies` half is caught by `checkPublishedPins`. Dropping the *override* half is not —
// that empties the expectation along with the pin — so the `published-pom` CI job's self-test re-adds the pre-#402
// shape on every run and requires the guard to reject it, which fails if either half has gone missing.
val jacksonPins = Seq(
  "com.fasterxml.jackson.core"     % "jackson-core"            % jacksonVersion,
  "com.fasterxml.jackson.core"     % "jackson-databind"        % jacksonVersion,
  "com.fasterxml.jackson.core"     % "jackson-annotations"     % jacksonAnnotationsVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion
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
      "dev.zio"                          %% "zio"      % zioVersion,
      "dev.openfeature.contrib.providers" % "ofrep"    % "0.0.2",
      "org.wiremock"                      % "wiremock" % wiremockVersion % Test
    ),
    // The pins predate contrib 0.0.2: 0.0.1 pulled jackson-databind 2.19.2, which carried five open advisories
    // plus an async-parser DoS on its jackson-core line (GHSA-r7wm-3cxj-wff9). 0.0.2 declares the 2.22.0 family,
    // which is outside that window — so these are no longer the fix for an advisory, and they stay for the other
    // two reasons: they hold the core/databind/jsr310 trio aligned (the skew `jacksonPins` documents), and the
    // `published-pom` guard is built on them. They remain a FLOOR, not a target — see `jacksonPins`.
    // Scoped to this module: core/extras/testkit carry no Jackson at all, and optimizely only jackson-annotations.
    libraryDependencies ++= jacksonPins,
    dependencyOverrides ++= jacksonPins
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
      "dev.zio" %% "zio" % zioVersion,
      // NOTE: on a core-api/core-httpclient upgrade, re-verify `com/optimizely/ab/ObservingOptimizelyHttpClient.java`
      // — it lives in the `com.optimizely.ab` package to reach the package-private `OptimizelyHttpClient` ctor and
      // `getHttpClient()` (the staleness fetch-observation seam for #267). A version bump breaks it at compile time.
      "com.optimizely.ab" % "core-api"             % "4.2.2",
      "com.optimizely.ab" % "core-httpclient-impl" % "4.2.2",
      "org.wiremock"      % "wiremock"             % wiremockVersion % Test
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
  // `extras` for the library suites: they evaluate against `HoconProvider`, `EnvVarProvider` and
  // `IntegerWideningLongProvider`.
  .dependsOn(core, testkit, extras)
  // "test->test" on optimizely additionally pulls its test classpath (not just main), so the
  // Optimizely flag-matrix suite below can reuse `RecommendationService`/`RecommendationResult`
  // from optimizely's own test sources instead of duplicating that toy SUT here.
  .dependsOn(optimizely % "test->test;compile->compile")
  .settings(
    name               := "zio-openfeature-conformance-zio-bdd",
    publish / skip     := true,
    crossScalaVersions := Seq(scala3Version),
    libraryDependencies ++= Seq(
      "dev.openfeature"          % "sdk"     % openFeatureSdkVersion % Test,
      "io.github.etacassiopeia" %% "zio-bdd" % zioBddVersion         % Test,
      // Rift's in-process HTTP mock engine (native, no Docker) replaces the WireMock + mitmproxy
      // container the matrix suites used to fake the Optimizely CDN. `-natives` bundles the engine
      // binaries; `-jdk21` is the JDK21 FFM binding (CI runs conformance on JDK 21).
      "io.github.etacassiopeia" %% "zio-bdd-rift-embedded-jdk21"   % zioBddVersion % Test,
      "io.github.etacassiopeia"  % "zio-bdd-rift-embedded-natives" % zioBddVersion % Test,
      "dev.zio"                 %% "zio-schema"                    % "1.6.6"       % Test,
      "dev.zio"                 %% "zio-schema-derivation"         % "1.6.6"       % Test
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
    // Run the suites sequentially. They share one in-process Rift engine (RiftEngine); letting sbt
    // run the suite classes concurrently races them to initialise that native engine, which on a CI
    // runner surfaces as an immediate "Interrupted" (intra-suite scenario parallelism is unaffected).
    Test / parallelExecution := false,
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

// Compiles the Typed Flags docs page's snippets against the real API, so a signature change to `FlagDef`, its
// evaluation overloads, `FlagType.derived`/`mapped` or the typed testkit fixtures breaks the build instead of
// silently staling the published page (#385).
lazy val examplesTypedFlags = (project in file("examples/typed-flags"))
  .dependsOn(core, testkit)
  .settings(examplesCommon)
  // The source lives in `scala-3/` because it is Scala-3-only (`enum`, `derives`) — and, less obviously, because
  // `.scalafmt.conf` pins every `src/main/scala/**` file to the scala213 dialect (those directories are shared by the
  // cross-built modules). A Scala 3 file under plain `scala/` therefore compiles but cannot be formatted.
  .settings(crossVersionSourceDirs)
  .settings(
    name := "zio-openfeature-example-typed-flags",
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
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
