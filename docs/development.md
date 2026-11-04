# Development

## Where things live

| Directory | What it holds |
|:----------|:--------------|
| `core` | The `FeatureFlags` service, context, hooks, transactions, events |
| `extras` | HOCON and environment-variable providers; caching, circuit-breaker and deferred wrappers |
| `ofrep`, `optimizely` | The OFREP and Optimizely providers |
| `schema` | `SchemaFlagType`, a `FlagType` derived from a zio-schema `Schema` |
| `testkit` | `TestFeatureProvider` and the test helpers |
| `conformance`, `conformance-zio-bdd` | The OpenFeature spec's gherkin suite, run with Cucumber and with zio-bdd; `conformance-zio-bdd` also runs the provider TCK |
| `provider-fault-tests` | Real providers under network faults (local only; see [Extra checks](#extra-checks)) |
| `examples/docs`, `examples/testkit-app`, `examples/ofrep-init-timeout` | Not published. `docs` holds every code sample on this site, with tests that run them; the others are reference apps |

[Under the hood](architecture.md) has the module dependency diagram.

## Build and test

```sh
sbt compile                 # all published modules, Scala 3
sbt +test                   # Scala 2.13 and 3
sbt scalafmtAll             # format
sbt conformance/test        # the spec's gherkin suite, via Cucumber
sbt conformanceZioBdd/test  # the same suite via zio-bdd, and the provider TCK (JDK 22+)
sbt examplesDocs/test examplesTestkitApp/test   # compile and run the examples
```

Library sources shared by both Scala versions live in `src/main/scala` and `src/test/scala`, and compile on 2.13 and 3,
so they use braces. Version-specific code goes in `scala-2/` and `scala-3/` next to them. The docs snippets and the
fault tests are Scala 3 only, so their sources sit in `scala-3/`.

The version comes from git: a commit with a `v*` tag is that release, any other commit is the next version with a
`-SNAPSHOT` suffix. See [RELEASING.md](https://github.com/EtaCassiopeia/zio-openfeature/blob/main/RELEASING.md), which
also covers the binary-compatibility baselines.

## What CI runs

| Job | Run it locally |
|:----|:---------------|
| format | `sbt scalafmtCheckAll scalafmtSbtCheck providerFaultTests/scalafmtCheckAll fuzz/scalafmtCheckAll` (`providerFaultTests` and `fuzz` are not aggregated, so format them explicitly) |
| build and test, JDK 17, 21 and 25, Scala 2.13 and 3 | `sbt +test` |
| binary compatibility | `sbt +mimaReportBinaryIssues tastyMiMaReportIssues`, then `.github/scripts/check-mima-filters.sh`. While 2.0 is in development there is no baseline, so the first two compare nothing and the script rejects any filter |
| published POMs | `sbt +checkPublishedPins +optimizely/checkOptimizelyJsonParser` |
| examples, and the fault-test and fuzz sources | `sbt examplesOfrepInitTimeout/compile examplesTestkitApp/test examplesDocs/test providerFaultTests/Test/compile fuzz/Test/compile` |
| conformance | `sbt conformance/test conformanceZioBdd/test` |
| gherkin copies in sync (every push); upstream drift (weekly) | `.github/scripts/check-gherkin-drift.sh mirror`; `.github/scripts/check-gherkin-drift.sh upstream main` |
| provider TCK upstream drift (weekly) | `.github/scripts/check-provider-tck-drift.sh upstream main` |
| dependency check (weekly) | OWASP Dependency-Check over each module's resolved classpath |
| coverage (weekly, report only) | `sbt coverage test coverageReport coverageAggregate`, then `python3 scripts/coverage-summary.py .` for the per-module table |

The docs job compiles and tests the samples (`examplesDocs/test`), compiles `providerFaultTests/Test/compile`, checks the
animations are up to date, and runs `mkdocs build`.

### The provider TCK

`conformance-zio-bdd` also runs the OpenFeature provider conformance suite (TCK), vendored unchanged in
`src/test/resources/features/provider-tck/` with its canonical flag set; its README holds the pin. The TCK tests a
provider rather than this library, so it drives each provider through a plain OpenFeature client. In CI it runs against
`HoconProvider`, `EnvVarProvider`, and `CachingProvider`, `CircuitBreakerProvider` and `DeferredProvider` over
`HoconProvider`, one suite each in `zio.openfeature.conformance.bdd.tck`.

A provider declares what it supports by leaving the TCK's capability tags out of its suite's `excludeTags`; a scenario
under a withheld capability is reported as ignored, never as passed. `TckBackends` says why each one is withheld. What a
declared or mandatory scenario still fails is a reviewed `KnownFailure` in that provider's list, naming the flag, the
check, the outcome it gives instead and why. The assertion must then fail with exactly that outcome: a known failure
that starts passing, or fails some other way, fails the suite until its entry is updated. The
weekly `Provider TCK drift` workflow files an issue when upstream moves.

## The documentation site

The site is built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) from `docs/` and published to
GitHub Pages from `main`. `strict: true` in `mkdocs.yml` makes a broken link or a missing snippet fail the build.

Code samples are not written in the pages. They live in `examples/docs/src/main/scala-3/snippets/` and
`examples/docs/src/test/scala-3/snippets/` (and, for the rift code, `provider-fault-tests/src/test/scala-3/snippets/`),
between `// --8<-- [start:name]` and `// --8<-- [end:name]` markers, and a page includes them with
`--8<-- "File.scala:name"`. So every sample compiles, and the tests run the ones that can run on their own.

```sh
pip install -r requirements-docs.txt
mkdocs serve                  # preview at http://127.0.0.1:8000
mkdocs build                  # fails on broken links and missing snippets
```

The animations are SVG files generated by the Python scripts in `scripts/visuals/`, one per animation, on top of the
small toolkit in `hud.py`. Edit the script, not the SVG, then regenerate; CI fails if a committed SVG is out of date:

```sh
python3 scripts/visuals/build.py              # all of them
python3 scripts/visuals/build.py evaluation   # one
```

## Extra checks

Mutation testing, the flake hunt, fuzzing and the provider fault tests never run on a pull request; the property tests
run a short version there.

**Flake hunt.** The nightly `Flake hunt` workflow runs the whole suite five times on two CPUs and compares the JUnit
reports with `scripts/flake-report.py`: a test that failed in one run and passed in another, one that failed in every
run, and a suite missing from a run (a crash or hang) are all reported in the job summary. A finding fails the run
unless `.github/flaky-tests.txt` lists it with its tracking issue; remove the entry when the issue is fixed.

**Fuzzing.** The `fuzz` module (not aggregated, compiled in CI) holds [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
targets for the code that reads untrusted input: environment variable values, HOCON text, and OFREP responses served by
a local server. Each typed read must return an evaluation or throw an `OpenFeatureError`; anything else is a finding.
The weekly `Fuzzing` workflow runs each target for five minutes from the corpus in `fuzz/corpus/<target>/` and uploads
findings with a Java reproducer, plus new corpus entries to fold back in by hand. The command to run one locally is in
the comment on the module in the build.

**Mutation testing** makes small deliberate changes to `core` and `extras` (flipping a condition, swapping an
operator) and checks that a test fails for each. A full run takes hours, so the `Mutation testing` workflow runs weekly
(or by hand). Each module has a score floor (`core` 60%, `extras` 66%, set in the workflow); a run below it fails, and
its summary lists every surviving mutant by file, with the score again without string-literal mutants (mostly log
messages). Only a module's own tests count: a `testkit` test does not kill a `core` mutant, so a test that pins core
behaviour belongs in `core`. Raise a floor by hand when you kill survivors. Reports land in
`<module>/target/stryker4s-report/`; to try a few files, narrow `mutate` in `stryker4s.conf` locally.

```sh
sbt core/stryker
sbt extras/stryker
```

**Property tests** generate inputs and check an invariant over each: value and context round trips, hostile values,
cache keys, the circuit breaker, hook ordering, the status machine, and `LifecycleModelSpec`, which runs generated
sequences of lifecycle actions (swaps to providers that start, fail, hang, hang and then fail, start or shut down
slowly, some reusing the name of the provider they replace; events from the installed provider and from one already
replaced; shutdown; an interrupted swap; handing back a provider a timed-out swap replaced; and pairs of these run at
once) against a real `FeatureFlags` and checks every step against a model written from
[Lifecycle and events](lifecycle.md) and [Providers](providers.md#replace-the-provider-at-runtime). A pair must agree
with the model after one of the orders its two actions can take effect in, and when a case ends every provider it
created must have been shut down exactly once. Each such test carries the shared `PropertyBudget(n)` aspect: pull requests run
its base count with zio-test's fixed seed, and the nightly `Property tests` workflow runs every one at 10× with a fresh
seed, printed in the job summary. Replay a nightly failure with the same scale and seed, then check the shrunk case in
as an explicit example (seeds are not stable across zio-test versions):

```sh
ZOF_PBT_SCALE=10 ZOF_PBT_SEED=<seed> sbt "core/testOnly -- -tags property"
```

A new property test takes `@@ PropertyBudget(n)` from `zio.openfeature.testsupport` (compiled into every module's
tests), so the nightly run picks it up.

**Deterministic interleavings.** Random timing finds lifecycle races slowly and cannot replay them, so
`LifecycleInterleavingSpec` (testkit) enumerates them instead. The lifecycle code calls a probe at named points of the
protocol (`internal/LifecycleProbe`: a swap starting, the SDK binding the new provider, a swap timing out or rolling
back, an SDK event reaching the bridge, a status write, the init watchdog, shutdown and the scope close). Production
instances get `LifecycleProbe.none`, a no-op, and the evaluation code never receives the probe.
The test holds each primary action (a swap that succeeds, fails or times out; a shutdown; a provider event) at each
point it reaches, runs a competing action while it is held, then lets it go, and checks the outcome against the same
model `LifecycleModelSpec` uses. A reach table lists the points each primary passes, so a probe that moves fails the
test rather than silently testing nothing. To hold a new point, add it to `LifecycleProbe.Point`, call `probe.at` there,
and add it to the reach table.

**Model checking.** `formal/` holds TLA+ models of the library's concurrent protocols. The `Formal model` workflow
checks them with TLC, on a pull request that touches `formal/`, the workflow, or the code a model maps (the paths are
listed in the workflow), and on `main`. A pull request that changes the lifecycle code without changing the model says
why, in a description line starting `formal: unaffected because`.

| Model | What it checks | Configs and tools | README |
|:------|:---------------|:------------------|:-------|
| `formal/lifecycle` | The provider lifecycle: swaps, rollbacks, the event bridge, the providers a timed-out swap leaves behind, shutdown, with the Java SDK as its environment. The invariants include the final `NotReady`, `NotReady` throughout a swap, no second `initialize()` while one runs, and every provider shut down exactly once | `LifecyclePR.cfg` on a pull request, `Lifecycle.cfg` on `main` and by hand; on every run also `Liveness.cfg` (recovery under fairness), `Shutdowns.cfg` and `ShutdownsLiveness.cfg` (providers whose `shutdown()` throws or hangs), `Evaluation.cfg` (one evaluation against swaps) and the silent-death witness | `formal/lifecycle/README.md` |
| `formal/lifecycle`, proofs | The structural invariants of the same model, proved with TLAPS for any number of providers, swaps, events and evaluations | `LifecycleProofs.tla` | `formal/lifecycle/README.md` |
| `formal/lifecycle`, traces and replay | Trace validation: each run the lifecycle test suites record is a behaviour of the model. Replay: behaviours generated from the model are replayed against the lifecycle's pure step | `validate.py`, `generate.py`, `LifecycleReplaySpec` | `formal/lifecycle/README.md`, `formal/lifecycle/replay-map.md` |
| `formal/registry` | One API shared by several instances: a single owner tears it down, each registered provider is shut down exactly once, one client per domain | `Registry.cfg` | `formal/registry/README.md` |
| `formal/lane` | `OrderedEmitter`, the lane the wrappers re-emit events through: slot writes follow the order of receipt, no deadlock with the SDK's lock, no dropped emission | `Lane.cfg`, `Witness.cfg` | `formal/lane/README.md` |
| `formal/caching` | `CachingProvider`: an invalidation holds against every order of lookup and write-back, a failed or `DEFAULT` result is not kept, a caller is not handed another lookup's failure | `Caching.cfg` | `formal/caching/README.md` |
| `formal/circuitbreaker` | The circuit breaker's lock-free state machine: at most one half-open probe, stale outcomes ignored, a dead probe's slot freed | `CircuitBreaker.cfg`, `Signals.cfg`, `Liveness.cfg` | `formal/circuitbreaker/README.md` |

The lifecycle, registry, lane, caching and breaker models also have a `mutants.py` that puts each fixed bug back into the
model and checks that a property catches it. The registry, lane, caching and breaker mutants run on every pull request;
the lifecycle mutants run on `main` and by hand.

A pull request checks smaller bounds for the lifecycle model than `main` does. `LifecyclePR.cfg` allows one provider
event and `Lifecycle.cfg` two, and the full bounds outgrew a pull request's wait: the pull request check takes about 14
minutes in CI (limit 30 minutes), the full one roughly 65 to 80 minutes on `main` (limit 150 minutes). A break only the
full bounds can find shows up in `main`'s run, so read it after a merge that touches the model. The other lifecycle
configs and the registry, lane, caching and breaker models are small enough to run in full on every pull request; the
workflow puts its group of small lifecycle and lane checks at about 25 minutes in all, and the registry model takes under
a second.

Trace validation and the replay need TLC 1.8, which reads the traces with the community modules' `Json`. CI takes it
from a `tooling/tla2tools-*` prerelease of this repository, and TLAPS from a `tooling/tlapm-*` one; the workflow's
`TRACE_TLA_TOOLS_RELEASE` and `TLAPM_RELEASE` name them. The other checks use TLC 1.7.4, so running `validate.py` or
`generate.py` with that jar will not work. A `tooling/*` tag goes on a parentless anchor commit, never on a commit of
`main`; see [RELEASING.md](https://github.com/EtaCassiopeia/zio-openfeature/blob/main/RELEASING.md#tags). On a pull
request the traces job checks the checker's fixtures and replays 2000 generated behaviours from a fixed seed. On `main`,
nightly and by hand it also validates the runs the lifecycle suites record, and nightly it replays 20000 behaviours from
a rotating seed.

To check a model change locally, run a pull request's bounds first. These are the commands for the lifecycle and
registry models with TLC 1.7.4; the traces and the replay have their own in `formal/lifecycle/README.md`:

```sh
cd formal/lifecycle
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config LifecyclePR.cfg Lifecycle.tla   # a pull request's bounds
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Lifecycle.cfg Lifecycle.tla      # main's bounds, about an hour
python3 mutants.py tla2tools.jar
cd ../registry
java -cp ../lifecycle/tla2tools.jar tlc2.TLC -workers auto -deadlock -config Registry.cfg Registry.tla
python3 mutants.py ../lifecycle/tla2tools.jar
```

**Linearizability checks.** `CircuitBreakerLincheckSpec` runs the circuit breaker's operations from several threads
under [Lincheck](https://github.com/JetBrains/lincheck)'s model checker, which explores thread interleavings and checks
every outcome matches some sequential order of the same calls. It belongs to the property run, so pull requests check
a small sample and the nightly run ten times more. It suits a lock-free structure driven by plain threads; state held in
a ZIO `Ref` is atomic by construction, and an effect running on ZIO's own scheduler is outside what the model checker
controls. A deliberately racy counter in the same spec must fail, so a checker that stopped seeing races fails too.

**Fault schedules over wrapper stacks.** `WrapperStackFaultSpec` (extras) generates a stack of up to three wrappers
(cache, circuit breaker, deferred construction, a multi-provider chain) over a `FaultyProvider`, scripts faults into its
initialization, evaluations and shutdown (throws, delays, hangs, `ERROR`/`STALE`/`READY` events, a blocking
`shutdown()`), and drives a real `FeatureFlags` through it. Every case must build or fail with a typed error, answer
each `booleanOrDefault` within a bound, pass a provider-reported error through to the status, serve the flag and
report `Ready` once the faults stop, and close its scope
without leaking a fiber or a non-daemon thread (`LeakCheck` in `zio.openfeature.testsupport`). It is a property test,
so the nightly run multiplies its budget. `OFREPFailureModeSpec` covers the HTTP side in CI with WireMock: dribbled
bodies, random bytes, malformed chunks, empty responses, truncated JSON and an HTML page served with status 200.

**End-state checks.** The lifecycle, swap, shutdown and abandoned-call specs, the interleaving grid and the extras
wrapper specs run under `Quiescence.aspect` (`zio.openfeature.testsupport`). After each test it fails the test if:
- a fiber or non-daemon thread the test started is still alive;
- an instance the test built still counts an abandoned call;
- once closed or shut down, an instance still has its event bridge on the client, an orphan left, a shut-down
  wrapper whose event lane still runs, or a status other than `NotReady` after `shutdown`;
- a `TestFeatureProvider` or `FaultyProvider` had `shutdown()` reach it twice in one initialization cycle.

Instances and test providers register on the test's fiber when they are made; one built in a fiber that is never
joined, or in a layer built outside the test's fiber, is not checked. A live instance skips the bridge and lane
checks, and checks its orphans only while no swap holds its lock.

**Provider fault tests** run the real OFREP and Optimizely providers through rift's TLS intercept proxy while scripted
network faults play out; see [Testing real providers](testing-real-providers.md). They are a dev-machine check by
design: not aggregated into the root project, so `sbt test` never runs them; CI only compiles them; they need JDK 22 or
later for rift's native engine (the suites are reported as ignored elsewhere); and they make no external network calls.

```sh
sbt providerFaultTests/test    # JDK 22+
```
