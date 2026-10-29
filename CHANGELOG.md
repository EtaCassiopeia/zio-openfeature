# Changelog

All notable changes to **zio-openfeature** are documented in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **`CachingProvider` no longer hands one caller another caller's failure** (#586). Concurrent callers for one key
  share a single delegate call, and zio-cache gives all of them its outcome. When that call failed, for instance with
  the `InterruptedException` a `CircuitBreakerProvider` timeout causes on the thread running it, a caller that had
  only joined it failed too, with an error from an evaluation that was not its own. Such a caller now evaluates the
  delegate once more itself; a failure of its own evaluation is still its answer, and failures are still never cached.
- **A `STALE` provider at startup no longer fails a synchronous build under a default `CircuitBreakerProvider`**
  (#603). Under the default `StalePolicy.Open`, the provider's `STALE` opened the circuit, and an open circuit reported
  `ERROR`, which a synchronous build rejects, while an async build of the same stack started. A circuit that a `STALE`
  opened now reports `STALE` (it still rejects calls until `resetTimeout` or the provider's `READY`); one opened by
  failures, `ERROR`, `FATAL` or a failed `initialize` still reports `ERROR`.
- **A `multiProvider` chain reports its members' status** (#602, spec compliance). The SDK's `MultiProvider` never
  attaches to its members, so their events went nowhere: a member's `ERROR` left the chain `Ready`, and `awaitReady`,
  health checks and a circuit breaker above the chain never saw it. The chain now does what the specification's
  Multi-Provider appendix prescribes: it tracks each member's status, takes the worst as its own and as its
  `getState` (`FATAL` > `NOT_READY` > `ERROR` > `STALE` > `READY`), re-emits a member's status event when that changes
  its own status, and always re-emits a configuration change. Under `firstSuccessful` a member's `Fatal` now makes the chain
  `Fatal`.
- **A timed-out swap no longer leaks the previous provider** (#564). When `setProvider`'s new provider did not finish
  initializing within `initTimeout`, the SDK kept it bound and dropped the previous provider from the slot, shutting it
  down only if the new one later became ready. If the new one failed instead, or was still initializing at shutdown,
  nothing ever shut the previous provider down, not even the API's own shutdown, so its threads and pollers stayed
  alive. An instance that owns its API now shuts it down in those cases, and the status stays `Error` until a later
  `setProvider`. Installing that previous provider again fails with a clear error until the new one has settled, and
  works after that.
- **A provider's ERROR then READY no longer leaves the status on `Error`** (#601). The Java SDK runs a provider's
  event handlers with no ordering between them, so the two events were often applied as READY then ERROR, and the
  status stayed `Error` until the next event while the provider served normally (11 of 30 rounds in a local repro).
  The status now follows the SDK's own state for the provider as of each event, which the SDK updates before it runs
  the event's handlers, so it settles on what the SDK reports. A `ProviderFatal` error still always makes it `Fatal`.
  A `READY` that finds the provider in `ERROR` is now delivered to handlers instead of dropped.
- **A shutdown during a provider swap stays `NotReady`** (#565). `shutdown()` did not wait for an in-flight
  `setProvider`, so a swap parked in `initialize()` that finished after the teardown moved the status to `Ready`
  (init released) or `Error` (init timed out), although `NotReady` is documented as final. Deterministic on a shared
  instance (registry or domain client), a narrow race on an owning one. `shutdown` now waits for in-flight and queued
  swaps to settle, each bounded by `initTimeout`, then tears down.
- **`HoconProvider.fromConfig` resolves the config it is given** (#583). A config built with `ConfigFactory.parseString`
  that used substitutions (`limit = ${base}`, `x = ${?ENV_VAR}`) was taken unresolved, so every read of it threw
  Typesafe Config's `NotResolved`, reported as a `GENERAL` error rather than the flag's value. `fromConfig` now
  resolves it, as `ConfigFactory.load` does for `HoconProvider(path)`; a required substitution that cannot be resolved
  fails at construction instead. Found by the new fuzz targets.

- **A half-open circuit breaker no longer rejects a caller that should have been its probe** (#577). Claiming the probe
  slot is a compare-and-swap, and losing it was taken to mean another caller had become the probe. A concurrent,
  unrelated change to the breaker's state (a success or a reachability signal recorded at the same moment) also makes
  it lose, so the caller was rejected while no probe was running, delaying recovery by one call. It now re-reads the
  state and decides again. Found by the new Lincheck check of the breaker.
- **`finallyAfter` gets the evaluation details when the provider's value does not decode** (#572). A custom type served
  a value its `FlagType` rejects (say `"purple"` for a `Color`) correctly returned `TYPE_MISMATCH` to the caller, but
  the `finallyAfter` hook saw `None`, as if the provider had not answered (spec 4.3.8 requires the details). It now
  gets the same resolution the total tier serves: the default, reason `ERROR`, code `TYPE_MISMATCH`, same message.
  A failure with no answer (not ready, timeout, a throw) still gives `None`.
- **The total tier keeps the provider's variant, flag metadata and message on an error-coded answer** (#571).
  `resolveOrDefault` and the `*OrDefault` methods rebuilt the served resolution from the typed tier's failure, so a
  provider's `FLAG_NOT_FOUND` with variant `v-err`, metadata `source` and message `"flag archived"` came back with no
  variant, empty metadata and `"Flag 'x' not found"`. The served resolution now carries the provider's own fields
  (spec 1.4), with your default as the value and reason `ERROR`; the typed tier is unchanged. The fallback log line
  quotes the provider's message too. A custom `FeatureFlags` implementation whose `*Details` succeed with an error
  code is now served the same way: the default as the value and reason `ERROR`, where it used to pass through as is.
- **Case-insensitive matching no longer depends on the JVM's default locale** (#570). Ten conversions used
  `toUpperCase`/`toLowerCase` without a locale. Under a Turkish or Azeri default locale `i` upper-cases to `İ` and `I`
  lower-cases to `ı`, so `EnvVarProvider` looked up `FF_İS_ENABLED` for `is-enabled` and reported every key containing
  an `i` as `FLAG_NOT_FOUND`; a provider reason `"disabled"` mapped to `Other("disabled")`; `FORBIDDEN` and
  `UNAUTHORIZED` errors were not classified as auth failures; and the OFREP and Optimizely providers' URL-scheme and
  message checks were affected the same way. All now use `Locale.ROOT`, and a test fails the build on a new
  conversion without it.
- **A more specific context layer's `targetingKey` attribute is no longer outranked by a less specific explicit key**
  (#569). `EvaluationContext.merge` kept the higher layer's explicit key but ignored its `targetingKey` string
  attribute, which only became the key after merging. So an invocation context of
  `withAttributes("targetingKey" -> "invocation-user")` over a global `EvaluationContext("global-user")` reached the
  provider as `global-user`, inverting the spec's precedence (3.2.3). Each layer's key now counts in either form.
- **A `Float` flag beyond `Float` range is `TYPE_MISMATCH`, not `Infinity`, and `Instant` values survive the bridge**
  (#568). A provider's `Double` was narrowed to `Float` before `FlagType[Float]` could check its range, so `1e300`
  read as `Infinity`. The decoder now sees the overflowing `Double` and rejects it; in-range values are unchanged. A
  custom `FlagType` on the `Float` wire therefore receives a `Double`, not `Float.PositiveInfinity`, for such a value.
  Separately, an `Instant` handed to the provider (a default, a map member, a `TestFeatureProvider` fixture) was
  turned into its text, so a `FlagType[Instant]` read failed with `TypeMismatch`. It now stays an `Instant`.
- **Three defects in Scala 3 `FlagType` derivation** (#567).
  - A missing field could take a default from a user's overload of `apply`. Scala names that overload's default getter
    `apply$default$<n>`, the same as the synthetic `apply`'s, so `Labelled(name, label)` with an absent `label` decoded
    to the overload's default instead of failing. Only the constructor's own defaults are read now.
  - When that overload's default had another type than the field, `decode` threw `ClassCastException` instead of
    returning a `Left`. Building the value is now guarded like reading a default is.
  - An enum with two labels that differ only by case (`On`, `ON`) decoded both to the first. An exact match now wins.
- **`CachingProvider` no longer serves one context's cached result to another** (#566). The cache key wrote each
  context attribute as its text, so attributes that differ only in type shared a key: `"30"` and `30`, `"true"` and
  `true`, an int and a long, a string and an instant, a null and `"null"`, and lists of each. A provider that targets
  on an attribute's type answered the first context, and the cache then served that answer, labelled `CACHED`, to the
  second. The key now tags every value with its type and encodes lists and structures element by element.
- **A client's status follows the SDK's own state, not just its events** (#563). Two cases went wrong, both with a
  provider shared between domains (a `FeatureFlagRegistry`'s default provider, or one passed to several domains):
  - A second domain's client was reported `Ready` while the shared provider was still initializing for the first,
    because the SDK sends `READY` to every domain bound to a provider as soon as any registration of it returns, and
    a registration that reuses a provider mid-initialization returns at once. Every evaluation through that client
    failed with `ProviderNotReady`. A `READY` now counts only once the SDK reports the provider ready.
  - A client registered on a provider that another domain had already initialized, or that had already failed, got no
    event at all: it stayed `NotReady` for the whole init timeout, and its init watchdog then shut down the provider
    the other domain was still serving. Such a client now takes the provider's state at once.
- **`FeatureFlagRegistry` retries a provider that failed to initialize, and a failed client no longer leaks** (#562).
  The Java SDK reuses a registered provider's state and initializes it only while it is not ready, so after one
  failed `initialize()` every later `getClient` (or `defaultClient`) replayed the error in about a millisecond without
  calling `initialize()` again. The failed client also stayed alive until the registry closed, along with its event
  handlers and its init watchdog, which could later shut down the very provider a retry had brought up. A failed build
  now releases its client at once, and the next attempt runs the provider's `initialize()` again through the SDK's own
  state manager, without shutting the provider down first (an Optimizely provider that owns its client could not come
  back from that). A `PROVIDER_FATAL` failure stays final, and a provider that initialized once and later reported an
  error is left to recover on its own.
- **A provider that can't shut down no longer hangs startup, the init watchdog or `setProvider`** (#561). A provider
  stuck in `initialize()` (an unreachable backend, a lock held by a hung connect) often blocks in `shutdown()` too.
  Three failure paths waited for that `shutdown()` without a bound: a failed sync build never returned, whatever
  `initTimeout` said; the async init watchdog reached `Fatal` but never published its `Error` event or released
  `awaitReady`, and closing the scope hung; and a `setProvider` whose rollback failed never returned. The build and
  `setProvider` now give that shutdown at most `initTimeout` (capped at 15 seconds) and then abandon it; the watchdog
  publishes its event and releases `awaitReady` first, and no longer holds up closing the scope. A test now fails the
  build if lifecycle code calls a provider's `shutdown()` or `initialize()` directly instead of through the bounded
  helpers.

## [1.2.1] — 2026-10-28

**A patch release with two fixes. Nothing to change when upgrading:** MiMa is green against 1.2.0 and no public
signature changed.
- **`track` now reaches every provider of a `multiProvider` chain.** In 1.2.0 it was dropped without an error, so
  tracking events sent through a chain never arrived.
- **testkit: `FaultyProvider.release` no longer misses a call that hasn't parked yet**, so `awaitEntered` followed by
  `release` is race-free.

### Fixed

- **`track` reaches the providers of a `multiProvider` chain** (#551). The Java SDK's `MultiProvider` does not
  override `track`, so every tracking event sent through `FeatureFlags.multiProvider` or the default
  `fromAcquireAsync` compose was silently dropped. Each provider in the chain now receives it. If one throws, the
  rest still receive it, and `track` fails with the first error, the others attached as suppressed. A failed `track`
  may already have reached some providers, so retrying it can count the event twice there.
- **`FaultyProvider.release` no longer misses a call that has not parked yet** (#556, testkit). `awaitEntered`
  returns as soon as a call starts, before it reaches its hang, and `release` only opened hangs already parked, so a
  release in between was lost and the call stayed parked until the scope closed. A release now reaches every call
  that entered before it, and `shutdown()` does the same for `HangUntilShutdown`, so `awaitEntered` followed by
  `release` is race-free. This was behind an intermittent `LifecycleModelSpec` failure in CI.

### Dependencies

- **Typesafe `config` 1.4.3 → 1.4.9** in `zio-openfeature-extras`, the only published dependency that moved. Build
  plugins, sbt (1.13.0) and scalafmt (3.11.5) were updated as well; none of them reach the published artifacts.

## [1.2.0] — 2026-10-24

**Contains a security fix.** If you use `zio-openfeature-ofrep`, upgrade. It now pins Jackson 2.22.4, which closes
four advisories in `jackson-core` and `jackson-databind`. Details are under **Security** below.

**A correctness release.** About forty fixes from a full review of the codebase, among them:
- `FeatureFlags.long` now serves values over OFREP and through `multiProvider` chains.
- Factory-built Optimizely providers now send impressions and conversions to Optimizely.
- A provider whose `initialize()` hangs no longer wedges the layer.
- Teardown no longer strands waiters.
- One initialization now delivers exactly one `Ready`.

The testkit gains `FaultyProvider`, per-test flag overrides, a hook recorder, `WrapperLaws` and `DifferentialLaws`.
Underneath: OpenFeature Java SDK 1.23.0, Optimizely SDK 4.6.0, ZIO 2.1.27.

**Upgrading from 1.1.x: recompile, don't just bump the version.** Most call sites compile unchanged. Several
signatures changed binary shape, however, so a caller compiled against 1.1.x that is **not** recompiled fails at run
time with `NoSuchMethodError`. The affected symbols are:
- `FeatureFlagsConfig`'s constructor, `apply` and `copy` (#437);
- `OptimizelyProviderConfig`'s constructor, `apply` and `copy` (#453);
- the OFREP factories' result type (#529).

The first two are listed, with their rationale, in the MiMa filters in `build.sbt`. MiMa cannot see the third, because
the result types erase to `ZIO`/`ZLayer`.

Some changes need more than a recompile:

- **Custom `MultiProviderStrategy.Strategy` implementations.** `evaluate` now takes a `List[FeatureProvider]` instead
  of a name-keyed `Map` (Java SDK 1.23.0).
- **Code that names `OfrepProvider` as an OFREP factory's result.** Change it to `OFREPFeatureProvider`, or reach the
  contrib provider with `.underlying` (#529).
- **`CachingProvider.make` and `CircuitBreakerProvider.make` can now fail** with `InvalidConfiguration` (#451). Widen
  any `UIO` / `ULayer` annotations.
- **Optimizely: factory-built providers now send events** (#453). Set `events = OptimizelyEvents.Disabled` to keep
  the old behaviour.
- **`FeatureFlags.trackedEvents` is empty in production** (#442). Assert on tracking with
  `TestFeatureProvider.trackedEvents` instead.

### Added

- **A model-based property test of the provider lifecycle** (#464). `LifecycleModelSpec` (testkit, test-only)
  generates sequences of swaps (to providers that start, fail, hang or start slowly), provider events, shutdowns,
  interrupted swaps and released hangs, runs them against a real `FeatureFlags` built with `FaultyProvider`s, and checks
  after every step that the status, `providerMetadata` and evaluations match a model written from the documented
  lifecycle; at the end the scope must close within 5 s and leave no fiber or non-daemon thread. 25 sequences on every
  PR, 300 weekly in the new `Property tests` workflow (`LIFECYCLE_MODEL_SAMPLES`). With the #434 fix reverted it
  finds an interrupted swap left `NotReady`; that sequence is the first `regressions` example.
- **`FaultyProvider` in the testkit** (#463): wraps any provider (or a fresh `TestFeatureProvider`) and applies a
  scripted `Fault` per `Op`, one per call with the last repeating: `Throw`, `Delay`, `HangUntilReleased` (an
  `initialize()` that ignores `shutdown()`), `HangUntilShutdown` and `Emit`. It records every call (`calls`,
  `awaitEntered`), and `FaultyProvider.scoped` releases any hang still parked when the scope closes. `Op` covers every
  `FeatureProvider`/`EventProvider` method but `emit*`, and a reflective test fails if the SDK adds one. The #433,
  #434 and #435 lifecycle scenarios are re-expressed with it in the testkit, and extras' tests now use it (extras
  depends on testkit for tests only). See "Fault injection" on the testkit page.

- **Weekly mutation testing of core** (#468): stryker4s via the new `Mutation testing` workflow (scheduled and
  manual), with its report as a build artifact. Informational only: never runs on a pull request and never fails the
  build. Locally, `core/stryker`; see the development guide.
- **`provider-fault-tests`, a local-only chaos suite for the real providers** (#470). The OFREP and Optimizely
  providers run through rift-scala's in-process TLS intercept proxy while the network rejects credentials, answers
  `503`/`429`, resets the connection, stalls, or returns a truncated or non-JSON body, and the suite asserts the
  library's outcome: a typed error or the fallback's answer, in bounded time, with a bounded shutdown. Run it with
  `sbt providerFaultTests/test` on JDK 22+. It is not in the root aggregate and CI only compiles it. The rift code on
  the "Testing real providers" page now comes from this module, so it is compiled with the docs.
- **Concurrency property tests** (#465) for hook-data isolation (#438), circuit-breaker epochs (#447) and cache
  invalidation (#449), plus a generated swap/evaluate schedule in the hot-swap stress spec. Test-only.
- Hostile-input property sweeps (Long beyond 2^53, NaN/Infinity/-0.0, emoji and control-character keys, nulls, deep and large structures) through value/context conversion, tracking conversion, OFREP response decoding and the Optimizely context transformer (#469).
- **`CachingProvider.scoped` and `CircuitBreakerProvider.scoped`, and config validation for both wrappers** (#451).
  `scoped(underlying, config)` is `make` plus a finalizer that shuts the wrapper (and so the wrapped provider) down,
  bounded and logged on failure; use `ZLayer.scoped(...)` for a layer. Every constructor now checks the config:
  `failureThreshold` and `halfOpenMaxCalls` at least 1, `resetTimeout`, `evaluationTimeout` and `probeTimeout` greater
  than zero (`Duration.Infinity` legal), `stateCheckInterval` zero or more, `maxEntries` at least 1, `ttl` greater
  than zero (`Duration.Infinity` legal). Before, a zero `evaluationTimeout` failed every call, a non-positive
  `maxEntries` cached nothing, and a negative `ttl` or `resetTimeout` expired at once. The unsafe `apply`
  constructors, including the standalone `CircuitBreaker.apply`, throw `IllegalArgumentException`. Both wrappers'
  `shutdown()` is now idempotent, so a wrapper that is scoped and registered with `FeatureFlags` shuts its delegate
  down once.
- **Per-test flag overrides and a hook recorder in the testkit** (#458). `TestFeatureProvider.withFlags(Flag := v)`,
  `withFlags(map)`, `withFlag(flag, v)` and `withFlag(key, v)` are test aspects that pin flags for one test and
  restore each key afterwards (or remove it if it was absent), so they nest and leave other keys alone.
  `RecordingHook.make()` is a `FeatureHook` that records every stage call (`HookCall.Before`/`After`/`Error`/`Finally`,
  with the full `HookContext` and hints) for assertions. See "Per-test flag overrides" and "Recording hook calls" on
  the testkit page.
- **A cap on abandoned provider calls** (#437). A timed-out evaluation abandons its provider call, which keeps a thread
  of ZIO's unbounded blocking pool until the provider returns, so a hung provider used to park one more thread per
  timeout. Each instance now counts the abandoned calls still running and, at `FeatureFlags.DefaultMaxAbandonedCalls`
  (256), fails timed evaluations fast with `ProviderError` wrapping a `RejectedExecutionException` without calling the
  provider, logging one rate-limited warning; the `*OrDefault` calls keep serving defaults and evaluations resume as
  the abandoned calls return. Healthy traffic never reaches the cap, and untimed evaluations are never gated.
  Configure it with `FeatureFlagsConfig.withMaxAbandonedCalls(n)` or `fromAcquireAsync`'s new trailing
  `maxAbandonedCalls`; `Int.MaxValue` disables it. The evaluation-timeout docs now spell out the per-timeout thread
  cost and recommend `CircuitBreakerProvider` for remote providers.
- **`WireType` and `FlagType.wire`** (#439). Evaluation now dispatches on a typed `WireType` (`Boolean`, `String`,
  `Int`, `Long`, `Float`, `Double`, `Object`) instead of the free-form `wireType` string. The built-ins, `mapped`,
  derived instances and `Option` set it themselves; a custom instance overrides `wire`. An instance that still
  overrides `wireType` keeps working, and its name is now matched ignoring case, so `"string"` selects the string
  resolver instead of silently falling to the object path.
- **`WrapperLaws.delegation` in the testkit** (#466): checks that a provider wrapper passes every `FeatureProvider`
  call on. One test per `Op`: the method is called on the wrapper with distinctive arguments and the wrapped
  `FaultyProvider` must record the same call with equal arguments, both `initialize` overloads included. Ops the
  wrapper answers itself go in `handledByWrapper` with a reason, and an entry that is no longer needed fails. The
  extras wrappers and `CachingReasonProvider` now run it on 2.13 and 3, replacing the Scala-3-only reflection specs,
  which could not see a missing default-method override. `FaultyProvider.Call` now records the call's arguments. See
  "Checking your own wrapper" on the testkit page.
- **`DifferentialLaws` in the testkit: compare a provider against `TestFeatureProvider`** (#467). It generates flag
  sets over every wire type (hostile strings, `Long`s beyond the `Int` range, NaN/-0.0, nested objects) plus a fixed
  corpus of edge cases, reads every key and an absent one through every typed accessor, evaluates both providers
  through `FeatureFlags`, and compares value, reason class (`Served`/`Default`/`Error`) and error code. Every
  difference must match a `Deviation` that gives its reason, and a deviation that matched nothing fails as stale.
  `agreesWithTestkit` is the `Spec`; `compare` returns the report. `HoconProvider` and `EnvVarProvider` now run
  against it in PR CI, with their deviations listed. See "Comparing a provider against the testkit" on the testkit
  page.
- **The OFREP provider runs against `DifferentialLaws`** (#526). `OfrepDifferentialSpec` serves each generated flag
  set from an in-process WireMock stub in the OFREP wire format and reads it through the real contrib client, in PR
  CI. Its deviations are JSON's limits (no NaN/Infinity, untyped numbers) and one upstream behaviour: contrib 0.0.2
  does not override `getLongEvaluation`, so a long read of a JSON integer is `TYPE_MISMATCH`. ofrep depends on
  testkit for tests only.

### Changed

- **The OFREP factories return `OFREPFeatureProvider`** (#529). `OFREPProvider.make`, `scoped` and `layer` (every
  overload) now yield `OFREPFeatureProvider`, a `FeatureProvider` wrapping the contrib `OfrepProvider` that adds the
  `getLongEvaluation` the contrib one lacks. Code that names `OfrepProvider` as the result type changes it to
  `OFREPFeatureProvider`, or reaches the contrib instance with `.underlying`. Code compiled against an earlier release
  must be recompiled: the result types erase to `ZIO`/`ZLayer`, so MiMa reports nothing, but a compiled caller that
  casts the result to `OfrepProvider` fails at run time. The deprecated `apply`/`fromOptions` are unchanged.
- **`CachingProvider.make` and `CircuitBreakerProvider.make` can fail** (#451). They return
  `IO[FeatureFlagError.InvalidConfiguration, _]` instead of `UIO`, the same as the OFREP and Optimizely `make`, failing
  with a message naming each invalid field (`CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0`).
  Binary-compatible; code that annotates the result as `UIO`/`ULayer`, or a layer error type as `Throwable`, widens
  it to include `FeatureFlagError`.
- **`FeatureFlagsConfig` has a new last field, `maxAbandonedCalls`** (#437), so its constructor, `apply` and `copy`
  changed binary signature: code compiled against an earlier release must be recompiled (source is unaffected, apart
  from positional patterns on it). A value below 1 now throws `IllegalArgumentException` at construction. A timed
  evaluation can now fail with `ProviderError(RejectedExecutionException)` when the cap is reached.

- **Tuning defaults are named** (#462). The circuit breaker, cache, construction-retry, event-hub, polling and
  watchdog defaults now come from named constants instead of literals at the call sites; no value changed. Public:
  `CircuitBreaker.DefaultFailureThreshold`/`DefaultResetTimeout`/`DefaultHalfOpenMaxCalls`/`DefaultProbeTimeout`
  (shared by both breaker configs, so they cannot drift), `CircuitBreakerProvider.DefaultEvaluationTimeout`/
  `DefaultStateCheckInterval`, `CachingProvider.DefaultMaxEntries`/`DefaultTtl`, `FeatureFlags.DefaultConstructionRetry`
  and `TestFeatureProvider.DefaultInitDelay`.
- **`clearApiHooks` warns on a `Shared` instance** (#472). The API is shared, so the call clears the API-level hooks of
  every sibling client; from an instance that does not own the API it now logs one warning saying so (an `Owned`
  instance logs nothing). Behaviour is unchanged. The scaladoc of `addApiHook`, `clearApiHooks`, the `on*` handlers
  (run the canceller, or scope it with `acquireRelease`) and `FeatureHook.logging` (`after` logs at Info) is sharper,
  and the events page shows how to scope a handler.
  Also fixed: a handler registered inside `ZIO.acquireRelease`'s acquire could never be cancelled (the canceller hung),
  because its fiber inherited the uninterruptible region; handler fibers are now always interruptible.
- **Optimizely SDK 4.6.0** (from 4.2.2, #474), with CMAB support. Factory-built providers now wire Optimizely's CMAB
  service: without it, a CMAB experiment served a placeholder `"$"` variation reported as `TARGETING_MATCH`, and
  every client build logged a WARN. A CMAB decision that cannot be fetched is now an `ERROR` evaluation (`GENERAL`)
  rather than a silent default. On a cache miss a CMAB decision is a blocking network call (10s, one retry by default).
  The bump also removes a latent crash: 4.2.2's event serializer reads a Jackson field that the jackson-databind this
  module ships no longer has, which would have failed the first real event dispatch.
- **Behaviour change: factory-built Optimizely providers now send events to Optimizely** (#453). Providers from
  `OptimizelyProvider.make`/`scoped`/`layer` built their client without an event processor, so the SDK dropped every
  impression and every `track` conversion: experiments showed zero visitors. They now batch events and send them to
  `logx.optimizely.com`, as the SDK's own default factory does. An event carries the targeting key as the visitor
  id, the audience attributes derived from the evaluation context, and the decision or conversion. To keep the old
  behaviour, set `events = OptimizelyEvents.Disabled` on `OptimizelyProviderConfig`; `OptimizelyEvents.Batched` tunes
  the batch size, flush interval and shutdown wait (at most 4 s, so shutdown stays within its 10 s bound). Providers
  from `fromOptimizelyClient` are unchanged. **Recompile, don't just bump the version:** `events` is a new field of
  `OptimizelyProviderConfig`, which is source compatible but removes the six-argument constructor, `apply` and `copy`,
  so code compiled against an earlier release fails with `NoSuchMethodError`.
- **Production instances no longer keep tracked events** (#442). Every instance recorded the last 1000 `track` calls
  with their fully merged evaluation contexts — often personal data — for `FeatureFlags.trackedEvents`, a test
  helper. Only testkit-built instances record now. Assert on tracking with the new `TestFeatureProvider.trackedEvents`
  (and `clearTrackedEvents`), which lists what the provider actually received.
- **ZIO 2.1.27, Scala 3.3.8 / 2.13.18, zio-cache 0.2.8** (from 2.1.14, 3.3.4 / 2.13.16, 0.2.3; #475). These are now
  the minimum versions consumers resolve to. The bump brings upstream fixes on paths this library runs on every
  evaluation: a hang when an interrupt hits `raceFibersWith` (behind `timeoutFail`), `Queue#take` losing items on
  interrupt (event-hub consumers), and zio-cache's concurrent-`get` deadlock and expired-entry hits. zio-cache stays on
  the 0.2 line: 0.3.0 is built with a Scala 3 compiler a 3.3 build cannot read and ships Java 17 bytecode.
- **Layers no longer ask for a `Scope`** (#443). Every `FeatureFlags` factory, `FeatureFlagRegistry.fromProvider` and
  the testkit layers were typed `ZLayer[Scope, …]`, although they build with `ZLayer.scoped`, which owns the layer's
  scope; so callers had to supply `Scope.default` for nothing. They are now `ZLayer[Any, …]` (`URLayer[Any, …]` for
  `fromAcquireAsync`); lifetimes are unchanged, still bound to the layer. Existing code keeps compiling, and
  `Scope.default >>> layer` still works. One catch: `provide(Scope.default, FeatureFlags.fromProvider(p))`, when
  nothing else needs the `Scope`, now draws ZIO's "unused layer" warning, which fails builds that use `-Werror` — drop
  the `Scope.default`. Binary compatible.
- **API tidy-ups** (#444):
  - `EvaluationOptions` (per-call hooks, hints, timeout) can now be passed to every `*OrDefault`, `valueOrDefault` and
    `value`, including the `FlagDef` forms, not only to `*Details` and `resolveOrDefault`.
  - `EvaluationContext.builder` takes `Long` and `java.time.Instant` attributes.
  - An `on(...)` registration reports the provider that is current when it runs, not when the effect was built.
  - Unreadable provider event metadata is logged instead of silently dropped (the event is still delivered).
  - A typed error built from a provider-reported code (`PARSE_ERROR`, `GENERAL`) carries the provider's message in a
    stackless cause instead of a stack trace pointing into this library.
- **The documentation site moved to MkDocs Material and was rewritten.** Every page was checked against the code and
  simplified, new pages cover evaluation, provider lifecycle and events, and OFREP, and animations show how an
  evaluation, context, hooks, transactions and providers work. Every code sample on the site is now compiled and,
  where it can run on its own, tested by the new `examplesDocs` project, which replaces `examplesTypedFlags`. Page
  URLs are unchanged.

- **OpenFeature Java SDK 1.23.0** (from 1.22.1, #423). Three behaviour changes reach this library:
  - **A `MultiProvider` keeps same-named providers.** Two chain members reporting the same metadata name both take
    part, in order; earlier SDKs kept only the last. Chains that relied on the collapse now consult every member.
  - **Chain members' own provider hooks run**, once for each member the strategy consults. They see the caller's
    client metadata, except behind a `CircuitBreakerProvider` with a finite `evaluationTimeout` wrapped around the
    whole chain: the timeout evaluates on another thread, so the hooks see the SDK's fallback domain
    `"multiprovider"` (see #427 under Fixed).
  - **Provider-emitted error events carry their error code.** `ProviderEvent.Error` now has the code, and a provider
    emitting `PROVIDER_FATAL` moves the status to `Fatal`, which stops further evaluations; earlier SDKs dropped the
    code, so it read as a recoverable `Error`.
- **Breaking for custom `MultiProviderStrategy.Strategy` implementations.** The alias re-exports the SDK interface,
  whose `evaluate` now takes a `List[FeatureProvider]` instead of a name-keyed `Map`, and
  `MultiProviderMetadata.getOriginalMetadata` returns a `List`. Implementations must be updated and recompiled. This
  library's own bytecode is unchanged (MiMa green); the built-in `firstMatch` / `firstSuccessful` are unaffected.
- **CI tests every published module on JDK 25** as well as 17 and 21, on both Scala versions (#424). JDK 25 was green before;
  it was just untested outside the zio-bdd conformance job. On Scala 3 it prints a `sun.misc.Unsafe` deprecation
  warning from the Scala 3.3 standard library (`scala.runtime.LazyVals`), not from this library.
- **`Option[A]` over a scalar `A` is resolved through `A`'s resolver** (#439). `value[Option[String]]` against a string
  flag used to call the provider's object method, so a provider that checks types answered `TYPE_MISMATCH`; it now
  reaches the string method and reads `Some(value)`. A `None` default is sent as `A`'s zero (`false`, `""`, `0`, a
  derived enum's first case) and reads back as `None` when the provider answers with an error code, or with reason
  `DEFAULT`, `DISABLED` or `ERROR` and that zero. A provider that serves exactly the zero with one of those reasons
  therefore reads as `None`. `List[A]` stays on the object path. Two behaviour notes: `FlagValueType.fromFlagType` for
  `Option[String]` is now `String` rather than `Object`, so hooks scoped by type see these evaluations; and the
  provider getter called for an `Option` flag changes, which is visible to provider-level hooks and test doubles.
- **An OFREP object flag whose JSON has a key with an unpaired UTF-16 surrogate now resolves to `PARSE_ERROR`** with
  the caller's default (#534). Jackson 2.22.4 rejects a lone `\uXXXX` surrogate escape in a field name (jackson-core
  #1683); 2.22.3 accepted it. Such a key is malformed UTF-16 that no conforming server emits. Well-formed keys,
  including non-BMP characters, are unaffected, and string values with lone surrogates still decode.

### Deprecated

- **`FeatureFlagError.EmptyFlagVariables`** (#444). No module has ever raised it; it stays for binary compatibility.
- **`FeatureFlags.trackedEvents`, in favour of `TestFeatureProvider.trackedEvents`** (#442). It is empty on production
  instances.
- **Standalone `CircuitBreaker(config)`** (#461). It is the engine behind `CircuitBreakerProvider`, whose gate
  result is internal and still changing; use `CircuitBreakerProvider`. Also documented, without code changes: the
  `Reconnecting` event type is reserved (the Java SDK never emits it, so `on(Reconnecting, …)` never fires), and
  `FlagValueType` mirrors the provider resolver called, so a hook scoped to `Double` also sees `Float` flags. A client
  `version` set without a domain is now passed to the SDK client too, through a single `getClient` call.

- **`FeatureFlagError.isRecoverable`, in favour of `isFlagError`** (#440). It is true for flag-scoped evaluation errors
  (missing flag, wrong type, unparsable value, bad context), which has nothing to do with retrying or with
  `ProviderStatus.isRecoverable`. The behaviour is unchanged.
- **`FlagType.wireType`, in favour of `wire`** (#439). Its default is still `typeName`, and it is read only through
  `wire`'s default.

### Fixed

- **`FeatureFlags.long` serves OFREP flags** (#529). The contrib OFREP provider does not override
  `getLongEvaluation`, so the SDK answered long reads from its double resolver, which accepts only JSON decimals:
  every integer flag came back `TYPE_MISMATCH`, and `2.0` was served as `2`. `OFREPFeatureProvider` resolves long
  reads through the object resolver: a JSON integer in the `Long` range is served exactly, anything else (`2.0`, a
  string, an integer beyond `Long`) is `TYPE_MISMATCH` with the default, and server errors keep their codes.
- **One provider initialization delivers one `Ready`** (#531). The event bridge was attached after the provider
  initialized, so the SDK's post-init `PROVIDER_READY` and its replay for an already-ready provider could both reach
  it, in either order: `on(Ready)` handlers and `events` subscribers could see a duplicate `Ready`, and the testkit's
  `layer` / `layerFrom` let that late `Ready` overwrite a `setStatus` made right after the build. The bridge is now
  attached before the provider is registered (sync and async builds, and so registry clients), and removed when
  initialization fails.
- **Scala Steward can open pull requests again** (#473). Every scheduled run had failed with a 403, because this
  repository does not let GitHub Actions create pull requests. The workflow now authenticates with a fine-grained
  `SCALA_STEWARD_TOKEN`, so its PRs also run CI. A new `.scala-steward.conf` keeps zio-cache on 0.2.x and Scala 3 on
  the 3.3 LTS line.
- **Long flags read through `multiProvider` use each provider's long getter** (#533). The Java SDK's `MultiProvider`
  has no `getLongEvaluation`, so a long read went through each child's double getter, which this library's providers
  answer with `TYPE_MISMATCH` for integer values: `firstSuccessful` served the fallback's value, and `firstMatch`
  failed. `FeatureFlags.multiProvider` and `fromAcquireAsync`'s default compose now build a `MultiProvider` subclass
  whose long reads run the strategy over each child's `getLongEvaluation`, exact across the `Long` range, with the
  child's provider hooks seeing `FlagValueType.LONG` and the caller's client metadata. A `MultiProvider` built
  directly with `new MultiProvider(...)` keeps the SDK behaviour.
- **A custom `FlagType` named `"Object"` runs its own `decode`** (#439). The object path's raw-map shortcut was chosen
  by `typeName == "Object"`, so `FlagType.from("Object", ...)` either failed with a `ProviderError` wrapping a
  `ClassCastException` (a non-`Map` default) or served the raw map past the user's `decode` (a `Map` default). Only the
  built-in `Map[String, Any]` instance takes that shortcut now.
- **`OptimizelyFeatureProvider` sends `track` calls to Optimizely** (#454). It did not implement `track`, so every call
  fell through to the SDK's no-op default and no conversion was ever recorded, whichever client the provider wrapped.
  A call now becomes a conversion for the context's user, with the details' attributes as event tags and the details'
  `value` as the `value` tag. It fails, rather than silently doing nothing, when the provider is not ready, the context
  has no targeting key, or the datafile has no such event.
- **Optimizely: a datafile that never loads is reported as such** (#455). `initialize()` first called
  `optimizely.isValid`, which blocks up to the SDK's blocking timeout while no datafile has loaded; added to `initWait`
  that outlasted the `FeatureFlags` init timeout, so the caller saw a generic timeout instead of "datafile did not load
  … check the SDK key and network reachability". A factory-built provider now reads its config manager's cache, and
  `OptimizelyProvider.DefaultInitWait` drops from 30 s to 25 s so it fires before the 30 s `initTimeout`. The redundant
  `start()` that logged a WARN on every init is gone, and the docs no longer claim construction makes no network
  calls: the SDK starts fetching the datafile then.
- **`HoconProvider` no longer changes a number to fit the getter** (#467). Typesafe Config's `getInt`/`getLong`
  truncated `1.5` to `1`, saturated `1e300` to `Long.MaxValue` and read `NaN` as `0`, and `getDouble` rounded a long
  beyond 2^53. Each is now `TYPE_MISMATCH`; an exact conversion (`2.0` as an int, `7` as a double) is still served. A
  long beyond the int range read as an int is `TYPE_MISMATCH` rather than `PARSE_ERROR`. Found by the new
  differential check.
- **`TestFeatureProvider` answers like a real provider** (#457). A key-based value read as another type was coerced:
  a `Long` read as `int` was truncated (`5000000000L` became `705032704`), anything read as `string` was its
  `toString`, numeric strings were parsed, and a non-`Boolean` read as `boolean` failed with `GENERAL`. The scalar
  getters are now strict: a value is served only when its type is exactly the one read, with no widening (an `Int` read
  as `long` or `double` is a mismatch too), and anything else is `TYPE_MISMATCH` with your default. Typed fixtures
  already store the right type; a test that stored `42` for a `long` flag must now store `42L`. Also fixed: an
  evaluation failed by a simulated failure is now recorded (`wasEvaluated`, `evaluationCount`), since the provider was
  asked; `layerFrom` waits for the SDK's initial ready event as `layer` does, so a `setStatus` right after the build is
  no longer overwritten; and the behaviour aspects restore the settings they found instead of the defaults, so a
  test-level aspect no longer wipes a suite-level one, and `withFailureProbability` clamps (NaN to 0) like
  `setFailureProbability`.
- **An `EvaluationContext` round-trips through the SDK unchanged, and an attribute can no longer clobber the targeting
  key** (#497). The SDK stores the targeting key as the attribute `targetingKey`, so a context read back from it
  (hooks, `TestFeatureProvider.evaluations`) carried the key twice, and an attribute of that name overwrote the
  targeting key on the way out. `targetingKey` is now a reserved attribute name: an explicit `targetingKey` wins over
  the attribute; with none set, a string attribute of that name becomes the key (as before) and a non-string one is
  dropped; and the attribute is never read back.
- **Optimizely: a variable of the wrong type is a `TYPE_MISMATCH`, not your default** (#456). Reading a string
  variable as `int` returned the default with reason `DEFAULT`, a double read as `int` was truncated, the string
  `"42"` was parsed, and an integer beyond `int` range could not be read as `long` at all. Conversions are now strict
  and lossless: an absent variable still serves the default (`DEFAULT`), a present one of another type fails on the
  typed tier with `TypeMismatch` (the total tier still serves the default), and integers widen exactly to `long` and
  `double`.
- **A `-0.0` context attribute keeps its sign** (#469). Converting a context back from the OpenFeature SDK treated
  `-0.0` as the whole number `0` and returned `IntValue(0)`. Found by the new hostile-input sweeps.
- **A `Long` context attribute beyond the `int` range keeps its exact value** (#471). It was sent to the OpenFeature
  SDK as a `Double`, so anything beyond 2^53 was rounded, and a `Long` arriving from the SDK (hook contexts,
  transactions) was read back through a `Double` the same way. Both directions now carry `java.lang.Long`; int-range
  values still go as `Integer` (#249).
- **OFREP 401/403 responses fail the typed tier with `Unauthorized`** (#498). The contrib provider does not throw on an
  auth failure: it returns a `GENERAL` evaluation error with the message "authentication/authorization error for
  flag: ...", which core mapped to `ProviderError`. A `GENERAL` error whose message names an auth failure (a standalone
  `401`/`403`, `unauthorized`, `forbidden`, or that literal) is now `Unauthorized`, with a `cause` carrying the message,
  on the typed tier and in the `error` hook stage. The resolution's error code stays `General`.
- **`CachingProvider` and `CircuitBreakerProvider` release their event plumbing properly** (#450). Their `shutdown`
  never called `EventProvider.shutdown`, so each wrapper's event emitter kept running and could still dispatch
  afterwards. And a wrapper whose attach to the delegate failed (the delegate already belonged to another wrapper)
  still detached the delegate on `shutdown`, cutting off the wrapper that owned it. Both now share one event-channel
  helper that records an attachment only once it succeeds, and `shutdown` ends with `EventProvider.shutdown`.
- **Interrupting `setProvider` mid-swap no longer leaves the status stuck at `NotReady`** (#434). An interrupt that
  arrived while the new provider was initializing skipped both the success signal and the rollback: the new provider
  could end up bound and ready while every typed evaluation failed. A started swap now runs to its outcome (success,
  or rollback, or the timed-out `Error` of #433) before the interrupt takes effect — bounded by the init timeout.
  Waiting for an earlier swap to finish stays interruptible.
- **`fromAcquireAsync` no longer fails evaluations while it swaps the real provider in** (#512). The swap went through
  `setProvider`, so for as long as the real provider's `initialize()` ran (the first datafile fetch or probe, up to
  `initTimeout`) the status was `NotReady` and every typed evaluation failed with `ProviderNotReady`, although the
  factory promises fallback answers until the swap. The SDK now sees one provider for the life of the layer, an
  internal switch that delegates to the fallback; the composed stack is initialized off to the side and the switch
  routes to it once that succeeds, so the status stays `Ready` and the fallback answers throughout. A failed or
  timed-out initialization leaves the first fallback serving (no fallback re-registration), and a `setProvider` before
  the switch wins, with the state `Failed`. The composed stack's events reach handlers and status through the switch.
- **Circuit breaker: stale outcomes and polled `READY` no longer reset the circuit** (#447).
  - A call admitted in one circuit state could record its outcome in a later one: a slow call admitted while closed
    could close a half-open circuit while the real probe was still running, or free the probe slot so two probes ran
    at once. Outcomes now only count in the state that admitted the call.
  - `CircuitBreakerProvider` reset a circuit that a delegate's `PROVIDER_ERROR` event had opened as soon as its next
    `getState()` poll said `READY`, and since SDK 1.22 that poll says `READY` unless the provider overrides it. A
    polled `READY` now only closes a circuit that a poll opened; an event-driven trip closes on an event `READY` or
    after `resetTimeout` and a successful probe. The same now holds for the trip a failed `initialize` causes, which
    the next poll used to undo.
- **`IntegerWideningLongProvider` reads longs beyond `Int` range** (#452). It picked the integer resolver from the
  caller's default, so a flag stored as `5_000_000_000` read with default `0L` failed with `TYPE_MISMATCH` (or was
  narrowed, depending on the provider). A `TYPE_MISMATCH` from the integer path now retries on the long path.
- **`FeatureFlagError.classify` reads the HTTP status instead of trusting the exception's name** (#440). Any class
  named `*HttpResponseException` was reported as `Unauthorized`, so a 503 from such a client looked like a bad SDK key.
  An exception with a `getStatusCode`/`statusCode` accessor is now classified by its status (401/403 → `Unauthorized`,
  anything else → `ProviderError`). `Unauthorized` produced by `classify` now keeps the original exception as its
  `cause` (new `FeatureFlagError.unauthorized(reason, cause)`; `Unauthorized(reason)` patterns are unchanged).
- **Teardown no longer strands waiters** (#435). Closing the layer's scope now shuts the event hub, so `events`
  consumers and event-handler fibers end with it instead of waiting forever; `awaitReady()` returns at once on an
  instance that is shutting down or shut down; and an interrupted `shutdown` still runs to the terminal `NotReady`
  instead of leaving `ShuttingDown` and a half torn-down API.
- **Tracking attributes are sent by type, and an unsupported one fails `track`** (#441). An `AttributeValue` attribute
  (and any type the converter did not know, including one nested in a `List`/`Map`) was sent as its `toString`, so
  `withAttribute("plan", AttributeValue.string("pro"))` arrived as `"StringValue(pro)"`. `AttributeValue`s, `Option`s
  and SDK `Value`s now convert properly, longs beyond `Int` range keep full precision, and any other type makes `track`
  fail with `FeatureFlagError.InvalidContext` naming the attribute — nothing is sent or recorded. **Behaviour change:**
  types that used to be sent as their `toString` now fail, among them `Short`, `Byte`, `BigDecimal`, `UUID` and
  collections other than `List`/`Map` (`Vector`, `Seq`, `Set`, Java collections); convert them before tracking. A long
  inside a `List`/`Map` that fits in an `Int` is now sent as an integer, as a top-level one already was.
- **`CircuitBreakerProvider` now trips on providers that return error codes instead of throwing** (#446). Any returned
  evaluation counted as a success, so a delegate that reports network failures as `errorCode = GENERAL` — the OFREP
  provider does — kept the circuit closed through a full outage, and a returned `FLAG_NOT_FOUND` could close a
  half-open circuit. Returned codes are now classified like thrown errors: `GENERAL`, `PROVIDER_NOT_READY` and
  `PROVIDER_FATAL` count as failures, application codes only prove the provider is reachable. The evaluation itself is
  still returned unchanged.
- **`DeferredProvider` forwards `track` and refuses to start after `shutdown`** (#448). `track` fell through to the
  SDK's no-op default, so tracking events never reached the wrapped provider; and `initialize` on a shut-down instance
  returned normally, so the SDK reported it ready while it answered `PROVIDER_NOT_READY` forever. It now throws
  `FatalError`, as it does after a failed start or when `shutdown` overtakes the construction. The wrapper-completeness test could not see the missing `track`, because Scala emits a synthetic
  forwarder for every inherited Java default method; it now ignores those.
- **`CachingProvider` no longer re-caches a stale value after invalidation** (#449). zio-cache stores a finished lookup
  unconditionally, so an evaluation already in flight when `invalidateAll` ran — or when the delegate reported a
  configuration change — wrote the pre-change value back, and it was served as `CACHED` for the rest of the TTL.
  Cache keys now carry an invalidation epoch, so such a late write lands under a key no later evaluation reads.
- **Hooks inside `FeatureHook.compose` no longer share `HookData` across evaluations** (#438, spec 4.3.2). A
  composite kept one `HookData` per inner hook for its whole lifetime, so a composite registered once (for example
  `addHook(compose(List(logging, metrics)))`) let concurrent evaluations overwrite each other's data, which made the
  timing hooks report wrong durations. The same hook instance listed twice, in a composite or via two `addHook` calls,
  also shared one `HookData`. Each inner hook now gets its own `HookData` per evaluation, keyed by its position.
- **Event handlers no longer inherit the registering fiber's transaction or context** (#436). A handler registered
  inside `transaction(...)` kept applying that transaction's overrides on every later event, and kept recording into
  its long-finished state; one registered inside `withContext(...)` kept that context. Handlers, including the
  immediate call for a provider already in the event's state, now run without either. Other `FiberRef`s, such as log
  annotations, are still inherited.
- **A provider whose `initialize()` never returns no longer wedges the layer** (#433). Registration used the Java
  SDK's `setProviderAndWait`, which holds the API write lock for the whole of `initialize()`:
  - `setProvider` had no time limit at all, so a hanging provider kept the swap — and status `NotReady` — forever.
    It is now bounded by the layer's `initTimeout`; on timeout it fails with `ProviderInitializationFailed`
    (wrapping a `TimeoutException`) and status becomes `Error`. A timed-out swap is not rolled back: the new provider
    stays registered and status recovers to `Ready` if it finishes initializing later. The previous provider is not
    restored, because the SDK would shut it down if the new one became ready later. A swap whose `initialize()`
    throws still rolls back as before. `fromAcquireAsync`, whose real provider initializes inside that swap, recovers
    from a timeout by swapping in a fresh fallback.
  - The sync layer build did time out, but closing its scope then blocked in `api.shutdown()` on the lock still held
    by the stuck `initialize()`, hanging application shutdown.

  Providers are now registered with the non-blocking `setProvider` and their outcome awaited with a bound, so the lock
  is never held across `initialize()`. The scope-close `api.shutdown()` is itself bounded (15s, then a warning). One
  visible difference: an init failure now carries the provider's message in a `GeneralError` (or `FatalError` for a
  FATAL outcome) rather than the original exception instance, because the SDK's non-blocking path reports only the
  message.
- **`zio-openfeature-optimizely` now depends on `jackson-databind` at compile scope** (#431). The Optimizely SDK
  needs a JSON parser and declares none, and this module shipped Jackson only to its own tests, so a consumer that
  added just this module had no parser. The SDK's first datafile fetch then died on its poller thread with an
  unlogged `ExceptionInInitializerError` (the SDK catches `Exception`, not `Error`), polling stopped, and
  `initialize()` timed out blaming the SDK key or network. `OptimizelyProvider.make` now also fails with
  `InvalidConfiguration` naming the missing parser if one is excluded, and CI checks the published POM for a parser.
- **`CachingProvider` evaluates a cache miss on the calling thread**, and **`CircuitBreakerProvider` does too when
  `evaluationTimeout = Duration.Infinity`** (#427). Members of a wrapped `MultiProvider` chain now see the caller's
  client metadata and hook hints in their provider hooks, which the SDK passes through a thread-local. A finite
  timeout (the default, `500.millis`) still evaluates on the blocking pool so it can be enforced, and keeps the
  fallback domain `"multiprovider"`. The caching miss also no longer hops threads for nothing.
- **`docs/spec-compliance.md` no longer says the library ships Java SDK 1.20.2** (#423).
- **A defect in an `after` hook now runs the `error` stage, and `finallyAfter` receives the default-valued details**
  (#422; spec §4.4.5, and §4.4.8 from spec `main`). Before, the pipeline skipped `error` and handed `finallyAfter`
  `None`, which a `before`-hook defect never did. `finallyAfter` now receives the default value with reason `ERROR`
  and code `GENERAL`, the same details the total tier serves. Only hooks observe the change: `*OrDefault` /
  `resolveOrDefault` still serve the default, and the typed tier still fails with the defect.
- **Vendored upstream's new `@spec-4.4.8` gherkin scenario** into both conformance runners, ahead of the spec tag that
  will carry it (the conformance README records the exception).

### Security

- **Raised the Jackson pins from 2.22.2 to 2.22.4**, closing Dependabot alerts #66–#69 (GHSA-wv8q-qhhj-9h54,
  GHSA-cxp5-3px4-pw24 in `jackson-databind`; GHSA-p6pp-m3f8-5c89, GHSA-7hhh-6rmp-j9qf in `jackson-core`; #534 for
  2.22.4's stricter parsing, see Changed). Jackson reaches this library only through the OFREP contrib provider, which parses each flag server
  response with `ObjectMapper.readValue(String, OfrepResponse.class)`. The `DataInput` parser (GHSA-7hhh),
  polymorphic type ids (GHSA-wv8q) and object-identity forward references (GHSA-cxp5) are not on that path; the float-parsing ReDoS (GHSA-p6pp) is reachable only from
  a hostile flag server. The pins are part of the published `zio-openfeature-ofrep` POM, so consumers resolve the
  patched versions too.

## [1.1.2] — 2026-09-27

**A build-hygiene patch. Nothing changes for consumers.** Every published artifact is bytecode-identical to 1.1.1 and
resolves the same dependencies: MiMa is green against 1.1.1, and the two changes below are test-scope and CI-only.
The release exists so the tag, the CHANGELOG and the repository's security posture line up: it takes the repository
back to **zero open Dependabot alerts**, and the one alert it closes resolved as *fixed* rather than dismissed.

### Security

- **Closed Dependabot alert #65, `org.jline:jline-reader` 3.25.1 (GHSA-5q95-hrpc-m3w3, a ReDoS through the
  `HISTORY_IGNORE` setting). Not reachable by a consumer, nor by a test** (#418; #419 restored the workflow steps
  that #418's edit had clobbered, so the exclusion took effect only from #419). Traced through the submitted SBOM's
  edges rather than guessed from the alert's `build.sbt` manifest: the only edge into `jline-reader` comes from
  `org.scala-lang:scala3-compiler_3:3.3.4`, which is sbt's `scala-tool` configuration, the compiler jar that builds
  this code. It is on no compile, test or runtime classpath and in no published POM, and the JLine inside it backs
  the compiler's REPL, which the build never starts. There is no fix at the version level: every Scala 3.3.x LTS
  release through 3.3.8 ships a JLine below the 3.30.15 patch floor (3.3.7 and 3.3.8 carry 3.29.0). A `dependencyOverrides` pin is not one either,
  because a pin that reaches no POM is precisely what `checkPublishedPins` (#405) refuses. So the dependency
  submission now ignores `scala-tool` alongside `scala-doc-tool` (#415), the action's own documented remedy. Nothing
  scanned elsewhere is hidden: the weekly `dependency-check` job reads each published module's `managedClasspath`,
  which never contained the compiler.

### Changed

- **`conformance-zio-bdd` moves to zio-bdd 1.5.0** (from 1.4.4; test scope only, nothing reaches a consumer
  classpath). Its embedded Rift engine is now Rift 0.18.0, through `zio-bdd-rift` plus `rift-java-embedded` /
  `rift-java-natives` 0.3.2. This picks up the release's intercept and TLS fixes. The retired
  `zio-bdd-rift-embedded-*` artifacts are gone, and with them the netty that `zio-http` pulled onto the test
  classpath. The engine now uses stable FFM, so the module's tests run on **JDK 25** in CI instead of JDK 21
  with `--enable-preview`. The harnesses call `EmbeddedRift.requireAvailable`, so a wrong JDK or missing natives
  fails loudly instead of skipping.

## [1.1.1] — 2026-09-03

**A dependency-only patch. `zio-openfeature-ofrep` is the sole artifact that changes for consumers**, and only in
what it resolves: the OFREP contrib provider moves 0.0.1 → 0.0.2 and the published Jackson pins move to the 2.22.2
family. No API changed and no bytecode of ours changed — MiMa is green against 1.1.0 — so unlike the 1.0.0 → 1.1.0
upgrade this needs **no recompile**: bumping the version is enough. Everything else below is test-scope or CI-only
and reaches no consumer classpath.

Also of note: this release takes the repository to **zero open Dependabot alerts**. The fourteen that were open all
resolved as *fixed* rather than dismissed, meaning the vulnerable coordinates genuinely left the dependency graph.

### Security

- **Closed the remaining fourteen Dependabot alerts, all of them in Jackson and none of them reachable by a
  consumer** (#414). They came from two places, and only one of them was a real gap in this build's pins.
  Neither was `ofrep`: its Jackson was already above every one of these advisories' patch floors before #412
  raised it further, so that module has never been the source of these fourteen.

  **WireMock's Jackson 2.20.1 (`optimizely`, `conformance-zio-bdd`; test scope).** The 3.13.2 bump in #403
  pulled a Jackson family — `core`, `databind`, `datatype-jsr310`, partly through
  `com.networknt:json-schema-validator` — that sits inside the still-open `[2.19.0, 2.21.4)` window of
  GHSA-r7wm-3cxj-wff9 (async-parser DoS), GHSA-j3rv-43j4-c7qm and GHSA-rmj7-2vxq-3g9f (both
  `PolymorphicTypeValidator` bypasses) and GHSA-hgj6-7826-r7m5 (`InetSocketAddress` SSRF). That bump landed in
  the same release as #402's Jackson pin, whose scope note — "`optimizely` carries only `jackson-annotations`" —
  it silently invalidated. Both modules now pin the same 2.22.2 family `ofrep` does. In `optimizely` the pin is
  declared at `% Test`: the coordinates reach it through `wiremock % Test` only, so there is no consumer to
  protect, and a test-scoped declaration is what `checkPublishedPins` (#405) asks for — the POM records it
  without a consumer inheriting it, and an overrides-only pin is still rejected. `conformance-zio-bdd` needs its
  own copy because an override governs the module it is declared in and does not cross the
  `optimizely % "test->test"` edge.

  **Scaladoc's Jackson 2.15.1 (every module; not a dependency at all).** The other seven alerts were reported
  against `core`, `extras`, `testkit` and the examples — modules that carry no Jackson — because the dependency
  submission included the `scala-doc-tool` configuration, which is sbt's classpath for the Scaladoc *generator*
  and reaches no compile, test or runtime classpath and nothing published. `org.scala-lang:scaladoc_3` vendors
  `nl.big-o:liqp` 0.8.2 and `jackson-dataformat-yaml` 2.15.1, and that is unfixable from here: every Scala 3.3.x
  LTS release through 3.3.7 pins the same versions. The submission workflow now passes
  `configs-ignore: scala-doc-tool`, the action's own documented remedy. Nothing that was scanned before goes
  unscanned: the weekly `dependency-check` job reads each published module's `managedClasspath`, which never
  contained the doc tool.

  Not fixed, because nothing reports it: `jackson-dataformat-yaml` 2.18.3 stays on the `ofrep` and `optimizely`
  test classpaths (via `json-schema-validator`) against a 2.22.2 core. It carries no advisories, and this
  particular skew — a newer `core` under an older data-format module — is the benign direction.

### Dependencies

- **OFREP contrib provider 0.0.1 → 0.0.2, with the Jackson pins raised in the same commit** (#412). The upstream
  bump is a pure dependency refresh: all eight class files in `dev.openfeature.contrib.providers:ofrep:0.0.2` are
  byte-identical (SHA-1) to `0.0.1`, and the public API of `OfrepProvider`, `OfrepProviderOptions` and its
  `Builder` is unchanged — so no behaviour moved and nothing in the OFREP facade needed adapting. What moved is
  what the provider declares: Jackson 2.19.2 → 2.22.0, `jackson-annotations` 2.19.2 → 2.22, Guava 33.4.0-jre →
  33.4.8-jre, `commons-validator` 1.10.0 → 1.10.1.
- **`jacksonVersion` 2.21.6 → 2.22.2 and `jacksonAnnotationsVersion` 2.21 → 2.22** (#412). These had to move with
  the provider, not after it. `jacksonPins` is applied to `ofrep` as `dependencyOverrides` as well as
  `libraryDependencies`, and an override forces in **both** directions — so bumping the provider alone would have
  silently pulled its new Jackson 2.22.0 back down to 2.21.6, visible only as an `(evicted by:)` line. This is the
  exact hazard the MAINTENANCE note above `jacksonPins` warns about; the pins are a floor to raise, not a target.
  `jackson-annotations` stays patch-less (`2.22`, not `2.22.2`) — from 2.20 onwards jackson-bom versions it without
  a patch component, and `2.22.2` does not exist for that artifact.
- The pins are no longer an advisory fix. 0.0.1's `jackson-databind` 2.19.2 sat inside the still-open
  `[2.19.0, 2.21.4)` window; 0.0.2's 2.22.0 family is outside it, so `zio-openfeature-ofrep` would have carried a
  patched Jackson either way. They stay for the two reasons that outlive the advisory: holding the
  core/databind/jsr310 trio aligned, and backing the `published-pom` guard (#409).

## [1.1.0] — 2026-09-02

**Contains a security fix.** If you use `zio-openfeature-ofrep`, upgrade: it now pins a patched, aligned Jackson
family (2.21.6) and, unlike the previous resolution-only override, actually publishes those versions so consumers
inherit them. Details under **Security** below.

**Upgrading from 1.0.0 — recompile, don't just bump the version.** Every change below is *source*-compatible:
your call sites compile unchanged. Several are not *binary*-compatible, so a caller that was compiled against
1.0.0 and is **not** recompiled will fail at runtime with a `NoSuchMethodError`. Recompiling against 1.1.0 fixes
this with no source edits. The affected symbols are whitelisted, with per-symbol rationale, in `build.sbt`:
`FeatureFlagsConfig.apply`/`this`/`copy` and `FeatureFlags.build`/`buildAsync`/`fromAcquireAsync` (#353),
`CircuitBreakerProvider.make`/`apply`/`underlying` (#379), `CachingProvider.make`/`apply`/`underlying` (#382),
and `FeatureFlags.transaction`/`transactionEither` (#386).

Two changes need more than a recompile:

- **If you implement the `FeatureFlags` trait yourself**, `transaction` and `transactionEither` gained a trailing
  `nested: NestedPolicy` parameter (#386). New abstract members, so your implementation must add it. Callers are
  unaffected — the default fills it in.
- **If you rely on the typed tier returning your default for an error-coded resolution**, it now fails instead
  (#388). This is a deliberate contract change with a one-line remedy per call site; the full migration is in the
  `#388` entry below. Read it before upgrading a fail-closed gate.

### Changed

- **The targeted OpenFeature specification moved from v0.8.0 to v0.9.0** (#331, #332). The vendored gherkin suites
  were re-synced to the [v0.9.0](https://github.com/open-feature/spec/releases/tag/v0.9.0) tag (commit `d5b0a73`) and
  a scheduled drift check now files an issue when the vendored copies diverge from upstream, so a new or changed
  upstream scenario surfaces as work rather than silently going unrun. On the library side (#332), the stale
  `1.7.6`/`1.7.7` provider-status citations were reframed as deliberate library policy — v0.9.0 renumbered them to
  `2.2.7` and no longer *requires* the NOT_READY/FATAL short-circuit, but this library keeps it — the bundled
  providers were audited for event ownership, and duplicate-event behaviour was pinned by tests.
  - **Not yet compliant:** spec v0.9.0 requires a provider to emit its own `PROVIDER_READY` / `PROVIDER_ERROR` /
    `PROVIDER_CONTEXT_CHANGED` (requirements 2.8.1–2.8.4). The bundled providers still rely on the SDK synthesizing
    them, because doing otherwise before the Java SDK ships its opt-in marker would deliver duplicate events to user
    handlers. Tracked in #340 and blocked upstream on
    [java-sdk#1999](https://github.com/open-feature/java-sdk/issues/1999).
- **`FallbackLogging.Off` now throttles the absorbed-defect breadcrumb** (#401). Under `Off` the served-default line
  was already silent, but the absorbed-*defect* line — still written, because a defect is a bug rather than outage
  noise — was written on every occurrence, making `Off` the noisiest policy for a defect on a hot flag. It now goes
  through the same per-key limiter at the default 60 s window: first occurrence immediately, then one line per key per
  minute with `(suppressed N similar)`. `Always` and `Throttled(w)` are unchanged. Alongside, the served-default warn
  line is now recorded in `docs/spec-compliance.md` as a deliberate, configurable deviation from spec §1.4.11
  ("client operations SHOULD NOT write log messages"): the default stays `Throttled(60.seconds)`, and `Off` is the
  conformant setting.
- **The typed tier now fails on a provider-reported error *code*, not only on a thrown error** (#388). Most providers
  report `FLAG_NOT_FOUND`, `TYPE_MISMATCH`, `PARSE_ERROR` and friends as a code on the resolution and never throw. Until
  now `value` / `valueDetails` / every `*Details` method returned such a resolution as a *success* carrying the caller's
  default plus the code — so the tier a caller reaches for precisely because a default would be wrong handed one back
  anyway, with no signal, and a fail-closed gate written with `value` was silently fail-open. It was also internally
  inconsistent: a decode-side `TYPE_MISMATCH` and the `PROVIDER_NOT_READY`/`PROVIDER_FATAL` codes already failed
  typed. Now every code does, mapped as: `FLAG_NOT_FOUND` → `FlagNotFound(key)`, `TYPE_MISMATCH` →
  `TypeMismatch(key, expected = the flag's type, actual = the provider's message)`, `PARSE_ERROR` → `ParseError`,
  `TARGETING_KEY_MISSING` → `TargetingKeyMissing`, `INVALID_CONTEXT` → `InvalidContext`, `PROVIDER_NOT_READY` →
  `ProviderNotReady`, `PROVIDER_FATAL` → `ProviderFatal`, anything else → `ProviderError`.
  - **What did not change:** the total tier (`*OrDefault`, `resolveOrDefault`) still never fails and still serves the
    default with `reason = Error` and the `errorCode`; hooks still see the `error` stage (not `after`) and a
    `finallyAfter` with the details; a `MultiProvider` chain still advances on `FLAG_NOT_FOUND` internally.
  - **Migration:** if you called `xDetails` and inspected `errorCode` on the result, call `resolveOrDefault` — that is
    the resolution-with-code form, and it is what the total tier is for. If you relied on `value`/`boolean` returning the
    default for a missing flag, `valueOrDefault`/`booleanOrDefault` is that contract. If you want the typed error, it is
    now simply `value(...).either`. No config switch is provided: a mode in which the typed tier lies is not a mode worth
    keeping, and both remedies are one-line renames.
  - Two smaller consequences. `resolveOrDefault`'s `errorMessage` for a provider-coded fallback is now the library's
    uniform `FeatureFlagError.message` (`Flag 'k' not found`) instead of the provider's text — the same text the
    served-default warn line already used for thrown errors; only `FlagNotFound`/`TargetingKeyMissing` cannot carry the
    provider's wording, the others do — and a `variant` or flag `metadata` a provider attaches to an *error* resolution
    is likewise not carried by the typed error (hooks still see them in `finallyAfter`). And a transaction no longer
    *serves* an error-coded evaluation from its cache: it
    was recorded as a clean `CACHED` default on the second read, which would have turned a `FlagNotFound` on the first
    read into a success on the second; the evaluation is still recorded, so `TransactionResult` keeps the audit entry.
  - The `error` hook stage's `TypeMismatch` now names the flag's wire type as `expected` rather than `"unknown"`.
- **`CachingProvider` now wraps any `FeatureProvider`, not only an `EventProvider`** (#382). Applies to the caching
  decorator exactly what #379 did for the circuit breaker: the delegate parameter and the `underlying` field are typed
  `FeatureProvider`, so a provider that implements only `FeatureProvider` can be cached. No adapter is interposed —
  the delegate is held as given. All six resolvers including `getLongEvaluation`, both `initialize` overloads,
  `isDomainScoped`, `getProviderHooks`, `track`, `shutdown`, `getMetadata` and `getState` are forwarded unchanged.
  - **Know what you lose with a plain delegate.** It cannot emit `PROVIDER_CONFIGURATION_CHANGED`, so the automatic
    cache invalidation that event triggers never fires. TTL expiry and `shutdown` become the only ways an entry is
    dropped, so a flag changed at the provider can keep being served from cache for up to `ttl`. Size `ttl`
    accordingly, or invalidate manually. An `EventProvider` that never emits has always behaved this way; the
    widening just makes it reachable by construction.
  - **Not binary-compatible**, same as #379: the widened types are new descriptors, so a caller compiled against
    1.0.0 and *not* recompiled fails with a `NoSuchMethodError` on `CachingProvider.make`, `.apply` or `.underlying`.
    Recompiling fixes it. The three symbols are whitelisted in `mimaBinaryIssueFilters`.
  - **Source compatibility is one-directional.** Passing an `EventProvider` to `make`/`apply` still compiles
    unchanged, because the parameter widened. But `underlying` widened in *result* position, so code that reads it at
    the narrower type — `val ep: EventProvider = cached.underlying` — no longer compiles and needs a type test.

- **`CircuitBreakerProvider` now wraps any `FeatureProvider`, not only an `EventProvider`** (#379). Its delegate
  parameter and its `underlying` field are typed `FeatureProvider`, so a provider that implements only
  `FeatureProvider` (plus perhaps `Tracking`) can be protected by the breaker. No adapter is interposed — the
  delegate is held as given. Only the event-driven tripping mechanism needs an `EventProvider`, and it degrades
  cleanly for a plain delegate: nothing is attached, no delegate events arrive, and the breaker relies on the
  failure-count and state-driven mechanisms exactly as it already does for an `EventProvider` that never emits.
  Every other part of the delegate's surface — all six resolvers including `getLongEvaluation`, both `initialize`
  overloads, `isDomainScoped`, `getProviderHooks`, `track`, `shutdown`, `getMetadata` and `getState` — is forwarded
  unchanged.
  - **Not binary-compatible.** The widened types are new descriptors at the bytecode level, so a
    caller compiled against 1.0.0 and *not* recompiled fails with a `NoSuchMethodError` on
    `CircuitBreakerProvider.make`, `.apply` or `.underlying`; recompiling against this release fixes it with no
    source edits. The three symbols are whitelisted in `mimaBinaryIssueFilters`.
  - **Source compatibility is one-directional**, as for `CachingProvider` above: passing an `EventProvider` to
    `make`/`apply` still compiles, but `underlying` widened in *result* position, so
    `val ep: EventProvider = cb.underlying` no longer compiles and needs a type test.
  - `CachingProvider` gets the same treatment in #382, below.

- **Hooks are now filtered on a flag's wire type, and a `null` string is an error** (#356). Two user-visible
  consequences of `FlagType.wireType` (above), both affecting custom flag types only:
  - `FlagValueType.fromFlagType` reports `wireType` rather than `typeName`, and that value is what
    `FeatureHook.supportedFlagTypes` filters on and what `HookContext.flagType` carries. A hook scoped to
    `FlagValueType.String` now fires for a string-backed custom flag where it was previously filtered out as
    `Object`. Note `HookContext.flagType` describes the **wire** type while `defaultValue` and
    `FlagResolution.value` carry **domain** values, so do not cast the latter based on the former.
  - Evaluation now runs `FlagType.decode` on the value extracted from the provider instead of casting it. For
    every built-in type the decode is an identity and results are unchanged, with one exception: a provider
    returning a `null` **String** now yields a typed `TypeMismatch` instead of a `null` flag value, matching
    what the object path already did.
- **`Long` flag evaluation now uses the OpenFeature SDK's native long surface** (#333). `ff.long` / `ff.longDetails`
  call `client.getLongDetails`, so the **provider's** `getLongEvaluation` decides the result instead of this library
  choosing a resolver for it. Previously an int-range default was routed to the provider's integer resolver and
  anything larger to its double resolver — exact only up to 2^53, and **silently lossy beyond it**.
  - Every provider shipped here (`TestFeatureProvider`, `HoconProvider`, `EnvVarProvider`,
    `OptimizelyFeatureProvider`) now implements `getLongEvaluation` natively and resolves the full 64-bit range
    exactly, so **there is no behaviour change if you use one of them**.
  - **Who is affected:** a *third-party* provider written against SDK &lt; 1.22.0 that does not override
    `getLongEvaluation`. It inherits the SDK's default, which answers from `getDoubleEvaluation` and returns a
    `TYPE_MISMATCH` (with your default echoed back) outside ±(2^53−1) instead of a quietly wrong number. An
    integer-stored flag will now meet that provider's *double* resolver.
  - **Remedy:** wrap it in the new `IntegerWideningLongProvider` to restore the previous int-range routing.
- **`FlagValueType.Long` is now reported for long evaluations** instead of `FlagValueType.Int` (#333). Only hooks
  that explicitly narrowed `supportedFlagTypes` are affected: one narrowed to `Int` no longer runs for long
  evaluations, and one narrowed to `Long` now does. `HookContext.flagType` is public, so this is observable.
- Out-of-int-range `Long` **tracking** attributes are now sent through `Value`'s native long support rather than
  being widened to `Double` (#333) — the same silent precision loss, on the tracking path.

### Added

- **CI now guards that every dependency pin actually reaches the published POM** (#405). An sbt `dependencyOverrides`
  entry is resolution-only — it is never written into the POM — so a version pinned only that way protects this build
  and no consumer of the artifact. That is exactly how #402's `jackson-core` security pin reached zero consumers, and
  nothing could observe it: the weekly OWASP scan reads each module's *resolved* classpath, which is post-override, so
  it stays green in precisely the state that is broken, and it runs on a schedule rather than on PRs. The new
  `checkPublishedPins` task (`sbt +checkPublishedPins`) parses each published module's `makePom` output and fails,
  listing every violation, when an override is absent from that POM — the #402 shape, an override on a coordinate
  that only arrives transitively — or is declared there at a different version; entries in `test`/`provided` scope or
  marked `optional` do not count, since a consumer does not inherit them. It runs in a
  new PR-gating `published-pom` CI job, which also **self-tests the guard**: it recreates the pre-#402 shape, drops a
  pin from `libraryDependencies`, and requires the guard to reject it with the right diagnosis — a guard never seen to
  fail is not known to guard anything. That self-test is also what catches the *other* direction: deleting an override
  deletes the expectation with it, so the task alone would go quietly green. The expectation is derived from
  `dependencyOverrides` rather than a hand-maintained list of "security pins", so it covers every module the root
  project aggregates without anyone having to keep a list current.

- **`transactionEvaluations` — "not in a transaction" is now distinguishable from "in one that read nothing"**
  (#387). `currentEvaluatedFlags` answers `Map.empty` in both situations, which is the wrong shape for what the read
  is usually for — an audit record of which flags shaped a request — because a refactor that moves the audit call
  outside the transaction boundary keeps compiling and starts writing empty flag sets into production records with
  no error and nothing in the logs. The new `transactionEvaluations: UIO[Option[Map[String, FlagEvaluation[_]]]]`
  (trait + companion accessor) answers `None` outside a transaction and `Some(...)` inside one — `Some(Map.empty)`
  being a real answer. `currentEvaluatedFlags` is unchanged and its scaladoc now names the ambiguity and points here.
  Additive: a concrete default on the `FeatureFlags` trait (derived from `inTransaction` + `currentEvaluatedFlags`),
  so an existing implementor keeps compiling and no MiMa filter is needed; the live instance overrides it with a
  single fiber-local read. The breaking form the issue also proposed — changing `currentEvaluatedFlags`'s return type
  — was not taken because it is a *source* break for every caller (`.contains`, `.get`, `.keys` stop compiling on
  `Option[Map]`), unlike every other break this cycle; whether to retire the old method is a 2.0 question.
- **Re-entrant transactions: `NestedPolicy` on `transaction` / `transactionEither`** (#386). Opening a transaction
  inside another one has always failed with `NestedTransactionNotAllowed` — a sound default for code that means to open
  two, but the wrong shape for a transaction used as *middleware*: a per-request wrapper and a handler that wraps a
  sub-operation in its own transaction neither know about each other, and the result was a failed request. Every such
  wrapper had to hand-roll an `inTransaction` guard whose two branches do not even return the same type (`A` vs
  `TransactionResult[A]`). Both methods — on the
  `FeatureFlags` trait and the companion accessors — gain a trailing `nested: NestedPolicy = NestedPolicy.Fail`:
  - `Fail` (default) — today's behaviour, unchanged: `NestedTransactionNotAllowed` before the inner body runs.
  - `Reuse` — the outermost transaction wins: the inner body runs inside the enclosing transaction (evaluations
    recorded there, served from its cache), and the returned `TransactionResult` reflects that transaction as of the
    body's completion. **The inner call's `overrides`, `context` and `cacheEvaluations` are ignored** — the enclosing
    transaction is the one running. Stated loudly here, in the scaladoc and in `docs/transactions.md`, since silently
    dropping an argument is the one surprising part.

  With no enclosing transaction the policy is irrelevant and either value opens a fresh one exactly as before. It
  applies to fibers forked from inside a transaction too (the transaction is fiber-local and inherited).
  Source-compatible for every caller (defaulted parameter); binary-incompatible on the four `transaction*` descriptors —
  same remedy as #353: recompile — covered by new MiMa filters. An **external implementor** of the `FeatureFlags` trait
  must add the parameter to its two overrides.
- **Served-default fallbacks are logged at warn, rate-limited per flag key** (#350). The total tier
  (`*OrDefault` / `resolveOrDefault`) previously logged only absorbed *defects* — every occurrence, unthrottled — and
  said nothing when a provider-reported problem (`FLAG_NOT_FOUND`, `PROVIDER_NOT_READY`, a timeout, …) made it serve
  the default. It now logs every served-default fallback at warn (`Flag 'k' fell back to its default false
  (FlagNotFound: …)`), **one line per flag key per window** (default 60 s), with `(suppressed N similar)` on the next
  line for that key; the absorbed-defect line goes through the same limiter in its own per-key bucket, keeps its cause,
  and is still emitted under `Off` (a defect is a bug, not outage noise). The per-key map is bounded (1024 keys; beyond
  that, further keys share one throttled overflow bucket). Policy is the new `FallbackLogging` ADT —
  `Off | Always | Throttled(window)`, default `Throttled(60.seconds)` — set with
  `FeatureFlagsConfig.withFallbackLogging(...)` or, on the config-less factory, `fromAcquireAsync(...,
  fallbackLogging = ...)`. Hooks and metrics still see every evaluation; only the built-in log line is limited.
  Wired through a defaulted `protected def logFallback` on the `FeatureFlags` trait (overridden by the live instance),
  so external implementors keep today's behaviour unchanged. `FeatureFlagsConfig` gains a ninth field — same
  binary-compat note and remedy as #353, covered by the existing MiMa filters.
- **`fromAcquireAsync` tells you whether the real provider is live** (#352). The factory is fallback-first, so
  `providerStatus` reads `Ready` from time zero (and dips through `NotReady` during the swap) — a `/ready` probe
  could not tell fallback values from real ones. It now returns `URLayer[Scope, FeatureFlags with AcquireStatus]`,
  a second small service provided by this factory only: `AcquireStatus.get` reads an `AcquireState` —
  `Constructing` (fallback serving; `acquire`/`verify` in flight), `Live` (real provider acquired, **verified** and
  swapped in), or `Failed(cause)` (terminal; set just before `onConstructionError` runs) — and
  `AcquireStatus.changes` streams the current state first, then the transition. A new trailing
  `onSwapped: UIO[Unit]` callback is the success-side twin of `onConstructionError`. The widened output is
  source-compatible (`ZLayer` is covariant in its output), and the new defaulted parameter is covered by the existing
  `fromAcquireAsync` MiMa filter. Two related tightenings: a swap failure now reaches `onConstructionError` (and
  `Failed`) as `ProviderSwapFailed(error: FeatureFlagError)` instead of a bare `RuntimeException` with the error
  flattened into its message; and a **defect** during construction (e.g. `acquire = ZIO.succeed(new Provider(...))`
  whose constructor throws) now also resolves to `Failed` and fires `onConstructionError` — previously it killed the
  construction fiber silently — and is then re-raised so it keeps whatever visibility it had.

  ```scala
  val ready: URIO[AcquireStatus, Boolean] = AcquireStatus.get.map(_.isLive)
  ```
- **`fromAcquireAsync` can verify the real provider before swapping it in** (#349). Construction success is a weak
  health signal — a provider can construct on bad credentials or an empty config and then serve *successful wrong
  values* that a first-successful chain accepts without ever consulting the fallback. A new trailing
  `verify: OFFeatureProvider => Task[Unit]` parameter (default: accept everything) runs on each acquired candidate
  **before** the swap; a failure rejects the candidate exactly like an `acquire` failure (released, `constructionRetry`
  advances, fallback keeps serving, terminal error reaches `onConstructionError`), and `acquire` + `verify` share the
  per-attempt `constructionTimeout`. `Verify.flagExists[A](key)` is the ready-made sentinel check — it evaluates `key`
  on the bare candidate through the getter matching `FlagType[A].wireType` and fails on any error code — and
  `Verify.all(...)` chains checks:

  ```scala
  FeatureFlags.fromAcquireAsync(acquire, fallback, verify = Verify.flagExists[Boolean]("kill-switch"))
  ```

  `verify` sees the candidate exactly as `acquire` returned it: the SDK has not called `initialize()` on it yet, and no
  ambient context applies — a provider that only answers after `initialize()` must be initialized inside `acquire`.

  **Teardown contract, tightened:** every attempt now runs `acquire` in its own child scope. A candidate that does not
  make it into service — rejected, timed out, or whose swap failed and rolled back — has its finalizers run
  immediately instead of lingering until layer close; the swapped-in candidate's scope is handed to the layer scope,
  so the real provider is torn down on layer release exactly as before. Binary-incompatible against 1.0.0 (new defaulted parameter, same remedy as #353: recompile) —
  covered by the existing `fromAcquireAsync` MiMa filter.
- **`TestFeatureProvider.makeNamed` — a test provider can be given its own metadata name** (#371). Every instance
  previously reported `"TestFeatureProvider"` with no way to change it, and two things key providers by that name: a
  `MultiProvider` chain keeps only the **last** provider of a given name (the SDK logs the collision at INFO and moves
  on), and the event-identity guard behind `FeatureFlags.setProvider` compares the old and new provider's names. So a
  chain of two test providers was really a chain of one — a test of fall-through or precedence between them passed or
  failed for the wrong reason — and a hot-swap between two of them was invisible to the guard. `makeNamed(name,
  initialFlags = Map.empty)` fixes both, and `TestFeatureProvider.DefaultName` is what every other factory still
  reports, so **nothing changes unless you call `makeNamed`**. Purely additive; no core change.
- **Typed test fixtures from a `FlagDef` (`testkit`)** (#351). `TestFeatureProvider`'s key-based
  `setFlag[A](key, value)` accepts anything, so a fixture could pin a value production would never decode — the test
  passed against the fixture and production failed with `TYPE_MISMATCH`. Building the fixture from a `FlagDef`
  type-checks the value against the flag's declared type and stores it through `flagType.encode`, so the test reads
  it back through the same decode path production uses:

  ```scala
  import zio.openfeature.testkit.FlagOverride.Ops   // brings `:=` into scope

  TestFeatureProvider.layer(UserPlan := Tier.Premium)   // compiles
  TestFeatureProvider.layer(UserPlan := "premium")      // does not
  ```

  - New `FlagOverride` plus `:=` on any `FlagDef`. `:=` also checks the encoding **round-trips** back through
    `decode` and fails loudly if it cannot — a codec that cannot read its own output would otherwise produce a
    fixture the test believes in and production cannot read.
  - `setFlag`, `setFlags`, `replaceFlags`, `removeFlag`, `wasEvaluated` and `evaluationCount` all gain `FlagDef`
    forms, and every flag-seeding factory gains a typed twin — `make`, `layer`, `scopedLayer`, `asyncLayer`,
    `asyncReadyLayer`, `providerLayer` and `scopedAsyncLayer` — so reaching for another factory after learning `layer`
    does not drop back to `Map[String, Any]`.
  - Two overrides for the same key are rejected rather than silently merged last-wins: that means two `FlagDef`s share
    a key, most likely at different types, which is a fixture bug worth failing on.
  - Additive: the key-based API is unchanged and remains the way to test an undeclared key, a foreign key, or a
    negative case such as `FLAG_NOT_FOUND`.
  - `FlagOverride` is deliberately **not** parameterised: the parameter would be unused after construction and would
    force a `[?]`/`[_]` wildcard into varargs signatures that cannot be spelled once across Scala 2.13 and 3. The
    compile-time guarantee lives in `:=`, which requires an `A` for a `FlagDef[A]`. For the same cross-build reason
    `:=` is an `implicit class` rather than a Scala 3 `extension`, which also means 2.13 projects get the sugar.
  - `asyncReadyLayer`'s typed twin takes `initDelay` explicitly, because the untyped one defaults both parameters —
    a plain varargs overload would have made the currently-legal `asyncReadyLayer()` ambiguous.

  **Source-compatibility note for Scala 2.13 only.** Every method that now has both a `Map[String, Any]` and a
  `FlagOverride*` form — `make`, `layer`, `scopedLayer`, `asyncLayer`, `asyncReadyLayer`, `providerLayer`,
  `scopedAsyncLayer`, and the instance-level `setFlags`/`replaceFlags` — loses 2.13's ability to infer the type
  arguments of a bare `Map.empty` at that call site. `TestFeatureProvider.layer(Map.empty)` stops compiling and needs
  either `layer` (the parameterless form, which is what such a call usually meant) or `layer(Map.empty[String, Any])`;
  likewise `provider.setFlags(Map.empty)`. A populated `Map(...)` literal is unaffected, as is every call on Scala 3,
  which infers it fine. Four call sites inside this repo needed the adjustment, so it is a real if narrow break rather
  than a theoretical one.

- **`ContextSource` — pull-based ambient evaluation context** (#353). Where every existing context level is
  push-based (the caller sets it), a `ContextSource` is an effect the library *consults* on each evaluation —
  for request identity the application holds somewhere the ZIO environment cannot see (an MDC-style map, a
  tracing/tag manager, a correlation-id carrier), so there is no natural place to call `withContext`:

  ```scala
  val fromMdc = ContextSource(ZIO.succeed(EvaluationContext(Mdc.get("userId"))))

  FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withContextSource(fromMdc))
  ```

  - **Precedence is the point.** The source is merged at a fixed slot:
    `Invocation > Scoped > ContextSource > Client > Transaction > Global`. Ambient identity **overrides**
    static client and global context, while an explicit `withContext` or a per-call context still **wins**
    over it. That slot is why this is library machinery rather than a `before` hook: a hook's contribution is
    merged on top of the finished effective context, so it could only ever take the *highest*-precedence slot,
    and `HookContext` exposes one flattened context with no provenance, so a hook cannot rebuild the ordering
    either.
  - `current` returns `UIO`, so a source can never fail an evaluation; a source with nothing to contribute
    returns `EvaluationContext.empty`, which merges to a no-op. It is consulted on every evaluation and on
    `track`, so keep it cheap (a `FiberRef` or `ThreadLocal` read, not a network call).
  - Compose with `++` (right-hand side wins on collisions, matching merge everywhere else);
    `ContextSource.empty` is the identity and the default, so **an existing client's behaviour is unchanged**.
  - `FeatureFlagsConfig` gains a defaulted `contextSource` field and a `withContextSource` setter.
  - **Source-compatible, but NOT binary-compatible** — the first whitelisted break since the `1.0.0` freeze.
    Every existing call site still compiles unchanged, but adding a field to a case class regenerates
    `apply`/`copy`/`<init>` with a new descriptor, and adding a trailing defaulted parameter is still a new
    signature in bytecode. A caller compiled against `1.0.0` and **not recompiled** will hit a
    `NoSuchMethodError` on `FeatureFlagsConfig.{apply, copy, <init>}` or on
    `FeatureFlags.{build, buildAsync, fromAcquireAsync}`. **Remedy: recompile against the new release** — no
    source edits are needed. On Scala 2.13 the `FeatureFlagsConfig` companion also stops extending
    `AbstractFunction7` (2.13 gives a case-class companion an `AbstractFunctionN` parent for its arity; Scala 3
    emits none), which affects only code that relied on that companion as a `Function7`. Whitelisted in
    `build.sbt` with `mimaBinaryIssueFilters` scoped to exactly those symbols, so any unrelated break in the
    same classes still fails the gate.
- **`FlagTypeLaws` (`testkit`) — law-check a hand-written `FlagType`** (#348). Holds a custom codec to the
  round-trip contract `FlagType` documents, driven by a `Gen`:
  `FlagTypeLaws.all(Gen.int.map(Celsius(_)))`. Two laws, deliberately distinct:
  - `roundTrip` checks `decode(encode(a)) == Right(a)` in memory. This is the law as stated, and it passes
    trivially for every built-in instance because their `encode` is the identity.
  - `throughValueBridge` checks the same after crossing the OpenFeature `Value` conversion that every object-path
    evaluation really crosses — which is where lossy encodings show up, since **every number returns as a
    `Double`** (so a `Long` beyond 2^53 does not survive) and an unrepresentable structure member is dropped.
    A codec can satisfy `roundTrip` and still fail this one.

  The laws exercise the library's real conversion code, not a copy of it: those helpers moved from
  `FeatureFlagsLive` into `internal.ValueBridge` so the testkit checks the same bridge production uses. The laws
  are in the shared source tree, so they work on Scala 2.13 as well as 3, and are applied to the library's own
  instances in `FlagTypeLawsSpec`. See `docs/testkit.md` → "Law-checking a custom FlagType".
- **`FlagType.derived` — Mirror-based derivation for enums and case classes** (#348, Scala 3 only). A
  string-backed enum or a structured flag no longer needs a hand-written codec:
  `enum Plan derives FlagType` and `final case class Rollout(...) derives FlagType` are enough.
  - An **enum with parameterless cases** derives a string codec over the case labels: `wireType` is `"String"`
    (so it resolves through the provider's string method — this is what #356 unlocked), `encode` emits the label
    as declared, `decode` matches case-insensitively, and `defaultValue` is the first declared case. A case with
    parameters is unsupported and does not compile; use `FlagType.from`/`mapped` for those.
  - A **product** derives a `Map[String, Any]` codec, field by field through each field's own instance, so nested
    products, `Option` and `List` fields work with no extra wiring. Unknown payload keys are ignored. An absent
    key resolves to the field's declared Scala default if it has one, else to whatever the field's instance makes
    of an absent value (which is how an `Option` field becomes `None`), else a decode error naming the field.
    `defaultValue` is built from the fields' own `defaultValue`s — a type-level zero, not the Scala defaults,
    since it is never consulted when evaluating.
  - Derivation is deliberately **not** a `given`, so it never competes in implicit search and the built-in
    instances keep priority for their own shapes. Derivation is Scala-3 only and purely additive: the 2.13 API is
    unchanged, and `from`/`mapped` remain supported on both versions.
- **`FlagType.wireType` — scalar-backed custom flag types are now evaluatable** (#356). A custom `FlagType[A]`
  whose wire representation is a *scalar* — the most common feature-flag shape, an enum stored as a string
  (`"off" | "dual_write" | "shard_only"`), or a newtype over an int — previously had no working evaluation path:
  dispatch keyed on `typeName`, so a domain `typeName` fell through to the object resolver and asked the provider
  for an object it does not hold, while forcing `typeName = "String"` hit a `ClassCastException`. `FlagType` now
  carries `wireType` (the representation the provider is asked for, defaulting to `typeName`); evaluation
  dispatches on it, sends `encode(default)`, and decodes the result, with a decode failure becoming a typed
  `TypeMismatch`. `FlagType.mapped` inherits its underlying `wireType` automatically, so
  `FlagType.mapped[Plan, String](…)` now just works — see `docs/architecture.md` "Scalar-backed custom types".
  Every existing instance keeps `wireType == typeName` and behaves exactly as before.
- **`FlagDef[A]` — typed flag definitions** (#347). States a flag's key, type and default once as a single
  first-class value, instead of restating them at every call site where they can drift:
  `val NewCheckout = FlagDef("checkout.v2", false, "new checkout flow")`. `value`, `valueOrDefault`,
  `resolveOrDefault` and `valueDetails` all gain `FlagDef` overloads on both the `FeatureFlags` trait and its
  companion accessors; each delegates to the existing key-based tier, so there is no new evaluation machinery
  and no behaviour change to the string-key API, which stays fully supported. `FlagDef.default` is always the
  value served on a miss or error — `FlagType.defaultValue` is a type-level zero and is never consulted on the
  evaluation path. Equality is structural over key/default/description (the `FlagType` instance is excluded);
  `sameKey` compares by key alone across differing type parameters.
- `IntegerWideningLongProvider` (`extras`) — wraps a provider that predates SDK 1.22.0 and resolves `Long`
  evaluations through its existing integer resolver for int-range defaults. The escape hatch for the behaviour
  change above; bundled providers never need it.
- `TestFeatureProvider.boundDomain` (`testkit`) — reports the domain the SDK bound the provider to, so tests can
  assert domain propagation (SDK 1.22.0's `initialize(ctx, domain)`).
- `docs/testing-real-providers.md` (#335): guide to fault-testing a **real** provider through rift-scala's TLS-MITM
  intercept fixtures — the gap `TestFeatureProvider` cannot cover, since it simulates provider *states* rather than
  exercising a real SDK's HTTP client, TLS, payload parsing and init/polling paths. Covers the six-scenario matrix
  (healthy, stall, stall + configured fallback, 401, connection reset, malformed payload), which wiring tier each
  kind of HTTP client needs, why waits and faults require `redirectTo(imposter)` rather than a `serve` rule, and
  the `awaitReady` / `Schedule.recurUntil` / `.exit` assertion patterns. No rift dependency is added to any
  published module — the wiring belongs in the reader's own test build.

### Fixed

- **The object path now sends the caller's default to the provider, and no longer relabels `FLAG_NOT_FOUND` as
  `TYPE_MISMATCH`** (#364). Two defects on the object-backed custom-type evaluation path. `getObjectDetails` was
  called with an empty `Value()` instead of `flagType.encode(default)`, so a provider could not serve the caller's
  default for a custom type the way it does for the built-in scalars. Separately, anything that failed to extract
  became a type error, so a plain missing flag surfaced as `TypeMismatch` and looked like a codec bug; a
  provider-reported `FLAG_NOT_FOUND` is now preserved as `FlagNotFound`.
- **The weekly OWASP dependency-check job now scans every published module** (#402). It collected JARs from
  `core` and `testkit` only — the two modules with almost no third-party surface — so `extras`, `ofrep` and
  `optimizely`, which carry the transitive HTTP stacks, were never scanned; it could not see any Jackson
  artifact at all. It also masked its own failures: the step piped `sbt` into `grep`, and a pipeline's exit
  status is its *last* command's, so an sbt failure part-way through the module list left the earlier
  modules' JARs in place and scanned green over a partial corpus. The step now runs under
  `set -eo pipefail` with `sbt` writing to a file, fails loudly on an empty JAR list, no longer swallows
  `cp` errors, and reports the count that actually reached the scan directory rather than the count it
  intended to.

  Scope note: this scan reads each module's *resolved* classpath, so it would **not** by itself have caught
  the published-POM gap described under Security — a scanner that only sees the resolved build cannot see
  what the POM does or does not declare. The widening closes a real and separate blind spot; it is not a
  regression guard for that bug.

- **Snapshots are actually published now** (#397). `Publish Snapshot` has run green on every `main` commit since it
  was added and never uploaded a single artifact: sbt-ci-release 1.9.2 routes snapshots through sbt-sonatype, which
  answers `sonatypeCentralHost` with "Sonatype Central does not accept snapshots, only official releases. Aborting
  release." — and then **exits 0**, so the workflow reported success while the snapshots repository stayed empty and
  the README's snapshot instructions pointed at a 404. Fixed by moving to Central Portal publishing proper:
  sbt-ci-release 1.12.0 (which drops sbt-sonatype for sbt 1.11+'s built-in support) on sbt 1.12.15, with
  `sonatypeCredentialHost` removed — `publishTo` now resolves to
  `https://central.sonatype.com/repository/maven-snapshots/` for a `-SNAPSHOT` version, and sbt reads the existing
  `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` secrets into credentials for `central.sonatype.com` itself, so no secret
  changes were needed. Tagged releases are unaffected in outcome but take a new route: `+publishSigned` into local
  staging, then `sonaRelease` uploads the bundle.
  - The workflow no longer trusts `ci-release`'s exit code. It requires the log line that only the snapshot branch
    prints, and then re-fetches the pom that run uploaded and checks its `Last-Modified` — every "doing nothing" path
    (missing secrets, a non-SNAPSHOT version, an unsupported host) now fails the run loudly instead of passing in
    silence. Note that `maven-metadata.xml` is a permanent 404 on this repository: sbt publishes through Ivy, which
    does not generate one, and Central does not synthesize it. Resolution is unaffected — sbt/coursier fetch the
    non-timestamped `-SNAPSHOT` artifact directly — but do not reach for that file to check whether a publish
    worked. Consumer-facing only in that snapshot coordinates finally resolve; no library behaviour changes.

- **`sbt scalafmtSbt` no longer breaks the build definition** (#381). `.scalafmt.conf` pinned the Scala 2 dialect
  for `src/main`, `src/test` and `scala-2`, but build files fell through to the top-level `runner.dialect = scala3`
  with `convertToNewSyntax` and `removeOptionalBraces`. Running `scalafmtSbt` therefore rewrote `build.sbt`'s
  `match { case ... }` into braceless form and `import scala.sys.process._` into `.*` — and sbt compiles build files
  with Scala 2.12, which parses neither, so the *next* sbt JVM failed to load the project at all
  (`'{' expected but 'case' found`), hanging a non-interactive run on the `(r)etry, (q)uit` prompt. Build files are
  now pinned to `scala212` with both rewrites off. Contributor-facing only — no library behaviour changes.
  - CI now also runs `scalafmtSbtCheck`. `scalafmtCheckAll` covers `src/**` only, so nothing exercised the build
    files' formatting and this defect was invisible to the gate; it would have been caught on day one. Making that
    check pass required a one-time whitespace realignment of `build.sbt` and `project/plugins.sbt`, which is
    included and changes no behaviour.
- **`TestFeatureProvider` now reports `FLAG_NOT_FOUND` for a key that has not been set** (#369), instead of a
  `DEFAULT`-reason result — the testkit half of #355. A default-reason answer with no error code reads to a
  `MultiProvider` chain as "I answered", so a chain never fell through past the test provider, and a test that
  chained it ahead of a real provider passed or failed for the wrong reason. It now reports what every real
  provider shipped here reports for an absent key (and what the SDK's own `InMemoryProvider` produces by throwing
  `FlagNotFoundError`), so it can sit in a `FeatureFlags.multiProvider(...)` chain.

  The returned *value* is unchanged and **no evaluation fails** — `ff.boolean`, `*OrDefault` and `resolveOrDefault`
  still yield the caller's default; a present key still resolves with `reason = TargetingMatch` and no error code;
  and an unset key is still recorded by `wasEvaluated` / `evaluationCount`. **Who is affected:** downstream tests
  that assert `reason == ResolutionReason.Default` / `getReason == "DEFAULT"` for a flag they never set, and hook or
  log assertions that expect the `after` stage for such a key — the resolution now carries `reason = Error` /
  `errorCode = FlagNotFound` and hooks see the **`error`** stage (so `Hook.logging()` logs the miss at error level).
  A test that means "this flag is off" should set it to `false` explicitly rather than rely on absence; the
  `examples/testkit-app` reference spec is updated to do exactly that. `ErrorMode.FlagNotFound` is unchanged: it
  yields the same resolution shape but for every key at once. New `AbsentKeyChainSpec` in the testkit proves
  fall-through, precedence and the all-absent case through `FeatureFlags.multiProvider` (chained with the SDK's
  `InMemoryProvider` — two `TestFeatureProvider`s collapse into one because they share a metadata name, see #371),
  plus the hook-stage change.
- **`HoconProvider` and `EnvVarProvider` now report `FLAG_NOT_FOUND` for an absent key** (#355), instead of a
  `DEFAULT`-reason result. A default-reason answer with no error code reads to a `MultiProvider` chain as "I
  answered", so the chain **stopped at the first provider** rather than trying the next one — a chain of two
  config-style providers only ever consulted the first. It also made the two cases indistinguishable to an
  operator: "the config holds this value" and "the config has no such key" looked identical.

  The returned *value* is unchanged and **no evaluation fails** — `ff.boolean`, `*OrDefault` and `resolveOrDefault`
  all still yield the caller's default. Verified end-to-end through `FeatureFlags`: a key absent from every provider
  in a chain still resolves to the caller's default, because substituting it is the SDK client's job rather than the
  provider's.

  **Two things do change for observers, so read this before upgrading:**
  - the resolution now carries `reason = Error` and `errorCode = FlagNotFound` where it previously carried
    `reason = Default` and no code;
  - hooks see the **`error`** stage instead of `after`. With the bundled `Hook.logging()` that means an absent key
    is logged at **error** level where it was previously info (`Hook.structuredLogging` uses warning). This is the
    spec-correct stage for an error-coded resolution (§4.3.6/§4.4.6), but it matters most for `EnvVarProvider`,
    which is often used as an opt-in override source where most variables are deliberately unset — tune
    `logError`/`errorLevel` on your hooks if that is your setup.

  **`MultiProviderStrategy.firstSuccessful` chains behave differently too.** A trailing `EnvVarProvider` holding
  only a few critical flags — a pattern `docs/optimizely.md` recommends — used to end the chain successfully for
  *any* key, because a clean `DEFAULT` counts as success. It now reports `FLAG_NOT_FOUND`, so when the primary
  provider is failing **and** the key is not in the fallback, the chain surfaces `errorCode = General` with the
  aggregated per-provider errors instead of a silent default. The value is still the caller's default and nothing
  fails. This is arguably the point — those same docs warned that the old shape made primary-provider failures
  invisible — but it is a visible change to a documented pattern.

  Two documentation errors this uncovered are corrected in the same change:
  - `MultiProviderStrategy.firstMatch` claimed the chain skips a provider "whose evaluation does not surface a
    default value". It does not — a `reason = DEFAULT` result with no error code is taken as an answer and ends the
    chain. Only `FLAG_NOT_FOUND` causes fall-through, which is exactly why this fix was needed.
  - `FeatureFlags.multiProvider` now documents that **each provider in a chain needs a distinct metadata name**:
    the SDK keys providers by `getMetadata.getName`, so two instances of the same provider type collapse into one
    and the chain silently consults only the survivor.
- **In-transaction caching and overrides now work for custom `FlagType`s** (#359). The transaction machinery fed
  `FlagType.decode` — a wire → domain function — the *domain* value in two places, which only worked for the
  built-ins where the two coincide. For a custom type (`FlagType.mapped`, `FlagType.from`, or a hand-rolled
  instance) a same-key re-read inside a caching transaction silently missed the cache and re-evaluated against
  the provider on every read, and an override could only be given in its wire form (`"dual_write"`, never
  `Phase.DualWrite`) — with the decode reason discarded in both cases. The cache now stores the wire value
  (`encode(value)`) next to each evaluation and decodes that on re-read, exactly as it decodes a provider answer;
  overrides accept either the domain or the wire value; and `OverrideTypeMismatch` now names the value's class
  *and* the decode reason (a `null` override is a typed `OverrideTypeMismatch` instead of a
  `NullPointerException` defect). Built-in evaluations are unaffected. The round-trip law this relies on,
  `decode(encode(a)) == Right(a)`, is now stated on the `FlagType` scaladoc, and two library instances that broke
  it are fixed: `FlagType[Option[A]]` and `FlagType[List[A]]` now `encode` through their underlying instance
  (previously the inherited identity), so an `Option`/`List` of a custom type caches and overrides like the custom
  type itself. One adjacent diagnostic also improved: the `Int`/`Long`/`Double`/`Float`/`Object`/`List` decoders
  return `Left("Cannot convert null to …")` for `null` instead of throwing.
- **Three defects on the object path for `Option`-shaped values**, all surfaced while building `FlagType.derived`:
  - an `Option`-valued **field** reached the provider as the literal string `"Some(x)"` — the object path's encoder
    had no `Option` case, so it fell through to a `toString` fallback. `Some` is now unwrapped and `None` becomes an
    empty value, which reads back as an absent key (and so as `None`). The same gap is fixed in
    `TestFeatureProvider`, which is how a derived product's flag is normally seeded in tests;
  - a top-level `Option[A]` **flag** could not resolve to `None`. `FlagType[Option[A]]`'s `typeName` routes it to
    the object path, where an empty answer with no provider error code was reported as `TypeMismatch("null")`. The
    instance now decides what an absent value means before failing, so an empty optional flag yields `None`;
  - a `None` **inside a `List` field** was silently dropped, shortening the list — positional data loss with no
    error. List members that cannot be converted now stay in place as `null` and are handed to the element's own
    `FlagType`, which either accepts them (`Option` → `None`) or rejects them loudly.
- The built-in `Map[String, Any]` flag path now encodes its members exactly as a derived product does; it
  previously stringified `Option` members while the custom-type path sent the unwrapped value.
- **Object-backed custom flag types now receive the caller's default and report `FLAG_NOT_FOUND` correctly.** Two
  defects on the custom-type evaluation path, both surfaced by the pre-implementation audit on #348:
  - the caller's default **never reached the provider** — an empty `Value()` was sent instead of
    `flagType.encode(default)` — so a provider had no way to serve the caller's default for a custom type the way it
    does for the built-in scalars;
  - anything that failed to extract was relabelled **`TYPE_MISMATCH`**, so a provider-reported `FLAG_NOT_FOUND` came
    back as a type error and a plain missing flag looked like a codec bug. The object path now mirrors the scalar
    path: the provider's own error code is preserved and the caller's default is served, while a payload that
    genuinely cannot decode still fails loudly with `TypeMismatch`.
- **A `FlagType` whose `encode` contradicts its declared `wireType` now fails with a diagnostic error** (#360).
  Overriding `wireType` to a scalar while leaving `encode` producing something else — the mistake the `wireType`
  scaladoc warns about, reachable at a documented extension point — used to surface as a bare
  `ClassCastException` from inside the SDK's bridge method, carrying no flag key and no hint at the cause.
  Evaluation now checks the encoded value against the box the chosen resolver will unbox, and fails with a
  `TypeMismatch` naming the domain type, the declared `wireType`, and what `encode` actually produced.
- **Corrected the CI comment describing the binary-compatibility gate** (#358). `.github/workflows/ci.yml` said
  `mimaPreviousArtifacts` "is currently empty … so this is a no-op", which was wrong: `commonSettings` baselines
  every module against its own `1.0.0` and those artifacts are published, so the gate has been live all along.
  The comment now says so and records the one real caveat — `mimaFailOnNoPrevious := false` means a module with
  no published baseline is skipped silently, so a green result alone does not prove a module was compared.
- **The delegating wrappers no longer strip new SDK interface methods** (#333). `CachingProvider`,
  `CircuitBreakerProvider`, `DeferredProvider` and `CachingReasonProvider` forwarded only the surface they were
  written against, so every capability the SDK adds as a *default method* was silently answered by the interface
  default instead of the wrapped provider. Under 1.22.0 that meant three at once: a `CachingProvider` around a
  provider with native 64-bit resolution routed long evaluations through the double-backed default,
  `initialize(ctx, domain)` dropped the bound domain, and `isDomainScoped` hid a wrapped provider's scoping from
  the SDK. All three are now forwarded, and a reflection-based completeness spec fails when a future SDK method
  goes unforwarded.

### Security

- **`zio-openfeature-ofrep` now pins a patched, aligned Jackson family — and actually ships it to consumers**
  (#402). The OFREP contrib provider pulls `jackson-databind` 2.19.2, which carries five open advisories
  including two `PolymorphicTypeValidator` bypasses (CVE-2026-54513, CVE-2026-54512 — CVSS 8.1 each), an
  `InetSocketAddress` SSRF (CVE-2026-54514) and a `@JsonIgnore` bypass on records (CVE-2026-59888); its
  `jackson-core` line adds an async-parser DoS (GHSA-r7wm-3cxj-wff9). The family is now pinned to 2.21.6
  (`jackson-annotations` 2.21, which drops the patch component from 2.20 onwards).

  `jackson-datatype-jsr310` moves to 2.21.6 with them. It has no advisories of its own — it is pinned
  because a Jackson data-format module must track `databind` or the family splits again.

  The important half of this fix is *how* it is pinned. The module previously carried
  `dependencyOverrides += jackson-core 2.21.2`, but an sbt override is resolution-only and is never written
  into the published POM — so it pinned this build and left every downstream consumer resolving the
  provider's vulnerable versions untouched. The patched versions are now declared as `libraryDependencies`
  as well, which *are* published, so a consumer picks them up by nearest-wins (Maven) and newest-wins
  (coursier/Gradle). This imposes a version *floor*: a Maven consumer can still take a different Jackson
  via `<dependencyManagement>`, but under Gradle/coursier merely declaring an older version is not enough —
  that needs a `constraint`, `force`, or `resolutionStrategy`.

  The same override had also produced the split family it was written to prevent — `jackson-core` 2.21.2
  against `jackson-databind` 2.19.2, two minors apart — because its premise (that the provider pulled
  databind 2.21.2) was never true. This particular skew was the benign direction (a newer `core` under an
  older `databind` is additive in practice); the breaking direction is the opposite, and nothing prevented
  the next provider bump from landing there. It was an unsupported mixed-version combination, not an
  observed failure.

  Not affected: `core`, `extras` and `testkit` carry no Jackson at all, and `optimizely` carries only
  `jackson-annotations`, which has no advisories.

### Dependencies

- WireMock 3.10.0 → 3.13.2 in `ofrep` and `optimizely` (test-only, #403). Declared once as `wiremockVersion`
  rather than as two independent literals that are meant to agree — the same drift shape that produced the
  Jackson skew fixed in #402.

  WireMock is the largest single source of test-scope advisories in this build, and the bump raises its
  transitive stack: `commons-io` 2.11.0 → 2.19.0, `json-smart` 2.5.0 → 2.6.0, `commons-fileupload` 1.5 → 1.6.0,
  `httpclient5` 5.4.1 → 5.5.1, `httpcore5` 5.3.1 → 5.3.6, jetty 11.0.24 → 11.0.26. That closes five open
  advisories outright (commons-io, json-smart, commons-fileupload, httpclient5, http2-common).

  Nine advisories in this tree remain open and **cannot** be closed from here: jetty needs 11.0.29/11.0.31 and
  3.13.2 pins 11.0.26; `handlebars` is still 4.3.1 against a 4.5.2 fix; `httpcore5` and `httpclient5` sit short
  of their 5.4.3 and 5.6.3 floors. 3.13.2 is the newest 3.x, so these wait on WireMock itself. None of them are
  published — WireMock is `% Test` in both modules, so nothing here reaches a consumer's classpath.

- OpenFeature Java SDK 1.21.0 → 1.22.1
  - 1.22.1 fixes a miss in the SDK's own 64-bit support: `Value` gained a `Long` constructor in 1.22.0, but
    `Structure.convertValue` was never taught the matching branch, so a `Long`-backed `Value` fell through every
    case and threw `ValueNotConvertableError`. That reached us on the object path — `ValueBridge.anyToValue`
    maps a Scala `Long` to `new Value(l)`, and anything that then walked the structure back out through
    `convertValue` / `asObjectMap()` (notably `ContextTransformer` in the Optimizely provider) hit the throw.
    No workaround was needed on our side and none is removed: `ContextConverter` already narrows a `LongValue`
    to `Integer`/`Double` before it reaches the SDK, so the context path was never affected.
- zio-bdd 1.4.2 → 1.4.4 (test-only)
- sbt 1.10.6 → 1.12.15 (build-only; required for Central Portal publishing, #397)
- sbt-ci-release 1.9.2 → 1.12.0 (build-only, #397)

## [1.0.0] — 2026-07-12

First stable release. **Upgrading from 0.9.x:** two behavior changes may require action —
**`FlagType` decoders no longer coerce silently** (strict type decoding, #187) and a **1-second default
per-evaluation timeout** (bounds hung providers; opt out with `evaluationTimeout = None`). Note also the
**BREAKING** entries below — the `EvaluationTimeout` ADT (#251) and the `FeatureHook.before` signature
change (#247) — and the **Removed** `OpenFeatureAPIFactory` shim (#316). Details in the entries below.

### Added

- **`FeatureFlagsConfig` and the config-driven `FeatureFlags.fromProvider(provider, config)` factory** (#253). The 17
  public factory overloads (`fromProvider*` / `fromProviderWithDomain*` / `fromProviderWithHooks*` /
  `fromMultiProvider*`, sync and async) collapse to one config-driven entry point plus two kept shorthands
  (`fromProvider(provider)`, `fromProviderAsync(provider)`). `FeatureFlagsConfig` composes `domain`, `version`,
  `initialHooks`, `evaluationTimeout` (the #251 `EvaluationTimeout` ADT), `initTimeout`, and the new `InitMode`
  (`Sync`/`Async`, replacing the sync/async factory-pair doubling) and `ApiOwnership` (`Auto`/`Owned`/`Shared`, making
  the previously-implicit `WithDomain` shutdown-finalizer behavior an explicit, documented value — see #243) fields —
  so combinations the old surface couldn't express (domain + hooks, domain + a per-instance evaluation timeout,
  domain + version + a custom init timeout, hooks + init timeout on async init) are now one config value away. Added
  `FeatureFlags.multiProvider(providers, strategy)` as the replacement for `fromMultiProvider*`, composable with every
  other config field (e.g. multi-provider + domain).

- **`OFREPProviderConfig` and scope-managed OFREP factories** (#268). `OFREPProvider.layer(...)` now uses
  `ZLayer.scoped` with a bounded shutdown finalizer (mirroring the Optimizely module), so the provider's executor and
  HttpClient are torn down on scope close instead of being orphaned — closing the JVM-exit-hang class (#217/#229) for a
  caller-supplied non-daemon executor. Added `scoped(...)` overloads and an `OFREPProviderConfig` case class
  (`baseUrl`, `requestTimeout`, `connectTimeout`, `headers`, `proxy`) with `make`/`scoped`/`layer` factories that build
  the provider options internally with a daemon executor and validate the base URL **before** creating any thread pool
  — so configuring timeouts/headers/a proxy no longer requires the raw Guava builder or risks re-arming the non-daemon
  pool footgun.

- **Optimizely provider staleness watchdog** (#267). After a successful init, datafile-poll failures never surfaced at
  the OpenFeature level — the provider served an aging datafile indefinitely with no operator signal. The provider now
  observes datafile fetches (via an HTTP-client wrapper that records the last successful fetch — distinguishing "polling
  succeeding but datafile unchanged" from "polling failing", which a naive last-change heuristic cannot) and runs a
  background watchdog: if fetches stop succeeding for longer than `staleAfter` it emits `PROVIDER_STALE` (and keeps
  serving the last-known datafile, per OpenFeature STALE semantics), recovering to `PROVIDER_READY` on the next
  successful fetch. Configurable via `OptimizelyProviderConfig.staleAfter` (default `3 × pollingInterval`; disabled when
  polling is off). The watchdog thread is a daemon and is cancelled on shutdown.

### Changed

- **Testkit `TestFeatureProvider` DX** (#272). `getEvaluations` now returns the library's own
  `List[(String, zio.openfeature.EvaluationContext)]` instead of leaking the Java SDK's
  `dev.openfeature.sdk.EvaluationContext`, so context-propagation assertions use the Scala API
  (`.targetingKey`, `.getString(...)`) directly; the raw Java contexts remain available via the new
  `getRawEvaluations`. `setFlags` now **merges** into the existing flags (a key overwrites its previous value) rather
  than silently clearing everything first — flags seeded via `make(Map(...))` / `layer(Map(...))` or earlier
  `setFlag`/`setFlags` calls survive; the previous replace-all behavior is available as the new `replaceFlags`. Both
  are breaking changes for testkit users: switch `getEvaluations` call sites that need the Java type to
  `getRawEvaluations`, and `setFlags` call sites that relied on the clear-first behavior to `replaceFlags`.

### Deprecated

- **Testkit `scopedLayer` / `scopedAsyncLayer`, in favour of `layer` / `asyncLayer`** (#443). With layers no longer
  needing a `Scope`, the twins are identical to them.

- **14 `FeatureFlags` factory overloads, superseded by `fromProvider(provider, config)`** (#253):
  `fromProvider(p, evaluationTimeout)`, `fromProvider(p, evaluationTimeout, initTimeout)`,
  `fromProviderWithDomain(p, domain[, version])`, `fromProviderWithHooks(p, hooks)`, `fromMultiProvider(ps[, strategy])`,
  and their `*Async` twins `fromProviderAsync(p, evaluationTimeout[, initTimeout])`,
  `fromProviderWithDomainAsync(p, domain[, version])`, `fromProviderWithHooksAsync(p, hooks)`, and
  `fromMultiProviderAsync(ps[, strategy])`. Each forwards to an equivalent `fromProvider(p, FeatureFlagsConfig()...)`
  call — see the `@deprecated` message on each overload for its exact one-line replacement. `fromProvider(provider)`
  and `fromProviderAsync(provider)` are unaffected and remain the recommended shorthands for the common case.

### Removed

- **`dev.openfeature.sdk.OpenFeatureAPIFactory`** (#316). This package shim existed only to reach the Java SDK's
  package-private `OpenFeatureAPI` constructor for creating isolated API instances. SDK 1.21.0 added official support
  via `public static OpenFeatureAPI.createIsolated()` (which the build already pins), so the shim is removed. **Migration:**
  replace `OpenFeatureAPIFactory.create()` with `OpenFeatureAPI.createIsolated()` — a pure rename with identical
  behavior (both allocate a fresh, isolated instance with its own lock).

### Fixed

- **A failed hot-swap now rolls back SDK client routing, not just the internal provider ref** (#282). `setProvider`'s
  failure path restored the internal `providerRef`/status but not the OpenFeature Java SDK's provider binding — the SDK
  binds a new provider into its domain/default slot *before* calling `initialize()` and does not revert that binding
  when init throws. So after a failed swap, evaluations (which route through the SDK client) kept returning values from
  the *failed* provider while `providerMetadata`/`providerStatus` claimed the previous provider was active. The rollback
  now re-registers the previous provider with the SDK (via a compile-time package shim `dev.openfeature.sdk.EventProviderAccess`
  that resets an `EventProvider`'s attach state so re-registration doesn't hit the SDK's "already attached" guard), so a
  failed swap reverts routing **and** status to the previous, still-serving provider. **Behavior change:** after a failed
  swap whose rollback succeeds, `providerStatus` is now `Ready` (the previous provider is serving) and evaluations return
  the previous provider's values; if the rollback itself fails (the previous provider's re-`initialize()` throws), status
  remains `Error` and the failure is logged. **Caveat:** re-registration starts a fresh SDK state manager for the
  previous provider, so its `initialize()` runs again (e.g. an Optimizely poller restarts) — unless it is still bound to
  another domain of a shared API, whose ready state manager is reused. `setProvider` still fails with
  `ProviderInitializationFailed` carrying the original error.

- **`providerNameRef` is refreshed for lazy-metadata providers on `PROVIDER_READY`** (#297). A provider whose metadata
  name only materializes inside `initialize()` — notably the SDK's `MultiProvider` — had its name captured as the
  `"unknown"` fallback at build time and never corrected, so a fully-ready `MultiProvider` reported
  `ProviderMetadata("unknown")` and the event-identity guard stayed permanently failed-open for it. When a
  `PROVIDER_READY` from the current provider arrives carrying a real stamped name, `providerNameRef` is now
  refreshed from `"unknown"` to that name (via a race-safe `compareAndSet` that never clobbers a name set by a
  concurrent swap), restoring accurate `providerMetadata` and re-enabling event-identity discrimination.

- **Optimizely initial datafile-load event suppression is now deterministic** (#308). The provider suppressed the
  initial datafile load's spurious `PROVIDER_CONFIGURATION_CHANGED` by gating emission on `state == READY` read at
  notification time, which raced the `optimizely.isValid` fast-path: if init reached READY before the handler processed
  the initial-load notification, that notification arrived post-READY and leaked a spurious startup event (a
  low-frequency CI flake). Suppression now keys on the datafile **revision** — the handler emits only when the current
  revision differs from the one captured at init — so the initial load is suppressed regardless of which side wins the
  READY race, and only genuine later revisions emit.

- **Optimizely `ContextTransformer` produces attribute types the audience evaluator can actually match** (#266).
  Instant, list, and structure attributes were passed through unchanged, but Optimizely's audience evaluator matches
  only `String`/`Boolean`/`Number` — so a targeting rule against such an attribute evaluated to `UNKNOWN` on every
  evaluation (silently dead targeting with one WARN log per call and no startup signal). Now an `Instant` is converted
  to its ISO-8601 string (matchable by string conditions), integral numbers are preserved as `Integer` instead of being
  coerced to `Double`, and lists/structures — which Optimizely cannot match — are dropped rather than poisoning every
  condition. Additionally, `decide()` now extracts the targeting key and checks provider readiness **before** normalizing
  attributes, so the not-ready / missing-key / invalid short-circuits no longer pay for a full attribute conversion whose
  result is discarded.

- **`OptimizelyFeatureProvider` lifecycle robustness** (#265). Three fixes to the provider's init/shutdown state machine:
  (1) a failed `initialize` (datafile timeout, invalid config, handler-registration failure) previously left the
  provider in a state where a retry silently no-op'd — the SDK treated the non-throwing retry as success and emitted
  `PROVIDER_READY` while every evaluation kept failing `PROVIDER_NOT_READY`; a new `Failed` lifecycle state now cleans up
  the registered handler on failure and lets a subsequent `initialize` cleanly re-attempt. (2) `shutdown()` racing an
  in-flight `initialize()` could leak the update handler or leave the provider reporting `READY` after shutdown;
  `initialize` now re-checks the lifecycle after registering its handler (removing it and aborting if a shutdown
  interleaved) and makes the final `READY` transition conditional on not having been shut down. (3) the initial datafile
  load no longer emits a spurious `PROVIDER_CONFIGURATION_CHANGED` ahead of `PROVIDER_READY` — the config-changed event
  is emitted only once the provider is ready (genuine revisions), not for the initial load.

- **`OptimizelyFeatureProvider.getObjectEvaluation` reads the configured variable and falls back to the default** (#264).
  Object evaluation returned the entire `decision.getVariables` map and never reached `defaultValue`, contradicting the
  provider's own documentation and diverging from every other typed path (a flag with zero variables handed callers an
  empty structure instead of their default). It now reads the single variable named by `variableKey` (default `"value"`,
  overridable via the `openfeature.variableKey` context attribute) as a JSON object and falls back to `defaultValue`
  with `Reason.DEFAULT` when that variable is absent or not a readable object — mirroring the string/integer/double
  paths.

- **CircuitBreaker robustness: monotonic timing, interruptible timeouts, no half-open probe leak** (#263). Three fixes:
  (1) elapsed-time decisions (open→half-open reset, delegate-state poll rate-limiting) now use a monotonic `Ticker`
  (`System.nanoTime`, injectable for tests) instead of a wall clock, so an NTP/clock step can no longer delay recovery
  or collapse the reset window against a still-down delegate. (2) The per-evaluation timeout now uses
  `attemptBlockingInterrupt`, so a timed-out delegate call delivers `Thread.interrupt` to the blocking-pool thread
  instead of leaking one pinned thread per timeout while the system is degraded. (3) A half-open probe slot can no longer
  wedge the circuit forever: `tryAcquire` steals a probe slot older than `probeTimeout` (default `evaluationTimeout +
  1s`), and a probe dying with a `VirtualMachineError` now records the failure (re-opening the circuit) before
  rethrowing. `CircuitBreakerConfig` gains a `probeTimeout` parameter.

- **`EnvVarProvider` surfaces parse failures instead of swallowing them** (#262). A set-but-unparsable environment
  variable was indistinguishable from an unset one: the integer/double paths collapsed a parse failure to the default
  with reason `DEFAULT`, and the boolean path was worse — it returned the default labeled `STATIC`, falsely claiming the
  value came from the environment (so `FF_NEW_CHECKOUT=enabled` silently ran on the code default). A set-but-unparsable
  boolean/integer/double value now throws `ParseError`, which the SDK surfaces as a `PARSE_ERROR` evaluation (the zio
  layer maps it to a typed `ParseError`) instead of hiding the misconfiguration. An unset variable still returns the
  default with reason `DEFAULT`.

- **`CachingProvider` and `CircuitBreakerProvider` now forward the delegate's provider hooks and tracking** (#261).
  Neither wrapper overrode `getProviderHooks` or `track`, so both inherited the Java SDK's no-op defaults: wrapping a
  provider that ships provider hooks (telemetry/validation) silently dropped them, and `client.track(...)` was silently
  discarded. Both wrappers now delegate `getProviderHooks` and `track` to the underlying provider (in
  `CircuitBreakerProvider`, `track` passes through without consulting the circuit, since it is fire-and-forget).

- **`HoconProvider.reload` now refreshes the original construction source** (#260). `reload` previously ignored how the
  provider was built — it always called `ConfigFactory.load()` against the classpath and read the `feature-flags`
  default path, so `HoconProvider("my-flags").reload()` silently swapped the flag set to the (usually empty)
  `feature-flags` subtree, and `HoconProvider.fromConfig(customConfig).reload()` discarded the injected config for
  whatever was on the classpath. The provider now stores its source: `apply(path)` re-reads that same path (after
  invalidating the config cache), and `fromConfig` keeps the injected config (which has no external source) instead of
  discarding it. `reload` no longer takes a `path` argument (it reloads the constructed source).

- **`CachingProvider` cache-correctness fixes** (#259). Two related defects: (1) a `DEFAULT`-reason evaluation (flag
  absent, delegate echoes the caller's default) was cached under a key that excludes the default value, so a second call
  site passing a different default received the first caller's value — labeled `CACHED` — for the whole TTL.
  `DEFAULT`-reason results are now invalidated after each lookup (like error results), so every call site gets its own
  default. (2) Hit detection used `cache.contains` followed by `cache.get`, which is not atomic — an entry expiring
  between the two calls made a fresh delegate evaluation report `CACHED` — and cost two cache operations per evaluation.
  Detection now uses a flag set inside the lookup thunk (which zio-cache runs only on a miss): one cache operation, and
  a re-evaluation after expiry is correctly reported with the delegate's reason instead of `CACHED`.

- **`CachingProvider` no longer throws `FiberFailure` into application code** (#258). Its synchronous cache lookup used
  `getOrThrowFiberFailure()`, so a delegate failure surfaced as a `zio.FiberFailure` — which extends `Throwable`, not
  `Exception`, and therefore sailed past the Java SDK's `catch (Exception)` around evaluation and was thrown into the
  caller, breaking the spec's never-throw contract and bypassing the error hooks. The lookup now unwraps the failure
  and rethrows the original `OpenFeatureError` as-is (so the SDK maps the right error code and reason), wrapping any
  other throwable in `GeneralError`. The `FiberFailure`-unwrapping logic that `CircuitBreakerProvider` already used is
  extracted into a shared `FiberFailures` helper reused by both.

### Added

- **`zio.openfeature.testkit.CachingReasonProvider`** (#257). A `FeatureProvider` decorator that reports the OpenFeature
  `CACHED` reason (spec §1.4.7) on the second and subsequent evaluation of a given flag key, delegating everything else
  to the wrapped provider. It lets tests exercise the `ResolutionReason.Cached` path against a provider (like the stock
  in-memory one) that never emits `CACHED` on its own. Not to be confused with `extras.CachingProvider`, which caches
  evaluation results — this only rewrites the reason and always re-delegates the evaluation.

- **Total (never-fails) evaluation variants** (#256, spec §1.4.10 / §1.1.7). `booleanOrDefault`, `stringOrDefault`,
  `intOrDefault`, `longOrDefault`, `doubleOrDefault`, `objOrDefault`, and `valueOrDefault[A]` return `UIO[A]` — they never
  fail, absorbing any evaluation error into the supplied default, matching the spec's promise that evaluation "MUST NOT
  throw ... always return the default value." A `resolveOrDefault[A]` details variant returns `UIO[FlagResolution[A]]`
  with `reason = Error` and `errorCode`/`errorMessage` populated, so callers can still see why the default was served.
  Both typed `FeatureFlagError`s and defects (unexpected exceptions) are absorbed (the opt-in "give me a value no matter
  what" contract), while fiber interruption is always propagated so cancellation still works. Implemented once in the
  `FeatureFlags` trait and exposed through matching companion accessors; the fallible methods are unchanged for callers
  who want to handle errors.

- **`FeatureFlags.transactionEither`** — a typed, cross-version transaction error channel (#255). On Scala 2.13,
  `transaction`'s error channel is `Compat.OrError[E, FeatureFlagError]`, which erases to `Any` (2.13 has no union
  types), disabling all typed recovery — `catchAll` yields an untyped value, `mapError`/`orElse` composition breaks, and
  a for-comprehension infers `Any` for the whole chain. `transactionEither` returns
  `ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]]` identically on Scala 2.13 and 3: `Left(e)` carries the
  caller's own error from `zio`, `Right(ffe)` carries a transaction-machinery error (e.g. `NestedTransactionNotAllowed`).
  Errors are tagged at their source, which is the only place the two can be told apart — once merged into a single
  channel an `E` that equals `FeatureFlagError` is indistinguishable from a machinery error. `transaction` is now defined
  in terms of `transactionEither` (merging the tag back into `OrError`), so its behavior and error channel are unchanged.

- **Non-blocking provider initialization** (#241). Three additions so provider construction never sits on the
  application boot path:
  - **`FeatureFlags.fromAcquireAsync`** — a fallback-first async factory (`URLayer[Scope, FeatureFlags]`). It takes the
    real provider *as an effect*, builds the layer immediately on a fresh fallback (status `Ready` from time zero,
    evaluations answer fallback values), constructs the real provider in a background scoped fiber with retry/timeout,
    and hot-swaps it in when ready. Terminal construction failures run `onConstructionError` and stay on the fallback;
    the `Nothing` error channel proves at compile time that no provider failure can fail the app's layer graph. Covers
    both constructor-blocking and `initialize()`-blocking providers.
  - **`zio.openfeature.extras.DeferredProvider`** — adapts a constructor-blocking provider into an `initialize()`-blocking
    one, deferring construction to the SDK init executor. Stable metadata name, typed `PROVIDER_NOT_READY` before ready,
    a state machine that handles `shutdown()` racing an in-flight `initialize()`, and hook forwarding.
  - **`FeatureFlags.awaitReady(within)`** — semantically blocks until the provider is evaluable (`Ready`/`Stale`),
    returns early on `Fatal`, or times out, returning the status at that moment. Backed by a status change stream (no
    polling) and safe for many concurrent waiters; ideal for `/ready` probes. The internal provider-status ref is now a
    `SubscriptionRef`, and the async init watchdog's `Fatal` transition now releases the `onReady` latch and is
    observable via `awaitReady`.

### Changed

- **Per-evaluation context handling avoids per-call allocation in the common case** (#252, performance). Context
  merging now short-circuits empty layers — `merge` returns an existing instance (identity) instead of allocating a new
  context and merged map when either side is empty, so the four merge passes per evaluation cost nothing when the
  transaction/client/fiber-local/invocation layers are empty (the overwhelmingly common case). The Scala→Java context
  conversion is cached by object identity, so when the merged context is the unchanged global context it is converted
  once and reused rather than rebuilt (a fresh `MutableContext` + one `Value` per attribute) on every call; a different
  context — after `setGlobalContext` or a non-empty invocation layer — misses and is re-converted, so no explicit
  invalidation is needed, and reuse is safe because the OpenFeature contract treats the provider-facing context as
  read-only. No behavior change. (The redundant per-stage hook filter in `FeatureHook.compose` is intentionally kept —
  it is part of that public method's self-contained contract, and the pre-filter already makes it a no-op.)

- **BREAKING: per-evaluation timeout is now an `EvaluationTimeout` ADT** (#251). `EvaluationOptions.timeout` changed from
  `Option[Duration]` to `EvaluationTimeout` (`Default` | `Disabled` | `After(d)`), so a single call can now express
  "no timeout" — previously impossible (`None` fell through to the global default, whose only escape was
  `withTimeout(365.days)`). `EvaluationOptions.empty.withTimeout(d)` still bounds a call; new `.withoutTimeout` disables
  it and skips the timeout scaffolding (a per-call fiber + timer race) entirely, which matters for microsecond-latency
  in-memory providers. The 1-second default (applied to every evaluation unless overridden — a real behavioral cliff for
  cold-start remote providers) is now documented prominently, and the `EvaluationOptions` doc that wrongly claimed the
  default was "no timeout" is corrected. Migration: code that set `timeout = Some(d)` / `None` directly should use
  `.withTimeout(d)` / `.withoutTimeout` (or `EvaluationTimeout.After(d)` / `Disabled` / `Default`).

- **BREAKING: `FeatureHook.before` no longer returns hook hints** (#247). Its signature changed from
  `UIO[Option[(EvaluationContext, HookHints)]]` to `UIO[Option[EvaluationContext]]`, so a `before` hook can modify the
  evaluation context but can no longer alter the hook hints seen by later hooks and stages — hints are now immutable
  through the pipeline, as required by spec §4.5.3/§4.2.2.1. Per-hook state belongs in `HookData` (spec §4.6): the
  built-in `FeatureHook.metrics` hook was migrated to store its start time in `ctx.hookData` (mirroring
  `metricsDetailed`) instead of writing it into the hints. Migration: a `before` that returned
  `Some((newContext, newHints))` should return `Some(newContext)` and move any per-hook state to `ctx.hookData`.

- **Bump `dev.openfeature:sdk` to 1.21.0** (#239). Per-provider error detail in multi-provider strategies and
  isolated `OpenFeatureAPI` instance support now flow from the upstream fix with no further wrapper changes (our
  event hub and `apiOverride` plumbing already isolated us from the bugs this release fixes). The SDK's
  package-private `EventProvider.attach` gained a required `AutoCloseableReentrantReadWriteLock` parameter — updated
  `EventProviderBridge` (used by `CachingProvider`/`CircuitBreakerProvider` to forward delegate events) to supply a
  private lock instance; the lock is only used to serialize `emit` against a real `OpenFeatureAPI`'s state, which our
  unregistered delegates never touch. Separately, Lombok's generated builders moved to a SuperBuilder-style F-bounded
  self-type, so `ProviderEvaluation.builder[T]().value(v).reason(r)` no longer resolves under Scala 2.13 ("value
  reason is not a member of ?1") even though Scala 3 is unaffected — this only surfaced via the cross-build CI matrix,
  not local `sbt test`. Added `zio.openfeature.internal.ProviderEvaluations` (call each builder setter on a stable
  reference instead of chaining off the return value) and used it at the ~90 call sites across `core`, `extras`,
  `optimizely`, and `testkit` that build a `ProviderEvaluation`.

### Fixed

- **Centralized provider-status state machine** (#244). Every provider-status write in `FeatureFlagsLive` and the async
  init watchdog now goes through one pure, exhaustively-tested transition function
  (`internal.ProviderStatusMachine`), replacing per-site ad-hoc guards. This fixes several concrete defects:
  - **The init watchdog no longer Fatals (nor shuts down) a provider that was ever `Ready`.** It previously could not
    distinguish "never initialized" from "was `Ready`, currently in a transient `Error`", so a recoverable
    `PROVIDER_ERROR` just before the init deadline led to a permanent `Fatal` and shutdown of a live, recoverable
    provider. The watchdog now tracks an `everReady` flag and only escalates a provider that never became usable; the
    transient-error-at-deadline case leaves status `Error` with the provider running.
  - **The watchdog `Fatal` transition now publishes a `ProviderEvent.Error`** carrying `ErrorCode.ProviderFatal` (it
    previously published nothing), in addition to releasing the `onReady` latch.
  - **A `PROVIDER_ERROR` event carrying `ErrorCode.PROVIDER_FATAL` now transitions to `Fatal`** (matching the Java SDK's
    own `FATAL` state, spec 1.7.6), is sticky, and releases the `onReady` latch — previously every error event mapped to
    the recoverable `Error`, so evaluations kept flowing to a provider the SDK considered dead.
  - **Terminal states can no longer be clobbered or resurrected.** `Fatal` is sticky against every provider event; after
    an explicit `shutdown` the terminal `NotReady` can no longer be moved by a late event from the dying provider; and a
    `STALE` event can no longer stamp over `Fatal`/`ShuttingDown` (previously the stale handler had no guard at all).
  - **The 500ms `nanoTime` swap-guard heuristics are gone**, replaced by provider-identity matching on the event's
    stamped provider name: an event from a replaced/failed provider still queued on the SDK's emitter executor is
    dropped (it is still published for observers, under the *emitting* provider's name — not the current one's).
    Identity matching fails open when either the event or the current provider name is indeterminate (e.g. the SDK's
    `MultiProvider`, whose metadata name only appears after `initialize()`), so a provider still drives its own status.
    `shutdown` now also deregisters the event-bridge handlers before tearing the API down. A same-named hot-swap remains
    indistinguishable by identity alone (the swap-in-progress window still covers the swap itself).
- **Transaction overrides resolve while the provider is not ready** (#254). The provider-readiness gate
  (`checkProviderStatus`, which fail-fasts on `NotReady`/`Fatal`/`ShuttingDown`) ran before transaction override and
  transaction-cache lookup, so `transaction(overrides = Map(...))` was rejected during async init, after a failed
  hot-swap, or during shutdown — defeating the two headline uses of overrides (deterministic tests without a live
  provider, and forcing known-safe values while a provider is down). The gate is now pushed down onto exactly the paths
  that must reach the provider (`evaluateFromClient`, and `evaluateAndCache` inside a transaction); a transaction
  override or a cached evaluation resolves purely locally and no longer consults provider status. A type-mismatched
  override still fails locally with `OverrideTypeMismatch` (not `ProviderNotReady`), and a non-overridden flag inside a
  transaction is still gated because its value must come from the provider.
- **Event-delivery fixes** (#250). Three defects in `FeatureFlagsLive`'s event system: (1) the generic
  `on(eventType, handler)` rebuilt narrowed events from the typed callbacks, dropping payload fields (`errorCode`,
  `errorMessage`, `eventMetadata`) — it now delivers the original event from the stream (spec §5.1.4/§5.2.4), while
  associated-state events still fire immediately when the provider is already in that state. (2) A defect in an event
  handler killed its delivery fiber and silently unsubscribed it; handler invocations are now isolated so a failure is
  logged and the subscription is retained (spec §5.2.5). (3) The internal event hub was `Hub.dropping`, which discards
  the *newest* event on overflow and could hide the latest `ConfigurationChanged` (whose `changedFlags` aren't
  reconstructible from status); it is now `Hub.sliding`, which discards the oldest so the newest always arrives
  (spec §5.1.2).

- **Int-range `Long` values are no longer silently coerced to `Double`** (#249). At several layers a `Long` was mapped
  to `Double`, so even small longs reached providers as `Double` (breaking `instanceof Integer` targeting rules) and a
  long-typed flag evaluated through the provider's double resolver could `TYPE_MISMATCH`. Now an int-range `Long` is
  sent as an `Integer` in context attributes (`ContextConverter`), tracking details (`FeatureFlagsLive`), and HOCON
  values (`HoconProvider`), and a long-typed flag evaluation routes through the SDK's integer resolver for int-range
  values (`ClientEvaluator`). OpenFeature's `Value` has no `Long` type, so out-of-int-range longs still use `Double`
  (exact for integers up to 2^53; larger values lose precision — this bound is now documented rather than silently
  applied to all longs). Spec §3.1.2 (typed context values), §1.3.4 (typed evaluation).

- **Provider-specific resolution reasons are preserved instead of collapsed to `Unknown`** (#248). Per spec §1.4.7
  reasons are provider-extensible strings, but any unrecognized reason was mapped to `ResolutionReason.Unknown`,
  discarding the provider's actual disposition (Optimizely/flagd emit custom reasons). Added
  `ResolutionReason.Other(value)` and route non-null unrecognized reasons to it verbatim; `Unknown` now means only a
  genuinely absent (null) reason. Note: `ResolutionReason` gained a case, so exhaustive matches on it must handle
  `Other`.

- **Hook pipeline stage routing now matches the spec** (#246). Two violations in `runHookPipeline`: (1) a returned
  resolution carrying an error code (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`, ...) ran *both* the `after` and `error` stages —
  per spec §4.3.6/§4.4.6 an error-code resolution is abnormal execution, so it now runs `error` only (`after` runs only
  for a clean resolution). This fixes the built-in metrics hook double-counting a single `FLAG_NOT_FOUND` as both a
  success and a failure. (2) A defect in a `before` hook skipped the `error` and `finallyAfter` stages entirely
  (violating §4.3.8/§4.4.7 and the "finally runs on every exit" contract); the whole pipeline is now wrapped so a
  before-hook defect still runs `error` and `finallyAfter` before propagating.

- **Evaluations no longer hard-fail while the provider status is `Error`** (#245). Per the OpenFeature spec, only
  `NOT_READY` (§1.7.6) and `FATAL` (§1.7.7) fail-fast; `checkProviderStatus` was also failing every evaluation with
  `ProviderNotReady(Error)` whenever a single transient `PROVIDER_ERROR` (e.g. one failed datafile poll) arrived,
  turning a degraded-but-serving provider into a total outage until a `PROVIDER_READY` re-arrived (which many providers
  never re-emit). Evaluations in `ERROR` now proceed to the provider — it serves cached values or errors on its own.
  `NOT_READY` and `FATAL` still fail-fast, and the `FeatureFlagRegistry` init handshake still fast-fails a provider that
  errors before ever becoming ready (that init-time behavior is intentional and left to the status-state-machine rework
  in #244).

- **`FeatureFlagRegistry.getClient` no longer hangs when provider registration throws** (#242). `FeatureFlags.buildAsync`
  wrapped the synchronous Java SDK registration calls (`api.setProvider`, `provider.getMetadata`) in `ZIO.succeed`, so a
  throw became a ZIO *defect* rather than a typed error. Combined with `runBuild` handling only typed failures, such a
  defect left the registry's per-domain `Promise` never completed and never evicted — every current and future
  `getClient(domain)` caller blocked forever. Registration is now wrapped in `ZIO.attempt` (surfacing throws as typed
  `ProviderInitializationFailed`), and `runBuild` settles and evicts the entry on any failure cause (typed or defect),
  restoring the documented "a failed initialization is not cached — a subsequent call retries" contract.
- **`FeatureFlags.shutdown` no longer tears down sibling clients that share an `OpenFeatureAPI`** (#243). `shutdown`
  called `api.shutdown()` unconditionally, so shutting down one client shut down every other client on the same API —
  including, for the `FeatureFlagRegistry` (whose domain clients share one API) and the global-singleton factories,
  clients the caller never touched. `shutdown` now only shuts the API when the instance solely owns it; a shared-API
  (registry/domain) client leaves the shared API and its provider untouched — both are owned by whatever owns the API
  (e.g. the registry, which tears every provider down once on its own scope close) — and releases only its own state.

### Added

- **Scope-managed Optimizely construction** (#208). `OptimizelyProvider.scoped(...)` and the (now scope-owning)
  `layer(...)` shut the provider down — stopping datafile polling and the SDK's HTTP client, with a bounded
  finalizer — when the surrounding scope closes, even if the provider never reached a `FeatureFlags` layer or its
  initialization failed.
- **`OptimizelyProviderConfig`** (#208) with `pollingInterval` and `blockingTimeout` knobs (SDK defaults: 5 minutes
  / 10 seconds), so tests and operators no longer need to hand-roll `HttpProjectConfigManager` construction to tune
  polling.

### Changed

- **`FlagType` decoders no longer coerce silently** (#187). Lossy and surprising conversions now fail with `Left`
  (surfacing as `TypeMismatch` / `OverrideTypeMismatch` instead of a wrong-but-plausible value):
  - `Int`/`Long`: fractional doubles are rejected (previously truncated, e.g. `42.9 → 42`); out-of-range longs are
    rejected (previously wrapped).
  - `Boolean`: numbers are rejected (previously C-style `n != 0`).
  - `String`: only strings decode (previously any value via `toString`, and `null` as `""`).
  - `Float`: doubles outside Float range are rejected (previously overflowed to `±Infinity`); precision rounding
    within range is still accepted.
  String parsing of numerics/booleans (e.g. `"42"`, `"true"`) is unchanged.
- **`trackedEvents` is bounded to the last 1000 events** (oldest dropped). The recorder previously grew without
  limit, leaking memory in long-running apps that call `track` per request; it is a test/debug affordance —
  providers still receive every `track` call. (#174)
- **Default per-evaluation timeout is now 1 second** (release hardening). `evaluationTimeout` defaults to
  `Some(1.second)` across the factory methods (`fromProvider`, `fromProviderAsync`, `fromMultiProvider*`, …);
  previously it was `None`, so a hung provider could block the calling fiber indefinitely. Worst-case evaluation
  latency is now bounded out of the box. Raise it with an explicit `evaluationTimeout = Some(largerDuration)`, or
  suppress it with `None`; a per-call `EvaluationOptions.empty.withTimeout(d)` still takes precedence. New public
  constant: `FeatureFlags.DefaultEvaluationTimeout`. **Migration:** providers that make synchronous per-evaluation
  network calls on slow links may now surface `FeatureFlagError.ProviderError`; pass `evaluationTimeout = None` to
  restore unbounded behavior.

### Fixed

- **Async-init watchdog now shuts down stalled providers** (release hardening). When `initTimeout` fires and a
  provider still hasn't become `READY`, the watchdog transitions status to `Fatal` *and* calls `provider.shutdown()`,
  so a stalled provider's background threads (datafile pollers, HTTP clients) are terminated instead of outliving the
  layer. The shutdown is gated on the transition, so a provider that became ready before the timeout is left untouched.

- **Optimizely provider no longer polls (or blocks) before `initialize()`** (#208). `OptimizelyProvider.make` used
  to start the SDK's background datafile poller at construction and block up to the SDK's 10s `getConfig` timeout —
  against an unreachable CDN or a bad key (403), a provider that was merely constructed (e.g. in a test) left a
  retry loop polling forever and stalled construction. Construction now performs no network activity; polling starts
  inside `initialize()` and stops at `shutdown()`.
- **`HoconProvider` reports spec-correct error codes.** A config value of the wrong type now surfaces as
  `TYPE_MISMATCH` and an unparseable value as `PARSE_ERROR`, instead of a GENERAL error wrapping a
  `ConfigException`. (#188)
- **`shutdown` clears ZIO API-level hooks and rejects in-flight evaluations.** API-level hooks (added via
  `addZioApiHook`) previously survived shutdown, and the `ShuttingDown` status was unreachable; shutdown now
  transitions through `ShuttingDown` (evaluations fail with `ProviderNotReady(ShuttingDown)`) and ends at
  `NotReady`. (#183)
- **Nested `Instant` attributes survive the SDK round-trip.** Instants inside lists and structs were converted to
  strings on the way into the Java SDK and came back as `StringValue`, silently breaking date-based targeting on
  nested attributes. The Long → Double 2^53 precision limit is now documented on `AttributeValue.LongValue`. (#184)
- **Hook stages observe the context modified by before hooks.** The `after`, `error`, and `finallyAfter` stages
  previously received the pre-`before` evaluation context, so hooks logging or tagging by context saw different
  attributes than the evaluation actually used (spec §4.3.5–4.3.8). (#178)

## [0.9.1] — 2026-06-04

### Fixed

- **Provider hooks no longer execute twice per evaluation.** The ZIO hook pipeline was including provider hooks from
  `getProviderHooks()` in `allHooks`, while the Java SDK's `client.getXxxDetails()` call already runs them internally.
  Removed the duplication — provider hooks now fire exactly once per evaluation as the spec requires. (#167)

### Added

- **ZIO API-level hook registration.** `FeatureFlags` now exposes `addZioApiHook`, `addZioApiHooks`,
  `clearZioApiHooks`, and `zioApiHooks` for registering `FeatureHook` instances at the API level (spec §4.4.1 level 1).
  API hooks run before client-level hooks on every evaluation (`API → Client → Invocation`). `FeatureFlagRegistry`
  propagates API hooks to all existing and future domain clients via `addZioApiHook`. (#166)

### Changed

- **FP idiom improvements.** Side effects on `HookData` inside ZIO for-comprehensions now use `_ <- ZIO.succeed(...)`
  instead of `_ = ...` to keep mutations explicit in the effect graph. `contextValidator` replaced a mutable
  `List.newBuilder` with functional composition. `CircuitBreakerProvider.checkDelegateState` no longer uses a `return`
  statement — restructured to `Try(...).toOption match { ... }`.

## [0.9.0] — 2026-05-13

### Added

- **Lifecycle and concurrency test coverage for `OptimizelyFeatureProvider`.** `OptimizelyProviderLifecycleSpec` covers
  double-initialize, double-shutdown, shutdown-before-initialize, evaluations before init / after shutdown, the full
  NOT_READY → READY → NOT_READY transition, NOT_READY → ERROR on failed init, and concurrent `initialize` from N
  threads converging on READY. `OptimizelyProviderConcurrencySpec` stress-tests 1000 parallel boolean evaluations and
  500 mixed-type evaluations racing init and shutdown — surfacing torn reads, NPEs, or deadlocks if present. (#158)
- **Provider initialization hardening.** All `FeatureFlags.fromProvider*` factories accept a new `initTimeout` (default
  30 s). Sync init blocks no longer than that bound; async init transitions `ProviderStatus` to `Fatal` if the provider
  hasn't become ready in time. The sync path also verifies the provider's `getState()` after `setProviderAndWait`
  returns, failing layer build instead of silently marking the layer `Ready` on a misconfigured provider.
- **Typed `FeatureFlagError` cases for operator-actionable failures.** `Unauthorized(reason)` and `Unreachable(cause)`
  are now first-class error cases alongside `ProviderError(Throwable)`. A shared classifier
  (`FeatureFlagError.classify`) maps known network and HTTP-auth exception shapes to the right typed case at every
  evaluation/tracking call site.
- **Validated OFREP construction.** `OFREPProvider.make(baseUrl)` and `OFREPProvider.layer(baseUrl)` parse and validate
  the URL (non-empty, supported scheme, non-empty host) before touching the contrib provider. Configuration mistakes
  surface as `FeatureFlagError.InvalidConfiguration` at layer build time, not at first evaluation.
- **Optimizely provider module (`zio-openfeature-optimizely`).** New sub-project integrating with the Optimizely Java
  SDK directly (`com.optimizely.ab:core-api` + `core-httpclient-impl`), since the upstream contrib provider isn't
  published to Maven Central yet. Includes `OptimizelyProvider.make(sdkKey)` for the CDN path,
  `make(sdkKey, datafileUrl)` for self-hosted Optimizely Agent, and `fromOptimizelyClient` as an escape hatch.
  Full guide at `docs/optimizely.md` (init timeout tuning, self-hosted Agent, `CircuitBreakerProvider`
  composition, testing patterns, alerting matrix).
- WireMock-backed failure-mode integration suites:
  - **OFREP** (`OFREPFailureModeSpec`) — 4xx, 5xx, connection reset, evaluation timeout, pre/post server-stop transition.
  - **Optimizely** (`OptimizelyProviderIntegrationSpec`) — happy path, 403/404/500 datafile fetch failures, slow
    response past `initWait`, connection reset, and datafile revision change (`PROVIDER_CONFIGURATION_CHANGED` fires
    on second poll).
- `ProviderInitFailureSpec` covering sync `initialize()` throws, async ERROR-event handling, recovery, and the
  documented Java-SDK-catches-provider-throws boundary.
- `ValueRoundTripSpec` — property-based coverage of `AttributeValue` / `EvaluationContext` round-tripping through the
  Java SDK boundary; 10 properties × 200 samples each.
- `ConcurrentEvaluationSpec` — 200 fibers × 10 evaluations under a `READY → ERROR → READY` status transition; proves
  no defects, no leaks, no deadlock, and correct final status.
- `sbt-mima` plugin wired in; `mimaReportBinaryIssues` runs in CI. `mimaPreviousArtifacts` is empty until the first
  post-mima release tag — the first 1.0 release sets the baseline.
- CI matrix expanded to JDK 17 + 21 × Scala 2.13 + 3.3. Format check, mima, and examples compilation each run as
  separate jobs for clearer signal.
- `examples/ofrep-init-timeout/` and `examples/testkit-app/` reference implementations showing the recommended
  production patterns (init timeout + circuit breaker composition; testing app code against `TestFeatureProvider`).
- Top-level `NOTICE` file with attributions for OpenFeature SDK, OFREP contrib, Optimizely SDK, ZIO, ZIO Cache,
  Typesafe Config, and WireMock.
- `docs/providers.md`: new "Choosing a strategy — sync vs async, with vs without fallback" section with a decision
  matrix, watchdog semantics under each cell, the cold-start "all defaults" gap, recovery semantics under `Fatal`,
  and `initTimeout` tuning guidance.
- `docs/optimizely.md` §7: expanded healthcheck guidance for readiness gating. New §8 "Choosing a topology" with
  three named patterns (Optimizely-only async with watchdog, Optimizely-only sync with fail-fast boot, Optimizely
  + `EnvVarProvider` hybrid for critical flags) and a decision table indexed by workload type.

### Changed

- `FeatureFlagsLive`'s evaluation and tracking paths now route every `ProviderError` construction through
  `FeatureFlagError.classify`. `ProviderError(Throwable)` remains the fallback for unclassified causes; the visible
  change is that auth-related and network-level failures now surface as the new typed cases.

### Deprecated

- `OFREPProvider.apply()`, `OFREPProvider.apply(baseUrl)`, and `OFREPProvider.fromOptions(options)` — replaced by the
  validated `make` / `layer` factories. The throwing factories remain for backwards compatibility but will be removed
  in a later major release.

### Fixed

- **Optimizely `PROVIDER_CONFIGURATION_CHANGED` events now reach OpenFeature `Client` listeners on every datafile
  update, not just the initial load.** `HttpProjectConfigManager` and the `Optimizely` client each construct their own
  `NotificationCenter` by default; without sharing, the manager fires `UpdateConfigNotification` on its private centre
  while handlers registered through `Optimizely.addUpdateConfigNotificationHandler` (the API our provider's
  `initialize` uses) live on the client's centre. `OptimizelyProvider.buildClient` now allocates one
  `NotificationCenter` and passes it to both builders. Initial loads worked already because
  `OptimizelyFeatureProvider.initialize` polls `optimizely.isValid` directly to count down its init latch, so the bug
  only manifested as silent event drops on revision changes after init. Two regression tests added (structural —
  `clientCtr eq mgrCtr`; behavioural — WireMock revision-bump + `Client.onProviderConfigurationChanged`). (#161)
- **`OptimizelyFeatureProvider.decide()` no longer throws from a closed HTTP client after `shutdown()`.** Surfaced
  while writing the new lifecycle spec: `optimizely.isValid()` post-shutdown re-enters the polling HTTP client (closed
  by then) and Apache HttpClient throws an `IOException`. `decide` now checks the provider's own `stateRef` first
  (short-circuits with `PROVIDER_NOT_READY` for NOT_READY / ERROR states without touching the SDK) and wraps the
  defensive `isValid` probe in `Try`. Check order also changed: provider state precedes targeting-key validation,
  since a non-ready provider makes the caller's context irrelevant. (#158)
- **`FeatureFlagsLive.setProvider` reliably leaves status at `Error` after a failed swap.** A race in the async
  `PROVIDER_READY` bridge could overwrite the explicit `Error` transition: a stale Ready event still pending on the
  OpenFeature SDK's emitter executor (cached thread pool, no ordering guarantee) would fire after `tapError` set
  `Error` and reset it to `Ready`. `setProvider` now stamps a `recentSwapFailureAt` timestamp before writing
  `statusRef.set(Error)`, and `readyHandler` switches from unconditional `set(Ready)` to a state-aware `update`:
  `NotReady`/`Stale` → `Ready` always, `Error` → `Ready` only if more than 500 ms have elapsed since the last failed
  swap. Eliminates the intermittent `FeatureFlagRegistrySpec.failed setProvider does not update providers map`
  failure observed in CI; 30/30 consecutive local runs after the fix. (#162)

## [0.8.0] — earlier

Prior versions tracked release-by-release in [GitHub Releases](https://github.com/EtaCassiopeia/zio-openfeature/releases).
Notable milestones:

- **v0.8.0**: OFREP provider module (`zio-openfeature-ofrep`) with WireMock-backed tests; circuit-breaker hardening.
- **v0.7.x**: `MultiProviderStrategy` aliases; deterministic `AsyncReadyLayerSpec` under `TestClock`.
- **v0.6.x**: Centralized type dispatch in `ClientEvaluator`; behavior controls on `TestFeatureProvider`;
  `asyncReadyLayer` and the layer-selection guide.
- **v0.5.x**: `CircuitBreakerProvider`; configurable evaluation timeout; structured logging hook; detailed metrics
  hook; runtime provider replacement (hot-swap); domain-scoped provider registry.
- **v0.4.x**: `extras` module (HOCON, env-var providers, caching wrapper); recursive `StructValue` merge;
  context-merge-order fix per spec; batch `addHooks`.
- **v0.3.x**: OpenFeature spec compliance features; tracking API; invocation-level hooks; provider lifecycle event
  handlers; client-level evaluation context.
- **v0.2.x**: `Fatal` provider status for unrecoverable errors.
- **v0.1.x**: Initial ZIO wrapper around the OpenFeature Java SDK.

## Maintaining this file

Every user-visible change (new public API, behaviour change, bug fix that affects callers, deprecation, removal,
or security fix) MUST add an entry to `[Unreleased]` in the same PR that introduces it. The release process
promotes `[Unreleased]` to the new version section when a release tag is cut.

Internal refactors that don't change behaviour or surface area don't need a CHANGELOG entry. When in doubt: write one.

[Unreleased]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.1.2...v1.2.0
[1.1.2]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v0.9.1...v1.0.0
[0.9.1]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/EtaCassiopeia/zio-openfeature/releases/tag/v0.9.0
[0.8.0]: https://github.com/EtaCassiopeia/zio-openfeature/releases/tag/v0.8.0
