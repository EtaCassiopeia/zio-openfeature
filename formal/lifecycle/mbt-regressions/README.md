# Behaviours the replay once failed on

The nightly run of the `Formal model` workflow generates 20000 behaviours of `Lifecycle.tla` from a seed it rotates,
and `LifecycleReplaySpec` replays them against `LifecycleStep.step`. A behaviour that fails the replay is copied here,
as the `<n>.ndjson` file `generate.py` wrote it, named after what it found (`<yyyy-mm-dd>-<what>.ndjson`), and is
replayed on every run alongside the freshly generated set, so the mismatch stays fixed. Every pull request's run
replays this directory too.

To reproduce a nightly failure: take the seed from the run's "Generate behaviours" step and run `generate.py` with it,
then the spec on the output (see `../README.md`, "Model-based replay").

None yet.
