---
layout: default
title: OpenFeature Spec Compliance
nav_order: 9
---

# OpenFeature Specification Compliance
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java), inheriting its specification compliance while adding ZIO-specific features. This document tracks compliance with the [OpenFeature Specification](https://openfeature.dev/specification/).

**Legend:**
- ✅ Implemented
- ⚠️ Partial / Different approach
- ❌ Not implemented

**Estimated Compliance: ~70%**

---

## Flag Evaluation API (Section 1)

### API Initialization & Configuration

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 1.1.1 - Global singleton | ⚠️ | Uses ZIO layers. OpenFeature SDK singleton managed internally. |
| 1.1.2.1 - Provider setter | ✅ | `FeatureFlags.fromProvider(provider)` |
| 1.1.2.2 - Initialize on register | ✅ | Uses `setProviderAndWait` for blocking initialization |
| 1.1.2.3 - Shutdown previous provider | ✅ | Handled by OpenFeature SDK and ZIO Scope finalizers |
| 1.1.3 - Domain-based provider binding | ✅ | `FeatureFlags.fromProviderWithDomain(provider, domain)` |
| 1.1.4 - Add hooks | ✅ | `FeatureFlags.addHook` appends to ZIO-native hook collection |
| 1.1.5 - Provider metadata | ✅ | `FeatureFlags.providerMetadata` |
| 1.1.6 - Create clients | ✅ | OpenFeature SDK client created internally |
| 1.1.7 - Client creation doesn't throw | ✅ | ZIO effect system ensures no exceptions |

### Flag Evaluation Methods

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 1.3.1.1 - Typed evaluation methods | ✅ | `boolean`, `string`, `int`, `long`, `double`, `obj`, `value[A]` |
| 1.3.3.1 - Separate int/double | ✅ | Separate `int`, `long`, `double` methods |
| 1.3.4 - Type guarantee | ✅ | `FlagType` type class ensures type safety |
| 1.4.1.1 - Detailed evaluation | ✅ | `booleanDetails`, `stringDetails`, etc. return `FlagResolution` |
| 1.4.3 - Value field | ✅ | `FlagResolution.value` |
| 1.4.5 - Flag key field | ✅ | `FlagResolution.flagKey` |
| 1.4.6 - Variant field | ✅ | `FlagResolution.variant` |
| 1.4.7 - Reason field | ✅ | `FlagResolution.reason` with `ResolutionReason` enum |
| 1.4.8 - Error code | ✅ | `FlagResolution.errorCode` |
| 1.4.10 - No exceptions | ✅ | Returns ZIO effect; errors are typed |
| 1.4.13 - Error message | ✅ | `FlagResolution.errorMessage` |
| 1.4.14 - Flag metadata | ✅ | `FlagResolution.metadata` |

### Evaluation Options

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 1.5.1 - Evaluation options hooks | ❌ | Per-invocation hooks not supported; use `addHook` |

### Provider Lifecycle

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 1.6.1 - Shutdown function | ✅ | Managed by ZIO Scope and OpenFeature SDK |
| 1.7.1 - Provider status | ⚠️ | `NotReady`, `Ready`, `Error`, `Stale`. Missing: `FATAL` |
| 1.7.3 - READY after init | ✅ | Providers set `Ready` on successful init |
| 1.7.4 - ERROR after init failure | ✅ | `ProviderStatus.Error` available |
| 1.7.5 - FATAL for fatal errors | ❌ | `FATAL` status not implemented |
| 1.7.6 - PROVIDER_NOT_READY error | ✅ | `FeatureFlagError.ProviderNotReady` |

---

## Providers (Section 2)

### Provider Interface

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 2.1.1 - Metadata with name | ✅ | Via OpenFeature provider's `getMetadata()` |
| 2.2.1 - Resolution methods | ✅ | Delegated to OpenFeature SDK Client |
| 2.2.3 - Value in resolution | ✅ | `FlagResolution.value` |
| 2.2.4 - Variant in resolution | ✅ | `FlagResolution.variant: Option[String]` |
| 2.2.5 - Reason in resolution | ✅ | `FlagResolution.reason: ResolutionReason` |
| 2.2.9 - Flag metadata | ✅ | `FlagResolution.metadata: FlagMetadata` |
| 2.2.10 - Metadata structure | ✅ | `FlagMetadata` supports arbitrary string keys |

### Provider Hooks

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 2.3.1 - Provider hook mechanism | ❌ | Provider-level hooks not exposed; use ZIO hooks |

### Provider Lifecycle

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 2.4.1 - Initialize function | ✅ | Via OpenFeature SDK `setProviderAndWait` |
| 2.5.1 - Shutdown mechanism | ✅ | Via OpenFeature SDK `shutdown()` |

### Context Reconciliation

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 2.6.1 - On context changed | ❌ | Not implemented |

### Tracking

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 2.7.1 - Tracking support | ❌ | Not implemented |

---

## Evaluation Context (Section 3)

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 3.1.1 - Targeting key | ✅ | `EvaluationContext.targetingKey: Option[String]` |
| 3.1.2 - Custom fields | ✅ | `EvaluationContext.attributes: Map[String, AttributeValue]` |
| 3.1.3 - Field access | ✅ | `getAttribute`, `attributes` accessor |
| 3.1.4 - Unique keys | ✅ | Map structure ensures uniqueness |
| 3.2.3 - Context merge order | ✅ | Global → Scoped → Transaction → Invocation |

### Context Hierarchy

ZIO OpenFeature implements a 4-level context hierarchy:

```
Global Context (lowest precedence)
    ↓ merge
Scoped Context (withContext)
    ↓ merge
Transaction Context
    ↓ merge
Invocation Context (highest precedence)
```

---

## Hooks (Section 4)

### Hook Context

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 4.1.1 - Hook context fields | ✅ | `HookContext(flagKey, flagType, defaultValue, evaluationContext, providerMetadata)` |
| 4.1.2 - Provider metadata | ✅ | `HookContext.providerMetadata` |
| 4.1.3 - Immutable key/type/default | ✅ | Case class with immutable fields |
| 4.1.5 - Mutable hook data | ❌ | Hook data not implemented |

### Hook Hints

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 4.2.1 - Hook hints structure | ✅ | `HookHints(values: Map[String, Any])` |

### Hook Lifecycle

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 4.3.1 - Specify stages | ✅ | `before`, `after`, `error`, `finallyAfter` |
| 4.3.2.1 - Before stage | ✅ | `before` returns `Option[(EvaluationContext, HookHints)]` |
| 4.3.4 - Context propagation | ✅ | `FeatureHook.compose` propagates context between hooks |
| 4.3.6 - After stage | ✅ | `after` receives `FlagResolution` |
| 4.3.7 - Error hook | ✅ | `error` receives `FeatureFlagError` |
| 4.3.8 - Finally hook | ✅ | `finallyAfter` always runs |

### Hook Registration & Execution

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 4.4.1 - Registration methods | ⚠️ | API-level hooks via `addHook`. No provider/invocation hooks. |
| 4.4.2 - Stack-wise execution | ⚠️ | `before` in order, `after`/`error`/`finally` in reverse |
| 4.4.7 - Default on before error | ✅ | Error in hooks handled by ZIO error handling |

---

## Events (Section 5)

### Provider Events

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 5.1.1 - Event types | ✅ | `Ready`, `Error`, `Stale`, `ConfigurationChanged` |
| 5.1.2 - Event handlers run | ✅ | Via `FeatureFlags.events` ZStream |
| 5.1.4 - Error message | ✅ | `ProviderEvent.Error(error, metadata)` |

### Event Handlers

| Requirement | Status | Notes |
|:------------|:-------|:------|
| 5.2.1 - Client event handlers | ⚠️ | Uses ZStream instead of callbacks |
| 5.2.3 - Provider name in details | ✅ | `ProviderEvent.metadata.name` |
| 5.2.5 - Handler error isolation | ✅ | ZIO effect system handles errors |
| 5.3.1 - READY triggers handlers | ✅ | `ProviderEvent.Ready` emitted |
| 5.3.2 - ERROR triggers handlers | ✅ | `ProviderEvent.Error` emitted |

---

## Gap Analysis

### Critical Gaps

| Feature | Description | Priority |
|:--------|:------------|:---------|
| FATAL provider status | Unrecoverable provider errors | Medium |
| Per-invocation hooks | Hooks passed to individual evaluations | Low |
| Provider-level hooks | Providers registering their own hooks | Low |

### Important Gaps

| Feature | Description | Priority |
|:--------|:------------|:---------|
| Context change events | Provider notification of context changes | Medium |
| Tracking support | User action/state tracking | Medium |
| Mutable hook data | Data passed between hook stages | Low |
| RECONCILING status | Static context paradigm support | Low |

---

## ZIO-Specific Features

Beyond the OpenFeature spec, ZIO OpenFeature provides:

### Transactions

Atomic flag evaluation with caching and overrides:

```scala
FeatureFlags.transaction(
  overrides = Map("feature-a" -> false),
  context = EvaluationContext("user-123"),
  cacheEvaluations = true
) {
  for
    a <- FeatureFlags.boolean("feature-a", false)
    b <- FeatureFlags.int("feature-b", 0)
  yield (a, b)
}
```

Features:
- **Auto-caching**: Flags evaluated once per transaction (optional)
- **Overrides**: Test with specific flag values
- **Evaluation tracking**: Know which flags were evaluated
- **Atomic context**: Consistent context for all evaluations

### Fiber-Local Context

Scope context to a block of code using ZIO's `FiberRef`:

```scala
FeatureFlags.withContext(requestContext) {
  // All evaluations use requestContext
  FeatureFlags.boolean("feature", false)
}
```

### Type-Safe Flag Types

The `FlagType` type class provides:
- Compile-time type safety
- Automatic type conversion
- Custom type support

```scala
given FlagType[MyEnum] = FlagType.from(...)
val value = FeatureFlags.value[MyEnum]("flag", MyEnum.Default)
```

### Built-in Hooks

Ready-to-use hooks:
- `FeatureHook.logging` - Log flag evaluations
- `FeatureHook.metrics` - Track evaluation timing
- `FeatureHook.contextValidator` - Validate required attributes

### Scoped Resource Management

Automatic provider lifecycle management via ZIO Scope:

```scala
program.provide(Scope.default >>> FeatureFlags.fromProvider(provider))
// Provider automatically initialized and shutdown
```

---

## Comparison with OpenFeature Java SDK

| Feature | Java SDK | ZIO OpenFeature |
|:--------|:---------|:----------------|
| Provider management | Global singleton | ZIO Layer |
| Error handling | Exceptions + defaults | Typed ZIO effects |
| Context scoping | Thread-local | Fiber-local (FiberRef) |
| Transactions | Not available | Built-in with caching |
| Hooks | Callback-based | Effect-based (UIO) |
| Events | Callback handlers | ZStream |
| Type safety | Runtime checks | Compile-time (FlagType) |

---

## Compliance Summary

**Core Features (Fully Compliant):**
- Flag evaluation API
- Evaluation context
- Provider lifecycle
- Provider events
- Hook lifecycle

**Advanced Features (Partial/Alternative):**
- Hook registration (ZIO-native approach)
- Event handlers (ZStream instead of callbacks)
- Domain-based providers (supported)

**Not Implemented:**
- FATAL provider status
- Per-invocation hooks
- Provider hooks
- Context change reconciliation
- Tracking support

The library provides all essential OpenFeature functionality while leveraging ZIO's effect system for improved type safety, resource management, and composability.

