---
layout: default
title: Home
nav_order: 1
description: "ZIO OpenFeature - A ZIO wrapper for the OpenFeature SDK"
permalink: /
---

# ZIO OpenFeature

A ZIO-native wrapper around the [OpenFeature](https://openfeature.dev/) Java SDK for Scala 3.
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

## Documentation

| Section | Description |
|:--------|:------------|
| [Getting Started]({{ site.baseurl }}/getting-started) | Installation and basic usage |
| [Architecture]({{ site.baseurl }}/architecture) | Core design and components |
| [Providers]({{ site.baseurl }}/providers) | Using OpenFeature providers |
| [Evaluation Context]({{ site.baseurl }}/context) | Targeting and context hierarchy |
| [Hooks]({{ site.baseurl }}/hooks) | Cross-cutting concerns |
| [Transactions]({{ site.baseurl }}/transactions) | Flag overrides and tracking |
| [Testkit]({{ site.baseurl }}/testkit) | Testing utilities |
| [Spec Compliance]({{ site.baseurl }}/spec-compliance) | OpenFeature specification compliance |

## Modules

| Module | Description |
|:-------|:------------|
| **core** | ZIO wrapper around OpenFeature SDK |
| **testkit** | In-memory provider for testing |

## Requirements

- Scala 3.3+
- ZIO 2.1+

## License

ZIO OpenFeature is distributed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).
