---
layout: default
title: Home
nav_order: 1
description: "ZIO OpenFeature - A ZIO wrapper for the OpenFeature SDK"
permalink: /
---

# ZIO OpenFeature

A ZIO-native wrapper around the [OpenFeature](https://openfeature.dev/) Java SDK for Scala 2.13 and Scala 3.
{: .fs-6 .fw-300 }

[Get Started]({{ site.baseurl }}/getting-started){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/EtaCassiopeia/zio-openfeature){: .btn .fs-5 .mb-4 .mb-md-0 }

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

---

## Why ZIO OpenFeature?

ZIO OpenFeature wraps the OpenFeature Java SDK, giving you access to the entire OpenFeature ecosystem while providing a type-safe, functional API designed for ZIO applications.

- **Use Any Provider** - Works with all OpenFeature providers: LaunchDarkly, Flagsmith, Flipt, flagd, and more
- **Type Safety** - Compile-time guarantees with the `FlagType` type class
- **ZIO Native** - Effect-based API with proper resource management
- **Transactions** - Scoped flag overrides with caching and evaluation tracking
- **Testkit** - In-memory provider for testing without external dependencies

## Quick Example

```scala
import zio.*
import zio.openfeature.*
import dev.openfeature.contrib.providers.flagd.FlagdProvider

object MyApp extends ZIOAppDefault:

  val program = for
    enabled <- FeatureFlags.boolean("new-feature", default = false)
    _       <- ZIO.when(enabled)(Console.printLine("Feature enabled!"))
  yield ()

  def run = program.provide(
    Scope.default >>> FeatureFlags.fromProvider(new FlagdProvider())
  )
```

## Two ways to read a flag

The call above names the key, the type and the default at the use site. That is the direct style, and it is
fully supported. If the same flag is read from more than one place, declaring it once is usually the simpler
option — the key, type and default stop being repeated, and a second call site can no longer disagree about
them:

```scala
object Flags:
  val NewFeature = FlagDef("new-feature", false, "the new checkout flow")

// anywhere
FeatureFlags.value(Flags.NewFeature)           // ZIO[FeatureFlags, FeatureFlagError, Boolean]
FeatureFlags.valueOrDefault(Flags.NewFeature)  // never fails — serves the definition's default
```

Both styles run through the same evaluation path — hooks, transactions, caching and error semantics are
identical, and you can mix them in one codebase. See [Typed Flags]({{ site.baseurl }}/typed-flags).

## Documentation

Start with **Getting Started**, then **Typed Flags** if you want the declare-once style. The rest are
reference pages — read them when you reach the problem they solve.

| Section | Description |
|:--------|:------------|
| [Getting Started]({{ site.baseurl }}/getting-started) | Installation and basic usage |
| [Typed Flags]({{ site.baseurl }}/typed-flags) | Declare a flag once — key, type and default — and evaluate it by name |
| [Architecture]({{ site.baseurl }}/architecture) | Core design and components |
| [Providers]({{ site.baseurl }}/providers) | Using OpenFeature providers |
| [Extras]({{ site.baseurl }}/extras) | HOCON, env var, and caching providers |
| [Optimizely]({{ site.baseurl }}/optimizely) | Optimizely Feature Experimentation integration |
| [Evaluation Context]({{ site.baseurl }}/context) | Targeting and context hierarchy |
| [Hooks]({{ site.baseurl }}/hooks) | Cross-cutting concerns |
| [Transactions]({{ site.baseurl }}/transactions) | Flag overrides and tracking |
| [Testkit]({{ site.baseurl }}/testkit) | Testing utilities |
| [Testing Real Providers]({{ site.baseurl }}/testing-real-providers) | Fault-testing a real provider over TLS-MITM |
| [Spec Compliance]({{ site.baseurl }}/spec-compliance) | OpenFeature specification compliance |

## Added since 1.0.0

Everything below is documented in the pages above; this table is a shortcut to the right section.

| Feature | What it gives you | Where |
|:--------|:------------------|:------|
| Typed tier fails on error codes | `value` / `*Details` fail with a typed `FlagNotFound`, `TypeMismatch`, … when the provider reports an error code — a fail-closed gate stays closed; the total tier still serves the default | [Getting Started]({{ site.baseurl }}/getting-started#two-tiers-typed-and-total) |
| `NestedPolicy.Reuse` | An inner transaction runs inside the enclosing one instead of failing — a per-request transaction is safe as middleware | [Transactions]({{ site.baseurl }}/transactions#nested-transactions) |
| `transactionEvaluations` | `None` outside a transaction, `Some(...)` inside — an audit read can no longer mistake "no transaction" for "nothing evaluated" | [Transactions]({{ site.baseurl }}/transactions#checking-transaction-state) |
| Wrappers take any `FeatureProvider` | `CircuitBreakerProvider` and `CachingProvider` no longer require an `EventProvider` | [Extras]({{ site.baseurl }}/extras#circuit-breaker-provider) |
| `FlagDef[A]` | Declare a flag once and evaluate it by name | [Typed Flags]({{ site.baseurl }}/typed-flags) |
| `derives FlagType` | Codecs for your own enums and case classes, no boilerplate | [Typed Flags]({{ site.baseurl }}/typed-flags#flags-that-are-not-boolean-string-or-a-number) |
| `FlagType.wireType` | A domain type carried over the wire as a scalar resolves through that scalar's method, and hooks see it | [Architecture]({{ site.baseurl }}/architecture#scalar-backed-custom-types) |
| Native 64-bit `Long` | `long`/`longDetails` resolve the full `Long` range exactly, instead of losing precision past 2^53 | [Extras]({{ site.baseurl }}/extras#integer-widening-long-provider) |
| `ContextSource` | Pull ambient context (MDC, tracing, correlation id) into every evaluation | [Evaluation Context]({{ site.baseurl }}/context#context-source) |
| `FallbackLogging` | A served default leaves a warn line, rate-limited per flag key | [Getting Started]({{ site.baseurl }}/getting-started#total-evaluation-never-fails) |
| `verify` + `AcquireStatus` | Reject a real provider that constructed but cannot serve, and ask whether it is live yet | [Providers]({{ site.baseurl }}/providers#fallback-first-initialization-fromacquireasync) |
| `FLAG_NOT_FOUND` for absent keys | A provider that does not hold a key lets a `MultiProvider` chain advance | [Extras]({{ site.baseurl }}/extras), [Testkit]({{ site.baseurl }}/testkit) |
| Typed fixtures (`:=`) | Pin a test fixture through the flag's own codec | [Testkit]({{ site.baseurl }}/testkit#typed-fixtures-with-flagdef) |
| `TestFeatureProvider.makeNamed` | Distinct metadata names, so a chain of test providers is really a chain | [Testkit]({{ site.baseurl }}/testkit) |

## Modules

| Module | Description |
|:-------|:------------|
| **core** | ZIO wrapper around OpenFeature SDK |
| **extras** | Built-in HOCON, env var, and caching providers |
| **ofrep** | OpenFeature Remote Evaluation Protocol (OFREP) provider over HTTP |
| **optimizely** | First-party Optimizely Feature Experimentation integration |
| **testkit** | In-memory provider for testing |

## Requirements

- Scala 2.13+ or Scala 3.3+
- ZIO 2.1+

## License

ZIO OpenFeature is distributed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).
