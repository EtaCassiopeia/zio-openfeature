---
layout: default
title: OpenFeature Spec Compliance
nav_order: 10
---

# OpenFeature Specification Compliance
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

This document tracks zio-openfeature's compliance with the [OpenFeature Specification](https://openfeature.dev/specification/). The specification defines normative requirements using keywords like MUST, SHOULD, and MAY.

**Legend:**
- ✅ Implemented
- ⚠️ Partial / Different approach
- ❌ Not implemented

---

## Flag Evaluation API (Section 1)

### API Initialization & Configuration

| Requirement | Status | Notes |
|-------------|--------|-------|
| 1.1.1 - Global singleton | ⚠️ | ZIO uses layers instead of global singleton. `FeatureFlags.live` provides the service. |
| 1.1.2.1 - Provider setter | ✅ | Provider is injected via ZLayer dependency |
| 1.1.2.2 - Initialize on register | ⚠️ | Manual initialization; use `scopedLayer` for automatic |
| 1.1.2.3 - Shutdown previous provider | ⚠️ | Handled by ZIO Scope finalizers |
| 1.1.3 - Domain-based provider binding | ❌ | Not implemented; single provider per service |
| 1.1.4 - Add hooks | ✅ | `FeatureFlags.addHook` appends to collection |
| 1.1.5 - Provider metadata | ✅ | `FeatureFlags.providerMetadata` |
| 1.1.6 - Create clients | ⚠️ | Uses ZIO service pattern instead of explicit clients |
| 1.1.7 - Client creation doesn't throw | ✅ | ZIO effect system ensures no exceptions |

### Flag Evaluation Methods

| Requirement | Status | Notes |
|-------------|--------|-------|
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
|-------------|--------|-------|
| 1.5.1 - Evaluation options hooks | ❌ | Per-invocation hooks not supported; use `addHook` |

### Provider Lifecycle

| Requirement | Status | Notes |
|-------------|--------|-------|
| 1.6.1 - Shutdown function | ✅ | `FeatureProvider.shutdown` |
| 1.7.1 - Provider status | ⚠️ | `NotReady`, `Ready`, `Error`, `Stale`, `ShuttingDown`. Missing: `FATAL` |
| 1.7.3 - READY after init | ✅ | Providers set `Ready` on successful init |
| 1.7.4 - ERROR after init failure | ✅ | `ProviderStatus.Error` available |
| 1.7.5 - FATAL for fatal errors | ❌ | `FATAL` status not implemented |
| 1.7.6 - PROVIDER_NOT_READY error | ✅ | `FeatureFlagError.ProviderNotReady` |

---

## Providers (Section 2)

### Provider Interface

| Requirement | Status | Notes |
|-------------|--------|-------|
| 2.1.1 - Metadata with name | ✅ | `ProviderMetadata(name, version)` |
| 2.2.1 - Resolution methods | ✅ | `resolveBooleanValue`, `resolveStringValue`, etc. |
| 2.2.3 - Value in resolution | ✅ | `FlagResolution.value` |
| 2.2.4 - Variant in resolution | ✅ | `FlagResolution.variant: Option[String]` |
| 2.2.5 - Reason in resolution | ✅ | `FlagResolution.reason: ResolutionReason` |
| 2.2.9 - Flag metadata | ✅ | `FlagResolution.metadata: FlagMetadata` |
| 2.2.10 - Metadata structure | ✅ | `FlagMetadata` supports arbitrary string keys |

### Provider Hooks

| Requirement | Status | Notes |
|-------------|--------|-------|
| 2.3.1 - Provider hook mechanism | ❌ | Provider-level hooks not implemented |

### Provider Lifecycle

| Requirement | Status | Notes |
|-------------|--------|-------|
| 2.4.1 - Initialize function | ✅ | `FeatureProvider.initialize` |
| 2.5.1 - Shutdown mechanism | ✅ | `FeatureProvider.shutdown` |

### Context Reconciliation

| Requirement | Status | Notes |
|-------------|--------|-------|
| 2.6.1 - On context changed | ❌ | Not implemented |

### Tracking

| Requirement | Status | Notes |
|-------------|--------|-------|
| 2.7.1 - Tracking support | ❌ | Not implemented |

---

## Evaluation Context (Section 3)

| Requirement | Status | Notes |
|-------------|--------|-------|
| 3.1.1 - Targeting key | ✅ | `EvaluationContext.targetingKey: Option[String]` |
| 3.1.2 - Custom fields | ✅ | `EvaluationContext.attributes: Map[String, AttributeValue]` |
| 3.1.3 - Field access | ✅ | `getAttribute`, `attributes` accessor |
| 3.1.4 - Unique keys | ✅ | Map structure ensures uniqueness |
| 3.2.3 - Context merge order | ✅ | Global → Transaction → Fiber → Invocation (see below) |

### Context Hierarchy

```
Global Context (lowest precedence)
    ↓ merge
Transaction Context
    ↓ merge
Fiber-local Context (withContext)
    ↓ merge
Invocation Context (highest precedence)
```

Implementation in `FeatureFlagsLive.effectiveContext`:
```scala
global
  .merge(fiberLocal)
  .merge(txContext)
  .merge(invocation)
```

---

## Hooks (Section 4)

### Hook Context

| Requirement | Status | Notes |
|-------------|--------|-------|
| 4.1.1 - Hook context fields | ✅ | `HookContext(flagKey, flagType, defaultValue, evaluationContext, providerMetadata)` |
| 4.1.2 - Provider metadata | ✅ | `HookContext.providerMetadata` |
| 4.1.3 - Immutable key/type/default | ✅ | Case class with immutable fields |
| 4.1.5 - Mutable hook data | ❌ | Hook data not implemented |

### Hook Hints

| Requirement | Status | Notes |
|-------------|--------|-------|
| 4.2.1 - Hook hints structure | ✅ | `HookHints(values: Map[String, Any])` |

### Hook Lifecycle

| Requirement | Status | Notes |
|-------------|--------|-------|
| 4.3.1 - Specify stages | ✅ | `before`, `after`, `error`, `finallyAfter` |
| 4.3.2.1 - Before stage | ✅ | `before` returns `Option[(EvaluationContext, HookHints)]` |
| 4.3.4 - Context propagation | ✅ | `FeatureHook.compose` propagates context between hooks |
| 4.3.6 - After stage | ✅ | `after` receives `FlagResolution` |
| 4.3.7 - Error hook | ✅ | `error` receives `FeatureFlagError` |
| 4.3.8 - Finally hook | ✅ | `finallyAfter` always runs |

### Hook Registration & Execution

| Requirement | Status | Notes |
|-------------|--------|-------|
| 4.4.1 - Registration methods | ⚠️ | API-level hooks via `addHook`. No provider/invocation hooks. |
| 4.4.2 - Stack-wise execution | ⚠️ | Hooks execute in registration order; no reversal after resolution |
| 4.4.7 - Default on before error | ✅ | Error in hooks returns default via ZIO error handling |

---

## Events (Section 5)

### Provider Events

| Requirement | Status | Notes |
|-------------|--------|-------|
| 5.1.1 - Event types | ✅ | `Ready`, `Error`, `Stale`, `ConfigurationChanged`, `Reconnecting` |
| 5.1.2 - Event handlers run | ✅ | Via ZStream subscription |
| 5.1.4 - Error message | ✅ | `ProviderEvent.Error(error, metadata)` |

### Event Handlers

| Requirement | Status | Notes |
|-------------|--------|-------|
| 5.2.1 - Client event handlers | ⚠️ | Uses `FeatureFlags.events` ZStream instead of callbacks |
| 5.2.3 - Provider name in details | ✅ | `ProviderEvent.metadata.name` |
| 5.2.5 - Handler error isolation | ✅ | ZIO effect system handles errors |
| 5.3.1 - READY triggers handlers | ✅ | `ProviderEvent.Ready` emitted |
| 5.3.2 - ERROR triggers handlers | ✅ | `ProviderEvent.Error` emitted |

---

## ZIO-Specific Features

Beyond the OpenFeature spec, zio-openfeature provides:

### Transactions

Atomic flag evaluation with caching and overrides:

```scala
FeatureFlags.transaction(
  overrides = Map("feature-a" -> false),
  context = EvaluationContext("user-123")
) {
  for
    a <- FeatureFlags.boolean("feature-a", false)
    b <- FeatureFlags.int("feature-b", 0)
  yield (a, b)
}
```

Features:
- **Auto-caching**: Flags evaluated once per transaction
- **Overrides**: Test with specific flag values
- **Evaluation tracking**: Know which flags were evaluated
- **Atomic context**: Consistent context for all evaluations

### Fiber-Local Context

Scope context to a block of code:

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

### Built-in Hooks

Ready-to-use hooks:
- `FeatureHook.logging` - Log flag evaluations
- `FeatureHook.metrics` - Track evaluation timing
- `FeatureHook.contextValidator` - Validate required attributes

---

## Missing Features Summary

The following OpenFeature spec features are not implemented:

1. **Domain-based provider binding** - Multiple named providers
2. **FATAL provider status** - Unrecoverable provider errors
3. **Provider hooks** - Providers registering their own hooks
4. **Evaluation options hooks** - Per-invocation hooks
5. **Hook data** - Mutable data passed between hook stages
6. **On context changed** - Provider notification of context changes
7. **Tracking** - User action/state tracking
8. **RECONCILING status** - Static context paradigm support

These features can be added in future versions based on demand.

---

## Compliance Level

**Core Features**: Fully compliant with OpenFeature SDK specification
**Advanced Features**: Partial compliance with ZIO-idiomatic alternatives

The library provides all essential OpenFeature functionality while leveraging ZIO's effect system for improved type safety, resource management, and composability.
