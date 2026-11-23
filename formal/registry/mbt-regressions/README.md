# Behaviours the registry replay must keep catching

`<topology>/target-*.ndjson` are the shortest behaviours of `../Generate.tla` that reach a leak shape random walks reach only by luck. `../targets.py` writes them (TLC 1.8) from the statements in `../Targets.tla`. The replay reads a behaviour's topology from its directory, `registry` or `shared`. `RegistryReplaySpec` replays them with the generated set. `../codemutants.py` re-plants each fix and requires it to be caught by these behaviours on their own, as well as by the generated set.

| target | topology | the fix it keeps |
|---|---|---|
| `retry-left-behind-released` | registry | a retry with another provider leaves the failed build's wrapper behind (`FeatureFlagRegistry`'s `buildClient`), and the registry's close releases it |
| `sibling-wrapper-not-orphaned` | shared | the owner's timed-out swap leaves a wrapper an open sibling's named slot holds to that sibling (`OrphanedProviders.heldByVisibleSlot`) |
| `left-behind-released-at-owner-close` | shared | the owner's close releases a wrapper a non-owner's timed-out swap left behind, whose late `initialize()` failed (`ApiInstances.releaseLeftBehind`) |
| `swap-off-refused-as-queued` | shared | a swap to a provider a sibling's swap in flight is moving off, held by no slot, is refused before anything changes (`ApiInstances.claimSwapOff`, `ShutdownQueued`): the SDK queued its shutdown at that swap's binding |

After a change to the model, `Generate.tla` or `Targets.tla`, rerun from `..`:

```
python3 targets.py tla2tools.jar CommunityModules-deps.jar mbt-regressions
```

## Turning a counterexample into a regression

A counterexample from checking `Registry.tla` itself has no `act` labels, so the replay cannot drive it, and its labels must not be guessed. Instead:
1. State the violated property, or "this step never happens" for the shape it reached, in `Targets.tla`.
2. Add it to `targets.py`'s `TARGETS` with the right topology.
3. Rerun `targets.py`.

TLC then returns a labelled behaviour of `GSpec`. When `GSpec` has no counterexample, `targets.py` names the target and fails: the shape is outside what `Generate.tla` lets the replay drive. Widening `Generate.tla` comes first.

A behaviour that failed a nightly run is copied here into its topology's directory, renamed from the `<n>.ndjson` that `generate.py` wrote to `<yyyy-mm-dd>-<what>.ndjson`.
