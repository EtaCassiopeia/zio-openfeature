# Registry.tla actions and the code

Each action of `Registry.tla`, the code that takes it, and the `LifecycleProbe.Point` a run records for it. Every
instance on the API records into one sequence, each line tagged with its instance (`TraceProbe.forInstance`); a
provider's wrapper records with the probe of the instance that wrapped it first. Points are recorded right after the
step's effect, and a failure's decision right after it read its siblings. `trace_labels.py` turns the points into
labels; its docstring gives the exact rules. `FFF` is `internal/FeatureFlagsFactories.scala`.

| Action | Code | Probe point |
|:--|:--|:--|
| `Lookup(i, d)` | `FeatureFlagRegistryLive.memoizedClient`, the promise found | none: a stutter, on the memoized `getClient` path |
| `Check(i, d)` | `memoizedClient`, the promise inserted under the lock; for a layer on a shared API, the build itself | `RegistryInsert(domain)`, bookkeeping only: the labeler takes `Check` at the binding, which nothing on another domain can tell apart |
| `Create(i, p, own)` | `FFF.build` (`registerThenAwait`) and `FFF.buildAsync` (`ProviderRegistration.bind`); the registry's `buildClient` builds async | `BuildBind(provider, domain, owns, async)` |
| `Complete(i)` | the registry's `runBuild`, `promise.succeed`; an async build outside a registry returns once bound; a sync build once its wait returns | `RegistryComplete(domain)`; `BuildBind` with `async`; none for a sync build, which is completed before its first step that needs it open |
| `SwapStart(i, p)` | `FeatureFlagsLive.runSwap`, its registration returned; `FeatureFlagRegistry.setProvider` on a domain's client | `SwapStart` (the provider) and `SdkBind` (the step) |
| `SwapDone(i)` | `runSwap`, the success outcome; the SDK's `shutDownOld` of the replaced provider | `ApplySignal(SwapSucceeded)`; the shutdown counted into `End` |
| `SwapFailMark(i)` | `runSwap`'s failure branch, `failedRegistration` set | `FailedRegistration(provider)`, the first in the swap |
| `SwapFail(i)` | `rollBack`, then `decideFailedSwap`, or `cleanupUnboundFailedProvider` when the rollback failed too | the swap's `SwapDone` point; `FailedRegistration` and `FailDecided(provider, shut)` after `SwapRollback` give the library's decision, checked against the model's |
| `UserBind(d, p)` | the test's own `api.setProvider` | `UserBind`, written by the test (`RegistryRecording.environment`) |
| `FailMark(i)` | `shutDownFailedRegistration`, `failedRegistration` set: a failed build (`FFF.build`'s `failedBuild`), the init watchdog (`escalateInitTimeout`) | `FailedRegistration(provider)` |
| `FailDecide(i)` | the rest of it: `Bound.Foreign`, `ApiInstances.heldByAnother`, or the shutdown; a failed build's scope then closes | `FailDecided(provider, shut)`, checked against `LibraryShuts` |
| `CloseInstance(i)` | a non-owner's `shutdown` (`teardown(tearDownApi = false)`) or scope close (`scopeFinalizer`) | `ShutdownDone`, `ScopeClose` |
| `OwnerClose` | the owner's `shutdown` or scope close: `ApiInstances.retireSiblings`, then the API's shutdown | `RetireSiblings`, then the owner's `ShutdownStart` or `ScopeClose`; the others' teardowns in between are their retirement |
| (the outcome) | each provider's `shutdown()` calls | `ProviderShutDown`, counted per provider into `End` |

Two steps end what is checked, since the model has no action for them: the test's own `api.shutdown()`
(`UserApiShutdown`), and the registry's finalizer (`ApiShutdown`). The registry owns its API but is no instance, and
`OwnerClose` needs an owning instance; its clients' scopes close before it, each a `CloseInstance`.

`RegistryTrace.tla` adds three checks to the model's actions: a failure's and a rollback's decision is the model's, for
a registration that has not ended; the run's owner is the model's; and each provider's `shutdown()` ran as often as the
run counted, over all its registrations (`total`). A failure on a registration that has ended (its wrapper was shut down
already, and absorbs another shutdown) marks nothing and decides nothing (`absorbed`). `preconditions.py` deletes each
guard the check rests on and requires its reject fixture to be accepted then. The labeler rejects on its own a step from
an instance that has closed or after the API closed, an owner's close before the others' retirement ended, and a second
promise for a domain whose client is still open.

## Runs the model does not cover

`trace_labels.py` skips these, naming the reason; `validate.py` counts them per spec.

| Reason | What happened | Why the model leaves it out |
|:--|:--|:--|
| a timed-out swap | `SwapTimeout` | its orphans are the lifecycle model's |
| a registry retry after a failed build | a second `RegistryInsert` for a domain | a failed instance stays closed here, and its domain is not built again |
| a retry of a failed registration | the test reinitializes a slot's failed provider (`UserRetryInit`) | the same retry, by hand |
| two shared-API layers on one domain | a second `BuildBind` on a domain outside a registry | one instance per domain (`OneInstancePerDomain`) |
| a provider the user bound under a domain no instance is on | `UserBind` of a provider, then a build or swap of it elsewhere, while no instance's slot, nor the default one, holds it | the library cannot see that slot (`Create`, `SwapStart`) |
| the user bound a provider the library had bound as its wrapper | `UserBind` of a provider after a `BuildBind` or swap of it | the SDK holds two registrations of one provider then, which the model cannot tell apart |
| an owner's failed build | `FailedRegistration` while the owner builds | the model takes it as `OwnerClose`, on the scope close, which needs the owner open |
| a swap whose registration was superseded | a swap bound, neither succeeded nor marked its failure | a sibling swap took the slot: the lifecycle model's |
