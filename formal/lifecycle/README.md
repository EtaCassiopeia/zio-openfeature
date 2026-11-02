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
therefore always shuts the previous provider down, and the step's `HeldBy` refusal is what keeps that off a provider
that serves again (`BoundNotShutDown`). Two limits of the code's guard the model does not show: it counts a registration
as returned once the SDK reports `READY`, while the SDK's shutdown of the replaced provider follows just after, under its
lock; and a rollback that times out is not modelled (its wait is folded into `SwapRollback`), so the guard's rollback
case is pinned only by `ProviderHotSwapSpec`.

## What it checks

| Invariant | Meaning |
|:--|:--|
| `FinalNotReady` | Once shutdown has completed, the status stays `NotReady` (a later shutdown call shows `ShuttingDown` while it runs). |
| `NotReadyDuringSwap` | While a swap is in flight the status is `NotReady`, whatever events arrive, until the swap has its outcome. |
| `OutcomeFollowsSettled` | A successful swap's outcome is the status of the state the SDK reported for the new provider, until the swap reads the slot again. |
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
| A swap into a `STALE` provider ends `Ready` (#617) | `OutcomeFollowsSettled` |
| A timed-out swap forgets the provider it replaced (#564) | `ClosedCleanly` |
| The status follows the event type, not the SDK's state (#601) | `StatusFollowsSdk` |
| A swap does not read the SDK's state once its gate drops (#619) | `StatusFollowsSdk` |
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
| The swap body is interruptible: an interrupt of the caller skips the outcome and the rollback | `StatusFollowsSdk` |
| The re-read of the slot after the outcome is interruptible (#717) | `StatusFollowsSdk` |
| An interrupted swap does not drop its gate | `GateDownWhenIdle` |

The re-read that `SwapReconcile` makes would also correct a swap whose outcome reads the wrong status, as the code's
does. So the #617 mutant is caught by `OutcomeFollowsSettled`, which checks the outcome before that re-read.

## Actions and the code

Each action is named after the lifecycle probe point (`core/.../internal/LifecycleProbe.scala`) at the same step, where
there is one.

| Action | Code |
|:--|:--|
| `SwapStart` | `FeatureFlagsLive.setProvider`: lock taken, `LifecycleInput.Admit` (the guards, a `Refuse` before any probe point), then `LifecycleInput.SwapStart` (gate up, `SwapStarted`, generation, `installed`) |
| `SdkBind` | `ProviderRegistration.registerThenAwait`: the SDK binds the new provider and starts `initialize()` |
| `SdkBindFail` | the SDK's registration throwing before it binds (`EventProvider.attach` or `registerGlobalProvider` refusing the provider) |
| `SdkInitOk`, `SdkInitFail` | the SDK's `FeatureProviderStateManager.initialize` returning or throwing; after a success, the SDK's `PROVIDER_READY` (`ran`) and `ProviderRepository.shutDownOld` |
| `SwapDone` | `setProvider`: `SwapSucceeded(settled)`; `Registration.settle` deciding by the wrapper's `initialize()` outcome |
| `SwapTimeout` | `setProvider`, timeout branch: `SwapFailed`, `Replaced`, `OrphanRecorded` (`recordOrphan`) |
| `SwapFailNoRollback` | `setProvider`, failure branch when the previous provider's `initialize()` still runs: `SwapFailed`, `OrphanRecorded` |
| `SwapRollback` | `setProvider`, failure branch: `restoreSdkRegistration` (probe point `RollbackBind`), `RollbackSucceeded(settled)` or `cleanupUnboundFailedProvider` |
| `SwapRollbackUnbound` | `setProvider`, failure branch: `failedBeforeBinding`, `RollbackUnbound(before)` |
| `SwapReconcile` | `setProvider`, after every outcome: `GateDown`, probe point `SwapReconcile`, then `reconcileLateReady` after a timeout or `reconcileRegistration` otherwise; inside the swap's uninterruptible region, with the lock held |
| `CallerInterrupt` | an interrupt of `setProvider`'s caller, which takes effect only in the phases in `Interruptible`: none, since the swap body and the re-read run uninterruptibly (`runSwap`'s `.uninterruptible`) |
| `Emit`, `EmitStaleWhileStarting` | a provider's `emit`; the SDK's `FeatureProviderStateManager.onEmit` |
| `Deliver` | `EventBridge.onSdkEvent` and `applySdkSignal` |
| `OrphanResolved` | `OrphanedProviders.settle` on the registration's outcome, then `OrphanSettled` |
| `ShutdownStart`, `ShutdownDone` | `FeatureFlagsLive.shutdown` |
| `ScopeClose` | the API scope finalizer in `FeatureFlagsFactories`: takes the swap lock, marks the instance closed, then `boundedApiShutdown`, `orphans.resolveAll` |

## The library's variables and the code

The library's variables are held by `LifecycleState` (`core/.../internal/LifecycleState.scala`), mostly under the same
names; the rows below say where one is held elsewhere. Every library action is one or more steps of
`LifecycleStep.step`, which `LifecycleStepSpec` tests per action.

| Variable | `LifecycleState` field |
|:--|:--|
| `status` | `status` (stored in the instance's `statusRef`, which the testkit may share) |
| `installed` | `installed` |
| `lock` | `lock` (the swap lock serializes the sequences; the field records which holds it) |
| `gate` | `gate`, mirrored to `swapInProgress` for the evaluator |
| `swap` | `swap` (without `phase` and `settled`: the swap's for-comprehension is the phase) |
| `pending` | not a field: whether the previous provider's `initialize()` still runs (its wrapper's count), read in `setProvider`'s failure branch |
| `prevOf` with `inits` (SDK variables) | `replaced`, the registrations still initializing with the provider each replaced, which the `HeldBy` refusal reads |
| `orphans` | `orphans`; `OrphanedProviders` keeps the watcher of each entry |
| `shutDown`, `closed` | `shutDown`, `closed` |
| `owns` | `ownsApi`, fixed at construction |
| `bridgeOn` | the bridge's registered handlers (`EventBridge`), not lifecycle state |

## Preconditions

An action's precondition is either **enforced**, a guard the code has, or **assumed**, something the environment is
taken never to do; a **bound** only keeps the state space finite. TLC proves the invariants under both, so an assumed precondition on an action a user can trigger
would hide a bug: each one below is enforced, or names the issue or the documented limit behind it. The SDK's and the provider's
own actions (`SdkBind`, `SdkInitOk`, `Emit`, `Deliver`, ...) model what those do and are not listed.

| Action | Precondition | Kind | Code, and what checks it |
|:---|:---|:---|:---|
| `SwapStart` | `lock = "free"` | enforced | `setProvider` runs under `swapLock`; fixture `reject/swap-inside-a-swap` |
| `SwapStart` | `~shutDown` | enforced | `LifecycleStep.admit` (`shutDown`); fixture `reject/swap-after-shutdown`, `FeatureFlagsShutdownSpec`, mutant "a swap after shutdown is not refused" |
| `SwapStart` | `~closed` | enforced | `LifecycleStep.admit` (`closed`, set by the scope finalizer's `ScopeClose` of every build, owning its API or not); `LifecycleTeardownSpec`, mutant "a swap after the scope close is not refused" (a run recorded after `ScopeClose` is not validated) |
| `SwapStart` | `~Settling` | assumed | a provider that reports `STALE` from inside `initialize()` is taken at its word, a documented limit (see below) |
| `SwapStart` | `inits[p] = 0` | enforced | `LifecycleStep.admit`, `StillInitializing`: a provider whose earlier `initialize()` has not returned is refused, however many swaps happened since, and installed again once that call returned; `ProviderHotSwapSpec`, three tests "... is refused while its initialize() runs ...", mutant "a provider still initializing is installed again" (recorded runs carry no provider identity, so no trace fixture can show this one) |
| `SwapStart` | `~\E q \in All : prevOf[q] = p /\ inits[q] > 0` | enforced | `LifecycleStep.admit`, `HeldBy` (the record's `replaced`): a provider that a registration still initializing replaced is refused until that `initialize()` returns, since its return makes the SDK shut the replaced provider down; the only such guard of an instance that shares its API; `ProviderHotSwapSpec` "an instance that shares its API refuses the provider a timed-out swap replaced until that swap's provider returns", mutant "a provider a registration still initializing replaced is installed again", and "after a rollback that timed out, the failed provider is refused until the restored provider returns" for the rollback case, which the model does not take (no trace fixture: recorded runs carry no provider identity) |
| `SwapStart` | `~\E o \in orphans : o.prev = p` | enforced | `LifecycleStep.admit`, `PendingOrphan` (the record's `orphans`; an instance that owns its API): the provider a timed-out swap replaced is refused until that swap's provider settles, since settling may shut it down; `ProviderHotSwapSpec` "the provider a timed-out swap replaced is refused until the swap's provider settles", mutant "a provider a timed-out swap still holds is installed again" (no trace fixture, for the same reason) |
| `SwapStart` | `p # bound` | enforced | `setProvider` returns at once, without a swap, when the slot already binds the provider by the SDK's own matching rule (`Bound.alreadyBound`, probe point `SwapSkipped`), since the SDK would reuse that registration and change nothing; `ProviderHotSwapSpec` "installing the bound provider changes nothing". No trace fixture: recorded runs carry no provider identity. No mutant: dropping the guard makes the model run an `initialize()` the SDK does not run, which no invariant observes |
| `SwapStart` | `swaps < MaxSwaps` | bound | the swap budget that keeps the state space finite; not a property of the code |
| `CallerInterrupt` | `swap.phase \in Interruptible`, which is empty | enforced | `runSwap`: the swap body and the re-read of the slot after it are one `.uninterruptible` region, so an interrupt of the caller waits for the swap to finish; `SetProviderInterruptSpec` "interrupted during a swap that times out, a READY dropped while the gate was up is still applied", fixture `reject/swap-without-reconcile`, mutants "the swap body is interruptible" and "the reconcile is interruptible". An interrupt while the caller waits for the lock changes no state, so it has no action |
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
| `SwapStart`, `SdkBind`, `SwapTimeout`, `SwapRollback`, `SwapReconcile` | the action of the same name |
| `ApplySignal(SwapSucceeded(settled))` | `SwapDone`, pinned to the settled state |
| `OrphanRecorded` in a swap with no other outcome | `SwapFailNoRollback` |
| `DeliverEvent` outside a swap, before any shutdown | `Deliver` |
| `OrphanResolved` outside a shutdown | `OrphanResolved` |
| `ShutdownStart`, `ShutdownDone`, `ScopeClose` | the action of the same name |

The other points belong to one of those steps, except `SwapSkipped`, a `setProvider` of the bound provider, which is no
action. A run that reaches `Watchdog`, which the model leaves out, is skipped.

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

## Failures and crashes

### Represented in the model

| Failure | How the model represents it |
|:--|:--|
| `initialize()` throws | `SdkInitFail(p)`: the SDK state becomes `ERROR` and an `ERROR` event is queued; the swap then takes `SwapRollback` or `SwapFailNoRollback` |
| `initialize()` hangs | `inits[p]` stays above 0, and `SwapTimeout` is enabled for as long as it runs, so the timeout fires at every possible point; `TearDown` interrupts every `initialize()` still running |
| `initialize()` hangs, then throws | `SwapTimeout`, then `SdkInitFail`; `OrphanResolved` reads `ERROR` from a provider whose `initialize()` never returned, and shuts the replaced provider down |
| The SDK refuses the registration | `SdkBindFail`, then `SwapRollbackUnbound` |
| The rollback's provider fails too | `SwapRollback(ok, r, early)` with `ok = FALSE` and `r` in `{"ERROR", "FATAL"}` |
| A provider reports a failure at runtime | `Emit(p, "ERROR")` or `Emit(p, "FATAL")`; `Fatal` is sticky (`Apply`), and `StatusFollowsSdk` allows for it |
| Events arrive late, in any order, or never | `events` is a set, and nothing forces `Deliver` |
| Shutdown or scope close while work is in flight | Both wait for the swap lock; `TearDown` shuts down the bound provider and the predecessors of the orphans not released |
| The caller of `setProvider` is interrupted | `CallerInterrupt`, enabled only in the swap phases in `Interruptible`, which is empty: the swap body and the re-read after it run uninterruptibly ([design record](../../docs/design/lifecycle.md), #433, #434), and the mutants that make either interruptible are caught. An interrupt while waiting for the lock changes no state; `shutdown` and the scope close are uninterruptible once they hold it (#435) |

### Left out, and what checks it instead

| Failure | Why it is out | Checked by |
|:--|:--|:--|
| A provider dies silently: evaluations fail and no event is emitted | The model has no evaluation action and knows provider health only from the SDK's state and events. The library cannot detect this either. #720 makes it explicit in the model. | the evaluation error handling; `CircuitBreakerProvider` (extras); the provider fault tests on the real OFREP and Optimizely providers |
| A provider's `shutdown()` throws or hangs | `TearDown` counts one shutdown, atomically. Every SDK and provider call on a lifecycle path is bounded (#561). #719 models it. | `LifecycleModelSpec` (`SlowShutdown`); `ManagedProvider` shuts a provider down at most once per initialization by construction |
| A defect in the library: a bridge handler throws, the SDK's executor dies | The library's own actions are assumed to complete | the property and interleaving suites |
| The process crashes | Nothing is durable, and a restart is a fresh `Init`. `ClosedCleanly` cannot hold when no finalizer runs. | not applicable |
| A provider reports a state from inside `initialize()` and then fails | See "What is outside the model" | `LifecycleInterleavingSpec` |

### Recovery

The invariants show that nothing wrong happens. `FairSpec` adds what makes things happen, and `Liveness.cfg` checks
that the system recovers under it. The fairness assumptions, in plain words:

- **The library's own steps.** A swap in flight takes its next step. The timeout eventually fires while a new provider's
  `initialize()` runs, which is the code's bounded wait. A started shutdown completes, and a watch that has resolved is
  acted on.
- **The SDK.** It eventually delivers, or drops, every pending event.
- **Providers get no fairness.** An `initialize()` may hang forever, and a provider may never report a state. The one
  exception is the assumption the model already makes: a provider that reported `STALE` from inside `initialize()`
  finishes starting.
- **Users get none either.** Nothing forces a swap, a shutdown or the scope close to start.

| Property | Meaning |
|:--|:--|
| `SwapEnds` | Every swap reaches its outcome and lets go of the lock, even against a provider that never returns. |
| `ShutdownEnds` | A started shutdown completes. |
| `EventsDrain` | The bridge sees, or the SDK drops, every pending event. |
| `StatusCatchesUp` | Once the instance stays idle and live, its status ends up as the SDK's state for the installed provider implies, and stays so. Without it, `StatusFollowsSdk` held vacuously on a behaviour where an event was never delivered. |
| `OrphansSettleOrTearDown` | An orphan whose watch has resolved is settled, unless a teardown settles it first. One watching a provider that never settles keeps its entry until a teardown, which nothing forces. |

`Liveness.cfg` has two providers besides `P0`, two swaps and one provider event. It uses no `SYMMETRY`, since TLC's
liveness checking is unsound under symmetry. It takes about 1.5 minutes on 10 cores (834K distinct states), so the
`Formal model` workflow runs it on every pull request. Three swaps and two events had not finished after more than two
hours, so the bounds stay small. `mutants.py` puts in three progress bugs, and TLC must report
the named property violated:

| Progress bug | Caught by |
|:--|:--|
| A swap waits for a hanging `initialize()` without a timeout | `SwapEnds` |
| Shutdown waits for the orphans to settle | `ShutdownEnds` |
| A resolved orphan is never settled | `OrphansSettleOrTearDown` |

## When the lifecycle code changes

The `Formal model` workflow runs TLC on every pull request that touches the files above or this directory, and the
mutants on `main` and on demand. A pull request that changes one of the lifecycle files without changing the model
states why in its description, with a line starting `formal: unaffected because`.
