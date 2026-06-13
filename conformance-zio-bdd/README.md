# Experimental: OpenFeature conformance via zio-bdd

This module is a **dog-food experiment**: the same OpenFeature gherkin conformance suite that
`conformance/` runs via Cucumber, re-implemented against [`zio-bdd`](https://github.com/EtaCassiopeia/zio-bdd)
(0.1.0) — a ZIO-native BDD framework. **Not intended to merge** unless zio-bdd matures enough for
real use. Not aggregated by root; Scala 3 only.

Run: `sbt conformanceZioBdd/test`

## What works (and reads nicely)

The zio-bdd model is a good fit where it works:

- **Native ZIO step bodies** — no `Unsafe.run` bridge (the Cucumber version needs one). Steps return
  `RIO[R with State[S], Unit]` and run on zio-bdd's own runtime.
- **`ScenarioContext`** (FiberRef-isolated per scenario) holds typed state. It can hold opaque
  references (the live `FeatureFlags`, recorder `Ref`s) since it only needs a `Default`, not a Schema.
- **`table[T]`** maps a gherkin data table to `List[T]` via a zio-schema record — clean for the
  metadata/hook tables.
- Provider setup, Scenario Outline + Examples, and the `@Suite` annotation all work.

Currently green: `metadata.feature` (5 scenarios) and the first two scenarios of `hooks.feature`
(including the `FLAG_NOT_FOUND` → error-hook case, which exercises the §4.4.6 alignment).

## What's blocked (zio-bdd gaps found — see filed issues)

The `.feature` files here are **modified** from the verbatim upstream copies in `conformance/`
(Examples names stripped, hooks reduced to 2 scenarios) to work around parser gaps. The originals
that don't parse are kept under `blocked/` as repro evidence.

Gaps surfaced during this migration (filed against zio-bdd):

1. **Gherkin parser — named `Examples:` blocks** (`Examples: Boolean evaluations`) are rejected
   (`expected "|"`). Standard Gherkin allows an Examples description.
2. **Gherkin parser — tags with `-`/`.`** (`@spec-1.3.1.1`, `@error-handling`) stop tokenizing at the
   hyphen. Blocks all of `evaluation_v2.feature`.
3. **Gherkin parser — tags on `Examples:` blocks** (`@transaction` above an Examples table) →
   `expected end-of-input`. Blocks `contextMerging.feature`.
4. **Gherkin parser — 3+ scenarios each ending in a data table** → `expected end-of-input` at the
   third table. Blocks the full `hooks.feature` (2 scenarios parse, 3 don't).
5. **`table[T]` requires a header row** and there's no raw-`DataTable` access in a step signature, so
   the headerless "levels of increasing precedence" table can't be consumed.
6. **Minor** — the `@Suite` default `reporters = {"console"}` warns `Unknown reporter 'console'`; and
   the README's `ScenarioContext.get.map(_ => assertTrue(...))` discards the assertion effect (should
   be `flatMap`, else assertions never run).

Filed against zio-bdd: parser gaps
[#36](https://github.com/EtaCassiopeia/zio-bdd/issues/36) (named Examples),
[#37](https://github.com/EtaCassiopeia/zio-bdd/issues/37) (hyphen/dot tags),
[#38](https://github.com/EtaCassiopeia/zio-bdd/issues/38) (Examples-block tags),
[#39](https://github.com/EtaCassiopeia/zio-bdd/issues/39) (3+ table scenarios);
[#40](https://github.com/EtaCassiopeia/zio-bdd/issues/40) (headerless tables);
[#41](https://github.com/EtaCassiopeia/zio-bdd/issues/41) (reporter default + docs).

## Comparison with the Cucumber suite

| Aspect | Cucumber (`conformance/`) | zio-bdd (this module) |
|---|---|---|
| Effect model | `Unsafe.run` bridge in every step | native ZIO |
| Per-scenario state | mutable `var`s in glue class | typed `ScenarioContext` (FiberRef) |
| Feature-file fidelity | verbatim, all 4 files, 110 scenarios | modified; 2 files, 7 scenarios (parser gaps) |
| Maturity | production | experimental (0.1.0) |

The ergonomics are promising; the blocker is the gherkin parser's coverage of standard syntax.
