# OFREP

The [OpenFeature Remote Evaluation Protocol](https://github.com/open-feature/protocol) (OFREP) is a vendor-neutral
HTTP API for evaluating flags on a server. flagd, OFREP relays and a growing number of flag services speak it.
`zio-openfeature-ofrep` connects to any of them.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"
```

It builds on the OpenFeature contrib `ofrep` provider, version 0.0.2. Both it and the protocol are young, so pin the
version deliberately. The module brings Jackson, Guava, Commons Validator and SLF4J with it, which is why it is
separate from [Extras](extras.md).

## Quick start

```scala
--8<-- "Ofrep.scala:quick-start"
```

Each evaluation is one HTTP `POST` to `<baseUrl>/ofrep/v1/evaluate/flags/<key>`, carrying the evaluation context. The
provider has no start-up step, so it is ready as soon as it is registered.

## Creating the provider

`OFREPProvider.make` checks the base URL before building anything. It must be non-empty, parse as a URI, use `http`
or `https`, and name a host. Anything else fails with `FeatureFlagError.InvalidConfiguration`, so a typo stops your
app at startup instead of at the first evaluation.

| Factory | Returns | Use it when |
|---|---|---|
| `make(baseUrl)` | `IO[InvalidConfiguration, OfrepProvider]` | you pass the provider to a `FeatureFlags` layer, which shuts it down |
| `make(config: OFREPProviderConfig)` | same | you need timeouts, headers or a proxy |
| `make(options: OfrepProviderOptions)` | same | you build the contrib provider's own options |
| `scoped(...)` | `ZIO[Scope, InvalidConfiguration, OfrepProvider]` | the provider should be shut down when a scope closes |
| `layer(...)` | `ZLayer[Any, InvalidConfiguration, OfrepProvider]` | you want the provider itself as a layer |

`scoped` and `layer` take the same three kinds of argument as `make`. The old `OFREPProvider()`,
`OFREPProvider(baseUrl)` and `OFREPProvider.fromOptions(options)` still work but are deprecated: they skip the checks.

## Configuration

```scala
--8<-- "Ofrep.scala:config"
```

| `OFREPProviderConfig` field | Default | Meaning |
|---|---|---|
| `baseUrl` | required | the OFREP server |
| `requestTimeout` | 10 s | longest wait for one HTTP request |
| `connectTimeout` | 10 s | longest wait to open a connection |
| `headers` | none | headers sent with every request, such as an `Authorization` token |
| `proxy` | the JVM's default | a `java.net.ProxySelector` for the HTTP traffic |

The provider makes its HTTP calls on a pool of daemon threads, so it never keeps your JVM from exiting. If you build
`OfrepProviderOptions` yourself, start from `OFREPProvider.daemonOptionsBuilder()` rather than
`OfrepProviderOptions.builder()`: the contrib default is a pool of non-daemon threads.

## Timeouts

Every evaluation waits for the network, so bound it. `FeatureFlags` gives up on an evaluation after
`evaluationTimeout`, one second by default:

```scala
--8<-- "Ofrep.scala:timeouts"
```

`requestTimeout` bounds the HTTP request itself. Keep `evaluationTimeout` at or below it, so a slow server costs your
callers no more than you choose.

## When something goes wrong

| What happens | Typed tier (`boolean`, `value`, ...) | Total tier (`booleanOrDefault`, `resolveOrDefault`, ...) |
|---|---|---|
| The server answers 401 or 403 | fails with `Unauthorized` (a wrong or missing credential) | your default, with error code `General` |
| The server answers another 4xx or 5xx | fails with the error the response names, `ProviderError` for `GENERAL` | your default, with the error code on the resolution |
| The server cannot be reached | fails with `Unreachable` (or `ProviderError`) | your default, with error code `General` |
| No answer within `evaluationTimeout` | fails with `ProviderError` holding a `TimeoutException` | your default, with error code `General` |

To alert on the total tier, look at the resolution's error code:

```scala
--8<-- "Ofrep.scala:alerting"
```

## With a circuit breaker

`CircuitBreakerProvider` from [Extras](extras.md#circuit-breaker-provider) wraps the OFREP provider directly:

```scala
--8<-- "Ofrep.scala:circuit-breaker"
```

The OFREP provider emits no events and always reports itself `READY`, so only failed calls trip the breaker:
network errors and calls slower than the breaker's `evaluationTimeout`. An HTTP error comes back as an answer with an
error code, not as a failure, so it does not count.

## `Long` flags

The contrib provider has no resolver of its own for `Long`, so OpenFeature answers `FeatureFlags.long` from its
double resolver. That is exact up to 2^53; beyond it you get a `TypeMismatch` rather than a wrong number. If your
server rejects integer flags read as doubles, wrap the provider in
[`IntegerWideningLongProvider`](extras.md#integer-widening-long-provider).
