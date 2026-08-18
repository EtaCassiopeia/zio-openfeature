---
layout: default
title: Extras
nav_order: 7
---

# Extras Module
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The `zio-openfeature-extras` module provides built-in providers for common use cases — reading flags from local config, environment variables, wrapping any provider with evaluation caching, and adding circuit breaker logic for fast failover.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-extras" % "<version>"
```

OFREP (HTTP-based remote evaluation) lives in its own module so its HTTP-client transitive deps (Jackson, Guava, etc.) don't get pulled in for users who only need HOCON/env-var providers. See the [OFREP Provider](#ofrep-provider) section below for the dependency snippet.

---

## HOCON Provider

Reads flag values from Typesafe Config (`application.conf` / `reference.conf`).

### Configuration

```hocon
# application.conf
feature-flags {
  new-checkout = true
  max-items = 50
  rate-limit = 2.5
  welcome-message = "Hello!"
  settings {
    timeout = 30
    retries = 3
  }
}
```

### Usage

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*

// Read from "feature-flags" path (default)
val layer = FeatureFlags.fromProvider(HoconProvider())

// Read from a custom path
val layer = FeatureFlags.fromProvider(HoconProvider("my-flags"))

// From a specific Config object
val layer = FeatureFlags.fromProvider(HoconProvider.fromConfig(myConfig))
```

### Supported types

- **Boolean**: `flag = true`
- **String**: `flag = "value"`
- **Integer**: `flag = 42`
- **Double**: `flag = 3.14`
- **Object**: Nested config objects are converted to SDK `Structure` values

A key that is present returns `STATIC` as the resolution reason, since the value came from config.

### Absent keys and provider chains

A key the config does **not** contain resolves as `FLAG_NOT_FOUND`, carrying your default as the value. No
evaluation fails and `*OrDefault` still gives you the default, but two things are observable: the resolution carries
`reason = Error` with `errorCode = FlagNotFound`, and hooks see the **`error`** stage rather than `after` — so
`Hook.logging()` reports an absent key at error level where it previously reported it at info. That is the
spec-correct stage for an error-coded resolution, though if you use a provider as an opt-in source where most keys
are deliberately absent, tune `logError`/`errorLevel` on your hooks.

Reporting not-found is what makes a chain work:

```scala
// The HOCON file has no "checkout.v2" key, so the chain moves on to the next provider.
FeatureFlags.multiProvider(List(HoconProvider(), EnvVarProvider()))
```

`MultiProviderStrategy.firstMatch` advances to the next provider only when a provider reports `FLAG_NOT_FOUND`. A
result carrying `reason = DEFAULT` is treated as an answer and ends the chain — which is why "no such key" has to
be reported as not-found rather than as a default. It also means an operator can tell "configured to this value"
from "not configured here", which a `DEFAULT` answer hides.

> **Each provider in a chain needs a distinct metadata name.** The SDK keys providers by `getMetadata.getName`, so
> two `HoconProvider`s over different configs collapse into one — and the survivor is the **last** one, not the
> first, whatever the strategy is called. The SDK logs `duplicated provider name` at INFO, so it is easy to miss
> unless SDK info logging is on. Chain different provider *types*, or wrap one in a provider reporting another name.

### Manual reload

```scala
// Re-read config without restarting. `reload()` refreshes the source the provider was built from:
// the classpath path for `HoconProvider(path)`, or the injected config for `HoconProvider.fromConfig(...)`.
val provider = HoconProvider("custom-path")
provider.reload()
```

---

## Environment Variable Provider

Reads flag values from environment variables with a configurable prefix and naming convention.

### Key mapping

Flag keys are mapped to env var names by combining a **prefix** with a **key transform**:

```
env var name = prefix + keyTransform(flagKey)
```

The default transform uppercases the key and replaces `-` and `.` with `_`:

| Flag key | Env var (default) |
|:---------|:------------------|
| `new-checkout` | `FF_NEW_CHECKOUT` |
| `max-items` | `FF_MAX_ITEMS` |
| `app.feature.enabled` | `FF_APP_FEATURE_ENABLED` |

```bash
export FF_NEW_CHECKOUT=true
export FF_MAX_ITEMS=50
export FF_RATE_LIMIT=2.5
export FF_WELCOME_MSG="Hello!"
```

### Usage

```scala
import zio.openfeature.*
import zio.openfeature.extras.*

// Default prefix: FF_
val layer = FeatureFlags.fromProvider(EnvVarProvider())

// Custom prefix
val layer = FeatureFlags.fromProvider(EnvVarProvider(prefix = "FEATURE_"))

// Custom key transform
val layer = FeatureFlags.fromProvider(
  EnvVarProvider(keyTransform = _.toUpperCase.replace(".", "__"))
)
```

### Type coercion

- **Boolean**: `true/false`, `yes/no`, `1/0`, `on/off`
- **Integer**: Parsed via `toInt`
- **Double**: Parsed via `toDouble`
- **String**: Raw env var value

A variable that is **set but unparseable** surfaces as `errorCode = ParseError` on the resolution (and through the
`error` hook stage) rather than quietly behaving like the default, so a typo in a deployed value is visible. The
evaluation itself does not fail — you still get your default as the value.

A variable that is **not set** resolves as `FLAG_NOT_FOUND`, carrying your default as the value — the same
behaviour, and for the same chaining reason, as
[an absent HOCON key](#absent-keys-and-provider-chains).

### Testing

Use `withLookup` to provide a custom env var source:

```scala
val testEnv = Map("FF_MY_FLAG" -> "true")
val provider = EnvVarProvider.withLookup(testEnv.get)
```

---

## Deferred Provider

`DeferredProvider` adapts a **constructor-blocking** provider — one that does all its network work in its Java constructor — into an `initialize()`-blocking one. It defers construction to `initialize(ctx)`, which the OpenFeature SDK runs on its own init executor, so any `InitMode.Async` config already keeps that work off the caller's thread.

```scala
import zio.openfeature.extras.DeferredProvider

// `construct` runs on the SDK init executor, not the layer-build thread
val deferred = DeferredProvider("optimizely")(() => new CofOptimizelyLocalProvider(options))

val layer = FeatureFlags.fromProviderAsync(deferred)
```

Behaviour:

- **Stable metadata** — `getMetadata` returns the given name before and after construction, so the event bridge and `MultiProvider` keying see one identity.
- **No NPE before ready** — evaluations before construction completes return a typed `ProviderEvaluation` with `ErrorCode.PROVIDER_NOT_READY`.
- **Clean shutdown race** — `shutdown()` racing an in-flight `initialize()` shuts the delegate down once construction finishes, instead of leaking its poller/HTTP client.
- **Hooks forwarded** — `getProviderHooks` delegates once active.

Use `DeferredProvider` when you want plain async semantics (typed `PROVIDER_NOT_READY` until ready). When a fallback must *answer* during the init window, use [`FeatureFlags.fromAcquireAsync`](providers.md#fallback-first-initialization-fromacquireasync) instead — inside a `MultiProvider`, `DeferredProvider` still gates overall readiness, since the SDK awaits all children.

---

## OFREP Provider

Evaluates flags via the [OpenFeature Remote Evaluation Protocol](https://github.com/open-feature/protocol) (OFREP) — the standard HTTP protocol for vendor-neutral remote flag evaluation. Use this when your flags are served by an OFREP-compatible backend (flagd, OFREP relays, or any compliant server) and you want a vendor-agnostic client.

`OFREPProvider` is a small Scala-friendly factory over the OpenFeature Java SDK's `dev.openfeature.contrib.providers.ofrep.OfrepProvider`. The Java provider handles HTTP requests, polling, caching, and state transitions; the Scala factory just sugars the construction.

> **Note:** The underlying contrib provider is at version `0.0.1` — the API may evolve as OFREP itself matures. Pin the dependency deliberately.

### Dependency

OFREP lives in its own module so that callers who only want HOCON / env-var providers don't pull in the HTTP-client transitive stack (Jackson, Guava, Commons Validator, SLF4J).

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"
```

### Usage

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.ofrep.OFREPProvider

val program = ZIO.scoped {
  for
    provider <- OFREPProvider.make("https://flags.example.com")
                  .mapError(e => new RuntimeException(e.message))
    env      <- FeatureFlags.fromProviderAsync(provider).build
    ff        = env.get[FeatureFlags]
    enabled  <- ff.boolean("new-checkout", default = false)
                  .mapError(e => new RuntimeException(e.message))
  yield enabled
}
```

`OFREPProvider.make(baseUrl)` parses and validates the URL before constructing — bad input fails layer build with `FeatureFlagError.InvalidConfiguration` rather than surfacing as an opaque `ProviderError(MalformedURLException)` on the first evaluation. The ZLayer convenience `OFREPProvider.layer(baseUrl)` does the same and exposes the result as `ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider]`.

For full configuration (auth headers, timeouts, custom executor), use `make(options)`:

```scala
import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions
import scala.jdk.CollectionConverters._
import java.time.Duration as JDuration

val options = OfrepProviderOptions.builder()
  .baseUrl("https://flags.example.com")
  .requestTimeout(JDuration.ofSeconds(5))
  .connectTimeout(JDuration.ofSeconds(2))
  .headers(Map("Authorization" -> "Bearer my-token").asJava)
  .build()

val provider = OFREPProvider.make(options)
```

The legacy throwing factories — `OFREPProvider()`, `OFREPProvider(baseUrl)`, `OFREPProvider.fromOptions(options)` — remain available but are deprecated. They accept any string and surface configuration mistakes only at the first evaluation; prefer `make` / `layer` for validated construction.

### Configuration options

The full set of options is exposed by the Java SDK's `OfrepProviderOptions` builder:

| Option | Default | Description |
|:-------|:--------|:------------|
| `baseUrl` | `http://localhost:8016` | OFREP server endpoint |
| `requestTimeout` | `10s` | Per-request HTTP timeout |
| `connectTimeout` | `10s` | TCP connect timeout |
| `headers` | empty | Static headers applied to every request (e.g., bearer token) |
| `proxySelector` | system default | Custom `java.net.ProxySelector` |
| `executor` | fixed pool of 5 | Executor for HTTP work |

### Async initialization

Like any other provider, the OFREP provider works with `fromProviderAsync` for non-blocking startup:

```scala
val layer = FeatureFlags.fromProviderAsync(OFREPProvider("https://flags.example.com"))
```

Evaluations fail with `ProviderNotReady` until the provider has fetched its initial flag set.

### Failure surfacing

How a downstream failure shows up depends on where it originates:

- **HTTP `4xx` / `5xx` from the OFREP endpoint**: the contrib provider catches the response and returns a successful `FlagResolution` with `errorCode` populated (typically `General`) and `errorMessage` set. The ZIO `FeatureFlags` layer hands this back to callers as a *successful* effect — operators are expected to alert on `resolution.errorCode.isDefined`.
- **Network-level failures** (DNS, `ConnectException`, connection reset): the contrib provider's HTTP client throws synchronously, the throw escapes `attemptBlocking`, and `FeatureFlagError.classify` maps it to a typed error — `Unreachable` for the known network exception types, `ProviderError` as the fallback. These arrive in the effect's error channel.
- **Evaluation timeout** (via `FeatureFlagsConfig().withEvaluationTimeout(d)`): surfaces as `ProviderError` wrapping a `TimeoutException`.

The `OFREPFailureModeSpec` in this module pins these behaviours so a contrib-provider upgrade doesn't silently shift them. If you build alerting on top of the OFREP integration, alert on both branches: `errorCode` on resolutions AND `Unreachable`/`ProviderError`/timeout in the error channel.

### Transitive dependencies

Adding `zio-openfeature-ofrep` pulls in Jackson (core/databind/jsr310), Guava, Commons Validator, and SLF4J via the contrib provider. This is intentionally isolated from the `extras` module so projects without OFREP keep their dependency footprint small.

---

## Circuit Breaker Provider

A decorator that wraps any provider with circuit breaker logic for fast failover. When the delegate provider fails repeatedly or becomes unhealthy, the circuit opens and evaluations fail immediately (< 1ms) — enabling instant fallback when composed with `MultiProvider` and `MultiProviderStrategy.firstSuccessful`.

### When to use

Use this when your primary provider is an external service (e.g., Optimizely, LaunchDarkly) and you need guaranteed fast failover to a local fallback (e.g., `EnvVarProvider`) if the service is slow or unavailable.

### State machine

The circuit breaker has three states:

| State | Behavior |
|:------|:---------|
| **Closed** | Normal operation. Evaluations forwarded to the delegate. Consecutive failures tracked. |
| **Open** | Evaluations fail immediately without calling the delegate (< 1ms). After `resetTimeout`, transitions to Half-Open. |
| **Half-Open** | A single probe evaluation is allowed through. On success → Closed. On failure → Open. |

### Three tripping mechanisms

1. **Failure-count**: After `failureThreshold` consecutive evaluation failures (including timeouts), the circuit opens.
2. **State-driven**: The delegate's state is polled at most once per `stateCheckInterval` (default `1.second`) rather than on every call, keeping the hot path cheap. If the observed state is `ERROR` or `FATAL`, the circuit opens immediately — no failed evaluations needed. When the delegate recovers to `READY`, the circuit closes automatically. Set `stateCheckInterval` to `Duration.Zero` to poll the delegate state on every evaluation.
3. **Event-driven**: The wrapper takes ownership of the delegate's event channel, so delegate events trip the breaker as soon as they are emitted — a `PROVIDER_ERROR` opens the circuit, a `PROVIDER_READY` resets an externally-opened circuit, and a `PROVIDER_STALE` applies the configured `stalePolicy`. This detects an unhealthy delegate without waiting for the next state poll or a failed evaluation. This is the one mechanism that needs an `EventProvider` delegate — see below.

### Wrapping a plain `FeatureProvider`

The delegate is a `FeatureProvider`, so a provider that does not extend `EventProvider` can be wrapped too — including third-party and in-house providers that implement only `FeatureProvider` (and perhaps `Tracking`). No adapter is needed and none is interposed: the breaker holds your provider as you passed it.

Only mechanism 3 needs the richer type, and it degrades cleanly: no event channel is attached, no delegate events arrive, and the breaker relies on the failure-count and state-driven mechanisms — exactly as it already does for an `EventProvider` that never emits. Two things follow:

- A plain delegate that does not override the SDK's deprecated `getState()` reports `READY` by default, so mechanism 2 never trips for it. Mechanism 1 is unaffected and still opens the circuit on `failureThreshold` consecutive failures.
- Recovery still works: after `resetTimeout` the half-open probe closes the circuit on success, without needing a `PROVIDER_READY` event.

Everything else is forwarded unchanged — all six resolvers (including `getLongEvaluation`), both `initialize` overloads, `isDomainScoped`, `getProviderHooks`, `track`, `shutdown`, `getMetadata` and `getState`.

### Usage

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*

// Wrap the primary provider with circuit breaker
val resilientProvider = CircuitBreakerProvider(
  optimizelyProvider,
  CircuitBreakerConfig(
    failureThreshold  = 3,           // open after 3 consecutive failures
    resetTimeout      = 30.seconds,  // probe recovery after 30s
    evaluationTimeout = 50.millis,   // timeout per delegate call
    halfOpenMaxCalls  = 1,           // probes before closing
    stalePolicy       = StalePolicy.Open
  )
)

// Compose with fallback using MultiProvider
val layer = FeatureFlags.fromProvider(
  FeatureFlags.multiProvider(List(resilientProvider, EnvVarProvider()), MultiProviderStrategy.firstSuccessful),
  FeatureFlagsConfig()
)
```

Or using the ZIO-based factory:

```scala
for
  cb   <- CircuitBreakerProvider.make(optimizelyProvider, CircuitBreakerConfig(
             evaluationTimeout = 50.millis
           ))
  layer = FeatureFlags.fromProvider(
            FeatureFlags.multiProvider(List(cb, EnvVarProvider()), MultiProviderStrategy.firstSuccessful),
            FeatureFlagsConfig()
          )
yield layer
```

### Configuration

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `failureThreshold` | `5` | Consecutive failures before the circuit opens |
| `resetTimeout` | `30.seconds` | Time in open state before allowing a probe |
| `evaluationTimeout` | `500.millis` | Max duration for a single delegate evaluation |
| `halfOpenMaxCalls` | `1` | Successful probes required to close the circuit |
| `stalePolicy` | `StalePolicy.Open` | Behavior when delegate reports `STALE` state |
| `stateCheckInterval` | `1.second` | Minimum interval between delegate state polls (`Duration.Zero` polls on every call) |

### Stale policy

Controls how the circuit breaker reacts when the delegate provider is in `STALE` state:

| Policy | Behavior |
|:-------|:---------|
| `StalePolicy.Open` | Treat stale as failure — open the circuit |
| `StalePolicy.Ignore` | Keep the current circuit state |
| `StalePolicy.HalfOpen` | Transition to half-open for probing |

### Failover latency comparison

| Approach | During outage | Failover latency |
|:---------|:--------------|:-----------------|
| `MultiProvider` + `firstSuccessful` alone | Tries primary every time, waits for failure | Up to minutes |
| Add timeout only (e.g., 50ms) | Still tries primary every time | 50ms per call |
| **Circuit breaker** | Skips primary entirely when open | **< 1ms** |

### Error classification

Not all errors indicate a provider health issue. The circuit breaker distinguishes between infrastructure failures and application-level errors:

| Error type | Counts toward threshold? | Examples |
|:-----------|:------------------------|:---------|
| **Infrastructure errors** | **Yes** | Timeouts, connection refused, `GeneralError`, `ProviderNotReadyError`, `FatalError` |
| **Application errors** | **No** | `FlagNotFoundError`, `TypeMismatchError`, `ParseError`, `TargetingKeyMissingError`, `InvalidContextError` |

Application-level errors **reset the consecutive failure counter** because they prove the provider is reachable. A burst of `FlagNotFoundError` calls for missing flags will **not** trip the circuit — in fact, they actively prevent it from tripping by resetting the failure count.

### State-driven failover example (Optimizely)

For providers like Optimizely Local that poll for configuration:

1. **Startup** → datafile fetch fails → provider reports `ERROR` → circuit opens instantly → fallback to `EnvVarProvider`
2. **30s later** → next poll succeeds → provider reports `READY` → circuit closes → evaluations resume via Optimizely
3. **Later poll fails** → provider reports `ERROR` → circuit opens again instantly

No evaluation failures needed — the circuit breaker reacts to the provider's health state directly.

---

## Caching Provider

A decorator that wraps any existing provider and adds evaluation caching backed by [zio-cache](https://github.com/zio/zio-cache).

### Benefits

- **Concurrent deduplication**: If N fibers evaluate the same flag simultaneously, the underlying provider is called exactly once
- **TTL-based expiration**: Cached values expire after a configurable duration
- **LRU eviction**: Bounded cache size with least-recently-used eviction

### Usage

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*

// Wrap any provider
val cachedProvider = CachingProvider(myRemoteProvider, CachingConfig(
  maxEntries = 1000,
  ttl = 5.minutes
))

val layer = FeatureFlags.fromProvider(cachedProvider)
```

Or using the ZIO-based factory:

```scala
for
  cached <- CachingProvider.make(myRemoteProvider, CachingConfig(ttl = 1.minute))
  layer   = FeatureFlags.fromProvider(cached)
yield layer
```

### Cache behavior

- **First evaluation**: Calls the underlying provider, caches the result
- **Subsequent evaluations**: Returns cached result with `CACHED` reason
- **Different contexts**: Cached separately (cache key includes context hash)
- **TTL expiry**: Re-evaluates from the underlying provider after TTL
- **Failures are never cached**: an evaluation that throws or resolves with an error code is returned to the caller but not stored, so the next call retries the delegate (a transient error never poisons the entry for the full TTL)

### High-cardinality contexts

If your evaluation context includes per-request fields (e.g., a random UUID as targeting key), every evaluation produces a unique cache key — defeating the cache entirely.

Use `contextKeys` to specify which context attributes matter for caching:

```scala
val cached = CachingProvider(remoteProvider, CachingConfig(
  ttl = 5.minutes,
  contextKeys = Some(Set("plan", "region"))  // only cache by plan + region
))
```

| `contextKeys` value | Behavior |
|:---------------------|:---------|
| `None` (default) | Full context hashed — every unique targeting key / attribute combo is a separate entry |
| `Some(Set("plan"))` | Only the `plan` attribute is hashed — different users with the same plan share a cache entry |
| `Some(Set.empty)` | Context ignored entirely — cache by flag key only (useful for flags that don't depend on context) |

### Invalidation

The wrapper takes ownership of the delegate's event channel, so a `PROVIDER_CONFIGURATION_CHANGED` emitted by the wrapped provider **invalidates the whole cache automatically** — and the event is re-emitted through the wrapper, so `FeatureFlags.events` subscribers still see it. (A delegate supports exactly one attachment, so don't register the same wrapped instance directly with an `OpenFeatureAPI`.)

You can still invalidate manually for changes the delegate doesn't signal:

```scala
for
  cached <- CachingProvider.make(myRemoteProvider)
  _      <- FeatureFlags.onConfigurationChanged { (flags, _) =>
               cached.invalidateAll
             }
yield ()
```

### Combining providers

The real power of the extras module comes from combining providers. The multi-provider pattern lets you layer local overrides on top of remote providers, with caching in between.

**Example: env var overrides → HOCON defaults → cached remote provider**

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*

object MyApp extends ZIOAppDefault:

  val program = for
    // Create providers — first match wins
    envProvider    <- ZIO.succeed(EnvVarProvider())            // Env vars: highest priority
    hoconProvider  <- ZIO.succeed(HoconProvider())             // application.conf: local defaults
    cachedRemote   <- CachingProvider.make(                    // Remote: cached, lowest priority
                        myRemoteProvider,
                        CachingConfig(maxEntries = 1000, ttl = 5.minutes)
                      )

    // Combine: env vars → HOCON → cached remote
    layer = FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(envProvider, hoconProvider, cachedRemote)), FeatureFlagsConfig())

    // Use feature flags
    _ <- FeatureFlags.boolean("new-checkout", default = false).flatMap { enabled =>
           ZIO.logInfo(s"new-checkout: $enabled")
         }.provide(Scope.default >>> layer)
  yield ()

  def run = program
```

With this setup:
- Set `FF_NEW_CHECKOUT=true` in the environment → overrides everything
- Add `new-checkout = true` to `application.conf` → overrides remote, but not env
- If neither is set → falls through to the cached remote provider
- Remote evaluations are cached for 5 minutes with concurrent dedup

**Example: cached remote provider with automatic invalidation**

```scala
for
  cached <- CachingProvider.make(remoteProvider, CachingConfig(ttl = 2.minutes))
  layer   = FeatureFlags.fromProvider(cached)
  ff     <- layer.build.map(_.get)

  // Wire up automatic cache invalidation on config changes
  _ <- ff.onConfigurationChanged { (changedFlags, _) =>
         ZIO.logInfo(s"Flags changed: $changedFlags") *>
           cached.invalidateAll
       }

  // Evaluations are now cached with automatic invalidation
  result <- ff.boolean("feature", default = false)
yield result
```
