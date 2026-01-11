---
layout: default
title: Hooks
nav_order: 4
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

val loggingHook = FeatureHook.logging { message =>
  ZIO.logInfo(message)
}

// Add to service
flags.addHook(loggingHook)
```

### Metrics Hook

```scala
val metricsHook = FeatureHook.metrics { (flagKey, value, duration) =>
  ZIO.succeed {
    println(s"Flag '$flagKey' evaluated to '$value' in ${duration.toMillis}ms")
    // Record to your metrics system
  }
}

flags.addHook(metricsHook)
```

### Context Validator Hook

```scala
val validatorHook = FeatureHook.contextValidator { ctx =>
  ZIO.when(ctx.targetingKey.isEmpty)(
    ZIO.fail(FeatureFlagError.TargetingKeyMissing("validation"))
  ).as(ctx)
}

flags.addHook(validatorHook)
```

## Custom Hooks

### Creating a Custom Hook

```scala
val customHook = new FeatureHook:
  def before(ctx: HookContext): UIO[EvaluationContext] =
    ZIO.logDebug(s"Evaluating ${ctx.flagKey}").as(ctx.context)

  def after(ctx: HookContext, value: Any): UIO[Unit] =
    ZIO.logDebug(s"${ctx.flagKey} = $value")

  def error(ctx: HookContext, error: FeatureFlagError): UIO[Unit] =
    ZIO.logError(s"Error evaluating ${ctx.flagKey}: ${error.message}")

  def finallyAfter(ctx: HookContext): UIO[Unit] =
    ZIO.unit
```

### Hook Context

The `HookContext` provides information about the current evaluation:

```scala
case class HookContext(
  flagKey: String,           // The flag being evaluated
  flagType: FlagValueType,   // Boolean, String, Int, Double, or Object
  defaultValue: Any,         // The default value
  context: EvaluationContext // The evaluation context
)
```

### Hook Hints

Hooks can pass data between stages using `HookHints`:

```scala
val timingHook = new FeatureHook:
  def before(ctx: HookContext): UIO[EvaluationContext] =
    Clock.nanoTime.flatMap { start =>
      // Store start time in fiber-local state
      ZIO.succeed(ctx.context)
    }

  def after(ctx: HookContext, value: Any): UIO[Unit] =
    Clock.nanoTime.flatMap { end =>
      ZIO.logInfo(s"Evaluation took ${end}ns")
    }

  def error(ctx: HookContext, error: FeatureFlagError): UIO[Unit] =
    ZIO.unit

  def finallyAfter(ctx: HookContext): UIO[Unit] =
    ZIO.unit
```

## Composing Hooks

### Combining Multiple Hooks

```scala
val hook1 = FeatureHook.logging(msg => ZIO.logInfo(msg))
val hook2 = FeatureHook.metrics((k, v, d) => ZIO.unit)

// Compose hooks - both will run
val combined = FeatureHook.compose(hook1, hook2)

flags.addHook(combined)
```

### Hook Execution Order

Hooks are executed in the order they were added:

```scala
flags.addHook(loggingHook)   // Runs first
flags.addHook(metricsHook)   // Runs second
flags.addHook(validatorHook) // Runs third
```

For the `before` stage, hooks run in order. For `after`, `error`, and `finallyAfter`, they run in reverse order.

## Managing Hooks

### Adding Hooks

```scala
// Add a single hook
flags.addHook(myHook)

// Create service with hooks
val layer = FeatureFlags.liveWithHooks(List(hook1, hook2))
```

### Clearing Hooks

```scala
// Remove all hooks
flags.clearHooks

// Get current hooks
val hooks: UIO[List[FeatureHook]] = flags.hooks
```

## Use Cases

### Audit Logging

```scala
val auditHook = new FeatureHook:
  def before(ctx: HookContext): UIO[EvaluationContext] =
    ZIO.succeed(ctx.context)

  def after(ctx: HookContext, value: Any): UIO[Unit] =
    ZIO.logInfo(
      s"AUDIT: User ${ctx.context.targetingKey.getOrElse("anonymous")} " +
      s"evaluated ${ctx.flagKey} = $value"
    )

  def error(ctx: HookContext, error: FeatureFlagError): UIO[Unit] =
    ZIO.logError(s"AUDIT: Flag evaluation failed: ${error.message}")

  def finallyAfter(ctx: HookContext): UIO[Unit] =
    ZIO.unit
```

### Feature Flag Analytics

```scala
val analyticsHook = FeatureHook.metrics { (flagKey, value, duration) =>
  for
    _ <- analyticsClient.recordEvaluation(flagKey, value)
    _ <- analyticsClient.recordLatency(flagKey, duration)
  yield ()
}
```
