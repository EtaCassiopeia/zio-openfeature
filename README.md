# ZIO OpenFeature

A type-safe, ZIO-native implementation of the [OpenFeature](https://openfeature.dev/) specification for Scala 3.

## Features

- Type-safe flag evaluation with `FlagType` type class
- Hierarchical evaluation context (global, scoped, transaction, invocation)
- Hook system for cross-cutting concerns (logging, metrics, validation)
- Transaction support with override injection and evaluation tracking
- Testkit module for easy testing
- Optimizely provider implementation

## Requirements

- Scala 3.3+
- ZIO 2.1+

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

Add the following to your `build.sbt`:

```scala
val zioOpenFeatureVersion = "0.1.0"

libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % zioOpenFeatureVersion

// For testing support
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % zioOpenFeatureVersion % Test

// For Optimizely integration
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % zioOpenFeatureVersion
```

## Quick Start

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object MyApp extends ZIOAppDefault:

  val program = for
    flags   <- ZIO.service[FeatureFlags]
    enabled <- flags.boolean("my-feature", false)
    _       <- ZIO.when(enabled)(ZIO.debug("Feature is enabled!"))
  yield ()

  def run = program.provide(
    FeatureFlags.live,
    TestFeatureProvider.layer(Map("my-feature" -> true))
  )
```

## Core Concepts

### Flag Evaluation

```scala
// Boolean flags
val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
  FeatureFlags.boolean("feature", false)

// String flags
val variant: ZIO[FeatureFlags, FeatureFlagError, String] =
  FeatureFlags.string("variant", "default")

// Typed flags with FlagType
val count: ZIO[FeatureFlags, FeatureFlagError, Int] =
  FeatureFlags.value[Int]("count", 0)
```

### Evaluation Context

```scala
// Create context with targeting key
val ctx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")

// Evaluate with context
FeatureFlags.boolean("premium-feature", false, ctx)

// Set global context
FeatureFlags.setGlobalContext(ctx)

// Scoped context for a block of code
FeatureFlags.withContext(ctx) {
  FeatureFlags.boolean("feature", false)
}
```

### Hooks

```scala
// Add logging hook
val loggingHook = FeatureHook.logging()
FeatureFlags.addHook(loggingHook)

// Add metrics hook
val metricsHook = FeatureHook.metrics { (key, duration, success) =>
  ZIO.succeed(println(s"Flag $key evaluated in ${duration.toMillis}ms (success=$success)"))
}
FeatureFlags.addHook(metricsHook)

// Context validation hook
val validationHook = FeatureHook.contextValidator(requireTargetingKey = true)
FeatureFlags.addHook(validationHook)
```

### Transactions

```scala
// Run code with flag overrides
val overrides = Map("feature-a" -> true, "feature-b" -> "variant-x")

val result = FeatureFlags.transaction(overrides) {
  for
    a <- FeatureFlags.boolean("feature-a", false)
    b <- FeatureFlags.string("feature-b", "default")
  yield (a, b)
}

// Access transaction result with evaluation tracking
result.map { txResult =>
  println(s"Result: ${txResult.result}")
  println(s"Flags evaluated: ${txResult.flagCount}")
  println(s"Overrides used: ${txResult.overrideCount}")
}
```

## Custom Provider

Implement the `FeatureProvider` trait:

```scala
class MyProvider extends FeatureProvider:
  val metadata = ProviderMetadata("MyProvider", "1.0.0")

  def status = Ref.make(ProviderStatus.Ready).flatMap(_.get)
  def events = ZStream.empty
  def initialize = ZIO.unit
  def shutdown = ZIO.unit

  def resolveBooleanValue(
    key: String,
    defaultValue: Boolean,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    // Your implementation
    ZIO.succeed(FlagResolution.default(key, defaultValue))

  // Implement other resolve methods...
```

## Testing

Use the testkit module for testing:

```scala
import zio.openfeature.testkit.*

val testLayer = TestFeatureProvider.layer(Map(
  "feature-a" -> true,
  "feature-b" -> "variant-1",
  "count" -> 42
))

val test = for
  provider <- ZIO.service[TestFeatureProvider]
  _        <- provider.initialize

  // Verify flag evaluations
  _        <- myCode.provide(FeatureFlags.live, testLayer)

  // Check what was evaluated
  was      <- provider.wasEvaluated("feature-a")
  count    <- provider.evaluationCount("feature-a")
yield assertTrue(was) && assertTrue(count == 1)
```

## Modules

- **core**: Core abstractions and FeatureFlags service
- **testkit**: Testing utilities including TestFeatureProvider
- **optimizely**: Optimizely feature flag provider

## Documentation

Full documentation is available at: https://etacassiopeia.github.io/zio-openfeature/

- [Getting Started](https://etacassiopeia.github.io/zio-openfeature/getting-started)
- [Architecture](https://etacassiopeia.github.io/zio-openfeature/architecture) - Core concepts and design
- [Evaluation Context](https://etacassiopeia.github.io/zio-openfeature/context)
- [Hooks](https://etacassiopeia.github.io/zio-openfeature/hooks)
- [Transactions](https://etacassiopeia.github.io/zio-openfeature/transactions)
- [Testkit](https://etacassiopeia.github.io/zio-openfeature/testkit)
- [Providers](https://etacassiopeia.github.io/zio-openfeature/providers)

## License

Apache 2.0
