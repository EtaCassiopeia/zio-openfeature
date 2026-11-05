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

## Actions and the code

| Action | Code |
|:--|:--|
| `AttemptOk` | `attempt` succeeds: its mask sets `owned` to the provider and `candidate` to its scope |
| `VerifyFails` | `attempt` fails after `acquire` returned: `abandon` shuts the provider down, then the scope closes |
| `Compose` | The uninterruptible step that runs `compose` and moves `owned` to the stack |
| `InitOk`, `InitFails` | `boundedInitialize`. A failing chain has already shut itself down, and `LongAwareMultiProvider.shutdown` absorbs the second call |
| `SwitchOk`, `SwitchRefused` | `switchProvider`, uninterruptible with the clearing of `owned`. A refusal goes to `discard` |
| `InterruptBetween`, `InterruptInInit` | The fiber's interrupt. The design attaches nothing to it: the finalizer covers what is owned |
| `FixRelease1` to `FixRelease3` | The layer's finalizers, in reverse order of registration: the fiber's interrupt, the API's shutdown, then what is still owned and the candidate |

## Invariants

| Invariant | What it says |
|:--|:--|
| `NoLeak` | At the end of a release, a provider `acquire` returned has been shut down, unless it is still bound to a shared API |
| `NoStackLeak` | The same for the stack, and so for the fresh fallback in it |
| `ShutDownOnce` | No provider is shut down twice |
| `ShutDownBeforeResources` | A provider's `shutdown()` runs before its resources close |
| `FinalizersRun` | `acquire`'s finalizers run, whatever happened |

`mutants.py` checks that the code at 2.0.0 breaks the first four, with 2 shutdowns from the library alone and 3 with a
user finalizer, and puts each bug the design excludes into the model. Each is checked against the one invariant named
for it, so none passes vacuously.

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

## Out of reach

The model has one attempt, which is never abandoned, and one release. `AcquireOwnershipSpec` alone covers: retries, a
value that arrives after its attempt was abandoned, the scope an abandoned attempt keeps open until its constructor
returns, the interrupt that waits for a blocking call to return (`abandonable`), a `compose` that throws, and the real
`Scope` semantics.
