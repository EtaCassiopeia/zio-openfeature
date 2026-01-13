---
layout: default
title: Testkit
nav_order: 8
---

# Testkit
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The testkit module provides `TestFeatureProvider`, an in-memory OpenFeature provider designed for testing. It allows you to:

- Pre-configure flag values
- Dynamically update flags during tests
- Track which flags were evaluated
- Verify evaluation counts and contexts

The `TestFeatureProvider` implements the OpenFeature `FeatureProvider` interface, so it works seamlessly with the ZIO OpenFeature layer system.

---

## Installation

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "0.3.2" % Test
```

---

## Basic Usage

### Creating a Test Layer

The simplest way to use the testkit is with `TestFeatureProvider.layer`:

```scala
import zio.*
import zio.test.*
import zio.openfeature.*
import zio.openfeature.testkit.*

// Create layer with initial flags
val testLayer = TestFeatureProvider.layer(Map(
  "feature-a" -> true,
  "feature-b" -> "variant-1",
  "max-items" -> 100
))

// Use in tests
val test = for
  result <- FeatureFlags.boolean("feature-a", false)
yield assertTrue(result == true)

test.provide(Scope.default >>> testLayer)
```

### Creating a Provider Directly

For more control, create the provider directly:

```scala
for
  provider <- TestFeatureProvider.make(Map(
    "feature" -> true,
    "variant" -> "control"
  ))
  // Use provider methods directly
  _ <- provider.setFlag("new-flag", "value")
yield ()
```

---

## Managing Flags

### Setting Flags

```scala
for
  provider <- TestFeatureProvider.make(Map.empty)
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

---

## Tracking Evaluations

### Check If Flag Was Evaluated

```scala
for
  provider <- TestFeatureProvider.make(Map("feature" -> true))
  layer     = TestFeatureProvider.layerFrom(provider)
  _        <- FeatureFlags.boolean("feature", false).provide(Scope.default >>> layer)
  was      <- provider.wasEvaluated("feature")
  wasNot   <- provider.wasEvaluated("other-flag")
yield assertTrue(was) && assertTrue(!wasNot)
```

### Count Evaluations

```scala
for
  provider <- TestFeatureProvider.make(Map("feature" -> true))
  layer     = TestFeatureProvider.layerFrom(provider)
  _        <- FeatureFlags.boolean("feature", false).provide(Scope.default >>> layer)
  _        <- FeatureFlags.boolean("feature", false).provide(Scope.default >>> layer)
  _        <- FeatureFlags.boolean("feature", false).provide(Scope.default >>> layer)
  count    <- provider.evaluationCount("feature")
yield assertTrue(count == 3)
```

### Get All Evaluations

```scala
for
  provider <- TestFeatureProvider.make(Map("flag-a" -> true, "flag-b" -> "value"))
  layer     = TestFeatureProvider.layerFrom(provider)
  _        <- FeatureFlags.boolean("flag-a", false, EvaluationContext("user-1"))
               .provide(Scope.default >>> layer)
  _        <- FeatureFlags.string("flag-b", "", EvaluationContext("user-2"))
               .provide(Scope.default >>> layer)
  evals    <- provider.getEvaluations
yield
  // evals is List[(String, EvaluationContext)]
  assertTrue(evals.length == 2)
```

### Clear Evaluation History

```scala
provider.clearEvaluations
```

---

## Provider Status

### Managing Status

When using `TestFeatureProvider.layer`, the provider starts in `Ready` status. You can change the status for testing different scenarios:

```scala
for
  provider <- ZIO.service[TestFeatureProvider]
  initial  <- provider.status                    // Ready (after layer creation)
  _        <- provider.setStatus(ProviderStatus.Error)
  error    <- provider.status
  _        <- provider.setStatus(ProviderStatus.Stale)
  stale    <- provider.status
yield
  assertTrue(initial == ProviderStatus.Ready) &&
  assertTrue(error == ProviderStatus.Error) &&
  assertTrue(stale == ProviderStatus.Stale)
```

The `setStatus` method updates both the ZIO status and the underlying OpenFeature provider state.

### Emitting Events

```scala
provider.emitEvent(ProviderEvent.ConfigurationChanged(
  Set("flag-1", "flag-2"),
  provider.metadata
))
```

---

## Testing Patterns

### Simple Flag Testing

```scala
import zio.test.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object MyServiceSpec extends ZIOSpecDefault:
  def spec = suite("MyService")(
    test("shows premium content for premium users") {
      val testLayer = TestFeatureProvider.layer(Map(
        "premium-content" -> true
      ))

      for
        result <- MyService.getContent("user-123")
      yield assertTrue(result.hasPremiumContent)
    }.provide(
      MyService.live,
      Scope.default >>> testLayer
    )
  )
```

### Testing Multiple Scenarios

```scala
def testWithFlags[R, E, A](flags: Map[String, Any])(
  test: ZIO[R & FeatureFlags, E, A]
): ZIO[R, E, A] =
  test.provide(Scope.default >>> TestFeatureProvider.layer(flags))

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
```

### Verifying Flag Usage

```scala
test("service evaluates expected flags") {
  for
    provider <- TestFeatureProvider.make(Map(
      "feature-a" -> true,
      "feature-b" -> "variant"
    ))
    layer     = TestFeatureProvider.layerFrom(provider)
    _        <- MyService.doSomething.provide(Scope.default >>> layer)
    wasA     <- provider.wasEvaluated("feature-a")
    wasB     <- provider.wasEvaluated("feature-b")
    wasC     <- provider.wasEvaluated("feature-c")
  yield
    assertTrue(wasA) &&
    assertTrue(wasB) &&
    assertTrue(!wasC)  // Should not evaluate feature-c
}
```

### Testing Context Propagation

```scala
test("context is passed to provider") {
  val ctx = EvaluationContext("user-123")
    .withAttribute("plan", "premium")

  for
    provider <- TestFeatureProvider.make(Map("feature" -> true))
    layer     = TestFeatureProvider.layerFrom(provider)
    _        <- FeatureFlags.boolean("feature", false, ctx)
                 .provide(Scope.default >>> layer)
    evals    <- provider.getEvaluations
    (_, evalCtx) = evals.head
  yield
    assertTrue(evalCtx.targetingKey == Some("user-123")) &&
    assertTrue(evalCtx.attributes.contains("plan"))
}
```

### Using Transactions for Override Testing

Combine testkit with transactions for fine-grained control:

```scala
test("feature logic with overrides") {
  val baseLayer = TestFeatureProvider.layer(Map(
    "feature-a" -> true,
    "feature-b" -> false
  ))

  // Test with base values
  val baseTest = for
    a <- FeatureFlags.boolean("feature-a", false)
    b <- FeatureFlags.boolean("feature-b", false)
  yield assertTrue(a == true) && assertTrue(b == false)

  // Test with overrides
  val overrideTest = FeatureFlags.transaction(Map("feature-b" -> true)) {
    for
      a <- FeatureFlags.boolean("feature-a", false)
      b <- FeatureFlags.boolean("feature-b", false)
    yield assertTrue(a == true) && assertTrue(b == true)
  }

  (baseTest *> overrideTest.map(_.result)).provide(Scope.default >>> baseLayer)
}
```

---

## Test Isolation

### Domain-Based Isolation

Use `FeatureFlags.fromProviderWithDomain` for test isolation when tests run in parallel:

```scala
test("isolated test 1") {
  val provider = new TestFeatureProvider(Map("flag" -> true))
  val layer = FeatureFlags.fromProviderWithDomain(provider, "test-1")

  FeatureFlags.boolean("flag", false)
    .provide(Scope.default >>> layer)
}

test("isolated test 2") {
  val provider = new TestFeatureProvider(Map("flag" -> false))
  val layer = FeatureFlags.fromProviderWithDomain(provider, "test-2")

  FeatureFlags.boolean("flag", false)
    .provide(Scope.default >>> layer)
}
```

### Sequential Tests

For tests that share state, run them sequentially:

```scala
suite("shared state tests")(
  test("test 1") { ... },
  test("test 2") { ... }
) @@ TestAspect.sequential
```

---

## Best Practices

### 1. Use Descriptive Flag Names

```scala
val testLayer = TestFeatureProvider.layer(Map(
  "premium-feature-enabled" -> true,
  "max-upload-size-mb" -> 100,
  "checkout-variant" -> "new"
))
```

### 2. Create Test Fixtures

```scala
object TestFixtures:
  val premiumUser = TestFeatureProvider.layer(Map(
    "premium" -> true,
    "max-items" -> 1000
  ))

  val freeUser = TestFeatureProvider.layer(Map(
    "premium" -> false,
    "max-items" -> 10
  ))

// Usage
test("premium user behavior") {
  myTest.provide(Scope.default >>> TestFixtures.premiumUser)
}
```

### 3. Verify Expected Evaluations

```scala
test("service only evaluates necessary flags") {
  for
    provider <- TestFeatureProvider.make(Map(
      "needed-flag" -> true,
      "unneeded-flag" -> true
    ))
    layer     = TestFeatureProvider.layerFrom(provider)
    _        <- myService.provide(Scope.default >>> layer)
    evals    <- provider.getEvaluations
  yield
    assertTrue(evals.map(_._1).contains("needed-flag")) &&
    assertTrue(!evals.map(_._1).contains("unneeded-flag"))
}
```

### 4. Test Edge Cases

```scala
suite("edge cases")(
  test("handles missing flag") {
    val layer = TestFeatureProvider.layer(Map.empty)

    FeatureFlags.boolean("missing", false)
      .map(result => assertTrue(result == false))
      .provide(Scope.default >>> layer)
  },
  test("handles type mismatch") {
    val layer = TestFeatureProvider.layer(Map("flag" -> "string"))

    FeatureFlags.boolean("flag", false)
      .map(result => assertTrue(result == false))  // Uses default
      .provide(Scope.default >>> layer)
  }
)
```

