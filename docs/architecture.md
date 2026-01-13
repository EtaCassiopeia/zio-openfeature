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

ZIO OpenFeature is a ZIO-native wrapper around the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java). It provides a functional, type-safe API for feature flag evaluation while leveraging the entire OpenFeature ecosystem of providers.

### Design Goals

1. **OpenFeature Ecosystem Access**: Use any OpenFeature provider (LaunchDarkly, Flagsmith, flagd, etc.)
2. **Type Safety**: Compile-time guarantees through the `FlagType` type class
3. **ZIO Integration**: Effect-based API with proper resource management
4. **Unique Features**: Transactions, caching, hierarchical context, ZIO-native hooks
5. **Testability**: In-memory provider for testing without external dependencies

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
│                     OpenFeature Java SDK                         │
│              (OpenFeatureAPI, Client, FeatureProvider)           │
└─────────────────────────────────────────────────────────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
    ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
    │     flagd     │   │ LaunchDarkly  │   │  Flagsmith    │
    │   Provider    │   │   Provider    │   │   Provider    │
    └───────────────┘   └───────────────┘   └───────────────┘
```

### Core Components

| Component | Responsibility |
|:----------|:---------------|
| **FeatureFlags** | Main service interface for flag evaluation, context management, hooks, and transactions |
| **OpenFeature SDK** | Underlying Java SDK for provider management and flag resolution |
| **EvaluationContext** | User and environment attributes for targeting decisions |
| **FlagType** | Type class for compile-time type safety and value conversion |
| **FeatureHook** | Cross-cutting concerns (logging, metrics, validation) |
| **Transaction** | Scoped flag overrides and evaluation tracking with caching |

---

## Layer Architecture

ZIO OpenFeature uses ZIO's layer system for dependency injection. The `FeatureFlags` layer is created from any OpenFeature provider:

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider

// Production: use any OpenFeature provider
val prodLayer: ZLayer[Scope, Throwable, FeatureFlags] =
  FeatureFlags.fromProvider(new FlagdProvider())

// Testing: use in-memory provider
val testLayer: ZLayer[Scope, Throwable, FeatureFlags] =
  TestFeatureProvider.layer(Map("my-flag" -> true))

// Provide to your application
program.provide(Scope.default >>> prodLayer)
```

### Layer Dependencies

```
FeatureFlags.fromProvider(provider)
    │
    └── wraps OpenFeature SDK
              │
              └── OpenFeatureAPI.getInstance()
                      │
                      └── FeatureProvider (any OpenFeature provider)
                              │
                              ├── FlagdProvider
                              ├── LaunchDarklyProvider
                              ├── FlagsmithProvider
                              ├── TestFeatureProvider (for testing)
                              └── ... any OpenFeature provider
```

### Factory Methods

| Method | Description |
|:-------|:------------|
| `fromProvider(provider)` | Create from any OpenFeature provider |
| `fromProviderWithDomain(provider, domain)` | Create with named domain for test isolation |
| `fromProviderWithHooks(provider, hooks)` | Create with initial hooks |

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

Built-in instances:

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
enum Plan:
  case Free, Premium, Enterprise

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
  FeatureFlags.value[Plan]("user-plan", Plan.Free)
```

---

## Context Hierarchy

Evaluation context flows through five levels (per OpenFeature spec), with later levels taking precedence:

| Level | Scope | Use Case |
|:------|:------|:---------|
| **Global** | Application-wide | App version, environment, deployment region |
| **Client** | FeatureFlags instance | Service name, region |
| **Scoped** | Block of code (via `withContext`) | User session, request context |
| **Transaction** | Within transaction block | Test overrides, experiment context |
| **Invocation** | Single evaluation | One-off targeting attributes |

Contexts merge with higher-precedence levels overriding lower ones: `Invocation > Transaction > Scoped > Client > Global`.

See [Evaluation Context]({{ site.baseurl }}/context) for detailed usage, attribute types, and practical examples.

---

## Hook Pipeline

Hooks execute around flag evaluation in four stages: **before**, **after**, **error**, and **finallyAfter**. Hooks can modify evaluation context, pass data between stages via `HookHints`, and run effects for logging, metrics, or validation.

| Stage | When | Purpose |
|:------|:-----|:--------|
| **before** | Before evaluation | Modify context, start timers, validate |
| **after** | On successful evaluation | Log results, record metrics |
| **error** | On evaluation failure | Log errors, alert, fallback logic |
| **finallyAfter** | Always (like try-finally) | Cleanup, span completion |

See [Hooks]({{ site.baseurl }}/hooks) for the complete hook lifecycle, built-in hooks, and custom hook examples.

---

## Transaction System

Transactions provide scoped flag overrides, evaluation caching, and tracking:

| Feature | Description |
|:--------|:------------|
| **Overrides** | Provide values that override provider evaluation |
| **Caching** | Evaluations cached within transaction (optional via `cacheEvaluations`) |
| **Tracking** | Record all flag keys and values evaluated |
| **Isolation** | Overrides only affect code within the transaction |

```scala
FeatureFlags.transaction(Map("feature-a" -> true)) {
  for
    a <- FeatureFlags.boolean("feature-a", false)  // Returns true (override)
    b <- FeatureFlags.boolean("feature-b", false)  // Evaluated from provider
  yield (a, b)
}
```

See [Transactions]({{ site.baseurl }}/transactions) for complete usage, caching behavior, and result API.

---

## Provider Lifecycle

The OpenFeature SDK manages provider lifecycle. ZIO OpenFeature adds scoped resource management via ZIO's `Scope`:

| State | Description |
|:------|:------------|
| `NotReady` | Provider not initialized |
| `Ready` | Can evaluate flags |
| `Error` | Provider encountered recoverable error |
| `Stale` | Provider data may be outdated |

Provider events (`Ready`, `ConfigurationChanged`, `Stale`, `Error`) can be observed via `FeatureFlags.events` stream or specific handlers like `onProviderReady`.

See [Providers]({{ site.baseurl }}/providers) for complete lifecycle management, events, and provider setup.

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
FeatureFlags.boolean("feature", false)
  .catchSome {
    case FeatureFlagError.FlagNotFound(_) =>
      ZIO.succeed(false)  // Use default
    case FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false)  // Fail safe
  }
```

---

## OpenFeature Relationship

ZIO OpenFeature wraps the OpenFeature Java SDK:

| OpenFeature Concept | ZIO OpenFeature |
|:--------------------|:----------------|
| `OpenFeatureAPI` | Internal, managed by `FeatureFlags` layer |
| `Client` | Internal, managed by `FeatureFlagsLive` |
| `FeatureProvider` | Passed to `FeatureFlags.fromProvider()` |
| `EvaluationContext` | Our `EvaluationContext`, converted internally |
| `Hooks` | Our `FeatureHook` trait (ZIO-native) |
| `ProviderEvent` | Our `ProviderEvent` enum |

### ZIO-Specific Additions

These features are unique to ZIO OpenFeature:

1. **FlagType Type Class**: Compile-time type safety beyond basic types
2. **Transactions**: Scoped overrides with caching and tracking
3. **Fiber-Local Context**: Hierarchical context via `FiberRef`
4. **Effect-Based API**: All operations return ZIO effects
5. **ZIO-Native Hooks**: Effectful hook pipeline

---

## Module Structure

```
zio-openfeature/
├── core/                    # ZIO wrapper around OpenFeature SDK
│   └── src/main/scala/zio/openfeature/
│       ├── FeatureFlags.scala        # Main service trait + factory methods
│       ├── FeatureFlagsLive.scala    # Service implementation
│       ├── EvaluationContext.scala   # Context for targeting
│       ├── FlagType.scala            # Type class for flag types
│       ├── FlagResolution.scala      # Resolution result
│       ├── Hook.scala                # Hook system
│       ├── Transaction.scala         # Transaction support
│       └── internal/
│           └── ContextConverter.scala  # ZIO ↔ OpenFeature conversion
│
└── testkit/                 # Testing utilities
    └── src/main/scala/zio/openfeature/testkit/
        ├── TestFeatureProvider.scala # In-memory OpenFeature provider
        └── TestAssertions.scala      # Test helpers
```

---

## Thread Safety

All components are designed for concurrent use:

- **FeatureFlags**: Uses `Ref` for global context, `FiberRef` for scoped context and transactions
- **OpenFeature SDK**: Thread-safe by design
- **TestFeatureProvider**: Uses `Ref` for mutable state
- **Transactions**: Use `FiberRef` for fiber isolation

---

## Performance Considerations

1. **Context Merging**: Performed on each evaluation; keep contexts small
2. **Hook Execution**: Hooks run sequentially; keep them fast
3. **Transaction Caching**: Enable caching to avoid redundant evaluations
4. **Type Conversion**: `FlagType.decode` runs on each evaluation

### Optimization Tips

- Set frequently-used attributes in global context (merged once)
- Use typed methods (`boolean`, `string`) instead of generic `value[A]`
- Enable transaction caching for repeated evaluations
- Keep hooks lightweight; use async operations for heavy work

