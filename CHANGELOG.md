# Changelog

All notable changes to **zio-openfeature** are documented in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
- WireMock-backed failure-mode integration suite for OFREP (`OFREPFailureModeSpec`) covering 4xx, 5xx, connection
  reset, evaluation timeout, and pre/post server-stop transitions. A parallel suite for the Optimizely module
  (`OptimizelyProviderIntegrationSpec`) lands in a follow-up PR.
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

### Changed

- `FeatureFlagsLive`'s evaluation and tracking paths now route every `ProviderError` construction through
  `FeatureFlagError.classify`. `ProviderError(Throwable)` remains the fallback for unclassified causes; the visible
  change is that auth-related and network-level failures now surface as the new typed cases.

### Deprecated

- `OFREPProvider.apply()`, `OFREPProvider.apply(baseUrl)`, and `OFREPProvider.fromOptions(options)` — replaced by the
  validated `make` / `layer` factories. The throwing factories remain for backwards compatibility but will be removed
  in a later major release.

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

[Unreleased]: https://github.com/EtaCassiopeia/zio-openfeature/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/EtaCassiopeia/zio-openfeature/releases/tag/v0.8.0
