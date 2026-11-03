# Circuit breaker model

`CircuitBreaker.tla` is a PlusCal model of `CircuitBreaker`
(`extras/src/main/scala/zio/openfeature/extras/CircuitBreaker.scala`), the lock-free state machine behind
`CircuitBreakerProvider`. One `AtomicReference` holds the circuit, the failure count and the epoch. Every operation is a
read followed by a `compareAndSet` that may fail and decide again, and the model splits each one at that point, so that
other threads interleave between the two.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar pcal.trans -nocfg CircuitBreaker.tla   # after editing the algorithm
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config CircuitBreaker.cfg CircuitBreaker.tla
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Signals.cfg CircuitBreaker.tla
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Liveness.cfg CircuitBreaker.tla
python3 witness.py tla2tools.jar
python3 mutants.py tla2tools.jar
```

The PlusCal algorithm is in the comment in `CircuitBreaker.tla`, and its translation, between `BEGIN TRANSLATION` and
`END TRANSLATION`, is checked in. After editing the algorithm, run `pcal.trans` with TLC 1.7.4 and commit both: CI
re-translates and fails on any difference, and other versions translate differently.

TLC runs with `-deadlock`: the budgets end, and every caller then waits at `CArrive`. No step of the breaker itself
blocks.

## Actions and the code

Line numbers are of `CircuitBreaker.scala`.

| Model | Code |
|:--|:--|
| `st` | `stateRef`, `CircuitBreakerState(circuit, consecutiveFailures, epoch)` (69) |
| `Transition` | `transition` (155): every circuit transition bumps the epoch |
| `CAcquireRead` | `acquire` (124): a closed circuit admits on the read alone; `ProbeFrom` is `probeFrom` (142) |
| `CAcquireCas` | `acquire`'s `compareAndSet` (133). A lost one decides again (136) |
| `CEvaluate`, first branch | environment: a probe dies, and never records. Its permit is gone and its slot stays occupied |
| `CRecordRead` | `current(permit)` (163), then the decision of `recordSuccess` (179), `recordReachable` (217) or `recordFailure` (247), in `RecordNext` |
| `CRecordCas` | the `compareAndSet` of those three (189, 194, 197, 226, 230, 257, 260) |
| `SRead`, `SCas` with a `Trip` | `trip(reason, stale)` (279), in `TripNext` |
| `SRead`, `SCas` with a `Reset` | `resetIf(opener)` (303), in `ResetNext`. Openers `{External, Polled}` are `reset()` (298), an event READY; `{Polled}` is `resetIfOpenedBy(Polled)` (301), a polled READY |
| `SRead`, `SCas` with `ToHalfOpen` | `transitionToHalfOpen` (319), in `ToHalfOpenNext` |
| `CTick`, `resetElapsed` | `ResetTimeoutElapses`: `now - sinceNanos >= resetTimeout` (145) |
| `CTick`, `probeElapsed` | `ProbeTimeoutElapses`: `now - probeStartNanos >= probeTimeout` (151) |

Why the abstraction of time is safe:

- **CAS equality.** `sinceNanos` and `probeStartNanos` are not in `st`, but they only change together with the epoch, so
  equality over `st` decides each `compareAndSet` as equality over the real state does.
- **The deadlines.** A flag may flip at any moment, which covers every real schedule. An open-to-open step keeps
  `sinceNanos` (285, 287), so it keeps `resetElapsed`. Any other transition clears both flags.
- **The clock read.** The code reads the clock just after the state, and the model reads both at once. It makes no
  difference: if the state moved in between, the `compareAndSet` fails and the decision is thrown away.

The ghosts are `generation`, the transitions so far, `probesInFlight`, `staleOutcomesApplied` and `staleAllowed`.
`generation` counts independently of the epoch, so a bug in the epoch cannot hide from a property that rests on it.

## What it checks

| Property | Kind | Meaning |
|:--|:--|:--|
| `AtMostOneProbe` | invariant | At most one probe occupies the half-open slot, counting a dead one, and the slot is occupied exactly while the circuit says `probing`. **The takeover rule:** a grant on an occupied slot is legal only once the occupant's `probeTimeout` has passed, and it replaces the occupant. The occupant may still be running, but its permit is of an earlier epoch, so at most one running probe's outcome can count |
| `StaleOutcomeIgnored` | invariant | An outcome whose permit is from an earlier state changes nothing (`staleOutcomesApplied = 0`) |
| `StaleFlag` | invariant | An open circuit is stale exactly when a stale trip opened it and no worse trip has landed since. A stale trip never raises the flag on a circuit that is already open |
| `ReasonRespected` | action property | The code's rules. `Open(Failures)` never closes. `Open(External)` closes only through an event READY, never a polled one. A step to half-open from open is either a probe after `resetTimeout`, or `transitionToHalfOpen`, and the latter never from `Failures`. An open circuit's reason changes only from `Polled` to `External` |
| `SlotFreed` | under fairness | `Probing ~> ~Probing`: a dead probe cannot pin the breaker |

The three configurations share the model:

| Config | Callers, signals | Thresholds | Budgets (calls, deaths, signals) | Purpose |
|:--|:--|:--|:--|:--|
| `CircuitBreaker.cfg` | 2, 1 | failure 2, half-open 2 | 5, 1, 1 | Opening by count and recovering through two probes |
| `Signals.cfg` | 2, 2 | failure 1, half-open 2 | 2, 1, 2 | Two signals racing, for the rules that take two trips |
| `Liveness.cfg` | 2, 1 | failure 1, half-open 2 | 2, 2, 1 | `SlotFreed`: a probe that takes over a dead probe's slot can die in turn |

**`SlotFreed` and the budgets.** A slot only frees when a caller arrives, and the budgets end. So a caller may always arrive
at a slot held by no running call (`Wedged`), whatever the budget says. The budget cannot then be what pins the slot,
and the state space stays finite, because each such arrival follows a death. The property is therefore stated for
finitely many deaths: if every probe died forever, each takeover would leave `probing` set, and no finite model could
tell that apart from a pinned slot.

`witness.py` asks TLC for each race the properties rest on, stated as if it could not happen, and requires exactly that
statement violated. A race that became unreachable would leave its property holding vacuously.

| Race | Statement required violated |
|:--|:--|
| A CAS loses to another thread | `LostCasNeverHappens` |
| An outcome arrives after its permit has passed | `OutcomesAreNeverLate` |
| A probe dies holding the slot | `SlotNeverWedges` |
| A reset meets a circuit that failures opened | `ResetNeverMeetsFailures` |
| A caller takes over an occupied slot | `NoTakeover` |
| Probes close the circuit | `ProbesNeverClose` |
| A stale event takes over a stale poll (`Signals.cfg`) | `PollNeverHandsOverStale` |

`mutants.py` puts in each bug the design excludes:

| Bug | Caught by | Config |
|:--|:--|:--|
| A transition does not bump the epoch | `StaleOutcomeIgnored` | `CircuitBreaker.cfg` |
| An outcome is applied whatever its permit | `StaleOutcomeIgnored` | `CircuitBreaker.cfg` |
| An outcome overwrites without a CAS | `StaleOutcomeIgnored` | `CircuitBreaker.cfg` |
| `acquire` grants a second probe while probing | `AtMostOneProbe` | `CircuitBreaker.cfg` |
| `acquire` takes the slot without a CAS | `AtMostOneProbe` | `CircuitBreaker.cfg` |
| No `probeTimeout` takeover | `SlotFreed` | `Liveness.cfg` |
| `resetIf` resets a circuit that failures opened | `ReasonRespected` | `CircuitBreaker.cfg` |
| A polled READY closes an event trip | `ReasonRespected` | `Signals.cfg` |
| `transitionToHalfOpen` moves a circuit that failures opened | `ReasonRespected` | `CircuitBreaker.cfg` |
| A worse trip does not clear stale | `StaleFlag` | `Signals.cfg` |
| The event takeover keeps stale if either was stale | `StaleFlag` | `Signals.cfg` |

`recordReachable` not freeing the slot is deliberately not in that list. Under that bug each reachable probe leaves the
slot wedged, the next caller takes it over and the epoch grows forever, so the state space is infinite and TLC does not
terminate.

## What is outside the model

- **Real time.** The model only knows whether a deadline has passed. How long `resetTimeout` and `probeTimeout` are, and
  the clock itself, are not modelled. `SlotFreed` needs a finite `probeTimeout`. `CircuitBreakerProviderConfig` derives it
  as `evaluationTimeout` plus a margin, so `evaluationTimeout = Duration.Infinity` makes it infinite, and that mode behaves
  as the "no `probeTimeout` takeover" mutant does: a probe that never returns holds the slot. That is a documented limit of
  the config, not a gap in the model: see `evaluationTimeout` in `CircuitBreakerProviderConfig`'s ScalaDoc.
- **The provider wrapper.** `CircuitBreakerProvider`'s event plumbing, its evaluation timeout and its error
  classification are not modelled. Signals arrive in any order, at any time. An outcome is any of the three, or a death.
- **The permit-less API.** `recordSuccess()`, `recordReachable()` and `recordFailure()` without a permit apply to whatever
  the current state is, by contract. Only direct use of the engine reaches them, so the model records with permits.
- **The returned booleans.** `didClose` and `didOpen` drive the wrapper's emissions, and are not modelled.
- **Long overflow.** Epochs and failure counts are unbounded in the model's types, and only reach the budgets.
