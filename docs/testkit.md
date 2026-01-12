---
layout: default
title: Testkit
nav_order: 7
---

# Testkit
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The testkit module provides `TestFeatureProvider`, a configurable in-memory provider designed for testing. It allows you to:

- Pre-configure flag values
- Dynamically update flags during tests
- Track which flags were evaluated
- Verify evaluation counts and contexts

## Installation

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "0.1.0" % Test
```

## Basic Usage

### Creating a Test Provider

```scala
import zio.*
import zio.test.*
import zio.openfeature.*
import zio.openfeature.testkit.*

// Create with initial flags
val provider = TestFeatureProvider.make(Map(
  "feature-a" -> true,
  "feature-b" -> "variant-1",
  "max-items" -> 100
))

// Create empty provider
val emptyProvider = TestFeatureProvider.make
```

### Using as a Layer

```scala
val testLayer = TestFeatureProvider.layer(Map(
  "feature" -> true
))

val test = for
  flags  <- ZIO.service[FeatureFlags]
  result <- flags.boolean("feature", false)
yield assertTrue(result == true)

test.provide(FeatureFlags.live, testLayer)
```

## Managing Flags

### Setting Flags

```scala
for
  provider <- TestFeatureProvider.make
  _        <- provider.setFlag("new-flag", true)
  _        <- provider.setFlag("count", 42)
  _        <- provider.setFlag("name", "test")
yield ()
```

### Replacing All Flags

```scala
provider.setFlags(Map(
  "flag-1" -> true,
  "flag-2" -> "value"
))
// Previous flags are removed
```

### Removing Flags

```scala
// Remove single flag
provider.removeFlag("flag-to-remove")

// Clear all flags
provider.clearFlags
```

## Tracking Evaluations

### Check If Flag Was Evaluated

```scala
for
  provider <- TestFeatureProvider.make(Map("feature" -> true))
  _        <- provider.initialize
  _        <- provider.resolveBooleanValue("feature", false, EvaluationContext.empty)
  was      <- provider.wasEvaluated("feature")
  wasNot   <- provider.wasEvaluated("other-flag")
yield assertTrue(was) && assertTrue(!wasNot)
```

### Count Evaluations

```scala
for
  provider <- TestFeatureProvider.make(Map("feature" -> true))
  _        <- provider.resolveBooleanValue("feature", false, EvaluationContext.empty)
  _        <- provider.resolveBooleanValue("feature", false, EvaluationContext.empty)
  _        <- provider.resolveBooleanValue("feature", false, EvaluationContext.empty)
  count    <- provider.evaluationCount("feature")
yield assertTrue(count == 3)
```

### Get All Evaluations

```scala
for
  provider <- TestFeatureProvider.make
  _        <- provider.resolveBooleanValue("flag-a", false, EvaluationContext("user-1"))
  _        <- provider.resolveStringValue("flag-b", "", EvaluationContext("user-2"))
  evals    <- provider.getEvaluations
yield
  // evals is List[(String, EvaluationContext)]
  assertTrue(evals.length == 2)
```

### Clear Evaluation History

```scala
provider.clearEvaluations
```

## Provider Status

### Managing Status

```scala
for
  provider <- TestFeatureProvider.make
  initial  <- provider.status
  _        <- provider.initialize
  ready    <- provider.status
  _        <- provider.setStatus(ProviderStatus.Error)
  error    <- provider.status
yield
  assertTrue(initial == ProviderStatus.NotReady) &&
  assertTrue(ready == ProviderStatus.Ready) &&
  assertTrue(error == ProviderStatus.Error)
```

### Emitting Events

```scala
provider.emitEvent(ProviderEvent.ConfigurationChanged(
  Set("flag-1", "flag-2"),
  provider.metadata
))
```

## Testing Patterns

### Testing Feature Flag Logic

```scala
object MyServiceSpec extends ZIOSpecDefault:
  def spec = suite("MyService")(
    test("shows premium content for premium users") {
      val testFlags = TestFeatureProvider.layer(Map(
        "premium-content" -> true
      ))

      for
        result <- MyService.getContent("user-123")
      yield assertTrue(result.hasPremiumContent)
    }.provide(MyService.live, FeatureFlags.live, testFlags)
  )
```

### Testing Multiple Scenarios

```scala
suite("Feature variations")(
  test("enabled") {
    testWithFlags(Map("feature" -> true)) {
      for result <- myLogic yield assertTrue(result.featureEnabled)
    }
  },
  test("disabled") {
    testWithFlags(Map("feature" -> false)) {
      for result <- myLogic yield assertTrue(!result.featureEnabled)
    }
  }
)

def testWithFlags[R, E, A](flags: Map[String, Any])(
  test: ZIO[R & FeatureFlags, E, A]
): ZIO[R, E, A] =
  test.provide(
    FeatureFlags.live,
    TestFeatureProvider.layer(flags)
  )
```

### Verifying Flag Usage

```scala
test("service evaluates expected flags") {
  for
    provider <- TestFeatureProvider.make(Map(
      "feature-a" -> true,
      "feature-b" -> "variant"
    ))
    _        <- provider.initialize
    _        <- ZIO.serviceWithZIO[FeatureFlags](_.initialize)
    _        <- MyService.doSomething
    wasA     <- provider.wasEvaluated("feature-a")
    wasB     <- provider.wasEvaluated("feature-b")
    wasC     <- provider.wasEvaluated("feature-c")
  yield
    assertTrue(wasA) &&
    assertTrue(wasB) &&
    assertTrue(!wasC)  // Should not evaluate feature-c
}.provide(
  FeatureFlags.live,
  ZLayer.fromZIO(TestFeatureProvider.make)
)
```

### Testing Context Propagation

```scala
test("context is passed to provider") {
  val ctx = EvaluationContext("user-123")
    .withAttribute("plan", "premium")

  for
    provider <- TestFeatureProvider.make(Map("feature" -> true))
    _        <- provider.resolveBooleanValue("feature", false, ctx)
    evals    <- provider.getEvaluations
    (_, evalCtx) = evals.head
  yield
    assertTrue(evalCtx.targetingKey == Some("user-123")) &&
    assertTrue(evalCtx.attributes.contains("plan"))
}
```
