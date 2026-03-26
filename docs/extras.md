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

All evaluations return `STATIC` as the resolution reason since values are loaded from config.

### Manual reload

```scala
// Re-read config from disk without restarting
val provider = HoconProvider()
provider.reload() // or provider.reload("custom-path")
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

Unparseable values fall back to the provided default.

### Testing

Use `withLookup` to provide a custom env var source:

```scala
val testEnv = Map("FF_MY_FLAG" -> "true")
val provider = EnvVarProvider.withLookup(testEnv.get)
```

---

## Circuit Breaker Provider

A decorator that wraps any provider with circuit breaker logic for fast failover. When the delegate provider fails repeatedly or becomes unhealthy, the circuit opens and evaluations fail immediately (< 1ms) — enabling instant fallback when composed with `MultiProvider` and `FirstSuccessfulStrategy`.

### When to use

Use this when your primary provider is an external service (e.g., Optimizely, LaunchDarkly) and you need guaranteed fast failover to a local fallback (e.g., `EnvVarProvider`) if the service is slow or unavailable.

### State machine

The circuit breaker has three states:

| State | Behavior |
|:------|:---------|
| **Closed** | Normal operation. Evaluations forwarded to the delegate. Consecutive failures tracked. |
| **Open** | Evaluations fail immediately without calling the delegate (< 1ms). After `resetTimeout`, transitions to Half-Open. |
| **Half-Open** | A single probe evaluation is allowed through. On success → Closed. On failure → Open. |

### Two tripping mechanisms

1. **Failure-count**: After `failureThreshold` consecutive evaluation failures (including timeouts), the circuit opens.
2. **State-driven**: Before each evaluation, the delegate's state is checked. If `ERROR` or `FATAL`, the circuit opens immediately — no failed evaluations needed. When the delegate recovers to `READY`, the circuit closes automatically.

### Usage

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*
import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy

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
val layer = FeatureFlags.fromMultiProvider(
  List(resilientProvider, EnvVarProvider()),
  new FirstSuccessfulStrategy()
)
```

Or using the ZIO-based factory:

```scala
for
  cb   <- CircuitBreakerProvider.make(optimizelyProvider, CircuitBreakerConfig(
             evaluationTimeout = 50.millis
           ))
  layer = FeatureFlags.fromMultiProvider(
            List(cb, EnvVarProvider()),
            new FirstSuccessfulStrategy()
          )
yield layer
```

### Configuration

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `failureThreshold` | `5` | Consecutive failures before the circuit opens |
| `resetTimeout` | `30.seconds` | Time in open state before allowing a probe |
| `evaluationTimeout` | `100.millis` | Max duration for a single delegate evaluation |
| `halfOpenMaxCalls` | `1` | Successful probes required to close the circuit |
| `stalePolicy` | `StalePolicy.Open` | Behavior when delegate reports `STALE` state |

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
| `MultiProvider` + `FirstSuccessfulStrategy` alone | Tries primary every time, waits for failure | Up to minutes |
| Add timeout only (e.g., 50ms) | Still tries primary every time | 50ms per call |
| **Circuit breaker** | Skips primary entirely when open | **< 1ms** |

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

Invalidate the cache when receiving `ConfigurationChanged` events:

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
    layer = FeatureFlags.fromMultiProvider(List(envProvider, hoconProvider, cachedRemote))

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
