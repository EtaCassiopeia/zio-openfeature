---
layout: default
title: Architecture
nav_order: 4
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
| `fromProvider(provider)` | Create from any OpenFeature provider (blocking init) |
| `fromProviderAsync(provider)` | Create from any OpenFeature provider (non-blocking init) |
| `fromProvider(provider, config)` | The config-driven factory — domain, version, hooks, timeouts, `InitMode`, `ApiOwnership`, in any combination, via [`FeatureFlagsConfig`](providers.md#featureflagsconfig) |
| `FeatureFlags.multiProvider(providers, strategy)` | Combine multiple providers into one `OFFeatureProvider` (first-match strategy by default), to pass into `fromProvider` |

See [Factory Methods](providers.md#factory-methods) for the full `FeatureFlagsConfig` reference.

---

## Type-Safe Flag Evaluation

### The FlagType Type Class

`FlagType[A]` provides compile-time type safety for flag values:

```scala
trait FlagType[A]:
  def typeName: String
  def wireType: String = typeName   // what the PROVIDER is asked for; see "Scalar-backed custom types"
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

Create custom flag types for domain-specific values. **How the flag is stored decides which constructor you
want** — see the next section; getting this wrong is the difference between a working flag and a
`TYPE_MISMATCH`.

`FlagType.from` builds an **object-backed** type: the value is fetched with the provider's *object*
resolver and then decoded. Use it when the flag really is stored as a structure/JSON:

```scala
final case class RolloutPlan(tier: String, percentage: Int)

given rolloutFlagType: FlagType[RolloutPlan] = FlagType.from(
  name = "RolloutPlan",
  default = RolloutPlan("free", 0),
  decoder = {
    case m: Map[?, ?] =>
      val sm = m.asInstanceOf[Map[String, Any]]
      Right(RolloutPlan(sm.getOrElse("tier", "free").toString, sm.get("percentage").fold(0)(_.toString.toInt)))
    case other => Left(s"Unknown rollout plan: $other")
  }
)
```

On this path the `encoder` matters even if you never read the encoded form yourself: it is what the caller's default
is converted to before being handed to the provider, so a provider can serve that default on a miss. A missing flag
comes back the same way it does for the built-in types — the caller's default with the provider's `FLAG_NOT_FOUND`
error code, not a `TYPE_MISMATCH`. A `TYPE_MISMATCH` on this path means what it says: the provider returned a
payload your `decoder` rejected.

Note the numbers your decoder receives arrive as `Double` (they pass through the OpenFeature `Value` bridge), and
null-valued fields are dropped rather than arriving as `null` — decode through `FlagType[Int]`/`FlagType[Long]`
rather than casting, and model optional fields as `Option`.

### Scalar-backed custom types

The most common feature flag is an **enum stored as a string** (`"off" | "dual_write" | "shard_only"`). Such
a flag is *not* an object: the provider holds a string, so it must be fetched with the **string** resolver and
then decoded into the domain type. `FlagType` expresses this with `wireType` — the representation the provider
is asked for, as distinct from `typeName`, the domain type.

`FlagType.mapped` sets `wireType` from its underlying type automatically, so this is all it takes:

```scala
enum Plan:
  case Free, Premium, Enterprise

object Plan:
  def parse(s: String): Plan = s match
    case "premium"    => Premium
    case "enterprise" => Enterprise
    case _            => Free
  def render(p: Plan): String = p.toString.toLowerCase

// wireType == "String" (inherited), typeName == "Plan"
given planFlagType: FlagType[Plan] =
  FlagType.mapped[Plan, String]("Plan", Plan.Free)(Plan.parse, Plan.render)

// Use with type safety — resolved through the provider's STRING resolver, then decoded
val plan: IO[FeatureFlagError, Plan] =
  FeatureFlags.value[Plan]("user-plan", Plan.Free)
```

The same works over any scalar: `FlagType.mapped[Level, Int](…)` for a newtype over an int, and so on.

> **Do not reach for `FlagType.from` for a string-backed enum.** It leaves `wireType` at the domain name, so
> the evaluation goes to the provider's *object* resolver, asks for an object the provider does not have, and
> comes back `TYPE_MISMATCH`.

If you need a **fallible** decoder on a scalar-backed type — rejecting an unknown variant rather than mapping
it to a fallback, which `mapped`'s total function cannot express — override `wireType` and `encode` together:

```scala
given tierFlagType: FlagType[Tier] with
  def typeName: String            = "Tier"
  override def wireType: String   = "String"        // fetch via the string resolver
  def defaultValue: Tier          = Tier.Free
  def decode(v: Any)              = Tier.parseStrict(v)   // may return Left => TYPE_MISMATCH
  override def encode(t: Tier)    = Tier.render(t)  // MUST produce the wireType's boxed type
```

A decode failure becomes a typed `TypeMismatch` error rather than a silently-substituted default. Note that
`wireType` also determines the `FlagValueType` hooks are filtered on, so a hook scoped to
`FlagValueType.String` sees these evaluations.

### Derived instances (Scala 3)

Most of the above can be skipped entirely: `FlagType` derives from the type's own structure, so a
string-backed enum or a structured flag needs no hand-written codec.

```scala
enum Plan derives FlagType:
  case Free, Premium, Enterprise

final case class Rollout(tier: String, pct: Int = 10, note: Option[String]) derives FlagType
```

An **enum with parameterless cases** derives a string codec over the case labels — `wireType` is `"String"`, so
it resolves through the provider's string method. `encode` emits the label as declared, `decode` matches
case-insensitively, and `defaultValue` is the first declared case. A case *with* parameters is not supported and
fails to compile; use `FlagType.from`/`mapped` for those.

A **product** derives a `Map[String, Any]` codec, field by field through each field's own `FlagType`, so nested
products, `Option` and `List` fields all work with no extra wiring. It stays on the object path. Unknown keys in
the payload are ignored, which keeps forward-compatible payloads working, and an absent key resolves in this
order:

1. the field's declared **Scala default**, if it has one (`pct = 10` above);
2. otherwise whatever the field's own instance makes of an absent value — which is how an `Option` field becomes
   `None`;
3. otherwise a decode error naming the field.

`defaultValue` for a derived product is built from its fields' own `defaultValue`s — `Rollout("", 0, None)` — a
type-level zero rather than the declared Scala defaults. That asymmetry is deliberate: `defaultValue` is never
consulted when evaluating (the caller's default is), whereas the Scala defaults describe how to read a real
payload.

Derivation is Scala 3 only, since it is built on `Mirror`. On 2.13 the same types are written with
`FlagType.from`/`mapped`, which remain fully supported on both versions.

> `FlagType.derived` is deliberately **not** a `given`, so it never competes in implicit search and the built-in
> instances keep priority for their own shapes. Use a `derives` clause, or write
> `given FlagType[X] = FlagType.derived[X]` explicitly.

### Typed Flag Definitions

The call above restates the key, the type and the default at every use site, so they can drift — two sites
falling back to different defaults for the same key, or reading one key at two types. `FlagDef[A]` states all
three once:

```scala
val UserPlan = FlagDef("user-plan", Plan.Free, "subscription tier")

val plan: ZIO[FeatureFlags, FeatureFlagError, Plan] =
  FeatureFlags.value(UserPlan)
```

`value`, `valueOrDefault`, `resolveOrDefault` and `valueDetails` each accept a `FlagDef` in place of the
`(key, default)` pair, on both the `FeatureFlags` trait and its companion accessors, with the same
context/options arities as the key-based forms:

```scala
FeatureFlags.valueOrDefault(UserPlan)                      // UIO[Plan] — never fails
FeatureFlags.value(UserPlan, ctx)                          // honours a targeting context
FeatureFlags.valueDetails(UserPlan, ctx, options)          // full resolution + invocation hooks
```

These are delegations to the generic tier, not a separate evaluation path, so hooks, caching, transactions,
timeouts and error semantics are all unchanged. The string-key API remains fully supported — `FlagDef` is
additive.

> **Which default is used.** `FlagType[A]` also carries a `defaultValue`, so a `FlagDef` looks like it holds two
> defaults. It does not in any way that matters: **`FlagDef.default` is always the value served** on a miss or
> error. `FlagType.defaultValue` is a type-level zero needed internally by `FlagType.from`/`mapped` and is never
> consulted when evaluating.

Two definitions for the same key with different defaults are **not** equal — they are genuinely different
definitions. Use `sameKey` to compare by key alone, across differing type parameters:

```scala
UserPlan.sameKey(FlagDef("user-plan", Plan.Enterprise))  // true
```

---

## Context Hierarchy

Evaluation context flows through six levels, with later levels taking precedence:

| Level | Scope | Use Case |
|:------|:------|:---------|
| **Global** | Application-wide | App version, environment, deployment region |
| **Transaction** | Within transaction block | Test overrides, experiment context |
| **Client** | FeatureFlags instance | Service name, region |
| **Context source** | Pulled per evaluation | Ambient identity held outside ZIO (MDC, tracing tags) |
| **Scoped** | Block of code (via `withContext`) | User session, request context |
| **Invocation** | Single evaluation | One-off targeting attributes |

Contexts merge with higher-precedence levels overriding lower ones:
`Invocation > Scoped > ContextSource > Client > Transaction > Global`. The five spec-defined levels are
push-based — the caller sets them. `ContextSource` is the one pull-based level: an effect the library
consults on every evaluation, for identity the application holds somewhere the ZIO environment cannot see.

Its slot is deliberate, and it is why this is library machinery rather than a hook. Ambient request
identity should override static client and global context, while an explicit `withContext` or a per-call
context at the call site should still win over it. A `before` hook cannot express that: its contribution is
merged on top of the already-finished effective context, so it can only ever take the highest-precedence
slot — and `HookContext` exposes one flattened context with no record of which attribute came from where,
so a hook cannot rebuild the ordering either.

```scala
val fromMdc = ContextSource(ZIO.succeed(EvaluationContext(Mdc.get("userId"))))

FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withContextSource(fromMdc))
```

`current` returns `UIO`, so a source can never fail an evaluation — a source with nothing to contribute
returns `EvaluationContext.empty`, which merges to a no-op. It is consulted on every evaluation and on
`track`, so keep it cheap (a `FiberRef` or `ThreadLocal` read, not a network call). Compose sources with
`++`; the right-hand side wins on key collisions, matching the right-biased merge used everywhere else.

See [Evaluation Context]({{ site.baseurl }}/context) for detailed usage, attribute types, and practical examples.

---

## Hook Pipeline

Hooks execute around flag evaluation in four stages: **before**, **after**, **error**, and **finallyAfter**. Hooks can modify evaluation context, pass data between stages via `HookHints`, and run effects for logging, metrics, or validation.

| Stage | When | Purpose |
|:------|:-----|:--------|
| **before** | Before evaluation | Modify context, start timers, validate |
| **after** | On successful evaluation | Log results, record metrics |
| **error** | On evaluation failure | Log errors, alert, fallback logic |
| **finallyAfter** | Always (like try-finally) | Cleanup, span completion. Receives `Option[FlagResolution[_]]` with evaluation details (spec 4.3.8) |

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
| `NotReady` | Provider not initialized. Evaluations fail with `ProviderNotReady` |
| `Ready` | Can evaluate flags |
| `Error` | Provider encountered a recoverable error. Evaluations still proceed (deliberate library policy — only `NotReady`/`Fatal` fail fast) — the provider serves cached values or errors on its own |
| `Stale` | Provider data may be outdated |
| `Fatal` | Provider encountered unrecoverable error. Evaluations fail with `ProviderFatal` |

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
| `ProviderInitializationFailed` | Sync init returned a non-`READY` state, or `setProviderAndWait` exceeded `initTimeout` |
| `ProviderFatal` | Provider hit an irrecoverable state (e.g. async init never produced a datafile within `initTimeout`) |
| `Unauthorized` | Provider rejected the request (HTTP 401 / 403 or analogous SDK signal — typically a wrong SDK key) |
| `Unreachable` | Provider could not be reached at the network layer (DNS, connection refused, no route) |
| `TargetingKeyMissing` | Required targeting key not provided |
| `InvalidContext` | Evaluation context is invalid |
| `InvalidConfiguration` | Provider construction was rejected (e.g. malformed `baseUrl`, placeholder SDK key) |
| `ProviderError` | Fallback wrapping an unclassified underlying provider exception |
| `NestedTransactionNotAllowed` | Attempted nested transaction |
| `OverrideTypeMismatch` | Transaction override type mismatch |

`Unauthorized`, `Unreachable`, and the `ProviderError` fallback are produced by a shared classifier (`FeatureFlagError.classify`) wherever the library wraps a `Throwable` from the underlying provider. Operators can alert on `Unauthorized` (a misconfigured key) distinctly from `Unreachable` (a network outage) and the generic `ProviderError` (everything else, with the original `Throwable` preserved on `.cause`).

### Error Recovery

```scala
FeatureFlags.boolean("feature", false)
  .catchSome {
    case FeatureFlagError.FlagNotFound(_) =>
      ZIO.succeed(false)  // Use default
    case _: FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false)  // Fail safe — provider not yet ready
    case _: FeatureFlagError.Unreachable =>
      ZIO.succeed(false)  // Network blip — serve default rather than fail
    case _: FeatureFlagError.Unauthorized =>
      // Misconfigured SDK key — fail loud so operators investigate, don't paper over
      ZIO.logError("Provider auth failed — check SDK key rotation") *> ZIO.succeed(false)
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
        └── TestFeatureProvider.scala # In-memory OpenFeature provider
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

