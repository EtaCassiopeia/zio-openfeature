# OpenFeature spec-conformance suite

These tests port the OpenFeature specification's canonical gherkin conformance assets to ZIO Test,
driving the `zio.openfeature.FeatureFlags` API against the Java SDK's `InMemoryProvider` (and the
testkit `TestFeatureProvider` where the gherkin needs a captured/failing provider).

## Upstream source

- Repo: https://github.com/open-feature/spec
- Path: `specification/assets/gherkin/`
- Pinned commit: **203c25f93495** (main, as of 2026-06-13)

The specs are hand-ported, so they can drift from upstream. To re-sync, diff the feature files at the
path above against the scenarios encoded here and update accordingly.

## Coverage

| Gherkin feature            | ZIO Test spec                       | Provider            |
|----------------------------|-------------------------------------|---------------------|
| `evaluation_v2.feature`    | `EvaluationConformanceSpec`         | InMemoryProvider    |
| `contextMerging.feature`   | `ContextMergingConformanceSpec`     | TestFeatureProvider |
| `hooks.feature`            | `HooksConformanceSpec`              | both                |
| `metadata.feature`         | `MetadataConformanceSpec`           | InMemoryProvider    |

Flag fixtures mirror `test-flags.json`; targeted flags use hardcoded `ContextEvaluator` lambdas
(the JEXL `contextEvaluator` strings are not interpreted, matching the Java SDK's own e2e harness).

The suite lives under `src/test/scala-3` and runs on Scala 3 (the primary target); it is not
cross-compiled to 2.13 because the context-merging scenarios exercise `transaction`'s union-typed
error channel, which erases to `Any` on 2.13.

## Out of scope (excluded, matching the Java SDK harness)

- `evaluation.feature` (`@deprecated`) — superseded by `evaluation_v2.feature`.
- `@reason-codes-cached`, `@evaluation-options`, `@immutability`, `@async`.
- `@provider-status` scenarios (PROVIDER_NOT_READY / FATAL / status accessibility) — provider
  lifecycle, covered by `ProviderStatusSpec` and `ProviderInitHardeningSpec`.
- Null context attribute values — the typed `EvaluationContext` has no null representation.

## Documented divergence (spec §4.4.6)

ZIO hooks are infallible (`UIO`). The `error` hook stage fires only when an evaluation fails through
the typed error channel (`ProviderNotReady` / `ProviderFatal`). Provider error-codes that the gherkin
routes to the `error` hook — `FLAG_NOT_FOUND`, `TYPE_MISMATCH` — are surfaced as a successful
`FlagResolution` carrying the error code, so `after`/`finally` observe them instead. The hook spec
asserts this adapted behavior and separately exercises the real `error` stage via a failing provider.
