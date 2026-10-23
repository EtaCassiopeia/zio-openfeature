# Extras

`zio-openfeature-extras` adds providers you can use without a flag service: flags from `application.conf` or from
environment variables. It also adds wrappers that make any provider faster or safer: a cache, a circuit breaker, and
two adapters for awkward third-party providers.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-extras" % "<version>"
```

| Provider | Use it to |
|---|---|
| [`HoconProvider`](#hocon-provider) | read flags from Typesafe Config (`application.conf`) |
| [`EnvVarProvider`](#environment-variable-provider) | read flags from environment variables |
| [`CachingProvider`](#caching-provider) | cache another provider's answers for a while |
| [`CircuitBreakerProvider`](#circuit-breaker-provider) | stop calling a failing provider and fail over fast |
| [`DeferredProvider`](#deferred-provider) | move a provider's slow constructor off your startup path |
| [`IntegerWideningLongProvider`](#integer-widening-long-provider) | read `Long` flags from an old provider that has no long resolver |

All of them are plain OpenFeature `FeatureProvider`s: pass them to any factory on
[Wiring a provider](providers.md), or combine them in a `FeatureFlags.multiProvider` chain.

## HOCON provider

Reads flags from a block of Typesafe Config, `feature-flags` by default:

```hocon
# application.conf
feature-flags {
  new-checkout = true
  max-items = 50
  rate-limit = 2.5
  welcome-message = "Hello!"
  settings {
    timeout = 30
    retries = 3
  }
}
```

```scala
--8<-- "Extras.scala:hocon"
```

- Booleans, strings, ints, longs and doubles are read as their type. A nested block is an object flag.
- A key that is present answers with reason `Static`.
- A value of the wrong type is a `TypeMismatch`; a value Typesafe Config cannot parse is a `ParseError`.
- An absent key is `FLAG_NOT_FOUND`: the typed tier fails with `FlagNotFound`, and the total tier serves your default
  with that error code. Hooks see the `error` stage. Reporting "not here" is what lets a
  [provider chain](#combining-providers) move on to the next provider.

`reload()` re-reads the config the provider was built from. If the reload fails, the provider keeps the old config
and reports `ERROR` until a later reload succeeds:

```scala
--8<-- "Extras.scala:hocon-reload"
```

A provider made with `fromConfig` has no source to re-read, so its `reload()` keeps the same config.

## Environment variable provider

Reads each flag from one environment variable. The variable's name is a prefix plus the transformed flag key. By
default the prefix is `FF_` and the key is upper-cased, with `-` and `.` turned into `_`:

| Flag key | Variable |
|---|---|
| `new-checkout` | `FF_NEW_CHECKOUT` |
| `max-items` | `FF_MAX_ITEMS` |
| `app.feature.enabled` | `FF_APP_FEATURE_ENABLED` |

```scala
--8<-- "Extras.scala:env"
```

`withLookup` takes any `String => Option[String]`, which is handy in tests.

| Flag type | Accepted values |
|---|---|
| Boolean | `true`/`false`, `yes`/`no`, `on`/`off`, `1`/`0` (any case) |
| Int, Long, Double | anything `toInt`, `toLong` or `toDouble` parses |
| String | the raw value |
| Object | the raw value, as a string |

A variable that is set but cannot be parsed is a `ParseError`, so a typo in a deployed value is visible instead of
quietly serving the default. An unset variable is `FLAG_NOT_FOUND`, exactly like an absent HOCON key.

## Combining providers

`FeatureFlags.multiProvider` chains providers. With the default first-match strategy, each provider is asked in turn
until one has the flag:

```scala
--8<-- "Extras.scala:chain"
```

Here `FF_NEW_CHECKOUT=true` overrides `new-checkout = false` in `application.conf`, and a key neither holds falls
back to the caller's default. The chain moves on only when a provider reports `FLAG_NOT_FOUND`. Any other error ends
it; use `MultiProviderStrategy.firstSuccessful` if errors should fall through too. See
[Wiring a provider](providers.md) for the strategies.

## Caching provider

Wraps any provider and keeps its answers for a while, using [zio-cache](https://github.com/zio/zio-cache):

```scala
--8<-- "Extras.scala:caching"
```

- The first evaluation of a flag calls the provider; repeats within `ttl` are served from the cache with reason
  `Cached`.
- Entries are keyed by flag key, flag type and evaluation context. Callers who ask for the same entry at the same
  time share one provider call.
- Errors are never cached, and neither are `DEFAULT`-reason answers, so a transient failure is retried on the next
  call.
- When the cache is full, the least recently used entry goes.

| `CachingConfig` field | Default | Meaning |
|---|---|---|
| `maxEntries` | `1000` | entries kept before the least recently used is evicted |
| `ttl` | `5.minutes` | how long an entry is served |
| `contextKeys` | `None` | which context attributes make up the cache key; `None` uses the whole context |

If your context carries something unique per request, such as a request id, every evaluation becomes its own entry
and nothing is ever served from the cache. Name the attributes that actually change the answer:

```scala
--8<-- "Extras.scala:caching-context-keys"
```

With `contextKeys` set, the targeting key is left out of the cache key too, and `Some(Set.empty)` caches by flag key
alone.

### Keeping the cache fresh

When the wrapped provider emits `PROVIDER_CONFIGURATION_CHANGED`, the whole cache is cleared, and the event is passed
on to your `FeatureFlags` event handlers. That needs a provider that emits events (an OpenFeature `EventProvider`). A
provider that does not, including one wrapped in `DeferredProvider` or `IntegerWideningLongProvider`, can serve a
changed flag for up to `ttl`. Put `CachingProvider` closest to the event-capable provider, choose `ttl` for how stale
you can afford to be, or clear the cache yourself:

```scala
--8<-- "Extras.scala:caching-invalidate"
```

Clearing applies to evaluations already in flight too: one that started before the clear still answers its caller, but
its result is not cached.

## Circuit breaker provider

A remote provider that is down can make every evaluation wait for a timeout. `CircuitBreakerProvider` notices
repeated failures and stops calling the provider for a while. While the circuit is open, calls fail at once, and the
next provider in a `firstSuccessful` chain answers instead:

```scala
--8<-- "Extras.scala:circuit-breaker"
```

![CircuitBreakerProvider wrapping a remote provider, with a fallback behind it in a firstSuccessful MultiProvider: three consecutive failures open the circuit, calls while it is open are rejected at once and answered by the fallback without calling the delegate, and after resetTimeout one probe call succeeds and closes the circuit.](assets/circuit-breaker.svg)

The circuit has three states:

| State | What happens to an evaluation |
|---|---|
| Closed | It goes to the provider. Consecutive failures are counted. |
| Open | It fails at once, without calling the provider. After `resetTimeout`, the next call becomes a probe. |
| Half-open | One probe goes to the provider at a time. `halfOpenMaxCalls` successful probes close the circuit; a failed one opens it again. A slow call let through earlier, before the circuit opened, does not count. |

Three things open the circuit:

- **Failures:** `failureThreshold` consecutive infrastructure failures. A call slower than `evaluationTimeout` counts
  as one. A failure counts whether the provider throws it or returns it as an error code (`GENERAL`,
  `PROVIDER_NOT_READY`, `PROVIDER_FATAL`); errors such as `FLAG_NOT_FOUND` only show the provider is reachable.
- **The provider's state:** at most once per `stateCheckInterval`, the breaker asks the provider for its state. `ERROR`
  or `FATAL` opens the circuit at once, and `READY` closes a circuit opened this way.
- **The provider's events:** `PROVIDER_ERROR` opens the circuit and `PROVIDER_READY` closes one opened by state or
  events. A circuit opened by an event, or by a failed `initialize`, stays open through a polled `READY`, since most
  providers report `READY` by default when polled; it closes on an event `READY` or after `resetTimeout` and a
  successful probe. This needs an `EventProvider`; any other provider relies on the first two.

Only failures that say the provider is unhealthy count. A `FLAG_NOT_FOUND`, type mismatch, parse error, missing
targeting key or invalid context proves the provider answered, so it resets the failure count instead.

| `CircuitBreakerProviderConfig` field | Default | Meaning |
|---|---|---|
| `failureThreshold` | `5` | consecutive failures that open the circuit |
| `resetTimeout` | `30.seconds` | how long the circuit stays open before a probe |
| `evaluationTimeout` | `500.millis` | longest wait for one provider call; `Duration.Infinity` disables it |
| `halfOpenMaxCalls` | `1` | successful probes needed to close the circuit |
| `stalePolicy` | `StalePolicy.Open` | what a `STALE` provider does: `Open` the circuit, `HalfOpen` it, or `Ignore` it |
| `stateCheckInterval` | `1.second` | how often the provider's state is polled; `Duration.Zero` polls on every call |

When failures open the circuit, the breaker emits `PROVIDER_STALE`; when a probe closes it, `PROVIDER_READY`. While it is
open, it reports its own state as `ERROR`, and `STALE` while half-open.

!!! note "Timeouts and provider hooks"
    With a finite `evaluationTimeout`, the wrapped provider runs on another thread. If you wrap a whole
    `MultiProvider`, the hooks of its members then see the domain `"multiprovider"` instead of your client's. Wrap
    each member in its own breaker instead, or set `evaluationTimeout = Duration.Infinity`, which keeps the call on
    the caller's thread but means a hung provider blocks the caller and never counts as a failure.

`CircuitBreaker` and `CircuitBreakerConfig` are the state machine behind the provider. `CircuitBreakerConfig` has the
same `failureThreshold`, `resetTimeout` and `halfOpenMaxCalls`, plus `probeTimeout`: how long a probe may hold the
half-open slot before another caller can take it. The provider sets it to `evaluationTimeout` plus one second.

## Deferred provider

Some providers do all their network work in their constructor, which blocks wherever you build them.
`DeferredProvider` takes a function that builds the provider, and calls it only when OpenFeature initializes the
provider. With async initialization, that is on the SDK's own thread, so your startup does not wait:

```scala
--8<-- "Extras.scala:deferred"
```

- It reports the name you give it, before and after the real provider exists.
- Until the real provider is built, evaluations answer `PROVIDER_NOT_READY` with your default.
- A shutdown during construction shuts the new provider down as soon as it is built.
- Once the real provider exists, provider hooks and `track` calls go to it.
- A `DeferredProvider` that was shut down, or failed to start, cannot be started again; build a new one.
- It does not pass on the wrapped provider's events.

Use `FeatureFlags.fromAcquireAsync` instead if a fallback should answer while the real provider starts; see
[Wiring a provider](providers.md).

## Integer widening long provider

`FeatureFlags.long` asks the provider's own long resolver, so 64-bit values are exact. A third-party provider written
before OpenFeature Java SDK 1.22 has no long resolver, and the SDK's stand-in answers from its double resolver, which
may reject an integer flag with a type mismatch. Wrap such a provider to read `Long` flags through its integer
resolver instead:

```scala
--8<-- "Extras.scala:integer-widening"
```

- A default that fits in an `Int` is resolved by the integer resolver and widened to `Long`, keeping the reason,
  variant, error code and metadata.
- A larger default goes to the provider's long path unchanged, so it is never truncated.
- If the integer resolver reports a type mismatch, for example because the stored value is a long beyond `Int` range,
  the evaluation is retried on the long path. A provider that silently truncates such a value instead cannot be
  detected.
- It does not pass on the wrapped provider's events.

Every provider shipped with this library already resolves longs natively, so they never need it.

## Wrapping rules

| Wrapper | Takes | Passes on events | Passes on provider hooks and `track` |
|---|---|---|---|
| `CachingProvider` | any `FeatureProvider` | yes, if the wrapped provider emits them | yes |
| `CircuitBreakerProvider` | any `FeatureProvider` | yes, if the wrapped provider emits them | yes |
| `DeferredProvider` | a function that builds one | no | hooks yes, `track` no |
| `IntegerWideningLongProvider` | any `FeatureProvider` | no | yes |

`CachingProvider` and `CircuitBreakerProvider` take over the wrapped provider's event channel when initialized. Don't
also register that same provider instance with OpenFeature directly.
