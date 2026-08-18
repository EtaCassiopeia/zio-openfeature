---
layout: default
title: OpenFeature Spec Compliance
nav_order: 13
---

# OpenFeature Specification Compliance

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java), inheriting its specification compliance while adding ZIO-specific features.

---

## Version Compatibility

| Component | Version | Notes |
|:----------|:--------|:------|
| **OpenFeature Spec** | v0.9.0 on `main`, v0.8.0 as of 1.0.0 | [Specification](https://github.com/open-feature/spec) |
| **OpenFeature Java SDK** | 1.22.0 on `main`, 1.21.0 as of 1.0.0 | [Java SDK](https://github.com/open-feature/java-sdk) |
| **ZIO OpenFeature** | [![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3) | This library |

This library targets the **dynamic-context paradigm** (server-side) of the OpenFeature specification.

### How compliance is verified

The claims on this page are executable, not assertions. The specification's own gherkin suites are vendored
verbatim from [`open-feature/spec`](https://github.com/open-feature/spec) at the pinned tag above and run on
every build, twice — once under Cucumber (`conformance`) and once under
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) (`conformance-zio-bdd`). A scheduled job compares the
vendored copies against upstream and files an issue if they drift, so a new or changed upstream scenario
surfaces as work rather than silently going unrun.

The same zio-bdd module carries a second suite for this library's *own* API — the ZIO-specific features
below, which the spec has no opinion about — in its own repo-owned feature directory, so the vendored spec
files stay byte-identical to upstream.

---

## Compliance Summary

The library is **fully compliant** with core OpenFeature functionality:

| Category | Status | Notes |
|:---------|:-------|:------|
| Flag Evaluation | ✅ Full | All typed evaluation methods |
| Evaluation Context | ✅ Full | Global, Client, Scoped, Transaction, Invocation levels |
| Hooks | ✅ Full | API, Client, and Invocation levels |
| Provider Lifecycle | ✅ Full | Init, shutdown, status, metadata |
| Events | ✅ Full | Stream + specific handlers with cancellation |
| Tracking | ✅ Full | All track() overloads |

---

## Flag Evaluation (Spec 1.3-1.4)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Boolean evaluation | ✅ | `boolean(key, default)`, `boolean(key, default, ctx)` |
| String evaluation | ✅ | `string(key, default)`, `string(key, default, ctx)` |
| Integer evaluation | ✅ | `int(key, default)`, `long(key, default)` + context overloads |
| Double evaluation | ✅ | `double(key, default)`, `double(key, default, ctx)` |
| Object evaluation | ✅ | `obj(key, default)`, `obj(key, default, ctx)` |
| Generic evaluation | ✅ | `value[A](key, default)` with `FlagType` + context overload |
| Detailed evaluation | ✅ | `*Details(key, default)`, `*Details(key, default, ctx)`, `*Details(key, default, ctx, options)` |
| Context overload | ✅ | All methods accept optional `EvaluationContext` |
| Options overload | ✅ | All detail methods accept `EvaluationOptions` |
| No exceptions | ✅ | Returns ZIO effects with typed errors |

### Evaluation Methods

All types support three overload patterns:

```scala
// Basic evaluation
FeatureFlags.boolean("flag", false)
FeatureFlags.int("flag", 0)
FeatureFlags.long("flag", 0L)
FeatureFlags.double("flag", 0.0)
FeatureFlags.string("flag", "default")
FeatureFlags.obj("flag", Map.empty)
FeatureFlags.value[A]("flag", default)

// With context
FeatureFlags.boolean("flag", false, context)
FeatureFlags.long("flag", 0L, context)
FeatureFlags.obj("flag", Map.empty, context)

// Detailed evaluation (all three patterns)
FeatureFlags.booleanDetails("flag", false)
FeatureFlags.booleanDetails("flag", false, context)
FeatureFlags.booleanDetails("flag", false, context, options)

// Same patterns for: stringDetails, intDetails, longDetails, doubleDetails, objDetails, valueDetails
```

### Resolution Details

| Field | Status | Notes |
|:------|:-------|:------|
| value | ✅ | The resolved flag value |
| variant | ✅ | Optional variant identifier |
| reason | ✅ | STATIC, DEFAULT, TARGETING_MATCH, SPLIT, CACHED, etc. |
| errorCode | ✅ | PROVIDER_NOT_READY, PROVIDER_FATAL, FLAG_NOT_FOUND, TYPE_MISMATCH, etc. |
| errorMessage | ✅ | Optional error description |
| flagMetadata | ✅ | Typed metadata values (boolean, string, int, long, double, float) per spec 2.2.10 |

---

## Evaluation Context (Spec 3.1)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Targeting key | ✅ | `EvaluationContext.targetingKey` |
| Custom attributes | ✅ | `EvaluationContext.attributes` |
| Global context | ✅ | `setGlobalContext` / `globalContext` |
| Client context | ✅ | `setClientContext` / `clientContext` |
| Scoped context | ✅ | `withContext` |
| Invocation context | ✅ | Per-evaluation parameter |
| Context merging | ✅ | Global → Transaction → Client → Scoped → Invocation |

### Context Merge Order

```
┌──────────────────────────────────────────────────────────────┐
│                     Final Merged Context                      │
│  (Invocation > Scoped > Client > Transaction > Global)       │
└──────────────────────────────────────────────────────────────┘
```

---

## Hooks (Spec 4.x)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Hook stages | ✅ | before, after, error, finallyAfter |
| Hook context | ✅ | flagKey, flagType, defaultValue, context, clientMetadata, providerMetadata |
| Hook hints | ✅ | `HookHints` for passing data between stages |
| Hook data (4.6.1) | ✅ | `HookData` per-hook mutable state across stages |
| API-level hooks | ✅ | `FeatureFlags.addApiHook` / `clearApiHooks` |
| Client-level hooks | ✅ | `FeatureFlags.addHook` / `addHooks` / `clearHooks` |
| Invocation-level hooks | ✅ | Via `EvaluationOptions` |
| Provider-level hooks | ✅ | Automatically included from `provider.getProviderHooks()` |
| Execution order | ✅ | API → Client → Invocation → Provider (reversed for after/error/finally) |

### Invocation-Level Hooks Example

```scala
val options = EvaluationOptions(
  hooks = List(myHook),
  hookHints = HookHints("key" -> "value")
)

FeatureFlags.booleanDetails("flag", false, EvaluationContext.empty, options)
```

---

## Provider Lifecycle (Spec 2.4-2.5)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Initialize (blocking) | ✅ | `setProviderAndWait` on layer creation |
| Initialize (async) | ✅ | `setProvider` via `fromProviderAsync` / `FeatureFlagsConfig(initMode = InitMode.Async)` |
| Shutdown (1.6.1) | ✅ | Automatic via ZIO Scope finalizer + explicit `shutdown` method |
| Provider metadata | ✅ | `providerMetadata` returns name and version |
| Client metadata | ✅ | `clientMetadata` returns domain and version |
| Domain binding | ✅ | `FeatureFlagsConfig().withDomain(domain)` |
| Client version | ✅ | `FeatureFlagsConfig().withDomain(domain).withVersion(version)` |

---

## Provider Status (Spec 1.7)

| Status | Implemented | Description |
|:-------|:------------|:------------|
| NOT_READY | ✅ `NotReady` | Provider not initialized |
| READY | ✅ `Ready` | Provider ready for evaluation |
| ERROR | ✅ `Error` | Provider encountered recoverable error |
| STALE | ✅ `Stale` | Provider data may be outdated |
| FATAL | ✅ `Fatal` | Provider encountered unrecoverable error |
| (extra) | ✅ `ShuttingDown` | Provider is shutting down |

```scala
for
  status <- FeatureFlags.providerStatus
  _ <- ZIO.when(status == ProviderStatus.Ready) {
         Console.printLine("Provider ready")
       }
yield ()
```

When a provider is in the `NotReady` state, all flag evaluations will fail with `FeatureFlagError.ProviderNotReady`. When a provider enters the `Fatal` state, all flag evaluations will fail with `FeatureFlagError.ProviderFatal`.

---

## Events (Spec 5.x)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Ready event | ✅ | `ProviderEvent.Ready` |
| Error event | ✅ | `ProviderEvent.Error` |
| Stale event | ✅ | `ProviderEvent.Stale` |
| ConfigurationChanged | ✅ | `ProviderEvent.ConfigurationChanged` |
| Reconnecting event | ✅ | `ProviderEvent.Reconnecting` |
| Event metadata | ✅ | All events carry `eventMetadata: FlagMetadata` from provider |
| Event stream | ✅ | `FeatureFlags.events` returns ZStream |
| Handler registration | ✅ | `onProviderReady`, `onProviderError`, etc. |
| Handler cancellation | ✅ | Handlers return cancellation effect |
| Generic handler | ✅ | `on(eventType, handler)` |
| Immediate execution | ✅ | Handlers run immediately if state matches (Spec 5.3.3) |

### Event Handlers

```scala
// Specific event handlers (return cancellation effect)
val cancel = FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo(s"Provider ${metadata.name} is ready")
}

// Cancel when no longer needed
cancel.flatMap(c => c)

// Generic event handler
FeatureFlags.on(ProviderEventType.Error, event => ZIO.logError(s"Error: $event"))

// Event stream
FeatureFlags.events.foreach { event =>
  ZIO.logInfo(s"Event: $event")
}.fork
```

---

## Tracking (Spec 2.7)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Track event name | ✅ | `track(eventName)` |
| Track with context | ✅ | `track(eventName, context)` |
| Track with details | ✅ | `track(eventName, details)` |
| Track full | ✅ | `track(eventName, context, details)` |

### Tracking Example

```scala
// Simple tracking
FeatureFlags.track("button-clicked")

// With context
FeatureFlags.track("purchase", EvaluationContext("user-123"))

// With details
val details = TrackingEventDetails(
  value = Some(99.99),
  attributes = Map("currency" -> "USD", "items" -> 3)
)
FeatureFlags.track("checkout", details)
```

---

## ZIO-Specific Features

Beyond the OpenFeature spec, ZIO OpenFeature provides:

| Feature | Description |
|:--------|:------------|
| Transactions | Scoped overrides with evaluation caching and tracking; re-entrant via `NestedPolicy.Reuse`; `transactionEvaluations` tells "no transaction" from "nothing evaluated" |
| Typed and total tiers | `value`/`*Details` fail typed on any evaluation problem, including a provider-reported error code; `*OrDefault`/`resolveOrDefault` never fail and serve the default (the spec's §1.4.10 contract) |
| Fiber-local context | `withContext` scopes context to a code block via FiberRef |
| Type-safe evaluation | `FlagType` type class for compile-time safety |
| Typed flag definitions | `FlagDef[A]` states a flag's key, type and default once — see [Typed Flags]({{ site.baseurl }}/typed-flags) |
| Derived flag codecs | `derives FlagType` for your own enums and case classes (Scala 3) |
| Ambient context | `ContextSource` pulls request identity into every evaluation from outside the ZIO environment |
| Effect-based hooks | Hooks return `UIO` instead of callbacks |
| Resource management | Automatic lifecycle via ZIO Scope |
| Event streaming | Provider events as ZStream |
| Multi-provider | Combine multiple providers with configurable strategies |
| Verified hot-swap | `fromAcquireAsync` serves a fallback, verifies the real provider before swapping it in, and reports `AcquireStatus` |
| Served-default logging | A default served after a failure leaves a warn line, rate-limited per flag key |

---

## Comparison with Java SDK

| Aspect | Java SDK | ZIO OpenFeature |
|:-------|:---------|:----------------|
| Provider management | Global singleton | ZIO Layer |
| Error handling | Exceptions + defaults | Typed effects |
| Context scoping | Thread-local | Fiber-local |
| Transactions | Not available | Built-in |
| Hooks | Callbacks | Effects (UIO) |
| Events | Callbacks | ZStream + handlers |
| Tracking | Blocking calls | Effect-based |

---

## Provider SDK Version Compatibility

ZIO OpenFeature ships with OpenFeature Java SDK 1.20.2. You may use providers compiled against older SDK versions, with the following considerations:

**Providers implementing `FeatureProvider` directly — fully compatible.** The `FeatureProvider` interface is identical from v1.14.1 through v1.20.2. No abstract methods were added; every new method (e.g., `track()`) was introduced with a `default` no-op implementation. A provider JAR compiled against any 1.14.x+ SDK will work at runtime without recompilation.

**Providers extending `EventProvider` — requires SDK 1.16.0+.** In SDK v1.16.0, the `emit()` and `emitProvider*()` methods changed their return type from `void` to `Awaitable`. Since return types are part of JVM method descriptors, a provider compiled against a pre-1.16.0 SDK that calls `this.emit(...)` will fail at runtime with `NoSuchMethodError`. Most ecosystem providers (flagd, LaunchDarkly, Optimizely, etc.) extend `EventProvider`, so they need to be compiled against SDK 1.16.0+.

**Recommendation:** Use provider versions that target the same SDK generation as this library (1.16.0+). When in doubt, check the provider's declared `dev.openfeature:sdk` dependency version.

---

## Not Implemented

The following OpenFeature features are not exposed in the ZIO wrapper:

| Feature | Reason |
|:--------|:-------|
| Context change reconciliation | Provider-specific, handled by SDK |
| Static-context paradigm | Use standard context hierarchy instead |
