# simulation

Test-only, unpublished, Scala 3. Not in the root aggregate; run with `sbt simulation/test`.

## The whole-system harness

| File | Role |
|---|---|
| `Spec.scala`, `SimLeaf.scala` | A provider stack as data (wrappers over leaves) and the leaf that serves it |
| `Model.scala` | Pure reference model: what each provider-level call should produce |
| `Harness.scala`, `Runner.scala` | Run a command list against the live stack and the model; fail with a trace at the first divergence |
| `SequentialSimulationSpec` | Every result must equal the model |
| `ConcurrentSimulationSpec` | Evaluation workers racing a swapper and a mutator; invariants only (no defects, hook totals, ...) |
| `PlanCodec.scala`, `Corpus.scala` | JSON codec for plans (derived from the case classes), and the loader and recorder of the regression corpus |
| `Stats.scala` | Coverage counters printed per run, so a generator that stops reaching a path shows up |

Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the sequential spec prints both at the start.

A failing run prints its shrunk plan as a `corpus entry (...)` line and writes it to `target/simulation-failures/`.
Saved under `src/test/resources/regressions/`, it is replayed before the generated cases on every run; see the README
there.

A swap to a Cache or Breaker over a Multi, whose name is unknown before it initializes, is exercised like any other and
counted as `swap.wrapsMulti` (`crun.swap.wrapsMulti` in the concurrent spec).
