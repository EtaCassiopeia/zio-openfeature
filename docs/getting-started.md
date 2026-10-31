# Getting started

## Install

Replace `<version>` with the latest release on
[Maven Central](https://central.sonatype.com/artifact/io.github.etacassiopeia/zio-openfeature-core_3).

```scala
libraryDependencies ++= Seq(
  "io.github.etacassiopeia" %% "zio-openfeature-core"       % "<version>",
  "io.github.etacassiopeia" %% "zio-openfeature-extras"     % "<version>", // HOCON, env vars, caching, circuit breaker
  "io.github.etacassiopeia" %% "zio-openfeature-ofrep"      % "<version>", // OpenFeature Remote Evaluation Protocol
  "io.github.etacassiopeia" %% "zio-openfeature-optimizely" % "<version>", // Optimizely Feature Experimentation
  "io.github.etacassiopeia" %% "zio-openfeature-testkit"    % "<version>" % Test // in-memory provider for tests
)
```

Only `core` is required. It is published for Scala 3.3.8+ and 2.13.18+, and needs ZIO 2.1.27+ and Java 11+. The
examples on this site use Scala 3 syntax.

??? note "Trying unreleased changes"
    Every commit on `main` publishes the next version with a `-SNAPSHOT` suffix (for example `1.2.2-SNAPSHOT` after
    `v1.2.1`). Add the snapshots resolver to use it. Snapshots change without notice; pin a release for anything you
    ship.

    ```scala
    resolvers += "Sonatype Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
    ```

## Your first flag

`FeatureFlags` is a ZIO service. Ask it for a flag by key, with the default to serve when the flag cannot be read.
`booleanOrDefault` never fails; `boolean`, shown below, fails instead. Here it runs on the testkit's in-memory
provider, so nothing else is needed:

```scala
--8<-- "GettingStarted.scala:first-flag"
```

## Use a real provider

Any OpenFeature Java provider works: pass it to `FeatureFlags.fromProvider`, and the program does not change. For
flagd, add `"dev.openfeature.contrib.providers" % "flagd" % "<version>"` and pass `new FlagdProvider()`; the
[OpenFeature ecosystem](https://openfeature.dev/ecosystem) lists the rest.

```scala
--8<-- "GettingStarted.scala:real-provider"
```

[Wiring a provider](providers.md) covers async start-up, timeouts, named domains and several providers at once.

## Two ways to read a flag

The typed tier fails when the flag cannot be read; the total tier serves your default. On the typed tier the default
is still sent to the provider and to hooks, but it is not served. See [Evaluating flags](evaluating.md#two-tiers).

```scala
--8<-- "GettingStarted.scala:two-tiers"
```

## Declare a flag once

A `FlagDef` states a flag's key, type and default in one place, so call sites cannot disagree. See
[Typed flags](typed-flags.md).

```scala
--8<-- "GettingStarted.scala:flag-def"
```

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
