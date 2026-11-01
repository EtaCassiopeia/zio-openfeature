# Provider lifecycle model

`Lifecycle.tla` is a TLA+ model of how `FeatureFlagsLive` swaps, watches and shuts down providers, with the OpenFeature
Java SDK (1.23.0) as its environment. TLC, the TLA+ model checker, explores every order the actions in it can happen in,
for a small configuration, and checks a set of invariants in every state it reaches.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Lifecycle.cfg Lifecycle.tla
```

`-deadlock` turns off TLC's deadlock check: a state with no action enabled is not an error here. The CI configuration (`Lifecycle.cfg`) has the initial
provider and three others, three swaps of any of them (a provider may be installed again, `P0` included) and two
provider events, from either start (the sync build's ready provider, or the async build's still initializing). The
three others are interchangeable, so TLC checks one ordering of them (`SYMMETRY ProviderSymmetry`): about 37 million
distinct states, about three minutes on a laptop. Each registration of a provider counts its own `initialize()` outcome and
shutdowns, so `ShutDownAtMostOnce` and `ClosedCleanly` are per registration. Each start is checked for an instance
that owns its API and for one that shares it (`owns`): only an owner records orphans and tears the API down.

The SDK's `shutDownOld` skips the previous provider only while its old registration is still bound. A provider
installed again gets a new registration, so the SDK shuts it down anyway once the registration that replaced it
returns; in one slot the skip needs the same provider set again, which the model does not take (#711). `ShutDownOld`
therefore always shuts the previous provider down, and `refuseReplacedByPendingInit` is what keeps that off a provider
that serves again (`BoundNotShutDown`). Two limits of the code's guard the model does not show: it counts a registration
as returned once the SDK reports `READY`, while the SDK's shutdown of the replaced provider follows just after, under its
lock; and a rollback that times out is not modelled (its wait is folded into `SwapRollback`), so the guard's rollback
case is pinned only by `ProviderHotSwapSpec`.

## What it checks

| Invariant | Meaning |
|:--|:--|
| `FinalNotReady` | Once shutdown has completed, the status stays `NotReady` (a later shutdown call shows `ShuttingDown` while it runs). |
| `NotReadyDuringSwap` | While a swap is in flight the status is `NotReady`, whatever events arrive. |
| `GateDownWhenIdle` | The swap gate is down whenever no swap holds the lock. |
| `OneLockHolder` | A swap and a shutdown never run at once. |
| `NoConcurrentInit` | No provider has `initialize()` entered while an earlier call runs. |
| `ShutDownAtMostOnce` | No registration of a provider is shut down twice. |
| `BoundNotShutDown` | While the instance is live, the provider bound to its slot has not been shut down. |
| `StatusFollowsSdk` | Outside a swap or shutdown, once the installed provider's events are delivered, the status is the one the SDK's state for it implies (or `Fatal`, which no event leaves), and the slot binds the installed provider. |
| `ClosedCleanly` | When the scope of an instance that owns its API closes, nothing is pending and the last registration of every provider the instance registered was shut down exactly once. (One that shares its API leaves its providers to the API's owner.) |

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
| A swap reads an `ERROR` reported right after `initialize()` returned as a failed initialization (#623) | `BoundNotShutDown` |
| A rollback reads such an `ERROR` from the restored provider as a failed rollback (#623) | `ShutDownAtMostOnce` |
| The watcher of a timed-out swap's provider reads such an `ERROR` as a failed initialization (#623) | `ShutDownAtMostOnce` |
| A swap after shutdown is not refused (#631) | `FinalNotReady` |
| A failed swap that never bound its provider reads a still-initializing provider as `Ready` (#634) | `StatusFollowsSdk` |
| The async build does not record its provider as initializing (#635) | `NoConcurrentInit` |
| The scope close does not wait for a swap in flight (#636) | `ClosedCleanly` |
| A swap after the scope close is not refused (#665) | `ClosedCleanly` |
| A provider still initializing is installed again (#669) | `NoConcurrentInit` |
| A provider a timed-out swap still holds is installed again | `BoundNotShutDown` |
| A provider a registration still initializing replaced is installed again (an instance sharing its API records no orphans) | `BoundNotShutDown` |
| The swap gate never goes up | `NotReadyDuringSwap` |

## Actions and the code

Each action is named after the lifecycle probe point (`core/.../internal/LifecycleProbe.scala`) at the same step, where
there is one.

| Action | Code |
|:--|:--|
| `SwapStart` | `FeatureFlagsLive.setProvider`: lock taken, gate up, `SwapStarted`; on a shut-down instance `setProvider` fails before this point (`~shutDown`) |
| `SdkBind` | `ProviderRegistration.registerThenAwait`: the SDK binds the new provider and starts `initialize()` |
| `SdkBindFail` | the SDK's registration throwing before it binds (`EventProvider.attach` or `registerGlobalProvider` refusing the provider) |
| `SdkInitOk`, `SdkInitFail` | the SDK's `FeatureProviderStateManager.initialize` returning or throwing; after a success, the SDK's `PROVIDER_READY` (`ran`) and `ProviderRepository.shutDownOld` |
| `SwapDone` | `setProvider`: `SwapSucceeded(settled)`, gate down, `reconcileRegistration`; `ProviderRegistration.awaitOutcome` deciding by the SDK's `PROVIDER_READY` |
| `SwapTimeout` | `setProvider`, timeout branch: `SwapFailed`, `pendingInit`, `recordOrphan`, `reconcileLateReady` |
| `SwapFailNoRollback` | `setProvider`, failure branch when `initPending(oldProvider)` |
| `SwapRollback` | `setProvider`, failure branch: `restoreSdkRegistration` (probe point `RollbackBind`), `RollbackSucceeded(settled)` or `cleanupUnboundFailedProvider` |
| `SwapRollbackUnbound` | `setProvider`, failure branch: `failedBeforeBinding`, `RollbackUnbound(before)`, then `reconcileRegistration` once the gate drops |
| `Emit`, `EmitStaleWhileStarting` | a provider's `emit`; the SDK's `FeatureProviderStateManager.onEmit` |
| `Deliver` | `EventBridge.onSdkEvent` and `applySdkSignal` |
| `OrphanResolved` | `OrphanedProviders.settle`, with the entry's `ReadyWatch` |
| `ShutdownStart`, `ShutdownDone` | `FeatureFlagsLive.shutdown` |
| `ScopeClose` | the API scope finalizer in `FeatureFlagsFactories`: takes the swap lock, marks the instance closed, then `boundedApiShutdown`, `orphans.resolveAll` |

## Preconditions

An action's precondition is either **enforced**, a guard the code has, or **assumed**, something the environment is
taken never to do; a **bound** only keeps the state space finite. TLC proves the invariants under both, so an assumed precondition on an action a user can trigger
would hide a bug: each one below is enforced, or names the issue or the documented limit behind it. The SDK's and the provider's
own actions (`SdkBind`, `SdkInitOk`, `Emit`, `Deliver`, ...) model what those do and are not listed.

| Action | Precondition | Kind | Code, and what checks it |
|:---|:---|:---|:---|
| `SwapStart` | `lock = "free"` | enforced | `setProvider` runs under `swapLock`; fixture `reject/swap-inside-a-swap` |
| `SwapStart` | `~shutDown` | enforced | `refuseSwap` (`shutdownCompleted`); fixture `reject/swap-after-shutdown`, `FeatureFlagsShutdownSpec`, mutant "a swap after shutdown is not refused" |
| `SwapStart` | `~closed` | enforced | `refuseSwap` (`closed`, set by the scope finalizer of every build, owning its API or not); `LifecycleTeardownSpec`, mutant "a swap after the scope close is not refused" (a run recorded after `ScopeClose` is not validated) |
| `SwapStart` | `~Settling` | assumed | a provider that reports `STALE` from inside `initialize()` is taken at its word, a documented limit (see below) |
| `SwapStart` | `inits[p] = 0` | enforced | `refuseStillInitializing`: a provider whose earlier `initialize()` has not returned is refused, however many swaps happened since, and installed again once that call returned; `ProviderHotSwapSpec`, three tests "... is refused while its initialize() runs ...", mutant "a provider still initializing is installed again" (recorded runs carry no provider identity, so no trace fixture can show this one) |
| `SwapStart` | `~\E q \in All : prevOf[q] = p /\ inits[q] > 0` | enforced | `refuseReplacedByPendingInit`: a provider that a registration still initializing replaced is refused until that `initialize()` returns, since its return makes the SDK shut the replaced provider down; the only such guard of an instance that shares its API; `ProviderHotSwapSpec` "an instance that shares its API refuses the provider a timed-out swap replaced until that swap's provider returns", mutant "a provider a registration still initializing replaced is installed again", and "after a rollback that timed out, the failed provider is refused until the restored provider returns" for the rollback case, which the model does not take (no trace fixture: recorded runs carry no provider identity) |
| `SwapStart` | `~\E o \in orphans : o.prev = p` | enforced | `refusePendingOrphan` (an instance that owns its API): the provider a timed-out swap replaced is refused until that swap's provider settles, since settling may shut it down; `ProviderHotSwapSpec` "the provider a timed-out swap replaced is refused until the swap's provider settles", mutant "a provider a timed-out swap still holds is installed again" (no trace fixture, for the same reason) |
| `SwapStart` | `p # bound` | assumed | setting the provider the slot already binds is not modelled (#711): the SDK reuses that provider's registration, and the code runs the swap, rolling back onto the same provider when it is in `ERROR` |
| `SwapStart` | `swaps < MaxSwaps` | bound | the swap budget that keeps the state space finite; not a property of the code |
| `ShutdownStart` | `lock = "free"` | enforced | `shutdown` runs under `swapLock`; fixtures `reject/shutdown-inside-a-swap` and `reject/recorded-shutdown-without-the-lock` |
| `ShutdownDone` | `lock = "shutdown"` | enforced | structural: the second half of `shutdown`, in the same permit as `ShutdownStart`, so no run shows one without the other |
| `ShutdownDone`, `ScopeClose` | `~Settling` (in `TearDown`) | assumed | the teardown waits for a provider that reported `STALE` from inside `initialize()` to finish starting; the code tears down regardless. The same documented limit as `SwapStart`'s |
| `ScopeClose` | `lock = "free"` | enforced | `scopeFinalizer` takes `swapLock`; fixture `reject/swap-without-outcome`, `LifecycleTeardownSpec`, mutant "the scope close does not wait for a swap in flight" |
| `ScopeClose` | `~closed` | assumed | a scope closes once; not an action a user can repeat |
| `Init` | `InitWith` or `InitAsync` | both starts | the sync build hands over a `READY` provider, the async build one still initializing (recorded as pending); an async provider whose `initialize()` threw is `InitAsync` followed by `SdkInitFail` |

`preconditions.py` checks that each fixture in the table is the run its precondition rules out: it deletes the
precondition and validates the fixture again, which must then be accepted. The `traces` job runs it with the
checker's self-test.

`LifecycleInterleavingSpec` pairs every primary with every competitor except two kinds the docs give no outcome for:
interrupting a provider-event primary (the interrupt has no lifecycle step to land in), and releasing hangs beside a
primary whose provider does not hang.

## Trace validation

The model is only worth what it says about the code, so runs of the code are checked against it. With `ZOF_TRACE=1`,
`LifecycleModelSpec` and `LifecycleInterleavingSpec` record every lifecycle probe point each run passes, one ndjson file
per run (`TraceProbe` in testkit's tests; `ZOF_TRACE_DIR` sets the directory, `target/traces` by default).
`trace_labels.py` turns a run into the library actions above, with what the run recorded about each: a delivered
event's type, whether a swap or its rollback ended `STALE`, whether the rollback succeeded. `TraceValidation.tla` then
lets TLC search for a behaviour of `Lifecycle` that takes exactly those library steps in that order, with the SDK's
steps (an `initialize()` returning or throwing, a provider emitting) free. The registered wrapper's own points
(`InitEntered`, `InitReturned`, `ProviderShutDown`) mark those SDK steps and are not labelled. A run is accepted when such a behaviour
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
| `ApplySignal(SwapSucceeded(settled))` | `SwapDone`, pinned to the settled state |
| `OrphanRecorded` in a swap with no other outcome | `SwapFailNoRollback` |
| `DeliverEvent` outside a swap, before any shutdown | `Deliver` |
| `OrphanResolved` outside a shutdown | `OrphanResolved` |
| `ShutdownStart`, `ShutdownDone`, `ScopeClose` | the action of the same name |

The other points belong to one of those steps. A run that reaches `Watchdog`, which the model leaves out, is skipped.

## What is outside the model

- **A shared instance's scope close.** `ScopeClose` models the owning topology, which tears the API down. An
  instance that shares its API also sets `closed` and drops its bridge when its scope closes, under the swap lock, but
  leaves the API and its providers to the API's owner (`LifecycleTeardownSpec`).
- **A provider that reports a state from inside its own `initialize()`.** The library reads whether `initialize()`
  returned from the SDK's own `PROVIDER_READY`, which a provider can also send, so such a report is taken at its word
  (see `docs/providers.md`, "Replace the provider at runtime"). The model allows only a `STALE` reported that way
  (`EmitStaleWhileStarting`), and holds such a provider to finishing its start: a provider that reports `READY` or
  `STALE` while starting and then fails, or is interrupted, leaves the provider it replaced unshut, and a rollback onto
  it would call `initialize()` again.
- **The watcher's two-tick wait.** The orphan watcher acts on an `ERROR` only when it persists across two ticks, which
  gives the SDK's `PROVIDER_READY` time to arrive; the model reads whether `initialize()` returned directly.
- **Time.** A timeout is an action enabled while `initialize()` runs; how long anything takes is not modelled.
- **The registry, shared APIs and `fromAcquireAsync`.** The model is one instance that owns its API.
- **The async build's init watchdog.** The async start is in (`InitAsync`); its watchdog is not, and
  `LifecycleInterleavingSpec` covers it.

## When the lifecycle code changes

The `Formal model` workflow runs TLC on every pull request that touches the files above or this directory, and the
mutants on `main` and on demand. A pull request that changes one of the lifecycle files without changing the model
states why in its description, with a line starting `formal: unaffected because`.
