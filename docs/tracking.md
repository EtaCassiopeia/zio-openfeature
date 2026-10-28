# Tracking user actions

`track` sends an event, such as a conversion, to the provider, for experiments and analytics.

```scala
--8<-- "Tracking.scala:tracking"
```

| Call | Sends |
|---|---|
| `track(name)` | the event name |
| `track(name, context)` | the event name and a targeting context |
| `track(name, details)` | the event name and `TrackingEventDetails` |
| `track(name, context, details)` | all three |

The provider receives the context merged exactly as for an evaluation (see
[Evaluation context](context.md#context-levels)). `TrackingEventDetails` has an optional numeric `value` and `attributes`. A
provider that does not support tracking ignores the call.

## Errors

- An attribute value the SDK cannot represent fails the call with `FeatureFlagError.InvalidContext` before anything is
  sent. Accepted values are the plain types `withAttribute` takes for a context, any `AttributeValue`, `Option`s of
  these, and nested `List`s and `Map`s. A `null` or `None` attribute is dropped.
- A provider error fails the call with the matching `FeatureFlagError`.

## With several providers

A [multi-provider](providers.md) chain sends every `track` call to each child, in order, whatever the strategy. One
throwing child does not starve the rest: the call fails with the first error and carries the others as suppressed.

!!! warning
    A failed `track` may already have reached some providers, so retrying it can count the event twice.

## Assert on tracking in a test

The instance does not keep tracked events: their merged contexts routinely hold personal data. Use the testkit's
`TestFeatureProvider.trackedEvents` instead; it lists what the provider received, oldest first (the last 1000). See
[Testkit](testkit.md).

```scala
--8<-- "Tracking.scala:assert"
```

`FeatureFlags.trackedEvents` is deprecated and empty outside testkit-built instances.
