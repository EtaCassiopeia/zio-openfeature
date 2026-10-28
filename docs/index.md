# ZIO OpenFeature

ZIO OpenFeature gives Scala applications type-safe feature flags as ZIO effects. It is built on the
[OpenFeature](https://openfeature.dev) Java SDK, so it works with any OpenFeature provider: LaunchDarkly, flagd,
Flagsmith, Flipt, OFREP, Optimizely, or one of the providers that ship with it.

![One flag evaluation through the pipeline: context merge, before hooks, transaction override or status gate and provider call, then after or error hooks and finallyAfter, for a plain read, a transaction override and a missing flag.](assets/evaluation.svg)

## At a glance

```scala
--8<-- "Home.scala:at-a-glance"
```

## What you get

- **Two tiers.** `boolean`, `value`, … fail with a typed error; `booleanOrDefault`, … never fail. [Evaluating flags](evaluating.md)
- **Typed flags.** One `FlagDef` per flag, with enums and case classes as values. [Typed flags](typed-flags.md)
- **Layered context.** Global, client, scoped and per-call attributes, plus ambient identity. [Evaluation context](context.md)
- **Hooks, transactions, events.** [Hooks](hooks.md), [transactions](transactions.md), [lifecycle and events](lifecycle.md)
- **Production wiring.** Async start-up, hot swaps, fallbacks, caching, circuit breaker. [Providers](providers.md)
- **Testkit.** An in-memory provider for flag-driven code. [Testkit](testkit.md)

## Modules

| Module | What it holds |
|---|---|
| `zio-openfeature-core` | The `FeatureFlags` service, context, hooks, transactions, events |
| `zio-openfeature-extras` | Providers for HOCON config and environment variables; caching, circuit-breaker and deferred wrappers |
| `zio-openfeature-ofrep` | A provider for the OpenFeature Remote Evaluation Protocol (OFREP) |
| `zio-openfeature-optimizely` | A provider for Optimizely Feature Experimentation |
| `zio-openfeature-testkit` | `TestFeatureProvider` and test helpers |

The library implements the server-side (dynamic context) paradigm of the OpenFeature specification; see
[spec compliance](spec-compliance.md) for the supported versions.

[Get started](getting-started.md){ .md-button .md-button--primary }
[How it works](architecture.md){ .md-button }
