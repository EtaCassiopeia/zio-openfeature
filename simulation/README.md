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
| `Stats.scala` | Coverage counters printed per run, so a generator that stops reaching a path shows up |

Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the sequential spec prints both at the start.

Swaps to a stack whose name read throws before init (a Cache or Breaker over an uninitialized Multi) are skipped and
counted as `swap.skippedNameThrows`, because a swap to one currently dies. Remove the skip in `Runner.scala`
(`Spec.nameThrowsBeforeInit`) once a swap reads the name safely.
