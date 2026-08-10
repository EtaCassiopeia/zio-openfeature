---
layout: default
title: Testing Real Providers
nav_order: 10
---

# Testing Real Providers Under Network Faults
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## When to reach for this

[`TestFeatureProvider`]({{ site.baseurl }}/testkit) simulates a provider's *states* — ready, not
ready, stale, error. That is the right tool for almost every test, and it is deterministic,
in-memory, and runs anywhere.

What it cannot do is exercise a **real** provider's own machinery: its HTTP client, its TLS
handshake, its payload parsing, and its initialization and polling paths. A vendor SDK that hangs
on a stalled datafile fetch, returns `401` for a rotated key, or chokes on a truncated body does so
inside code `TestFeatureProvider` never runs.

To cover that gap you need the real provider talking to a real socket, with something adversarial in
the middle. [rift-scala](https://github.com/achird-labs/rift-scala) provides that: an in-process
TLS-MITM intercept proxy plus zio-test fixtures for wiring the JVM at it.

**Positioning.** These are *integration* tests. They boot an embedded engine, mutate JVM-global proxy
and trust state, and need a modern JDK with platform natives on the test classpath. Keep them
local and JDK-gated — a separate module, or gated by `riftAspects.embeddedOnly` — and out of the
default CI matrix. This library ships **no** rift dependency in any published module, including
`testkit`; the wiring below belongs in your own test build.

---

## Requirements

{: .warning }
> **The fixtures used on this page are not in a published rift-scala release yet.** They landed in
> [rift-scala#145](https://github.com/achird-labs/rift-scala/issues/145) after `v0.1.4`, which is
> the latest release at the time of writing and contains neither `rift.zio.testkit.intercept` nor
> the `sbt-rift` plugin. Pin the first release **after `0.1.4`** that includes them; the version
> below is a placeholder until then.

```scala
// build.sbt — test scope only, in a module you gate locally
libraryDependencies += "io.github.achird-labs" %% "rift-scala-zio-testkit" % riftVersion % Test
```

```scala
// project/plugins.sbt — only needed for the truststore wiring described below
addSbtPlugin("io.github.achird-labs" % "sbt-rift" % riftVersion)
```

The embedded engine also needs the rift platform natives on the test classpath and a JDK that can
bind them through the Foreign Function & Memory API:

| JDK | What you need |
|:----|:--------------|
| 21 | the JDK-21 FFM artifact plus `--enable-preview --enable-native-access=ALL-UNNAMED` on the forked test JVM (`sbt-rift` sets the preview flag via `riftTlsEnablePreview := true`) |
| 22+ | no flags — FFM is final |

This repo already runs exactly that configuration for its own conformance suites: see the
`conformance-zio-bdd` module in `build.sbt`, which pins the JDK-21 embedded engine, forks the test
JVM with both flags, and runs its suites sequentially because they share one in-process engine.
It is the working precedent to copy.

Where the engine cannot load, `Rift.isEmbeddedAvailable` is `false` and `riftAspects.embeddedOnly`
turns the suite into an *ignored* one rather than a failing one.

---

## How the provider's HTTP client reaches the proxy

Which wiring you need depends on how the provider builds its HTTP client — this is the single
biggest source of "the fixture ran but intercepted nothing":

| The client... | Wiring | Notes |
|:--------------|:-------|:------|
| is built by your test | none — hand it `handle.sslContext` / `handle.proxySelector` | weakest coupling; prefer it when the SDK allows it |
| reads `ProxySelector.getDefault` / `SSLContext.getDefault` at construction | `intercept.systemProxySelector` / `intercept.systemSslContext` | a stock `java.net.http.HttpClient` does this |
| reads `javax.net.ssl.trustStore` once at first TLS init | `intercept.systemProxyProps` **plus** a truststore on disk before the JVM forks | Apache HttpClient and most vendor SDKs built on it |

The third row is why `sbt-rift` exists: a truststore that must exist *before* the test JVM forks
cannot be created from inside that JVM. Enabling the plugin replaces the whole hand-rolled
CA/truststore/`javaOptions` dance with one line:

```scala
lazy val providerFaultTests =
  (project in file("provider-fault-tests")).enablePlugins(RiftTlsPlugin)
```

It generates a throwaway CA and truststore under `target/rift`, sets `Test / fork := true`, and
points the forked JVM at both — including the `rift.ca.p12` properties that
`intercept.caFromBuildProps` reads back, so the proxy mints leaf certificates from the same CA the
JVM already trusts. Nothing generated is a secret: the CA is minted per checkout under `target/`.

---

## Scenario matrix

Worth covering for any remote provider. The **Intercept rule** column matters — see
[Serve rules vs imposters](#serve-rules-vs-imposters) for why half these rows cannot use `serve`:

| Scenario | Intercept rule | Expected behavior |
|:---------|:---------------|:------------------|
| healthy | `serve` a valid payload | provider becomes ready; its values are served |
| slow endpoint | `redirectTo` an imposter stalling ~30s | boot does not block (`fromAcquireAsync`); fallback answers immediately |
| slow endpoint + configured fallback | same, with a seeded fallback | the fallback's *configured* value, not just the hardcoded default |
| rejected credentials | `serve` a `401` | provider never ready; falls through to fallback; evaluation never fails |
| connection reset | `redirectTo` an imposter with a TCP fault | same — falls through, never fails |
| malformed payload | `serve` a non-JSON body typed as JSON | same — falls through, never fails |

---

## Serve rules vs imposters

An intercept rule can answer in two ways, and they are **not** interchangeable:

`serve(...)` delivers exactly a **status code, headers and a body** — nothing else. Since
[rift-scala#150](https://github.com/achird-labs/rift-scala/issues/150), anything the engine's serve
action cannot actually deliver is **rejected** at rule-creation time with a
`RiftError.InvalidDefinition` naming every offender: waits (`.after`, `.afterBetween`), fault
injection (`.withTcpFault`, `.withLatencyFault`, `.withErrorFault`), templating, `decorate`,
`repeat`, `shellTransform`, binary bodies, and repeated header names.

```scala
// Rejected — a serve rule cannot carry a wait.
handle.rule("cdn.example.com").serve(ok.json(payload).after(FiniteDuration(30, SECONDS)))
```

That rejection is a feature. Before it, the call was accepted and then answered a plain `200`
*without* the stall, so a resilience test passed against a success response nobody asked for.

For a wait or a fault, point the rule at an **imposter**, which carries the full response model:

```scala
// Correct — the imposter owns the wait; the rule just routes to it.
for
  engine <- ZIO.service[Rift]
  handle <- ZIO.service[InterceptHandle]
  slow   <- engine.create(
              imposter("slow-cdn")
                .stub(get("/datafile.json").reply(ok.json(payload).after(FiniteDuration(30, SECONDS))))
            )
  _      <- handle.rule("cdn.example.com").redirectTo(slow)
yield ()
```

{: .warning }
> `.after` takes a **`scala.concurrent.duration.FiniteDuration`**. Under `import zio.*`, `30.seconds`
> is a `zio.Duration` (an alias for `java.time.Duration`) and will not compile here — hence the
> explicit `FiniteDuration(30, SECONDS)` from `scala.concurrent.duration`. Importing both duration
> vocabularies unqualified makes `30.seconds` ambiguous instead, so prefer the explicit form in a
> suite that already imports `zio.*`.

One more serve-rule behavior worth knowing: a **non-string** JSON body with no caller-set
content-type is now typed `application/json` automatically
([rift-scala#151](https://github.com/achird-labs/rift-scala/issues/151)), matching the imposter path. A
string body is left alone — so to make a *malformed* payload land in the SDK's JSON parser rather
than being rejected on content type, set the header yourself:

```scala
handle.rule("cdn.example.com").serve(ok.text("<html>nope</html>").header("Content-Type", "application/json"))
```

---

## The fixture layer

For a suite whose scenarios are all expressible as `serve`, the one-call layer is enough:

```scala
import rift.zio.testkit.intercept as riftIntercept

// ZLayer[Any, RiftError, InterceptHandle] — embedded engine + intercept + JVM proxy props
riftIntercept.tlsIntercept
```

It defaults to `CaSource.BuildPropsOrGenerated` (the build's CA under `sbt-rift`, an engine-minted
one otherwise) and installs only `systemProxyProps`; `InterceptTestConfig` turns on
`proxySelector`, `sslContext`, or `includeHttpProxy` when the client under test needs them.

`tlsIntercept` builds its engine internally and yields **only** the `InterceptHandle`, so it cannot
create the imposters the stall and TCP-fault rows need. When you need those, compose the same
pieces yourself and keep the engine in the environment:

```scala
import rift.bridge.InterceptConfig
import rift.zio.{InterceptHandle, Rift}
import rift.zio.testkit.intercept as riftIntercept
import zio.*

val riftFixture: ZLayer[Any, rift.RiftError, Rift & InterceptHandle] =
  ZLayer.scopedEnvironment {
    for
      engine <- Rift.embedded.build.map(_.get[Rift])
      ca     <- riftIntercept.caFromBuildProps()
      handle <- engine.intercept(InterceptConfig(ca = Some(ca)))
      _      <- riftIntercept.systemProxyProps(handle)
    yield ZEnvironment(engine, handle)
  }
```

{: .note }
> `caFromBuildProps` **fails** when the build did not wire a CA, rather than silently falling back
> to one nothing trusts. Under a plain `sbt test` with no `RiftTlsPlugin`, drop it and let the
> engine mint its own — but then pair it with `sslContext = true`, or hand
> `handle.sslContext` to a client you construct, since nothing on the machine trusts that CA.

---

## Worked example

```scala
import rift.dsl.*
import rift.model.TcpFaultKind
import rift.zio.{InterceptHandle, Rift}
import rift.zio.testkit.aspects as riftAspects
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.test.*
import zio.test.TestAspect.*

object RealProviderFaultSpec extends ZIOSpecDefault:

  // The fallback is SEEDED, so its answer differs from the OpenFeature default below. A fallback
  // that agrees with the default makes every assertion here pass with rift absent entirely.
  val seededFallback = EnvVarProvider.withLookup(Map("FF_MY_FLAG" -> "true").get)

  val flagsLayer: ZLayer[Scope, Nothing, FeatureFlags] =
    FeatureFlags.fromAcquireAsync(
      acquire = ZIO.attemptBlocking(newRealProvider()),
      fallback = ZIO.succeed(seededFallback)
    )

  def spec =
    suite("real provider under network faults")(
      test("stalled fetch: boot serves the fallback immediately") {
        for
          engine <- ZIO.service[Rift]
          handle <- ZIO.service[InterceptHandle]
          _      <- handle.clearRules
          slow   <- engine.create(
                      imposter("slow-cdn")
                        .stub(
                          get("/datafile.json")
                            .reply(ok.json(datafileJson).after(FiniteDuration(30, SECONDS)))
                        )
                    )
          _      <- handle.rule("cdn.example.com").redirectTo(slow)
          // The upstream stalls for 30s; a boot that blocked on it could not answer inside 5.
          flag   <- ZIO
                      .scoped(FeatureFlags.boolean("my-flag", default = false).provideSome[Scope](flagsLayer))
                      .timeoutFail(new AssertionError("boot blocked on the stalled fetch"))(5.seconds)
        yield assertTrue(flag) // the fallback's configured value, not the `default = false`
      },
      test("rejected credentials: never ready, evaluation never fails") {
        for
          handle <- ZIO.service[InterceptHandle]
          _      <- handle.clearRules
          _      <- handle.rule("cdn.example.com").serve(status(401).json("""{"error":"unauthorized"}"""))
          exit   <- ZIO.scoped(FeatureFlags.boolean("my-flag", default = false).provideSome[Scope](flagsLayer)).exit
        yield assertTrue(exit.isSuccess)
      },
      test("connection reset: falls through to the fallback") {
        for
          engine <- ZIO.service[Rift]
          handle <- ZIO.service[InterceptHandle]
          _      <- handle.clearRules
          broken <- engine.create(
                      imposter("reset-cdn")
                        .stub(
                          get("/datafile.json")
                            .reply(ok.json(datafileJson).withTcpFault(TcpFaultKind.ConnectionResetByPeer))
                        )
                    )
          _      <- handle.rule("cdn.example.com").redirectTo(broken)
          exit   <- ZIO.scoped(FeatureFlags.boolean("my-flag", default = false).provideSome[Scope](flagsLayer)).exit
        yield assertTrue(exit.isSuccess)
      }
      // ... one test per scenario row
    ).provideShared(riftFixture) @@ sequential @@ withLiveClock @@ riftAspects.embeddedOnly
```

Five things in that suite are load-bearing:

**`provideShared` + `sequential`.** Every `system*` helper mutates state shared by the whole JVM —
proxy properties, the default `ProxySelector`, the default `SSLContext` — and restores it when the
scope closes, including on failure and interruption. Nesting is safe; *racing* is not. One shared
fixture, one test at a time.

**`clearRules` then reinstall, per test.** Intercept rules accumulate on the handle. A suite that
adds without clearing gets whichever rule matches first, which is rarely the one the test just
wrote.

**`riftAspects.embeddedOnly`.** Degrades the suite to *ignored* where the embedded engine cannot
load, instead of failing for reasons that say nothing about your code.

**`withLiveClock`.** The stalls and faults happen in the engine, on wall-clock time. zio-test's
default clock is the *test* clock, which no amount of `TestClock.adjust` will advance the proxy
by — without the live clock a timeout-based assertion measures nothing.

**The renamed import.** `import rift.zio.testkit.aspects as riftAspects` is required, not stylistic.
`ZIOSpec` — and so every `ZIOSpecDefault` — inherits a member of its own called `aspects`, and an
inherited definition binds tighter than an import, so inside a spec the bare name resolves to
zio-test's default aspects and **never** to rift's object.

---

## Assertion patterns

**"Boot did not block."** `fromAcquireAsync` builds on a fresh fallback immediately and swaps the
real provider in from a background fiber, so status is `Ready` from time zero and evaluations answer
fallback values rather than `ProviderNotReady`. Assert on the *value*, and let a stalled upstream
prove itself by the fallback's answer arriving promptly.

**"Never fails."** The evaluation API is typed (`IO[FeatureFlagError, Boolean]`), so a fault that
leaks becomes a visible failure. Assert on the exit rather than the value:

```scala
FeatureFlags.boolean("my-flag", default = false).exit.map(exit => assertTrue(exit.isSuccess))
```

For call sites that must be total regardless, `booleanOrDefault` has no error channel at all.

**"The provider did become ready"** (the healthy row). `awaitReady` is backed by the status-change
stream rather than polling, and returns the status at that moment either way:

```scala
FeatureFlags.awaitReady(5.seconds).map(status => assertTrue(status.canEvaluate))
```

**"The real provider swapped in."** The swap happens on a background fiber, so it is the one thing
worth polling for — bounded, and spaced so a fast swap does not spin:

```scala
FeatureFlags
  .boolean("my-flag", default = false)
  .repeat(Schedule.spaced(100.millis) && Schedule.recurUntil[Boolean](identity))
  .timeout(10.seconds)
```

{: .note }
> `fromAcquireAsync` composes real and fallback with `MultiProvider` + `FirstSuccessfulStrategy`,
> which keys children by **provider metadata name and silently drops duplicates**. If your real
> provider and fallback report the same name, one of them disappears — rename one.

---

## See also

- [Testkit]({{ site.baseurl }}/testkit) — `TestFeatureProvider` for state-level tests
- [Providers]({{ site.baseurl }}/providers) — `fromAcquireAsync` and the async-init factories
- [Extras]({{ site.baseurl }}/extras) — `EnvVarProvider` and the other built-in fallbacks
