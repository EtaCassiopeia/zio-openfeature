# Registry.tla actions and the replay

How `RegistryReplaySpec` (`core/src/test/scala/zio/openfeature/RegistryReplaySpec.scala`) drives each action of a
behaviour `Generate.tla` writes, the twin of its `step`. Each step waits for the point it drives to, bounded by
`WaitMillis` (5 s). A step that never gets there fails the behaviour; it never passes it. Every provider is a fresh
`Scripted`: each `initialize()` waits until the replay settles it (ok or failing), and each `shutdown()` is counted.
Topologies:

- `registry`: one `FeatureFlagRegistry.fromProvider(p1, …)` over its private API, with one `ReplayProbe` per domain.
- `shared`: one isolated `OpenFeatureAPI`, each instance built by `FeatureFlagsFactories.build` with its own probe and
  scope.

| Action | What the replay does | Waits for |
|:--|:--|:--|
| `Lookup(i, d)` | `getClient(d)` (`defaultClient` for the default) | the instance the domain's attempt produced, the same object; nothing when that build still runs, since the lookup would wait on it |
| `Check(i, d)` | registry: records the provider the next `Create` names (`registry.setProvider(d, p)`), except the default provider for a domain the map holds nothing for, which the registry falls back to; arms a hold at `RegistryInsert(d)` and forks `getClient(d)`; shared: nothing | the hold reached, with the semaphore held as the model's `lock` |
| `Create(i, p, own)` | registry: releases the hold; shared: forks the build with `addShutdownFinalizer = own` | `BuildBind`, or `p`'s `initialize()` starting (a retry initializes its failed provider before binding), or the build ending |
| `Complete(i)` | settles `p`'s `initialize()` ok, if one runs | the build returning its instance |
| `BuildFail(i)` | settles `p`'s `initialize()` failing, unless the `ReleaseReplaced` before it did | the failed client's `ScopeClose`, or the build ending |
| `Evict(i)` | nothing | `getClient` failing |
| `ReleaseReplaced(i)` | settles the retry's `initialize()` failing | `FailDecided` from the previous failed client's `shutDownFailedBuild` |
| `SwapStart(i, p)` | forks `registry.setProvider(d, p)` for a domain's client, `FeatureFlagsLive.setProvider(p)` otherwise | `SdkBind`, or `p`'s `initialize()` starting, or the swap ending |
| `SwapDone(i)` | settles `p`'s `initialize()` ok, if one runs | the swap succeeding |
| `SwapTimeout(i)` | fires the swap's deadline (`ScriptedInitDeadline.fire`) once its wait for `initialize()` is armed; the call keeps running | the swap failing with the deadline's `TimeoutException` |
| `InitSettle(p, ok)` | settles the timed-out swap's still-running `initialize()` of `p` with `ok` | nothing more: the comparison after it waits for the shutdowns that follow |
| `SwapOldShut(i)` | nothing: the SDK runs it in the binding | the replaced provider's `shutdown()` count reaching the model's |
| `SwapFailMark(i)` | arms a hold at `FailedRegistration`, settles `p`'s `initialize()` failing | the hold reached |
| `SwapFail(i)` | releases the hold, and lets the rollback's re-`initialize()` of the previous provider return (the model takes its success) | the swap failing |
| `FailMark(i)` | as `SwapFailMark`, under a shared build | the hold reached |
| `FailDecide(i)` | releases the hold | a building instance's build failing |
| `UserBind(d, p)` | `api.setProviderAndWait(d, p)`, settling `p`'s `initialize()` ok if one runs | the call returning |
| `CloseInstance(i)` | closes the instance's scope (shared), or `shutdown` (a registry client) | the close returning |
| `OwnerClose` | closes the owner's scope | the close returning |
| `RegistryClose` | closes the registry's scope | the close returning |
| `Init`, `Done` | nothing | nothing |

Any other action fails the behaviour. At a binding's outcome (`Complete`, `BuildFail`, `SwapDone`, `SwapFailMark`,
`FailMark`, `SwapTimeout`), the replay checks that the binding started exactly the `initialize()` calls the model says it did: one when
the provider's call is in the ghost `inFlight` after the binding and was not before, none otherwise.

## Compared at settled states

A state is settled when `swapping = {}`, `deciding = {}`, `lock = None` and `then = None`. Shutdowns land on SDK
threads, so a comparison is retried until it holds, and reports what still differs after `WaitMillis`. After the last
state, the comparison must hold once more 50 ms later, so a late shutdown shows.

| Variable | The code's side |
|:--|:--|
| `bound` | each slot's provider, `api.getProvider(d)`, unwrapped to the scripted provider behind the library's wrapper; `NoOpProvider` is `None`. The SDK answers an unbound domain with the default slot's provider and keeps no public record of which domains are bound, so a domain that resolves to the default's provider reads as unbound when the model has it so. That blind spot runs both ways: a bug that binds a domain to the default's own provider, or one that never binds a domain the model binds to it, goes unseen here, and shows only in `total` |
| `total` | each scripted provider's `shutdown()` count, against the ghost `total` (cumulative shutdowns over all registrations) |
| `inFlight`, with `pending` | the scripted providers with an `initialize()` running that the replay has not settled: the model's `inFlight` and, once a swap timed out, `pending` |
| `orphans` | the previous providers the instances' orphan lists will shut down unless the SDK does (`FeatureFlagsLive.replayOrphans`, entries not `heldWhenRecorded`) |
| `leftBehind` (API open) | what the close would release now (`ShutApi`'s `rel`): the model's entries still registered, held by no slot and waiting on no pending call, against `ApiInstances.leftBehindRunning` (the wrappers still in the cycle they were left in) minus those a slot holds. The model keeps every entry it made, and the code only those still in their cycle, so a provider shut down and bound again since counts in neither |
| `owner` (shared, API open) | the set of instances whose `replayView` says they own the API, once the owner's build has handed it out |
| `failedOn` (open instances) | `FeatureFlagsLive.replayView`'s failed registration |
| `phase` | for each instance the replay holds: closed (its lifecycle closed or shut down) exactly when the model has it `closed` |
| `phase`, reported as `promise` (registry, API open) | the domains holding a promise (the registry's `replayView`): those with an instance not `absent`, `checking` or `evicted` |

Skipped:

- `lock`, `swapping`, `prior` and `deciding` are transient, and settled states have them empty.
- `registered` and `userOwned` deliberately differ from the SDK's for wrappers the library absorbed.
- `replaced` shows in `total`.
- `everBound`, `swappedOff`, `abandoned` and `builtOn` are ghosts or known to the replay.
- `abandoned` shows in `total`: the user's providers nothing shuts down.
