# Wiring a provider

A `FeatureFlags` layer wraps one [OpenFeature](https://openfeature.dev/) provider: the component that actually holds
your flags, whether that is a vendor SDK, a config file or an HTTP service. This page shows how to build that layer,
configure it, combine providers and replace them at runtime.

## Use any OpenFeature provider

Pass the provider to `FeatureFlags.fromProvider`, and provide the layer to your program:

```scala
--8<-- "Providers.scala:any-provider"
```

Any provider written for the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java)
works. Some that are ready to use:

| Provider | Dependency |
|:--|:--|
| HOCON, environment variables, caching, circuit breaker | `"io.github.etacassiopeia" %% "zio-openfeature-extras" % "<version>"`, see [Extras](extras.md) |
| [OFREP](https://github.com/open-feature/protocol) (remote evaluation over HTTP) | `"io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"`, see [OFREP](ofrep.md) |
| [Optimizely](https://www.optimizely.com/) | `"io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"`, see [Optimizely](optimizely.md) |
| [flagd](https://flagd.dev/) | `"dev.openfeature.contrib.providers" % "flagd" % "<version>"` |
| [LaunchDarkly](https://launchdarkly.com/) | `"dev.openfeature.contrib.providers" % "launchdarkly" % "<version>"` |
| [Flagsmith](https://flagsmith.com/) | `"dev.openfeature.contrib.providers" % "flagsmith" % "<version>"` |
| [Flipt](https://flipt.io/) | `"dev.openfeature.contrib.providers" % "flipt" % "<version>"` |
| [Unleash](https://www.getunleash.io/) | `"dev.openfeature.contrib.providers" % "unleash" % "<version>"` |
| [ConfigCat](https://configcat.com/) | `"dev.openfeature.contrib.providers" % "configcat" % "<version>"` |
| [GO Feature Flag](https://gofeatureflag.org/) | `"dev.openfeature.contrib.providers" % "go-feature-flag" % "<version>"` |

The [OpenFeature ecosystem](https://openfeature.dev/ecosystem?instant_search%5BrefinementList%5D%5Btechnology%5D%5B0%5D=Java)
lists the rest, and [Maven Central](https://central.sonatype.com/search?q=g:dev.openfeature.contrib.providers) has
their current versions. For tests, use the in-memory `TestFeatureProvider` from the [testkit](testkit.md).

## Pick a factory

| You want | Use |
|:--|:--|
| Start the provider before the app starts, and fail at startup if it cannot | `FeatureFlags.fromProvider(provider)` |
| Start the app at once, and let the provider become ready in the background | `FeatureFlags.fromProviderAsync(provider)` |
| A domain, hooks, timeouts, or any other setting | `FeatureFlags.fromProvider(provider, FeatureFlagsConfig(...))` |
| Several providers asked in order | `FeatureFlags.multiProvider(providers)`, then any factory above |
| Serve a fallback while a slow provider is built | [`FeatureFlags.fromAcquireAsync`](#start-on-a-fallback-fromacquireasync) |
| One client per domain, created on demand | [`FeatureFlagRegistry.fromProvider`](#one-client-per-domain-featureflagregistry) |

```scala
--8<-- "Providers.scala:shorthands"
```

Every factory returns a layer that owns its scope, so you provide it directly with no `Scope`. When the layer is
released, the instance [shuts down](#shut-down).

## Configure it with `FeatureFlagsConfig`

`fromProvider(provider, config)` is the one factory with every setting. `FeatureFlagsConfig()` with no changes is
exactly `fromProvider(provider)`.

```scala
--8<-- "Providers.scala:config"
```

| Field | Default | Setter | What it does |
|:--|:--|:--|:--|
| `domain` | none | `withDomain(d)` | Binds the client to a named [domain](#domains). |
| `version` | none | `withVersion(v)` | Client version, reported by `clientMetadata`. Only used together with a domain. |
| `initialHooks` | none | `withHooks(list)`, `withHook(h)` | Client-level [hooks](hooks.md) installed from the start. |
| `evaluationTimeout` | 1 second | `withEvaluationTimeout(d)`, `withoutEvaluationTimeout` | Bounds each evaluation. On timeout the call fails with `ProviderError` wrapping a `TimeoutException`; the provider call keeps running in the background. Per-call override: see [Evaluating flags](evaluating.md). |
| `initTimeout` | 30 seconds | `withInitTimeout(d)` | Bounds initialization, see [Sync or async start](#sync-or-async-start). |
| `initMode` | `InitMode.Sync` | `withAsyncInit`, `withSyncInit` | [Sync or async start](#sync-or-async-start). |
| `apiOwnership` | `ApiOwnership.Auto` | `withApiOwnership(o)` | Whether shutdown also stops the OpenFeature API, see [Who shuts down the API](#who-shuts-down-the-api). |
| `contextSource` | none | `withContextSource(s)` | Ambient context read on every evaluation, see [Evaluation context](context.md). |
| `fallbackLogging` | one line per flag per minute | `withFallbackLogging(p)` | How `*OrDefault` calls log a served default: `FallbackLogging.Off`, `Always` or `Throttled(window)`. See [Evaluating flags](evaluating.md). |

## Sync or async start

Providers usually need a moment to start: open a connection, download their rules. The layer can wait for that, or
not.

**Sync** (`InitMode.Sync`, the default; `fromProvider`). Building the layer waits until the provider is `Ready` or
`Stale`. If that takes longer than `initTimeout`, the layer fails with a `TimeoutException`. If the provider ends up in
any other state, the layer fails with an `IllegalStateException`. Either way the provider is shut down, and a wrong SDK
key or an unreachable service stops the app at startup instead of serving defaults. Closing the scope after a timeout
does not wait for a stuck `initialize()`, and the scope-close `api.shutdown()` is itself capped at 15 seconds.

**Async** (`InitMode.Async`; `fromProviderAsync`). The layer is ready at once. Until the provider reports `Ready`,
evaluations fail with `ProviderNotReady`. If the provider never gets there within `initTimeout`, the status becomes
`Fatal`: the provider is shut down, an `Error` event with code `ProviderFatal` is published, and evaluations fail with
`ProviderFatal`. A provider that was ready once and later reports an error is never moved to `Fatal` this way.

Use sync when serving a default is worse than not starting. Use async to start fast, and gate traffic on
[readiness](lifecycle.md#wait-until-the-provider-is-ready) or answer with the default meanwhile. To start fast *and*
answer with real values from the first request, put a local provider next to the remote one
([multi-provider](#ask-several-providers-in-order)) or start on a [fallback](#start-on-a-fallback-fromacquireasync).

[Lifecycle and events](lifecycle.md) has the full set of statuses and what moves between them.

## Who shuts down the API

Every instance talks to an OpenFeature API object. Shutting the API down stops every provider registered on it.
`apiOwnership` says whether this instance may do that when it shuts down:

| `apiOwnership` | On shutdown |
|:--|:--|
| `ApiOwnership.Auto` (default) | `Owned` without a domain, `Shared` with one. |
| `ApiOwnership.Owned` | Shuts down the API and its providers. |
| `ApiOwnership.Shared` | Leaves the API and its provider running. Other clients on it keep working. |

The default fits the usual setups: an app with one unnamed client owns the API, while named domains usually share it.

!!! warning
    A domain client made with `fromProvider(provider, FeatureFlagsConfig().withDomain(d))` uses the process-wide
    API, which nothing else owns. With the default `Shared` ownership, its provider is never shut down. Use
    `withApiOwnership(ApiOwnership.Owned)`, or a [`FeatureFlagRegistry`](#one-client-per-domain-featureflagregistry),
    for providers that hold threads or connections.

## Ask several providers in order

`FeatureFlags.multiProvider` combines providers into one, using the Java SDK's `MultiProvider`. Pass the result to any
factory:

```scala
--8<-- "Providers.scala:multi-provider"
```

A strategy decides when the chain moves on to the next provider:

| Strategy | Moves on when a provider | Stops at |
|:--|:--|:--|
| `MultiProviderStrategy.firstMatch` (default) | reports `FLAG_NOT_FOUND` | the first answer that is not "flag not found", including an error |
| `MultiProviderStrategy.firstSuccessful` | reports any error, including `FLAG_NOT_FOUND` | the first answer without an error |

```scala
--8<-- "Providers.scala:multi-provider-strategy"
```

Things to know:

- A provider must answer `FLAG_NOT_FOUND` for a key it does not hold. An answer with reason `DEFAULT` and no error
  code counts as a match and ends the chain.
- With `firstMatch`, when no provider has the flag, you get `FLAG_NOT_FOUND` and the caller's default. With
  `firstSuccessful`, you get a `GENERAL` error carrying every provider's error.
- Every provider stays in the chain, in order, even if two report the same name.
- For your own strategy, implement `MultiProviderStrategy.Strategy` (the Java SDK's `Strategy` interface).

## Start on a fallback: `fromAcquireAsync`

Some providers do their network work in the constructor, so even `fromProviderAsync` blocks until they are built.
`fromAcquireAsync` takes the provider as an effect instead. The layer starts at once on a fallback provider, and a
background fiber builds the real one, checks it, and swaps it in:

![fromAcquireAsync: from the first moment evaluations are answered by the fallback provider while a background fiber acquires the real provider, retries after a failed attempt, verifies the candidate knows a sentinel flag, and swaps it in; AcquireStatus moves from Constructing to Live, and later evaluations get real values.](assets/acquire.svg)

```scala
--8<-- "Providers.scala:acquire"
```

The layer's error type is `Nothing`: a real provider that never comes up cannot fail your app. It is retried, reported
to `onConstructionError`, and the fallback keeps serving.

| Parameter | Default | Meaning |
|:--|:--|:--|
| `acquire` | | Builds the real provider. Register cleanup in its `Scope` (for example with `ZIO.acquireRelease`) to have a rejected or unused provider closed. |
| `fallback` | | Builds a fallback. It runs again for the swap, because the swap shuts the first fallback down, so it must return a fresh instance each time. |
| `verify` | accept | Checks the real provider before the swap. A failure counts as a failed attempt. |
| `compose` | `FeatureFlags.defaultAcquireCompose` | Combines real and fallback into the provider that is swapped in. The default asks the real provider first and falls back on any error (`firstSuccessful`). |
| `constructionRetry` | `Schedule.recurs(3) && Schedule.exponential(1.second).jittered` | Retries `acquire` plus `verify`. |
| `constructionTimeout` | 30 seconds | Bounds one attempt. |
| `onSwapped` | nothing | Runs once the real provider serves. |
| `onConstructionError` | nothing | Runs once all attempts have failed, or the swap failed. |
| `evaluationTimeout`, `initTimeout`, `contextSource`, `fallbackLogging` | as in `FeatureFlagsConfig` | The same settings as [above](#configure-it-with-featureflagsconfig). |

**Verify the candidate.** A provider can be built with a wrong key or an empty configuration and still answer, with
wrong values. `Verify.flagExists[A](key)` asks the candidate for a flag you know exists, and rejects it if the answer
carries any error code (`Verify.VerificationFailed(key, errorCode, message)`). `Verify.all(check1, check2)` runs several
checks in order; any `FeatureProvider => Task[Unit]` is a check. The candidate is checked as `acquire` returned it,
before the SDK calls its `initialize()` and without global or ambient context. A provider that only answers after
`initialize()` must be initialized inside `acquire`.

**Which provider is serving?** The status is `Ready` from the start, so it cannot tell you. The layer also provides
`AcquireStatus`, whose state is `Constructing`, then either `Live` or `Failed(cause)`:

```scala
--8<-- "Providers.scala:acquire-status"
```

`AcquireStatus` reports how construction ended, not ongoing health: `Live` stays `Live` if the real provider degrades
later, and the default `compose` then quietly answers from the fallback. For ongoing health, wrap the real provider in
a [`CircuitBreakerProvider`](extras.md). A failed swap is reported as `ProviderSwapFailed(error)`.

!!! note
    The swap itself takes a moment, and evaluations that start during it fail with `ProviderNotReady`, like any
    [`setProvider`](#replace-the-provider-at-runtime). Use the `*OrDefault` calls if a default is acceptable there.

If you want plain async start without a fallback for a provider whose constructor blocks, wrap it in a
[`DeferredProvider`](extras.md) and pass it to `fromProviderAsync`.

## Domains

A domain is a name for a client. Providers can be registered per domain, so different parts of an app can use
different providers. `clientMetadata` tells you which client you have:

```scala
--8<-- "Providers.scala:client-metadata"
```

`providerMetadata` returns the name of the provider currently serving.

### One client per domain: `FeatureFlagRegistry`

`FeatureFlagRegistry` builds clients on demand, one per domain, all on a private OpenFeature API that it owns:

```scala
--8<-- "Providers.scala:registry"
```

- `getClient(domain)` builds the client the first time and returns the same instance afterwards. Callers asking for the
  same domain at once share one build, and a slow domain does not hold up the others.
- A domain without its own provider uses the default provider. `defaultClient` is the client without a domain.
- `setProvider(domain, provider)` registers a provider for the domain. If its client already exists, the provider is
  swapped in.
- A provider that does not become ready within 30 seconds, or fails, gives `ProviderInitializationFailed`. The next
  `getClient` tries again.
- `addZioApiHook(hook)` adds an [API-level hook](hooks.md) to every client, current and future.
- When the registry's scope closes, it shuts down its API and every provider on it.

## Replace the provider at runtime

`setProvider` swaps the provider of a running instance. Hooks, context, event handlers and tracked events stay.

```scala
--8<-- "Providers.scala:hot-swap"
```

What happens:

1. The status becomes `NotReady`. Evaluations that start now fail with `ProviderNotReady`; ones already running
   finish on the old provider. Swaps are serialized.
2. The SDK initializes the new provider. `setProvider` waits for this for at most `initTimeout`, the layer's init
   timeout. Once the new provider is ready, the SDK shuts down the old one.
3. On success, the status becomes `Ready` and the new provider serves.
4. If the new provider's `initialize()` throws, `setProvider` fails with `ProviderInitializationFailed`, the previous
   provider is registered again, and the status returns to `Ready`. If even that fails, the status stays `Error`.
5. If `initialize()` has not finished within `initTimeout`, `setProvider` fails with `ProviderInitializationFailed`
   wrapping a `TimeoutException`, and the status becomes `Error`. This swap is not rolled back: the new provider stays
   registered, and the status becomes `Ready` on its own if it finishes later. The previous provider is not restored,
   because the SDK would shut it down as soon as the new one became ready. `fromAcquireAsync` handles this by
   swapping in a fresh fallback.

In every failure case, `setProvider` again with a working provider recovers.

`setProvider` is the only way out of `Fatal`. When you use a wrapper such as `CircuitBreakerProvider`, swap in a new
wrapper around the new provider, not just the inner provider.

## Shut down

Closing the layer's scope shuts the instance down. You can also call `FeatureFlags.shutdown`. See
[Lifecycle and events](lifecycle.md#shut-down) for what it releases. Whether the provider stops too depends on
[API ownership](#who-shuts-down-the-api).

## Older factories

These still compile but are deprecated. Each is a shorthand for a `FeatureFlagsConfig`:

| Deprecated | Write instead |
|:--|:--|
| `fromProvider(p, evaluationTimeout)` | `fromProvider(p, FeatureFlagsConfig().withEvaluationTimeout(t))` |
| `fromProvider(p, evaluationTimeout, initTimeout)` | `fromProvider(p, FeatureFlagsConfig().withEvaluationTimeout(t).withInitTimeout(i))` |
| `fromProviderWithDomain(p, domain[, version])` | `fromProvider(p, FeatureFlagsConfig().withDomain(d).withVersion(v))` |
| `fromProviderWithHooks(p, hooks)` | `fromProvider(p, FeatureFlagsConfig().withHooks(hooks))` |
| `fromMultiProvider(ps[, strategy])` | `fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig())` |
| `fromProviderAsync(p, evaluationTimeout[, initTimeout])` | `fromProvider(p, FeatureFlagsConfig().withAsyncInit.withEvaluationTimeout(t))` |
| `fromProviderWithDomainAsync`, `fromProviderWithHooksAsync`, `fromMultiProviderAsync` | the same as their sync form, plus `.withAsyncInit` |
