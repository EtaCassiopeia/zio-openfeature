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
| `LateArrive(a)` | An abandoned attempt's constructor returns: it sees `Abandoned`, shuts the provider down, then closes the attempt's scope |
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

Nothing mechanical does. Unlike `formal/lifecycle`, this model has no trace validation or replay: TLC and the mutants
check the design, not the Scala. The workflow reruns the model when `FeatureFlagsFactories.scala` changes, but a code
change that breaks the model's assumptions still passes it. The link is `AcquireOwnershipSpec`, which pins the same
rules on the real code, so change the two together.

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
a `compose` that throws, and the real `Scope` semantics.

## When its code changes

`code-paths` lists the code this model describes. A pull request that changes one of those files without changing this
directory states why in its description, with a line `formal/acquire: unaffected because <reason>`; the `Formal model`
workflow's `model-or-reason` job checks it.
