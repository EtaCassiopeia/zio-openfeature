---
layout: default
title: Getting Started
nav_order: 2
---

# Getting Started
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

Add the following to your `build.sbt`, replacing `<version>` with the version shown in the badge above:

```scala
// Core library (includes OpenFeature SDK)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>"

// Built-in providers: HOCON, env vars, caching wrapper (optional)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-extras" % "<version>"

// OFREP — OpenFeature Remote Evaluation Protocol provider (HTTP)
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"

// Optimizely Feature Experimentation — direct integration on top of the Optimizely Java SDK
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>"

// For testing
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

You'll also need an OpenFeature provider for your feature flag service:

```scala
// Example: flagd provider
libraryDependencies += "dev.openfeature.contrib.providers" % "flagd" % "0.8.9"

// Or: LaunchDarkly
libraryDependencies += "dev.openfeature.contrib.providers" % "launchdarkly" % "1.1.0"
```

See the [OpenFeature ecosystem](https://openfeature.dev/ecosystem) for all available providers.

## Basic Usage

### Setting Up with a Provider

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider

object MyApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("my-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(new FlagdProvider())
  )
```

### Setting Up for Testing

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object TestApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("my-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    TestFeatureProvider.scopedLayer(Map("my-feature" -> true))
  )
```

## Evaluating Flags

### Boolean Flags

```scala
val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
  FeatureFlags.boolean("feature-toggle", default = false)
```

### String Flags

```scala
val variant: ZIO[FeatureFlags, FeatureFlagError, String] =
  FeatureFlags.string("button-color", default = "blue")
```

### Numeric Flags

```scala
val limit: ZIO[FeatureFlags, FeatureFlagError, Int] =
  FeatureFlags.int("max-items", default = 100)

val rate: ZIO[FeatureFlags, FeatureFlagError, Double] =
  FeatureFlags.double("sample-rate", default = 0.1)

val count: ZIO[FeatureFlags, FeatureFlagError, Long] =
  FeatureFlags.long("max-bytes", default = 1000000L)
```

### Object Flags

```scala
val config: ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
  FeatureFlags.obj("feature-config", default = Map("timeout" -> 30))
```

### Declaring a flag once

Each call above names the key, the type and the default at the use site. For a flag read from more than one
place, declare it once with a `FlagDef` and pass the definition instead:

```scala
val MaxItems = FlagDef("max-items", 100, "cart page size")

val limit: ZIO[FeatureFlags, FeatureFlagError, Int] =
  FeatureFlags.value(MaxItems)
```

`value`, `valueOrDefault`, `valueDetails` and `resolveOrDefault` all accept a `FlagDef` in place of the
`(key, default)` pair, with the same context and options arities. It is the same evaluation path — see
[Typed Flags]({{ site.baseurl }}/typed-flags) for the full picture, including how to use your own enums and
case classes as flag types.

### Detailed Evaluation

Get full resolution details including variant, reason, and metadata:

```scala
val details: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
  FeatureFlags.booleanDetails("feature", default = false)

details.map { resolution =>
  println(s"Value: ${resolution.value}")
  println(s"Variant: ${resolution.variant}")
  println(s"Reason: ${resolution.reason}")
  println(s"Flag Key: ${resolution.flagKey}")
}
```

### Two tiers: typed and total

The methods above are the **typed tier**: they surface every evaluation error in a typed `FeatureFlagError` channel —
including a provider that answers with an error *code* rather than by throwing (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`,
`PARSE_ERROR`, …), which is how most providers report problems. A missing flag fails with `FlagNotFound(key)`, a
provider-side type problem with `TypeMismatch`, and so on. Reach for this tier when a default would be *wrong* — a
fail-closed gate ("if I cannot read this flag, refuse the operation") must actually fail closed:

```scala
FeatureFlags.boolean("payments.enabled", default = false).either.flatMap {
  case Right(enabled)                         => proceed(enabled)
  case Left(FeatureFlagError.FlagNotFound(_)) => ZIO.fail(Misconfigured("payments.enabled is not defined"))
  case Left(err)                              => ZIO.fail(FlagsUnavailable(err))
}
```

{: .note }
> **Upgrading from 1.0.0.** In 1.0.0 the typed tier returned a provider-reported error code as a *successful*
> resolution carrying your default. If you called an `xDetails` method and inspected `errorCode` on the result, call
> `resolveOrDefault` instead — that is the resolution-with-code form. If you relied on `value` / `boolean` returning
> the default for a missing flag, `valueOrDefault` / `booleanOrDefault` is that contract. Both are one-line renames;
> see the [CHANGELOG](https://github.com/EtaCassiopeia/zio-openfeature/blob/main/CHANGELOG.md) entry for #388.

### Total Evaluation (never fails)

When you would rather always get a value — falling back to the default on any error, per the OpenFeature spec's
"total" evaluation (§1.4.10) — use the `*OrDefault` variants. They return `UIO`, so there is no error to handle:

```scala
val enabled: ZIO[FeatureFlags, Nothing, Boolean] =
  FeatureFlags.booleanOrDefault("feature-toggle", default = false)
```

`resolveOrDefault` is the total form of `booleanDetails`/`valueDetails`: it never fails, but the returned
`FlagResolution` still tells you *why* the default was served — `reason` is `Error` and `errorCode`/`errorMessage`
are populated when a fallback occurred.

```scala
FeatureFlags.resolveOrDefault[Boolean]("feature-toggle", default = false).map { resolution =>
  if (resolution.errorCode.isDefined) println(s"Served default: ${resolution.errorMessage}")
}
```

Both typed errors and unexpected defects are absorbed into the default; only fiber interruption still propagates.

Every served-default fallback leaves a **warn** log line naming the flag, why it degraded and the value served —
`Flag 'checkout-v2' fell back to its default false (FlagNotFound: Flag 'checkout-v2' not found)` — **rate-limited per flag key** so a
provider outage on a hot flag does not drown the one line that matters. By default one line per key per 60 seconds; the
next line for that key carries `(suppressed 412 similar)`. Absorbed defects go through the same limiter but in their
own per-key bucket, keep their cause, and are still logged under `Off` (throttled at the default window) — a defect is
a bug, not outage noise. Beyond 1024 distinct keys, further keys share one throttled overflow bucket rather than going
unlimited. Hooks and metrics still see every evaluation; only this log line is limited. A flag that is *permanently*
absent from the provider is a permanent (throttled) warn source — define it, or evaluate it through the typed API.

This line is a deliberate deviation from spec §1.4.11 (client operations *should not* write log messages), recorded in
[Spec Compliance]({{ site.baseurl }}/spec-compliance#flag-evaluation-spec-13-14): the throttle bounds the volume the
spec worries about, and a silently served default is the signal most worth having. `FallbackLogging.Off` — optionally
with `FeatureHook.logging` for per-evaluation logs, the spec's own mechanism — is the conformant configuration. Tune or
silence it per instance:

```scala
FeatureFlagsConfig().withFallbackLogging(FallbackLogging.Throttled(5.minutes)) // or Off, or Always
FeatureFlags.fromAcquireAsync(acquire, fallback, fallbackLogging = FallbackLogging.Off)
```

## Using Evaluation Context

Pass user and environment information for targeted flag evaluation:

```scala
// Create context with targeting key (user ID)
val ctx = EvaluationContext("user-123")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("beta", true)

// Evaluate with context
FeatureFlags.boolean("premium-feature", default = false, ctx)
```

### Setting Global Context

Apply context to all evaluations:

```scala
val globalCtx = EvaluationContext.empty
  .withAttribute("app_version", "2.0.0")
  .withAttribute("environment", "production")

FeatureFlags.setGlobalContext(globalCtx)
```

### Scoped Context

Apply context to a block of code:

```scala
val requestCtx = EvaluationContext("user-456")
  .withAttribute("session_id", sessionId)

FeatureFlags.withContext(requestCtx) {
  // All evaluations in this block use requestCtx
  for
    a <- FeatureFlags.boolean("feature-a", default = false)
    b <- FeatureFlags.string("feature-b", default = "control")
  yield (a, b)
}
```

## Factory Methods

### From Any OpenFeature Provider

```scala
import dev.openfeature.sdk.FeatureProvider

val provider: FeatureProvider = // any OpenFeature provider

val layer = FeatureFlags.fromProvider(provider)
```

### With Domain Isolation, Hooks, Timeouts, ...

Anything beyond the plain provider — a named domain, initial hooks, custom timeouts, async init, or any combination —
goes through `FeatureFlagsConfig` and the config-driven factory `FeatureFlags.fromProvider(provider, config)`:

```scala
// Domain isolation, for multi-provider setups
val layer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain("my-domain"))

// Optionally include a version for telemetry/debugging
val versionedLayer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain("my-domain").withVersion("1.0.0"))

// Initial hooks
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)
val hookedLayer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withHooks(hooks))

// Domain + hooks together — not expressible with the pre-#253 factory overloads
val combinedLayer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain("my-domain").withHooks(hooks))
```

### With Multiple Providers

Combine multiple providers using the SDK's MultiProvider support via `FeatureFlags.multiProvider`, then pass the
result into `fromProvider` like any other provider:

```scala
val layer = FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(localProvider, remoteProvider)), FeatureFlagsConfig())
```

See [Factory Methods](providers.md#factory-methods) in the Providers guide for the full `FeatureFlagsConfig`
reference, including `InitMode` (sync vs async) and `ApiOwnership`.

## Tracking Events

Track user actions for analytics and experimentation:

```scala
// Simple event tracking
FeatureFlags.track("button-clicked")

// Track with user context
FeatureFlags.track("purchase", EvaluationContext("user-123"))

// Track with event details
val details = TrackingEventDetails(
  value = Some(99.99),
  attributes = Map("currency" -> "USD", "items" -> 3)
)
FeatureFlags.track("checkout", details)
```

## Event Handlers

React to provider lifecycle events:

```scala
// Handle provider ready
FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo(s"Provider ${metadata.name} is ready")
}

// Handle configuration changes
FeatureFlags.onConfigurationChanged { (flags, metadata) =>
  ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}")
}

// Handle errors
FeatureFlags.onProviderError { (error, metadata) =>
  ZIO.logError(s"Provider error: ${error.getMessage}")
}
```

## Next Steps

- Read [Typed Flags]({{ site.baseurl }}/typed-flags) to declare each flag once and use your own domain types
- Learn about [Evaluation Context]({{ site.baseurl }}/context) for targeted flag evaluation
- Explore [Hooks]({{ site.baseurl }}/hooks) for logging, metrics, and validation
- Use [Transactions]({{ site.baseurl }}/transactions) for flag overrides and tracking
- See [Testkit]({{ site.baseurl }}/testkit) for testing best practices
- Check [Providers]({{ site.baseurl }}/providers) for provider-specific features
- Review [Spec Compliance]({{ site.baseurl }}/spec-compliance) for OpenFeature compatibility
