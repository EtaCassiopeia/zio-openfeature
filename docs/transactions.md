---
layout: default
title: Transactions
nav_order: 7
---

# Transactions
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Transactions are a unique feature of ZIO OpenFeature that allow you to override flag values and track evaluations within a scoped block of code. This is useful for:

- Testing specific flag combinations
- A/B testing with predetermined values
- Debugging flag behavior
- Audit trails of flag usage
- Caching evaluations for performance

---

## Basic Usage

### Simple Transaction

```scala
import zio.*
import zio.openfeature.*

val overrides = Map(
  "feature-a" -> true,
  "feature-b" -> "variant-x",
  "max-items" -> 100
)

val result = FeatureFlags.transaction(overrides) {
  for
    a <- FeatureFlags.boolean("feature-a", false)  // Returns true (overridden)
    b <- FeatureFlags.string("feature-b", "default") // Returns "variant-x"
    c <- FeatureFlags.int("max-items", 10)           // Returns 100
  yield (a, b, c)
}
```

### Transaction with Context

```scala
val ctx = EvaluationContext("user-123")
val overrides = Map("premium" -> true)

val result = FeatureFlags.transaction(overrides, ctx) {
  FeatureFlags.boolean("premium", false)
}
```

---

## Evaluation Caching

By default, transactions cache flag evaluations. When the same flag is evaluated multiple times within a transaction, only the first evaluation calls the provider:

```scala
FeatureFlags.transaction() {
  for
    a <- FeatureFlags.boolean("feature", false)  // Calls provider
    b <- FeatureFlags.boolean("feature", false)  // Returns cached value
    c <- FeatureFlags.boolean("feature", false)  // Returns cached value
  yield (a, b, c)  // All three have the same value
}
```

This behavior:
- Ensures consistency within a transaction
- Reduces provider calls for better performance
- Returns `ResolutionReason.Cached` for subsequent evaluations

### Disabling Caching

To disable caching and call the provider for every evaluation:

```scala
FeatureFlags.transaction(cacheEvaluations = false) {
  for
    a <- FeatureFlags.boolean("feature", false)  // Calls provider
    b <- FeatureFlags.boolean("feature", false)  // Calls provider again
  yield (a, b)  // May differ if flag changed between calls
}
```

### Parameters

| Parameter | Type | Default | Description |
|:----------|:-----|:--------|:------------|
| `overrides` | `Map[String, Any]` | `Map.empty` | Flag values to override |
| `context` | `EvaluationContext` | `empty` | Context for this transaction |
| `cacheEvaluations` | `Boolean` | `true` | Cache flag values within transaction |

---

## Transaction Results

Transactions return a `TransactionResult` containing:

- The result of your code
- Information about which flags were evaluated
- Which flags were overridden

```scala
val result: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[(Boolean, String)]] =
  FeatureFlags.transaction(overrides) {
    for
      a <- FeatureFlags.boolean("feature-a", false)
      b <- FeatureFlags.string("feature-b", "default")
    yield (a, b)
  }

// Access the result
result.map { txResult =>
  println(s"Result: ${txResult.result}")           // (true, "variant-x")
  println(s"Flags evaluated: ${txResult.flagCount}") // 2
  println(s"Overrides used: ${txResult.overrideCount}") // 2
  println(s"All flag keys: ${txResult.allFlagKeys}") // Set("feature-a", "feature-b")
}
```

---

## Transaction Result API

### Checking Evaluations

```scala
txResult.wasEvaluated("feature-a")  // true if flag was evaluated
txResult.wasOverridden("feature-a") // true if flag used override value
```

### Getting Evaluation Details

```scala
txResult.getEvaluation("feature-a").map { eval =>
  println(s"Key: ${eval.key}")
  println(s"Value: ${eval.value}")
  println(s"Was overridden: ${eval.wasOverridden}")
  println(s"Timestamp: ${eval.timestamp}")
}
```

### Accessing All Values

```scala
// Get simple key-value map
val valueMap: Map[String, Any] = txResult.toValueMap

// Get keys evaluated by provider (not overridden)
val providerKeys: Set[String] = txResult.providerEvaluatedKeys
```

---

## Override Behavior

### Override Priority

Overrides take precedence over provider values:

```scala
// Provider has "feature" = false
// Override sets "feature" = true

FeatureFlags.transaction(Map("feature" -> true)) {
  FeatureFlags.boolean("feature", false) // Returns true (override wins)
}
```

### Type Safety

Override values must match the expected type:

```scala
// This will fail - type mismatch
FeatureFlags.transaction(Map("count" -> "not-a-number")) {
  FeatureFlags.int("count", 0) // Error: OverrideTypeMismatch
}
```

### Missing Overrides

Flags not in the override map are evaluated normally:

```scala
FeatureFlags.transaction(Map("feature-a" -> true)) {
  for
    a <- FeatureFlags.boolean("feature-a", false) // true (overridden)
    b <- FeatureFlags.boolean("feature-b", false) // Evaluated from provider
  yield (a, b)
}
```

---

## Nested Transactions

Nested transactions are not allowed and will fail:

```scala
FeatureFlags.transaction(Map("a" -> true)) {
  // This will fail with NestedTransactionNotAllowed
  FeatureFlags.transaction(Map("b" -> true)) {
    // ...
  }
}
```

---

## Checking Transaction State

You can check if code is running inside a transaction:

```scala
val inTx: ZIO[FeatureFlags, Nothing, Boolean] = FeatureFlags.inTransaction

// Get currently evaluated flags in active transaction
val evaluated: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[?]]] =
  FeatureFlags.currentEvaluatedFlags
```

---

## Use Cases

### Testing Specific Scenarios

```scala
test("premium users see new feature") {
  FeatureFlags.transaction(Map("new-feature" -> true, "user-tier" -> "premium")) {
    for
      result <- myFeatureLogic
    yield assertTrue(result.showsNewFeature)
  }
}
```

### Debugging Flag Behavior

```scala
val debugResult = FeatureFlags.transaction(Map.empty) {
  // No overrides - just track what gets evaluated
  myComplexBusinessLogic
}

debugResult.map { tx =>
  println(s"Flags used: ${tx.allFlagKeys}")
  tx.toValueMap.foreach { case (k, v) =>
    println(s"  $k = $v")
  }
}
```

### Audit Trail

```scala
val auditedResult = FeatureFlags.transaction(Map.empty) {
  processUserRequest(userId)
}

auditedResult.flatMap { tx =>
  auditService.record(
    userId = userId,
    flagsEvaluated = tx.allFlagKeys,
    values = tx.toValueMap,
    timestamp = java.time.Instant.now()
  )
}
```

### Consistent Flag Values

Ensure the same flag value is used throughout a request:

```scala
def handleRequest(request: Request) = {
  FeatureFlags.transaction() {
    for
      // All evaluations of "feature-x" return the same value
      header   <- renderHeader    // Uses "feature-x"
      content  <- renderContent   // Uses "feature-x" (cached)
      footer   <- renderFooter    // Uses "feature-x" (cached)
    yield Response(header, content, footer)
  }
}
```

### Staged Rollout Testing

```scala
val scenarios = List(
  Map("new-checkout" -> true, "new-payment" -> true),
  Map("new-checkout" -> true, "new-payment" -> false),
  Map("new-checkout" -> false, "new-payment" -> true),
  Map("new-checkout" -> false, "new-payment" -> false)
)

ZIO.foreach(scenarios) { overrides =>
  FeatureFlags.transaction(overrides) {
    for
      result <- runCheckoutFlow
      _      <- ZIO.logInfo(s"Scenario $overrides: $result")
    yield result
  }
}
```

---

## Best Practices

### 1. Use Transactions for Testing

```scala
test("feature behaves correctly when disabled") {
  val testLayer = TestFeatureProvider.layer(Map("feature" -> true))

  val result = FeatureFlags.transaction(Map("feature" -> false)) {
    myFeatureLogic
  }.provide(Scope.default >>> testLayer)

  // Verify behavior with feature disabled
}
```

### 2. Keep Transactions Short

Transactions hold state in memory. Keep them focused:

```scala
// Good: Focused transaction
FeatureFlags.transaction() {
  for
    enabled <- FeatureFlags.boolean("feature", false)
    config  <- FeatureFlags.obj("config", Map.empty)
  yield processWithFlags(enabled, config)
}

// Avoid: Long-running transaction
FeatureFlags.transaction() {
  for
    flags   <- evaluateAllFlags
    _       <- longRunningOperation  // Transaction state held in memory
    result  <- processResult
  yield result
}
```

### 3. Use Empty Transactions for Tracking

Track flag usage without overriding:

```scala
FeatureFlags.transaction(Map.empty) {
  businessLogic
}.map { tx =>
  // Analyze which flags were actually used
  analytics.record("flags_used", tx.allFlagKeys)
}
```

