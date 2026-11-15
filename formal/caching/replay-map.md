# Replay map: model action to code

`CachingReplaySpec` replays the behaviours of `Generate.tla` through the real `CachingProvider`
(`extras/src/main/scala/zio/openfeature/extras/CachingProvider.scala`) over zio-cache 0.2.8, under a schedule the test
controls. Not trace validation: `CStart` (`generation.get()`) and `IBump` are lock-free, so a probe could not be
recorded atomically with its step, and `CGet`, `CWrite` and `CAwait` happen inside zio-cache, where nothing can be
observed.

What the test controls or observes:

- **The delegate** (`ReplayDelegate`, a `GatedProvider` of `CachingInvalidationRaceSpec`) holds every call at two gates.
  Gate A sits before it reads its version `pv`: reaching it is a `CGet` miss, and releasing it is `CEval`. Gate B sits
  before it returns: releasing it is `CWrite`, with the model's result `r`. Each caller passes its number as the default
  value, which reaches the delegate through its own `CacheKey`'s thunk, so every call is told to its caller.
- **Callers**: one thread each. A caller starts at `CGet`.
- **Hit, join or miss**: zio-cache's `cacheStats` and the caller's thread. A miss reaches gate A. A join counts a hit
  (`trackHit()` comes before `promise.await`, `Cache.scala:204-207`) and parks without returning. A hit counts a hit and
  returns.
- **Invalidation**: the delegate's `emitProviderConfigurationChanged(...).await()`, which reaches `onDelegateEvent`.
- **State**: two `private[extras]` probes on `CachingProvider`, `currentGeneration` (`generation.get`) and `probe`
  (`cache.contains` for the key under an epoch, and `cacheStats`).

## Fused steps

The test cannot split a caller's `CStart` and `CGet`, an invalidation's `IBump` and `IClear`, an owner's `CWrite` and
`CTail`, or a joiner's wake-up after a write (`CAwait`, `CTail`, `CRetry`). `Generate.tla`'s `GNext` restricts `Next`:
while a process is in the middle of one of these (`Busy`), only the processes in the middle of one may step. Every
generated behaviour is still a behaviour of `Caching`. The steps of one cluster commute, since each removes the same
key, so the replay compares the state only when `Busy` is empty (`Settled`), and waits for each step of a cluster at its
own model step.

| Model action | Code | Replay |
|:--|:--|:--|
| `Change` | no library code: the delegate's version bump | bump `pv`; the loop's exit does nothing |
| `IQueued` | the event is queued | nothing: the event is held until `IBump` |
| `IBump` + `IClear` | `onDelegateEvent`: `generation.incrementAndGet()`, then `cache.invalidateAll` | emit `CONFIGURATION_CHANGED` and await it, at `IBump`; `IClear` does nothing |
| `CStart` + `CGet` | `generation.get()` in `CacheKey`; `cache.get(ck)` | start the caller at `CGet`; wait for gate A (miss), a hit counted and the thread parked (join), or a return (hit) |
| `CEval` | the thunk `ran.set(true); evaluate` | release gate A; wait for gate B |
| `CWrite` | zio-cache `map.put` + `promise.done` | release gate B with the model's `r` |
| `CAwait` | `promise.await` | nothing: the joiner wakes once gate B is released |
| `CTail` | `onError`, `tap`, `!ran.get()` | wait for the caller to return, unless it goes on to `CRetry` |
| `CRetry` | `run(cache.invalidate(ck))` | wait for the retry to reach gate A; a return instead is a mismatch |
| `CRetryEval` | `Exit.attempt(ck.evaluate())` | release gate A, wait for gate B, release it with the model's `r`, wait for the return |
| `Done` | | nothing: the end of a behaviour, repeated to its depth |

The delegate answers `r@pv`. `ok` is reason `STATIC`. `soft` is reason `DEFAULT` or error code `GENERAL`, chosen per
behaviour from its name. `fail` throws.

## Compared once settled

- Each caller's answer, once the model's has returned: `ok`, `soft` or thrown (= `fail`) with its `pv`, and reason
  `CACHED` iff the model's `~ran`. A caller the model has not returned must not have returned.
- The delegate calls of each caller: the model's `CEval` and `CRetryEval` steps.
- `epoch` against `generation`.
- For each epoch `e`, the model's `map[e].st = "absent"` against the cache holding no `CacheKey(gen = e)`, and the
  number of entries. `CacheKey` equality ignores the thunk.
- zio-cache's hit and miss counts against the model's `CGet` steps.

Every wait is bounded (5s; the delegate holds a gate for 30s on its own, so a held call never lets itself go): a gate
that is never reached fails the behaviour. At the end of a behaviour every step the model did not take stays untaken (a
caller held at a gate stays held, an event not handled is not emitted) until the final state has been compared.

## What the spec requires

It fails when a label above or a named case is never reached: a lookup in flight across an invalidation, ending in a
stranded write; a join on a failed lookup, then its retry; a hit on a kept `ok`; a `soft` result that gets removed;
`soft` as `DEFAULT` and as an error code; a behaviour that ends with a caller held. `targets.py` writes the targeted
behaviours in `mbt-regressions/`, among them the counterexamples of witness.py's `InFlightNeverStale`, `NoStrandedWrite`
and `NoJoinerRetries`.

`codemutants.py` puts four bugs into `CachingProvider.scala`, one at a time, and requires the replay to turn red with a
mismatch of the named field among both the generated and the targeted behaviours:

| Mutant | Field |
|:--|:--|
| `generation.get()` in the key becomes `0L` | `lookup`: a later caller hits the stranded old value |
| `.onError(_ => cache.invalidate(ck))` becomes `.onError(_ => ZIO.unit)` | `map`: the failure stays in the map, which the state comparison sees before the next caller fails without reaching gate A |
| the `!ran.get() && …` guard becomes `false` | `retry`: the joiner returns instead of retrying |
| `Exit.attempt(ck.evaluate())` becomes `run(cache.get(ck))` | `map`: the retry's pending entry stays in the map, which the state comparison sees before a third caller joins it |
