---
layout: default
title: Hooks
nav_order: 6
---

# Hooks
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Hooks provide a mechanism to add cross-cutting concerns to flag evaluation. They can execute code before, after, on error, and finally after each evaluation. ZIO OpenFeature hooks are ZIO-native, meaning all hook methods return ZIO effects.

---

## Hook Lifecycle

Each hook can implement four stages:

1. **before** - Runs before flag evaluation, can modify context
2. **after** - Runs after successful evaluation
3. **error** - Runs when evaluation fails
4. **finallyAfter** - Always runs, regardless of success or failure

```
┌─────────────────────────────────────────────────────────────┐
│                      Evaluation Request                      │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    BEFORE hooks (in order)                   │
│  - Can modify evaluation context                             │
│  - Can pass hints to later stages                            │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     Flag Resolution                          │
└─────────────────────────────────────────────────────────────┘
                            │
            ┌───────────────┴───────────────┐
            │ Success                       │ Failure
            ▼                               ▼
┌───────────────────────┐       ┌───────────────────────┐
│  AFTER hooks          │       │  ERROR hooks          │
│  (reverse order)      │       │  (reverse order)      │
└───────────────────────┘       └───────────────────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                 FINALLY hooks (reverse order)                │
└─────────────────────────────────────────────────────────────┘
```

---

## Built-in Hooks

### Logging Hook

Logs flag evaluations to ZIO's logging system:

```scala
import zio.*
import zio.openfeature.*

val loggingHook = FeatureHook.logging(
  logBefore = false,
  logAfter = true,
  logError = true
)

// Add to service
FeatureFlags.addHook(loggingHook)
```

### Metrics Hook

Records evaluation metrics:

```scala
val metricsHook = FeatureHook.metrics { (flagKey, duration, success) =>
  ZIO.succeed {
    println(s"Flag '$flagKey' evaluated in ${duration.toMillis}ms (success=$success)")
    // Record to your metrics system
  }
}

FeatureFlags.addHook(metricsHook)
```

### Context Validator Hook

Validates evaluation context before evaluation:

```scala
val validatorHook = FeatureHook.contextValidator(
  requireTargetingKey = true,
  requiredAttributes = List("userId", "sessionId")
)

FeatureFlags.addHook(validatorHook)
```

---

## Custom Hooks

### Creating a Custom Hook

Implement the `FeatureHook` trait:

```scala
val customHook = new FeatureHook:
  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    ZIO.logDebug(s"Evaluating ${ctx.flagKey}").as(None)

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.logDebug(s"${ctx.flagKey} = ${details.value}")

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.logError(s"Error evaluating ${ctx.flagKey}: ${error.message}")

  override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit
```

### Hook Context

The `HookContext` provides information about the current evaluation:

```scala
final case class HookContext(
  flagKey: String,                     // The flag being evaluated
  flagType: FlagValueType,             // Boolean, String, Int, Double, or Object
  defaultValue: Any,                   // The default value
  evaluationContext: EvaluationContext, // The evaluation context
  providerMetadata: ProviderMetadata   // Provider information
)
```

### Hook Hints

Hooks can pass data between stages using `HookHints`. Return `Some((modifiedContext, newHints))` from `before` to modify context or pass hints:

```scala
val timingHook = new FeatureHook:
  private val startTimeKey = "timing.start"

  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    Clock.nanoTime.map { start =>
      // Store start time in hints for later stages
      Some((ctx.evaluationContext, hints + (startTimeKey -> start)))
    }

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    for
      end <- Clock.nanoTime
      start = hints.getOrElse[Long](startTimeKey, end)
      _ <- ZIO.logInfo(s"Evaluation took ${(end - start) / 1_000_000}ms")
    yield ()

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.unit

  override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit
```

---

## Composing Hooks

### Combining Multiple Hooks

```scala
val hook1 = FeatureHook.logging()
val hook2 = FeatureHook.metrics((k, d, s) => ZIO.unit)

// Compose hooks - both will run
val combined = FeatureHook.compose(List(hook1, hook2))

FeatureFlags.addHook(combined)
```

### Hook Execution Order

Hooks are executed in the order they were added:

```scala
FeatureFlags.addHook(loggingHook)   // Runs first in before
FeatureFlags.addHook(metricsHook)   // Runs second in before
FeatureFlags.addHook(validatorHook) // Runs third in before
```

For the `before` stage, hooks run in order. For `after`, `error`, and `finallyAfter`, they run in reverse order.

---

## Hook Registration Levels

Per the OpenFeature specification, hooks can be registered at three levels:

### API-Level Hooks

API-level hooks apply to all clients and use the OpenFeature SDK's Hook interface:

```scala
import dev.openfeature.sdk.Hook

// Add API-level hook (uses OpenFeature SDK Hook interface)
FeatureFlags.addApiHook(myOpenFeatureHook)

// Clear all API-level hooks
FeatureFlags.clearApiHooks
```

### Client-Level Hooks

Client-level hooks apply to a specific FeatureFlags instance:

```scala
// Add a single hook at runtime
FeatureFlags.addHook(myHook)

// Create layer with initial hooks
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)

val layer = FeatureFlags.fromProviderWithHooks(provider, hooks)

// Remove all client hooks
FeatureFlags.clearHooks

// Get current client hooks
val currentHooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
  FeatureFlags.hooks
```

### Invocation-Level Hooks

Invocation-level hooks apply to a single evaluation call:

```scala
import zio.openfeature.*

// Create hook for this evaluation
val auditHook = new FeatureHook:
  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.logInfo(s"Evaluated ${ctx.flagKey} = ${details.value}")

// Use EvaluationOptions to pass invocation hooks
val options = EvaluationOptions(
  hooks = List(auditHook),
  hookHints = HookHints("audit-id" -> "12345")
)

// Evaluate with invocation hooks
FeatureFlags.booleanDetails("feature", false, EvaluationContext.empty, options)
```

### Hook Execution Order

Per OpenFeature spec, hooks execute in this order:

**Before stage:** API → Client → Invocation (in addition order within each level)

**After/Error/Finally stages:** Invocation → Client → API (reverse order)

---

## Use Cases

### Audit Logging

Track all flag evaluations for compliance:

```scala
val auditHook = new FeatureHook:
  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    ZIO.none

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.logInfo(
      s"AUDIT: User ${ctx.evaluationContext.targetingKey.getOrElse("anonymous")} " +
      s"evaluated ${ctx.flagKey} = ${details.value}"
    )

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.logError(s"AUDIT: Flag evaluation failed: ${error.message}")

  override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit
```

### Feature Flag Analytics

Send evaluation data to your analytics platform:

```scala
val analyticsHook = FeatureHook.metrics { (flagKey, duration, success) =>
  for
    _ <- analyticsClient.recordEvaluation(flagKey, success)
    _ <- analyticsClient.recordLatency(flagKey, duration)
  yield ()
}
```

### Context Enrichment

Automatically add attributes to evaluation context:

```scala
val enrichmentHook = new FeatureHook:
  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    for
      now <- Clock.instant
      enrichedCtx = ctx.evaluationContext
        .withAttribute("timestamp", now.toString)
        .withAttribute("region", currentRegion)
    yield Some((enrichedCtx, hints))

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.unit

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.unit

  override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit
```

### Error Alerting

Send alerts when flag evaluations fail:

```scala
val alertingHook = new FeatureHook:
  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    ZIO.none

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.unit

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    alertService.sendAlert(
      level = AlertLevel.Warning,
      message = s"Flag evaluation failed: ${ctx.flagKey}",
      details = Map("error" -> error.message)
    ).ignore

  override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit
```

---

## Best Practices

### 1. Keep Hooks Fast

Hooks run synchronously for each evaluation. Avoid slow operations:

```scala
// Good: Fast, in-memory operation
override def after[A](...): UIO[Unit] =
  ZIO.succeed(counter.increment())

// Consider: Fork slow operations
override def after[A](...): UIO[Unit] =
  sendToAnalytics(details).forkDaemon.unit
```

### 2. Handle Errors Gracefully

Hooks should not throw exceptions:

```scala
override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
  riskyOperation.catchAll { error =>
    ZIO.logError(s"Hook error: $error")
  }
```

### 3. Use Hints for Inter-Stage Communication

Pass data between hook stages using hints:

```scala
override def before(...): UIO[Option[(EvaluationContext, HookHints)]] =
  ZIO.some((ctx.evaluationContext, hints + ("key" -> value)))

override def after[A](...): UIO[Unit] =
  val storedValue = hints.get[String]("key")
  // Use the stored value
```

### 4. Order Hooks Appropriately

Consider hook order for dependencies:

```scala
// Validation should run first
FeatureFlags.addHook(validatorHook)
// Then enrichment
FeatureFlags.addHook(enrichmentHook)
// Then logging/metrics
FeatureFlags.addHook(loggingHook)
```

