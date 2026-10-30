# Provider lifecycle model

`Lifecycle.tla` is a TLA+ model of how `FeatureFlagsLive` swaps, watches and shuts down providers, with the OpenFeature
Java SDK (1.23.0) as its environment. TLC, the TLA+ model checker, explores every order the actions in it can happen in,
for a small configuration, and checks a set of invariants in every state it reaches.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Lifecycle.cfg Lifecycle.tla
```

`-deadlock` is needed because a closed scope is a final state. The CI configuration (`Lifecycle.cfg`) has the initial
provider, three swaps and two provider events: about 11 million distinct states, under a minute on a laptop.

## What it checks

| Invariant | Meaning |
|:--|:--|
| `FinalNotReady` | Once shutdown has completed, the status stays `NotReady`. |
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
