# Testkit

`zio-openfeature-testkit` gives you `TestFeatureProvider`: an in-memory OpenFeature provider you control from your
tests. Set flags, flip the provider's status, emit events, inject failures, and check which flags your code read.
It runs the same evaluation path as production: hooks, context, transactions and error handling included.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

To test a provider you wrote, or a wrapper around one, see [Testing providers and wrappers](testing-providers.md).

## Your first test

Say your service reads a flag:

```scala
--8<-- "TestkitSpec.scala:service"
```

Provide `TestFeatureProvider.layer` in place of the real `FeatureFlags` layer. It provides both `FeatureFlags` and the
`TestFeatureProvider`, so a test can set flags and then run the code under test:

```scala
--8<-- "TestkitSpec.scala:first-test"
```

Each test gets its own provider and its own isolated OpenFeature API instance, so tests can run in parallel.

!!! warning
    `FeatureFlags.fromProvider` and the other public factories use the global OpenFeature API, so tests that call them
    directly must not run in parallel: add `@@ TestAspect.sequential`. The testkit's own layers are isolated and need
    nothing. With `provideShared`, tests share one provider, so flag aspects on tests that run at the same time race
    each other; add `@@ TestAspect.sequential` there too.

## Choosing a layer

Every layer provides `TestFeatureProvider & FeatureFlags`.

| Layer | Provider starts | Use it when |
|:------|:----------------|:------------|
| `layer`, `layer(flags)` | `Ready` | most tests |
| `asyncLayer`, `asyncLayer(flags)` | `NotReady`, until you call `setStatus(Ready)` | testing how your code behaves while the provider starts up |
| `asyncReadyLayer(flags, initDelay)` | `NotReady`, then `Ready` after `initDelay` (default 100 ms) | a provider that becomes ready on its own |
| `layerFrom(provider)` | as the provider is | you built the provider yourself with `make` / `makeNamed` |

None of these needs a `Scope`: each layer owns its own and shuts the provider down when it is released. `providerLayer`
provides just a `TestFeatureProvider`, without `FeatureFlags`.

`flags` is a `Map[String, Any]`, or typed overrides (`Flag := value`, below). To build a provider without a layer, use
`TestFeatureProvider.make(flags)`, or `makeNamed(name, flags)` to give it its own metadata name. A name matters when you
swap providers with `FeatureFlags.setProvider`, which tells the old and new provider apart by name. Every other factory
uses `TestFeatureProvider.DefaultName`.

??? note "Deprecated aliases"
    `scopedLayer` and `scopedAsyncLayer` are deprecated aliases of `layer` and `asyncLayer`.

## Typed fixtures

A fixture keyed by string accepts any value, so a typo such as `"premum"` passes the test and fails in production.
Build the fixture from your [`FlagDef`s](typed-flags.md) instead, and the compiler checks the value's type:

```scala
--8<-- "TestkitSpec.scala:typed-fixture"
```

`:=` stores the value the way a provider would carry it (`flagType.encode(value)`), so the test reads it back through
the same decoding as production. It also checks that the codec can decode its own encoding, and throws at fixture
construction if it cannot.

Each factory that takes a flag map also takes `FlagOverride*`. For `asyncReadyLayer` the delay comes first:
`asyncReadyLayer(100.millis, Flag := v)`.

With a case-object default, name the type: `FlagDef[Tier]("k", Tier.Free)` (see
[Typed flags](typed-flags.md)).

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

## Unset flags and wrong types

A key the provider does not hold resolves as `FLAG_NOT_FOUND`, as with real providers. Hooks see the `error` stage for
it, not `after`, so when a test means "this flag is off", set it to `false`. This is also what lets a test provider sit
in a [provider chain](#provider-chains).

```scala
--8<-- "TestkitSpec.scala:absent-keys"
```

A value of the wrong type is a `TYPE_MISMATCH` error, never a silent default:

```scala
--8<-- "TestkitSpec.scala:wrong-type"
```

A key-based value is served only when its type is exactly the one read; nothing is converted, with one exception.

| Stored | Read as | Result |
|:-------|:--------|:-------|
| `Boolean`, `String`, `Int`, `Long`, `Double` | the same type | served |
| `Float` | `double` | served (the one conversion) |
| `Long` (even a small one) | `int` | mismatch |
| `Int` | `long` or `double` | mismatch |
| a string | a number | mismatch |

Store `42L` for a `long` flag and `2.0` for a `double` one. Typed fixtures (`setFlag(flagDef, v)`, `Flag := v`) always
store the right type.

## Checking which flags were read

The provider records every evaluation that reaches it, with the context it received, including evaluations a
[simulated failure](#simulating-failures) made fail.

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
| `trackedEvents` | every [`track`](tracking.md) call received, oldest first (the last 1000): `(name, EvaluationContext, Option[TrackingEventDetails])`, with the context the SDK merged |
| `clearTrackedEvents` | forgets them |
| `boundDomain` | the domain the provider was registered under, if any |

Evaluations answered without the provider, by a [transaction](transactions.md) override or cache, are not recorded.

## Provider status and startup

`setStatus` changes what the provider reports. While it is `NotReady`, `ShuttingDown` or `Fatal`, evaluations fail fast
(see [Lifecycle and events](lifecycle.md)). `getStatus` (or `status`) reads it.

```scala
--8<-- "TestkitSpec.scala:status"
```

`asyncLayer` builds at once with the provider `NotReady`, as `FeatureFlags.fromProviderAsync` does with a slow
provider. Evaluations fail with `ProviderNotReady` until you call `setStatus(ProviderStatus.Ready)`:

```scala
--8<-- "TestkitSpec.scala:async-layer"
```

`asyncReadyLayer` becomes ready on its own after `initDelay`. The delay runs on the test clock, so advance it:

```scala
--8<-- "TestkitSpec.scala:async-ready-layer"
```

`layer` and `layerFrom` return only after the SDK has delivered its initial ready event, so a `setStatus` straight after
the layer builds is not overwritten by it.

## Events

`emitEvent` sends a provider event through the OpenFeature SDK, so it reaches `FeatureFlags.events` and the `on*`
handlers as a real provider's event would:

```scala
--8<-- "TestkitSpec.scala:events"
```

Use `ProviderEvent.Ready`, `Error`, `Stale` or `ConfigurationChanged`, with `provider.metadata` as the provider's
metadata. `Reconnecting` has no SDK equivalent, so it does not reach `FeatureFlags`. The provider's own `events` stream
sees every event you emit.

## Simulating failures

```scala
--8<-- "TestkitSpec.scala:failures"
```

| Control | Effect |
|:--------|:-------|
| `setErrorMode(mode)` | every evaluation fails with `mode`: `FlagNotFound`, `ParseError`, `TypeMismatch`, `ProviderNotReady` or `General` |
| `setFailing(true)` | same as `setErrorMode(ErrorMode.General)`; `setFailing(false)` clears it |
| `setFailureProbability(p)` | each evaluation fails with probability `p` (clamped to 0-1) |
| `setDelay(d)` | each evaluation sleeps `d` first, on the real clock |
| `clearErrorMode`, `clearDelay`, `clearBehavior` | undo the above |

Each control has a test aspect that sets it before the test and restores what it found afterwards, so aspects nest:
`TestFeatureProvider.withErrorMode(mode)`, `withFailures`, `withFailureProbability(p)` and `withDelay(d)`. They need
`TestFeatureProvider` in the environment. An error mode applies to every key; to make one key missing, leave it unset.

To make the provider itself misbehave (a hung `initialize()`, a failure on the first call only), see
[fault injection](testing-providers.md#inject-faults-into-a-provider).

## Per-test flag overrides

To pin flags for one test, or for every test in a suite, use a flag aspect. It needs `TestFeatureProvider` in the
environment:

```scala
--8<-- "TestkitSpec.scala:flag-aspects"
```

| Aspect | Pins |
|:-------|:-----|
| `withFlags(overrides*)` | typed overrides, `Flag := value`, as `layer` takes them |
| `withFlags(map)` | key/value pairs, as `layer(map)` takes them |
| `withFlag(flag, value)` | one typed flag |
| `withFlag(key, value)` | one key |

Each aspect restores per key: before the test it saves the current value of each key it pins (or notes the key was
absent), and afterwards puts it back or removes the key. Keys it does not pin are left alone, and a test-level aspect
inside a suite-level one on the same key leaves the suite's value afterwards.

Unlike a [transaction](#transactions) override, the value comes from the provider, so `wasEvaluated`, the error modes
and provider chains all apply.

## Recording hook calls

`RecordingHook` records every stage call it receives, in order. Install it like any other hook (`FeatureFlags.addHook`,
`FeatureFlagsConfig.withHook`, or per call through `EvaluationOptions`). It works with any `FeatureFlags`, not only the
testkit layers:

```scala
--8<-- "TestkitSpec.scala:recording-hook"
```

`calls` returns a `Chunk[HookCall]`, oldest first, and `clear` empties it. Each `HookCall` carries its stage's full
arguments, including the `HookContext` and the hints:

| Case | Stage | Also carries |
|:-----|:------|:-------------|
| `HookCall.Before` | `before` | |
| `HookCall.After` | `after`, on a clean resolution | `resolution` |
| `HookCall.Error` | `error`, on a failure or an error-coded resolution such as `FLAG_NOT_FOUND` | `error` |
| `HookCall.Finally` | `finallyAfter`, last on every exit | `resolution: Option[...]` |

`RecordingHook.make(types)` records only evaluations of those flag types; by default, all of them. Its `before` always
returns `None`, so it never changes the evaluation context.

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

To test network code rather than states, see [Testing real providers](testing-real-providers.md).
