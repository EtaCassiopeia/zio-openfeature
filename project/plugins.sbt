addSbtPlugin("org.scalameta"      % "sbt-scalafmt"         % "2.5.6")
addSbtPlugin("org.scoverage"      % "sbt-scoverage"        % "2.0.12")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"       % "1.12.1")
addSbtPlugin("net.nmoncho"        % "sbt-dependency-check" % "1.9.2")
addSbtPlugin("com.typesafe"       % "sbt-mima-plugin"      % "1.1.6")
// TASTy compatibility of the Scala 3 artifacts, which MiMa's bytecode check does not see. See RELEASING.md.
addSbtPlugin("ch.epfl.scala" % "sbt-tasty-mima" % "1.4.0")
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s"        % "1.1.1")
// Opt-in per module (`noTrigger`): only `providerFaultTests` enables it. See the note on that module in build.sbt.
addSbtPlugin("io.github.achird-labs" % "sbt-rift" % "0.4.0")
