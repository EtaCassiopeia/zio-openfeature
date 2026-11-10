# Provider lifecycle model

`Lifecycle.tla` is a TLA+ model of how `FeatureFlagsLive` swaps, watches and shuts down providers, with the OpenFeature
Java SDK (1.23.0) as its environment. TLC, the TLA+ model checker, explores every order the actions in it can happen in,
for a small configuration, and checks a set of invariants in every state it reaches.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Lifecycle.cfg Lifecycle.tla
```

`-deadlock` turns off TLC's deadlock check: a state with no action enabled is not an error here. The full configuration (`Lifecycle.cfg`) has the initial
provider and three others, three swaps of any of them (a provider may be installed again, `P0` included) and two
provider events, from either start (the sync build's ready provider, or the async build's still initializing). The
three others are interchangeable, so TLC checks one ordering of them (`SYMMETRY ProviderSymmetry`): about 174 million
distinct states, about 40 minutes on a laptop and over an hour on a CI runner, so it runs on main and on demand. Pull
requests check `LifecyclePR.cfg`, the same with one provider event: about 34 million distinct states, about 15 minutes. Each registration of a provider counts its own `initialize()` outcome and
shutdowns, so `ShutDownAtMostOnce` and `ClosedCleanly` are per registration. Each start is checked for an instance
that owns its API and for one that shares it (`owns`): only an owner records orphans and tears the API down.

The SDK's `shutDownOld` skips the previous provider only while its old registration is still bound. A provider
installed again gets a new registration, so the SDK shuts it down anyway once the registration that replaced it
returns; in one slot the skip needs the same provider set again, which the model does not take (#711). So `SdkInitOk`
always queues the previous provider's shutdown, `SdkShutDownOld` runs it later as the SDK's executor does, and two
guards keep it off a provider that serves again (`BoundNotShutDown`): the step's `HeldBy` refusal while the registration
that replaced it initializes, and the bounded wait `Admit` asks for once it has returned. The model takes that wait as a
guard, `~oldPending[p]`, in one step with the refusal. That is sound because `Admit` reads both from one record and the
swap holds its lock from there on, so the swap starts after the wait with neither guard able to turn false in between,
except that a sibling on a shared API is not in the model. Time is not modelled, so the case where the bound elapses is
not in it. That case needs the SDK to skip the shutdown, or its executor to be wedged. A rollback that times out is not modelled either (its wait is folded
into `SwapRollback`), so its handover to the orphan watcher is pinned only by `ProviderHotSwapSpec`.

## What it checks

| Invariant | Meaning |
|:--|:--|
| `TypeInv` | Every variable has its type. |
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
| A provider whose queued shutdown has not run is installed again: the bounded wait `Admit` asks for removed (#728) | `BoundNotShutDown` |
| The swap gate never goes up | `NotReadyDuringSwap` |
| The swap body is interruptible: an interrupt of the caller skips the outcome and the rollback | `StatusFollowsSdk` |
| The re-read of the slot after the outcome is interruptible (#717) | `StatusFollowsSdk` |
| An interrupted swap does not drop its gate | `GateDownWhenIdle` |

The re-read that `SwapReconcile` makes would also correct a swap whose outcome reads the wrong status, as the code's
does. So the #617 mutant is caught by `OutcomeFollowsSettled`, which checks the outcome before that re-read.

## Proved for all sizes

TLC checks every invariant above, but only at `Lifecycle.cfg`'s bounds, and a bug that needs a fourth swap is beyond it.
`LifecycleProofs.tla` proves three of them with [TLAPS](https://proofs.tlapl.us/) for **any** number of providers, swaps,
events and evaluations, for all constants satisfying `ConstantsOK`:

```sh
tlapm --cleanfp LifecycleProofs.tla
```

| Invariant | Checked by |
|:--|:--|
| `TypeInv`, `OneLockHolder`, `GateDownWhenIdle` | **proved** for all sizes, and model-checked |
| every other invariant | model-checked at `Lifecycle.cfg`'s bounds |

`IndInv`, the conjunction of the three, is inductive: `NextInd` proves that every action preserves it. The `Formal
model` workflow's `proofs` job runs the prover on every pull request that changes the model, in a few minutes, and
fails on any unproved obligation. It runs with `--stretch 5`, since the largest steps outrun the default prover
timeouts on a CI runner; a local failure on `Deliver`, `OrphanResolved` or the teardown steps may need the same. The `SwapRollback` case of `NextType` is bounded by Z3's `rlimit` instead (`SMTT("r100")`): Zenon and Isabelle can't prove it, so a Z3 that missed its wall-clock limit on a slow runner failed the job with no code change. Its outcome no longer depends on load, so a model change that outgrows the budget fails on every run; then raise `N`. `N` counts differently per Z3 build: this step needs `r2` on a native Z3 5.3.3 and `r25` on the build CI pins, so check a new value against that build (it runs under `docker run --platform linux/amd64`). A change to `lock`, `gate` or `swap`, or a new action, must update the proofs.

`ConstantsOK` states what the proofs assume of the constants, and TLC checks it too. One clause is easy to miss:
`None \notin SwapRec`. The prover does not know `None` is a model value, so without it a new swap might equal `None`.
`TypeInv` includes `IsFiniteSet(orphans)`, since the teardown counts orphans.

The proofs have teeth, checked by hand on the mutated model:

| Bug put in | `tlapm` |
|:--|:--|
| An interrupted swap leaves its gate up | 2 obligations fail (`GateDownWhenIdle`) |
| The swap's re-read ends the swap but keeps the lock | 1 obligation fails (`OneLockHolder`) |
| Shutdown does not wait for a swap in flight; no swap gate | all proved: neither breaks these three, and TLC catches them through `NotReadyDuringSwap` |

On Apple Silicon, the arm64 TLAPS tarball bundles an x86_64 `z3` that cannot run without Rosetta. The SMT backend then
fails silently, and obligations it would have proved show as failed. Replace `lib/tlapm/backends/bin/z3` with a native
build.

## Evaluation

`Evaluator.gated` makes a lifecycle decision of its own. It reads `swapGeneration`, checks the status, calls the
provider, and then re-checks: the answer is returned only if the generation has not moved and no swap is in flight
(`swapInProgress`). Otherwise the evaluation fails with `ProviderNotReady(NotReady)`. The SDK resolves the slot's
provider per call, so without the re-check a provider the swap has already replaced or rejected could answer.

The model has `MaxEvals` evaluations running alongside the lifecycle, as `EvalRead`, `EvalCheck`, `EvalCall` and
`EvalReturn`. The generation is `Gen == swaps`, since only `SwapStart` moves either. `Evaluation.cfg` has one
evaluation on small bounds (two providers besides `P0`, two swaps, one event): 8.9 million distinct states, about 40
seconds, on every pull request. Every other configuration has `MaxEvals = 0`, so its state space is unchanged.

The issue that added this stated the contract as: *an answer returned to a caller was produced by a provider that was
installed, with no swap started or in flight, for the whole window between the generation read and the re-check.*
Two invariants carry it:

| Invariant | Meaning | The half it carries |
|:--|:--|:--|
| `NoRejectedAnswer` | A returned answer came from the provider installed when it returned. | No swap started in the window: one that started and ended inside it leaves a different provider installed. |
| `NoAnswerDuringSwap` | No answer is returned while a swap is in flight. | No swap in flight. |

The contract's "installed at the generation read" is dropped on purpose. An evaluation can read the generation while a
swap is in flight that is then refused before binding. `SwapRollbackUnbound` puts the status back to `Ready`, the
check passes, and the provider the rollback kept answers. That answer is correct, and the stricter wording would
refuse it.

`mutants.py` puts in three evaluation bugs:

| Evaluation bug | Caught by |
|:--|:--|
| The answer is returned without the re-check | `NoAnswerDuringSwap` or `NoRejectedAnswer`: it breaks both, and TLC's workers report whichever they reach first |
| The re-check reads only the generation: the provider of a swap past its outcome, with status `Error`, answers | `NoAnswerDuringSwap` |
| The re-check reads only the gate: a swap that starts and ends inside the window leaves the replaced provider's answer | `NoRejectedAnswer` |

**Where the generation moves does not matter.** Moving its increment to the SDK registration, or to the gate's drop, is
caught by nothing, and rightly so. The gate is up from `SwapStart` until after the swap's re-read, which covers every
moment a new provider can be bound before its outcome. The generation's job is a swap that starts and ends inside one
evaluation's window, and for that it only has to move while the gate is up. `ProviderHotSwapSpec` "an evaluation whose
provider call spans a whole swap is not served by the provider the swap replaced" pins that job in the code.

**`SwapStart` stays one step.** The code's swap start writes the gate, then the status, then the generation, under the
swap lock and before the registration. An evaluation that reads the new generation finds the gate already up. One
that reads the old generation with the status already `NotReady` is refused at its check. So taking the three writes
in one step loses no outcome.
Only that step writes the generation's mirror: it is written outside the step's critical section, so a write from every
step could land late and move it back, a reordering this one step cannot show.

Trace validation records no evaluations (`MaxEvals = 0`): the recorded runs check the lifecycle only.

## Actions and the code

Each action is named after the lifecycle probe point (`core/.../internal/LifecycleProbe.scala`) at the same step, where
there is one.

| Action | Code |
|:--|:--|
| `SwapStart` | `FeatureFlagsLive.setProvider`: lock taken, `LifecycleInput.Admit` (the guards, a `Refuse` before any probe point), then `LifecycleInput.SwapStart` (gate up, `SwapStarted`, generation, `installed`) |
| `SdkBind` | `ProviderRegistration.registerThenAwait`: the SDK binds the new provider and starts `initialize()` |
| `SdkBindFail` | the SDK's registration throwing before it binds (`EventProvider.attach` or `registerGlobalProvider` refusing the provider), or `ProviderRegistration.register` refusing a swap because another registration's `initialize()` of the provider has started since the swap was admitted and the slot does not hold it yet (`StillInitializing`) |
| `SdkInitOk`, `SdkInitFail` | the SDK's `FeatureProviderStateManager.initialize` returning or throwing; after a success, the SDK's `PROVIDER_READY` (`ran`) and `ProviderRepository.shutDownOld`, which queues the previous provider's shutdown |
| `SdkShutDownOld` | that shutdown running on the SDK's executor (`ProviderRepository.shutdownProvider`), through `CallShutdown`: the wrapper reaches the provider's `shutdown()` once per cycle and records `ProviderShutDown`, then `ShutdownReturned(ok)` if it returns; a throw is logged by the SDK, a hang is the executor's |
| `SwapDone` | `setProvider`: `Replaced`, `SwapSucceeded(settled)`; `Registration.settle` deciding by the wrapper's `initialize()` outcome |
| `SwapTimeout` | `setProvider`, timeout branch: `SwapFailed`, `Replaced`, `OrphanRecorded` (`recordOrphan`) |
| `SwapFailNoRollback` | `setProvider`, failure branch when the previous provider's `initialize()` still runs: `SwapFailed`, `OrphanRecorded` |
| `SwapRollback` | `setProvider`, failure branch, `rollBack`: `restoreSdkRegistration` (probe point `RollbackBind`), then `Replaced` and `RollbackSucceeded(settled)`; or `cleanupUnboundFailedProvider` when `initialize()` threw, which is `CallShutdown` of the failed provider through `boundedProviderShutdown` (a throw is logged, a hang abandoned at the bound). On a timeout, which the model folds into this step, `Replaced` and `OrphanRecorded` |
| `SwapRollbackUnbound` | `setProvider`, failure branch: `failedBeforeBinding`, `RollbackUnbound(before)` |
| `SwapReconcile` | `setProvider`, after every outcome: `GateDown`, probe point `SwapReconcile` (and `LateReady` after a timeout), then `reconcileRegistration` and the replay of a deferred watchdog deadline; inside the swap's uninterruptible region, with the lock held |
| `CallerInterrupt` | an interrupt of `setProvider`'s caller, which takes effect only in the phases in `Interruptible`: none, since the swap body and the re-read run uninterruptibly (`runSwap`'s `.uninterruptible`) |
| `Die` | none: a provider that stops working without reporting it. Only with `AllowDeath` (`Witness.cfg`) |
| `EvalRead`, `EvalCheck`, `EvalCall`, `EvalReturn` | `Evaluator.gated`: the `swapGeneration` read, `checkProviderStatus`, the provider call through the SDK client, and the re-check (probe point `GateRechecked`) |
| `Emit`, `EmitStaleWhileStarting` | a provider's `emit`; the SDK's `FeatureProviderStateManager.onEmit` |
| `Deliver` | `EventBridge.onSdkEvent` and `applySdkSignal` |
| `OrphanResolved` | `OrphanedProviders.settle` on the registration's outcome, after its bounded wait for the SDK's shutdown when `initialize()` returned; otherwise `CallShutdown` of the previous provider through `boundedProviderShutdown`; then `OrphanSettled` |
| `ShutdownStart`, `ShutdownDone` | `FeatureFlagsLive.shutdown`; the teardown's `CallShutdowns` is `boundedApiShutdown` (the SDK shuts the bound provider down and runs the shutdowns it queued) and `orphans.resolveAll` (`boundedProviderShutdown` of each unreleased entry's provider) |
| `ScopeClose` | the API scope finalizer in `FeatureFlagsFactories`: takes the swap lock, marks the instance closed, then `boundedApiShutdown`, `orphans.resolveAll`, the same `CallShutdowns` as `ShutdownDone` |

## The library's variables and the code

The library's variables are held by `LifecycleState` (`core/.../internal/LifecycleState.scala`), mostly under the same
names; the rows below say where one is held elsewhere. Every library action is one or more steps of
`LifecycleStep.step`, which `LifecycleStepSpec` tests per action. The event hub is not modelled:
`Lifecycle.whenAdmitted`, which publishes a provider's event, shares the steps' semaphore but runs no step and writes no
record.

| Variable | `LifecycleState` field |
|:--|:--|
| `status` | `status` (stored in the instance's `statusRef`, which the testkit may share) |
| `installed` | `installed` |
| `lock` | `lock` (the swap lock serializes the sequences; the field records which holds it) |
| `gate` | `gate`, mirrored to `swapInProgress` for the evaluator |
| `swap` | `swap` (without `phase` and `settled`: the swap's for-comprehension is the phase) |
| `pending` | not a field: whether the previous provider's `initialize()` still runs (its wrapper's count), read in `setProvider`'s failure branch |
| `prevOf` with `inits`, and `oldPending` (SDK variables) | `replaced`, the registrations with the provider each replaced, until the SDK has nothing left to do to it; the `HeldBy` refusal and the bounded wait before `Admit` read it |
| `orphans` | `orphans`; `OrphanedProviders` keeps the watcher of each entry |
| `shutDown`, `closed` | `shutDown`, `closed` |
| `owns` | `ownsApi`, fixed at construction |
| `bridgeOn` | the bridge's registered handlers (`EventBridge`), not lifecycle state |
| `swaps` (as `Gen`) | `generation`, mirrored to `swapGeneration` for the evaluator once the step has published the status |
| not a model variable | `name`, what the instance knows of the installed provider's name, mirrored to `providerNameRef` inside the step for the event bridge and the hook pipeline |
| `evals` | not library state: each evaluation's progress through `Evaluator.gated` |

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
| `SwapStart` | `~oldPending[p]` | enforced, with a bound | `LifecycleEffect.AwaitSdkShutDown`, which `Admit` asks for and `FeatureFlagsLive.refuseSwap` runs (probe point `AwaitSdkShutDown`): a provider a registration replaced, once that registration's `initialize()` returned, waits for the shutdown the SDK queued on its executor, which would otherwise reach its new cycle. The wait gives up after `OrphanedProviders.SdkShutDownWait` and the swap proceeds, which the model does not take: the SDK skips the shutdown of a provider still in another slot of a shared API, and then nothing arrives. `ProviderHotSwapSpec`, the same two tests, "a provider a successful swap replaced waits ...", "a failed provider a successful rollback replaced waits ...", "the wait for the SDK's shutdown is bounded ...", mutant "a provider whose queued shutdown has not run is installed again" (no trace fixture, for the same reason) |
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
(`InitEntered`, `InitReturned`) mark those SDK steps and are not labelled; its `ProviderShutDown` and `ShutdownReturned(ok)` points
are counted on the teardown they fall in (how many providers the teardown shut down, how many of those calls threw,
how many returned at all), and `TearDown` may shut down no more providers, and no more of each behaviour, than the
run counted (the SDK's executor can run a queued shutdown inside the teardown that the model placed before it, when
the watcher's bound elapsed, so the run may count one more); an `OrphanRecorded` point marks the run's instance as
owning its API, the topology whose teardown the counts constrain; the shutdown
points outside a teardown (the SDK's queued shutdown, an orphan's settle, a rollback's cleanup) stay free. A run is accepted when such a behaviour
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
| `SwapStart(provider)` | `SwapStart`, naming the provider it installs |
| `SdkBind`, `SwapTimeout`, `SwapRollback`, `SwapReconcile` | the action of the same name |
| `ApplySignal(SwapSucceeded(settled))` | `SwapDone`, pinned to the settled state |
| `OrphanRecorded` in a swap with no other outcome | `SwapFailNoRollback` |
| `DeliverEvent` outside a swap, before any shutdown | `Deliver` |
| `OrphanResolved(previous, watch)` outside a shutdown, inside a swap too (a failed swap settles an earlier swap's entry before it ends) | `OrphanResolved`, naming the entry's provider and the one it watches |
| `ShutdownStart`, `ShutdownDone`, `ScopeClose` | the action of the same name |

Points carry the provider objects, not names, so that production, whose probe is a no-op, builds nothing: `TraceProbe`
derives the identity. Each line gains `provider` (the name) and `providerInstance` (the identity hash, which tells apart
providers that share a name) on `Begin`, `SwapStart`, `ProviderShutDown` and `OrphanResolved` (its previous provider),
`watch` and `watchInstance` on `OrphanResolved`, `caller` (the thread name, which tells the SDK's executor from the
library) on `ProviderShutDown`, and `provider` (the event's provider name) on `DeliverEvent`. `GateDown` marks the swap
having its outcome with the generation gate about to drop; it is not labelled. `trace_labels.py` names each instance
`q0` (the `Begin` line's), `q1`, ... by first appearance, and `TraceValidation.tla` makes a `SwapStart` or
`OrphanResolved` match only those providers. A run that names its providers is not symmetric in them, so `validate.py`
drops `SYMMETRY` for it; a run without identity fields is checked as before.

The other points belong to one of those steps, except `SwapSkipped`, a `setProvider` of the bound provider, which is no
action, and `AwaitSdkShutDown`, the wait `SwapStart`'s guard stands for. A run that reaches `Watchdog`, or records an
orphan after a rollback (a rollback that timed out), both of which the model leaves out, is skipped. The SDK's
`SdkShutDownOld` is free, like its other steps.

### Model-based replay

Trace validation checks the order of a run's library steps; it does not compare states, and it checks only the runs
the suites produce. The replay checks the other direction, with states. `Generate.tla` is `Lifecycle` with every
disjunct of `LifecycleNext` labelled by a ghost variable `act` (the action and its parameters), and `generate.py` runs
TLC in generation mode on it (`-generate`, TLC 1.8): random behaviours of 20 states, written as one ndjson file each,
with every variable of the model after every step. Shutdown and the scope close are held back to the last levels
unless nothing else is enabled, so the sample is not spent in the shut-down state; that only removes choices, so every
behaviour is a behaviour of `Lifecycle`. `generate.py` fails if an action of `LifecycleNext` has no label.

`LifecycleReplaySpec` (core's tests) folds each behaviour through `LifecycleStep.step`, with no fibers, SDK or timing.
Each library action becomes the inputs the code sends for it, by [replay-map.md](replay-map.md); the SDK's actions
send no input and only change the SDK variables the next input reads. After every step the record's library
variables (`status`, `installed`, `lock`, `gate`, `swap`, `replaced` as `prevOf` with `inits` and `oldPending`,
`orphans`, `shutDown`, `closed`) must equal the model's, a library action's inputs must produce no `Refuse`, no wait
and no `EscalateInitTimeout`, and at every state the step must refuse a swap or a switch, or ask for the wait on a
queued shutdown, exactly where the model's guards the code has say so. Not
compared: `bridgeOn` and `owns` (not fields of the record), `pending` (no field; the action label carries the branch),
and the SDK's variables. The spec also fails if any library action, or any of the cases it counts (a shared API, a
failed rollback, a second shutdown, a provider installed again, each refusal, and so on; the list is in
`replay-map.md`), never occurs in the set. It checks its own teeth: a step whose `SwapStart` leaves the gate down and
a bridge that follows the event's type instead of the SDK's state must each fail the replay, and a second suite that
needs no generated behaviours replays one hand-written behaviour under six planted bugs, each of which must fail it.

```sh
python3 generate.py tla2tools-1.8.0.jar CommunityModules-deps.jar /tmp/behaviours --num 2000 --depth 20 --seed 7
cd ../.. && sbt "-Dzof.lifecycle.behaviours=/tmp/behaviours" "core/testOnly zio.openfeature.LifecycleReplaySpec"
```

`zof.lifecycle.behaviours` (a system property, or the `ZOF_LIFECYCLE_BEHAVIOURS` environment variable) takes files or
directories, comma-separated; without it the suite is ignored, unless `ZOF_LIFECYCLE_BEHAVIOURS_REQUIRED` is set, when
it fails (the `traces` job sets it). The `traces` job runs both on every pull request and
push, 2000 behaviours from a fixed seed, and nightly 20000 from the run's id as the seed; a behaviour the nightly run
fails on is checked in under `mbt-regressions/`, which every run replays too. Writing the replay found one fold in the
model and one gap in the code: after a rollback whose registration failed, the model set the status to the state the
provider settled in while the code leaves the failed swap's `Error` until the reconcile reads it (the model now does
the same); and after a timed-out swap the code's reconcile picked up only a late `READY` or `STALE`, not a `FATAL` the
provider reported under the gate, which `StatusFollowsSdk` requires (the code now reads the slot as after every other
outcome).

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
- **Time.** A timeout is an action enabled while `initialize()` runs; how long anything takes is not modelled. `SdkInitOk`
  returns from `initialize()` and writes the slot in one step: for the library, "returned but not yet written" is the same
  observation as "still running", with the same timeout and the same recovery by the late `READY`.
- **Several instances on one API.** The model is one instance, owning its API or sharing it (`owns`). The registry
  and the shared-API topology, with their ownership rules, are `formal/registry` (`Registry.tla`), a coarser model
  composed with this one by assumption: this one checks each instance's swap protocol, that one who tears the API
  down, which provider is shut down by whom and how often, and one client per domain. `fromAcquireAsync` on a
  shared API is an instance like any other there; its switch is not an SDK registration.
- **The async build's init watchdog.** The async start is in (`InitAsync`); its watchdog is not, and
  `LifecycleInterleavingSpec` covers it.

## Failures and crashes

### Represented in the model

| Failure | How the model represents it |
|:--|:--|
| `initialize()` throws | `SdkInitFail(p)`: the SDK state becomes `ERROR` and an `ERROR` event is queued; the swap then takes `SwapRollback` or `SwapFailNoRollback` |
| `initialize()` hangs | `inits[p]` stays above 0, and `SwapTimeout` is enabled for as long as it runs, so the timeout fires at every possible point; `TearDown` interrupts every `initialize()` still running |
| `initialize()` hangs, then throws | `SwapTimeout`, then `SdkInitFail`; `OrphanResolved` reads `ERROR` from a provider whose `initialize()` never returned, and shuts the replaced provider down |
| The registration is refused before binding | `SdkBindFail`, then `SwapRollbackUnbound` |
| The rollback's provider fails too | `SwapRollback(ok, r, early)` with `ok = FALSE` and `r` in `{"ERROR", "FATAL"}` |
| A provider reports a failure at runtime | `Emit(p, "ERROR")` or `Emit(p, "FATAL")`; `Fatal` is sticky (`Apply`), and `StatusFollowsSdk` allows for it |
| Events arrive late, in any order, or never | `events` is a set, and nothing forces `Deliver` |
| Shutdown or scope close while work is in flight | Both wait for the swap lock; `TearDown` shuts down the bound provider, the providers whose shutdown the SDK had queued, and the predecessors of the orphans not released |
| A provider's `shutdown()` throws or hangs | `shutBehaviour[p]`, fixed when the provider is registered (`Behaviours`): every shutdown site goes through `CallShutdowns`, which counts the call and records `shutOutcome[p]` as `ok`, `threw` or `timedOut` (the bound elapsed; time is not modelled, so in the same step). Nothing reads the outcome: the library logs a throw and abandons a hang at its bound, as `boundedProviderShutdown` and `boundedApiShutdown` do, so every invariant holds for every mix, `NoRetryAfterFailure` says no failed call is repeated (today `ShutDownAtMostOnce` implies it; it names the policy for the failed case), and `ShutdownEnds` holds under `FairSpec`. A behaviour per registration multiplies the state space, so `Lifecycle.cfg`, `LifecyclePR.cfg` and `Evaluation.cfg` keep `Behaviours = {"ok"}`; `Shutdowns.cfg` (the liveness bounds, about 23 million distinct states, a few minutes) checks every invariant with the full set on every pull request, `Witness.cfg` uses it too, and `ShutdownsLiveness.cfg` (one swap, providers that return or hang) checks `ShutdownEnds` under `FairSpec` against a hung shutdown, which liveness checking cannot afford on `Liveness.cfg`'s bounds. A `Bound.Foreign` provider, registered with the API outside the instance, is outside this model, which is one instance owning its API; the library shuts one down only when a timed-out swap left it in no slot it can see (`OrphanedProviders`) |
| A provider dies silently: its evaluations fail or hang, and it emits nothing | `Die(p)`, only with `AllowDeath`: the ghost `dead[p]` is set and nothing else changes, and nothing in the library reads it. Represented, and undetectable by design: see "Silent death" below |
| The caller of `setProvider` is interrupted | `CallerInterrupt`, enabled only in the swap phases in `Interruptible`, which is empty: the swap body and the re-read after it run uninterruptibly ([design record](../../docs/design/lifecycle.md), #433, #434), and the mutants that make either interruptible are caught. An interrupt while waiting for the lock changes no state; `shutdown` and the scope close are uninterruptible once they hold it (#435) |

### Left out, and what checks it instead

| Failure | Why it is out | Checked by |
|:--|:--|:--|
| A defect in the library: a bridge handler throws, the SDK's executor dies | The library's own actions are assumed to complete | the property and interleaving suites |
| The process crashes | Nothing is durable, and a restart is a fresh `Init`. `ClosedCleanly` cannot hold when no finalizer runs. | not applicable |
| A provider reports a state from inside `initialize()` and then fails | See "What is outside the model" | `LifecycleInterleavingSpec` |

### Silent death

A provider can stop working without saying so. Its SDK state stays `READY`, and the instance's status follows the SDK,
so it reports `Ready` for a dead provider. The library learns a provider's health only from the SDK's state and events,
and the model's evaluations never feed back into the status. `Die(p)` states the gap in the model instead of leaving it to prose:

- `AllowDeath` gates it. It is `FALSE` in `Lifecycle.cfg`, `Liveness.cfg` and the trace-validation configurations, so
  their state spaces are unchanged.
- A dead provider emits nothing more (`Emit` and `EmitStaleWhileStarting` require `~dead[p]`), or `Die` would only be an
  early `ERROR`. A new registration of it, by a swap's `SdkBind` or a rollback, starts a new `initialize()` and clears
  `dead[p]`.
- `Witness.cfg` sets `AllowDeath = TRUE`. `witness.py` checks it twice: without `SilentDeathUndetected`, every invariant
  must hold while providers may die; with it, TLC must report `SilentDeathUndetected` violated, and nothing else. That
  invariant asserts the instance never reports `Ready`, idle and live, with a dead provider installed. CI runs it on
  every pull request, in about five seconds. If a change makes the state unreachable, because the library learns
  health from evaluations, the check fails and this section must change with it.

What handles a silent death in practice: the evaluation error handling, which returns the default with an error
reason; `CircuitBreakerProvider` in `extras`, which opens on repeated evaluation failures and reports `STALE`; and the
provider fault tests on the real OFREP and Optimizely providers.

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
