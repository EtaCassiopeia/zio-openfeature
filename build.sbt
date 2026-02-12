import xerial.sbt.Sonatype.sonatypeCentralHost
import net.nmoncho.sbt.dependencycheck.settings._

val scala213Version       = "2.13.16"
val scala3Version         = "3.3.4"
val zioVersion            = "2.1.14"
val openFeatureSdkVersion = "1.20.1"

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
  )
) ++ crossVersionSourceDirs

lazy val root = (project in file("."))
  .aggregate(core, testkit)
  .settings(
    name                           := "zio-openfeature",
    publish / skip                 := true,
    dependencyCheckFailBuildOnCVSS := 7,
    dependencyCheckNvdApi          := NvdApiSettings(apiKey = sys.env.getOrElse("NVD_API_KEY", ""))
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
