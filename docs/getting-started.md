# Getting started

## Install

Add the core module, plus whichever others you need, to `build.sbt`. Replace `<version>` with the latest release
shown on [Maven Central](https://central.sonatype.com/artifact/io.github.etacassiopeia/zio-openfeature-core_3).

```scala
// The FeatureFlags service, on top of the OpenFeature Java SDK
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-core" % "<version>"

// Optional providers
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-extras"     % "<version>" // HOCON, env vars, caching, circuit breaker
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-ofrep"      % "<version>" // OpenFeature Remote Evaluation Protocol
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>" // Optimizely Feature Experimentation

// An in-memory provider for tests
libraryDependencies += "io.github.etacassiopeia" %% "zio-openfeature-testkit" % "<version>" % Test
```

The library is published for Scala 3.3+ and Scala 2.13, needs ZIO 2.1 and Java 11 or later. The examples on this site
use Scala 3 syntax.

??? note "Trying unreleased changes"
    Every commit on `main` publishes the next version with a `-SNAPSHOT` suffix (for example `1.1.3-SNAPSHOT` after
    `v1.1.2`). Add the snapshots resolver to use it:

    ```scala
    resolvers += "Sonatype Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
    ```

    Snapshots change without notice. Pin a released version for anything you ship.

## Your first flag

`FeatureFlags` is a ZIO service. Ask it for a flag by key, with the value to use when the flag is not set. Here the
service runs on the testkit's in-memory provider, so the program needs nothing else to run:

```scala
--8<-- "GettingStarted.scala:first-flag"
```

## Use a real provider

Your flags live in a flag service, which OpenFeature reaches through a *provider*. Any OpenFeature Java provider works:
pass it to `FeatureFlags.fromProvider`, and the program itself does not change.

```scala
--8<-- "GettingStarted.scala:real-provider"
```

For flagd, for example, add `"dev.openfeature.contrib.providers" % "flagd" % "<version>"` and pass
`new FlagdProvider()`. The [OpenFeature ecosystem](https://openfeature.dev/ecosystem) lists the providers.
[Wiring a provider](providers.md) covers the other ways to build the layer: async start-up, timeouts, named domains
and several providers at once.

## When a flag cannot be read

A flag may be missing, have the wrong type, or the provider may be down. Every evaluation method comes in two tiers, and
you pick the one that fits the call site:

```scala
--8<-- "GettingStarted.scala:two-tiers"
```

The **typed tier** fails with a `FeatureFlagError` saying what went wrong. Use it when a default would be the wrong
answer. The **total tier** never fails: it serves your default and logs why. See [Evaluating flags](evaluating.md).

## Declare a flag once

A key, a type and a default written out at every call site can drift apart. A `FlagDef` states them once:

```scala
--8<-- "GettingStarted.scala:flag-def"
```

Definitions also work with your own enums and case classes. See [Typed flags](typed-flags.md).

## Where next

| To | Read |
|---|---|
| target a flag at a user, a request or a region | [Evaluation context](context.md) |
| log, measure or validate every evaluation | [Hooks](hooks.md) |
| pin flag values for one block of code, and see what it read | [Transactions](transactions.md) |
| react to provider start-up, errors and flag changes | [Lifecycle and events](lifecycle.md) |
| read flags from HOCON or env vars, or add caching and a circuit breaker | [Extras](extras.md) |
| test code that reads flags | [Testkit](testkit.md) |
| see how it works inside | [Under the hood](architecture.md) |
