# Testkit

`zio-openfeature-testkit` gives you `TestFeatureProvider`: an in-memory OpenFeature provider you control from your
tests. Set flags, flip the provider's status, emit events, inject failures, and check which flags your code read.
It runs the same evaluation path as production — hooks, context, transactions and error handling included.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

## Your first test

Say your service reads a flag:

```scala
--8<-- "TestkitSpec.scala:service"
```

Provide `TestFeatureProvider.layer` in place of the real `FeatureFlags` layer. It provides both `FeatureFlags`
and the `TestFeatureProvider` itself, so a test can set flags and then run the code under test:

```scala
--8<-- "TestkitSpec.scala:first-test"
```

Each test gets its own provider and its own isolated OpenFeature API instance, so tests can run in parallel.

## Choosing a layer

Every layer provides `TestFeatureProvider & FeatureFlags`.

| Layer | Provider starts | Use it when |
|:------|:----------------|:------------|
| `layer`, `layer(flags)` | `Ready` | most tests |
| `asyncLayer`, `asyncLayer(flags)` | `NotReady`, until you call `setStatus(Ready)` | testing how your code behaves while the provider starts up |
| `asyncReadyLayer(flags, initDelay)` | `NotReady`, then `Ready` after `initDelay` (default 100 ms) | a provider that becomes ready on its own |
| `layerFrom(provider)` | as the provider is | you built the provider yourself with `make` / `makeNamed` |

None of these needs a `Scope`: each layer owns its own and shuts the provider down when it is released.
`layer` and `layerFrom` return only after the OpenFeature SDK has delivered its initial ready event, so a
`setStatus` straight after the layer builds is not overwritten by that late event.
`scopedLayer` and `scopedAsyncLayer` are deprecated aliases of `layer` and `asyncLayer` from when layers did need one.

`flags` is a `Map[String, Any]`, or typed overrides (`Flag := value`, see below). `providerLayer` provides just a
`TestFeatureProvider`, without `FeatureFlags`.

To build a provider without a layer, use `TestFeatureProvider.make(flags)`, or `makeNamed(name, flags)` to give it
its own metadata name. A name matters when you swap providers with `FeatureFlags.setProvider`, which tells the old
and new provider apart by name. Every other factory names the provider `TestFeatureProvider.DefaultName`.

## Typed fixtures

A fixture keyed by string accepts any value, so a typo such as `"premum"` passes the test and fails in production.
Build the fixture from your [`FlagDef`s](typed-flags.md) instead, and the compiler checks the value's type:

```scala
--8<-- "TestkitSpec.scala:typed-fixture"
```

`:=` stores the value the way a provider would carry it (`flagType.encode(value)`), so the test reads it back through
the same decoding as production. It also checks that the codec can decode its own encoding, and throws at fixture
construction if it cannot.

Every factory that takes flags (`make`, `layer`, `layer`, `asyncLayer`, `asyncLayer`, `providerLayer`)
also takes `FlagOverride*`. For `asyncReadyLayer` the delay comes first: `asyncReadyLayer(100.millis, Flag := v)`.

!!! note
    Name the type parameter when the default is a case object: `FlagDef("k", Tier.Free)` infers `Tier.Free.type`
    and finds no `FlagType` for it. Write `FlagDef[Tier]("k", Tier.Free)`.

## Changing flags during a test

```scala
--8<-- "TestkitSpec.scala:managing-flags"
```

| Method | Effect |
|:-------|:-------|
| `setFlag(key, value)`, `setFlag(flagDef, value)` | set one flag |
| `setFlags(map)`, `setFlags(overrides*)` | set several, keeping the others |
| `replaceFlags(map)`, `replaceFlags(overrides*)` | drop every flag, then set these |
| `removeFlag(key)`, `removeFlag(flagDef)` | remove one flag |
| `clearFlags` | remove every flag |

### Unset flags

A key the provider does not hold resolves as `FLAG_NOT_FOUND`, as it does with real providers:

```scala
--8<-- "TestkitSpec.scala:absent-keys"
```

Hooks see the `error` stage for it, not `after`. So when a test means "this flag is off", set it to `false` instead
of leaving it unset.

Reporting `FLAG_NOT_FOUND` is also what lets a test provider sit in a [provider chain](#provider-chains).

A value of the wrong type is an error too, reported as `TYPE_MISMATCH` as a real provider reports it. The typed tier
fails with `TypeMismatch` and the total tier serves your default; neither silently returns the default as if the flag
were fine:

```scala
--8<-- "TestkitSpec.scala:wrong-type"
```

A key-based value is served only when its type is exactly the one read: `Boolean`, `String`, `Int`, `Long` or
`Double`. Nothing is converted. A `Long` read as `int` is a mismatch even when it fits, and so is an `Int` read as
`long` or `double`, a string read as a number, or any value read as `string`. Store `42L` for a `long` flag and `2.0`
for a `double` one. Typed fixtures (`setFlag(flagDef, v)`, `Flag := v`) always store the right type.

## Checking which flags were read

The provider records every evaluation that reaches it, with the context it received. That includes evaluations a
[simulated failure](#simulating-failures) made fail: the provider was asked, even though it did not answer.

```scala
--8<-- "TestkitSpec.scala:recorded"
```

| Method | Returns |
|:-------|:--------|
| `wasEvaluated(key)`, `wasEvaluated(flagDef)` | whether the flag was read |
| `evaluationCount(key)`, `evaluationCount(flagDef)` | how many times |
| `getEvaluations` | every `(key, EvaluationContext)`, oldest first |
| `getRawEvaluations` | the same, with the Java SDK's `EvaluationContext` |
| `clearEvaluations` | forgets them |
| `trackedEvents` | every `track` call received, oldest first (the last 1000): `(name, EvaluationContext, Option[TrackingEventDetails])`, with the context the SDK merged |
| `clearTrackedEvents` | forgets them |
| `boundDomain` | the domain the provider was registered under, if any |

Evaluations answered without the provider, by a [transaction](transactions.md) override or cache, are not recorded.

## Provider status

`setStatus` changes what the provider reports. While it is `NotReady` or `Fatal`, evaluations fail fast:

```scala
--8<-- "TestkitSpec.scala:status"
```

`getStatus` (or `status`) reads the current status.

### Startup

`asyncLayer` builds at once, with the provider still `NotReady`, as `FeatureFlags.fromProviderAsync` does with a slow
provider. Evaluations fail with `ProviderNotReady` until you call `setStatus(ProviderStatus.Ready)`:

```scala
--8<-- "TestkitSpec.scala:async-layer"
```

`asyncReadyLayer` becomes ready on its own after `initDelay`. The delay runs on the test clock, so advance it:

```scala
--8<-- "TestkitSpec.scala:async-ready-layer"
```

## Events

`emitEvent` sends a provider event through the OpenFeature SDK, so it reaches `FeatureFlags.events` and the
`on*` handlers as a real provider's event would:

```scala
--8<-- "TestkitSpec.scala:events"
```

Use `ProviderEvent.Ready`, `Error`, `Stale` or `ConfigurationChanged`, with `provider.metadata` as the provider's
metadata. `Reconnecting` has no OpenFeature SDK equivalent, so it does not reach `FeatureFlags`. The provider's own
`events` stream sees every event you emit.

## Simulating failures

```scala
--8<-- "TestkitSpec.scala:failures"
```

| Control | Effect |
|:--------|:-------|
| `setErrorMode(mode)` | every evaluation fails with `mode`: `FlagNotFound`, `ParseError`, `TypeMismatch`, `ProviderNotReady` or `General` |
| `setFailing(true)` | same as `setErrorMode(ErrorMode.General)`; `setFailing(false)` clears it |
| `setFailureProbability(p)` | each evaluation fails with probability `p` (clamped to 0–1) |
| `setDelay(d)` | each evaluation sleeps `d` first, on the real clock |
| `clearErrorMode`, `clearDelay`, `clearBehavior` | undo the above |

Each control has a test aspect that sets it before the test and, after it, restores the settings it found:
`TestFeatureProvider.withErrorMode(mode)`, `withFailures`, `withFailureProbability(p)` (clamped like
`setFailureProbability`) and `withDelay(d)`. Because each restores what it found rather than the defaults, aspects
nest: a test-level `withDelay` inside a suite-level `withErrorMode` keeps the error mode. They need
`TestFeatureProvider` in the environment.

An error mode applies to every key. To make one key missing, leave it unset.

## Fault injection

The controls above make evaluations fail. To test what your code does when the provider itself misbehaves, wrap it
in a `FaultyProvider`: an `initialize()` that never returns, a `shutdown()` that hangs, an error event fired while
starting up, or a failure on the first call only.

```scala
--8<-- "TestkitSpec.scala:fault-injection"
```

`FaultyProvider.script(op, faults*)` gives one `Op` a sequence of faults, used one per call. The last one repeats, so
`script(Op.Initialize, Fault.Throw(e), Fault.Succeed)` fails the first `initialize()` and passes every later one. An
op with no script calls the wrapped provider.

| `Fault` | Effect |
|:--------|:-------|
| `Succeed` | call the wrapped provider |
| `Throw(error)` | throw `error` without calling it |
| `Delay(d, andThen)` | block the calling thread for `d`, then apply `andThen` (default `Succeed`) |
| `HangUntilReleased` | block until `release`; `shutdown()` does not unblock it |
| `HangUntilShutdown` | block until `shutdown()` or `release` |
| `Emit(event, details, andThen)` | emit an OpenFeature event (e.g. `PROVIDER_ERROR` from inside `initialize()`), then apply `andThen` |

There is an `Op` for every `FeatureProvider` method: `Initialize` (both overloads), `Shutdown`, `GetMetadata`,
`GetProviderHooks`, `GetState`, `IsDomainScoped`, `Track`, and one per getter, `EvaluateBoolean` to `EvaluateObject`.

| Factory | Wraps |
|:--------|:------|
| `FaultyProvider.scoped(flags, name)` | a fresh `TestFeatureProvider`; the scope releases every hang still parked when it closes |
| `FaultyProvider.make(flags, name)` | the same, without the finalizer |
| `FaultyProvider.wrapScoped(provider, name)`, `wrap(provider, name)` | any provider |

Prefer the scoped factories: a test that fails while a call is parked then still frees the thread. `delegate` is the
wrapped provider (for `make`, the `TestFeatureProvider`, so `setFlag` works). `calls` lists every completed call with
its op, flag key, thread, start time, outcome and arguments; a hung call appears once it is released. `awaitEntered(op)` waits
until a call has started, for example until `initialize()` is parked.

The wrapper emits its own events, so the wrapped provider's events do not reach `FeatureFlags`: use `Fault.Emit` or
the wrapper's `emit`.

## Transactions

[Transactions](transactions.md) work on top of the test provider, so you can override a flag for one block:

```scala
--8<-- "TestkitSpec.scala:transactions"
```

## Provider chains

A test provider reports `FLAG_NOT_FOUND` for keys it does not hold, so a `MultiProvider` chain moves on to the next
provider:

```scala
--8<-- "TestkitSpec.scala:chain"
```

!!! warning
    `FeatureFlags.fromProvider` and the other public factories use the global OpenFeature API, so tests that use
    them directly must not run in parallel. Add `@@ TestAspect.sequential`. The testkit's own layers are isolated and
    need nothing.

## Checking a custom `FlagType`

If you write a `FlagType` by hand, check it with `FlagTypeLaws`. Give it a `Gen` for your type:

```scala
--8<-- "TestkitSpec.scala:laws"
```

`celsiusLaws` is a `Spec`; put it in a suite. `all` runs two laws:

| Law | Checks |
|:----|:-------|
| `roundTrip` | `decode(encode(a)) == Right(a)` |
| `throughValueBridge` | the same, after a trip through the OpenFeature SDK's `Value` type |

The second one finds the real problems: the SDK turns every number into a `Double`, so a `Long` above 2^53 does not
survive, and it drops structure members it cannot represent. It matters most for object-backed types, which always
take that path. Both laws work on Scala 2.13 too.

## Checking your own wrapper

A provider that wraps another one (for caching, metrics, retries) must pass every call on. The OpenFeature SDK adds
methods to `FeatureProvider` with default bodies, so a wrapper written before one of them keeps compiling and answers
it from the default instead of asking the provider it wraps: `track` events vanish, or a `Long` evaluation goes
through `getDoubleEvaluation`. `WrapperLaws.delegation` catches this. Give it a name and a function that builds your
wrapper around a provider:

```scala
--8<-- "TestkitSpec.scala:wrapper-laws"
```

The result is a suite with one test per `Op`. Each test builds your wrapper around a fresh `FaultyProvider`, calls one
method with distinctive arguments, and checks the wrapped provider received the same call with equal arguments
(contexts and tracking details are compared by content). For `Initialize` it checks both overloads, so a wrapper that
drops the domain from `initialize(ctx, domain)` fails. The law does not look at the results.

`handledByWrapper` lists the ops your wrapper answers itself, each with a reason that becomes part of the test name.
For those, the test checks the call does not reach the wrapped provider, and fails with "allow-list entry no longer
needed" if it does, so the list cannot go stale. The law runs on Scala 2.13 and 3. The library's own wrappers
(`CachingProvider`, `CircuitBreakerProvider`, `DeferredProvider`, `IntegerWideningLongProvider`,
`CachingReasonProvider`) are checked with it.

## `CachingReasonProvider`

`CachingReasonProvider` wraps any OpenFeature provider and reports the `CACHED` reason from the second evaluation of
a key on. It always calls the wrapped provider; only the reason changes. This repository uses it to run the
OpenFeature spec's `CACHED` scenario. It does not cache values: for that, see
[`CachingProvider`](extras.md).

```scala
--8<-- "TestkitSpec.scala:caching-reason"
```

## Scenario matrices with zio-bdd

To run one scenario against several flag configurations, the
[zio-bdd](https://github.com/EtaCassiopeia/zio-bdd) framework can build a `FeatureFlags` layer per `@flags(...)` tag.
Override `flagLayer` in your `ZIOSteps` suite. Tag values arrive as strings, so turn them into flag values of the
right type:

```scala
override def flagLayer(meta: ScenarioMetadata, flags: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
  TestFeatureProvider.layer(Map("new-greeting" -> flags.get("greeting").contains("new")))
```

`@flags(greeting=new, plan=pro)` runs the scenario once, with both keys in `flags`. Two `@flags(...)` tags on separate lines run it
twice, once per tag. The hook also has coarser siblings: `scenarioLayer`, `featureLayer` and `applicationLayer`.
This repository's `conformance-zio-bdd` module has working matrix suites (`FlagMatrixSpec`) that drive the
Optimizely provider this way.

To test a real provider's network code rather than its states, see [Testing real providers](testing-real-providers.md).
