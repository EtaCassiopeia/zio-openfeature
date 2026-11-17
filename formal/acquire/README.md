# Acquire hand-off model

`Acquire.tla` is a TLA+ model of how `fromAcquireAsync` (`core/.../internal/FeatureFlagsFactories.scala`) hands a
provider from `acquire` to the switch, against a layer release that can arrive between any two steps. It checks the
ownership rule: the library shuts down every provider `acquire` returns exactly once, and `acquire`'s `Scope` closes
after that.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -config Acquire.cfg Acquire.tla
java -cp tla2tools.jar tlc2.TLC -config AcquireShared.cfg Acquire.tla
python3 mutants.py tla2tools.jar
```

Trace validation needs TLC 1.8 and the community modules (the workflow's `TRACE_TLA_TOOLS_RELEASE`), from this
directory:

```sh
python3 validate.py tla2tools.jar CommunityModules-deps.jar --selftest traces
python3 preconditions.py tla2tools.jar CommunityModules-deps.jar
ZOF_TRACE=1 ZOF_TRACE_DIR=/tmp/traces sbt "core/testOnly zio.openfeature.AcquireOwnershipSpec zio.openfeature.AcquireNoNotReadyWindowSpec"
python3 validate.py tla2tools.jar CommunityModules-deps.jar /tmp/traces/acquire --coverage
```

## Constants

| Constant | Meaning |
|:--|:--|
| `Fix` | `TRUE` is the ownership design. `FALSE` is the code at 2.0.0, kept so its traces stay checkable |
| `UserShuts` | `acquire`'s finalizer also calls `shutdown()`, the idiom the 2.0.0 scaladoc pointed at. The design's configs leave it off: the library can't deduplicate a call on the raw provider |
| `Owning` | The layer's release shuts the API down, so the serving stack goes with it. `AcquireShared.cfg` turns it off: the stack stays bound and the API's owner shuts it down |
| `MaxAttempts` | Attempts the retry schedule allows (`attempt.retry(constructionRetry)`); 2 in both configs, so an attempt can start while an abandoned one's constructor still runs |

## Actions and the code

| Action | Code |
|:--|:--|
| `AttemptOk` | `attempt` succeeds: its mask sets `owned` to the provider and `candidate` to its scope |
| `VerifyFails` | `attempt` fails after `acquire` returned: `abandon` shuts the provider down, then the scope closes |
| `Compose` | The uninterruptible step that runs `compose` and moves `owned` to the stack |
| `InitOk`, `InitFails` | `boundedInitialize`. A failing chain has already shut itself down, and `LongAwareMultiProvider.shutdown` absorbs the second call |
| `SwitchOk`, `SwitchRefused` | `switchProvider`, uninterruptible with the clearing of `owned`. A refusal goes to `discard` |
| `Timeout` | The attempt's `Timeouts.failAfter` fires while it waits for its constructor: `abandonable` gives up on it and its `onError` marks it `Abandoned`. The next attempt starts, or construction fails |
| `AcquireFails` | The constructor fails or is interrupted: its `onError` closes the attempt's scope, with no provider |
| `LateArrive(a)` | An abandoned attempt's constructor, or its `verify`, returns: it sees `Abandoned`, shuts the provider down, then closes the attempt's scope |
| `LateFail(a)` | An abandoned attempt's constructor fails, or is interrupted once it suspends (`interruptOnceSuspended`): its scope closes |
| `InterruptBetween`, `InterruptInInit` | The fiber's interrupt. The design attaches nothing to it: the finalizer covers what is owned. An attempt still waiting for its constructor is abandoned, as on a timeout |
| `FixRelease1` to `FixRelease3` | The layer's finalizers, in reverse order of registration: the fiber's interrupt, the API's shutdown, then what is still owned and the candidate, which is only ever an accepted attempt's scope |

## Invariants

| Invariant | What it says |
|:--|:--|
| `NoLeak` | At the end of a release, a provider `acquire` returned has been shut down, unless it is still bound to a shared API; an abandoned attempt's, once no constructor still runs |
| `NoStackLeak` | The same for the stack, and so for the fresh fallback in it |
| `ShutDownOnce` | No provider is shut down twice, per attempt |
| `ShutDownBeforeResources` | A provider's `shutdown()` runs before its resources close, per attempt |
| `FinalizersRun` | `acquire`'s finalizers run for every attempt whose constructor has returned |
| `NoCloseUnderConstructor` | No attempt's scope is closed while its constructor may still be using it: the rule `acquireAttempt`'s doc states |
| `AbandonedNeverOwned` | A provider an abandoned attempt returns is never owned or handed on, and so never served |

`mutants.py` checks that the code at 2.0.0 breaks the first four, with 2 shutdowns from the library alone and 3 with a
user finalizer, and puts each bug the design excludes into the model, including a late arrival taken for the attempt in
progress, a late arrival not shut down, and a timeout or a release that closes an abandoned attempt's scope. Each is
checked against the one invariant named for it, so none passes vacuously.

## What ties this to the code

Trace validation. With `ZOF_TRACE=1`, every test of `AcquireOwnershipSpec` and `AcquireNoNotReadyWindowSpec` that
builds a `fromAcquireAsync` layer records its run (`AcquireRecording`, through a `private[openfeature]` overload of
`FeatureFlagsFactories.fromAcquireAsync` that takes a `LifecycleProbe`): the `Acquire*` probe points, each recorded
right after the step's effect, between a `Begin` line carrying `Owning` and `UserShuts` and an `End` line carrying what
the test observed after the release, the shutdown counts, the stack's, and whether the resource `acquire` registered
closed before or after a shutdown. `trace_labels.py` turns a run into the model's actions (`trace-map.md` is the map),
and `validate.py` has TLC search `AcquireTrace.tla` for a behaviour of the model under `Fix` that takes exactly those
steps, in that order, and ends as `End` says. Every action under `Fix` is a step a point records, so nothing in the
search is free: a run whose steps, order or outcome the model forbids is rejected. The driver is `formal/tracecheck.py`,
shared with `formal/lifecycle`. The `Formal model` workflow's `acquire-traces` job records and checks the runs on
every pull request.

What makes a rejection mean something:

- `End` is required, and is what catches a change to who shuts what down: a run whose late provider is never shut down,
  or whose stack is not, takes the same control flow and differs only there.
- `preconditions.py` deletes each guard the check rests on, every action's precondition and every check
  `AcquireTrace.tla` adds, and requires its reject fixture under `traces/reject`, rejected as the model stands, to be
  accepted without it.
- `validate.py --coverage` matches the accepted runs' behaviours against the model's state graph (TLC's `-dump dot`, for
  each set of constants the runs were checked with) and fails when an action under `Fix` is taken by no accepted run.
  A transition no run takes is printed, not failed on: most cannot be reached by the specs, see below.
- The probe costs nothing in production: `LifecycleProbe.none` is a constant `ZIO.unit`, and `ManagedProvider` does not
  enter the runtime for it.

Full transition coverage is not required, and the specs do not reach it. The check fails only when an action under
`Fix` is taken by no accepted run; every transition no run takes is printed, with the counts per action. The specs
reach every action, and on the recorded runs (`Owning`, one attempt) about three in four transitions. Those they do not take are timing windows a test cannot aim at without a gate in the code: the release
landing between the stack's `initialize()` and the switch; an abandoned constructor returning, or failing, while the
release's finalizers run; and `AcquireFails` with no retry left. Only one spec runs two attempts, one runs on a shared
API and one with `UserShuts`, so their graphs are covered along a single path.

The runs the model does not cover are skipped with their reason (`trace-map.md`): an attempt after a rejected one,
and a `compose` that throws. A timeout or the release landing while `verify` runs, or after it passed, is the model's
`Timeout` or `FixRelease1`, then `LateArrive`: `AttemptOk` takes `acquire` and `verify` as one step, and the provider
is released once `verify` no longer uses it, as a late constructor's is.

| Invariant | Tests that pin it, in `AcquireOwnershipSpec` |
|:--|:--|
| `NoLeak` | The tests under "a release while the provider is on its way", and "verify rejects, bare" |
| `NoStackLeak` | "during compose: the stack, the real provider and the fresh fallback each once" |
| `ShutDownOnce` | "initialize fails under the default compose, bare", "racing the end of initialize", "two shutdowns reach each member once" |
| `ShutDownBeforeResources` | "an owning instance shuts the provider down before a resource acquire registered closes", "initialize fails: the provider shuts down before a resource acquire registered closes" |
| `FinalizersRun` | "a stack still bound to a shared API is left to that API's owner, and the resource closes" |
| `NoCloseUnderConstructor` | "a blocking constructor keeps its resource until it returns, then shuts down before the resource closes" |
| `AbandonedNeverOwned` | "the attempt times out and the constructor returns later: once", "verify does not run on a provider that arrived after its attempt was abandoned" |

## Out of reach

The model has one release, and verify is part of the step that accepts an attempt, so `Timeout` and `InterruptBetween`
at `pc = "attempt"` cover an attempt abandoned in `acquire` or in verify alike: in the code, too, the attempt's own
fiber releases the provider once the constructor or verify returns ("a timeout during a blocking verify keeps the
provider and its resource until verify returns"). An attempt after a rejected one is not modelled: the rejected provider
is shut down and its scope closed by its attempt's fiber, the state `LateArrive` leaves, before the retry starts unless
the attempt's timeout lands during that release. The next attempt starts as soon as one times out, where the code first
waits for the retry's delay: the model has more behaviours there than the code, never fewer.
`AcquireOwnershipSpec` alone covers how long the interrupt waits for a blocking call to return (`abandonable`'s bound),
the retry's timing, a `compose` that throws, and the real `Scope` semantics: the trace check sees a scope's close only
through the points and the test's own observations of a resource.

## When its code changes

`code-paths` lists the code this model describes. A pull request that changes one of those files without changing this
directory states why in its description, with a line `formal/acquire: unaffected because <reason>`; the `Formal model`
workflow's `model-or-reason` job checks it.
