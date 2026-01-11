---
layout: default
title: Transactions
nav_order: 5
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

val result = flags.transaction(overrides) {
  for
    a <- flags.getBooleanValue("feature-a", false)  // Returns true (overridden)
    b <- flags.getStringValue("feature-b", "default") // Returns "variant-x"
    c <- flags.getIntValue("max-items", 10)           // Returns 100
  yield (a, b, c)
}
```

### Transaction with Context

```scala
val ctx = EvaluationContext("user-123")
val overrides = Map("premium" -> true)

val result = flags.transactionWithContext(overrides, ctx) {
  flags.getBooleanValue("premium", false)
}
```

## Transaction Results

Transactions return a `TransactionResult` containing:

- The result of your code
- Information about which flags were evaluated
- Which flags were overridden

```scala
val result: TransactionResult[(Boolean, String)] =
  flags.transaction(overrides) {
    for
      a <- flags.getBooleanValue("feature-a", false)
      b <- flags.getStringValue("feature-b", "default")
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

flags.transaction(Map("feature" -> true)) {
  flags.getBooleanValue("feature", false) // Returns true (override wins)
}
```

### Type Safety

Override values must match the expected type:

```scala
// This will fail - type mismatch
flags.transaction(Map("count" -> "not-a-number")) {
  flags.getIntValue("count", 0) // Error: OverrideTypeMismatch
}
```

### Missing Overrides

Flags not in the override map are evaluated normally:

```scala
flags.transaction(Map("feature-a" -> true)) {
  for
    a <- flags.getBooleanValue("feature-a", false) // true (overridden)
    b <- flags.getBooleanValue("feature-b", false) // Evaluated from provider
  yield (a, b)
}
```

## Nested Transactions

Nested transactions are not allowed and will fail:

```scala
flags.transaction(Map("a" -> true)) {
  // This will fail with NestedTransactionNotAllowed
  flags.transaction(Map("b" -> true)) {
    // ...
  }
}
```

## Use Cases

### Testing Specific Scenarios

```scala
test("premium users see new feature") {
  flags.transaction(Map("new-feature" -> true, "user-tier" -> "premium")) {
    for
      result <- myFeatureLogic
    yield assertTrue(result.showsNewFeature)
  }
}
```

### Debugging Flag Behavior

```scala
val debugResult = flags.transaction(Map.empty) {
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
val auditedResult = flags.transaction(Map.empty) {
  processUserRequest(userId)
}

auditedResult.flatMap { tx =>
  auditService.record(
    userId = userId,
    flagsEvaluated = tx.allFlagKeys,
    values = tx.toValueMap,
    timestamp = Instant.now()
  )
}
```
