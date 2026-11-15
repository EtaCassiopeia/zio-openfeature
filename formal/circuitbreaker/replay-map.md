# Replay map: model action to code

`CircuitBreakerReplaySpec` replays the behaviours of `Generate.tla` through the real `CircuitBreaker`
(`extras/src/main/scala/zio/openfeature/extras/CircuitBreaker.scala`), on one thread, with a fake `Ticker`. Each model
step that takes effect becomes the call the code makes at that point: the deciding read, or the `compareAndSet` that
wins. A step that loses its `compareAndSet` sends nothing, because the code retries inside the call. The spec's `step`
is this table's twin.

The spec reaches the operations through a small test trait (`acquire`, `recordSuccess`, `recordReachable`,
`recordFailure`, `trip`, `reset`, `resetIfOpenedBy`, `transitionToHalfOpen`, `state`) backed by the real breaker, so a
planted bug can wrap it. The step's pc label is the `act` that `Generate.tla` records; the step's variant is read from
the pc the step leads to, and the outcome or signal it carried is in `act`, since the next state no longer holds it.

| Model action | Call the replay makes | Expected |
|:--|:--|:--|
| `CArrive` | none (budget only) | |
| `CAcquireRead`, Closed (leads to `CEvaluate`) | `acquire()` | `Some(Permit(st.epoch))`, the model's permit epoch |
| `CAcquireRead`, `ProbeFrom = None` (leads to `CArrive`) | `acquire()`, which ends in `probeFrom` returning `None` | `None` |
| `CAcquireRead`, snapshot taken (leads to `CAcquireCas`) | none: the decision lands at the compareAndSet | |
| `CAcquireCas` won | `acquire()` | `Some(Permit(the model's permit epoch))` |
| `CAcquireCas` lost | none | |
| `CEvaluate` | none: the outcome is kept, or a death drops the permit | |
| `CRecordRead`, stale permit or no-op outcome (leads to `CArrive`) | `recordSuccess`, `recordReachable` or `recordFailure`, with the caller's `Permit` | state unchanged |
| `CRecordRead`, go (leads to `CRecordCas`) | none | |
| `CRecordCas` won | the same record call | state = the model's `st` |
| `CRecordCas` lost | none | |
| `SArrive` | none | |
| `SRead`, no-op (leads to `SArrive`) | the signal's call, below | state unchanged |
| `SRead`, snapshot taken (leads to `SCas`) | none | |
| `SCas` won | the signal's call, below | state = the model's `st` |
| `SCas` lost | none | |
| `CTick` | advance the fake `Ticker` by 10ns, the `resetTimeout` and the `probeTimeout` | |

A signal's call, from the `Op` in `act`:

| Model op | Call |
|:--|:--|
| `Trip(reason, stale)` | `trip(reason, stale)` |
| `Reset`, openers `{External, Polled}` | `reset()` |
| `Reset`, openers `{Polled}` | `resetIfOpenedBy(OpenReason.Polled)` |
| `ToHalfOpen` | `transitionToHalfOpen()` |

The model's pieces and where they live in the code:

| Model | Code |
|:--|:--|
| `st` | `stateRef`: `CircuitBreakerState(circuit, consecutiveFailures, epoch)` |
| `Transition` | `transition`: every circuit transition bumps the epoch |
| `ProbeFrom` | `probeFrom` |
| `RecordNext` | the decision of `recordSuccess`, `recordReachable` and `recordFailure`, after `current(permit)` |
| `TripNext`, `ResetNext`, `ToHalfOpenNext` | `trip(reason, stale)`, `resetIf(opener)`, `transitionToHalfOpen` |
| `resetElapsed` | `ticker.nanos() - sinceNanos >= resetTimeout` in `probeFrom` |
| `probeElapsed` | `ticker.nanos() - probeStartNanos >= probeTimeout` in `probeFrom` |

## The clock

`resetTimeout = probeTimeout = 10ns` and `now` starts at 0. On `CTick`, `now` advances by 10ns, whichever flag the tick
sets. That is sound because `sinceNanos` matters only while the circuit is Open, and `probeStartNanos` only while
HalfOpen is probing, so the two never overlap, and an Open-to-Open trip keeps `sinceNanos`, as the model's `install`
keeps `resetElapsed`. The replay also checks the flags themselves: while the circuit is Open, the code's
`now - sinceNanos >= 10` must equal `resetElapsed`, and while it is probing, `now - probeStartNanos >= 10` must equal
`probeElapsed`.

## Compared after every step

- The circuit kind, the open reason, `stale`, `successes`, `probing`, `failures` (`consecutiveFailures`) and the
  `epoch`.
- The permit epoch each acquire returns.
- The two deadline flags, as above.

Not compared: the ghosts (`generation`, `probesInFlight`, `staleOutcomesApplied`, `staleAllowed`), the budgets, and the
returned `didClose` and `didOpen`.

## What the spec requires

It fails when a label above, a call of the table, or a named case is never reached: a lost `compareAndSet` in acquire,
record and signal; a stale-permit outcome; a takeover of a timed-out probe (`CAcquireCas` while `Probing` and
`probeElapsed`); a reset that meets `Open(Failures)`; an event trip taking over a polled one; a trip that clears
`stale`; probes that close the circuit.

It also plants four bugs on every run, each of which must turn the replay red: a transition that keeps the epoch,
`reset()` that resets any reason, a `probeFrom` that never takes over a timed-out probe, and `current(permit)` that
treats a stale permit as current. The `target-*` behaviours (`mbt-regressions/`, from `targets.py`) must catch each of
them on their own.
