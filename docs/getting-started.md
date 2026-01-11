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

Add the following to your `build.sbt`:

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.1.0-SNAPSHOT"

// For testing support
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "0.1.0-SNAPSHOT" % Test

// For Optimizely integration
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "0.1.0-SNAPSHOT"
```

## Basic Usage

### Setting Up the Service

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object MyApp extends ZIOAppDefault:

  val program = for
    flags <- ZIO.service[FeatureFlags]
    _     <- flags.initialize
    // Use feature flags...
  yield ()

  def run = program.provide(
    FeatureFlags.live,
    TestFeatureProvider.layer(Map("my-feature" -> true))
  )
```

### Evaluating Flags

```scala
// Boolean flags
val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
  ZIO.serviceWithZIO[FeatureFlags](_.getBooleanValue("feature", false))

// String flags
val variant: ZIO[FeatureFlags, FeatureFlagError, String] =
  ZIO.serviceWithZIO[FeatureFlags](_.getStringValue("variant", "default"))

// Integer flags
val limit: ZIO[FeatureFlags, FeatureFlagError, Int] =
  ZIO.serviceWithZIO[FeatureFlags](_.getIntValue("limit", 100))

// Double flags
val rate: ZIO[FeatureFlags, FeatureFlagError, Double] =
  ZIO.serviceWithZIO[FeatureFlags](_.getDoubleValue("rate", 0.5))

// Typed flags with FlagType
val count: ZIO[FeatureFlags, FeatureFlagError, Long] =
  ZIO.serviceWithZIO[FeatureFlags](_.getValue[Long]("count", 0L))
```

### Using Evaluation Context

```scala
// Create context with targeting key
val ctx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")

// Evaluate with context
ZIO.serviceWithZIO[FeatureFlags](
  _.getBooleanValue("premium-feature", false, ctx)
)
```

## Next Steps

- Learn about [Evaluation Context]({{ site.baseurl }}/context) for targeted flag evaluation
- Explore [Hooks]({{ site.baseurl }}/hooks) for cross-cutting concerns
- Use [Transactions]({{ site.baseurl }}/transactions) for flag overrides
- Check out the [Testkit]({{ site.baseurl }}/testkit) for testing
