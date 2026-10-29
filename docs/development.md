# Development

## Where things live

| Directory | What it holds |
|:----------|:--------------|
| `core` | The `FeatureFlags` service, context, hooks, transactions, events |
| `extras` | HOCON and environment-variable providers; caching, circuit-breaker and deferred wrappers |
| `ofrep`, `optimizely` | The OFREP and Optimizely providers |
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
| format | `sbt scalafmtCheckAll scalafmtSbtCheck providerFaultTests/scalafmtCheckAll` (`providerFaultTests` is not aggregated, so format it explicitly) |
| build and test, JDK 17, 21 and 25, Scala 2.13 and 3 | `sbt +test` |
| binary compatibility | `sbt +mimaReportBinaryIssues` |
| published POMs | `sbt +checkPublishedPins +optimizely/checkOptimizelyJsonParser` |
| examples | `sbt examplesOfrepInitTimeout/compile examplesTestkitApp/test examplesDocs/test` |
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

Mutation testing and the provider fault tests never run on a pull request; the property tests run a short version there.

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
sequences of lifecycle actions (swaps to providers that start, fail, hang or start slowly, provider events, shutdown,
an interrupted swap) against a real `FeatureFlags` and checks every step against a model written from
[Lifecycle and events](lifecycle.md). Each such test carries the shared `PropertyBudget(n)` aspect: pull requests run
its base count with zio-test's fixed seed, and the nightly `Property tests` workflow runs every one at 10× with a fresh
seed, printed in the job summary. Replay a nightly failure with the same scale and seed, then check the shrunk case in
as an explicit example (seeds are not stable across zio-test versions):

```sh
ZOF_PBT_SCALE=10 ZOF_PBT_SEED=<seed> sbt "core/testOnly -- -tags property"
```

A new property test takes `@@ PropertyBudget(n)` from `zio.openfeature.testsupport` (compiled into every module's
tests), so the nightly run picks it up.

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

**Provider fault tests** run the real OFREP and Optimizely providers through rift's TLS intercept proxy while scripted
network faults play out; see [Testing real providers](testing-real-providers.md). They are a dev-machine check by
design: not aggregated into the root project, so `sbt test` never runs them; CI only compiles them; they need JDK 22 or
later for rift's native engine (the suites are reported as ignored elsewhere); and they make no external network calls.

```sh
sbt providerFaultTests/test    # JDK 22+
```
