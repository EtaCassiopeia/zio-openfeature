# Experimental: OpenFeature conformance via zio-bdd (RC1)

Dog-food of [`zio-bdd`](https://github.com/EtaCassiopeia/zio-bdd): the same OpenFeature gherkin
conformance suite that `conformance/` runs via Cucumber, re-implemented against zio-bdd (a ZIO-native
BDD framework). **Not intended to merge** unless zio-bdd ships as a stable release. Not aggregated by
root; Scala 3 only.

Run: `sbt conformanceZioBdd/test` → **110 passed, 9 ignored, 0 failed**.

## Status vs the 0.1.0 attempt

The earlier attempt (zio-bdd 0.1.0) could only run a hand-trimmed subset — the parser rejected named
`Examples:` blocks, hyphenated tags, and tagged `Examples`. **zio-bdd 1.0.0-RC1 rewrote the parser**,
so the suite now runs the **verbatim** upstream feature files (`evaluation_v2`, `contextMerging`,
`hooks`, `metadata`) — same as the Cucumber suite. The 9 ignored scenarios are the same out-of-scope
ones the Java SDK harness excludes (`@async`, `@immutability`, `@evaluation-options`,
`@reason-codes-cached`), here skipped via `excludeTags`.

## RC1 best practices used

- **Native ZIO step bodies** — no `Unsafe.run` bridge (Cucumber needs one).
- **Typed `ScenarioContext`** (FiberRef-isolated) with `Default.from(World())` for the state default.
- **Per-scenario `Scope`** — providers are built into a `Scope.Closeable` held in state and released
  by an `afterScenario` hook. No global/leaked scope.
- **`table[T]`** for the metadata/hook data tables (zio-schema records).
- **Typed extractors** (`string`) chained with `/`; `excludeTags` for the out-of-scope scenarios;
  `featureDirs` (not the deprecated `featureDir`).

## Dependency note — requires a local zio-bdd build

This module depends on `io.github.etacassiopeia %% zio-bdd % "1.0.0-RC1-local"`, a `publishLocal`
build of zio-bdd that includes a **tag-filtering fix** (see below). The published `1.0.0-RC1` has a
parser bug where a scenario's tags are swallowed by the previous scenario's `Examples` lookahead,
which silently breaks `includeTags`/`excludeTags`. To build:

```sh
cd /path/to/zio-bdd
sbt 'set ThisBuild / version := "1.0.0-RC1-local"' gherkin/publishLocal core/publishLocal
```

Once that fix is released, bump the coordinate to the published version.

## Bug found and fixed during this migration

[zio-bdd#77](https://github.com/EtaCassiopeia/zio-bdd/pull/77) — `parseScenario` consumed the *next*
scenario's tag line while looking for an `Examples` block and discarded it when none followed,
stripping that scenario's tags and breaking tag-based filtering. Fixed with a cursor lookahead/restore
+ regression test. Discovered because `excludeTags` did nothing here until the parser was corrected.

## Comparison with the Cucumber suite

| Aspect | Cucumber (`conformance/`) | zio-bdd RC1 (this module) |
|---|---|---|
| Effect model | `Unsafe.run` bridge per step | native ZIO |
| Per-scenario state | mutable `var`s in glue class | typed `ScenarioContext` (FiberRef) |
| Resource cleanup | per-scenario via Cucumber `@After` | `afterScenario` + scenario `Scope` |
| Feature-file fidelity | verbatim, 110 + 9 excluded | verbatim, 110 + 9 excluded |
| Maturity | production | release candidate (needs #77) |
