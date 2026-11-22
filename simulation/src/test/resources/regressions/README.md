# Simulation regression corpus

Each `.json` file is one plan the simulation replays before its generated cases, on every run:

- `sequential/` is replayed by `SequentialSimulationSpec` (a `SeqPlan`: a provider stack and a command list);
- `concurrent/` is replayed by `ConcurrentSimulationSpec` (a `Plan`: a stack, evaluation workers, swaps, mutations
  and the `scheduleSeed` its schedule probe runs under).
- `shared/` is replayed by `SharedApiSimulationSpec` (a `Plan`: two or three instances on one API, each with its stack
  or the earlier instance it shares one with, its swaps and workers, the closes, mutations and `scheduleSeed`);
- `registry/` is replayed by `RegistrySimulationSpec` (a `Plan`: one registry's domains, each with the stack its build
  starts from and maybe the replacement a retry builds, its swaps and workers, the mutations and `scheduleSeed`).

An entry holds no zio-test seed or state, so it replays the same plan after any zio-test upgrade. A directory with no
`.json` file fails its replay test, so a broken resource path cannot pass silently.

## From a failure to an entry

1. A failing run prints `corpus entry (<SpecName>): {...}` under the shrunk failure. CI also uploads the same plan as
   the `simulation-failures` artifact (`<SpecName>.json`); a local run writes it to
   `simulation/target/simulation-failures/`.
2. Save that one line as `<sequential|concurrent|shared|registry>/<yyyy-mm-dd>-<what>.json`, named for the behaviour it pins.
3. Fix the bug. The entry fails before the fix and passes after it.

A concurrent plan is replayed under its own `scheduleSeed`, which makes most failures recur, but not reproduced
exactly: the SDK's threads run outside ZIO's scheduler. Prefer a
sequential plan when one reproduces the failure.

## Format

JSON written by `PlanCodec`, derived from the plan case classes. A product is an object of its fields, a case of a
sealed trait is tagged by name (`{"Swap":{...}}`), a case object is its name (`"ThrowGeneral"`), a `Double` is a
string, and an `Option` is `null` or its value.

**A missing field reads as `null`**, so a new `Option` field keeps older entries readable. A new required field or a
renamed case breaks the entry loudly: the replay test names the file and the path of the field that failed to decode.
Update the entry then.
