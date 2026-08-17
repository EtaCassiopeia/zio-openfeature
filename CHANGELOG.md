# Changelog

All notable changes to **zio-openfeature** are documented in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

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

- **The delegating wrappers no longer strip new SDK interface methods** (#333). `CachingProvider`,
  `CircuitBreakerProvider`, `DeferredProvider` and `CachingReasonProvider` forwarded only the surface they were
  written against, so every capability the SDK adds as a *default method* was silently answered by the interface
  default instead of the wrapped provider. Under 1.22.0 that meant three at once: a `CachingProvider` around a
  provider with native 64-bit resolution routed long evaluations through the double-backed default,
  `initialize(ctx, domain)` dropped the bound domain, and `isDomainScoped` hid a wrapped provider's scoping from
  the SDK. All three are now forwarded, and a reflection-based completeness spec fails when a future SDK method
  goes unforwarded.

### Dependencies

- OpenFeature Java SDK 1.21.0 → 1.22.0
- zio-bdd 1.4.2 → 1.4.4 (test-only)

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

[Unreleased]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v0.9.1...v1.0.0
[0.9.1]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/EtaCassiopeia/zio-openfeature/releases/tag/v0.9.0
[0.8.0]: https://github.com/EtaCassiopeia/zio-openfeature/releases/tag/v0.8.0
