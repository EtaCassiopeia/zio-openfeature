# ZIO OpenFeature

A ZIO-native wrapper around the [OpenFeature](https://openfeature.dev/) Java SDK for Scala 2.13 and Scala 3.

## What is ZIO OpenFeature?

ZIO OpenFeature provides a type-safe, functional interface for feature flag evaluation using any OpenFeature-compatible provider. It wraps the OpenFeature Java SDK, giving you:

- **Any OpenFeature Provider**: Use LaunchDarkly, Flagsmith, CloudBees, Flipt, or any other OpenFeature provider
- **Type Safety**: Compile-time guarantees with the `FlagType` type class
- **ZIO Integration**: First-class effect handling, resource management, and fiber-local context
- **Transactions**: Scoped flag overrides with evaluation caching and tracking
- **Hooks**: Cross-cutting concerns for logging, metrics, and validation

## Requirements

- Scala 2.13+ or Scala 3.3+
- ZIO 2.1+
- Java 11+

## Version Compatibility

| ZIO OpenFeature | OpenFeature Spec | OpenFeature Java SDK |
|:----------------|:-----------------|:---------------------|
| 1.0.0-RC2 (latest published) | [v0.8.0](https://github.com/open-feature/spec/releases/tag/v0.8.0) | 1.20.2 |
| 1.0.0 (upcoming) | [v0.8.0](https://github.com/open-feature/spec/releases/tag/v0.8.0) | 1.21.0 |

This library implements the **dynamic-context paradigm** (server-side) of the OpenFeature specification. See [Spec Compliance](https://etacassiopeia.github.io/zio-openfeature/spec-compliance) for details.

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

Replace `<version>` below with the version shown in the badge above.

```scala
// Core library (includes OpenFeature SDK)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>"

// Built-in providers: HOCON, env vars, caching wrapper (optional)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-extras" % "<version>"

// OFREP — OpenFeature Remote Evaluation Protocol provider (HTTP)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"

// Optimizely Feature Experimentation — direct integration on top of the Optimizely Java SDK
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"

// For testing
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

### Snapshot builds

Every commit merged to `main` publishes a `-SNAPSHOT` to the Sonatype Central snapshots repository (via
[`snapshot.yml`](.github/workflows/snapshot.yml), after CI passes on that commit), so you can try unreleased
changes ahead of the next tagged release. Add the snapshots resolver and depend on a snapshot version:

```scala
resolvers += "Sonatype Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"

libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % "<snapshot-version>"
```

Snapshots share a single moving coordinate — the next version with a `-SNAPSHOT` suffix (e.g. `1.0.0-RC3-SNAPSHOT`
while `v1.0.0-RC2` is the latest release tag). Every `main` commit republishes to that same version, so you can pin
it and pull the newest build via a normal `-SNAPSHOT` refresh. The current coordinate is the latest tag with its
final segment bumped; find it in the
[snapshots repository](https://central.sonatype.com/repository/maven-snapshots/io/github/etacassiopeia/) or the
`Publish Snapshot` Actions log. Snapshots are unstable and may change at any time — pin a released version for
anything you ship.

## Quick Start

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object MyApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("my-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature is enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> TestFeatureProvider.layer(Map("my-feature" -> true))
  )
```

## Using OpenFeature Providers

ZIO OpenFeature works with any OpenFeature Java SDK provider:

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider

object ProductionApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("new-checkout", default = false)
    variant <- FeatureFlags.string("button-color", default = "blue")
  yield (enabled, variant)

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(new FlagdProvider())
  )
```

### Using Optimizely Feature Experimentation

`zio-openfeature-optimizely` is the first-party integration with [Optimizely](https://www.optimizely.com/products/feature-experimentation/). It validates the SDK key before constructing, uses the default 30 s `initTimeout`, and composes cleanly with `CircuitBreakerProvider` from `zio-openfeature-extras` for production resilience. See the [Optimizely guide]({{ site.baseurl }}/optimizely) for the full story (init timeout tuning, self-hosted Agent, what to alert on).

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.optimizely.OptimizelyProvider

object MyApp extends ZIOAppDefault:

  def run = ZIO.scoped {
    for
      sdkKey   <- ZIO.attempt(sys.env("OPTIMIZELY_SDK_KEY"))
      provider <- OptimizelyProvider.make(sdkKey).mapError(e => new RuntimeException(e.message))
      env      <- FeatureFlags.fromProviderAsync(provider).build
      ff        = env.get[FeatureFlags]
      enabled  <- ff.boolean("new-checkout", default = false).mapError(e => new RuntimeException(e.message))
      _        <- ZIO.logInfo(s"new-checkout = $enabled")
    yield ()
  }
```

### Popular Providers

| Provider | Dependency |
|----------|------------|
| [Optimizely](https://www.optimizely.com/) | `"io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"` (this library) |
| [OFREP](https://github.com/open-feature/protocol) | `"io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"` (this library) |
| [flagd](https://flagd.dev/) | `"dev.openfeature.contrib.providers" % "flagd" % "x.y.z"` |
| [LaunchDarkly](https://launchdarkly.com/) | `"dev.openfeature.contrib.providers" % "launchdarkly" % "x.y.z"` |
| [Flagsmith](https://flagsmith.com/) | `"dev.openfeature.contrib.providers" % "flagsmith" % "x.y.z"` |
| [Flipt](https://flipt.io/) | `"dev.openfeature.contrib.providers" % "flipt" % "x.y.z"` |

See the [OpenFeature ecosystem](https://openfeature.dev/ecosystem) for all available providers.

## Core Concepts

### Flag Evaluation

```scala
// Boolean flags
val enabled = FeatureFlags.boolean("feature", default = false)

// String flags
val variant = FeatureFlags.string("variant", default = "control")

// Numeric flags
val limit = FeatureFlags.int("max-items", default = 100)
val rate  = FeatureFlags.double("sample-rate", default = 0.1)

// Detailed evaluation with metadata
val details = FeatureFlags.booleanDetails("feature", default = false)
details.map { resolution =>
  println(s"Value: ${resolution.value}")
  println(s"Reason: ${resolution.reason}")
  println(s"Variant: ${resolution.variant}")
}
```

### Evaluation Context

```scala
// Create context for targeting
val ctx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")

// Evaluate with context
FeatureFlags.boolean("premium-feature", default = false, ctx)

// Set global context for all evaluations
FeatureFlags.setGlobalContext(ctx)

// Scope context to a block
FeatureFlags.withContext(ctx) {
  FeatureFlags.boolean("feature", default = false)
}
```

### Transactions

```scala
// Run code with flag overrides and evaluation tracking
val result = FeatureFlags.transaction(
  overrides = Map("feature-a" -> true, "max-items" -> 50)
) {
  for
    a <- FeatureFlags.boolean("feature-a", default = false)
    n <- FeatureFlags.int("max-items", default = 10)
  yield (a, n)
}

result.map { txResult =>
  println(s"Result: ${txResult.result}")        // (true, 50)
  println(s"Flags evaluated: ${txResult.flagCount}")
  println(s"Overrides used: ${txResult.overrideCount}")
}
```

### Hooks

```scala
// Add logging
FeatureFlags.addHook(FeatureHook.logging())

// Add metrics
FeatureFlags.addHook(FeatureHook.metrics { (key, duration, success) =>
  ZIO.succeed(println(s"Flag $key evaluated in ${duration.toMillis}ms"))
})

// Validate context
FeatureFlags.addHook(FeatureHook.contextValidator(requireTargetingKey = true))
```

### Tracking

```scala
// Track user actions
FeatureFlags.track("button-clicked")

// Track with details
val details = TrackingEventDetails(value = Some(99.99))
FeatureFlags.track("purchase", EvaluationContext("user-123"), details)
```

### Event Handlers

```scala
// React to provider events
FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo(s"Provider ${metadata.name} ready")
}

FeatureFlags.onConfigurationChanged { (flags, _) =>
  ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}")
}
```

## Modules

| Module | Description |
|--------|-------------|
| **core** | ZIO wrapper around OpenFeature SDK with FeatureFlags service |
| **testkit** | TestFeatureProvider for testing without external dependencies |

## Documentation

Full documentation: https://etacassiopeia.github.io/zio-openfeature/

- [Getting Started](https://etacassiopeia.github.io/zio-openfeature/getting-started) - Installation and basic usage
- [Architecture](https://etacassiopeia.github.io/zio-openfeature/architecture) - Design and components
- [Providers](https://etacassiopeia.github.io/zio-openfeature/providers) - Using OpenFeature providers
- [Evaluation Context](https://etacassiopeia.github.io/zio-openfeature/context) - Targeting and context hierarchy
- [Hooks](https://etacassiopeia.github.io/zio-openfeature/hooks) - Cross-cutting concerns
- [Transactions](https://etacassiopeia.github.io/zio-openfeature/transactions) - Overrides and tracking
- [Testkit](https://etacassiopeia.github.io/zio-openfeature/testkit) - Testing utilities
- [Spec Compliance](https://etacassiopeia.github.io/zio-openfeature/spec-compliance) - OpenFeature specification compliance

## License

Apache 2.0
