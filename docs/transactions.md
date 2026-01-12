---
layout: default
title: Transactions
nav_order: 6
---

# Transactions
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Transactions allow you to override flag values and track evaluations within a scoped block of code. This is useful for:

- Testing specific flag combinations
- A/B testing with predetermined values
- Debugging flag behavior
- Audit trails of flag usage

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
