import xerial.sbt.Sonatype.sonatypeCentralHost

val scala3Version = "3.3.4"
val zioVersion    = "2.1.14"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "io.github.etacassiopeia"

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

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-Yretain-trees"
)

ThisBuild / coverageEnabled          := false
ThisBuild / coverageMinimumStmtTotal := 80
ThisBuild / coverageFailOnMinimum    := true

lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  )
)

lazy val root = (project in file("."))
  .aggregate(core, testkit, optimizely)
  .settings(
    name           := "zio-openfeature",
    publish / skip := true
  )

lazy val core = (project in file("core"))
  .settings(
    name := "zio-openfeature-core",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion
    )
  )

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

lazy val optimizely = (project in file("optimizely"))
  .dependsOn(core, testkit % Test)
  .settings(
    name := "zio-openfeature-optimizely",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"              %% "zio"                  % zioVersion,
      "com.optimizely.ab"     % "core-api"             % "4.1.1",
      "com.optimizely.ab"     % "core-httpclient-impl" % "4.1.1",
      "com.google.code.gson"  % "gson"                 % "2.10.1"
    )
  )
