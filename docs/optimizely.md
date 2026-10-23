# Optimizely

`zio-openfeature-optimizely` serves flags from
[Optimizely Feature Experimentation](https://www.optimizely.com/products/feature-experimentation/). It is built
directly on the official Optimizely Java SDK, which downloads your project's datafile and makes every decision
locally, in your process.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"
```

The Optimizely SDK needs a JSON parser but does not declare one, so this module brings `jackson-databind`. If gson is
also on your classpath, the SDK prefers it; `-Doptimizely.default_parser=` picks one explicitly. If no parser can be
found, `OptimizelyProvider.make` fails with `InvalidConfiguration` and says so.

## Quick start

You need an SDK key: in [app.optimizely.com](https://app.optimizely.com), open your project's
**Settings → Environments** and copy the key for the environment. Each environment has its own key.

```scala
--8<-- "Optimizely.scala:quick-start"
```

!!! warning "Every evaluation needs a targeting key"
    Optimizely decides per user, so it needs a user id. The provider uses the context's targeting key. Without one,
    every evaluation answers `TARGETING_KEY_MISSING`: the typed tier fails with `TargetingKeyMissing`, and the total
    tier serves your default. Set it per call, or once with [`withContext` or the global context](context.md).

## What happens at startup

1. `OptimizelyProvider.make(sdkKey)` checks the key, builds the Optimizely client, and the client starts fetching the
   datafile from `https://cdn.optimizely.com/datafiles/<sdkKey>.json`.
2. When the provider is initialized, it waits up to `initWait` (30 s) for the datafile. Once it has one, the provider
   is `READY`.
3. `FeatureFlags.fromProviderAsync` does not wait for that. Until the provider is ready, evaluations fail with
   `ProviderNotReady`. If it is not ready within `initTimeout` (30 s by default), the status becomes `Fatal` and the
   provider is shut down. `Fatal` is final: install a new provider with `setProvider`, or rebuild the layer.

The key must be 6 to 128 characters of `A-Z`, `a-z`, `0-9`, `_` or `-`, with no whitespace, and not an obvious
placeholder such as `YOUR_SDK_KEY` or `changeme`. Anything else fails with `InvalidConfiguration` before the SDK is
touched.

The provider owns a polling thread and an HTTP client. Registering it with a `FeatureFlags` layer hands those over:
the layer shuts the provider down when it closes. If the provider might never reach a layer, create it with
`OptimizelyProvider.scoped` or `OptimizelyProvider.layer`, which shut it down with their scope.

## Fail fast instead

If some flags guard money or safety, serving defaults while Optimizely starts may be wrong. Initialize synchronously:
the layer build then waits for the datafile, and fails if it does not arrive within `initTimeout`.

```scala
--8<-- "Optimizely.scala:fail-fast"
```

With async initialization, keep traffic away until the provider is ready by wiring its status into your readiness
check:

```scala
--8<-- "Optimizely.scala:readiness"
```

## How flags are read

| You ask for | You get |
|---|---|
| `boolean` | whether the flag is enabled for this user |
| `string` | the flag variable named `value`; if there is none, the variation key; otherwise your default |
| `int`, `long`, `double`, `obj` | the flag variable named `value` (a JSON variable for `obj`); otherwise your default |

A long is read from an integer variable, since Optimizely has no 64-bit one. To read a variable with another name,
put its name in the context attribute `openfeature.variableKey`
(`OptimizelyFeatureProvider.VariableKeyAttribute`):

```scala
--8<-- "Optimizely.scala:variables"
```

The reason is `TargetingMatch` when the user got a variation and `Default` otherwise, and the variation key is the
variant. The resolution's metadata carries `optimizely.flagKey` and `optimizely.ruleKey`.

| Error code | When |
|---|---|
| `TargetingKeyMissing` | the context has no targeting key |
| `FlagNotFound` | the datafile has no flag with this key |
| `ProviderNotReady` | the datafile has not loaded yet, or the provider was shut down |
| `General` | the Optimizely SDK failed to decide |

Context attributes go to Optimizely as audience attributes. Strings, booleans and numbers pass as they are, and an
`Instant` becomes its ISO-8601 string. Lists and structures are left out, because no audience condition can match
them.

## Configuration

```scala
--8<-- "Optimizely.scala:config"
```

| `OptimizelyProviderConfig` field | Default | Meaning |
|---|---|---|
| `sdkKey` | required | your environment's SDK key |
| `datafileUrl` | the Optimizely CDN | where to fetch the datafile, such as a self-hosted Agent |
| `initWait` | 30 s | how long initialization waits for the first datafile |
| `pollingInterval` | the SDK's, 5 min | how often the datafile is fetched again; setting it also turns on the staleness watchdog |
| `blockingTimeout` | the SDK's, 10 s | longest the SDK blocks waiting for a config |
| `staleAfter` | 3 × `pollingInterval` | with the watchdog on, how long without a successful fetch before the provider is `STALE` |

`OptimizelyProvider.make(sdkKey, datafileUrl, initWait)` is a shortcut for the first three.

### Self-hosted Optimizely Agent

If you run [Optimizely Agent](https://docs.developers.optimizely.com/feature-experimentation/docs/agent) inside your
network, fetch the datafile from it:

```scala
--8<-- "Optimizely.scala:agent"
```

The URL is checked like the key: it must parse, use `http` or `https`, and name a host.

## Datafile updates

The SDK fetches the datafile again every `pollingInterval`. When a new revision arrives, the provider emits
`ConfigurationChanged`. Its set of changed flags is empty, because the SDK does not say which flags changed: use it as
a signal, and read the flags again.

```scala
--8<-- "Optimizely.scala:config-changed"
```

A fetch that fails leaves the last datafile in use, and nothing tells you about it unless the staleness watchdog is on.
It runs only when you set `pollingInterval`. It marks the provider `STALE` once fetches have failed for `staleAfter`,
and `READY` again when a fetch succeeds. A stale provider keeps answering from the last datafile.

## With a circuit breaker

Decisions are local, so a slow Optimizely CDN does not slow evaluations down once the datafile is loaded. The
provider's own state is what matters. `CircuitBreakerProvider` from [Extras](extras.md#circuit-breaker-provider) reads
it, and opens while the provider reports `ERROR` or `FATAL`, or, by default, `STALE`. Put a fallback behind it for the
flags that must have a sensible value during an outage:

```scala
--8<-- "Optimizely.scala:circuit-breaker"
```

The fallback hides Optimizely outages from your callers, so watch Optimizely separately: a flag that falls back looks
like any other answer.

## Choosing a setup

| | Async (quick start) | Sync (fail fast) | With a fallback |
|---|---|---|---|
| Starts while Optimizely is down | yes; evaluations fail until it is ready | no, the build fails | yes; the fallback answers |
| During an outage after startup | the last datafile keeps answering | the last datafile keeps answering | the last datafile, or the fallback while the breaker is open |
| Good for | experiments, UI changes | flags that guard money or safety | a few flags whose default would be wrong |

## What to watch

| Signal | Likely cause |
|---|---|
| status `Fatal` soon after start, or the sync build fails | wrong SDK key, or the datafile URL is unreachable |
| status `Stale` (watchdog on) | datafile fetches have been failing for `staleAfter` |
| resolutions with `TargetingKeyMissing` | a code path evaluates without a user id |
| resolutions with `FlagNotFound` | the flag key is misspelled, or the flag is missing from this environment |

## Bring your own client

If you already build and manage an `Optimizely` client, wrap it. The provider then never closes the client:

```scala
--8<-- "Optimizely.scala:own-client"
```

## Testing

Test your application's flag logic against `TestFeatureProvider`, with no Optimizely key or network; see
[Testkit](testkit.md). To check the Optimizely wiring itself, point `datafileUrl` at a local HTTP stub that serves a
datafile; [Testing real providers](testing-real-providers.md) covers fault-testing a real provider.
