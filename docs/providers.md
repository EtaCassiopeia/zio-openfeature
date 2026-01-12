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

---

## Using Optimizely

[Optimizely](https://www.optimizely.com/) is the recommended provider for most users. It provides feature flags, A/B testing, and experimentation capabilities.

### Installation

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core" % "0.2.0",
  "dev.openfeature.contrib.providers" % "optimizely" % "0.1.0"
)

// Optional: for flat flags support
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-contrib-optimizely" % "0.2.0"
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

### Flat Flags (contrib-optimizely)

For Optimizely features using a single variable named `_`, use the flat flags extension:

```scala
import zio.openfeature.contrib.optimizely.*

val program = for
  ff       <- ZIO.service[FeatureFlags]
  discount <- ff.flatDouble("discount-rate", 0.0)
  limit    <- ff.flatInt("max-items", 10)
yield (discount, limit)
```

See [Flat Flags](#flat-flags-contrib-optimizely) section below for setup details.

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

---

## Flat Flags (contrib-optimizely)

The `contrib-optimizely` module provides extensions for Optimizely's variable pattern.

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

Using flat flags:
ff.flatDouble("discount-percentage", 0.0)  → 15.0
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
val sdkKey = sys.env.getOrElse("OPTIMIZELY_SDK_KEY", "fallback-key")
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

