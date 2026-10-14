# Formal Specifications — `zio-openfeature`

Three state machines in this codebase have correctness properties that are
difficult to verify by inspection or testing alone. Each is encoded below
as a machine-checkable spec. Counterexamples from these models have already
helped diagnose past bugs (see individual spec files for details).

---

## Quick start

### TLA+ / TLC (Provider Lifecycle)

```sh
# Download the CLI jar once
curl -sL https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar \
  -o tla2tools.jar

# Run the model checker
java -jar tla2tools.jar -workers 4 \
  specs/lifecycle/ProviderLifecycle.tla \
  -config specs/lifecycle/ProviderLifecycle.cfg
```

Or open `ProviderLifecycle.tla` in the [TLA+ Toolbox IDE](https://lamport.azurewebsites.net/tla/toolbox.html)
and load `ProviderLifecycle.cfg` as the model configuration for a visual run.

### P (CircuitBreaker) — requires .NET SDK

```sh
dotnet tool install --global P

p compile specs/circuitbreaker/CircuitBreaker.p
p check -tc TestCircuitBreaker -i 10000
```

### Alloy 6 (Hook Pipeline)

```sh
# Download the Alloy jar once
curl -sL https://github.com/AlloyTools/org.alloytools.alloy/releases/latest/download/org.alloytools.alloy.dist.jar \
  -o alloy6.jar

# Run all assertions headless
java -cp alloy6.jar edu.mit.csail.sdg.alloy4whole.ExampleUsingTheCompiler \
  specs/hooks/HookPipeline.als

# Or open the GUI to visualise instances and counterexamples as graphs
java -jar alloy6.jar specs/hooks/HookPipeline.als
```

---

## Verification workflow

The specs are most valuable as a **pre-merge gate** on PRs that touch the
modelled state machines. The loop is:

1. **Edit `FeatureFlagsLive.scala`, `CircuitBreaker.scala`, or `Hook.scala`**
2. **Update the corresponding spec** to reflect the new behaviour
3. **Run the checker** — if a property fails, you get an exact counterexample
   trace that shows the step-by-step interleaving that breaks the invariant
4. **Fix the code** (or the property if the invariant was wrong), re-run until clean
5. **Merge** — the CI workflow in `specs/README.md § CI integration` automates
   TLA+ and Alloy checks for PRs that touch relevant source files

The specs are **not** a replacement for the ZIO test suite — they catch a
different class of bug. Tests verify specific scenarios; model checkers
exhaustively explore every possible interleaving within the declared bounds.
A race that would take weeks of load testing to surface shows up as a two-line
counterexample trace in TLC.

---

## Reading counterexamples

### TLC (TLA+)

A violated invariant prints a numbered state sequence:

```
Error: Invariant StatusValid is violated.
The behavior up to this point is:
State 1: <Initial predicate>
  /\ status = "NotReady"
  /\ swapLocked = FALSE
  ...
State 4: <Swapper line 112>
  /\ status = "ShuttingDown"   ← unexpected value
  ...
```

Each state shows all variables. Work backwards from the violating state to
find which actor wrote the bad value and under what condition.

### P

A safety violation prints an event trace:

```
<ErrorLog> Assertion Failed:
  Invariant violated: more than one probe in-flight in HalfOpen state
<Trace>
  1. CircuitBreakerMachine(1): receives eTryAcquire from Caller(1) → sends eAllowed
  2. CircuitBreakerMachine(1): receives eTryAcquire from Caller(2) → sends eAllowed
     *** hoProbing was already true — CAS race ***
```

Re-run with `-v` for verbose event dumps. The `-i` flag controls how many
random interleavings to explore; increase to `100000` for higher coverage.

### Alloy

A failed assertion opens the Counterexample Visualizer showing a relational
instance that violates the assertion. In headless mode it prints:

```
Executing "check ReverseSymmetry for 4 but 3 Int"
   Counterexample found. Assertion is not valid.
   ...
```

Open the GUI (`java -jar alloy6.jar`) and click **Show** to browse the
violating instance as a graph — hooks, tiers, and execution indices are shown
as nodes and edges.

---

## Resource list

- [TLA+ Home](https://lamport.azurewebsites.net/tla/tla.html) · [Tutorial](https://lamport.azurewebsites.net/tla/tutorial/home.html) · [Video course](https://lamport.azurewebsites.net/video/videos.html) · [Advanced](https://lamport.azurewebsites.net/tla/advanced.html)
- [Practical TLA+ (book)](https://www.oreilly.com/library/view/practical-tla-planning/9781484238295/)
- [Specifying Systems (Lamport)](https://lamport.azurewebsites.net/tla/book.html)
- [P language](https://p-org.github.io/P/)
- [Alloy Tools](https://alloytools.org/)
- [FizzBee](https://fizzbee.io)
- [How AWS Uses Formal Methods (PDF)](https://assets.amazon.science/67/f9/92733d574c11ba1a11bd08bfb8ae/how-amazon-web-services-uses-formal-methods.pdf)
- [Practical Formal Methods book](https://www.amazon.com/Way-Practical-Programming-Formal-Methods/dp/0521559766)
- [Redex (PL semantics)](https://redex.racket-lang.org/)

---

## Spec overview

| Spec | Tool | Target | File |
|------|------|--------|------|
| Provider Lifecycle | TLA+ / TLC | `ProviderStatus` state machine — 3 concurrent actors | `lifecycle/ProviderLifecycle.tla` |
| CircuitBreaker | P | CAS state machine — N concurrent callers | `circuitbreaker/CircuitBreaker.p` |
| Hook Pipeline | Alloy 6 | Hook stage ordering, reverse symmetry, finally-totality | `hooks/HookPipeline.als` |

---

## Spec 1 — Provider Lifecycle (TLA+)

**Why it matters:** Three concurrent actors race on `statusRef`:
- `Swapper` — `FeatureFlagsLive.setProvider` serialised by `swapLock`
- `EventBridge` — fires `PROVIDER_READY/ERROR/STALE` from Java SDK callbacks
- `Watchdog` — forks at layer build; flips `NotReady|Error → Fatal` after `initTimeout`

Past bugs triggered by this: `f4e9345` (duplicate `PROVIDER_READY` arrived after
`PROVIDER_ERROR`, flipping `statusRef` back to `Ready`). The 500 ms
`FailedSwapGuardMillis` is a heuristic that TLC can show is unsound under
unbounded scheduler delay.

**Properties checked:**

| Property | Kind | Expected |
|----------|------|----------|
| `StatusValid` | Safety | Holds — status always in declared enum |
| `ShuttingDownUnreachable` | Safety | Holds — `ShuttingDown` is never written |
| `EvalPassedOnCanEvaluate` | Safety | Holds — the snapshot at `checkProviderStatus` was valid |
| `FatalIsTerminal` | Temporal | Holds — `Fatal` has no outbound transition |
| `EventuallyNotStuck` | Temporal | Holds with watchdog fairness |
| `WatchdogFires` | Temporal | Holds |

**Run:**
```sh
# Install TLA+ Toolbox or the tla2tools.jar CLI
# https://github.com/tlaplus/tlaplus/releases

java -jar tla2tools.jar -workers 4 \
  specs/lifecycle/ProviderLifecycle.tla \
  -config specs/lifecycle/ProviderLifecycle.cfg
```

Or open `ProviderLifecycle.tla` in the TLA+ Toolbox and load
`ProviderLifecycle.cfg` as the model configuration.

---

## Spec 2 — CircuitBreaker (P)

**Why it matters:** The CAS loops in `CircuitBreaker.scala` are correct but
three claims are asserted via code comments, not proofs:

1. Half-open single-flight — "at most one probe at a time" (also tested in
   `CircuitBreakerSpec.scala:105`)
2. `CircuitBreakerProvider.scala:140-143` — "at most one extra call leaks
   through" the `checkDelegateState`/`tryAcquire` race window
3. `reset()` only closes `Open(External)` — the `Open(Failures)` path is
   never closed by `reset()`

**Spec monitors:**

| Property | Monitor | Expected |
|----------|---------|----------|
| Half-open single-flight | `SpecMonitor` | Holds — CAS guarantees single winner |
| `Open(External)` via `trip()` only | `SpecMonitor` | Holds |
| `consecutiveFailures >= 0` | `SpecMonitor` | Holds |
| `hoSuccesses < halfOpenMaxCalls` in HalfOpen | `SpecMonitor` | Holds |
| `reset()` only closes `Open(External)` | `SpecMonitor` | Holds |
| Starvation: infinite `recordReachable` | Design question | Confirmed — circuit stays HalfOpen |

**Run:**
```sh
# Install P: https://p-org.github.io/P/getstarted/install/
p compile specs/circuitbreaker/CircuitBreaker.p
p check -tc TestCircuitBreaker -i 10000
```

---

## Spec 3 — Hook Pipeline (Alloy 6)

**Why it matters:** The hook ordering rules are structural invariants over
execution traces — exactly what Alloy's bounded relational checker is built for.

Past bug: `e41ca60` — provider hooks ran twice because they were included in
both `allHooks` and the Java SDK's own invocation. `ProviderHookExactlyOnce`
documents the post-fix invariant mechanically.

Open question: `FinallyTotality` checks that every applicable hook gets a
`finallyAfter` invocation — even hooks whose `before` never ran (because a
prior hook's `before` failed). The code does this. Whether spec §4.3.4 *requires*
this is the question the assertion makes precise.

**Assertions:**

| Assertion | Expected | Notes |
|-----------|----------|-------|
| `ReverseSymmetry` | Holds | after/error/finally index = n-1 - before index |
| `FinallyTotality` | Holds | finallyAfter runs for all hooks unconditionally |
| `ProviderHookExactlyOnce` | Holds | No ProviderTier hook in allHooks post-e41ca60 |
| `HookDataIdentityKey` | Holds | Duplicate registration collapses to one HookData |
| `ApiClientInvocationOrder` | Holds | API → Client → Invocation in Before |

**Run:**
```sh
# Download Alloy 6: https://alloytools.org/download.html
java -jar alloy6.jar specs/hooks/HookPipeline.als
# Then run each `check` command from the Execute menu, or headless:
java -cp alloy6.jar edu.mit.csail.sdg.alloy4whole.ExampleUsingTheCompiler \
  specs/hooks/HookPipeline.als
```

---

## CI integration (week 7 target)

Add `.github/workflows/formal-check.yml`:

```yaml
name: Formal checks

on:
  pull_request:
    paths:
      - 'core/src/main/scala/zio/openfeature/FeatureFlagsLive.scala'
      - 'core/src/main/scala/zio/openfeature/Hook.scala'
      - 'core/src/main/scala-3/zio/openfeature/ProviderStatus.scala'
      - 'extras/src/main/scala/zio/openfeature/extras/CircuitBreaker.scala'
      - 'specs/**'

jobs:
  tla:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: |
          curl -sL https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar \
            -o tla2tools.jar
          java -jar tla2tools.jar -workers 4 \
            specs/lifecycle/ProviderLifecycle.tla \
            -config specs/lifecycle/ProviderLifecycle.cfg

  alloy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: |
          curl -sL https://github.com/AlloyTools/org.alloytools.alloy/releases/latest/download/org.alloytools.alloy.dist.jar \
            -o alloy6.jar
          java -cp alloy6.jar edu.mit.csail.sdg.alloy4whole.ExampleUsingTheCompiler \
            specs/hooks/HookPipeline.als

  # P checker is heavier — run on a schedule instead:
  # p-nightly: (see .github/workflows/formal-nightly.yml)
```

P checker nightly job:
```yaml
name: CircuitBreaker P nightly

on:
  schedule:
    - cron: '0 2 * * *'   # 2 AM UTC

jobs:
  p-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: |
          dotnet tool install --global P
          p compile specs/circuitbreaker/CircuitBreaker.p
          p check -tc TestCircuitBreaker -i 10000
```

---

## Secondary targets (deferred)

- **TestFeatureProvider init handshake** — `testkit/…/TestFeatureProvider.scala:34-84`.
  3-actor latch (`test-thread`, `initialize-thread`, `event-bridge`). Good second TLA+ model.
- **Transaction confinement** — `Transaction.scala:65-103`, `FeatureFlagsLive.scala:597-619`.
  `FiberRef` provides safety; Alloy could prove `flagCount` determinism.
- **EvaluationContext merge algebra** — `EvaluationContext.scala:7-51`.
  Associativity + idempotence of deep-`StructValue` merge. Covered almost as well by
  zio-test property-based tests.
- **FeatureFlagRegistry** — coarse `Semaphore(1)` gives serial execution; low priority.

