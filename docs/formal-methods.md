---
layout: default
title: Formal Methods
nav_order: 10
---

# Formal Methods

ZIO OpenFeature includes machine-checkable formal specifications for three of
its most concurrency-sensitive subsystems. The specs live in [`specs/`](../specs/README.md)
and are checked with three different tools — one per problem class.

---

## Why formal methods?

Unit and integration tests verify specific scenarios. A model checker
*exhaustively* explores every possible interleaving within declared bounds,
finding races that would take weeks of load testing to surface by chance.

Past bugs caught or documented by these specs:

| Commit | Bug | Caught by |
|--------|-----|-----------|
| `f4e9345` | Duplicate `PROVIDER_READY` event after `PROVIDER_ERROR` flipped `statusRef` back to `Ready` | TLA+ `EvalPassedOnCanEvaluate` invariant |
| `e41ca60` | Provider hooks ran twice — included in both `allHooks` and the Java SDK's own invocation | Alloy `ProviderHookExactlyOnce` assertion |

---

## Specs at a glance

| Spec | Tool | What it models | File |
|------|------|----------------|------|
| Provider Lifecycle | TLA+ / TLC | 3 concurrent actors racing on `statusRef` | `specs/lifecycle/ProviderLifecycle.tla` |
| CircuitBreaker | P | CAS state machine with N concurrent callers | `specs/circuitbreaker/CircuitBreaker.p` |
| Hook Pipeline | Alloy 6 | Hook stage ordering invariants over execution traces | `specs/hooks/HookPipeline.als` |

---

## Running the specs

### Provider Lifecycle — TLA+ / TLC

Models `FeatureFlagsLive` with three concurrent actors — `Swapper`
(serialised by `swapLock`), `EventBridge` (async Java SDK callbacks), and
`Watchdog` (the `initTimeout` fiber). TLC explores all interleavings and
checks six properties:

| Property | Kind |
|----------|------|
| `StatusValid` | Safety — status stays in the declared enum |
| `ShuttingDownUnreachable` | Safety — `ShuttingDown` is never written |
| `EvalPassedOnCanEvaluate` | Safety — TOCTOU: snapshot was valid when taken |
| `FatalIsTerminal` | Temporal — `Fatal` has no outbound transition |
| `EventuallyNotStuck` | Temporal — `NotReady` eventually resolves |
| `WatchdogFires` | Temporal — watchdog fires when stuck past `initTimeout` |

```sh
curl -sL https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar \
  -o tla2tools.jar

java -jar tla2tools.jar -workers 4 \
  specs/lifecycle/ProviderLifecycle.tla \
  -config specs/lifecycle/ProviderLifecycle.cfg
```

Expected output when all properties hold:

```
Model checking completed. No error has been found.
  Estimates of the probability that TLC did not check all reachable states...
```

### CircuitBreaker — P

Models the CAS loops in `CircuitBreaker.scala` with three concurrent callers
and a `SpecMonitor` that observes every state transition. Verifies:

- Half-open single-flight — at most one probe in-flight at any time
- `Open(External)` reachable only via `trip()`
- `reset()` only closes `Open(External)`, never `Open(Failures)`
- `consecutiveFailures >= 0` at all times

```sh
dotnet tool install --global P   # requires .NET SDK

p compile specs/circuitbreaker/CircuitBreaker.p
p check -tc TestCircuitBreaker -i 10000
```

### Hook Pipeline — Alloy 6

Checks structural invariants over hook execution traces using Alloy's bounded
relational model checker:

| Assertion | What it checks |
|-----------|----------------|
| `ReverseSymmetry` | `after`/`error`/`finally` run in reverse `before` order |
| `FinallyTotality` | `finallyAfter` runs for every hook unconditionally |
| `ProviderHookExactlyOnce` | No `ProviderTier` hook appears in `allHooks` |
| `HookDataIdentityKey` | Duplicate hook registration collapses to one `HookData` |
| `ApiClientInvocationOrder` | API → Client → Invocation ordering in `before` |

```sh
curl -sL https://github.com/AlloyTools/org.alloytools.alloy/releases/latest/download/org.alloytools.alloy.dist.jar \
  -o alloy6.jar

# Headless — runs all five assertions
java -cp alloy6.jar edu.mit.csail.sdg.alloy4whole.ExampleUsingTheCompiler \
  specs/hooks/HookPipeline.als

# GUI — visualise instances and counterexamples as graphs
java -jar alloy6.jar specs/hooks/HookPipeline.als
```

---

## The verification workflow

Use the specs as a **pre-merge gate** on PRs that touch the modelled
components:

1. Edit `FeatureFlagsLive.scala`, `CircuitBreaker.scala`, or `Hook.scala`
2. Update the corresponding spec to reflect the changed behaviour
3. Run the checker — a violated property produces an exact counterexample trace
4. Fix the code (or the property if the invariant was wrong), re-run until clean
5. Merge — the CI configuration in `specs/README.md` automates TLA+ and Alloy
   checks on PRs that touch the relevant source files

---

## Reading a counterexample

### TLC counterexample

```
Error: Invariant StatusValid is violated.
State 1: <Initial predicate>
  /\ status = "NotReady"
  /\ swapLocked = FALSE
State 4: <Swapper line 112>
  /\ status = "ShuttingDown"   ← unexpected value
```

Each numbered state shows all variables. Work backwards from the violating
state to find which actor wrote the unexpected value and under what condition.

### P counterexample

```
<ErrorLog> Assertion Failed: more than one probe in-flight in HalfOpen state
<Trace>
  1. CircuitBreakerMachine receives eTryAcquire from Caller(1) → eAllowed
  2. CircuitBreakerMachine receives eTryAcquire from Caller(2) → eAllowed
     *** hoProbing was already true — CAS race ***
```

Re-run with `-v` for verbose event dumps. Increase `-i` to `100000` for
broader coverage.

### Alloy counterexample

A failed assertion opens the Counterexample Visualizer (in the GUI) showing
the relational instance that breaks the assertion — hooks, tiers, and
execution indices as nodes and edges. In headless mode it prints:

```
Executing "check ReverseSymmetry for 4 but 3 Int"
   Counterexample found. Assertion is not valid.
```

Open the GUI and click **Show** to browse the violating instance.

---

## Why three different tools?

Each spec was written in a different language because each problem has a
different *shape*, and different tools are built for different shapes.

### TLA+ — exhaustive state enumeration

TLA+ models a system as a set of variables and transitions. TLC, the model
checker, does **breadth-first search** over the reachable state space: it
literally enumerates every possible state the system can be in and checks that
no invariant is violated in any of them.

This is the right tool for the provider lifecycle because the bug there
(`f4e9345`) was a *specific state sequence* — `NotReady → Error → Ready`
caused by a late-arriving `PROVIDER_READY` event after a failed swap. TLC
finds it by exploring all orderings of all transitions, not by running random
interleavings.

**Tradeoff:** the state space must be bounded (the `.cfg` sets
`N_BRIDGE_EVENTS = 2`, `INIT_TIMEOUT = 5`). Outside those bounds TLC can't
finish.

### P — concurrent event-driven programs

P is designed for **message-passing machines** — state machines that
communicate by sending events to each other. The checker runs a **randomised
scheduler** that explores many interleavings and fires any `assert` in the
`SpecMonitor` machine on every observed state.

This fits the circuit breaker because the code is structured exactly as
concurrent callers sending operations to a shared resource. P's model maps
directly to that structure: `Caller` machines send `eTryAcquire`,
`eRecordFailure`, etc. to the `CircuitBreakerMachine`, and the `SpecMonitor`
watches every state snapshot.

**Tradeoff:** P does *bounded random exploration* (`-i 10000` interleavings),
not exhaustive search. It finds concurrency bugs quickly but cannot prove
absence of bugs the way TLC can.

### Alloy — structural/relational properties

Alloy doesn't model time or concurrency at all. It reasons about **relations
between sets** — it asks "does there exist *any* configuration of hooks,
tiers, and execution indices that satisfies the structural constraints but
violates this assertion?" The checker does bounded exhaustive search over all
possible relational instances up to a given size (`for 4`).

This fits the hook pipeline because the correctness properties there are
*structural*, not temporal — "after always runs in reverse before order",
"every hook gets exactly one `finallyAfter`." These are statements about the
shape of an execution trace, not about race conditions or timing. Alloy
expresses and checks them as concise relational constraints.

**Tradeoff:** Alloy can only check *finite bounded instances*, not infinite
executions. For structural ordering properties that's usually sufficient.

### Tool selection summary

| Tool | Technique | Best for |
|------|-----------|----------|
| TLA+ / TLC | Exhaustive state enumeration (BFS) | State machines with bounded state space; safety and liveness over concurrent transitions |
| P | Randomised concurrent scheduling | Message-passing systems with many concurrent actors; assertion-based monitors |
| Alloy | Bounded relational model checking | Structural invariants over data shapes; ordering, symmetry, uniqueness properties |

The tools are not interchangeable. You *could* model the hook pipeline in
TLA+, but you'd end up writing something far more verbose than a four-line
Alloy assertion. You *could* model the circuit breaker in TLA+, but P's
event-machine syntax maps directly to the code structure, making the spec
easier to read and maintain alongside the implementation.

---

## Further reading

- [TLA+ Home & video course](https://lamport.azurewebsites.net/tla/tla.html)
- [Practical TLA+ (Hillel Wayne, O'Reilly)](https://www.oreilly.com/library/view/practical-tla-planning/9781484238295/)
- [P language](https://p-org.github.io/P/)
- [Alloy Tools](https://alloytools.org/)
- [How AWS Uses Formal Methods (PDF)](https://assets.amazon.science/67/f9/92733d574c11ba1a11bd08bfb8ae/how-amazon-web-services-uses-formal-methods.pdf)
