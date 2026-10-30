# Testing real providers

[`TestFeatureProvider`](testkit.md) simulates a provider's states, which covers almost every test. It cannot run a real
provider's own code: its HTTP client, TLS handshake, payload parsing, start-up and polling. A vendor SDK that hangs on a
stalled fetch, or chokes on a `401` or a truncated body, does so in code the test provider never runs.

To test that, run the real provider against a real socket with something hostile in between.
[rift-scala](https://github.com/achird-labs/rift-scala) provides an in-process TLS man-in-the-middle proxy, plus
zio-test fixtures that point the JVM at it. Your test decides, per host, what the provider's requests get.

![A real provider's HTTPS requests go through the rift intercept proxy. A serve rule answers directly with a status and a body, such as a 401; a rule that redirects to an imposter can stall the connection. FeatureFlags keeps answering from its fallback throughout.](assets/real-provider-faults.svg)

These are integration tests: they start a native engine, change JVM-wide proxy and TLS settings, and need a recent JDK.
Keep them in a separate module that you run locally, not in the default CI build. This repository's
`provider-fault-tests` module does this to the real OFREP and Optimizely providers; the code on this page is its code.

## Set up the test module

```scala
// build.sbt: rift-scala 0.4.0 is what this repository uses
libraryDependencies += "io.github.achird-labs" %% "rift-scala-zio-testkit" % "0.4.0" % Test

// project/plugins.sbt, for providers that read the JVM truststore (see below)
addSbtPlugin("io.github.achird-labs" % "sbt-rift" % "0.4.0")
```

The embedded engine also needs rift's platform natives on the test classpath and a forked JVM that may load them:

| JDK | Add |
|:----|:----|
| 22 or later | `"io.github.achird-labs" % "rift-java-embedded" % "0.3.7" % Test`, the matching `rift-java-natives` for your platform, and `--enable-native-access=ALL-UNNAMED` |
| 21 | `rift-java-embedded-jdk21`, and `--enable-preview --enable-native-access=ALL-UNNAMED` (with sbt-rift: `riftTlsEnablePreview := true`) |

Where the engine cannot load, `rift.zio.Rift.isEmbeddedAvailable` is `false`.

## Point the provider at the proxy

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

`intercept.caFromBuildProps()` then loads that CA (and fails if the plugin is not enabled). The plugin is opt-in and
per project, so no other module's test JVM changes. Give the provider a host with a real-looking top-level domain such as `example.com`: the OFREP provider
validates its base URL and rejects `.test`.

## Build the `FeatureFlags` side

Build the layer with [`fromAcquireAsync`](providers.md#start-on-a-fallback-fromacquireasync): it starts on a fallback at
once and swaps the real provider in when it is ready, so a broken network cannot block start-up or fail an evaluation.

```scala
--8<-- "RealProvidersSpec.scala:flags-layer"
```

## Set up the rift fixture

The fixture keeps the engine in the environment, so tests can create imposters:

```scala
--8<-- "RiftFixture.scala:fixture-imports"

--8<-- "RiftFixture.scala:fixture"
```

When every scenario is a plain `serve`, `riftIntercept.tlsIntercept` is enough: one layer that yields just the
`InterceptHandle`.

## Write the suite

Each test installs its rules, then evaluates through `flagsLayer`. Here the provider is Optimizely's, pointed at
`cdn.example.com`:

```scala
--8<-- "RealProviderFaultSpec.scala:suite-imports"

--8<-- "RealProviderFaultSpec.scala:suite"
```

Two details are easy to miss. Rename the rift `aspects` import (`aspects as riftAspects`): every `ZIOSpecDefault` has its
own member called `aspects`, which would hide rift's. And `.after` takes a `scala.concurrent.duration.FiniteDuration`;
under `import zio.*`, `30.seconds` is a `zio.Duration` and does not compile there.

### Serve rules and imposters

- `serve(response)` answers with a status, headers and a body, nothing more. A response with a wait, a fault or
  templating is rejected when you create the rule.
- `redirectTo(imposter)` sends the request to an imposter, which supports the full response model, including
  `.after(delay)` and `.withTcpFault(...)`.

So waits and faults always go through an imposter.

## Scenarios worth covering

What you should see, through `fromProvider` (the provider's own error surfaces):

| Scenario | Rule | OFREP | Optimizely |
|:---------|:-----|:------|:-----------|
| healthy | serve a valid payload | its values are served | its values are served |
| rejected credentials | serve `401` or `403` | typed `Unauthorized` | the layer fails with "datafile did not load", within its init wait |
| unavailable, rate limited | serve `503` or `429` | provider error | as above |
| malformed payload | serve a truncated or non-JSON body marked `application/json` | provider error | as above |
| connection reset | redirect to an imposter with a TCP fault | provider error | as above |
| stalled endpoint | redirect to an imposter that waits ~30 s | `ProviderError(TimeoutException)` at the evaluation timeout | as above, at the init wait |

Through `fromAcquireAsync`, Optimizely fetches at start-up, so start-up does not wait: the fallback's value is served at
once and `AcquireStatus` ends in `Failed`. OFREP fetches per evaluation, so its error answers fall through to the
fallback, while a stalled call ends in the typed timeout at the evaluation timeout. In every case the call returns within
a bound, and closing the scope does too.

## Assert on the outcome

Assert on the outcome (the fallback is seeded, so a test that never reached the fault fails):

```scala
--8<-- "RealProvidersSpec.scala:assert-boot"
```

```scala
--8<-- "RealProvidersSpec.scala:assert-never-fails"
```

For code that must never fail, use the total tier (`booleanOrDefault` and friends), which has no error channel. For the
healthy scenario, wait for the swap through `AcquireStatus`, which `fromAcquireAsync` also provides, and wrap it in a
`timeout` if the provider might never become ready:

```scala
--8<-- "RealProvidersSpec.scala:assert-swapped"
```

To run the module locally, see [Development](development.md#extra-checks).
