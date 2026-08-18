---
layout: default
title: Hooks
nav_order: 9
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

### Structured Logging Hook

A richer logging hook that adds machine-readable annotations to log output via `ZIO.logAnnotate`. Unlike the basic `logging()` hook which produces plain text messages, structured logging attaches typed fields that are preserved by `zio-logging` backends (JSON, SLF4J MDC, OpenTelemetry, etc.).

```scala
val hook = FeatureHook.structuredLogging(
  beforeLevel = Some(LogLevel.Debug),   // None to disable
  afterLevel = Some(LogLevel.Debug),
  errorLevel = Some(LogLevel.Warning),
  logContext = false,                    // include evaluation context in annotations
  redactKeys = Set("email", "ip")       // redact sensitive context attributes
)

FeatureFlags.addHook(hook)
```

**Log annotations added:**

| Annotation | Stage | Example |
|:-----------|:------|:--------|
| `flag.key` | all | `"dark-mode"` |
| `flag.type` | all | `"Boolean"` |
| `flag.provider` | all | `"OptimizelyProvider"` |
| `flag.domain` | all (if set) | `"my-service"` |
| `flag.value` | after | `"true"` |
| `flag.reason` | after | `"TargetingMatch"` |
| `flag.variant` | after (if present) | `"treatment-a"` |
| `flag.duration_ms` | after, error | `"12"` |
| `flag.error` | error | `"Flag 'x' not found"` |
| `flag.error.type` | error | `"FlagNotFound"` |
| `flag.context.targetingKey` | before, after, error (if `logContext`) | `"user-123"` |
| `flag.context.<attr>` | before, after, error (if `logContext`) | `"premium"` |

**Context logging and redaction:**

When `logContext = true`, the hook includes the evaluation context (targeting key + attributes) in log annotations. The `redactKeys` parameter specifies which attribute keys should have their values replaced with `"[REDACTED]"` — the key is still logged so you know the attribute was present, but the value is hidden.

```scala
val hook = FeatureHook.structuredLogging(
  logContext = true,
  redactKeys = Set("email", "ssn")
)

// With context: targetingKey="user-123", email="john@example.com", plan="premium"
// Produces annotations:
//   flag.context.targetingKey = "user-123"     ← not redacted
//   flag.context.email        = "[REDACTED]"   ← value hidden
//   flag.context.plan         = "premium"      ← not redacted
```

When `logContext = false` (default), no context attributes are logged and `redactKeys` has no effect.

Note: `redactKeys` only applies to context attributes, not to the targeting key. The targeting key is always logged as-is when `logContext` is enabled.

**Example JSON output** (with `zio-logging` JSON backend):

```json
{
  "level": "DEBUG",
  "message": "Flag 'dark-mode' = true (TargetingMatch, 3ms)",
  "flag.key": "dark-mode",
  "flag.type": "Boolean",
  "flag.provider": "OptimizelyProvider",
  "flag.value": "true",
  "flag.reason": "TargetingMatch",
  "flag.variant": "treatment-a",
  "flag.duration_ms": "3"
}
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

### Detailed Metrics Hook

A richer metrics hook that provides full evaluation context for building proper metric tags. Unlike `metrics()` which only gives you the flag key and a boolean, this hook passes the complete `HookContext` and `FlagResolution`/`FeatureFlagError` to your callbacks.

```scala
val hook = FeatureHook.metricsDetailed(
  onSuccess = (ctx, details, duration) =>
    metricsTracker.recordResponseTime("flag.evaluation", duration, Map(
      "flag.key"      -> ctx.flagKey,
      "flag.type"     -> ctx.flagType.name,
      "flag.provider" -> ctx.providerMetadata.name,
      "flag.reason"   -> details.reason.toString,
      "flag.variant"  -> details.variant.getOrElse("none")
    )),
  onError = (ctx, err, duration) =>
    metricsTracker.recordResponseTime("flag.evaluation.error", duration, Map(
      "flag.key"   -> ctx.flagKey,
      "flag.error" -> err.getClass.getSimpleName
    ))
)

FeatureFlags.addHook(hook)
```

**Available context in callbacks:**

| From `HookContext` | Description |
|:-------------------|:------------|
| `ctx.flagKey` | Flag key being evaluated |
| `ctx.flagType` | Type (Boolean, String, Int, etc.) |
| `ctx.providerMetadata.name` | Provider name |
| `ctx.clientMetadata.domain` | Client domain (if set) |
| `ctx.evaluationContext` | Full evaluation context |

| From `FlagResolution` (success only) | Description |
|:--------------------------------------|:------------|
| `details.value` | Evaluated value |
| `details.reason` | Resolution reason (TargetingMatch, Split, Static, etc.) |
| `details.variant` | Variant name (if applicable) |

| From `FeatureFlagError` (error only) | Description |
|:--------------------------------------|:------------|
| `err.message` | Error message |
| `err.getClass.getSimpleName` | Error type (FlagNotFound, TypeMismatch, etc.) |

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

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
    ZIO.unit
```

### Error semantics

Every `FeatureHook` stage returns `UIO`, so hooks are **infallible by construction and cannot abort an evaluation**. This is an intentional deviation from spec §4.4.6: a misbehaving observer (logging, metrics, validation) must never take flag evaluation down. The consequences:

- Handle expected failures **inside** the hook (e.g. `.catchAll`/`.ignore` on effects that can fail) — there is no typed error channel to surface them.
- Defects (unexpected `Throwable`s) still propagate as defects, so wrap untrusted third-party code (e.g. with `.catchAllDefect`) if it must not interfere with evaluation.
- For hooks that should run **inside the Java SDK** (and participate in the SDK's own hook error model), register them with `addApiHook` instead of `addHook`.

### Hook Context

The `HookContext` provides information about the current evaluation:

```scala
final case class HookContext(
  flagKey: String,                     // The flag being evaluated
  flagType: FlagValueType,             // Boolean, String, Int, Double, or Object
  defaultValue: Any,                   // The default value
  evaluationContext: EvaluationContext, // The evaluation context
  clientMetadata: ClientMetadata,      // Client information (spec 4.1.2)
  providerMetadata: ProviderMetadata,  // Provider information
  hookData: HookData                   // Per-hook mutable state (spec 4.6.1)
)
```

### Hook Data (Spec 4.6.1)

Each hook has its own `HookData` instance that persists across all stages of a single evaluation. Unlike `HookHints` (which are shared and read-only after `before`), `HookData` is mutable and scoped to an individual hook instance:

```scala
val spanHook = new FeatureHook:
  override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    ZIO.succeed {
      ctx.hookData.set("spanId", generateSpanId())
      None
    }

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.succeed {
      val spanId = ctx.hookData.get[String]("spanId").getOrElse("unknown")
      recordSpan(spanId, details)
    }

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
    ZIO.succeed {
      ctx.hookData.clear()
    }
```

When hooks are composed via `FeatureHook.compose`, each hook receives its own isolated `HookData` instance, so hooks cannot interfere with each other's state.

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

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
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
// Hooks run in registration order (first added = first to run in `before`)
FeatureFlags.addHooks(List(loggingHook, metricsHook, validatorHook))
```

For the `before` stage, hooks run in order. For `after`, `error`, and `finallyAfter`, they run in reverse order.

---

## Hook Registration Levels

Per the OpenFeature specification, hooks can be registered at four levels:

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

// Add multiple hooks atomically
FeatureFlags.addHooks(List(loggingHook, metricsHook, validatorHook))

// Create layer with initial hooks
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)

val layer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withHooks(hooks))

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

### Provider-Level Hooks

Provider hooks are automatically retrieved from the underlying provider via `provider.getProviderHooks()` and included in the hook pipeline. You don't need to register them manually.

### Hook Execution Order

Per OpenFeature spec, hooks execute in this order:

**Before stage:** API → Client → Invocation → Provider (in addition order within each level)

**After/Error/Finally stages:** Provider → Invocation → Client → API (reverse order)

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

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
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

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
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

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
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
// Order matters: validation → enrichment → logging
FeatureFlags.addHooks(List(validatorHook, enrichmentHook, loggingHook))
```

