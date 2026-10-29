# Lifecycle and events

A provider starts, becomes ready, may go stale or fail, and is shut down. `FeatureFlags` tracks this as a status,
uses the status to decide whether an evaluation may run, and publishes the provider's events to your code.

![Provider status: the provider starts NotReady and evaluations fail; a READY event makes it Ready; STALE and ERROR events move it to Stale or Error, where evaluations still run, and STALE also leads from Error to Stale; READY brings it back; shutdown passes through ShuttingDown to NotReady. Fatal, reached by an init timeout before the first Ready or a fatal error, fails every evaluation until setProvider installs a new provider.](assets/lifecycle.svg)

## Statuses

| `ProviderStatus` | An evaluation | Entered by |
|:--|:--|:--|
| `NotReady` | fails with `ProviderNotReady(NotReady)` | start, a `setProvider` swap in progress, or shutdown complete |
| `Ready` | runs | a `READY` event |
| `Stale` | runs | a `STALE` event, or the SDK reporting `STALE` for the provider, from `NotReady`, `Ready` or `Error` |
| `Error` | runs: the provider usually serves its last good values or answers with an error code | an `ERROR` event |
| `Fatal` | fails with `ProviderFatal` | an `ERROR` event with code `ProviderFatal`, or `initTimeout` passing before the provider was ever ready |
| `ShuttingDown` | fails with `ProviderNotReady(ShuttingDown)` | `shutdown` in progress |

- `Fatal` and `ShuttingDown` ignore both `READY` and `ERROR`. `Fatal` stays until
  [`setProvider`](providers.md#replace-the-provider-at-runtime) installs a new provider.
- Events that arrive during a swap or after a shutdown do not change the status.
- A `READY` event counts only once the Java SDK itself reports the provider ready; the SDK can send one while a
  provider shared with another domain is still initializing. A client registered on a provider that is already
  initialized, or that already failed, takes that state at once, since no event follows.
- The Java SDK delivers a provider's events with no ordering between them, so an `ERROR` followed quickly by a
  `READY` can reach this library in either order. Each `READY`, `STALE` or `ERROR` therefore applies the state the
  SDK reports for the provider when the event is handled, not the event's own type, within the rules above (`Fatal`
  final). An `ERROR` with code `ProviderFatal` always makes the status `Fatal`. Any other event handled while the
  SDK still reports `NOT_READY` for the provider changes nothing: it comes from another registration, such as a replaced
  provider with the same name. Either way the event itself reaches your handlers as sent.
- `status.canEvaluate` is true for `Ready` and `Stale`; `status.isRecoverable` is false only for `Fatal`.
- [`fromAcquireAsync`](providers.md#start-on-a-fallback-fromacquireasync) never changes the status when it switches from
  its fallback to the real provider. How the start works, and how long it may take, is set on the
  [factory](providers.md#sync-or-async-start).

## Wait until the provider is ready

`awaitReady(within)` waits until the status is `Ready` or `Stale`, and gives up after `within`. It returns the status at
that moment, so it never fails. It also returns early on `Fatal`, and on an instance that is shutting down or shut
down. It does not poll, and any number of fibers can wait at once.

```scala
--8<-- "Lifecycle.scala:readiness"
```

!!! warning
    With `fromAcquireAsync`, `providerStatus` reads `Ready` from the start, so `awaitReady` is the wrong probe. Use
    `AcquireStatus` (see [Start on a fallback](providers.md#start-on-a-fallback-fromacquireasync)).

With an [async start](providers.md#sync-or-async-start), evaluations fail with `ProviderNotReady` until the provider is
ready. Serve the default meanwhile (the `*OrDefault` calls do this for every error, see
[Evaluating flags](evaluating.md)):

```scala
--8<-- "Lifecycle.scala:not-ready"
```

`providerMetadata` and `clientMetadata` return the provider's `name` and the client's `domain` and `version`.

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
  you register may reach the handler twice: make handlers idempotent.
- When the status already matches, the handler gets a payload that describes the current state, not the event that
  caused it.
- A handler that dies is logged, and keeps its subscription.
- A handler does not run inside the transaction or `withContext` block it was registered from: its evaluations see
  neither the transaction's overrides nor that fiber-local context.

!!! tip "Scope a handler"
    A handler keeps running until you run its canceller or the instance shuts down. To tie it to a scope, hand the
    canceller to `acquireRelease`; the same shape works for every `on*` method.

    ```scala
    --8<-- "Lifecycle.scala:scoped-handler"
    ```

## Read the event stream

`FeatureFlags.events` is a `ZStream` of every event. It ends when the instance shuts down.

```scala
--8<-- "Lifecycle.scala:events"
```

| `ProviderEvent` (`eventType`) | Fields |
|:--|:--|
| `Ready` | `providerMetadata`, `eventMetadata` |
| `Error` | `error`, `providerMetadata`, `errorCode`, `errorMessage`, `eventMetadata` |
| `Stale` | `reason`, `providerMetadata`, `eventMetadata` |
| `ConfigurationChanged` | `changedFlags`, `providerMetadata`, `eventMetadata` |
| `Reconnecting` [^1] | `providerMetadata`, `eventMetadata` |

Every event has `eventType` (a `ProviderEventType` with the same five cases), `metadata`, `eventMeta` (the provider's
extra `FlagMetadata`), and `isError` / `isHealthy`.

[^1]: The specification defines `Reconnecting` for client-side providers. The Java SDK has no such event, so a
    server-side provider never sends it.

The Java SDK sends `READY` or `ERROR` when any provider's initialization finishes. Beyond that, the
[Optimizely](optimizely.md) provider and the [Extras](extras.md) `CircuitBreakerProvider` send their own events (the
breaker and `CachingProvider` also pass on the wrapped provider's), and the [testkit](testkit.md) provider sends what a test emits with `emitEvent`. The HOCON,
environment-variable, deferred and long-widening providers send none.

## Shut down

```scala
--8<-- "Lifecycle.scala:shutdown"
```

Shutdown moves the status to `ShuttingDown` and then `NotReady`, and evaluations fail from then on. It clears the
client and ZIO API hooks and the global and client context, and ends the event stream and every handler. Whether it
also stops the provider depends on [who owns the API](providers.md#who-shuts-down-the-api).

A shutdown called while a [provider swap](providers.md#replace-the-provider-at-runtime) is in flight waits for that
swap to finish first, and for any swap already queued behind it, each bounded by its own `initTimeout`, and then runs.
The status ends `NotReady` whatever the swap's outcome. Interrupting a shutdown that is still waiting cancels it
before it has changed anything. An instance that owns its API also shuts down a provider that a
[timed-out swap](providers.md#replace-the-provider-at-runtime) replaced, if the swap's new provider has not become
ready by then.
