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
import dev.openfeature.contrib.providers.flagd.FlagdProvider

val layer = FeatureFlags.fromProvider(new FlagdProvider())

program.provide(Scope.default >>> layer)
```

---

## Available Providers

The OpenFeature ecosystem includes providers for major feature flag services:

| Provider | Dependency | Documentation |
|:---------|:-----------|:--------------|
| [flagd](https://flagd.dev/) | `"dev.openfeature.contrib.providers" % "flagd" % "0.8.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#flagd) |
| [LaunchDarkly](https://launchdarkly.com/) | `"dev.openfeature.contrib.providers" % "launchdarkly" % "1.1.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#launchdarkly) |
| [Flagsmith](https://flagsmith.com/) | `"dev.openfeature.contrib.providers" % "flagsmith" % "0.1.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#flagsmith) |
| [CloudBees](https://www.cloudbees.com/) | `"dev.openfeature.contrib.providers" % "cloudbees" % "0.0.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#cloudbees) |
| [Flipt](https://flipt.io/) | `"dev.openfeature.contrib.providers" % "flipt" % "0.2.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#flipt) |
| [Split](https://www.split.io/) | `"dev.openfeature.contrib.providers" % "split" % "0.1.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#split) |
| [Statsig](https://statsig.com/) | `"dev.openfeature.contrib.providers" % "statsig" % "0.1.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#statsig) |
| [Optimizely](https://www.optimizely.com/) | `"dev.openfeature.contrib.providers" % "optimizely" % "0.1.x"` | [Docs](https://openfeature.dev/docs/reference/technologies/server/java/#optimizely) |

Check the [OpenFeature ecosystem](https://openfeature.dev/ecosystem?instant_search%5BrefinementList%5D%5Bvendor%5D%5B0%5D=Community&instant_search%5BrefinementList%5D%5Btechnology%5D%5B0%5D=Java) for the latest provider versions.

---

## Using flagd

[flagd](https://flagd.dev/) is an open-source, cloud-native feature flag evaluation engine.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.2.0",
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
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.2.0",
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

  val program = for
    enabled <- FeatureFlags.boolean("my-flag", default = false)
    _       <- ZIO.debug(s"Flag enabled: $enabled")
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(provider)
  )
```

---

## Using Flagsmith

[Flagsmith](https://flagsmith.com/) is an open-source feature flag and remote config service.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.2.0",
  "dev.openfeature.contrib.providers" % "flagsmith" % "0.1.0"
)
```

### Setup

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagsmith.FlagsmithProvider
import dev.openfeature.contrib.providers.flagsmith.FlagsmithProviderOptions

object MyApp extends ZIOAppDefault:

  val apiKey = sys.env.getOrElse("FLAGSMITH_API_KEY", "your-api-key")

  val options = FlagsmithProviderOptions.builder()
    .apiKey(apiKey)
    .build()

  val provider = new FlagsmithProvider(options)

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

val provider: FeatureProvider = new FlagdProvider()

val layer: ZLayer[Scope, Throwable, FeatureFlags] =
  FeatureFlags.fromProvider(provider)
```

### fromProviderWithDomain

Create with a named domain for test isolation. Each domain gets its own client:

```scala
val layer = FeatureFlags.fromProviderWithDomain(provider, "my-service")

// Useful for testing - each test can use a different domain
val testLayer = FeatureFlags.fromProviderWithDomain(testProvider, "test-domain-123")
```

### fromProviderWithHooks

Create with initial hooks:

```scala
val hooks = List(
  FeatureHook.logging(),
  FeatureHook.metrics((k, d, s) => ZIO.unit)
)

val layer = FeatureFlags.fromProviderWithHooks(provider, hooks)
```

---

## Provider Lifecycle

### Initialization

When you create a `FeatureFlags` layer, the provider is automatically initialized using `setProviderAndWait`. This ensures the provider is ready before any flag evaluations:

```scala
// Provider is initialized when layer is provided
program.provide(Scope.default >>> FeatureFlags.fromProvider(provider))
```

### Shutdown

When the scope ends, the OpenFeature API is automatically shut down:

```scala
ZIO.scoped {
  for
    ff <- ZIO.service[FeatureFlags]
    // Use feature flags
  yield ()
}
// Provider shutdown automatically on scope exit
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

Subscribe to provider lifecycle events using the event stream:

```scala
val eventHandler = FeatureFlags.events.foreach { event =>
  event match
    case ProviderEvent.Ready(meta) =>
      ZIO.logInfo(s"Provider ${meta.name} is ready")
    case ProviderEvent.ConfigurationChanged(flags, meta) =>
      ZIO.logInfo(s"Flags changed: ${flags.mkString(", ")}")
    case ProviderEvent.Stale(reason, meta) =>
      ZIO.logWarning(s"Provider data stale: $reason")
    case ProviderEvent.Error(error, meta) =>
      ZIO.logError(s"Provider error: ${error.getMessage}")
}

// Run event handler in background
eventHandler.fork
```

### Event Types

| Event | Description |
|:------|:------------|
| `Ready` | Provider initialized successfully |
| `ConfigurationChanged` | Flag definitions updated (with affected flag keys) |
| `Stale` | Provider data may be outdated |
| `Error` | Provider encountered an error |

---

## User Targeting

Pass user information using `EvaluationContext` for targeted flag evaluation:

```scala
// Create context with user ID (targeting key)
val userContext = EvaluationContext("user-12345")
  .withAttribute("email", "user@example.com")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("age", 28)
  .withAttribute("beta_user", true)

// Evaluate with user context
val result = FeatureFlags.boolean("premium-feature", false, userContext)
```

### Global Context

Set attributes that apply to all evaluations:

```scala
val globalContext = EvaluationContext.empty
  .withAttribute("app_version", "2.1.0")
  .withAttribute("environment", "production")

FeatureFlags.setGlobalContext(globalContext)
```

### Scoped Context

Apply context to a block of code:

```scala
val requestContext = EvaluationContext("user-123")
  .withAttribute("session_id", sessionId)

FeatureFlags.withContext(requestContext) {
  // All evaluations in this block use requestContext
  FeatureFlags.boolean("feature", false)
}
```

See [Evaluation Context]({{ site.baseurl }}/context) for more details.

---

## Optimizely Flat Flags

The `contrib-optimizely` module provides extensions for [Optimizely](https://www.optimizely.com/)'s variable pattern.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.2.0",
  "io.github.etacassiopeia" %% "zio-openfeature-contrib-optimizely" % "0.2.0",
  "dev.openfeature.contrib.providers" % "optimizely" % "0.1.0"
)
```

### What are Flat Flags?

Optimizely features are boolean toggles with optional variables. **Flat flags** are a convention where a feature has a single variable named `_` that holds a non-boolean value:

```
Optimizely Feature: discount-percentage
├── Enabled: true
└── Variables:
    └── _ (Double): 15.0

OpenFeature API:
FeatureFlags.double("discount-percentage", 0.0)  → 15.0
```

### Using Flat Flags

```scala
import zio.openfeature.contrib.optimizely.*

val program = for
  ff <- ZIO.service[FeatureFlags]
  // Use flat flag extensions
  discount <- ff.flatDouble("discount-rate", 0.0)
  limit    <- ff.flatInt("max-items", 10)
  label    <- ff.flatString("button-label", "Submit")
yield (discount, limit, label)
```

### Setting Up in Optimizely

1. Create a feature in Optimizely (e.g., `discount-rate`)
2. Add a variable named `_` with appropriate type (Integer, Double, String)
3. Set values per variation
4. Configure targeting rules

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
val sdkKey = sys.env.getOrElse("PROVIDER_SDK_KEY", "fallback-key")
```

### 2. Initialize Early

Create the FeatureFlags layer at application startup to ensure providers are ready before flag evaluations.

### 3. Handle Errors Gracefully

```scala
FeatureFlags.boolean("feature", false)
  .catchAll {
    case FeatureFlagError.ProviderNotReady =>
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

### 5. Use Domain Isolation in Tests

```scala
val testLayer = FeatureFlags.fromProviderWithDomain(
  testProvider,
  s"test-${java.util.UUID.randomUUID()}"
)
```

