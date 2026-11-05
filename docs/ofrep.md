# OFREP

The [OpenFeature Remote Evaluation Protocol](https://github.com/open-feature/protocol) (OFREP) is a vendor-neutral
HTTP API for evaluating flags on a server. flagd, OFREP relays and a growing number of flag services speak it.
`zio-openfeature-ofrep` connects to any of them.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep" % "<version>"
```

It builds on the OpenFeature contrib `ofrep` provider (0.0.2). Both it and the protocol are young, so pin the version
deliberately. The module is separate from [Extras](extras.md) because it brings Jackson, Guava and SLF4J with it.

## Quick start

```scala
--8<-- "Ofrep.scala:quick-start"
```

```mermaid
flowchart LR
    A["FeatureFlags"] --> B["OFREPFeatureProvider"]
    B -->|"POST baseUrl/ofrep/v1/evaluate/flags/key<br/>(context in the body)"| C["OFREP server"]
    C -->|"JSON answer or HTTP error"| B
    B --> D["typed tier: value or FeatureFlagError<br/>total tier: value or your default"]
```

Every evaluation is one HTTP call. The provider has no start-up step, so it is ready as soon as it is registered.

## Creating the provider

`OFREPProvider.make` checks the base URL before building anything: it must be non-empty, parse as a URI, use `http` or
`https`, and name a host. Anything else fails with `FeatureFlagError.InvalidConfiguration`, so a typo stops your app at
startup instead of at the first evaluation. The contrib provider then runs its own URL check, which also rejects a host
whose top-level domain it does not know, such as `.test`; that is an `InvalidConfiguration` too.

`make` returns an `IO`; `scoped` and `layer` shut the provider down with their scope or layer, giving up on a shutdown
that takes longer than 5 seconds and logging a warning, while a provider from `make` is shut down by a `FeatureFlags`
layer that [owns the API](providers.md#who-shuts-down-the-api). A layer with a domain doesn't by default, so use `scoped`
or `layer` there. Each takes a base URL, an `OFREPProviderConfig`,
or the contrib provider's own `OfrepProviderOptions`.

```scala
--8<-- "Ofrep.scala:lifecycle"
```

## Configuration

```scala
--8<-- "Ofrep.scala:config"
```

A config starts from `OFREPProviderConfig(baseUrl)` and changes one field per `with<Field>` setter. It has no `copy`.

| `OFREPProviderConfig` field | Default | Setter | Meaning |
|---|---|---|---|
| `baseUrl` | required | `withBaseUrl(url)` | the OFREP server |
| `requestTimeout` | the contrib provider's | `withRequestTimeout(d)` | longest wait for one HTTP request |
| `connectTimeout` | the contrib provider's | `withConnectTimeout(d)` | longest wait to open a connection |
| `headers` | none | `withHeaders(h)` | headers sent with every request, such as an `Authorization` token |
| `proxy` | the JVM's default | `withProxy(selector)` | a `java.net.ProxySelector` for the HTTP traffic |

Keep `FeatureFlags`' `evaluationTimeout` (one second by default) at or below `requestTimeout`.

## When something goes wrong

| What happens | Typed tier | Total tier |
|---|---|---|
| The server answers 401 or 403 | fails with `Unauthorized` | your default, error code `General` |
| The server answers 429, 5xx or another status not listed here | fails with `ProviderError`, whatever the body says | your default, error code `General` |
| The server answers 400, 404 or 406 with an OFREP JSON body | fails with `InvalidContext` (400, 406) or `FlagNotFound` (404) | your default, with that error code |
| The server cannot be reached | fails with `ProviderError` | your default, error code `General` |
| The response is not OFREP JSON (an HTML error page, an empty body, truncated JSON), whatever the status | fails with `ProviderError`; a plain-text body naming the failure (`Forbidden`, `Unauthorized`) reads as `Unauthorized`, since the parse error quotes it | your default, error code `General` |

To alert on the total tier, check the resolution's error code:

```scala
--8<-- "Ofrep.scala:alerting"
```

## With a circuit breaker

`CircuitBreakerProvider` from [Extras](extras.md#circuit-breaker-provider) wraps the OFREP provider directly:

```scala
--8<-- "Ofrep.scala:circuit-breaker"
```

The contrib provider reports `READY` and emits no state events, so only failed calls trip the breaker: network errors,
`GENERAL` answers (401, 403, 429, 5xx, and any answer that is not OFREP JSON, such as a proxy's HTML error page) and
calls slower than the breaker's `evaluationTimeout`. A 404 (`FLAG_NOT_FOUND`) or a type error proves the server is
reachable and does not count. A 429 whose body is not JSON counts as a failure but cannot set the rate-limit window:
the contrib provider reads `Retry-After` only from a response it could parse.

## `Long` flags

`FeatureFlags.long` reads JSON integers exactly. Anything else the server sends for a long read, including a whole
decimal such as `2.0`, is a `TypeMismatch`. Use `make`, `scoped` or `layer`: the contrib provider on its own rejects
every integer flag on a long read.

## Notes

??? note "Contrib provider details"
    - `OFREPFeatureProvider` wraps the contrib `OfrepProvider`; `.underlying` returns it.
    - HTTP calls run on a pool of daemon threads, so the provider never keeps your JVM from exiting. If you build
      `OfrepProviderOptions` yourself, start from `OFREPProvider.daemonOptionsBuilder()`: the contrib default is a pool
      of non-daemon threads.
