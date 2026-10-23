# ZIO OpenFeature

ZIO OpenFeature gives Scala applications type-safe feature flags as ZIO effects. It is built on the
[OpenFeature](https://openfeature.dev) Java SDK, so it works with any OpenFeature provider: LaunchDarkly, flagd,
Flagsmith, Flipt, OFREP, Optimizely, or one of the providers that ship with it.

![One flag evaluation through the pipeline. The context is merged and the before hooks run in order. A transaction override answers at the transaction stage without calling the provider; otherwise the status gate is checked and the provider is called with a timeout. The answer returns through the after hooks, or the error hooks when it carries an error code, then finallyAfter. For a flag the provider does not know, the typed tier fails with FlagNotFound and the total tier serves the default.](assets/evaluation.svg)

## At a glance

```scala
--8<-- "Home.scala:at-a-glance"
```

## What you get

Two ways to read a flag
:   The **typed tier** (`boolean`, `value`, …) fails with a `FeatureFlagError` when a flag cannot be read, so a
    fail-closed gate stays closed. The **total tier** (`booleanOrDefault`, …) never fails and serves your default,
    with the reason on the result. [Read more](evaluating.md).

Flags as typed values
:   Declare a flag once with `FlagDef`, and read enums, case classes and newtypes, not just strings and numbers.
    [Read more](typed-flags.md).

Targeting context in layers
:   Global, client, per-request and per-call attributes merge in a fixed order, and ambient identity (MDC, tracing
    tags) can be pulled in on every evaluation. [Read more](context.md).

Hooks, transactions and events
:   ZIO-native hooks for logging, metrics and validation; scoped overrides that record every flag a block of code
    read; provider status and change events as a `ZStream`. [Hooks](hooks.md), [transactions](transactions.md),
    [lifecycle and events](lifecycle.md).

Production wiring
:   Sync or async start-up with timeouts, hot provider swaps, a fallback provider while the real one is built, and
    resilience wrappers (caching, circuit breaker). [Read more](providers.md).

A testkit
:   An in-memory provider to test flag-driven code without a network. [Read more](testkit.md).

## Modules

| Module | What it holds |
|---|---|
| `zio-openfeature-core` | The `FeatureFlags` service, context, hooks, transactions, events |
| `zio-openfeature-extras` | Providers for HOCON config and environment variables; caching, circuit-breaker and deferred wrappers |
| `zio-openfeature-ofrep` | A provider for the OpenFeature Remote Evaluation Protocol (OFREP) |
| `zio-openfeature-optimizely` | A provider for Optimizely Feature Experimentation |
| `zio-openfeature-testkit` | `TestFeatureProvider` and test helpers |

Scala 3.3+ and 2.13, ZIO 2.1, JDK 11+ (CI tests 17, 21 and 25). It implements the server-side (dynamic context) paradigm of the
OpenFeature specification v0.9.0; see [spec compliance](spec-compliance.md).

[Get started](getting-started.md){ .md-button .md-button--primary }
[How it works](architecture.md){ .md-button }
