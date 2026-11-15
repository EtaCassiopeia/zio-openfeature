# Behaviours the cache replay must keep catching

`target-*.ndjson` are the shortest behaviours of `../Generate.tla` that reach a case random walks reach only by luck,
written by `../targets.py` (TLC 1.8). `CachingReplaySpec` replays them with the generated set, and `../codemutants.py`
requires each planted bug to be caught by them on their own too. Rerun
`python3 ../targets.py tla2tools.jar CommunityModules-deps.jar .` after a change to the model or to `Generate.tla`.

A behaviour that failed a nightly run is copied here as the `<n>.ndjson` file `generate.py` wrote, named
`<yyyy-mm-dd>-<what>.ndjson`.
