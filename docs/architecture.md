---
layout: default
title: Architecture
nav_order: 3
---

# Architecture
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

ZIO OpenFeature is a ZIO-native implementation of the [OpenFeature specification](https://openfeature.dev/specification/), providing a vendor-agnostic API for feature flag evaluation. The library is designed around functional programming principles, leveraging ZIO's effect system for type safety, resource management, and concurrency.

### Design Goals

1. **Type Safety**: Compile-time guarantees for flag types through the `FlagType` type class
2. **ZIO Integration**: First-class ZIO support with proper resource management and effect handling
3. **Extensibility**: Easy to implement custom providers for any feature flag backend
4. **Testability**: Built-in testkit for deterministic testing without external dependencies
5. **OpenFeature Compliance**: Adherence to the OpenFeature specification for interoperability

---

## Component Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Code                          │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                         FeatureFlags                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Hooks     │  │ Transactions│  │   Context Management    │  │
│  │  Pipeline   │  │   Support   │  │ (Global/Fiber/Invocation)│  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                       FeatureProvider                            │
│                    (Provider Abstraction)                        │
└─────────────────────────────────────────────────────────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
    ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
    │  Optimizely   │   │    Test       │   │    Custom     │
    │   Provider    │   │   Provider    │   │   Provider    │
    └───────────────┘   └───────────────┘   └───────────────┘
```

### Core Components

| Component | Responsibility |
|:----------|:---------------|
| **FeatureFlags** | Main service interface for flag evaluation, context management, hooks, and transactions |
| **FeatureProvider** | Abstraction for flag resolution backends (Optimizely, in-memory, custom) |
| **EvaluationContext** | User and environment attributes for targeting decisions |
| **FlagType** | Type class for compile-time type safety and value conversion |
| **FeatureHook** | Cross-cutting concerns (logging, metrics, validation) |
| **Transaction** | Scoped flag overrides and evaluation tracking |

---

## Layer Architecture

ZIO OpenFeature uses ZIO's layer system for dependency injection:

```scala
// Application layer composition
val appLayer: ZLayer[Any, Throwable, FeatureFlags] =
  OptimizelyProvider.layerFromOptions(options) >>> FeatureFlags.live

// For testing
val testLayer: ZLayer[Any, Nothing, FeatureFlags] =
  TestFeatureProvider.layer(flags) >>> FeatureFlags.live
```

### Layer Dependencies

```
FeatureFlags.live
    │
    └── requires FeatureProvider
              │
              ├── OptimizelyProvider (production)
              │       └── requires OptimizelyOptions
              │
              └── TestFeatureProvider (testing)
                      └── requires initial flag map
```

---

## Type-Safe Flag Evaluation

### The FlagType Type Class

`FlagType[A]` provides compile-time type safety for flag values:

```scala
trait FlagType[A]:
  def typeName: String
  def decode(value: Any): Either[String, A]
  def encode(value: A): Any
  def defaultValue: A
```

Built-in instances are provided for:

| Type | Description |
|:-----|:------------|
| `Boolean` | Feature toggles |
| `String` | Variations, variants |
| `Int`, `Long` | Numeric configurations |
| `Float`, `Double` | Percentages, rates |
| `Map[String, Any]` | Complex JSON configurations |
| `Option[A]` | Optional values |
| `List[A]` | Collections |

### Custom Flag Types

Create custom flag types for domain-specific values:

```scala
// Define your domain type
enum Plan:
  case Free, Premium, Enterprise

// Create a FlagType instance
given planFlagType: FlagType[Plan] = FlagType.from(
  name = "Plan",
  default = Plan.Free,
  decoder = {
    case "free"       => Right(Plan.Free)
    case "premium"    => Right(Plan.Premium)
    case "enterprise" => Right(Plan.Enterprise)
    case other        => Left(s"Unknown plan: $other")
  },
  encoder = _.toString.toLowerCase
)

// Use with type safety
val plan: IO[FeatureFlagError, Plan] =
  flags.value[Plan]("user-plan", Plan.Free)
```

---

## Context Hierarchy

Evaluation context flows through multiple levels, with later levels taking precedence:

```
┌─────────────────────────────────────────────────────────────┐
│                    Final Merged Context                      │
│  (Used for flag evaluation)                                  │
└─────────────────────────────────────────────────────────────┘
                            ▲
            ┌───────────────┼───────────────┐
            │               │               │
    ┌───────┴───────┐ ┌─────┴─────┐ ┌───────┴───────┐
    │  Invocation   │ │Transaction│ │    Scoped     │
    │   Context     │ │  Context  │ │   Context     │
    │  (per-call)   │ │ (override)│ │ (withContext) │
    └───────────────┘ └───────────┘ └───────────────┘
                            │
                    ┌───────┴───────┐
                    │    Global     │
                    │   Context     │
                    │ (application) │
                    └───────────────┘
```

### Context Levels

| Level | Scope | Use Case |
|:------|:------|:---------|
| **Global** | Application-wide | App version, environment, deployment region |
| **Scoped** | Block of code (via `withContext`) | User session, request context |
| **Transaction** | Within transaction block | Test overrides, experiment context |
| **Invocation** | Single evaluation | One-off targeting attributes |

### Merging Strategy

When contexts are merged, attributes from higher-precedence levels override lower ones:

```scala
// Global context (lowest precedence)
FeatureFlags.setGlobalContext(
  EvaluationContext("app")
    .withAttribute("env", AttributeValue.string("prod"))
    .withAttribute("version", AttributeValue.string("1.0"))
)

// Scoped context (overrides global for this block)
val scopedCtx = EvaluationContext("user-123")
  .withAttribute("version", AttributeValue.string("2.0"))  // Overrides global

FeatureFlags.withContext(scopedCtx) {
  // Invocation context (highest precedence)
  val ctx = EvaluationContext.empty
    .withAttribute("experiment", AttributeValue.string("A"))

  // Final merged context for this evaluation:
  // - targetingKey: "user-123" (from scoped)
  // - env: "prod" (from global)
  // - version: "2.0" (from scoped, overrides global)
  // - experiment: "A" (from invocation)
  FeatureFlags.boolean("feature", false, ctx)
}
```

---

## Hook Pipeline

Hooks execute in a defined order around flag evaluation:

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
│                   Provider Resolution                        │
│  - Calls FeatureProvider.resolveXxxValue()                   │
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
│  - Always runs, success or failure                           │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                       Return Result                          │
└─────────────────────────────────────────────────────────────┘
```

### Hook Stages

| Stage | When | Purpose |
|:------|:-----|:--------|
| **before** | Before provider call | Modify context, start timers, validate |
| **after** | On successful evaluation | Log results, record metrics |
| **error** | On evaluation failure | Log errors, alert, fallback logic |
| **finallyAfter** | Always (like try-finally) | Cleanup, span completion |

### Hook Hints

Hooks can pass data between stages using `HookHints`:

```scala
val timingHook = new FeatureHook:
  private val startTimeKey = "timing.start"

  override def before(ctx: HookContext, hints: HookHints) =
    Clock.nanoTime.map { start =>
      Some((ctx.evaluationContext, hints + (startTimeKey -> start)))
    }

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints) =
    for
      end   <- Clock.nanoTime
      start  = hints.getOrElse[Long](startTimeKey, end)
      _     <- ZIO.logInfo(s"Evaluation took ${(end - start) / 1_000_000}ms")
    yield ()
```

---

## Provider Lifecycle

Providers follow a defined lifecycle managed by ZIO's resource system:

```
┌──────────────┐
│   NotReady   │ ◄─── Initial state
└──────┬───────┘
       │ initialize()
       ▼
┌──────────────┐
│    Ready     │ ◄─── Can evaluate flags
└──────┬───────┘
       │ shutdown() or error
       ▼
┌──────────────┐
│   NotReady   │ ◄─── Cannot evaluate flags
└──────────────┘
```

### Provider Events

Providers emit events to notify the system of state changes:

| Event | Meaning |
|:------|:--------|
| `Ready` | Provider initialized successfully |
| `ConfigurationChanged` | Flag definitions updated |
| `Stale` | Provider data may be outdated |
| `Error` | Provider encountered an error |

### Scoped Provider Management

Use `ZIO.scoped` for automatic lifecycle management:

```scala
ZIO.scoped {
  for
    provider <- OptimizelyProvider.scopedFromOptions(options)
    // Provider is automatically initialized
    flags    <- ZIO.service[FeatureFlags]
    result   <- flags.boolean("feature", false)
  yield result
  // Provider is automatically shutdown when scope ends
}
```

---

## Transaction System

Transactions provide scoped flag overrides and evaluation tracking:

```scala
val overrides = Map("feature-a" -> true, "max-items" -> 100)

flags.transaction(overrides) {
  for
    a <- flags.boolean("feature-a", false)  // Returns true (override)
    b <- flags.boolean("feature-b", false)  // Evaluated from provider
    n <- flags.int("max-items", 10)         // Returns 100 (override)
  yield (a, b, n)
}.map { txResult =>
  // txResult.result = (true, false, 100)
  // txResult.allFlagKeys = Set("feature-a", "feature-b", "max-items")
  // txResult.overriddenFlags = Set("feature-a", "max-items")
}
```

### Transaction Guarantees

1. **Isolation**: Overrides only affect code within the transaction block
2. **Tracking**: All flag evaluations are recorded with timestamps
3. **Type Safety**: Override values must match expected types
4. **No Nesting**: Nested transactions are not allowed (fails fast)

---

## Error Handling

The library uses `FeatureFlagError` for typed error handling:

| Error | Cause |
|:------|:------|
| `FlagNotFound` | Flag key doesn't exist in provider |
| `TypeMismatch` | Value type doesn't match expected type |
| `ProviderNotReady` | Provider not initialized |
| `TargetingKeyMissing` | Required targeting key not provided |
| `InvalidContext` | Evaluation context is invalid |
| `ProviderError` | Underlying provider exception |
| `NestedTransactionNotAllowed` | Attempted nested transaction |
| `OverrideTypeMismatch` | Transaction override type mismatch |

### Error Recovery

```scala
flags.boolean("feature", false)
  .catchSome {
    case FeatureFlagError.FlagNotFound(_) =>
      ZIO.succeed(false)  // Use default
    case FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false)  // Fail safe
  }
```

---

## OpenFeature Compliance

This library implements the [OpenFeature specification](https://openfeature.dev/specification/) with the following mappings:

| OpenFeature Concept | ZIO OpenFeature Implementation |
|:--------------------|:-------------------------------|
| API | `FeatureFlags` trait |
| Provider | `FeatureProvider` trait |
| Evaluation Context | `EvaluationContext` case class |
| Hooks | `FeatureHook` trait |
| Flag Resolution | `FlagResolution[A]` case class |
| Provider Events | `ProviderEvent` enum via `ZStream` |
| Provider Status | `ProviderStatus` enum |

### Specification Extensions

ZIO OpenFeature extends the specification with:

1. **FlagType Type Class**: Compile-time type safety beyond the spec's basic types
2. **Transactions**: Scoped overrides with evaluation tracking (not in spec)
3. **Fiber-Local Context**: ZIO-specific context propagation
4. **Effect-Based API**: All operations return ZIO effects

---

## Module Structure

```
zio-openfeature/
├── core/                    # Core abstractions and service
│   └── src/main/scala/zio/openfeature/
│       ├── FeatureFlags.scala        # Main service trait
│       ├── FeatureFlagsLive.scala    # Service implementation
│       ├── FeatureProvider.scala     # Provider abstraction
│       ├── EvaluationContext.scala   # Context for targeting
│       ├── FlagType.scala            # Type class for flag types
│       ├── FlagResolution.scala      # Resolution result
│       ├── Hook.scala                # Hook system
│       ├── Transaction.scala         # Transaction support
│       └── ...
├── testkit/                 # Testing utilities
│   └── src/main/scala/zio/openfeature/testkit/
│       └── TestFeatureProvider.scala # In-memory test provider
└── optimizely/              # Optimizely provider
    └── src/main/scala/zio/openfeature/provider/optimizely/
        ├── OptimizelyProvider.scala  # Provider implementation
        └── OptimizelyOptions.scala   # Configuration
```

---

## Thread Safety

All components are designed for concurrent use:

- **FeatureFlags**: Uses `Ref` for global context, `FiberRef` for scoped context and transactions
- **FeatureProvider**: Implementations should be thread-safe
- **TestFeatureProvider**: Uses `Ref` for mutable state, safe for concurrent tests
- **Transactions**: Use `FiberRef` for isolation between concurrent operations

---

## Performance Considerations

1. **Context Merging**: Performed on each evaluation; keep contexts small
2. **Hook Execution**: Hooks run sequentially; keep them fast
3. **Provider Caching**: Providers may implement internal caching
4. **Type Conversion**: `FlagType.decode` runs on each evaluation

### Optimization Tips

- Set frequently-used attributes in global context (merged once at startup)
- Use typed methods (`boolean`, `string`) instead of generic `value[A]` when possible
- Keep hooks lightweight; use async operations for heavy work
- Consider provider-level caching for frequently evaluated flags
