package zio.openfeature.conformance.bdd.library

import zio._
import zio.bdd.core.Suite
import zio.bdd.core.step.ZIOSteps

/** Behavioural conformance for this library's own API surface.
  *
  * ==Why this is a second suite and not more scenarios in `ConformanceSpec`==
  *
  * `ConformanceSpec` runs the OpenFeature specification's own gherkin, vendored byte-identically from
  * `open-feature/spec` and checked for drift by `.github/scripts/check-gherkin-drift.sh`. Those files describe the
  * *spec*, so nothing library-specific may be added to them. Everything here is a `zio-openfeature` API that the spec
  * has no opinion about, so it lives in its own repo-owned feature directory — the same arrangement the Optimizely
  * matrix suites already use.
  *
  * ==Coverage==
  *
  * One feature file per area, each pinning one area:
  *
  *   - `typed_flags.feature` — `FlagDef`, wire-type dispatch, the encode/wire diagnostic,
  *     Mirror derivation, decode-on-extract and hook wire-type filtering
  *   - `long_evaluation.feature` — the SDK 1.22.0 native long surface, `FlagValueType.Long`, and
  *     `IntegerWideningLongProvider`
  *   - `absent_keys.feature` — `FLAG_NOT_FOUND` for absent keys in the testkit and config providers, and
  *     the caller default + surviving error code on the object path
  *   - `transactions.feature` — wire-value caching and domain-or-wire overrides
  *   - `context_source.feature` — pull-based ambient context and its precedence slot
  *   - `fallback_logging.feature` — the rate-limited served-default warning
  *   - `acquire_status.feature` — `verify` before the swap and `AcquireStatus`
  *   - `test_fixtures.feature` — typed `FlagOverride` fixtures and `makeNamed`
  *
  * Two additions are deliberately not here. `FlagTypeLaws` builds a `zio-test` `Spec`, which has no
  * meaning inside a gherkin step — `testkit`'s own `FlagTypeLawsSpec` is its home; what a *scenario* can assert is the
  * law itself, which `test_fixtures.feature` does by seeding through `encode` and reading back through `decode`. MiMa
  * baselining and the gherkin drift checks are build/CI machinery with no runtime surface.
  */
@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/library"),
  reporters = Array("pretty"),
  parallelism = 1,
  // Each scenario builds its own provider on its own isolated `OpenFeatureAPI`, so this is safe to raise; it is kept at
  // 1 because the suite is fast and a deterministic order makes a failure easier to read.
  scenarioParallelism = 1,
  logLevel = "warning"
)
object LibraryConformanceSpec
    extends ZIOSteps[Any, LibraryWorld]
    with LibrarySetupSteps
    with LibraryEvaluationSteps
    with LibraryLifecycleSteps {

  // Providers are scoped resources built into a per-scenario `Scope.Closeable` held in the state; close it here so no
  // provider, API registration or background construction fiber outlives its scenario.
  afterScenario {
    ScenarioContext.get.flatMap(w => ZIO.foreachDiscard(w.scope)(_.close(Exit.unit)))
  }
}
