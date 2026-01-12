---
layout: default
title: OpenFeature Spec Compliance
nav_order: 9
---

# OpenFeature Specification Compliance

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java), inheriting its specification compliance while adding ZIO-specific features.

---

## Compliance Summary

The library is fully compliant with core OpenFeature functionality:

**Implemented:**
- Flag evaluation API (boolean, string, int, double, object)
- Detailed evaluation with resolution metadata
- Evaluation context with targeting key and attributes
- Context hierarchy (global, scoped, invocation)
- Provider lifecycle (init, shutdown)
- Provider status and metadata
- Hook lifecycle (before, after, error, finally)
- Provider events via ZStream

**Not Implemented:**
- Per-invocation hooks (use `addHook` instead)
- Provider-level hooks
- FATAL provider status
- Context change reconciliation
- Tracking support

---

## Flag Evaluation

| Feature | Status | Notes |
|:--------|:-------|:------|
| Typed evaluation methods | Yes | `boolean`, `string`, `int`, `long`, `double`, `obj` |
| Generic evaluation | Yes | `value[A]` with `FlagType` type class |
| Detailed evaluation | Yes | `booleanDetails`, etc. return `FlagResolution` |
| Resolution fields | Yes | value, variant, reason, errorCode, metadata |
| No exceptions | Yes | Returns ZIO effects with typed errors |

---

## Evaluation Context

| Feature | Status | Notes |
|:--------|:-------|:------|
| Targeting key | Yes | `EvaluationContext.targetingKey` |
| Custom attributes | Yes | `EvaluationContext.attributes` |
| Context merging | Yes | Global → Scoped → Transaction → Invocation |

---

## Hooks

| Feature | Status | Notes |
|:--------|:-------|:------|
| Hook stages | Yes | before, after, error, finallyAfter |
| Hook context | Yes | flagKey, flagType, defaultValue, context, metadata |
| Hook hints | Yes | `HookHints` for passing data between stages |
| API-level hooks | Yes | `FeatureFlags.addHook` |
| Per-invocation hooks | No | Use `addHook` for all hooks |
| Provider hooks | No | Not exposed |

---

## Provider Lifecycle

| Feature | Status | Notes |
|:--------|:-------|:------|
| Initialize | Yes | `setProviderAndWait` on layer creation |
| Shutdown | Yes | Automatic via ZIO Scope finalizer |
| Provider status | Partial | Ready, NotReady, Error, Stale (no FATAL) |
| Domain binding | Yes | `fromProviderWithDomain` |

---

## Events

| Feature | Status | Notes |
|:--------|:-------|:------|
| Event types | Yes | Ready, Error, Stale, ConfigurationChanged |
| Event streaming | Yes | `FeatureFlags.events` returns ZStream |
| Handler isolation | Yes | ZIO effect system handles errors |

---

## ZIO-Specific Features

Beyond the OpenFeature spec:

| Feature | Description |
|:--------|:------------|
| Transactions | Scoped overrides with evaluation caching and tracking |
| Fiber-local context | `withContext` scopes context to a code block |
| Type-safe evaluation | `FlagType` type class for compile-time safety |
| Effect-based hooks | Hooks return `UIO` instead of callbacks |
| Resource management | Automatic lifecycle via ZIO Scope |

---

## Comparison with Java SDK

| Aspect | Java SDK | ZIO OpenFeature |
|:-------|:---------|:----------------|
| Provider management | Global singleton | ZIO Layer |
| Error handling | Exceptions + defaults | Typed effects |
| Context scoping | Thread-local | Fiber-local |
| Transactions | Not available | Built-in |
| Hooks | Callbacks | Effects (UIO) |
| Events | Callbacks | ZStream |
