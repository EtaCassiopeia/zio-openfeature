# Changelog

All notable changes to **zio-openfeature** are documented in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] — 2026-06-17

First stable release. **Upgrading from 0.9.x:** two behavior changes may require action —
**`FlagType` decoders no longer coerce silently** (strict type decoding, #187) and a **1-second default
per-evaluation timeout** (bounds hung providers; opt out with `evaluationTimeout = None`). Details in the
entries below.

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
