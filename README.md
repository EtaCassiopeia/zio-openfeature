# ZIO OpenFeature

A ZIO-native wrapper around the [OpenFeature](https://openfeature.dev/) Java SDK for Scala 3.

## What is ZIO OpenFeature?

ZIO OpenFeature provides a type-safe, functional interface for feature flag evaluation using any OpenFeature-compatible provider. It wraps the OpenFeature Java SDK, giving you:

- **Any OpenFeature Provider**: Use LaunchDarkly, Flagsmith, CloudBees, Flipt, or any other OpenFeature provider
- **Type Safety**: Compile-time guarantees with the `FlagType` type class
- **ZIO Integration**: First-class effect handling, resource management, and fiber-local context
- **Transactions**: Scoped flag overrides with evaluation caching and tracking
- **Hooks**: Cross-cutting concerns for logging, metrics, and validation

## Requirements

- Scala 3.3+
- ZIO 2.1+

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

```scala
val zioOpenFeatureVersion = "0.2.0"

// Core library (includes OpenFeature SDK)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % zioOpenFeatureVersion

// For testing
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % zioOpenFeatureVersion % Test
```

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

### Popular Providers

| Provider | Dependency |
|----------|------------|
| [Optimizely](https://www.optimizely.com/) | `"dev.openfeature.contrib.providers" % "optimizely" % "x.y.z"` |
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
