# Lifecycle and events

A provider starts, becomes ready, may go stale or fail, and is shut down. `FeatureFlags` tracks this as a status,
uses the status to decide whether an evaluation may run, and publishes the provider's events to your code.

![Provider status: the provider starts NotReady and evaluations fail; a READY event makes it Ready; STALE and ERROR events move it to Stale or Error, where evaluations still run; READY brings it back; shutdown passes through ShuttingDown to NotReady. Fatal, reached by an init timeout before the first Ready or a fatal error, fails every evaluation until setProvider installs a new provider.](assets/lifecycle.svg)

## Statuses

| `ProviderStatus` | Meaning | An evaluation |
|:--|:--|:--|
| `NotReady` | Starting, being swapped, or shut down | fails with `ProviderNotReady(NotReady)` |
| `Ready` | Serving | runs |
| `Stale` | Serving data that may be out of date | runs |
| `Error` | The provider reported an error it may recover from | runs |
| `Fatal` | The provider cannot recover | fails with `ProviderFatal` |
| `ShuttingDown` | `shutdown` is in progress | fails with `ProviderNotReady(ShuttingDown)` |

`Error` still evaluates on purpose: providers in that state usually keep serving their last good values, or answer
with an error code of their own. `status.canEvaluate` is true for `Ready` and `Stale`, and `status.isRecoverable` is
false only for `Fatal`.

## What changes the status

| Cause | Change |
|:--|:--|
| The provider reports `READY` | `NotReady`, `Stale` or `Error` → `Ready` |
| The provider reports `STALE` | `Ready` → `Stale` |
| The provider reports `ERROR` | `NotReady`, `Ready` or `Stale` → `Error` |
| The provider reports `ERROR` with code `ProviderFatal` | any status except `ShuttingDown` → `Fatal` |
| Async start: `initTimeout` passes before the provider was ever ready | `NotReady` or `Error` → `Fatal` |
| [`setProvider`](providers.md#replace-the-provider-at-runtime) | any → `NotReady` while it runs, then `Ready` (`Error` if neither the new nor the old provider can serve, or if the new provider is still initializing at `initTimeout`) |
| `shutdown` | any → `ShuttingDown` → `NotReady` |

`Fatal` stays until `setProvider` installs a new provider. Events that arrive during a swap, or after a shutdown, do
not change the status. How the start works, and how long it may take, is set on the
[factory](providers.md#sync-or-async-start).

## Wait until the provider is ready

`awaitReady(within)` waits until the status is `Ready` or `Stale`, stops early on `Fatal`, and gives up after `within`.
It returns the status at that moment, so it never fails. It does not poll, and any number of fibers can wait at once.

```scala
--8<-- "Lifecycle.scala:readiness"
```

## Answer while the provider is not ready

With an [async start](providers.md#sync-or-async-start), evaluations fail with `ProviderNotReady` until the provider is
ready. Serve the default meanwhile:

```scala
--8<-- "Lifecycle.scala:not-ready"
```

The `*OrDefault` calls do this for every error, see [Evaluating flags](evaluating.md).

## React to events

Register a handler for the events you care about. Each registration returns an effect that unsubscribes it:

```scala
--8<-- "Lifecycle.scala:handlers"
```

| Handler | Called with | Also called at once if the status is |
|:--|:--|:--|
| `onProviderReady` | `ProviderMetadata` | `Ready` |
| `onProviderError` | the error and `ProviderMetadata` | `Error` or `Fatal` |
| `onProviderStale` | the reason and `ProviderMetadata` | `Stale` |
| `onConfigurationChanged` | the changed flag keys and `ProviderMetadata` | never |
| `on(eventType, handler)` | the whole `ProviderEvent` | as the matching handler above |

- The subscription is in place before registration returns, so no later event is missed. An event that arrives while
  you register may reach the handler twice. Make handlers idempotent.
- A handler that dies is logged, and keeps its subscription.
- When the status already matches, the handler gets a payload that describes the current state, not the event that
  caused it.

## Read the event stream

`FeatureFlags.events` is a `ZStream` of every event. It ends when the instance shuts down.

```scala
--8<-- "Lifecycle.scala:events"
```

| `ProviderEvent` | Fields |
|:--|:--|
| `Ready` | `providerMetadata`, `eventMetadata` |
| `Error` | `error`, `providerMetadata`, `errorCode`, `errorMessage`, `eventMetadata` |
| `Stale` | `reason`, `providerMetadata`, `eventMetadata` |
| `ConfigurationChanged` | `changedFlags`, `providerMetadata`, `eventMetadata` |
| `Reconnecting` | `providerMetadata`, `eventMetadata` |

Every event has `eventType` (a `ProviderEventType`), `metadata` and `eventMeta` (the provider's extra
`FlagMetadata`), and `isError` / `isHealthy`.

!!! note
    `Reconnecting` exists for completeness with the specification, which defines it for client-side providers. The
    Java SDK has no such event, so a server-side provider never sends it.

### Which bundled providers send events

| Provider | Sends |
|:--|:--|
| `OptimizelyFeatureProvider`, from `OptimizelyProvider.make` ([Optimizely](optimizely.md)) | `READY` and `STALE` when its datafile goes stale and recovers, `CONFIGURATION_CHANGED` on a new datafile |
| `CircuitBreakerProvider` ([Extras](extras.md)) | its own `READY` and `STALE` when the breaker changes, plus the wrapped provider's events if it sends any |
| `CachingProvider` ([Extras](extras.md)) | the wrapped provider's events, if it sends any |
| `TestFeatureProvider` ([Testkit](testkit.md)) | whatever the test emits with `emitEvent` |
| `HoconProvider`, `EnvVarProvider`, `DeferredProvider`, `IntegerWideningLongProvider` | nothing |

The Java SDK itself sends `READY` or `ERROR` when a provider's initialization finishes, for every provider.

## Track user actions

`track` sends an event, such as a conversion, to the provider, for experiments and analytics:

```scala
--8<-- "Lifecycle.scala:tracking"
```

| Call | Sends |
|:--|:--|
| `track(name)` | the event name |
| `track(name, context)` | the event name and a targeting context |
| `track(name, details)` | the event name and `TrackingEventDetails` |
| `track(name, context, details)` | all three |

The context is merged with the global, client and ambient context exactly as for an evaluation (see
[Evaluation context](context.md)). `TrackingEventDetails` has an optional numeric `value` and `attributes`. A provider
that does not support tracking ignores the call. A provider error fails the call with a `FeatureFlagError`.

`trackedEvents` returns the last 1000 events this instance sent, oldest first, with their merged context. It is meant
for tests and debugging.

## Shut down

```scala
--8<-- "Lifecycle.scala:shutdown"
```

Shutdown moves the status to `ShuttingDown` and then `NotReady`, and evaluations fail from then on. It clears the
client hooks and ZIO API-level hooks, the global and client context and the tracked events, and ends the event stream and every
handler. Whether it also stops the provider depends on
[who owns the API](providers.md#who-shuts-down-the-api).
