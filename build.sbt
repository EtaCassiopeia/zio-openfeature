import net.nmoncho.sbt.dependencycheck.settings._
import com.typesafe.tools.mima.core._
import tastymima.intf.{ProblemKind, ProblemMatcher}

val scala213Version = "2.13.18"
val scala3Version   = "3.3.8"
val zioVersion      = "2.1.27"
val zioBddVersion   = "1.5.0"
// The embedded Rift engine zio-bdd-rift drives (Rift 0.18.0). Keep in step with zio-bdd-rift's own rift-java pin.
val riftJavaVersion       = "0.3.2"
val openFeatureSdkVersion = "1.23.0"
// `providerFaultTests` drives rift's TLS intercept proxy through rift-scala, which pins its own rift-java (0.3.7); the
// embedded engine and natives must match that, so this is separate from `riftJavaVersion` (zio-bdd-rift's pin).
val riftScalaVersion         = "0.4.0"
val riftJavaInterceptVersion = "0.3.7"

// Test-only HTTP stubbing for the ofrep and optimizely provider suites. Declared once rather than per module:
// the two had drifted apart before, and a WireMock bump is the single cheapest lever on this build's advisory
// count — it drags in the jetty / httpclient5 / handlebars / commons-* stack that most test-scope alerts sit in.
val wiremockVersion = "3.13.2"

// Jackson reaches us only through the OFREP contrib provider; see `jacksonPins` above the `ofrep` module.
// `jackson-annotations` is versioned WITHOUT a patch component from 2.20 onwards (jackson-bom 2.22.4 pins it
// to `2.22`), so it cannot share `jacksonVersion` — `2.22.4` does not exist for that artifact.
// 2.22.4 rejects a lone UTF-16 surrogate in a JSON field name (jackson-core #1683): such an OFREP object flag is a
// PARSE_ERROR with the default (#534).
val jacksonVersion            = "2.22.4"
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

ThisBuild / homepage   := Some(url("https://github.com/EtaCassiopeia/zio-openfeature"))
ThisBuild / licenses   := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
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
    case None      =>
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
    // A discarded value is how a ZIO effect gets built and silently never run, so main code may not discard one
    // without saying so (`: Unit`, `.unit`, `val _ =`). Unused imports, privates and locals are dead code. Unused
    // parameters are not checked: default hook stages and SDK overrides ignore theirs by design.
    case Some((3, _)) =>
      Seq(
        "-Xfatal-warnings",
        "-Yretain-trees",
        "-Wnonunit-statement",
        "-Wvalue-discard",
        "-Wunused:imports,privates,locals"
      )
    case _ => Seq()
  }
}

// Tests discard results all the time (a provider call made for its side effect), so there only a discarded ZIO effect
// fails the build: that is a test step that silently never runs. On 2.13, main code is held to zero warnings too, and
// the Scala 3 migration hints that only test code triggers are silenced.
lazy val lintSettings = Seq(
  // Test inherits Compile's options; on 2.13 only main code is held to zero warnings. On Scala 3 the last matching
  // `-Wconf` rule wins, so the effect-type errors come after the catch-all silencing.
  // The administration members moved to FeatureFlagsAdmin but stay supported for 1.x, and the specs that exercise them
  // through `FeatureFlags` keep doing so: that deprecation alone is silenced in tests.
  Test / scalacOptions := {
    val inherited    = (Test / scalacOptions).value
    val movedToAdmin = "-Wconf:cat=deprecation&msg=Moved to FeatureFlagsAdmin:s"
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => inherited.filterNot(_ == "-Werror") :+ "-Wconf:cat=scala3-migration:s" :+ movedToAdmin
      case Some((3, _)) =>
        inherited :+ "-Wconf:id=E175:s,id=E176:s,id=E175&msg=.*type zio\\.(ZIO|UIO|URIO|IO|Task|RIO)\\b.*:e,id=E176&msg=.*type zio\\.(ZIO|UIO|URIO|IO|Task|RIO)\\b.*:e" :+ movedToAdmin
      case _ => inherited
    }
  },
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Werror")
      case _            => Seq()
    }
  },
  // The doc task inherits the compiler options, but its warnings are about how links render, not about the code:
  // overloaded names, links into another module and links to Java classes. Kept fatal, they failed every release's
  // `packageDoc` on 2.13 while compiling stayed green.
  Compile / doc / scalacOptions := (Compile / doc / scalacOptions).value.filterNot(_ == "-Werror")
)

// Coverage is opt-in (`sbt coverage test coverageReport coverageAggregate`), so ordinary builds stay uninstrumented.
// The weekly `Coverage` workflow runs it on Scala 3 and reports per module (scripts/coverage-summary.py); there is no
// minimum yet. Instrumentation compiles every module on Scala 3.3.8: the old failure on the Java SDK's
// `<FromJavaObject>` terms (scoverage 2.0.9, Scala 3.3.4) no longer reproduces.
ThisBuild / coverageEnabled := false

// Binary-compatibility check via sbt-mima, and TASTy compatibility of the Scala 3 artifacts via sbt-tasty-mima. The
// API is frozen as of `1.0.0`, so each module baselines against its own most recent release — currently `1.2.1` (see
// `mimaPreviousArtifacts` in `commonSettings`). Which filters a minor release may carry is RELEASING.md's
// "Binary compatibility policy", enforced by `.github/scripts/check-mima-filters.sh`:
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
  // Baseline against each module's last release (see the ThisBuild MiMa note above). Bump the version to the previous
  // release version when cutting a new one, per `RELEASING.md`.
  mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "1.2.1"),
  // TASTy only exists for the Scala 3 artifacts.
  tastyMiMaPreviousArtifacts := { if (scalaBinaryVersion.value == "3") mimaPreviousArtifacts.value else Set.empty },
  checkPublishedPins         := checkPublishedPinsTask.value,
  // Test helpers every module's specs share (the property-test budget), compiled into each module's tests rather than
  // published or depended on.
  Test / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "test-support" / "src" / "test" / "scala"
) ++ crossVersionSourceDirs ++ lintSettings

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
    val problems  = overrides.flatMap { m =>
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

// The Optimizely SDK picks a JSON parser at runtime (gson, then jackson-databind, then org.json, then json-simple) and
// declares none of them, so a consumer that adds only `zio-openfeature-optimizely` gets no parser at all — the first
// datafile poll then dies with an unlogged `ExceptionInInitializerError` on the SDK's poller thread (#431). The module's
// own tests cannot see that: WireMock brings Jackson onto the Test classpath. So this reads the POM a consumer
// resolves, the only place the gap is visible. See the `published-pom` CI job.
val checkOptimizelyJsonParser = taskKey[Unit](
  "Fail unless the published optimizely POM gives consumers a JSON parser the Optimizely SDK can use"
)

lazy val root = (project in file("."))
  // `conformance` is intentionally NOT aggregated: it is Scala 3 only, so aggregating it would make `++2.13.18 test`
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
    ),
    // `private[openfeature]` internals compile to public bytecode on 2.13, so MiMa sees them. The swap's outcome signals
    // carry how the new provider settled; nothing outside the library can name them.
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[Problem]("zio.openfeature.internal.ProviderStatusMachine#Signal#SwapSucceeded*"),
      ProblemFilters.exclude[Problem]("zio.openfeature.internal.ProviderStatusMachine$Signal$SwapSucceeded*"),
      ProblemFilters.exclude[Problem]("zio.openfeature.internal.ProviderStatusMachine#Signal#RollbackSucceeded*"),
      ProblemFilters.exclude[Problem]("zio.openfeature.internal.ProviderStatusMachine$Signal$RollbackSucceeded*"),
      // A top-level `private` class, public bytecode on 2.13; it now carries the registry's config and holds its clients // as the instance.
      // non-public: private[openfeature]
      ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.FeatureFlagRegistryLive.this")
    ),
    // TASTy-MiMa checks `private[...]` members as well, so the internals and the instance's constructor are reported.
    tastyMiMaConfig ~= (_.withMoreProblemFilters(
      java.util.Arrays.asList(
        ProblemMatcher.make("zio.openfeature.internal.*"),
        // non-public: private[openfeature]
        ProblemMatcher.make("zio.openfeature.FeatureFlagsLive.<init>"),
        // A top-level `private` class, visible only inside `zio.openfeature`; it now carries the registry's config and
        // holds its clients as the instance.
        // non-public: private[openfeature]
        ProblemMatcher.make("zio.openfeature.FeatureFlagRegistryLive.<init>")
      )
    ))
  )

// Extras module - built-in providers (HOCON, env vars, caching wrapper)
lazy val extras = (project in file("extras"))
  // testkit's FaultyProvider for the wrapper specs (#463). Test scope only: consumers of extras never resolve testkit.
  .dependsOn(core, testkit % "test->compile")
  .settings(
    name := "zio-openfeature-extras",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"     %% "zio"       % zioVersion,
      "dev.zio"     %% "zio-cache" % "0.2.8",
      "com.typesafe" % "config"    % "1.4.9",
      // Linearizability checks of the lock-free state machines. Test scope only.
      "org.jetbrains.lincheck" % "lincheck" % "3.7" % Test
    ),
    // Lincheck's model checker instruments classes through a dynamically attached agent; JDK 21+ warns about that
    // unless it is enabled explicitly.
    Test / javaOptions += "-XX:+EnableDynamicAgentLoading",
    // CircuitState.Open gained the stale flag. The type is private[extras], which Scala 2.13 emits as public bytecode.
    mimaBinaryIssueFilters ++= Seq(
      // non-public: private[extras]
      ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("zio.openfeature.extras.CircuitState#Open.apply"),
      ProblemFilters.exclude[MissingTypesProblem]("zio.openfeature.extras.CircuitState$Open$")
    ),
    // The `reason` overload of `trip` is `private[extras]`; the public `trip()` is unchanged.
    tastyMiMaConfig ~= (_.withMoreProblemFilters(
      java.util.Arrays.asList(
        // non-public: private[extras]
        ProblemMatcher.make("zio.openfeature.extras.CircuitState.Open.*"),
        ProblemMatcher.make(ProblemKind.MissingTermMember, "zio.openfeature.extras.CircuitBreaker.trip")
      )
    ))
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
  // testkit's DifferentialLaws for OfrepDifferentialSpec (#526). Test scope only: consumers of ofrep never resolve
  // testkit.
  .dependsOn(core, testkit % "test->compile")
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
    // Here they are declared at compile scope because a consumer of this artifact resolves Jackson through us;
    // `optimizely` ships it too, for the Optimizely SDK's JSON parser (#431); `conformance-zio-bdd` repeats the
    // pin for its own test classpath only (see there).
    // `core`/`extras`/`testkit` carry no Jackson at all.
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
      "com.optimizely.ab" % "core-api"             % "4.6.0",
      "com.optimizely.ab" % "core-httpclient-impl" % "4.6.0",
      "org.wiremock"      % "wiremock"             % wiremockVersion % Test
    ),
    // The Optimizely SDK needs a JSON parser it does not declare (see `checkOptimizelyJsonParser`), so this module
    // ships jackson-databind at compile scope (#431). Jackson rather than gson because it is the family this build
    // already pins, scans and ships through `ofrep`, so a consumer of both modules resolves one JSON stack. A
    // consumer that also has gson gets gson (the SDK prefers it); `-Doptimizely.default_parser=` overrides either way.
    // The pins also hold WireMock's test-only Jackson 2.20.1 (inside the `[2.19.0, 2.21.4)` window of
    // GHSA-r7wm-3cxj-wff9 and siblings) at the patched family, the job they did alone before #431.
    libraryDependencies ++= jacksonPins,
    dependencyOverrides ++= jacksonPins,
    checkOptimizelyJsonParser := {
      val parsers = Set(
        "com.google.code.gson"       -> "gson",
        "com.fasterxml.jackson.core" -> "jackson-databind",
        "org.json"                   -> "json",
        "com.googlecode.json-simple" -> "json-simple"
      )
      // Same inherited-scope reading as `checkPublishedPinsTask`: a test, provided or optional declaration is in
      // the POM but no consumer resolves it, which is exactly the pre-#431 shape. Direct dependencies only — a
      // parser that merely arrives transitively is one upstream exclusion away from vanishing.
      val inherited = (scala.xml.XML.loadFile(makePom.value) \ "dependencies" \ "dependency").collect {
        case d
            if (d \ "optional").text.trim != "true" &&
              !Set("test", "provided").contains((d \ "scope").text.trim) =>
          (d \ "groupId").text.trim -> (d \ "artifactId").text.trim
      }.toSet
      val found = parsers.intersect(inherited)
      if (found.isEmpty)
        sys.error(
          s"${name.value}: the published POM gives consumers no JSON parser for the Optimizely SDK. It needs one of " +
            parsers.map { case (g, a) => s"$g:$a" }.toSeq.sorted.mkString(", ") +
            " at compile or runtime scope; without it the first datafile poll dies silently (#431)."
        )
      streams.value.log.info(
        s"${name.value}: consumers resolve a JSON parser via ${found.map { case (g, a) => s"$g:$a" }.mkString(", ")}"
      )
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

// Conformance module - runs the OpenFeature specification's canonical gherkin feature files verbatim (via Cucumber on
// JUnit) against the ZIO `FeatureFlags` API. Not published; Scala 3 only. Kept as a separate module so the Cucumber /
// JUnit test stack stays out of the published `testkit` artifact's dependency surface. The `.feature` files are
// vendored under src/test/resources (pinned to an upstream spec commit) so the build is hermetic.
lazy val conformance = (project in file("conformance"))
  .settings(lintSettings)
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

// The `rift-java-natives` classifier for the JVM running the build. `None` on a host with no published natives
// (e.g. Windows): the dependency is then omitted and `EmbeddedRift.requireAvailable` fails the suites loudly.
lazy val riftNativesClassifier: Option[String] = {
  val os   = System.getProperty("os.name").toLowerCase
  val arch = System.getProperty("os.arch").toLowerCase match {
    case "arm64"         => "aarch64"
    case "amd64" | "x64" => "x86_64"
    case other           => other
  }
  val platform =
    if (os.contains("mac") || os.contains("darwin")) Some("darwin")
    else if (os.contains("linux")) Some("linux")
    else None
  platform.filter(_ => arch == "aarch64" || arch == "x86_64").map(p => s"$p-$arch")
}

lazy val conformanceZioBdd = (project in file("conformance-zio-bdd"))
  .settings(lintSettings)
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
      // container the matrix suites used to fake the Optimizely CDN. `zio-bdd-rift` holds the adapter;
      // the engine (`rift-java-embedded`) and its per-platform natives are runtime ServiceLoader deps.
      "io.github.etacassiopeia" %% "zio-bdd-rift"          % zioBddVersion   % Test,
      "io.github.achird-labs"    % "rift-java-embedded"    % riftJavaVersion % Test,
      "dev.zio"                 %% "zio-schema"            % "1.6.6"         % Test,
      "dev.zio"                 %% "zio-schema-derivation" % "1.6.6"         % Test
    ) ++ riftNativesClassifier
      .map(c => ("io.github.achird-labs" % "rift-java-natives" % riftJavaVersion % Test).classifier(c)),
    // The `optimizely % "test->test"` edge above pulls WireMock's Jackson onto this module's test
    // classpath, and an override does not cross a project dependency — it governs the module it is
    // declared in — so the pin has to be repeated here. Nothing is published from this module, so
    // `libraryDependencies` has no POM to reach and `checkPublishedPins` is a no-op.
    dependencyOverrides ++= jacksonPins,
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
    // Rift's embedded engine uses the stable Foreign Function & Memory API, so this module's tests
    // require JDK 22+ (CI and the pre-push gate run them on JDK 25). On an older JDK the harnesses fail
    // loudly via `EmbeddedRift.requireAvailable` rather than skipping.
    Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
    // Run the suites sequentially. They share one in-process Rift engine (RiftEngine); letting sbt
    // run the suite classes concurrently races them to initialise that native engine, which on a CI
    // runner surfaces as an immediate "Interrupted" (intra-suite scenario parallelism is unaffected).
    Test / parallelExecution := false,
    Test / testFrameworks += new TestFramework("zio.bdd.ZIOBDDFramework")
  )

// Real-provider fault tests (#470): the real OFREP and Optimizely providers run through rift-scala's in-process TLS
// intercept proxy while the "network" refuses, stalls, truncates and resets. Local only, by project policy:
//   - NOT in the root `.aggregate(...)`, so `sbt test` and `sbt +test` never run it;
//   - CI only COMPILES it (`providerFaultTests/Test/compile`, the `examples` job) — no test step, secret or schedule;
//   - run it on a dev machine with JDK 22+: `sbt providerFaultTests/test` (see docs/development.md).
// On a host where rift's embedded engine cannot load, the suites report as ignored rather than failed.
//
// `RiftTlsPlugin` (sbt-rift) is enabled here and nowhere else: it is `noTrigger`, and its settings are all
// project-scoped. It generates a throwaway CA + truststore under this module's `target/rift`, forks the test JVM and
// points `javax.net.ssl.trustStore` at it — which Optimizely's Apache HttpClient needs, since it reads the truststore
// once at first use.
// The embedded engine and its natives, only when the JVM running the build can load them (JDK 22+). Its classes are
// Java 22 bytecode, so with them on an older JDK's test classpath even `Rift.isEmbeddedAvailable` throws
// `UnsupportedClassVersionError` instead of answering `false`; leaving them off is what lets `embeddedOnly` ignore the
// suites there. The test JVM is the build JVM (no `Test / javaHome`), so this is the right JVM to ask.
lazy val riftEngineDependencies: Seq[ModuleID] = {
  val spec  = System.getProperty("java.specification.version")
  val major = scala.util.Try(spec.stripPrefix("1.").takeWhile(_.isDigit).toInt).getOrElse(0)
  if (major >= 22)
    ("io.github.achird-labs" % "rift-java-embedded" % riftJavaInterceptVersion % Test) +:
      riftNativesClassifier.toSeq
        .map(c => ("io.github.achird-labs" % "rift-java-natives" % riftJavaInterceptVersion % Test).classifier(c))
  else Seq.empty
}

lazy val providerFaultTests = (project in file("provider-fault-tests"))
  .settings(lintSettings)
  .enablePlugins(RiftTlsPlugin)
  .dependsOn(core, extras, ofrep, optimizely)
  // Scala 3 only, and the sources live in `scala-3/` because `.scalafmt.conf` pins `src/test/scala/**` to the scala213
  // dialect (shared by the cross-built modules).
  .settings(crossVersionSourceDirs)
  .settings(
    name               := "zio-openfeature-provider-fault-tests",
    publish / skip     := true,
    crossScalaVersions := Seq(scala3Version),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio"               %% "zio-test"               % zioVersion       % Test,
      "dev.zio"               %% "zio-test-sbt"           % zioVersion       % Test,
      "io.github.achird-labs" %% "rift-scala-zio-testkit" % riftScalaVersion % Test
    ) ++ riftEngineDependencies,
    // OFREP and Optimizely pull Jackson; hold it at the family the published modules pin (see `jacksonPins`).
    dependencyOverrides ++= jacksonPins,
    // The plugin already forks the test JVM and, on JDK 21+, passes `--enable-native-access=ALL-UNNAMED` (it must not
    // pass it on older JDKs, where the flag stops the JVM from starting). The embedded engine itself needs JDK 22+.
    Test / fork := true,
    // The rift intercept proxy rewrites JVM-wide proxy and TLS settings: one suite class at a time.
    Test / parallelExecution := false
  )

// Coverage-guided fuzzing with Jazzer of the code that reads untrusted input: environment variables, HOCON, and OFREP
// responses. Like `providerFaultTests`, NOT in the root aggregate: `sbt test` never runs it, CI only compiles it, and
// the weekly `Fuzzing` workflow runs each target for a few minutes from the corpus in `fuzz/corpus/`. Run one locally
// (the forked JVM runs in `fuzz/`, so paths are relative to it; create `fuzz/target/corpus-envvar` first):
//   sbt "fuzz/Test/runMain com.code_intelligence.jazzer.Jazzer --target_class=zio.openfeature.fuzz.EnvVarFuzz
//        --instrumentation_includes=zio.openfeature.**:dev.openfeature.contrib.** --reproducer_path=target
//        -artifact_prefix=target/
//        target/corpus-envvar corpus/envvar -max_total_time=60"
lazy val fuzz = (project in file("fuzz"))
  .settings(lintSettings)
  .dependsOn(core, extras, ofrep)
  .settings(
    name                                          := "zio-openfeature-fuzz",
    publish / skip                                := true,
    crossScalaVersions                            := Seq(scala3Version),
    libraryDependencies += "com.code-intelligence" % "jazzer" % "0.30.0" % Test,
    dependencyOverrides ++= jacksonPins,
    Test / fork := true,
    // Jazzer instruments classes through an agent it attaches at startup; JDK 21+ warns about that unless enabled.
    Test / javaOptions += "-XX:+EnableDynamicAgentLoading"
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

// The documentation site's code. `mkdocs.yml` includes its `--8<--` sections into the pages, so every snippet on the
// site is compiled here, and its tests run the ones that can run on their own and check what the page says they do.
lazy val examplesDocs = (project in file("examples/docs"))
  .dependsOn(core, testkit, extras, ofrep, optimizely)
  .settings(examplesCommon)
  // The sources live in `scala-3/` because they are Scala 3 only, and because `.scalafmt.conf` pins every
  // `src/main/scala/**` file to the scala213 dialect (those directories are shared by the cross-built modules).
  .settings(crossVersionSourceDirs)
  .settings(
    name := "zio-openfeature-example-docs",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    // OFREP and Optimizely pull Jackson; hold it at the family the published modules pin (see `jacksonPins`).
    dependencyOverrides ++= jacksonPins,
    // Forked for the reason `commonSettings` gives: a leaked non-daemon thread cannot then hang sbt after the tests.
    Test / fork := true,
    // Several pages show `fromProvider` without a domain, which registers on the JVM-wide `OpenFeatureAPI`; run the
    // spec classes one at a time so their providers cannot replace each other mid-test.
    Test / parallelExecution := false,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
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
