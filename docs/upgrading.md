# Upgrading to 2.0

This guide takes an application from 1.2.1 to 2.0.

Read it top to bottom and fix compile errors as you go; the most common come first.
[Behaviour changes that still compile](#behaviour-changes-that-still-compile) need a separate look, since the compiler
says nothing about them.

## Requirements

| | 1.2.1 | 2.0 |
|---|---|---|
| Java | 11+ | 17+ |
| Scala 3 | 3.3.8+ | 3.3.9+ |
| Scala 2.13 | 2.13.18+ | unchanged |
| ZIO | 2.1.27+ | unchanged |
| OpenFeature Java SDK | 1.23.0 | unchanged |
| OpenFeature specification | 0.9.0 | unchanged |

2.0 is not binary compatible with 1.x. **Recompile everything built against 1.x**, including your own libraries, custom
providers and wrappers. A library still built against 1.x cannot be used next to 2.0: it fails at link time, or sbt's
eviction check reports the conflict first, since the version scheme marks 1.x and 2.x as incompatible.

## Configuration objects

**What you will see:** no matching `apply` on a config's companion, or `copy` is not a member.

`FeatureFlagsConfig`, `EvaluationOptions`, `CachingConfig`, `CircuitBreakerConfig`, `CircuitBreakerProviderConfig`,
`OFREPProviderConfig`, `OptimizelyProviderConfig` and `OptimizelyEvents.Batched` are final classes now. Start from
`default` (`EvaluationOptions.empty`; `OFREPProviderConfig(baseUrl)` and `OptimizelyProviderConfig(sdkKey)` take their
required field) and call a `with*` method per field. `EvaluationOptions(hook)` and `EvaluationOptions(h1, h2)` still
work.

Before:

```scala
val flagsConfig = FeatureFlagsConfig(initMode = InitMode.Async, domain = Some("checkout"))
  .copy(evaluationTimeout = EvaluationTimeout.After(300.millis))
val callOptions = EvaluationOptions(timeout = EvaluationTimeout.After(100.millis))
val caching     = CachingConfig(ttl = 1.minute, maxEntries = 1000)
val breaker     = CircuitBreakerProviderConfig(failureThreshold = 3, resetTimeout = 10.seconds)
val ofrepConfig = OFREPProviderConfig("https://flags.example.com", requestTimeout = Some(java.time.Duration.ofSeconds(2)))
val optimizelyConfig = OptimizelyProviderConfig(
  "my-sdk-key",
  datafileUrl = Some("https://agent.example.com/datafile.json"),
  events = OptimizelyEvents.Batched(batchSize = 50)
)
```

After:

```scala
--8<-- "Upgrading.scala:configs"
```

| 1.x | 2.0 |
|---|---|
| `FeatureFlagsConfig()` | `FeatureFlagsConfig.default` |
| `config.copy(domain = Some("d"))` | `config.withDomain("d")` |
| `config.copy(evaluationTimeout = EvaluationTimeout.Disabled)` | `config.withoutEvaluationTimeout` |
| `EvaluationOptions(hooks = hs)` | `EvaluationOptions.empty.withHooks(hs)` |
| `CircuitBreakerConfig(probeTimeout = d)` | `CircuitBreakerConfig.default.withProbeTimeout(d)` |
| `OptimizelyEvents.Batched()` | `OptimizelyEvents.Batched.default` |

[Wiring a provider](providers.md#configure-it-with-featureflagsconfig) lists the setters of `FeatureFlagsConfig`.

Traps:

- **`==` still compiles, and now compares references.** These classes have no structural equality, so a test that
  compares two equal configs now fails. Compare the fields, which read as before (`config.domain`).
- **No setter clears an `Option` field.** `config.copy(domain = None)` has no counterpart: build again from `default`.
- **An `Option` field takes the bare value:** `withDatafileUrl(url)`, not `Some(url)`.
- **A pattern on a config no longer compiles,** since none has an `unapply`. `OptimizelyEvents` is also no longer a
  `Product`.

```scala
--8<-- "Upgrading.scala:batched-match"
```

## `FeatureFlagsAdmin`

**What you will see:** `setProvider`, `shutdown`, `on`, `onProviderReady`, `onProviderError`, `onProviderStale`,
`onConfigurationChanged`, `addApiHook`, `clearApiHooks`, `addZioApiHook`, `addZioApiHooks`, `clearZioApiHooks` or
`zioApiHooks` is not a member of `FeatureFlags`.

These moved, with their service accessors, to the new `FeatureFlagsAdmin` service. The rest stays on `FeatureFlags`,
including `addHook`, `addHooks`, `clearHooks`, `hooks`, `events`, `providerStatus` and `awaitReady`.

Before:

```scala
def swapThenRead(next: FeatureProvider): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
  for
    _       <- FeatureFlags.onProviderReady(meta => ZIO.logInfo(s"ready: ${meta.name}"))
    _       <- FeatureFlags.setProvider(next)
    enabled <- FeatureFlags.boolean("new-checkout", default = false)
  yield enabled
```

After:

```scala
--8<-- "Upgrading.scala:admin"
```

Traps:

- **An annotated layer hides the new service.** `val flags: ZLayer[Any, Throwable, FeatureFlags] = FeatureFlags.fromProvider(p)`
  still compiles, but nothing can read `FeatureFlagsAdmin` from it. Widen the annotation to
  `FeatureFlags & FeatureFlagsAdmin` (`FeatureFlags with FeatureFlagsAdmin` on 2.13) where a program administers it.
- **`layer.build.map(_.get)` no longer compiles:** "You must not use an intersection type". Name the service:

    ```scala
    --8<-- "Upgrading.scala:admin-build"
    ```

- **A `FeatureFlagRegistry` client is typed `FeatureFlags`.** The registry's own `setProvider(domain, provider)` and API
  hook methods are unchanged. For anything else, `FeatureFlagsAdmin.layer` reaches the same instance. It dies for a
  hand-written `FeatureFlags`, which has no administration to give.

    ```scala
    --8<-- "Upgrading.scala:admin-registry"
    ```

## Removed factories and members

**What you will see:** not a member, or for `fromProvider(provider, timeout)` a `Duration` where a `FeatureFlagsConfig`
is expected. Everything here but `Compat` was deprecated in 1.x. The deprecation messages say `FeatureFlagsConfig()`;
write `FeatureFlagsConfig.default`.

`fromProvider(provider)`, `fromProvider(provider, config)` and `fromProviderAsync(provider)` stay.

| 1.x | 2.0 |
|---|---|
| `fromProvider(p, evalTimeout)` | `fromProvider(p, FeatureFlagsConfig.default.withEvaluationTimeout(evalTimeout))` |
| `fromProvider(p, evalTimeout, initTimeout)` | the same, plus `.withInitTimeout(initTimeout)` |
| `fromProviderWithDomain(p, d)`, `(p, d, v)` | `fromProvider(p, FeatureFlagsConfig.default.withDomain(d).withVersion(v))` |
| `fromProviderWithHooks(p, hooks)` | `fromProvider(p, FeatureFlagsConfig.default.withHooks(hooks))` |
| `fromMultiProvider(ps)`, `(ps, strategy)` | `fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig.default)` |
| `fromProviderAsync(p, evalTimeout)` and the other `*Async` forms | the same, plus `.withAsyncInit` |
| `FeatureFlags.trackedEvents` | `TestFeatureProvider.trackedEvents`; it was empty outside the testkit |
| `OFREPProvider()` | `OFREPProvider.make("http://localhost:8016")`, the contrib provider's default URL |
| `OFREPProvider(baseUrl)` | `OFREPProvider.make(baseUrl)`, `scoped` or `layer` |
| `OFREPProvider.fromOptions(options)` | `OFREPProvider.make(options)`, `scoped` or `layer` |
| `TestFeatureProvider.scopedLayer` | `TestFeatureProvider.layer` |
| `TestFeatureProvider.scopedAsyncLayer` | `TestFeatureProvider.asyncLayer` |
| `CircuitBreaker(config)` | `CircuitBreakerProvider`; the standalone breaker has no public constructor |
| `AttributeValue.isNull` | `isEmptyValue` |
| `FlagType.wireType` | override `wire`; its default reads `typeName` |
| `zio.openfeature.Compat` and `OrError` | gone; see [transactions](#transactions-on-scala-213) |

```scala
--8<-- "Upgrading.scala:factories"
```

The `OFREPProvider.make` family returns an `OFREPFeatureProvider`, not the contrib `OfrepProvider`, in an effect that
fails with `InvalidConfiguration` for a bad URL. The contrib provider is its `.underlying`.

## Error types

**What you will see:** wrong number of patterns on `Unauthorized`, `actual` or `cause0` is not a member, not found for
`EmptyFlagVariables`, or an incompatible or unreachable arm in a `match`.

| 1.x | 2.0 |
|---|---|
| `case Unauthorized(reason)` | `case Unauthorized(reason, _)` |
| `FeatureFlagError.unauthorized(reason, cause)` | `Unauthorized(reason, Some(cause))` |
| `TypeMismatch(...).actual`, `OverrideTypeMismatch(...).actual` | `.detail` |
| `Unreachable(...).cause0` | `.underlying` |
| `case EmptyFlagVariables(_)` | delete the arm: it was never raised |
| `FeatureFlagError.isRecoverable(e)` | `FeatureFlagError.isFlagError(e)`; `ProviderStatus.isRecoverable` stays |

**Narrower error channels.** Each operation fails with a sealed sub-trait of what it can raise:

| Operation | Fails with | Its cases |
|---|---|---|
| evaluations, `track` | `FeatureFlagError.EvaluationError` | all but `ProviderInitializationFailed`, `InvalidConfiguration` and `NestedTransactionNotAllowed` |
| `FeatureFlagsAdmin.setProvider` | `FeatureFlagError.LifecycleError` | `ProviderNotReady`, `ProviderInitializationFailed`, `ProviderError`, `Unauthorized`, `Unreachable`, `InvalidConfiguration`, `ProviderFatal` |
| `transaction`, `transactionEither` | `FeatureFlagError.TransactionError`, besides the block's own error | `NestedTransactionNotAllowed`, `OverrideTypeMismatch` |

The factories still fail with `Throwable`. An annotation that says `FeatureFlagError` keeps compiling, since ZIO is
covariant in the error. A `match` on the narrower type must drop the arms it cannot reach.

**`FeatureFlagError` is a `Throwable`,** without a stack trace, so `.orDie` needs no `mapError` first. `getMessage` is
`message`, and `getCause` is `cause`, or `null` for none; `cause` itself is still an `Option`.

Before:

```scala
def describe(error: FeatureFlagError): String =
  error match
    case FeatureFlagError.FlagNotFound(key)            => s"no flag $key"
    case FeatureFlagError.TypeMismatch(key, _, actual) => s"$key: $actual"
    case FeatureFlagError.Unauthorized(reason)         => s"rejected: $reason"
    case FeatureFlagError.Unreachable(underlying)      => s"unreachable: ${underlying.getMessage}"
    case other                                         => other.message

val killSwitch = FeatureFlags.boolean("kill-switch", default = false)
  .mapError(e => new RuntimeException(e.message))
  .orDie
```

After:

```scala
--8<-- "Upgrading.scala:errors"
```

Trap: **a handler for `Throwable` now also catches these errors.** A `FeatureFlagError` matches `case t: Throwable`, and
an `IO[FeatureFlagError, A]` conforms to `Task[A]`. Check `case _: Throwable` arms on a channel that mixes flag errors
with exceptions, since one written for exceptions now takes flag errors too.

See [Evaluating flags](evaluating.md#errors) for what each case means.

## Transactions on Scala 2.13

**What you will see, on 2.13:** `transaction` is not a member of `FeatureFlags`; `Compat` is not found.

`transaction` existed on 2.13 with `Any` as its error channel. It is gone there; use `transactionEither`, which is the
same on both Scala versions. The Scala 3 form stays, its channel now `E | TransactionError` (it was
`E | FeatureFlagError`). `transactionEither` was `Either[E, FeatureFlagError]`; it is now `Either[E, TransactionError]`.

Before, on 2.13:

```scala
FeatureFlags
  .transaction(overrides = Map("new-checkout" -> true)) {
    FeatureFlags.boolean("new-checkout", default = false)
  } // the error is Any
```

After, on both versions. Samples here compile on Scala 3 only, but this one has no Scala 3 syntax in it:

```scala
--8<-- "Upgrading.scala:transaction-either"
```

See [Transactions](transactions.md#errors).

## Multi-provider strategies

**What you will see:** a type mismatch between `MultiProviderStrategy.Strategy` and the SDK's `Strategy`.

`MultiProviderStrategy.Strategy` was an alias of `dev.openfeature.sdk.multiprovider.Strategy`. It is now this library's
own sealed trait, and `FeatureFlags.multiProvider` takes only that.

| 1.x | 2.0 |
|---|---|
| `new FirstMatchStrategy()` | `MultiProviderStrategy.FirstMatch` |
| `new FirstSuccessfulStrategy()` | `MultiProviderStrategy.FirstSuccessful` |
| `class X extends dev.openfeature.sdk.multiprovider.Strategy` | `class X extends MultiProviderStrategy.CustomStrategy`, passed as `MultiProviderStrategy.Custom(new X)` |

`MultiProviderStrategy.firstMatch` and `firstSuccessful` stay, and return the first two.

Before, with `SdkContext` standing for `dev.openfeature.sdk.EvaluationContext`:

```scala
class PrimaryOnly extends dev.openfeature.sdk.multiprovider.Strategy:
  def evaluate[T](
    providers: java.util.List[FeatureProvider],
    key: String,
    defaultValue: T,
    ctx: SdkContext,
    call: java.util.function.Function[FeatureProvider, ProviderEvaluation[T]]
  ): ProviderEvaluation[T] =
    call.apply(providers.get(0))
```

After:

```scala
--8<-- "Upgrading.scala:strategies"
```

Trap: **`firstMatch` no longer fits the SDK's own `MultiProvider`.** A hand-built `new MultiProvider(list, strategy)` now
takes the SDK's classes directly (`new FirstMatchStrategy()`). See
[Ask several providers in order](providers.md#ask-several-providers-in-order).

## `fromAcquireAsync`

**What you will see:** the named argument is not found, or the third argument is not a `FeatureFlagsConfig`.

The settings moved to two objects. `fromAcquireAsync(acquire, fallback)` and `(acquire, fallback, config)` use the
defaults for the rest.

| 1.x argument | 2.0 |
|---|---|
| `evaluationTimeout` | `FeatureFlagsConfig.withEvaluationTimeout` |
| `initTimeout` | `FeatureFlagsConfig.withInitTimeout` |
| `contextSource` | `FeatureFlagsConfig.withContextSource` |
| `fallbackLogging` | `FeatureFlagsConfig.withFallbackLogging` |
| `maxAbandonedCalls` | `FeatureFlagsConfig.withMaxAbandonedCalls` |
| `compose` | `AcquireConfig.withCompose` |
| `constructionRetry` | `AcquireConfig.withConstructionRetry` |
| `constructionTimeout` | `AcquireConfig.withConstructionTimeout` |
| `onConstructionError` | `AcquireConfig.withOnConstructionError` |
| `verify` | `AcquireConfig.withVerify` |
| `onSwapped` | `AcquireConfig.withOnSwapped` |

Before:

```scala
FeatureFlags.fromAcquireAsync(
  acquire = ZIO.attemptBlocking(buildRealProvider()),
  fallback = ZIO.succeed(EnvVarProvider()),
  constructionTimeout = 10.seconds,
  evaluationTimeout = 200.millis,
  verify = Verify.flagExists[Boolean]("kill-switch"),
  onSwapped = ZIO.logInfo("real provider is live"),
  fallbackLogging = FallbackLogging.Off
)
```

After:

```scala
--8<-- "Upgrading.scala:acquire"
```

The config also lets this path set a domain, version, initial hooks and API ownership, which it could not before. See
[Start on a fallback](providers.md#start-on-a-fallback-fromacquireasync).

## Implementing `FeatureFlags`

**What you will see:** cannot override a final member, a class that does not implement abstract members, or an
incompatible return type. This section matters only for a hand-written `FeatureFlags`, such as a stub or a decorator.

`valueDetails` is now the only abstract evaluation method. `booleanDetails` to `objDetails`, and `boolean` to `obj`,
`booleanOrDefault` to `objOrDefault`, are final and defined through it.

Before:

```scala
class MyFlags extends FeatureFlags:
  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]] = ...
  // stringDetails, intDetails, longDetails, doubleDetails, objDetails, valueDetails,
  // transaction, setProvider, shutdown, trackedEvents, and so on
```

After:

```scala
--8<-- "Upgrading.scala:implementing"
```

- **Implement:** `valueDetails`; the four `track` forms; `setGlobalContext`, `globalContext`, `setClientContext`,
  `clientContext`, `withContext`; `transactionEither`, `inTransaction`, `currentEvaluatedFlags`; `events`,
  `providerStatus`, `awaitReady`, `providerMetadata`, `clientMetadata`; `addHook`, `addHooks`, `clearHooks`, `hooks`.
- **Delete:** the per-type `*Details`, `transaction`, `trackedEvents` and the [administration members](#featureflagsadmin).
  `transaction` is defined through `transactionEither` now, and on 2.13 does not exist to override.
- **Declare the narrower type:** `valueDetails` and `track` return `IO[FeatureFlagError.EvaluationError, _]`, and
  `transactionEither` fails with `Either[E, FeatureFlagError.TransactionError]`.

**Callers.** The arity overloads of the per-type getters, `*OrDefault` and `value` are now one method each, with `ctx`
and `options` defaulted. A call site compiles unchanged, and a lambda such as `ff.boolean(_, _)` still works. A bare
method reference does not: `ff.boolean` passed where a `(String, Boolean) => ...` is expected now has four parameters,
so write the lambda.

## Testkit

- **Layers provide more.** `layer`, `asyncLayer` and `asyncReadyLayer` also provide `FeatureFlagsAdmin`, and `layerFrom`
  provides `FeatureFlags & FeatureFlagsAdmin`. The same [`build` trap](#featureflagsadmin) applies.
- **`scopedLayer` and `scopedAsyncLayer` are gone,** in every overload: use `layer` and `asyncLayer`.
- **`Fault` has two more cases,** `Fault.Answer` and `Fault.MetadataAnswer`, so an exhaustive match on it needs them.
- **`setStatus` goes through the instance's lifecycle.** On an attached instance it dies once the instance is shut down
  or closed, and while a `setProvider` is in flight. A test that sets a status there needs to wait, or to stop doing it.
  See [Provider status and startup](testkit.md#provider-status-and-startup).

```scala
--8<-- "Upgrading.scala:testkit"
```

## Behaviour changes that still compile

Nothing below shows up as a compile error, and some of it changes what your application serves. Defaults are unchanged:
a 1 second evaluation timeout, a 30 second init timeout and 256 abandoned calls.

### Values and errors

| What changed | What to check | See |
|---|---|---|
| A `Map[String, Any]` read of an object flag answered with no value, or with a non-object, is a `TypeMismatch`. It used to resolve to an empty map. This hits `obj` on `EnvVarProvider`, and on a scalar or list key in `HoconProvider`. | Any `obj` read whose empty map was the "off" case: the typed tier now fails, and the total tier serves your default with `TYPE_MISMATCH`. | [Extras](extras.md#environment-variable-provider) |
| A value a flag's type cannot read (none at all, or another box), or a non-`Value` object from a provider's object getter, fails with `TypeMismatch`, through every hook stage. It used to kill the fiber. | A `catchAllDefect` or fiber-death alert that relied on this. It now arrives as a typed failure, or as your default on the total tier. | [Errors](evaluating.md#errors) |
| The total tier keeps the provider's variant, flag metadata and message on an error-coded answer, with your default as the value and reason `Error`. | Code or alerts matching the library's old message text, such as `Flag 'x' not found`, on `errorMessage` or in the fallback log line. A hand-written `FeatureFlags` whose details succeed with an error code is now served the same way. | [Two tiers](evaluating.md#two-tiers) |
| A more specific context layer's string `targetingKey` attribute now outranks a less specific explicit key. | Contexts that set `targetingKey` as an attribute over a global or client key: **users can land in different variants.** | [Creating a context](context.md#creating-a-context) |
| A `Long` beyond 2^53 inside an object flag comes back as an exact `Long`, not a `Double`. Other numbers are still `Double`. | A `match` on a `Map[String, Any]` member with only `case d: Double`. | [Check a custom `FlagType`](testing-providers.md#check-a-custom-flagtype) |
| A `Float` flag beyond `Float` range is `TYPE_MISMATCH`, not `Infinity`. An `Instant` handed to a provider stays an `Instant`, not its text. | A custom `FlagType` on the `Float` wire receives a `Double` for such a value. | [Map it from another type](typed-flags.md#map-it-from-another-type) |
| A derived `FlagType`: an enum whose names differ only by case prefers the exact match, and a missing field never takes the default of an `apply` overload. | Case classes with an overloaded `apply`: such a field now fails or takes the constructor's default. | [Derive it](typed-flags.md#derive-it-scala-3) |

### Replacing and shutting down a provider

| What changed | What to check | See |
|---|---|---|
| `setProvider` with the provider already installed returns at once and runs no swap. | Code that re-installs a provider to initialize it again: install a new instance. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| `setProvider` after `shutdown`, or after the layer's scope closed, fails with `ProviderNotReady(NotReady)`. It used to revive the instance, or register a provider on a torn-down API. | A reference to the instance kept past its scope. Build a new layer. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| `setProvider` refuses, with `ProviderInitializationFailed`, a provider whose `initialize()` is still running from an earlier registration, and the provider a timed-out swap replaced, until the new one's `initialize()` has settled. | Retry loops that re-install the same provider after a timeout, and fallbacks that re-install the previous provider after one: use a new instance. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| An evaluation in flight when a swap starts fails with `ProviderNotReady`, even if the old provider answered it. | Typed reads around swaps: expect more of these. The total tier serves your default. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| An `ERROR` the new provider reports just after `initialize()` returned no longer fails the swap: `setProvider` succeeds with status `Error` or `Fatal`. | Code that takes a successful `setProvider` to mean healthy: read `providerStatus` too. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| A failed swap right after a timed-out one, or on an async-built instance still starting, has nothing to roll back to: the failed provider stays registered with status `Error`. | Code that assumes a failed `setProvider` always restores the old provider. | [Replace the provider](providers.md#replace-the-provider-at-runtime) |
| An owner's shutdown or scope close retires every other instance on that API: they report `NotReady` and typed reads fail. They used to stay `Ready` and serve the default through the SDK's no-op provider. Building on a slot that holds another provider logs a warning. | Several instances on the global API, and any new warning in your logs. `withIsolatedApi` keeps instances apart. | [Sharing the SDK](providers.md#sharing-the-sdk) |
| `api.getProvider()` returns the library's wrapper, unless you registered the provider with the API yourself. | Casts or `eq` checks on the result. | [Sharing the SDK](providers.md#sharing-the-sdk) |
| A failed build or a passed init deadline no longer shuts down a provider you registered with the API yourself that the library reused, or a wrapper another live instance is still using. The first logs a warning. | Code that relied on the library stopping such a provider: it stays in its slot until the slot is replaced or the API shuts down. | [Sharing the SDK](providers.md#sharing-the-sdk) |
| A `shutdown()` that hangs is abandoned after a bound, on a failed build, a failed swap, and the scope close of `CachingProvider`, `CircuitBreakerProvider`, `OFREPProvider` and `OptimizelyProvider`, where it is also logged. | Shutdowns that used to block for as long as they took: look for the log line. | [Sync or async start](providers.md#sync-or-async-start) |

### Status

| What changed | What to check | See |
|---|---|---|
| A `multiProvider` chain's status is the worst of its members'. A member's `ERROR` used to leave the chain `Ready`. Under `firstSuccessful`, one `Fatal` member makes the chain `Fatal`, and evaluations then fail, although another member could answer. | Health checks and `awaitReady` on a chain, and whether one member should be able to stop it. | [Ask several providers in order](providers.md#ask-several-providers-in-order) |
| `Stale` is entered from `Error` as well, and a `setProvider` whose provider reports `STALE` ends `Stale`, not `Ready`. A circuit a `STALE` opened reports `STALE`, so a sync build under a default `CircuitBreakerProvider` no longer fails on it. | Code that compares the status with `Ready`: use `status.canEvaluate`. | [Statuses](lifecycle.md#statuses) |
| The status follows the state the SDK reports, and a client on a provider another domain already initialized or failed takes that state at once. `onProviderReady` now also fires for a `READY` after an `ERROR`, and not for one the SDK sends while the provider still initializes. | Registry and shared-provider setups that relied on `NotReady` lasting until an event, and handlers that count `READY`s. | [Statuses](lifecycle.md#statuses) |
| `FeatureFlagRegistry`: `getClient` after a failed `initialize()` runs it again, unless the failure was `PROVIDER_FATAL`. It used to replay the first error. | A provider whose `initialize()` is not safe to call twice, and code that treats the first failure as final. | [One client per domain](providers.md#one-client-per-domain-featureflagregistry) |

### Providers, hooks and transactions

| What changed | What to check | See |
|---|---|---|
| OFREP: a response that is not OFREP JSON (an HTML page, an empty body, a 401 or 403 from a proxy, a 429 without JSON, also a 200 with malformed JSON) is `GENERAL`, so `ProviderError` on the typed tier, not `ParseError`. A plain-text body naming the failure (`Forbidden`, `Unauthorized`) reads as `Unauthorized`, since the parse error quotes it. It now trips a circuit breaker. | Alerts or recovery keyed on `ParseError`, and breaker thresholds: a dead backend behind a proxy now opens the circuit. | [When something goes wrong](ofrep.md#when-something-goes-wrong) |
| `HoconProvider.fromConfig` resolves the config, so substitutions work, and **a required substitution that cannot be resolved fails at construction**. A key that is not a HOCON path (`flag@v2`, `a..b`) is `FLAG_NOT_FOUND`, not `GENERAL`. | Configs built with `ConfigFactory.parseString`, and where the construction runs. | [HOCON provider](extras.md#hocon-provider) |
| `CachingProvider` keys carry each attribute's type, and with `contextKeys` always `openfeature.variableKey`. A failed lookup is no longer handed to a caller that only joined it. | Hit rates may fall a little, and contexts that differ only in type (`"30"`, `30`) stop sharing an answer. | [Caching provider](extras.md#caching-provider) |
| `CircuitBreakerProvider` holds the delegate's `READY` until the circuit closes, and `StalePolicy.HalfOpen` acts only on a circuit the provider's own signal opened. | Event handlers that counted on the earlier `READY`, and recovery timing under `HalfOpen`. | [Circuit breaker provider](extras.md#circuit-breaker-provider) |
| `finallyAfter` receives the resolution when a value does not decode. An `error` hook's `TypeMismatch` names the flag's type, as the caller's does. | Hooks that read `None` as "no answer", or the wire type in the error. | [Order and outcomes](hooks.md#order-and-outcomes) |
| In a transaction, a cached `String` is not served to a `Boolean` or numeric read of the same key: the provider answers. A cached read keeps the first read's variant and flag metadata. | Blocks reading one key as two types, and hooks keyed on the variant. | [Caching](transactions.md#caching) |
| Optimizely: `shutdown()` during `initialize()` ends the wait at once, and a later `initialize()` of that instance fails with "was shut down". | Code that shuts a provider down and re-installs the same instance: build a new one. | [Optimizely](optimizely.md) |
| Case-insensitive matching uses `Locale.ROOT`. | Only under a Turkish or Azeri default locale, where `EnvVarProvider` keys, reasons and error classification change. | [Environment variables](extras.md#environment-variable-provider) |

The [CHANGELOG](https://github.com/EtaCassiopeia/zio-openfeature/blob/main/CHANGELOG.md) has every fix, including the
ones that only remove a race.

## New, and no migration needed

- [`zio-openfeature-schema`](typed-flags.md#derive-it-with-zio-schema-scala-213-and-3): a `FlagType` from a zio-schema
  `Schema`, on Scala 2.13 and 3.
- [`FeatureFlagsConfig.withIsolatedApi`](providers.md#sharing-the-sdk): a private `OpenFeatureAPI` for one instance.
- `FeatureFlagRegistry.fromProvider(defaultProvider, config)`: applies a config to every client.
- `EvaluationOptions` after the context on `boolean`, `string`, `int`, `long`, `double` and `obj`:
  [options for one call](evaluating.md#options-for-one-call).
- [`HostileProvider`](testing-providers.md#hostile-providers) and
  [`DifferentialLaws.absentKeysAreNotFound`](testing-providers.md#absent-keys-are-not-found) in the testkit.
