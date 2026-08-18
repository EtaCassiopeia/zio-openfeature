---
layout: default
title: Transactions
nav_order: 10
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

### Overriding a Custom Flag Type

For a custom `FlagType` whose wire form differs from its domain form (a `FlagType.mapped` enum, a `FlagType.from` object type), an override may be given as **either** the domain value or the wire value — both decode to the same result:

```scala
// Phase is a domain enum carried over the wire as a string ("off" | "dual_write" | ...)
FeatureFlags.transaction(Map("rollout.phase" -> Phase.DualWrite)) { ... }  // domain value
FeatureFlags.transaction(Map("rollout.phase" -> "dual_write")) { ... }     // wire value, same effect
```

The wire form is tried first, and the domain form only if that fails, so the two spellings agree as long as the type's wire and domain values are distinguishable (or coincide, as they do for every built-in). A value that is neither fails the evaluation with `OverrideTypeMismatch`, whose message names the value's class and why it did not decode. This relies on the instance's round-trip law, `decode(encode(a)) == Right(a)` — see the `FlagType` scaladoc.

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
- Applies to custom `FlagType`s too: the cache holds the wire value and a re-read decodes it exactly as a provider answer would be decoded
- Never serves an **error-coded** evaluation: if the provider answered `FLAG_NOT_FOUND` (or any other code), the evaluation is *recorded* — so `TransactionResult` still shows the key was asked for and how it failed — but a re-read goes back to the provider, exactly as it would outside a transaction. Serving it would have turned a `FlagNotFound` on the first read into a successful `CACHED` default on the second

Re-reading the same key at a *different* type is served from the cache when the cached wire value decodes as that type (an `Int` re-read as `Long`; a string-backed custom type re-read as `String` yields its wire string), and otherwise falls through to the provider (a `Boolean` re-read as `String`).

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
| `nested` | `NestedPolicy` | `Fail` | What to do when already inside a transaction: `Fail` raises `NestedTransactionNotAllowed`; `Reuse` runs the body inside the enclosing transaction — see [Nested Transactions](#nested-transactions) |

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

By default, opening a transaction inside another one fails with `NestedTransactionNotAllowed` — before the inner
body runs:

```scala
FeatureFlags.transaction(Map("a" -> true)) {
  // Fails with NestedTransactionNotAllowed (the default, NestedPolicy.Fail)
  FeatureFlags.transaction(Map("b" -> true)) {
    // ...
  }
}
```

That is the right default for code that deliberately opens two transactions. It is the wrong shape for the most
common composition, though: a transaction used as **middleware**. A server wraps every request in one so a flag
cannot change mid-request; a handler inside then wraps a sub-operation in its own, for the same reason. Neither
knows about the other, and the result is a failed request. Pass `nested = NestedPolicy.Reuse` and the outermost
transaction wins:

```scala
// The request wrapper — knows nothing about what handlers do inside.
def withRequestTransaction[R, E, A](handler: ZIO[R, E, A]) =
  FeatureFlags.transaction()(handler)

// A handler that wants a sub-operation to see one consistent flag set — and works whether or not a request
// transaction is already open around it.
val checkout =
  FeatureFlags.transaction(nested = NestedPolicy.Reuse) {
    for
      v2   <- FeatureFlags.boolean("checkout-v2", false)
      step <- FeatureFlags.boolean("checkout-v2", false)   // same answer as `v2`, from the enclosing cache
    yield (v2, step)
  }
```

Under `Reuse`, an inner call that finds an enclosing transaction:

- **runs its body inside that transaction** — evaluations are recorded there and served from its cache;
- **ignores its own `overrides`, `context` and `cacheEvaluations`** — the enclosing transaction is the one running,
  and it was configured by whoever opened it. This is the one surprising part, so it is worth saying plainly: an
  inner `Map("b" -> true)` above would do nothing. If a nested caller genuinely needs its own overrides, it wants
  `Fail` (to find out it is nested) and a redesign, not `Reuse`;
- **returns a `TransactionResult` for the enclosing transaction** as of the body's completion — everything evaluated
  so far, including before the inner call. It did not open anything of its own, so that is the only honest thing it
  can report.

When there is *no* enclosing transaction, `Reuse` opens a fresh one exactly as `Fail` would — the policy only decides
what happens on the way in. It applies to a fiber forked from inside a transaction too, because the transaction is
fiber-local and inherited.

`NestedPolicy` is a parameter on both `transaction` and `transactionEither`, on the `FeatureFlags` trait and the
companion accessors alike; the default is `Fail`, so nothing changes unless you pass it. Before it existed, wrappers
had to hand-roll this guard with `inTransaction` — a guard whose two branches do not even return the same type
(`A` on the "already inside" branch, `TransactionResult[A]` on the other), so every wrapper re-solved that too.

---

## Checking Transaction State

Three reads, for three different questions:

```scala
// Am I inside a transaction?
val inTx: ZIO[FeatureFlags, Nothing, Boolean] = FeatureFlags.inTransaction

// What has the current transaction evaluated — and IS there one? None means "no transaction".
val evaluated: ZIO[FeatureFlags, Nothing, Option[Map[String, FlagEvaluation[?]]]] =
  FeatureFlags.transactionEvaluations

// Convenience: the same map, or Map.empty when there is no transaction.
val evaluatedOrEmpty: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[?]]] =
  FeatureFlags.currentEvaluatedFlags
```

The distinction between the last two matters exactly where these reads are usually made — **audit records**
("which flags shaped this request"). `currentEvaluatedFlags` answers `Map.empty` both outside any transaction and
inside one that has evaluated nothing yet, so a refactor that moves the audit call outside the transaction boundary
(or a middleware that stops wrapping) keeps compiling and starts writing empty flag sets into production records,
silently. `transactionEvaluations` puts the difference in the type: `Some(Map.empty)` is a real answer, `None` is
"this question does not apply here" — and it cannot be mistaken for the other.

```scala
def recordAudit(requestId: String): ZIO[FeatureFlags & Audit, Nothing, Unit] =
  FeatureFlags.transactionEvaluations.flatMap {
    case Some(flags) => Audit.record(requestId, flags)
    case None        => ZIO.logWarning(s"$requestId: audit called outside a flag transaction — nothing recorded")
  }
```

`transactionEvaluations` composes with [`NestedPolicy.Reuse`](#nested-transactions): inside a reused inner call
it reports the enclosing transaction's evaluations, since that is the transaction that is running.

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

