# Registry.tla actions and the code

Each action of `Registry.tla`, the code that takes it, and the `LifecycleProbe.Point` a run records for it. Every
instance on the API records into one sequence, each line tagged with its instance (`TraceProbe.forInstance`); a
provider's wrapper records with the probe of the instance that wrapped it first. Points are recorded right after the
step's effect, except `FailedRegistration` and `FailDecided`: a failure's mark and its decision's read of the siblings
are recorded with their effect as one step (`LifecycleProbe.step`, which the recorder runs under one lock for the whole
run), so a mark and a sibling's read of it are recorded in the order they happened. `trace_labels.py` turns the points into
labels; its docstring gives the exact rules. `FFF` is `internal/FeatureFlagsFactories.scala`.

| Action | Code | Probe point |
|:--|:--|:--|
| `Lookup(i, d)` | `FeatureFlagRegistryLive.memoizedClient`, the promise found | none: a stutter, on the memoized `getClient` path |
| `Check(i, d)` | `memoizedClient`, the promise inserted under the lock; for a layer on a shared API, the build itself | `RegistryInsert(domain)`, bookkeeping only: the labeler takes `Check` at the binding, which nothing on another domain can tell apart |
| `Create(i, p, own)` | `FFF.build` (`registerThenAwait`) and `FFF.buildAsync` (`ProviderRegistration.bind`); the registry's `buildClient` builds async | `BuildBind(provider, domain, owns, async)` |
| `Complete(i)` | the registry's `runBuild`, `promise.succeed`; an async build outside a registry returns once bound; a sync build once its wait returns. After a retry with another provider, the SDK's `shutDownOld` of the provider it replaced, which `buildClient` also left for the registry's close (`ApiInstances.leaveBehind`) | `RegistryComplete(domain)`; `BuildBind` with `async`; none for a sync build, which is completed before its first step that needs it open. The replaced provider's `ProviderShutDown` (an SDK thread) is counted into `End` |
| `BuildFail(i)` | the registry's `buildClient`: `awaitReady` failed, `releaseReplacedFailure` ran, the failed client is stored in `lastFailed`, and its child scope closes | the client's `ScopeClose` while its build runs; or, without one, the domain's next `RegistryInsert` |
| `Evict(i)` | `runBuild`'s typed-failure branch, `lock.withPermit(remove *> promise.fail(err))` | none: taken at the domain's next `RegistryInsert`, the retry that found no promise |
| `ReleaseReplaced(i)` | `releaseReplacedFailure`: the domain's previous failed client, of another provider, runs `shutDownFailedBuild` on that provider (`shutDownFailedRegistration`) | `FailedRegistration` and `FailDecided(provider, shut)` naming the previous attempt's provider under the domain's tag; `shut` is checked against `LibraryShuts` |
| `SwapStart(i, p)` | `FeatureFlagsLive.runSwap`, its registration returned; `FeatureFlagRegistry.setProvider` on a domain's client | `SwapStart` (the provider) and `SdkBind` (the step) |
| `SwapDone(i)` | `runSwap`, the success outcome; the SDK's `shutDownOld` of the replaced provider | `ApplySignal(SwapSucceeded)`; the shutdown counted into `End` |
| `SwapOldShut(i)` | the SDK's `shutDownOld` of the swap's replaced provider, run at once by a swap whose SDK task found a sibling's `initialize()` of the new provider under way (`FeatureProviderStateManager.initialize`'s `isInitialized` check); a rollback then registers the old provider again | `ProviderShutDown` of the swap's old provider before its outcome, while a sibling holds the swap's provider; still counted into `End` |
| `SwapFailMark(i)` | `runSwap`'s failure branch, `failedRegistration` set | `FailedRegistration(provider)`, the first in the swap |
| `SwapFail(i)` | `rollBack`, then `decideFailedSwap`, or `cleanupUnboundFailedProvider` when the rollback failed too | the swap's `SwapDone` point; `FailedRegistration` and `FailDecided(provider, shut)` after `SwapRollback` give the library's decision, checked against the model's |
| `SwapTimeout(i)` | `runSwap`'s timeout branch; `recordOrphan`: `OrphanedProviders.record` for an instance that owns its API, `ApiInstances.leaveBehind` (`leftBehind`) for one that does not | `SwapTimeout`; `OrphanRecorded` after it gives `recorded`, checked against the model (only the owner records); the swap's `SwapDone` point ends it |
| `InitSettle(p, ok)` | the timed-out swap's provider's `initialize()` returns or throws; the SDK's `shutDownOld`, or `OrphanedProviders.settle` | `InitOutcome(provider, ok)` of that provider; `OrphanResolved` and `OrphanDropped` are part of it, and the shutdowns that follow are counted into `End` |
| `UserBind(d, p)` | the test's own `api.setProvider` | `UserBind`, written by the test (`RegistryRecording.environment`) |
| `FailMark(i)` | `shutDownFailedRegistration`, `failedRegistration` set: a failed build (`FFF.build`'s `failedBuild`), the init watchdog (`escalateInitTimeout`) | `FailedRegistration(provider)` |
| `FailDecide(i)` | the rest of it: `Bound.Foreign`, `ApiInstances.heldByAnother`, or the shutdown; a failed build's scope then closes | `FailDecided(provider, shut)`, checked against `LibraryShuts` |
| `CloseInstance(i)` | a non-owner's `shutdown` (`teardown(tearDownApi = false)`) or scope close (`scopeFinalizer`) | `ShutdownDone`, `ScopeClose` |
| `OwnerClose` | the owner's `shutdown` or scope close: `ApiInstances.retireSiblings`, then the API's shutdown, then `OrphanedProviders.resolveAll`, then `ApiInstances.releaseLeftBehind` | `RetireSiblings`, then the owner's `ShutdownStart` or `ScopeClose`; the others' teardowns in between are their retirement |
| `RegistryClose` | the registry's layer finalizer: `ApiInstances.retireAll`, then the API's shutdown, then `ApiInstances.releaseLeftBehind` | `ApiShutdown`, taken at the end of the run: every client still open is retired first (`UnretiredSibling`), and the shutdowns after it are counted |
| (the outcome) | each provider's `shutdown()` calls | `ProviderShutDown`, counted per provider into `End` |

One step ends what is checked, since the model has no action for it: the test's own `api.shutdown()`
(`UserApiShutdown`), which retires nothing. The registry's finalizer (`ApiShutdown`) is `RegistryClose`; a client's
scope that closes before it is a `CloseInstance`, and a failure decision still pending at the close skips the run.

`RegistryTrace.tla` adds four checks to the model's actions: a failure's and a rollback's decision is the model's, for
a registration that has not ended; the run's owner is the model's; a timed-out swap records an orphan exactly when the
model's owner swapped; and each provider's `shutdown()` ran as often as the
run counted, over all its registrations (`total`). A failure on a registration that has ended (its wrapper was shut down
already, and absorbs another shutdown) marks nothing and decides nothing (`absorbed`). `preconditions.py` deletes each
guard the check rests on and requires its reject fixture to be accepted then. The labeler rejects on its own a step from
an instance that has closed or after the API closed, an owner's close before the others' retirement ended, and a second
promise for a domain whose client completed. Each registry build attempt is its own model instance: a retry's
`RegistryInsert` evicts the previous attempt (`BuildFail`, `Evict`), and its `BuildBind` is a new `Check` and `Create`.

## Runs the model does not cover

`trace_labels.py` skips these, naming the reason; `validate.py` counts them per spec.

| Reason | What happened | Why the model leaves it out |
|:--|:--|:--|
| a timed-out swap to a provider the user registered | `SwapTimeout` of a swap to a provider the test bound itself | no wrapper reports its `InitOutcome`, the settle the model takes |
| a timed-out swap off a provider another timed-out swap left waiting | a second `SwapTimeout` off the provider a first one replaced, before that one's `InitOutcome` | a provider waits on one `initialize()` here (`SwapTimeout`: `replaced[q] = None`) |
| a registry retry after a failure under its build | a second `RegistryInsert` for a domain whose build a failure's decision (`FailDecide`) closed | that instance is closed, not evicted, here |
| a failure's decision interrupted by the registry's close | `ApiShutdown` between a `FailedRegistration` and its `FailDecided`, a release's included | `RegistryClose` waits for every decision here |
| a retry of a failed registration | the test reinitializes a slot's failed provider (`UserRetryInit`) | the same retry, by hand |
| two shared-API layers on one domain | a second `BuildBind` on a domain outside a registry | one instance per domain (`OneInstancePerDomain`) |
| a provider the user bound under a domain no instance is on | `UserBind` of a provider, then a build or swap of it elsewhere, while no instance's slot, nor the default one, holds it | the library cannot see that slot (`Create`, `SwapStart`) |
| the user bound a provider the library had bound as its wrapper | `UserBind` of a provider after a `BuildBind` or swap of it | the SDK holds two registrations of one provider then, which the model cannot tell apart |
| an owner's failed build | `FailedRegistration` while the owner builds | the model takes it as `OwnerClose`, on the scope close, which needs the owner open |
| a swap whose registration was superseded | a swap bound, neither succeeded nor marked its failure | a sibling swap took the slot: the lifecycle model's |
