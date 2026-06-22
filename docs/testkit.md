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

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

---

## Choosing a Layer

| Layer | Provider starts as | Use when |
|:------|:-------------------|:---------|
| `layer(flags)` | `Ready` | Most tests — flags work immediately |
| `scopedLayer(flags)` | `Ready` | Same, self-contained scope |
| `asyncLayer(flags)` | `NotReady` | Testing startup/initialization behavior — requires manual `setStatus` |
| `asyncReadyLayer(flags, delay)` | `NotReady` → `Ready` | Simulating real async init without manual status management |

**Rule of thumb:** Use `layer` unless you specifically need to test how your code handles a provider that isn't ready yet.

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
  // evals is List[(String, dev.openfeature.sdk.EvaluationContext)]
  // The context is the OpenFeature SDK's EvaluationContext (after conversion)
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
// Simple event
provider.emitEvent(ProviderEvent.ConfigurationChanged(
  Set("flag-1", "flag-2"),
  provider.metadata
))

// Event with metadata
provider.emitEvent(ProviderEvent.ConfigurationChanged(
  Set("flag-1"),
  provider.metadata,
  FlagMetadata.fromStrings("source" -> "webhook")
))
```

---

## Behavior Controls

Simulate real-world failure modes like slow responses, intermittent failures, and specific error types. Useful for testing timeouts, circuit breakers, and fallback logic.

### Imperative API

```scala
for
  tp <- ZIO.service[TestFeatureProvider]
  // Simulate network latency
  _  <- tp.setDelay(200.millis)
  // Make all evaluations fail
  _  <- tp.setFailing(true)
  // Simulate specific error types
  _  <- tp.setErrorMode(TestFeatureProvider.ErrorMode.FlagNotFound)
  // Simulate flaky service (30% failure rate)
  _  <- tp.setFailureProbability(0.3)
  // Reset everything
  _  <- tp.clearBehavior
yield ()
```

Available error modes: `FlagNotFound`, `ParseError`, `TypeMismatch`, `ProviderNotReady`, `General`.

Provider exceptions are caught by the Java SDK and returned as default-valued resolutions with error codes. Use `booleanDetails` (or other `*Details` methods) to inspect the error code:

```scala
tp.setErrorMode(TestFeatureProvider.ErrorMode.FlagNotFound)
resolution <- FeatureFlags.booleanDetails("flag", default = false)
// resolution.errorCode == Some(ErrorCode.FlagNotFound)
// resolution.value == false (the default)
```

The exception is `ProviderNotReady`, which propagates as a ZIO-level `FeatureFlagError.ProviderNotReady`.

### TestAspect API

For cleaner test setup/teardown, use ZIO test aspects. Behavior is set before the test and cleaned up after:

```scala
test("handles slow provider") {
  for
    result <- FeatureFlags.boolean("flag", false).timeout(100.millis)
  yield assertTrue(result.isEmpty)
} @@ TestFeatureProvider.withDelay(500.millis)

test("handles provider failures") {
  for
    resolution <- FeatureFlags.booleanDetails("flag", default = false)
  yield assertTrue(resolution.errorCode.isDefined)
} @@ TestFeatureProvider.withFailures
```

Available aspects:

| Aspect | Effect |
|:-------|:-------|
| `TestFeatureProvider.withDelay(d)` | Adds delay before each evaluation |
| `TestFeatureProvider.withFailures` | All evaluations fail with a general error |
| `TestFeatureProvider.withErrorMode(mode)` | All evaluations fail with a specific error |
| `TestFeatureProvider.withFailureProbability(p)` | Evaluations fail randomly (0.0 to 1.0) |

Aspects require `TestFeatureProvider` in the environment. Apply `.provide(testLayer)` at the suite level when using aspects on individual tests.

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

The `getEvaluations` method returns OpenFeature SDK contexts (after conversion from ZIO contexts). You can verify that context attributes were correctly propagated:

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
    (_, sdkCtx) = evals.head
  yield
    // sdkCtx is dev.openfeature.sdk.EvaluationContext (Java SDK type)
    assertTrue(sdkCtx.getTargetingKey == "user-123") &&
    assertTrue(sdkCtx.getValue("plan") != null)
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

### Testing Async Initialization

Use `TestFeatureProvider.asyncLayer` to test how your code handles a provider that isn't ready yet:

```scala
test("service handles provider not ready") {
  for
    result <- MyService.getFeature.either
  yield assertTrue(result.isLeft)  // Fails with ProviderNotReady
}.provide(Scope.default >>> TestFeatureProvider.asyncLayer(Map("feature" -> true)))

test("service works after provider becomes ready") {
  for
    tp     <- ZIO.service[TestFeatureProvider]
    _      <- tp.setStatus(ProviderStatus.Ready)
    result <- MyService.getFeature
  yield assertTrue(result == true)
}.provide(Scope.default >>> TestFeatureProvider.asyncLayer(Map("feature" -> true)))
```

The `asyncLayer` creates a provider that starts in `NotReady` state. Call `setStatus(ProviderStatus.Ready)` to simulate the provider becoming ready. This is useful for testing graceful degradation and startup behavior.

### Simulating Real Async Init

If you don't need to test the `NotReady` state directly, use `asyncReadyLayer` which auto-transitions to `Ready` after a configurable delay:

```scala
test("service works with async provider") {
  for
    _      <- ZIO.sleep(200.millis) // Wait for auto-init
    result <- MyService.getFeature
  yield assertTrue(result == true)
}.provide(Scope.default >>> TestFeatureProvider.asyncReadyLayer(
  Map("feature" -> true),
  initDelay = 100.millis
))
```

This simulates a real provider (e.g., Optimizely connecting to its server) without requiring manual `setStatus` calls in every test.

---

## Test Isolation

### Automatic Isolation

`TestFeatureProvider.layer`, `asyncLayer`, and `layerFrom` each create an **isolated `OpenFeatureAPI` instance** with its own provider repository and event support. This means tests using these layers can run in parallel without cross-test contamination — no extra configuration needed.

```scala
// These tests run in parallel safely — each gets its own isolated API instance
test("test 1") {
  for result <- FeatureFlags.boolean("flag", false)
  yield assertTrue(result == true)
}.provide(Scope.default >>> TestFeatureProvider.layer(Map("flag" -> true)))

test("test 2") {
  for result <- FeatureFlags.boolean("flag", false)
  yield assertTrue(result == false)
}.provide(Scope.default >>> TestFeatureProvider.layer(Map("flag" -> false)))
```

If you need to access both the provider and the `FeatureFlags` service (e.g. to track evaluations or emit events), use `layerFrom`:

```scala
test("tracks evaluations") {
  for
    provider <- TestFeatureProvider.make(Map("flag" -> true))
    layer     = TestFeatureProvider.layerFrom(provider)
    _        <- FeatureFlags.boolean("flag", false).provide(Scope.default >>> layer)
    was      <- provider.wasEvaluated("flag")
  yield assertTrue(was)
}
```

> **Note:** The public factory methods (`FeatureFlags.fromProvider`, `fromMultiProvider`, etc.)
> use the global `OpenFeatureAPI` singleton and are **not** isolated. If you test with these
> directly, use `@@ TestAspect.sequential` to prevent conflicts.

---

## Behavior-Matrix Testing with zio-bdd

This pattern works with any `FeatureFlags` layer — `TestFeatureProvider`, `OptimizelyProvider`, OFREP, or your own provider. It's a feature of the [`zio-bdd`](https://github.com/EtaCassiopeia/zio-bdd) test framework's layer-injection hooks, not something specific to this testkit module, so it's documented here rather than on a provider-specific page.

### The layer-injection hook tiers

A `zio-bdd` suite (`object MySpec extends ZIOSteps[R, S]`) builds its environment through four overridable hooks, each one tier more specific than the last. Override exactly one — the others delegate down to it by default:

| Hook | Called | Default |
|:-----|:-------|:--------|
| `applicationLayer` | Once per test run | — |
| `featureLayer(meta)` | Once per `.feature` file | delegates to `applicationLayer` |
| `scenarioLayer(meta)` | Once per scenario | delegates to `featureLayer` |
| `flagLayer(meta, flags)` | Once per `@flags(...)` tag occurrence on a scenario | delegates to `scenarioLayer` |

```scala
override def scenarioLayer(meta: ScenarioMetadata): ZLayer[Any, Throwable, R] =
  if (meta.tags.contains("use-mock")) mockHttpLayer else realHttpLayer
```

### `@flags(...)` tags

`flagLayer` is the hook to override when a scenario needs different flag values per run. Tag a scenario with `@flags(key=value, ...)`, and the framework parses the tag into a `Map[String, String]` before calling `flagLayer(meta, flags)`:

```scala
override def flagLayer(meta: ScenarioMetadata, flags: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
  environment >>> FlagConfig.layer(flags)
```

Two things to know about how tags expand into runs:

- **One tag, multiple keys → one run.** `@flags(datafile=X, plan=Y)` parses to a single `Map("datafile" -> "X", "plan" -> "Y")` and calls `flagLayer` once, with both keys present together.
- **Multiple tags → multiple runs.** Two separate tag occurrences on the same scenario — written on consecutive lines:
  ```gherkin
  @flags(datafile=X)
  @flags(datafile=Y)
  Scenario: ...
  ```
  expand into two independent runs, each calling `flagLayer` once with only that tag's own map (not merged) — the scenario body executes twice, against two separately-built environments.

A blank line between or around `@flags(...)` tags is fine. A blank line inside the free-text description directly under `Feature:` is not — it silently drops the whole feature instead of raising a parse error; see [zio-bdd#87](https://github.com/EtaCassiopeia/zio-bdd/issues/87).

See [Optimizely → Testing your app]({{ site.baseurl }}/optimizely) for a worked example applying this to a real provider, with datafile fixtures driving the `@flags(datafile=...)` values.

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

Use `wasEvaluated` for cleaner flag usage assertions:

```scala
test("service only evaluates necessary flags") {
  for
    provider <- TestFeatureProvider.make(Map(
      "needed-flag" -> true,
      "unneeded-flag" -> true
    ))
    layer     = TestFeatureProvider.layerFrom(provider)
    _        <- myService.provide(Scope.default >>> layer)
    wasNeeded   <- provider.wasEvaluated("needed-flag")
    wasUnneeded <- provider.wasEvaluated("unneeded-flag")
  yield
    assertTrue(wasNeeded) &&
    assertTrue(!wasUnneeded)
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

