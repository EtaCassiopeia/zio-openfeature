---
layout: default
title: Optimizely
nav_order: 6
---

# Optimizely
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

`zio-openfeature-optimizely` integrates with [Optimizely Feature Experimentation](https://www.optimizely.com/products/feature-experimentation/) directly on top of the official **Optimizely Java SDK** (`com.optimizely.ab:core-api` and `core-httpclient-impl`). The library does NOT depend on `dev.openfeature.contrib.providers:optimizely` — at the time of writing that artifact is not published to Maven Central.

You get:

- A Scala-friendly factory (`OptimizelyProvider.make(sdkKey)`) that validates inputs and returns a typed
  `FeatureFlagError.InvalidConfiguration` on bad config.
- An `OpenFeature`-compatible provider (`OptimizelyFeatureProvider extends EventProvider`) that emits
  `PROVIDER_CONFIGURATION_CHANGED` when Optimizely's datafile poller picks up a new revision.
- The same `FeatureFlags.fromProvider*` factories you use with any other provider, including the 30-second
  `initTimeout` default added in workstream A.

---

## 1. Getting an SDK key

1. Log in to [app.optimizely.com](https://app.optimizely.com).
2. Pick the project you want to drive flags from.
3. Open **Settings → Environments**.
4. Copy the **SDK Key** for the environment (Development / Staging / Production). Each environment has its own key — never reuse a Production key in Development apps.

The SDK key is a short alphanumeric token like `abcd1234efgh5678`. The library's validator accepts `[A-Za-z0-9_-]+` between 6 and 128 chars and rejects obvious placeholders (`YOUR_SDK_KEY`, `<sdk-key>`, `changeme`, …) up front.

---

## 2. Quick start

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"
```

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.optimizely.OptimizelyProvider

object MyApp extends ZIOAppDefault:

  def run: ZIO[Any, Throwable, Unit] = ZIO.scoped {
    for
      sdkKey   <- ZIO.attempt(sys.env("OPTIMIZELY_SDK_KEY"))
      provider <- OptimizelyProvider.make(sdkKey).mapError(e => new RuntimeException(e.message))
      env      <- FeatureFlags.fromProviderAsync(provider).build
      ff        = env.get[FeatureFlags]
      enabled  <- ff.boolean("new-checkout", default = false).mapError(e => new RuntimeException(e.message))
      _        <- ZIO.logInfo(s"new-checkout = $enabled")
    yield ()
  }
```

What this does on startup:

1. `OptimizelyProvider.make(sdkKey)` validates the key shape (non-empty, allowed characters, not a placeholder) and constructs an `Optimizely` client wired to the public CDN at `https://cdn.optimizely.com/datafiles/<sdkKey>.json`. Construction performs no network activity and starts no background polling — the datafile poller runs only between `initialize()` and `shutdown()`. A provider you build owns a polling executor and an HTTP client, so pair `make` with something that shuts it down (a `FeatureFlags` layer does), or use `OptimizelyProvider.scoped` / `OptimizelyProvider.layer`, which release it when the surrounding scope closes. `OptimizelyProviderConfig` exposes `pollingInterval` and `blockingTimeout` when the SDK defaults (5 min / 10 s) don't fit.
2. `FeatureFlags.fromProviderAsync(provider)` starts the provider in the background, watches for the first datafile load, and uses the default **30 s `initTimeout`** as an upper bound. If the datafile never arrives, `providerStatus` transitions to `Fatal` — your evaluations stop hanging.
3. On every subsequent datafile revision (typically every 30 s for the SDK's default polling interval), the provider emits `ProviderEvent.ConfigurationChanged` — call `FeatureFlags.onConfigurationChanged(handler)` if you want to react.

---

## 3. Configuring init timeout

The 30-second default is friendly for most apps. You'll want to tune it in three cases:

- **Tests** — drop it to 1–2 seconds so a forgotten WireMock stub fails fast.
- **Cold start on a constrained network** — raise it if your CI runners or first production deploys regularly need >30 s for the initial datafile fetch.
- **Run-anywhere CLIs** — set it lower (5–10 s) so users on flaky networks get a clear startup error instead of waiting half a minute.

Override via the 3-arg async factory:

```scala
FeatureFlags.fromProviderAsync(
  provider,
  evaluationTimeout = 500.millis,
  initTimeout       = 5.seconds
)
```

When `initTimeout` elapses without the datafile arriving, the layer's watchdog flips `providerStatus` to `Fatal`. Application code that polls `providerStatus` (or registers `onProviderReady` / `onProviderError`) sees this immediately and can fail loud:

```scala
val ready = for
  ff     <- ZIO.service[FeatureFlags]
  status <- ff.providerStatus
  _      <- ZIO.fail(new RuntimeException(s"Optimizely never became ready: $status"))
              .when(status == ProviderStatus.Fatal)
yield ()
```

---

## 4. Self-hosted Optimizely Agent

When you run [Optimizely Agent](https://docs.developers.optimizely.com/feature-experimentation/docs/agent) inside your network (e.g., for FedRAMP / data-residency requirements), pass the agent's URL explicitly:

```scala
val provider = OptimizelyProvider.make(
  sdkKey      = sys.env("OPTIMIZELY_SDK_KEY"),
  datafileUrl = "https://flags.internal.example.com/datafile.json"
)
```

The URL is validated alongside the SDK key: parseable as a URI, scheme `http` or `https`, non-empty host. A malformed URL fails layer build with `FeatureFlagError.InvalidConfiguration("malformed datafileUrl …")` before any network call.

---

## 5. Production resilience — composing with `CircuitBreakerProvider`

`OptimizelyFeatureProvider` extends `EventProvider`, so it composes cleanly with the breaker from `zio-openfeature-extras`. A degraded Optimizely CDN trips the breaker; while open, evaluations fail fast (sub-millisecond) without contacting Optimizely.

```scala
import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig}

val breakerConfig = CircuitBreakerProviderConfig(
  failureThreshold = 5,
  resetTimeout     = 30.seconds
)

val program = ZIO.scoped {
  for
    sdkKey   <- ZIO.attempt(sys.env("OPTIMIZELY_SDK_KEY"))
    inner    <- OptimizelyProvider.make(sdkKey).mapError(e => new RuntimeException(e.message))
    wrapped  <- CircuitBreakerProvider.make(inner, breakerConfig)
    env      <- FeatureFlags.fromProviderAsync(wrapped, evaluationTimeout = 500.millis).build
    ff        = env.get[FeatureFlags]
    enabled  <- ff.boolean("flag", default = false).mapError(e => new RuntimeException(e.message))
  yield enabled
}
```

The breaker counts *infrastructure* errors (timeouts, connection failures, `GeneralError`, `ProviderNotReadyError`) toward the failure threshold. Application-level errors (`FlagNotFoundError`, type mismatches, etc.) don't trip the breaker — those indicate the provider is reachable. See [Extras → CircuitBreakerProvider]({{ site.baseurl }}/extras) for the full classification.

---

## 6. Testing your app

Two patterns, both supported:

### Unit-testing application code without touching Optimizely

Use `TestFeatureProvider` from `zio-openfeature-testkit` — there's a worked example under [`examples/testkit-app/`](https://github.com/EtaCassiopeia/zio-openfeature/tree/main/examples/testkit-app):

```scala
import zio.openfeature.testkit.TestFeatureProvider

val spec = test("UserService returns the new greeting when the flag is ON") {
  for
    provider <- ZIO.service[TestFeatureProvider]
    _        <- provider.setFlag("new-greeting-copy", true)
    svc      <- ZIO.service[UserService]
    greeting <- svc.welcome("alice")
  yield assertTrue(greeting.contains("Hey alice"))
}.provide(TestFeatureProvider.scopedLayer >>> UserService.live)
```

This is what your app developers should use day-to-day. Nothing in this test path requires a real Optimizely SDK key or network access.

### Integration-testing the Optimizely wiring itself

The library's own integration suite (`OptimizelyProviderIntegrationSpec`) drives a WireMock server impersonating the Optimizely CDN, with short `blockingTimeout` and `pollingInterval` knobs to keep tests fast. If you want to replicate that pattern in your own repo (e.g., to confirm a custom datafile schema), copy the per-test `WireMockServer` setup and use `OptimizelyFeatureProvider`'s package-private constructor through the `fromOptimizelyClient` escape hatch:

```scala
val opt = Optimizely.builder()
  .withConfigManager(
    HttpProjectConfigManager.builder()
      .withSdkKey("test-sdk-key")
      .withUrl(wireMockBaseUrl)
      .withBlockingTimeout(1L, TimeUnit.SECONDS)
      .withPollingInterval(1L, TimeUnit.HOURS)  // effectively disable polling for the test
      .build()
  )
  .build()

val provider = Unsafe.unsafe { implicit u =>
  Runtime.default.unsafe.run(OptimizelyProvider.fromOptimizelyClient(opt)).getOrThrow()
}
```

---

## 7. Operating Optimizely-backed apps

### What to alert on

| Signal                                           | Likely cause                                        | Suggested action                         |
|--------------------------------------------------|-----------------------------------------------------|------------------------------------------|
| `FeatureFlagError.Unauthorized`                  | Wrong / revoked SDK key, expired access token       | Page on-call; check key rotation status  |
| `FeatureFlagError.Unreachable`                   | DNS / network failure reaching `cdn.optimizely.com` | Check network egress; consider self-hosting via Optimizely Agent |
| `providerStatus = Fatal` after startup           | Datafile never loaded (SDK key invalid, CDN unreachable, network ACL) | Restart with corrected config; alert on >1 occurrence |
| `FlagResolution.errorCode` populated repeatedly | Misconfigured flag, missing rollout                 | Application-side issue, not a transport one — check Optimizely UI |

`Unauthorized` and `Unreachable` come from the typed-error classifier (`FeatureFlagError.classify`); see the [error types]({{ site.baseurl }}/architecture) doc for the full ADT.

### `ProviderEvent.ConfigurationChanged` events

The Optimizely SDK polls its datafile URL on a background thread (default ~30 s interval). Whenever a new revision is fetched and parsed, `OptimizelyFeatureProvider` emits OpenFeature's `PROVIDER_CONFIGURATION_CHANGED` event. Hook into it for log lines, cache invalidation, or metrics:

```scala
ff.onConfigurationChanged { (flagsChanged, meta) =>
  ZIO.logInfo(s"Optimizely datafile updated; affected flags: $flagsChanged")
}
```

The library doesn't pass through the *list* of changed flags from Optimizely (the underlying notification doesn't expose them in this version of the SDK) — the `flagsChanged` set will be empty. Use the event as a trigger; check the actual flag values via fresh evaluations.

### Detecting `Fatal` status from a healthcheck

After `initTimeout` elapses with the provider still un-ready, the async watchdog flips status to `Fatal`. This is recoverable (a later `PROVIDER_READY` event transitions `Fatal → Ready`), but during the gap every evaluation fails — so don't route traffic to the pod. The standard pattern:

```scala
val readinessCheck: URIO[FeatureFlags, Boolean] =
  ZIO.serviceWithZIO[FeatureFlags](_.providerStatus).map {
    case ProviderStatus.Ready | ProviderStatus.Stale => true
    case _                                            => false
  }
```

Wire `readinessCheck` into your readiness endpoint. Kubernetes / ECS / Nomad won't direct traffic to the pod until `Ready`. The cold-start window — and any post-`Fatal` recovery window — becomes invisible to users. Without this, your app advertises healthy while every flag silently returns its OF default.

---

## 8. Choosing a topology

The patterns below differ in **when defaults are served** and **whether the app refuses to start on a misconfiguration**. See [Providers → Choosing a strategy]({{ site.baseurl }}/providers#choosing-a-strategy--sync-vs-async-with-vs-without-fallback) for the full decision matrix; the Optimizely-specific guidance is below.

### Pattern A — Optimizely-only with the async watchdog (default)

Highest availability after boot, but during the cold-start window every flag returns its OF default until Optimizely's first datafile arrives. Use this when:

- Most of your flags are non-correctness-bearing (UI variations, feature gates, experiments).
- You wire `providerStatus` into your readiness check (so the cold-start defaults don't reach users).
- You can tolerate a 30-second window where new pods are not-routed.

```scala
ZIO.scoped {
  for
    provider <- OptimizelyProvider.make(sys.env("OPTIMIZELY_SDK_KEY"))
    env      <- FeatureFlags.fromProviderAsync(provider, evaluationTimeout = 500.millis).build
    // initTimeout uses the library default (30 s); override via the 3-arg overload
  yield env.get[FeatureFlags]
}
```

### Pattern B — Optimizely-only with fail-fast boot

Boot blocks until Optimizely is `READY` or `initTimeout` elapses; on failure the layer build throws and the orchestrator restarts the pod. Use this when:

- Some of your flags gate correctness (financial logic, fraud checks, billing rules).
- "All flags default" is unsafe even for a few seconds.
- You'd rather fail loud at boot than serve traffic in a degraded state.

```scala
ZIO.scoped {
  for
    provider <- OptimizelyProvider.make(sys.env("OPTIMIZELY_SDK_KEY"))
    env      <- FeatureFlags.fromProvider(
                  provider,
                  evaluationTimeout = 500.millis,
                  initTimeout       = 15.seconds   // tight — fail fast on misconfig
                ).build
  yield env.get[FeatureFlags]
}
```

If Optimizely doesn't reach `READY` in 15 s, the layer build fails with `TimeoutException` or `IllegalStateException`. Your pod doesn't go live; the orchestrator restarts it. There's no half-initialised state and no window of all-defaults.

### Pattern C — Optimizely + `EnvVarProvider` for critical flags only

Hybrid: Optimizely serves the bulk of flags; a small `EnvVarProvider` is the second provider in a `MultiProvider`, holding only the flags whose default-value matters under outage. Use this when:

- A small subset of flags is correctness-bearing; the rest is fine to default during an outage.
- You want the highest availability (multi-provider is always `Ready` via EnvVar) without sacrificing safety on the critical few.

```scala
import zio.openfeature.extras.EnvVarProvider
import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy

val critical = Map(
  "FF_MAINTENANCE_MODE"    -> "false",
  "FF_FRAUD_CHECK_ENABLED" -> "true"
)

ZIO.scoped {
  for
    optimizely <- OptimizelyProvider.make(sys.env("OPTIMIZELY_SDK_KEY"))
    envProvider = EnvVarProvider.withLookup(critical.get)
    env        <- FeatureFlags.fromMultiProviderAsync(
                    List(optimizely, envProvider),
                    new FirstSuccessfulStrategy()
                  ).build
  yield env.get[FeatureFlags]
}
```

`FirstSuccessfulStrategy` tries Optimizely first; if it fails or is unready, falls through to `EnvVarProvider`. Because `EnvVarProvider` is instantly `Ready`, the `MultiProvider`'s aggregate state is `Ready` immediately and the 30-second watchdog never fires — meaning Optimizely-specific failures are no longer visible at the `FeatureFlags` layer. If you want to alert on Optimizely-side problems anyway, poll the underlying client's `isValid` from a side healthcheck or hook into `onProviderError`.

### Picking between A, B, and C

| Question | Pattern A (Optimizely, async) | Pattern B (Optimizely, sync) | Pattern C (Optimizely + EnvVar) |
|---|---|---|---|
| Can the app start while Optimizely is down? | yes, but readiness blocks until `Ready` | no — boot fails | yes, always |
| What happens during a 60-second Optimizely outage at runtime? | flags return OF defaults until recovery | n/a (app refuses to start) | flags served from EnvVar where defined; OF defaults otherwise |
| Operational visibility on Optimizely failures | high — `providerStatus` reports `Fatal` | very high — boot fails loudly | low — `MultiProvider` masks it; needs separate monitoring |
| Right for correctness-critical workloads | only if you wire readiness | **yes — preferred** | yes for the EnvVar-covered flags only |
| Right for experiments / UI gates / dashboards | **yes — preferred** | overkill | overkill |

If in doubt: start with B for any service handling money or user-trust decisions; start with A for everything else; pull in C only when a specific outage exposed a flag whose default was wrong.
