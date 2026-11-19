# simulation

Test-only, unpublished, Scala 3. Not in the root aggregate; run with `sbt simulation/test`.

## The whole-system harness

| File | Role |
|---|---|
| `Spec.scala`, `SimLeaf.scala` | A provider stack as data (wrappers over leaves) and the leaf that serves it |
| `Model.scala` | Pure reference model: what each provider-level call should produce |
| `Harness.scala`, `Runner.scala` | Run a command list against the live stack and the model; fail with a trace at the first divergence |
| `SequentialSimulationSpec` | Every result must equal the model |
| `ConcurrentSimulationSpec` | Evaluation workers racing a swapper and a mutator; invariants (no defects, hook totals, ...), and each value against the recorded history |
| `History.scala`, `HistorySpec` | The recorded history of a concurrent run (evaluations, flag writes, swaps on one logical clock) and the checker that explains each successful value from it |
| `PlanCodec.scala`, `Corpus.scala` | JSON codec for plans (derived from the case classes), and the loader and recorder of the regression corpus |
| `Stats.scala` | Coverage counters printed per run, so a generator that stops reaching a path shows up |

Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the sequential spec prints both at the start.

## Faults

A leaf fails cleanly (`ThrowGeneral`, `ThrowNotFound`, `ReturnGeneral`) or slowly:

- `Stall(pastBound)`, sequential only: the next call that reaches the leaf blocks until the runner moves the clock to
  just short of, or just past, the timeout that ends it (the innermost breaker's, else the core's), then releases it.
  Each breaker's timeout is 10 ms shorter than the one around it, and all are shorter than the core's 1 s.
- `InitFault` on a swap target's leaves: `Fails`, `StallThenOk`, `StallThenFail` (the concurrent spec uses `Fails`
  only). A stalled swap is checked while it waits (status `NotReady`, a read rejected) and after it settles; a failed
  one must roll back to the previous stack, and the failed stack's leaves are each shut down once and never answer.
- `ShutdownFault.Throws` on any leaf.

The sequential spec runs on the `TestClock`, so a stall costs no real time; waits for the SDK's own threads are bounded
on the live clock. A stalled read runs outside the test's fiber supervision (`zio.SimulationSupervision`), because
`TestClock.adjust` waits for every supervised fiber to suspend, and a fiber blocked in a leaf never does. The concurrent
spec runs on the live clock and has no stalls.

Each fault has a coverage counter (`fault.stall.*`, `init.*`, `swap.rolledBack`, `shutdown.throws`, `crun.init.fails`,
`crun.swap.rolledBack`, `crun.shutdown.throws`), counted for generated cases only (under `replay.` while the corpus
replays). The last test of each spec fails if one of them stays at 0.

A failing run prints its shrunk plan as a `corpus entry (...)` line and writes it to `target/simulation-failures/`.
Saved under `src/test/resources/regressions/`, it is replayed before the generated cases on every run; see the README
there.

A swap to a Cache or Breaker over a Multi, whose name is unknown before it initializes, is exercised like any other and
counted as `swap.wrapsMulti` (`crun.swap.wrapsMulti` in the concurrent spec).
