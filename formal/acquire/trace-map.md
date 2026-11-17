# Acquire.tla actions and the code

Each action of `Acquire.tla` under `Fix`, the code that takes it in `FeatureFlagsFactories.scala` (`FFF`), and the
`LifecycleProbe.Point` a run records for it. Every point is recorded right after the step's effect: a shutdown has
returned, a scope has closed, a reference is set. `trace_labels.py` turns the points into labels; its docstring gives
the exact rules.

| Action | Code | Probe point |
|:--|:--|:--|
| (none: numbers the attempts) | `acquireAttempt`, its scope made | `AcquireAttemptStart(attempt)` |
| `AttemptOk` | `acquireAttempt`: `verify` passed, `owned` and `candidate` set | `AcquireAccepted(attempt, provider)` |
| `VerifyFails` | `verify` failed (`AcquireVerifyFailed`), and the attempt's fiber releases the provider: `shutDownOwned`, then the scope's close | `AcquireRejected(attempt, provider)` |
| `Timeout` | `Timeouts.failAfter` fires, `abandonable` gives up, and the attempt's `onError` marks it `Abandoned` | `AcquireAbandoned(attempt, interrupted = false)` |
| `AcquireFails` | `acquire` fails: its `onError` closes the attempt's scope, before the attempt's handler runs | `AcquireFailed(attempt)`, then `AcquireAbandoned` |
| `LateArrive(a)` | an abandoned attempt's constructor, or its `verify`, returns: `release`, then `ZIO.interrupt`; a `verify` the abandonment interrupted is released the same way. An attempt abandoned after `verify` passed is released by its handler | `AcquireLateArrival(attempt, provider)`, or `AcquireRejected` without `AcquireVerifyFailed`. Either may be recorded before the attempt's `AcquireAbandoned`, which is then taken first |
| `LateFail(a)` | an abandoned attempt's constructor fails, or is interrupted once it suspends: its scope closes | `AcquireFailed(attempt)` after `AcquireAbandoned` |
| `Compose` | `constructAndSwitch`: `compose`, then `owned := stack`, uninterruptible | `AcquireComposed(stack)` |
| `InitOk` | `boundedInitialize` and `verifyProviderState` of the stack | `AcquireStackInit(true)` |
| `InitFails` | they fail: `recordConstructionOutcome` runs `discardOwned` | `AcquireStackInit(false)`, then `AcquireDiscarded` |
| `SwitchOk` | `switchProvider` with `owned := None`, uninterruptible | `AcquireSwitched(true)` |
| `SwitchRefused` | the switch refuses: `discardOwned` | `AcquireSwitched(false)`, then `AcquireDiscarded` |
| `FixRelease1` (`InterruptBetween`, `InterruptInInit`) | the release interrupts the `forkScoped` fiber; a finalizer registered just before `forkScoped` runs once it has ended. An attempt still waiting for its constructor is abandoned by it | `AcquireReleaseInterrupted`, or `AcquireAbandoned(attempt, interrupted = true)` |
| `FixRelease2` | `build`'s scope finalizer, `scopeFinalizer`: the API's shutdown | `ScopeClose` (the lifecycle point) |
| `FixRelease3` | the finalizer registered before `build`: `shutDownOwned` of what is owned, then the candidate's close | `AcquireReleaseOwned(owned)` |
| (the outcome) | the test's counts once the layer was released | `End`, written by `AcquireRecording` |

`CurrentRelease1` to `CurrentRelease3`, and the other `Fix = FALSE` branches, are the code at 2.0.0: no code takes them.

## Runs the model does not cover

`trace_labels.py` skips these, naming the reason. A third, "a verify interrupted with its attempt in progress", is
defensive: only a `verify` that interrupts itself can record it, and no spec does.

| Reason | What happened | Why the model leaves it out |
|:--|:--|:--|
| an attempt after a rejected one | an attempt starts after `AcquireRejected` | `VerifyFails` ends construction |
| a compose that threw | `AcquireDiscarded` without `AcquireComposed` | the model's `Compose` cannot fail |
