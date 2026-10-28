# Spec compliance

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java) and
implements the specification's **dynamic-context paradigm**, the one for servers: each evaluation can carry its own
context. The static-context paradigm (client side) and its context reconciliation are not implemented.

| ZIO OpenFeature | [OpenFeature specification](https://github.com/open-feature/spec) | [OpenFeature Java SDK](https://github.com/open-feature/java-sdk) |
|:----------------|:-----------------------------------------------------------------|:-----------------------------------------------------------------|
| 1.2.0 (latest published) | [v0.9.0](https://github.com/open-feature/spec/releases/tag/v0.9.0) | 1.23.0 |
| 1.1.x | v0.9.0 | 1.22.1 |
| 1.0.0 | [v0.8.0](https://github.com/open-feature/spec/releases/tag/v0.8.0) | 1.21.0 |

## How compliance is tested

The specification ships its own test scenarios as Gherkin feature files. This repository vendors them from the v0.9.0 tag
and runs them on every build, twice: the `conformance` module runs them with Cucumber, and `conformance-zio-bdd` with
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd).

- `hooks.feature` comes from the specification's `main` branch (the scenario for requirement 4.4.8 is not in a release
  yet), and `evaluation.feature` is deliberately not vendored. A weekly job opens an issue when the copies drift.
- Three tags are skipped: `@deprecated` (superseded scenarios), `@async` (every evaluation is already a non-blocking
  effect, so there is no separate async API to test) and `@immutability` (`EvaluationContext` is an immutable value,
  but these scenarios have no step definitions yet).
- `conformance-zio-bdd` also has feature files for this library's own additions (typed flags, transactions, context
  sources, served-default logging and more), an Optimizely flag matrix, and an intercept suite.

## Requirement to API

| Requirement | How |
|:------------|:----|
| **Flag evaluation (§1)** | |
| Typed evaluation: boolean, string, number, object | `boolean`, `string`, `int`, `long`, `double`, `obj`, and `value[A]` for any `FlagType` |
| Detailed evaluation | `booleanDetails` ... `objDetails`, `valueDetails`, returning `FlagResolution` |
| Optional context and options arguments | every method takes a context and `EvaluationOptions` (hooks, hook hints, timeout) |
| Resolution details: value, variant, reason, error code, error message, flag metadata | `FlagResolution` fields; metadata values may be boolean, string, int, long, float or double |
| Never throw; return the default (§1.4.10) | the total tier (`*OrDefault`, `resolveOrDefault`) never fails and returns the default, with the error code on the resolution. The typed tier (`boolean`, `value`, `*Details`) fails with a typed `FeatureFlagError` instead, a ZIO-native addition. See [Evaluating flags](evaluating.md). |
| No logging by the client (§1.4.11, SHOULD NOT) | **Deviates by default**: when the total tier serves a default, it logs one warning per flag key per minute (`FallbackLogging.Default`). Set `FallbackLogging.Off` to conform. A default served because of a defect is still logged, since it means a bug. |
| **Providers (§1.6, §1.7, §2)** | |
| Set a provider and wait for it (blocking init) | `FeatureFlags.fromProvider` |
| Set a provider without waiting (async init) | `fromProviderAsync`, or `FeatureFlagsConfig().withAsyncInit` |
| Shut down (§1.6.1) | when the layer's `Scope` closes, or `FeatureFlags.shutdown` |
| Domains | `FeatureFlagsConfig().withDomain(d)` (and `.withVersion(v)` for the client version); `FeatureFlagRegistry` for many domains |
| Provider and client metadata | `providerMetadata`, `clientMetadata` |
| Provider status (§1.7) | `providerStatus`: `NotReady`, `Ready`, `Stale`, `Error`, `Fatal`, plus `ShuttingDown` |
| Several providers | `FeatureFlags.multiProvider` (the SDK's `MultiProvider`) |
| **Evaluation context (§3)** | |
| Targeting key and attributes | `EvaluationContext(targetingKey)`, `.withAttribute(...)` |
| Global (API), client and invocation context | `setGlobalContext` / `globalContext`; `setClientContext` / `clientContext`; the `ctx` argument of an evaluation |
| Transaction context propagation (§3.3) | `withContext(ctx)(effect)`, scoped to the fiber |
| Merging, later wins | global, transaction, client, `ContextSource`, `withContext`, invocation |
| **Hooks (§4)** | |
| Stages: before, after, error, finally | `FeatureHook`: `before`, `after`, `error`, `finallyAfter` |
| Hook context and hints | `HookContext` (flag key, flag type, default value, evaluation context, client and provider metadata); `HookHints`, passed through `EvaluationOptions` |
| Hook data (§4.6) | `HookData`, per hook and per evaluation, shared across its stages |
| API, client, invocation and provider hooks | `addZioApiHook`; `addHook`; `EvaluationOptions(hooks = ...)`; the provider's own `getProviderHooks`. Java SDK hooks can be added with `addApiHook`. |
| Order | before: API, client, invocation, then, inside the SDK call, Java SDK API hooks, provider hooks; after, error and finally run in reverse |
| A failing `before` or `after` runs `error` (§4.4.5-4.4.6) | yes; the rest of that stage is skipped |
| A failing `after` yields the default (§4.4.8) | the total tier returns the default with reason `Error` and code `General`, and `finallyAfter` sees that; the typed tier fails |
| **Events (§5)** | |
| Ready, error, stale, configuration-changed events | `ProviderEvent.Ready`, `Error`, `Stale`, `ConfigurationChanged`, each with the provider's event metadata |
| Add and remove handlers | `onProviderReady`, `onProviderError`, `onProviderStale`, `onConfigurationChanged`, or `on(eventType, handler)`; each returns an effect that removes the handler |
| Run a handler at once if the provider is already in that state (§5.3.3) | yes |
| All events as a stream | `FeatureFlags.events`, a `ZStream` |
| **Tracking (§6)** | |
| Track an event, optionally with context and details | `track(name)`, `track(name, ctx)`, `track(name, details)`, `track(name, ctx, details)`; details are `TrackingEventDetails(value, attributes)`. See [Tracking events](tracking.md). |

`ProviderEvent.Reconnecting` has no Java SDK equivalent (it belongs to the client-side paradigm), so `FeatureFlags`
never emits it. `ContextSource` and the transaction level are additions: see [Evaluation context](context.md).

??? note "Fail-fast while the provider is not ready"
    While the provider is `NotReady` or `ShuttingDown`, evaluations fail with `ProviderNotReady`; while it is `Fatal`,
    with `ProviderFatal`. Spec v0.9.0 no longer requires this short-circuit but allows it, and the library keeps it so
    its error contract does not change. Transaction overrides and cached transaction values are still served. See
    [Lifecycle and events](lifecycle.md).

## Providers built for older SDKs

A provider built against an older Java SDK usually works. One that implements `FeatureProvider` directly works, because
every method added since has a default implementation. One that extends `EventProvider` (most ecosystem providers, such
as flagd and LaunchDarkly) can fail with `NoSuchMethodError` when it emits an event, because a later SDK release changed
the return type of the `emit*` methods. Check which `dev.openfeature:sdk` version the provider depends on.
