# Provider lifecycle model

`Lifecycle.tla` is a TLA+ model of how `FeatureFlagsLive` swaps, watches and shuts down providers, with the OpenFeature
Java SDK (1.23.0) as its environment. TLC, the TLA+ model checker, explores every order the actions in it can happen in,
for a small configuration, and checks a set of invariants in every state it reaches.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Lifecycle.cfg Lifecycle.tla
```

`-deadlock` is needed because a closed scope is a final state. The CI configuration (`Lifecycle.cfg`) has the initial
provider, three swaps and two provider events: about 12 million distinct states, under two minutes on a laptop.

## What it checks

| Invariant | Meaning |
|:--|:--|
| `FinalNotReady` | Once shutdown has completed, the status stays `NotReady` (a later shutdown call shows `ShuttingDown` while it runs). |
| `NotReadyDuringSwap` | While a swap is in flight the status is `NotReady`, whatever events arrive. |
| `GateDownWhenIdle` | The swap gate is down whenever no swap holds the lock. |
| `OneLockHolder` | A swap and a shutdown never run at once. |
| `NoConcurrentInit` | No provider has `initialize()` entered while an earlier call runs. |
| `ShutDownAtMostOnce` | No provider is shut down twice. |
| `StatusFollowsSdk` | Outside a swap or shutdown, once the installed provider's events are delivered, the status is the one the SDK's state for it implies (or `Fatal`, which no event leaves), and the slot binds the installed provider. |
| `ClosedCleanly` | When the scope closes, nothing is pending and every provider the instance registered was shut down exactly once. |

`mutants.py` puts each fixed lifecycle bug back into the model and checks that one of these invariants catches it:

| Bug put back | Caught by |
|:--|:--|
| A failed swap rolls back onto a provider still initializing (#619) | `NoConcurrentInit` |
| Shutdown does not wait for a swap in flight (#565) | `NotReadyDuringSwap` |
| `STALE` moves the status only from `Ready` (#613) | `StatusFollowsSdk` |
| A swap into a `STALE` provider ends `Ready` (#617) | `StatusFollowsSdk` |
| A timed-out swap forgets the provider it replaced (#564) | `ClosedCleanly` |
| The status follows the event type, not the SDK's state (#601) | `StatusFollowsSdk` |
| A failed swap does not read the SDK's state once its gate drops (#619) | `StatusFollowsSdk` |
| The swap gate never goes up | `NotReadyDuringSwap` |

## Actions and the code

Each action is named after the lifecycle probe point (`core/.../internal/LifecycleProbe.scala`) at the same step, where
there is one.

| Action | Code |
|:--|:--|
| `SwapStart` | `FeatureFlagsLive.setProvider`: lock taken, gate up, `SwapStarted` |
| `SdkBind` | `ProviderRegistration.registerThenAwait`: the SDK binds the new provider and starts `initialize()` |
| `SdkInitOk`, `SdkInitFail` | the SDK's `FeatureProviderStateManager.initialize` returning or throwing; `ProviderRepository.shutDownOld` after a success |
| `SwapDone` | `setProvider`: `SwapSucceeded(stale)`, gate down, `reconcileRegistration` |
| `SwapTimeout` | `setProvider`, timeout branch: `SwapFailed`, `pendingInit`, `recordOrphan`, `reconcileLateReady` |
| `SwapFailNoRollback` | `setProvider`, failure branch when `initPending(oldProvider)` |
| `SwapRollback` | `setProvider`, failure branch: `restoreSdkRegistration`, `RollbackSucceeded` or `cleanupUnboundFailedProvider` |
| `Emit`, `EmitStaleWhileStarting` | a provider's `emit`; the SDK's `FeatureProviderStateManager.onEmit` |
| `Deliver` | `EventBridge.onSdkEvent` and `applySdkSignal` |
| `OrphanResolved` | `OrphanedProviders.settle` |
| `ShutdownStart`, `ShutdownDone` | `FeatureFlagsLive.shutdown` |
| `ScopeClose` | the API scope finalizer in `FeatureFlagsFactories`: `boundedApiShutdown`, `orphans.resolveAll` |

## Trace validation

The model is only worth what it says about the code, so runs of the code are checked against it. With `ZOF_TRACE=1`,
`LifecycleModelSpec` and `LifecycleInterleavingSpec` record every lifecycle probe point each run passes, one ndjson file
per run (`TraceProbe` in testkit's tests; `ZOF_TRACE_DIR` sets the directory, `target/traces` by default).
`trace_labels.py` turns a run into the library actions above, with what the run recorded about each: a delivered
event's type, whether a swap or its rollback ended `STALE`, whether the rollback succeeded. `TraceValidation.tla` then
lets TLC search for a behaviour of `Lifecycle` that takes exactly those library steps in that order, with the SDK's
steps (an `initialize()` returning or throwing, a provider emitting) free. A run is accepted when such a behaviour
exists. The check is of the order of the library's steps and what was recorded about them; the statuses a run observed
are not compared, which `LifecycleModelSpec`'s own oracle does. A trace TLC cannot decide in time is reported as
undecided without failing the run, and a run that checks no trace at all fails.

```sh
curl -sSLo tla2tools-1.8.0.jar https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
curl -sSLo CommunityModules-deps.jar https://github.com/tlaplus/CommunityModules/releases/download/202610262215/CommunityModules-deps.jar
python3 validate.py tla2tools-1.8.0.jar CommunityModules-deps.jar --selftest traces
python3 validate.py tla2tools-1.8.0.jar CommunityModules-deps.jar ../../testkit/target/traces
```

Reading the traces needs the community modules' `Json`, which needs TLC 1.8.0; the model check itself stays on 1.7.4.
`--selftest` checks the checker: the runs under `traces/accept` must be accepted and those under `traces/reject`
rejected, among them a run recorded with shutdown's lock removed from `FeatureFlagsLive`. The `Formal model` workflow
runs the self-test on pull requests, and on `main`, nightly and on demand records both suites and validates every
run.

| Probe point | Library action |
|:--|:--|
| `SwapStart`, `SdkBind`, `SwapTimeout`, `SwapRollback` | the action of the same name |
| `ApplySignal(SwapSucceeded(stale))` | `SwapDone` |
| `OrphanRecorded` in a swap with no other outcome | `SwapFailNoRollback` |
| `DeliverEvent` outside a swap, before any shutdown | `Deliver` |
| `OrphanResolved` outside a shutdown | `OrphanResolved` |
| `ShutdownStart`, `ShutdownDone`, `ScopeClose` | the action of the same name |

The other points belong to one of those steps. A run that reaches `Watchdog`, which the model leaves out, is skipped.

## What is outside the model

- **A provider that reports a state before the library has seen its `initialize()` outcome.** The SDK exposes a
  registration's state, not whether `initialize()` has returned, so the library takes such a report at its word (see
  `docs/providers.md`, "Replace the provider at runtime"). Three consequences follow. The model leaves each one out, and
  #623 tracks the fix:
  - an `ERROR` reported right after a successful `initialize()`, before the swap's wait sees it, makes the swap roll back
    onto a provider the SDK has already shut down;
  - the same `ERROR` before the watcher of the provider it replaced polls makes the watcher shut that provider down a
    second time;
  - a provider that reports `STALE` while starting and then fails, or is interrupted, leaves the provider it replaced
    unshut, and a rollback onto it would call `initialize()` again.
- **The rollback's own wait seeing a late `ERROR`** (#623). The rollback is one step here.
- **Time.** A timeout is an action enabled while `initialize()` runs; how long anything takes is not modelled.
- **The registry, shared APIs and `fromAcquireAsync`.** The model is one instance that owns its API.
- **The async build's init watchdog.** `LifecycleInterleavingSpec` covers it.

## When the lifecycle code changes

The `Formal model` workflow runs TLC on every pull request that touches the files above or this directory, and the
mutants on `main` and on demand. A pull request that changes one of the lifecycle files without changing the model
states why in its description, with a line starting `formal: unaffected because`.
