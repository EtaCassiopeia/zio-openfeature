---
layout: default
title: Home
nav_order: 1
description: "ZIO OpenFeature - Type-safe feature flags for Scala 3"
permalink: /
---

# ZIO OpenFeature

A type-safe, ZIO-native implementation of the [OpenFeature](https://openfeature.dev/) specification for Scala 3.
{: .fs-6 .fw-300 }

[Get Started]({{ site.baseurl }}/getting-started){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/EtaCassiopeia/zio-openfeature){: .btn .fs-5 .mb-4 .mb-md-0 }

[![Maven Central](https://img.shields.io/maven-central/v/io.github.etacassiopeia/zio-openfeature-core_3.svg)](https://search.maven.org/search?q=g:io.github.etacassiopeia%20AND%20a:zio-openfeature-core_3)

---

## Features

- **Type-safe flag evaluation** with `FlagType` type class
- **Hierarchical evaluation context** (global, scoped, transaction, invocation)
- **Hook system** for cross-cutting concerns (logging, metrics, validation)
- **Transaction support** with override injection and evaluation tracking
- **Testkit module** for easy testing
- **Optimizely provider** implementation
- **OpenFeature compliant** - implements the [OpenFeature specification](https://openfeature.dev/specification/)

## Requirements

- Scala 3.3+
- ZIO 2.1+

## Documentation

| Section | Description |
|:--------|:------------|
| [Getting Started]({{ site.baseurl }}/getting-started) | Installation and basic usage |
| [Architecture]({{ site.baseurl }}/architecture) | Core concepts, design principles, and component overview |
| [Evaluation Context]({{ site.baseurl }}/context) | Hierarchical context system for targeting |
| [Hooks]({{ site.baseurl }}/hooks) | Cross-cutting concerns pipeline |
| [Transactions]({{ site.baseurl }}/transactions) | Flag overrides and evaluation tracking |
| [Testkit]({{ site.baseurl }}/testkit) | Testing utilities |
| [Providers]({{ site.baseurl }}/providers) | Optimizely and custom providers |

## Quick Example

```scala
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

object MyApp extends ZIOAppDefault:

  val program = for
    flags   <- ZIO.service[FeatureFlags]
    enabled <- flags.boolean("my-feature", false)
    _       <- ZIO.when(enabled)(ZIO.debug("Feature is enabled!"))
  yield ()

  def run = program.provide(
    FeatureFlags.live,
    TestFeatureProvider.layer(Map("my-feature" -> true))
  )
```

## Modules

| Module | Description |
|:-------|:------------|
| **core** | Core abstractions and FeatureFlags service |
| **testkit** | Testing utilities including TestFeatureProvider |
| **optimizely** | Optimizely feature flag provider |

## License

ZIO OpenFeature is distributed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).
