---
layout: default
title: Hooks
nav_order: 5
---

# Hooks
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Hooks provide a mechanism to add cross-cutting concerns to flag evaluation. They can execute code before, after, on error, and finally after each evaluation.

## Hook Lifecycle

Each hook can implement four stages:

1. **before** - Runs before flag evaluation, can modify context
2. **after** - Runs after successful evaluation
3. **error** - Runs when evaluation fails
4. **finallyAfter** - Always runs, regardless of success or failure

## Built-in Hooks

### Logging Hook

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

```scala
val validatorHook = FeatureHook.contextValidator(
  requireTargetingKey = true,
  requiredAttributes = List("userId", "sessionId")
)

FeatureFlags.addHook(validatorHook)
```

## Custom Hooks

### Creating a Custom Hook

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
FeatureFlags.addHook(loggingHook)   // Runs first
FeatureFlags.addHook(metricsHook)   // Runs second
FeatureFlags.addHook(validatorHook) // Runs third
```

For the `before` stage, hooks run in order. For `after`, `error`, and `finallyAfter`, they run in reverse order.

## Managing Hooks

### Adding Hooks

```scala
// Add a single hook
FeatureFlags.addHook(myHook)

// Create service with hooks
val layer = FeatureFlags.liveWithHooks(List(hook1, hook2))
```

### Clearing Hooks

```scala
// Remove all hooks
FeatureFlags.clearHooks

// Get current hooks
val currentHooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
  FeatureFlags.hooks
```

## Use Cases

### Audit Logging

```scala
val auditHook = new FeatureHook:
  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.logInfo(
      s"AUDIT: User ${ctx.evaluationContext.targetingKey.getOrElse("anonymous")} " +
      s"evaluated ${ctx.flagKey} = ${details.value}"
    )

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.logError(s"AUDIT: Flag evaluation failed: ${error.message}")
```

### Feature Flag Analytics

```scala
val analyticsHook = FeatureHook.metrics { (flagKey, duration, success) =>
  for
    _ <- analyticsClient.recordEvaluation(flagKey, success)
    _ <- analyticsClient.recordLatency(flagKey, duration)
  yield ()
}
```
