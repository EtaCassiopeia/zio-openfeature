---
layout: default
title: Providers
nav_order: 4
---

# Providers
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

ZIO OpenFeature wraps the [OpenFeature Java SDK](https://openfeature.dev/docs/reference/technologies/server/java), giving you access to the entire ecosystem of OpenFeature providers. Use any provider that implements the OpenFeature specification.

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.optimizely.OptimizelyProvider

val layer = FeatureFlags.fromProvider(provider)

program.provide(Scope.default >>> layer)
```

### Built-in Providers

This repo ships providers for common use cases — no external vendor required.

The `zio-openfeature-extras` module bundles the lightweight providers:

| Provider | Use case |
|:---------|:---------|
| `HoconProvider` | Read flags from `application.conf` (Typesafe Config) |
| `EnvVarProvider` | Read flags from environment variables |
| `CachingProvider` | Wrap any provider with zio-cache backed evaluation caching |

The `zio-openfeature-ofrep` module is shipped separately so its HTTP-client transitive stack (Jackson, Guava) only loads if you actually use OFREP:

| Provider | Use case |
|:---------|:---------|
| `OFREPProvider` | Evaluate flags via the OpenFeature Remote Evaluation Protocol (HTTP) |

See [Extras]({{ site.baseurl }}/extras) for details on all of the above.

---

## Using Optimizely

[Optimizely](https://www.optimizely.com/) is the recommended provider for most users. It provides feature flags, A/B testing, and experimentation capabilities.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>",
  "dev.openfeature.contrib.providers" % "optimizely" % "0.1.0"
)
```

### Getting Your SDK Key

1. Log in to [Optimizely](https://app.optimizely.com)
2. Navigate to **Settings** → **Environments**
3. Copy the **SDK Key** for your environment (Development, Staging, or Production)

### Basic Setup

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.optimizely.OptimizelyProvider
import dev.openfeature.contrib.providers.optimizely.OptimizelyProviderConfig

object MyApp extends ZIOAppDefault:

  val config = OptimizelyProviderConfig.builder()
    .sdkKey("YOUR_SDK_KEY")
    .build()

  val provider = new OptimizelyProvider(config)

  val program = for
    enabled <- FeatureFlags.boolean("new-checkout-flow", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("New checkout enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(provider)
  )
```

### User Targeting

The `targetingKey` in the evaluation context maps to the Optimizely user ID:

```scala
// Create context with user ID
val userContext = EvaluationContext("user-12345")
  .withAttribute("email", "user@example.com")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")

// Evaluate with user context
val result = FeatureFlags.boolean("premium-feature", false, userContext)
```

### Feature Variables

Optimizely supports feature variables. By default, the OpenFeature provider searches for a variable named `"value"`:

```scala
// Optimizely feature with a "value" variable
val discount = FeatureFlags.double("discount-rate", 0.0, userContext)
```

To use a different variable name, add the `variableKey` attribute to the context:

```scala
val ctx = userContext.withAttribute("variableKey", "custom_variable_name")
val value = FeatureFlags.string("feature-key", "default", ctx)
```

### Complete Example

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.optimizely.OptimizelyProvider
import dev.openfeature.contrib.providers.optimizely.OptimizelyProviderConfig

object FeatureFlagApp extends ZIOAppDefault:

  val sdkKey = sys.env.getOrElse("OPTIMIZELY_SDK_KEY", "YOUR_SDK_KEY")

  val config = OptimizelyProviderConfig.builder()
    .sdkKey(sdkKey)
    .build()

  val provider = new OptimizelyProvider(config)

  def handleUserRequest(userId: String, plan: String) = for
    ctx = EvaluationContext(userId).withAttribute("plan", plan)

    // Check if new checkout is enabled for this user
    newCheckout <- FeatureFlags.boolean("new-checkout-flow", false, ctx)

    // Get the button variation
    buttonColor <- FeatureFlags.string("checkout-button", "blue", ctx)

    // Get max cart items
    maxItems <- FeatureFlags.int("max-cart-items", 10, ctx)

    _ <- Console.printLine(s"User $userId (plan: $plan):")
    _ <- Console.printLine(s"  - New checkout: $newCheckout")
    _ <- Console.printLine(s"  - Button color: $buttonColor")
    _ <- Console.printLine(s"  - Max items: $maxItems")
  yield ()

  val program = for
    _ <- handleUserRequest("user-001", "free")
    _ <- handleUserRequest("user-002", "premium")
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(provider)
  )
```

---

## Other Providers

The OpenFeature ecosystem includes providers for many feature flag services:

| Provider | Dependency | Description |
|:---------|:-----------|:------------|
| [Optimizely](https://www.optimizely.com/) | `"dev.openfeature.contrib.providers" % "optimizely" % "0.1.0"` | Feature flags and A/B testing |
| [flagd](https://flagd.dev/) | `"dev.openfeature.contrib.providers" % "flagd" % "0.8.9"` | Open-source flag evaluation engine |
| [LaunchDarkly](https://launchdarkly.com/) | `"dev.openfeature.contrib.providers" % "launchdarkly" % "1.1.0"` | Enterprise feature management |
| [Flagsmith](https://flagsmith.com/) | `"dev.openfeature.contrib.providers" % "flagsmith" % "0.1.0"` | Open-source feature flags |
| [Flipt](https://flipt.io/) | `"dev.openfeature.contrib.providers" % "flipt" % "0.2.0"` | Open-source feature flags |
| [Statsig](https://statsig.com/) | `"dev.openfeature.contrib.providers" % "statsig" % "0.2.1"` | Feature gates and experiments |
| [Unleash](https://www.getunleash.io/) | `"dev.openfeature.contrib.providers" % "unleash" % "0.1.3"` | Open-source feature management |
| [ConfigCat](https://configcat.com/) | `"dev.openfeature.contrib.providers" % "configcat" % "0.1.0"` | Feature flags for teams |
| [Go Feature Flag](https://gofeatureflag.org/) | `"dev.openfeature.contrib.providers" % "go-feature-flag" % "0.3.0"` | Simple feature flag solution |

Check [Maven Central](https://central.sonatype.com/search?q=g:dev.openfeature.contrib.providers) for the latest versions.

---

## Using flagd

[flagd](https://flagd.dev/) is an open-source, cloud-native feature flag evaluation engine.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>",
  "dev.openfeature.contrib.providers" % "flagd" % "0.8.9"
)
```

### Setup

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider
import dev.openfeature.contrib.providers.flagd.FlagdOptions

object MyApp extends ZIOAppDefault:

  // Default: connects to localhost:8013
  val provider = new FlagdProvider()

  // Or with custom options
  val customProvider = new FlagdProvider(
    FlagdOptions.builder()
      .host("flagd.example.com")
      .port(8013)
      .tls(true)
      .build()
  )

  val program = for
    enabled <- FeatureFlags.boolean("new-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(provider)
  )
```

---

## Using LaunchDarkly

[LaunchDarkly](https://launchdarkly.com/) is an enterprise feature management platform.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>",
  "dev.openfeature.contrib.providers" % "launchdarkly" % "1.1.0"
)
```

### Setup

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.launchdarkly.LaunchDarklyProvider
import dev.openfeature.contrib.providers.launchdarkly.LaunchDarklyProviderOptions

object MyApp extends ZIOAppDefault:

  val sdkKey = sys.env.getOrElse("LAUNCHDARKLY_SDK_KEY", "your-sdk-key")

  val options = LaunchDarklyProviderOptions.builder()
    .sdkKey(sdkKey)
    .build()

  val provider = new LaunchDarklyProvider(options)

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(provider)
  )
```

---

## Factory Methods

ZIO OpenFeature provides several factory methods to create the `FeatureFlags` layer:

### fromProvider

Create from any OpenFeature provider:

```scala
import dev.openfeature.sdk.FeatureProvider

val provider: FeatureProvider = new OptimizelyProvider(config)

val layer: ZLayer[Scope, Throwable, FeatureFlags] =
  FeatureFlags.fromProvider(provider)
```

### fromProvider with evaluation timeout

Create with a global evaluation timeout to prevent hung providers from blocking fibers indefinitely. If a provider evaluation takes longer than the timeout, it fails with `ProviderError` containing a `TimeoutException`.

```scala
val layer = FeatureFlags.fromProvider(provider, evaluationTimeout = 500.millis)
```

When the timeout fires, the calling fiber receives the error immediately. The underlying provider thread completes naturally in the background (it is not interrupted, avoiding potential corruption of provider internal state).

**Per-call timeout override:**

You can also set a timeout on individual evaluations via `EvaluationOptions`, which overrides the global default:

```scala
// This evaluation times out after 100ms, regardless of the global setting
val result = ff.booleanDetails(
  "flag",
  default = false,
  options = EvaluationOptions.empty.withTimeout(100.millis)
)
```

| Setting | Scope | Default |
|:--------|:------|:--------|
| `fromProvider(provider, evaluationTimeout)` | All evaluations on this instance | `None` (no timeout) |
| `EvaluationOptions.empty.withTimeout(duration)` | Single evaluation call | `None` (uses global) |

Per-call timeout takes precedence over global. If neither is set, no timeout is applied (backward compatible).

### Runtime provider replacement (hot-swap)

Replace the underlying provider at runtime without recreating the `FeatureFlags` instance. Hooks, context, and event handlers are preserved across the swap.

```scala
val ff: FeatureFlags = ...

// Start with Optimizely
ff.setProvider(optimizelyProvider)

// Later, swap to a different provider
ff.setProvider(launchDarklyProvider)
```

**Swap lifecycle:**

1. Status transitions to `NotReady` — new evaluations fail fast with `ProviderNotReady`
2. Provider and metadata refs are updated (so the event bridge sees consistent metadata)
3. The Java SDK calls `shutdown()` on the old provider and `initialize()` on the new provider (blocks until ready)
4. Status transitions to `Ready` — evaluations resume with the new provider

The swap is serialized via a semaphore — concurrent `setProvider` calls queue up safely.

**What is preserved across swaps:**

| Preserved | Details |
|:----------|:--------|
| API-level hooks | `state.hooksRef` is not touched |
| Client-level hooks | Same |
| Global context | `state.globalContextRef` unchanged |
| Client context | `state.clientContextRef` unchanged |
| Event handlers | Registered on the client, which follows provider changes automatically |
| Event bridge | Handlers read provider name dynamically — metadata reflects the new provider |
| Tracked events | `state.trackRecorder` unchanged |

**What changes:**

| Changed | Details |
|:--------|:--------|
| Provider-level hooks | `getProviderHooks` reads from `providerRef` — automatically uses the new provider's hooks |
| Provider metadata | `providerMetadata` returns the new provider's name |
| Evaluations | Served by the new provider |

**In-flight evaluations** during a swap complete against the old provider (the Java SDK does not interrupt running calls). New evaluations that start during the swap fail with `ProviderNotReady`.

**Error handling:**

If the new provider fails to initialize, `setProvider` returns `FeatureFlagError.ProviderInitializationFailed` and the status transitions to `Error` (not `NotReady`). The old provider has already been shut down by the Java SDK. To recover, call `setProvider` again with a working provider:

```scala
val result = ff.setProvider(unreliableProvider).either
result match {
  case Left(_: FeatureFlagError.ProviderInitializationFailed) =>
    // Old provider is gone, new one failed — recover with a fallback
    ff.setProvider(envVarFallbackProvider)
  case Left(other) => ZIO.fail(other)
  case Right(_)    => ZIO.unit
}
```

**Using with CircuitBreakerProvider:**

`setProvider` replaces the entire provider registered with the Java SDK. If you're using `CircuitBreakerProvider`, swap the whole wrapper — don't try to swap just the inner delegate:

```scala
// Correct: swap the entire CB+delegate stack
val cb = CircuitBreakerProvider(newOptimizelyProvider, config)
ff.setProvider(cb)

// Wrong: the old CB's state (failure count, open/closed) is meaningless for a new delegate
```

**Service accessor:**

```scala
// Via ZIO service accessor (when FeatureFlags is in the environment)
FeatureFlags.setProvider(newProvider)
```

### fromProviderWithDomain

Create with a named domain. Each domain gets its own client, useful for segmenting feature flag configuration:

```scala
val layer = FeatureFlags.fromProviderWithDomain(provider, "my-service")
```

Optionally include a version string (useful for telemetry and debugging):

```scala
val layer = FeatureFlags.fromProviderWithDomain(provider, "my-service", "1.2.3")
```

The version is available via `clientMetadata`:

```scala
for {
  meta <- FeatureFlags.clientMetadata
  _    <- ZIO.logInfo(s"Client: ${meta.domain} version: ${meta.version}")
} yield ()
```

> For test isolation, prefer `TestFeatureProvider.layer` which automatically creates isolated API instances.

### fromProviderWithHooks

Create with initial hooks:

```scala
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)

val layer = FeatureFlags.fromProviderWithHooks(provider, hooks)
```

### fromMultiProvider

Create from multiple providers using the SDK's MultiProvider support. By default this uses the first-match strategy — the first provider whose result is not a default value is returned (a provider error from any source aborts the chain):

```scala
import dev.openfeature.sdk.FeatureProvider

val localProvider: FeatureProvider = // local overrides
val remoteProvider: FeatureProvider = // remote service

// Uses first-match strategy by default
val layer = FeatureFlags.fromMultiProvider(List(localProvider, remoteProvider))
```

You can also supply a custom strategy. The two built-in strategies are exposed via `MultiProviderStrategy` so you don't need to import the Java SDK's multiprovider package directly:

```scala
import zio.openfeature.MultiProviderStrategy

val layer = FeatureFlags.fromMultiProvider(
  List(primaryProvider, fallbackProvider),
  MultiProviderStrategy.firstSuccessful
)
```

`MultiProviderStrategy.firstMatch` and `MultiProviderStrategy.firstSuccessful` are the two built-ins. For a custom strategy, implement the `MultiProviderStrategy.Strategy` interface (an alias for the Java SDK's `Strategy`) and pass an instance the same way.

### Initialization Timeout

Every `fromProvider` and `fromProviderAsync` factory bounds initialization at **30 seconds by default**. The bound applies to both modes:

- **Sync (`fromProvider`)**: if `setProviderAndWait` takes longer than `initTimeout`, the layer build fails with a `TimeoutException`. After init returns, the library verifies the provider's actual state — anything other than `READY` / `STALE` fails the build with an `IllegalStateException`, so a wrong SDK key or unreachable endpoint surfaces at startup instead of returning default values on every evaluation.
- **Async (`fromProviderAsync`)**: a watchdog fiber forked into the layer's `Scope` sleeps `initTimeout`, then atomically transitions `ProviderStatus` from `NotReady` / `Error` to `Fatal`. Callers polling `providerStatus` stop waiting.

Override via the explicit-`initTimeout` overload:

```scala
// Lower for tests / quick-start CLIs that should fail fast on a missing flag service
FeatureFlags.fromProvider(provider, evaluationTimeout = 500.millis, initTimeout = 5.seconds)

// Raise for cold-start datafile fetches on slow networks
FeatureFlags.fromProviderAsync(provider, evaluationTimeout = 500.millis, initTimeout = 90.seconds)

// Effectively disable (not recommended in production)
FeatureFlags.fromProvider(provider, evaluationTimeout = 500.millis, initTimeout = 365.days)
```

### Async Variants (Non-Blocking Initialization)

Every factory method has an async counterpart that uses the Java SDK's non-blocking `setProvider` instead of `setProviderAndWait`. The provider initializes in the background; evaluations fail with `ProviderNotReady` until the provider is ready. The init-timeout watchdog described above still applies — after the configured `initTimeout` elapses, status transitions to `Fatal` so callers stop polling for ready.

This is useful for microservices that need fast startup and can tolerate returning default flag values during the brief initialization window.

```scala
// Non-blocking: layer is available immediately
val layer = FeatureFlags.fromProviderAsync(provider)

// With domain
val domainLayer = FeatureFlags.fromProviderWithDomainAsync(provider, "my-service")

// With domain and version
val versionedLayer = FeatureFlags.fromProviderWithDomainAsync(provider, "my-service", "1.0.0")

// With hooks
val hookedLayer = FeatureFlags.fromProviderWithHooksAsync(provider, hooks)

// Multi-provider
val multiLayer = FeatureFlags.fromMultiProviderAsync(List(provider1, provider2))
```

Use `onProviderReady` or `providerStatus` to detect when the provider becomes available:

```scala
val program = for
  // Register a handler that fires when the provider is ready
  _ <- FeatureFlags.onProviderReady { metadata =>
    ZIO.logInfo(s"Provider ${metadata.name} is ready")
  }
  // Evaluations before ready will fail with ProviderNotReady
  result <- FeatureFlags.boolean("feature", default = false).catchAll {
    case _: FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false) // Safe default while provider initializes
    case other => ZIO.fail(other)
  }
yield result

program.provide(Scope.default >>> FeatureFlags.fromProviderAsync(provider))
```

---

## Provider Registry

For applications that need multiple providers across different domains (e.g., billing, auth, analytics), `FeatureFlagRegistry` provides a centralized service that manages domain-scoped providers with automatic fallback to a default.

```scala
import zio.*
import zio.openfeature.*

val program = for
  billing <- FeatureFlagRegistry.getClient("billing")
  auth    <- FeatureFlagRegistry.getClient("auth")
  // Domains without an explicit provider fall back to the default
  other   <- FeatureFlagRegistry.getClient("analytics")
  flag    <- billing.boolean("new-pricing", default = false)
yield flag

program.provide(Scope.default >>> FeatureFlagRegistry.fromProvider(defaultProvider))
```

### Registering domain providers

Register a provider for a specific domain. If a client already exists for the domain, the provider is hot-swapped:

```scala
for
  _ <- FeatureFlagRegistry.setProvider("billing", optimizelyProvider)
  _ <- FeatureFlagRegistry.setProvider("auth", launchDarklyProvider)
  // Later, hot-swap billing to a different provider
  _ <- FeatureFlagRegistry.setProvider("billing", newOptimizelyProvider)
yield ()
```

### How it works

- **Client caching** — `getClient` returns the same `FeatureFlags` instance for a given domain. State (hooks, context, event handlers) is preserved.
- **Default fallback** — domains without an explicit provider use the default provider passed to `fromProvider`.
- **Isolated API** — the registry creates its own `OpenFeatureAPI` instance, avoiding interference with other registries or standalone `FeatureFlags` layers.
- **Lifecycle** — when the registry's scope closes, all managed providers are shut down.

---

## Provider Lifecycle

### Initialization

When you create a `FeatureFlags` layer, the provider is automatically initialized using `setProviderAndWait`. This ensures the provider is ready before any flag evaluations. The initialization is bounded by `initTimeout` (default 30 seconds) and the provider's actual state is verified after the SDK call returns — see [Initialization Timeout](#initialization-timeout) above for details.

```scala
// Provider is initialized when layer is provided (blocking, bounded by initTimeout)
program.provide(Scope.default >>> FeatureFlags.fromProvider(provider))

// Or use the async variant for non-blocking initialization
program.provide(Scope.default >>> FeatureFlags.fromProviderAsync(provider))
```

### Shutdown

When the scope ends, the OpenFeature API is automatically shut down. Shutdown resets the provider status to `NotReady`, clears hooks and contexts, shuts down the event hub (terminating all event stream subscribers), and calls the SDK's shutdown:

```scala
ZIO.scoped {
  for
    ff <- ZIO.service[FeatureFlags]
    // Use feature flags
  yield ()
}
// Provider shutdown automatically on scope exit

// Explicit shutdown is also available
FeatureFlags.shutdown
```

### Provider Status

Check if the provider is ready:

```scala
for
  status <- FeatureFlags.providerStatus
  _      <- ZIO.when(status == ProviderStatus.Ready) {
              Console.printLine("Provider is ready")
            }
yield ()
```

### Provider Metadata

Get information about the current provider:

```scala
for
  metadata <- FeatureFlags.providerMetadata
  _        <- Console.printLine(s"Provider: ${metadata.name}")
yield ()
```

---

## Provider Events

Java SDK provider events are automatically bridged to the ZIO event system. When a provider emits events (Ready, Error, Stale, ConfigurationChanged), they are captured via Java SDK event listeners and published to the ZIO event hub. The provider status is also kept in sync automatically. Event bridge handlers are registered during layer creation and cleaned up when the scope ends.

ZIO OpenFeature provides two ways to consume provider events: event handlers and event streams.

### Event Handlers

Register handlers for specific event types. Handlers return a cancellation effect per OpenFeature spec 5.2.7:

```scala
// Handler for ready events
val cancelReady = FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo(s"Provider ${metadata.name} is ready")
}

// Handler for error events
val cancelError = FeatureFlags.onProviderError { (error, metadata) =>
  ZIO.logError(s"Provider ${metadata.name} error: ${error.getMessage}")
}

// Handler for stale events
val cancelStale = FeatureFlags.onProviderStale { (reason, metadata) =>
  ZIO.logWarning(s"Provider ${metadata.name} stale: $reason")
}

// Handler for configuration changed events
val cancelConfig = FeatureFlags.onConfigurationChanged { (flags, metadata) =>
  ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}")
}

// Generic handler for any event type
val cancelGeneric = FeatureFlags.on(ProviderEventType.Ready, event =>
  ZIO.logInfo(s"Event: $event")
)

// Cancel handler when no longer needed
cancelReady.flatMap(cancel => cancel)
```

Per OpenFeature spec 5.3.3, handlers run immediately if the provider is already in the matching state:

```scala
// If provider is already ready, this handler runs immediately
FeatureFlags.onProviderReady { metadata =>
  ZIO.logInfo("Provider ready!")  // Runs right away if already ready
}
```

### Event Stream

For reactive event processing, use the ZStream:

```scala
val eventHandler = FeatureFlags.events.foreach { event =>
  event match
    case ProviderEvent.Ready(meta, _) =>
      ZIO.logInfo(s"Provider ${meta.name} is ready")
    case ProviderEvent.ConfigurationChanged(flags, meta, eventMeta) =>
      ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}") *>
        ZIO.logDebug(s"Event metadata: $eventMeta")
    case ProviderEvent.Stale(reason, meta, _) =>
      ZIO.logWarning(s"Provider data stale: $reason")
    case ProviderEvent.Error(error, meta, errorCode, errorMessage, _) =>
      ZIO.logError(s"Provider error: ${errorMessage.getOrElse(error.getMessage)}")
    case ProviderEvent.Reconnecting(meta, _) =>
      ZIO.logInfo(s"Provider ${meta.name} reconnecting...")
}

// Run event handler in background
eventHandler.fork
```

> **Event Metadata:** All provider events carry an optional `eventMetadata: FlagMetadata` field
> (defaults to `FlagMetadata.empty`). Providers can attach arbitrary metadata to events — for
> example, diagnostic info or the source of a configuration change. Access it via the `eventMeta`
> extension method: `event.eventMeta.getString("source")`.

---

## Testing

For testing, use `TestFeatureProvider` from the testkit module:

```scala
import zio.openfeature.testkit.*

val testLayer = TestFeatureProvider.layer(Map(
  "feature-a" -> true,
  "feature-b" -> "variant-1",
  "max-items" -> 50
))

val testProgram = for
  a <- FeatureFlags.boolean("feature-a", false)
  b <- FeatureFlags.string("feature-b", "control")
  n <- FeatureFlags.int("max-items", 10)
yield (a, b, n)  // (true, "variant-1", 50)

testProgram.provide(Scope.default >>> testLayer)
```

See [Testkit]({{ site.baseurl }}/testkit) for more testing utilities.

---

## Best Practices

### 1. Use Environment Variables for Credentials

```scala
val sdkKey = sys.env.getOrElse("OPTIMIZELY_SDK_KEY", "fallback-key")
```

### 2. Initialize Early

Create the FeatureFlags layer at application startup to ensure providers are ready before flag evaluations.

### 3. Handle Errors Gracefully

```scala
FeatureFlags.boolean("feature", false)
  .catchAll {
    case _: FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false)  // Safe default
    case FeatureFlagError.ProviderError(cause) =>
      ZIO.logError(s"Provider error: $cause") *> ZIO.succeed(false)
    case _ =>
      ZIO.succeed(false)
  }
```

### 4. Provide User Context

Always include a targeting key for consistent user experiences:

```scala
val ctx = EvaluationContext(userId)
  .withAttribute("plan", userPlan)

FeatureFlags.boolean("feature", false, ctx)
```

### 5. Use Testkit Layers for Isolation

`TestFeatureProvider.layer` creates an isolated API instance per test — no manual domain management needed:

```scala
test("my test") {
  for result <- FeatureFlags.boolean("flag", false)
  yield assertTrue(result == true)
}.provide(Scope.default >>> TestFeatureProvider.layer(Map("flag" -> true)))
```

