# ZIO OpenFeature

[![CI](https://github.com/EtaCassiopeia/zio-openfeature/actions/workflows/ci.yml/badge.svg)](https://github.com/EtaCassiopeia/zio-openfeature/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3)](https://central.sonatype.com/artifact/io.github.etacassiopeia/zio-openfeature-core_3)
[![Docs](https://img.shields.io/badge/docs-etacassiopeia.github.io%2Fzio--openfeature-blue)](https://etacassiopeia.github.io/zio-openfeature/)

Type-safe feature flags for ZIO, on top of the [OpenFeature](https://openfeature.dev) Java SDK. Use any OpenFeature
provider (LaunchDarkly, flagd, Flagsmith, Flipt, OFREP, Optimizely, …) and read flags as ZIO effects with typed
errors, layered targeting context, ZIO hooks, transactions and provider events as a stream.

<p align="center">
  <img src="docs/assets/evaluation.svg" width="100%" alt="One flag evaluation through the pipeline: context merge, before hooks, transaction, status gate, provider, then after or error hooks and finallyAfter, shown for a plain read, a transaction override and a missing flag.">
</p>

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core"    % "<version>",
  "io.github.etacassiopeia" %% "zio-openfeature-extras"  % "<version>", // EnvVarProvider, below
  "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
)
```

```scala
object MyApp extends ZIOAppDefault:

  val program: ZIO[FeatureFlags, Nothing, Unit] =
    for
      // never fails: serves false if the flag is missing or the provider is down
      enabled <- FeatureFlags.booleanOrDefault("my-feature", default = false)
      _       <- ZIO.when(enabled)(ZIO.logInfo("my-feature is on"))
    yield ()

  // Flags from environment variables (FF_MY_FEATURE=true); swap in any OpenFeature provider
  def run = program.provide(FeatureFlags.fromProvider(EnvVarProvider()))

// In tests, the in-memory provider from the testkit:
//   MyApp.program.provide(TestFeatureProvider.layer(Map("my-feature" -> true)))
```

`booleanOrDefault` never fails; `boolean` fails with a `FeatureFlagError`, for gates that must fail closed.

**[Documentation](https://etacassiopeia.github.io/zio-openfeature/)**: modules, requirements, typed flags, providers,
testing, and [spec compliance](https://etacassiopeia.github.io/zio-openfeature/spec-compliance/) with the supported
OpenFeature spec and SDK versions. Apache 2.0.
