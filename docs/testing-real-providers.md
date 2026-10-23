# Testing real providers

[`TestFeatureProvider`](testkit.md) simulates a provider's states: ready, not ready, stale, failing. That covers
almost every test. What it cannot do is run a real provider's own code: its HTTP client, TLS handshake, payload
parsing, start-up and polling. A vendor SDK that hangs on a stalled fetch, or chokes on a `401` or a truncated body,
does so in code the test provider never runs.

To test that, run the real provider against a real socket with something hostile in between.
[rift-scala](https://github.com/achird-labs/rift-scala) provides it: an in-process TLS man-in-the-middle proxy, plus
zio-test fixtures that point the JVM at it. Your test then decides, per host, what the provider's requests get.

![A real provider's HTTPS requests go through the rift intercept proxy. A serve rule answers directly with a status and a body, such as a 401; a rule that redirects to an imposter can stall or reset the connection. FeatureFlags keeps answering from its fallback throughout.](assets/real-provider-faults.svg)

These are integration tests. They start a native engine, change JVM-wide proxy and TLS settings, and need a recent
JDK. Keep them in a separate test module that you run locally, not in the default CI build. No published module of
this library depends on rift.

!!! note
    The rift code on this page uses rift-scala's API, which this repository does not build against, so it is not
    compiled with the docs. The `FeatureFlags` code is.

## Setup

Add the fixtures to the test module (rift-scala 0.3.0 or later):

```scala
// build.sbt
libraryDependencies += "io.github.achird-labs" %% "rift-scala-zio-testkit" % "0.4.0" % Test

// project/plugins.sbt, for providers that read the JVM truststore (see below)
addSbtPlugin("io.github.achird-labs" % "sbt-rift" % "0.4.0")
```

The embedded engine also needs rift's platform natives on the test classpath and a forked JVM that may load them:

| JDK | Add |
|:----|:----|
| 22 or later | `rift-java-embedded`, and `--enable-native-access=ALL-UNNAMED` |
| 21 | `rift-java-embedded-jdk21`, and `--enable-preview --enable-native-access=ALL-UNNAMED` (with sbt-rift: `riftTlsEnablePreview := true`) |

This repository's `conformance-zio-bdd` module runs this setup on JDK 25: see its settings in `build.sbt`.
Where the engine cannot load, `rift.zio.Rift.isEmbeddedAvailable` is `false`.

## Pointing the provider at the proxy

How you wire the proxy depends on how the provider builds its HTTP client. Getting this wrong is the usual reason a
test runs but intercepts nothing.

| The provider's HTTP client... | Wiring |
|:------------------------------|:-------|
| is one you build and pass in | give it `handle.sslContext` and `handle.proxySelector` |
| reads `ProxySelector.getDefault` and `SSLContext.getDefault` | `intercept.systemProxySelector(handle)` and `intercept.systemSslContext(handle)` |
| reads `javax.net.ssl.trustStore` once, at first use (Apache HttpClient, most vendor SDKs) | `intercept.systemProxyProps(handle)`, plus a truststore that exists before the test JVM starts |

For the last row, enable the sbt-rift plugin on the test module. It creates a throwaway CA and truststore under
`target/rift`, forks the test JVM and points it at both:

```scala
lazy val providerFaultTests = (project in file("provider-fault-tests")).enablePlugins(RiftTlsPlugin)
```

`intercept.caFromBuildProps()` then loads that CA, so the proxy signs its certificates with a CA the JVM trusts. It
fails if the plugin is not enabled.

## The `FeatureFlags` side

Build the layer with [`fromAcquireAsync`](providers.md): it starts on a fallback at once and swaps the real provider
in when it is ready, so a broken network cannot block start-up or fail an evaluation.

```scala
--8<-- "RealProvidersSpec.scala:flags-layer"
```

Seed the fallback with a value different from the default your test passes. Otherwise a test that never reached the
fault would still pass.

## Scenarios worth covering

| Scenario | Rule | Expect |
|:---------|:-----|:-------|
| healthy | serve a valid payload | the provider becomes ready; its values are served |
| slow endpoint | redirect to an imposter that waits ~30 s | start-up does not wait; the fallback's value is served at once |
| rejected credentials | serve a `401` | never ready; the fallback answers; no evaluation fails |
| connection reset | redirect to an imposter with a TCP fault | the same |
| malformed payload | serve a non-JSON body marked `application/json` | the same |

### Serve rules and imposters

A rule answers in one of two ways:

- `serve(response)` answers with a status, headers and a body, nothing more. A response with a wait, a fault or
  templating is rejected when you create the rule.
- `redirectTo(imposter)` sends the request to an imposter, which supports the full response model, including
  `.after(delay)` and `.withTcpFault(...)`.

So waits and faults always go through an imposter.

## A suite

The rift side of the fixture keeps the engine in the environment, so tests can create imposters:

```scala
import rift.bridge.InterceptConfig
import rift.zio.{InterceptHandle, Rift}
import rift.zio.testkit.intercept as riftIntercept

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

When every scenario is a plain `serve`, `riftIntercept.tlsIntercept` is enough: one layer that yields just the
`InterceptHandle`.

Each test installs its rules, then evaluates through `flagsLayer`:

```scala
import rift.dsl.*
import rift.zio.testkit.aspects as riftAspects
import scala.concurrent.duration.{FiniteDuration, SECONDS}

object RealProviderFaultSpec extends ZIOSpecDefault:

  def spec = suite("real provider under network faults")(
    test("stalled fetch: the fallback answers at once") {
      for
        engine <- ZIO.service[Rift]
        handle <- ZIO.service[InterceptHandle]
        _      <- handle.clearRules
        slow   <- engine.create(
                    imposter("slow-cdn").stub(get("/datafile.json").reply(ok.json(datafile).after(FiniteDuration(30, SECONDS))))
                  )
        _      <- handle.rule("cdn.example.com").redirectTo(slow)
        result <- bootDidNotBlock.provideSome[Scope](flagsLayer(ZIO.attemptBlocking(newVendorProvider())))
      yield result
    },
    test("rejected credentials: evaluation never fails") {
      for
        handle <- ZIO.service[InterceptHandle]
        _      <- handle.clearRules
        _      <- handle.rule("cdn.example.com").serve(status(401).json("""{"error":"unauthorized"}"""))
        result <- neverFails.provideSome[Scope](flagsLayer(ZIO.attemptBlocking(newVendorProvider())))
      yield result
    }
  ).provideShared(riftFixture) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ riftAspects.embeddedOnly
```

The suite settings all matter:

- **`provideShared` and `sequential`.** The proxy settings are JVM-wide. They are restored when the scope closes, but
  two tests must not change them at once.
- **`clearRules` first.** Rules accumulate on the handle; without clearing, an earlier test's rule may match first.
- **`withLiveClock`.** Waits and faults happen in the engine, in real time. On zio-test's default test clock, a
  timeout measures nothing.
- **`riftAspects.embeddedOnly`.** Marks the suite ignored where the engine cannot load, instead of failing it.
- **`aspects as riftAspects`.** Rename the import: every `ZIOSpecDefault` has its own member called `aspects`, which
  would hide rift's.

`.after` takes a `scala.concurrent.duration.FiniteDuration`. Under `import zio.*`, `30.seconds` is a `zio.Duration`
and does not compile there.

## Assertions

The page's `FeatureFlags` assertions, as used in the suite above:

```scala
--8<-- "RealProvidersSpec.scala:assert-boot"
```

```scala
--8<-- "RealProvidersSpec.scala:assert-never-fails"
```

For code that must never fail, use the total tier (`booleanOrDefault` and friends), which has no error channel.

For the healthy scenario, wait for the swap through `AcquireStatus`, which `fromAcquireAsync` also provides:

```scala
--8<-- "RealProvidersSpec.scala:assert-swapped"
```

Wrap it in a `timeout` if the provider might never become ready.
