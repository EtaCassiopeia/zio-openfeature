---
layout: default
title: OpenFeature Spec Compliance
nav_order: 9
---

# OpenFeature Specification Compliance

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java), inheriting its specification compliance while adding ZIO-specific features.

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
| Boolean evaluation | ✅ | `boolean(key, default)` |
| String evaluation | ✅ | `string(key, default)` |
| Integer evaluation | ✅ | `int(key, default)`, `long(key, default)` |
| Double evaluation | ✅ | `double(key, default)` |
| Object evaluation | ✅ | `obj(key, default)` |
| Generic evaluation | ✅ | `value[A](key, default)` with `FlagType` |
| Detailed evaluation | ✅ | `booleanDetails`, `stringDetails`, etc. |
| Context overload | ✅ | All methods accept optional `EvaluationContext` |
| Options overload | ✅ | Detail methods accept `EvaluationOptions` |
| No exceptions | ✅ | Returns ZIO effects with typed errors |

### Resolution Details

| Field | Status | Notes |
|:------|:-------|:------|
| value | ✅ | The resolved flag value |
| variant | ✅ | Optional variant identifier |
| reason | ✅ | STATIC, DEFAULT, TARGETING_MATCH, SPLIT, CACHED, etc. |
| errorCode | ✅ | PROVIDER_NOT_READY, FLAG_NOT_FOUND, TYPE_MISMATCH, etc. |
| errorMessage | ✅ | Optional error description |
| flagMetadata | ✅ | Additional provider metadata |

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
| Context merging | ✅ | Global → Client → Scoped → Transaction → Invocation |

### Context Merge Order

```
┌──────────────────────────────────────────────────────────────┐
│                     Final Merged Context                      │
│  (Invocation > Transaction > Scoped > Client > Global)       │
└──────────────────────────────────────────────────────────────┘
```

---

## Hooks (Spec 4.x)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Hook stages | ✅ | before, after, error, finallyAfter |
| Hook context | ✅ | flagKey, flagType, defaultValue, context, metadata |
| Hook hints | ✅ | `HookHints` for passing data between stages |
| API-level hooks | ✅ | `FeatureFlags.addApiHook` / `clearApiHooks` |
| Client-level hooks | ✅ | `FeatureFlags.addHook` / `clearHooks` |
| Invocation-level hooks | ✅ | Via `EvaluationOptions` |
| Execution order | ✅ | API → Client → Invocation (reversed for after/error/finally) |

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
| Initialize | ✅ | `setProviderAndWait` on layer creation |
| Shutdown | ✅ | Automatic via ZIO Scope finalizer |
| Provider metadata | ✅ | `providerMetadata` returns name and version |
| Client metadata | ✅ | `clientMetadata` returns domain |
| Domain binding | ✅ | `fromProviderWithDomain` |

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

---

## Events (Spec 5.x)

| Requirement | Status | Implementation |
|:------------|:-------|:---------------|
| Ready event | ✅ | `ProviderEvent.Ready` |
| Error event | ✅ | `ProviderEvent.Error` |
| Stale event | ✅ | `ProviderEvent.Stale` |
| ConfigurationChanged | ✅ | `ProviderEvent.ConfigurationChanged` |
| Reconnecting event | ✅ | `ProviderEvent.Reconnecting` |
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
| Transactions | Scoped overrides with evaluation caching and tracking |
| Fiber-local context | `withContext` scopes context to a code block via FiberRef |
| Type-safe evaluation | `FlagType` type class for compile-time safety |
| Effect-based hooks | Hooks return `UIO` instead of callbacks |
| Resource management | Automatic lifecycle via ZIO Scope |
| Event streaming | Provider events as ZStream |

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

## Not Implemented

The following OpenFeature features are not exposed in the ZIO wrapper:

| Feature | Reason |
|:--------|:-------|
| Provider-level hooks | Handled internally by OpenFeature SDK |
| Context change reconciliation | Provider-specific, handled by SDK |
| Static-context paradigm | Use standard context hierarchy instead |
