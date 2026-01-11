---
layout: default
title: Providers
nav_order: 7
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

- **TestFeatureProvider** - For testing (see [Testkit]({{ site.baseurl }}/testkit))
- **OptimizelyProvider** - For Optimizely Feature Experimentation

## Optimizely Provider

### Installation

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "0.1.0-SNAPSHOT"
```

### Basic Setup

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.provider.optimizely.*
import com.optimizely.ab.Optimizely

val optimizelyClient = Optimizely.builder()
  .withDatafile(datafileJson)
  .build()

val program = for
  flags <- ZIO.service[FeatureFlags]
  _     <- flags.initialize
  // Use feature flags...
yield ()

program.provide(
  FeatureFlags.live,
  OptimizelyProvider.layer(optimizelyClient)
)
```

### Scoped Provider

For automatic cleanup, use the scoped layer:

```scala
val program = for
  flags <- ZIO.service[FeatureFlags]
  // Provider is automatically initialized and shutdown
yield ()

program.provide(
  FeatureFlags.live,
  OptimizelyProvider.scopedLayer(optimizelyClient)
)
```

### Using with SDK Key

```scala
import com.optimizely.ab.Optimizely
import com.optimizely.ab.OptimizelyFactory

val client = OptimizelyFactory.newDefaultInstance("YOUR_SDK_KEY")

program.provide(
  FeatureFlags.live,
  OptimizelyProvider.layer(client)
)
```

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

  def resolveStringValue(
    key: String,
    defaultValue: String,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[String]] =
    // Similar implementation...

  def resolveIntValue(
    key: String,
    defaultValue: Int,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    // Similar implementation...

  def resolveDoubleValue(
    key: String,
    defaultValue: Double,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    // Similar implementation...

  def resolveObjectValue(
    key: String,
    defaultValue: Map[String, Any],
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    // Similar implementation...

  private def loadConfiguration: Task[Unit] =
    // Load from your configuration source
    ZIO.unit
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

### Resolution Metadata

```scala
val resolution = FlagResolution(
  value = true,
  variant = Some("control"),
  reason = ResolutionReason.TargetingMatch,
  metadata = FlagMetadata("source" -> "remote", "version" -> "1.2.3"),
  flagKey = "my-flag",
  errorCode = None,
  errorMessage = None
)
```

## Provider Events

Providers should emit events to notify the system of state changes:

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

// Provider is reconnecting
eventsHub.publish(ProviderEvent.Reconnecting(metadata))
```

## Error Handling

Providers should return appropriate errors:

```scala
// Flag not found
ZIO.fail(FeatureFlagError.FlagNotFound(key))

// Type mismatch
ZIO.fail(FeatureFlagError.TypeMismatch(key, "Boolean", "String"))

// Parse error
ZIO.fail(FeatureFlagError.ParseError(key, parseException))

// Provider error
ZIO.fail(FeatureFlagError.ProviderError(underlyingException))
```
