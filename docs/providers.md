---
layout: default
title: Providers
nav_order: 8
---

# Providers
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Providers are the bridge between the OpenFeature API and your feature flag management system. ZIO OpenFeature includes:

- **OptimizelyProvider** - For Optimizely Feature Experimentation (recommended for production)
- **TestFeatureProvider** - For testing (see [Testkit]({{ site.baseurl }}/testkit))

---

## Optimizely Provider

The Optimizely provider integrates with [Optimizely Feature Experimentation](https://www.optimizely.com/products/feature-experimentation/) to deliver feature flags and A/B testing capabilities.

### Installation

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "0.1.0"
```

### Getting Your SDK Key

1. Log in to [Optimizely](https://app.optimizely.com)
2. Navigate to **Settings** → **Environments**
3. Copy the **SDK Key** for your environment (Development, Staging, or Production)

Each environment has its own SDK key, allowing different flag configurations per environment.

### Basic Setup

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.provider.optimizely.*

object MyApp extends ZIOAppDefault:

  // Create options with SDK key and access token
  val options = OptimizelyOptions
    .builder()
    .withSdkKey("YOUR_SDK_KEY")
    .withAccessToken("YOUR_ACCESS_TOKEN")  // Required for authenticated datafile access
    .withPollingIntervalSeconds(30)        // Poll for updates every 30 seconds
    .build()

  val program = for
    flags   <- ZIO.service[FeatureFlags]
    enabled <- flags.boolean("new-checkout-flow", false)
    _       <- ZIO.when(enabled)(ZIO.debug("New checkout flow is enabled!"))
  yield ()

  def run = program.provide(
    FeatureFlags.live,
    OptimizelyProvider.layerFromOptions(options)
  )
```

### Configuration Options

Use `OptimizelyOptions` to configure the provider:

```scala
import zio.openfeature.provider.optimizely.*
import scala.concurrent.duration.*

val options = OptimizelyOptions(
  sdkKey = "YOUR_SDK_KEY",
  accessToken = Some("YOUR_ACCESS_TOKEN"),  // For authenticated datafile access
  pollingInterval = 30.seconds,              // How often to poll for updates
  blockingTimeout = 10.seconds               // Initial datafile fetch timeout
)

// Or use the builder
val optionsFromBuilder = OptimizelyOptions
  .builder()
  .withSdkKey("YOUR_SDK_KEY")
  .withAccessToken("YOUR_ACCESS_TOKEN")
  .withPollingIntervalSeconds(30)
  .build()
```

### Initialization Methods

#### Using OptimizelyOptions (Recommended)

```scala
// Simple: SDK key only (public datafile)
val provider1 = OptimizelyProvider.fromSdkKey("YOUR_SDK_KEY")

// With access token (authenticated datafile)
val provider2 = OptimizelyProvider.fromSdkKey("YOUR_SDK_KEY", "YOUR_ACCESS_TOKEN")

// Full options
val options = OptimizelyOptions
  .builder()
  .withSdkKey("YOUR_SDK_KEY")
  .withAccessToken("YOUR_ACCESS_TOKEN")
  .withPollingIntervalSeconds(30)
  .build()

val provider3 = OptimizelyProvider.fromOptions(options)
```

#### Using Pre-built Client

For advanced scenarios, you can create the Optimizely client manually:

```scala
import com.optimizely.ab.Optimizely

val datafileJson = """{"version": "4", ...}"""

val client = Optimizely.builder()
  .withDatafile(datafileJson)
  .build()

val provider = OptimizelyProvider.layer(client)
```

### Scoped Provider (Recommended)

For automatic initialization and cleanup, use the scoped layer:

```scala
val program = for
  flags   <- ZIO.service[FeatureFlags]
  // Provider is automatically initialized
  enabled <- flags.boolean("feature-x", false)
yield enabled
// Provider is automatically shutdown when scope ends

ZIO.scoped {
  program.provide(
    FeatureFlags.live,
    OptimizelyProvider.scopedLayer(optimizelyClient)
  )
}
```

### User Targeting

Optimizely evaluates flags based on user attributes. Use `EvaluationContext` to pass user information:

```scala
// Create context with user ID (targeting key)
val userContext = EvaluationContext("user-12345")
  .withAttribute("email", "user@example.com")
  .withAttribute("plan", "premium")
  .withAttribute("country", "US")
  .withAttribute("age", 28)
  .withAttribute("beta_user", true)

// Evaluate with user context
val result = for
  flags <- ZIO.service[FeatureFlags]
  value <- flags.boolean("premium-feature", false, userContext)
yield value
```

#### Setting Global Context

For attributes that apply to all evaluations (e.g., app version, environment):

```scala
val globalContext = EvaluationContext.empty
  .withAttribute("app_version", "2.1.0")
  .withAttribute("platform", "ios")

FeatureFlags.setGlobalContext(globalContext)
```

#### Setting Fiber-Local Context

For request-scoped attributes, use `withContext` to scope context to a block:

```scala
val requestContext = EvaluationContext("user-123")
  .withAttribute("session_id", sessionId)

FeatureFlags.withContext(requestContext) {
  // All evaluations in this block use requestContext
  FeatureFlags.boolean("feature", false)
}
```

### Feature Types

#### Boolean Flags (Feature Toggles)

```scala
// Simple on/off feature
val isEnabled = FeatureFlags.boolean("new-dashboard", false)

// With user targeting
val isEnabledForUser = FeatureFlags.boolean("new-dashboard", false, userContext)
```

#### String Flags (Variations)

```scala
// Get variation key for A/B tests
val variation = FeatureFlags.string("checkout-button-color", "blue", userContext)

// Use the variation
variation.flatMap {
  case "blue"  => renderBlueButton
  case "green" => renderGreenButton
  case "red"   => renderRedButton
  case _       => renderDefaultButton
}
```

#### Numeric Flags (Feature Variables)

```scala
// Integer variables
val maxItems = FeatureFlags.int("cart-max-items", 10, userContext)

// Double variables
val discountRate = FeatureFlags.double("discount-percentage", 0.0, userContext)
```

#### JSON/Object Flags

```scala
// Complex configuration as JSON
val config = FeatureFlags.obj(
  "feature-config",
  Map("timeout" -> 30, "retries" -> 3),
  userContext
)
```

### Complete Example Application

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.provider.optimizely.*

object FeatureFlagApp extends ZIOAppDefault:

  // Configuration from environment variables
  val options = OptimizelyOptions
    .builder()
    .withSdkKey(sys.env.getOrElse("OPTIMIZELY_SDK_KEY", "YOUR_SDK_KEY"))
    .withAccessToken(sys.env.getOrElse("OPTIMIZELY_ACCESS_TOKEN", "YOUR_TOKEN"))
    .withPollingIntervalSeconds(30)
    .build()

  // Simulate a user request
  def handleUserRequest(userId: String, plan: String) = for
    flags <- ZIO.service[FeatureFlags]

    // Create user context
    ctx = EvaluationContext(userId)
      .withAttribute("plan", plan)
      .withAttribute("registered_at", java.time.Instant.now())

    // Check if new checkout is enabled for this user
    newCheckout <- flags.boolean("new-checkout-flow", false, ctx)

    // Get the button variation
    buttonColor <- flags.string("checkout-button", "blue", ctx)

    // Get max cart items
    maxItems <- flags.int("max-cart-items", 10, ctx)

    _ <- ZIO.debug(s"User $userId (plan: $plan):")
    _ <- ZIO.debug(s"  - New checkout: $newCheckout")
    _ <- ZIO.debug(s"  - Button color: $buttonColor")
    _ <- ZIO.debug(s"  - Max items: $maxItems")
  yield ()

  val program = for
    // Simulate different users
    _ <- handleUserRequest("user-001", "free")
    _ <- handleUserRequest("user-002", "premium")
    _ <- handleUserRequest("user-003", "enterprise")
  yield ()

  def run = program.provide(
    FeatureFlags.live,
    OptimizelyProvider.layerFromOptions(options)
  )
```

### Error Handling

```scala
import zio.openfeature.FeatureFlagError

val safeEvaluation = FeatureFlags.boolean("risky-feature", false)
  .catchAll {
    case FeatureFlagError.FlagNotFound(key) =>
      ZIO.debug(s"Flag $key not found, using default") *> ZIO.succeed(false)
    case FeatureFlagError.ProviderError(cause) =>
      ZIO.debug(s"Provider error: ${cause.getMessage}") *> ZIO.succeed(false)
    case FeatureFlagError.TypeMismatch(key, expected, actual) =>
      ZIO.debug(s"Type mismatch for $key") *> ZIO.succeed(false)
    case other =>
      ZIO.debug(s"Unexpected error: $other") *> ZIO.succeed(false)
  }
```

### Flat Flags

OpenFeature supports non-boolean flag values (String, Int, Double, Object), but Optimizely flags are fundamentally boolean. To bridge this gap, the provider supports "flat" flags:

A **flat flag** is an Optimizely feature with a single variable named `_` that holds the non-boolean value.

**Example in Optimizely:**
- Feature key: `discount-percentage`
- Variable name: `_`
- Variable type: `Double`
- Variable value: `15.0`

**Usage in code:**
```scala
// Returns 15.0 from the "_" variable
val discount = FeatureFlags.double("discount-percentage", 0.0, ctx)
```

This convention ensures consistent mapping between OpenFeature's type system and Optimizely's feature model.

### Resolution Metadata

When a flag is successfully evaluated, the resolution includes metadata from Optimizely:

```scala
val resolution = FeatureFlags.booleanDetails("my-feature", false)

resolution.map { res =>
  println(s"Value: ${res.value}")
  println(s"Variant: ${res.variant}")           // e.g., "control" or "treatment"
  println(s"Rule Key: ${res.metadata.get("ruleKey")}")  // Optimizely rule that matched
  println(s"Reason: ${res.reason}")             // e.g., TargetingMatch
}
```

The `ruleKey` in metadata identifies which Optimizely targeting rule was applied, useful for debugging and analytics.

### Best Practices

1. **Use Environment Variables for SDK Keys**
   ```scala
   val sdkKey = sys.env.getOrElse("OPTIMIZELY_SDK_KEY", "fallback-key")
   ```

2. **Initialize Early**: Initialize the provider at application startup to ensure the datafile is fetched before flag evaluations.

3. **Use Scoped Layer**: Prefer `scopedLayer` for automatic resource management.

4. **Provide User Context**: Always include a targeting key (user ID) for consistent user experiences and accurate experiment results.

5. **Handle Defaults Gracefully**: Choose sensible default values that provide a safe fallback experience.

6. **Use Typed Methods**: Use `boolean`, `string`, `int`, `double` methods instead of generic `value[A]` for type safety.

### Optimizely Dashboard Setup

To use this provider, configure your flags in Optimizely:

1. **Create a Feature Flag**:
   - Go to **Features** → **Create New Feature**
   - Add a feature key (e.g., `new-checkout-flow`)
   - Configure targeting rules

2. **Add Variables** (for non-boolean values):
   - In your feature, add variables with types (string, integer, double, JSON)
   - Set default values and variation-specific values

3. **Configure Audiences**:
   - Define audiences based on user attributes
   - Target specific user segments

4. **Enable the Feature**:
   - Toggle the feature on for your environment
   - Set traffic allocation for gradual rollouts

---

## Custom Providers

### Implementing FeatureProvider

```scala
import zio.*
import zio.stream.*
import zio.openfeature.*

class MyProvider(
  configRef: Ref[Map[String, Any]],
  statusRef: Ref[ProviderStatus],
  eventsHub: Hub[ProviderEvent]
) extends FeatureProvider:

  val metadata: ProviderMetadata = ProviderMetadata("MyProvider", "1.0.0")

  def status: UIO[ProviderStatus] = statusRef.get

  def events: UStream[ProviderEvent] = ZStream.fromHub(eventsHub)

  def initialize: Task[Unit] =
    for
      _ <- loadConfiguration
      _ <- statusRef.set(ProviderStatus.Ready)
      _ <- eventsHub.publish(ProviderEvent.Ready(metadata))
    yield ()

  def shutdown: UIO[Unit] =
    for
      _ <- statusRef.set(ProviderStatus.NotReady)
      _ <- eventsHub.shutdown
    yield ()

  def resolveBooleanValue(
    key: String,
    defaultValue: Boolean,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    for
      config <- configRef.get
      result <- config.get(key) match
        case Some(v: Boolean) =>
          ZIO.succeed(FlagResolution.targetingMatch(key, v))
        case Some(_) =>
          ZIO.fail(FeatureFlagError.TypeMismatch(key, "Boolean", "other"))
        case None =>
          ZIO.succeed(FlagResolution.default(key, defaultValue))
    yield result

  // Implement other resolve methods similarly...

  private def loadConfiguration: Task[Unit] = ZIO.unit
```

### Provider Companion Object

```scala
object MyProvider:
  def make: UIO[MyProvider] =
    for
      configRef <- Ref.make(Map.empty[String, Any])
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      eventsHub <- Hub.unbounded[ProviderEvent]
    yield MyProvider(configRef, statusRef, eventsHub)

  def layer: ULayer[FeatureProvider] =
    ZLayer.fromZIO(make)

  def scoped: ZIO[Scope, Nothing, MyProvider] =
    for
      provider <- make
      _        <- provider.initialize.orDie
      _        <- ZIO.addFinalizer(provider.shutdown)
    yield provider

  def scopedLayer: ZLayer[Scope, Nothing, FeatureProvider] =
    ZLayer.fromZIO(scoped)
```

---

## Flag Resolution

### Resolution Types

```scala
// Flag found and evaluated
FlagResolution.targetingMatch(key, value)
FlagResolution.targetingMatch(key, value, Some("variant-name"))

// Flag not found, using default
FlagResolution.default(key, defaultValue)

// Value from cache
FlagResolution.cached(key, cachedValue)

// Error during evaluation
FlagResolution.error(key, defaultValue, ErrorCode.FlagNotFound, "Flag not found")
```

---

## Provider Events

Providers emit events to notify the system of state changes:

```scala
// Provider is ready
eventsHub.publish(ProviderEvent.Ready(metadata))

// Configuration changed
eventsHub.publish(ProviderEvent.ConfigurationChanged(
  changedFlags = Set("flag-1", "flag-2"),
  metadata = metadata
))

// Provider encountered an error
eventsHub.publish(ProviderEvent.Error(
  error = new Exception("Connection lost"),
  metadata = metadata
))

// Provider data is stale
eventsHub.publish(ProviderEvent.Stale(
  reason = "Cache expired",
  metadata = metadata
))
```
