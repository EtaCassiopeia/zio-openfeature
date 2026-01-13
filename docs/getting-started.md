---
layout: default
title: Getting Started
nav_order: 2
---

# Getting Started
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

Add the following to your `build.sbt`:

```scala
val zioOpenFeatureVersion = "0.2.0"

// Core library (includes OpenFeature SDK)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % zioOpenFeatureVersion

// For testing
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % zioOpenFeatureVersion % Test
```

You'll also need an OpenFeature provider for your feature flag service:

```scala
// Example: flagd provider
libraryDependencies += "dev.openfeature.contrib.providers" % "flagd" % "0.8.9"

// Or: LaunchDarkly
libraryDependencies += "dev.openfeature.contrib.providers" % "launchdarkly" % "1.1.0"
```

See the [OpenFeature ecosystem](https://openfeature.dev/ecosystem) for all available providers.

## Basic Usage

### Setting Up with a Provider

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider

object MyApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("my-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(new FlagdProvider())
  )
```

### Setting Up for Testing

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object TestApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("my-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> TestFeatureProvider.layer(Map("my-feature" -> true))
  )
```

## Evaluating Flags

### Boolean Flags

```scala
val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
  FeatureFlags.boolean("feature-toggle", default = false)
```

### String Flags

```scala
val variant: ZIO[FeatureFlags, FeatureFlagError, String] =
  FeatureFlags.string("button-color", default = "blue")
```

### Numeric Flags

```scala
val limit: ZIO[FeatureFlags, FeatureFlagError, Int] =
  FeatureFlags.int("max-items", default = 100)

val rate: ZIO[FeatureFlags, FeatureFlagError, Double] =
  FeatureFlags.double("sample-rate", default = 0.1)

val count: ZIO[FeatureFlags, FeatureFlagError, Long] =
  FeatureFlags.long("max-bytes", default = 1000000L)
```

### Object Flags

```scala
val config: ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
  FeatureFlags.obj("feature-config", default = Map("timeout" -> 30))
```

### Detailed Evaluation

Get full resolution details including variant, reason, and metadata:

```scala
val details: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
  FeatureFlags.booleanDetails("feature", default = false)

details.map { resolution =>
  println(s"Value: ${resolution.value}")
  println(s"Variant: ${resolution.variant}")
  println(s"Reason: ${resolution.reason}")
  println(s"Flag Key: ${resolution.flagKey}")
}
```

## Using Evaluation Context

Pass user and environment information for targeted flag evaluation:

```scala
// Create context with targeting key (user ID)
val ctx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("beta", true)

// Evaluate with context
FeatureFlags.boolean("premium-feature", default = false, ctx)
```

### Setting Global Context

Apply context to all evaluations:

```scala
val globalCtx = EvaluationContext.empty
  .withAttribute("app_version", "2.0.0")
  .withAttribute("environment", "production")

FeatureFlags.setGlobalContext(globalCtx)
```

### Scoped Context

Apply context to a block of code:

```scala
val requestCtx = EvaluationContext("user-456")
  .withAttribute("session_id", sessionId)

FeatureFlags.withContext(requestCtx) {
  // All evaluations in this block use requestCtx
  for
    a <- FeatureFlags.boolean("feature-a", default = false)
    b <- FeatureFlags.string("feature-b", default = "control")
  yield (a, b)
}
```

## Factory Methods

### From Any OpenFeature Provider

```scala
import dev.openfeature.sdk.FeatureProvider

val provider: FeatureProvider = // any OpenFeature provider

val layer = FeatureFlags.fromProvider(provider)
```

### With Domain Isolation

For multi-provider setups, use domains to isolate providers:

```scala
val layer = FeatureFlags.fromProviderWithDomain(provider, "my-domain")
```

### With Initial Hooks

```scala
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)

val layer = FeatureFlags.fromProviderWithHooks(provider, hooks)
```

## Tracking Events

Track user actions for analytics and experimentation:

```scala
// Simple event tracking
FeatureFlags.track("button-clicked")

// Track with user context
FeatureFlags.track("purchase", EvaluationContext("user-123"))

// Track with event details
val details = TrackingEventDetails(
  value = Some(99.99),
  attributes = Map("currency" -> "USD", "items" -> 3)
)
FeatureFlags.track("checkout", details)
```

## Event Handlers

React to provider lifecycle events:

```scala
// Handle provider ready
FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo(s"Provider ${metadata.name} is ready")
}

// Handle configuration changes
FeatureFlags.onConfigurationChanged { (flags, metadata) =>
  ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}")
}

// Handle errors
FeatureFlags.onProviderError { (error, metadata) =>
  ZIO.logError(s"Provider error: ${error.getMessage}")
}
```

## Next Steps

- Learn about [Evaluation Context]({{ site.baseurl }}/context) for targeted flag evaluation
- Explore [Hooks]({{ site.baseurl }}/hooks) for logging, metrics, and validation
- Use [Transactions]({{ site.baseurl }}/transactions) for flag overrides and tracking
- See [Testkit]({{ site.baseurl }}/testkit) for testing best practices
- Check [Providers]({{ site.baseurl }}/providers) for provider-specific features
- Review [Spec Compliance]({{ site.baseurl }}/spec-compliance) for OpenFeature compatibility
