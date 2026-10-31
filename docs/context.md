# Evaluation context

An `EvaluationContext` tells the provider who or what a flag is being evaluated for: a **targeting key** and
**attributes** such as a plan or a country. Providers use it for targeting rules and percentage rollouts, and typically
hash the targeting key into a rollout bucket, so use a stable id (a user, account or device id) rather than something
that changes, like a session id.

You can set context at several levels, from the whole application down to one call. Before each evaluation the levels
are merged into the one context the provider sees.

![Six context levels merge into the one context the provider sees: each level adds its attributes, a higher level replaces the plan set by a lower one, and a before hook's addition lands last.](assets/context.svg)

## Creating a context

```scala
--8<-- "Context.scala:create"
```

`withAttribute` accepts plain `Boolean`, `String`, `Int`, `Long`, `Double` and `java.time.Instant` values. Also
available: `withTargetingKey`, `withAttributes(...)`, `withoutAttribute(key)`, `EvaluationContext.empty`, and
`isEmpty` / `nonEmpty`. `forEntity(entityId, entityType = "user")` sets the key and the `entityId` and `entityType`
attributes.

`targetingKey` is a reserved attribute name: the SDK stores the key as that attribute, so an explicit targeting key
wins over an attribute of that name, and a context read back in a hook never has such an attribute. Across merged
layers the more specific layer's key wins whichever form it takes: a `targetingKey` string attribute on the
invocation context beats an explicit key on the global one.

### Attribute types

Richer values use `AttributeValue`: `bool`, `string`, `int`, `long`, `double`, `instant`, `list` and `struct`.

```scala
--8<-- "Context.scala:attribute-types"
```

!!! note "Large longs"
    The Java SDK carries a `Long` as a `Long`, so a `Long` attribute of any size reaches the provider exact. A provider
    that stores numbers as doubles can still round one above 2^53: pass IDs that large as strings for such a provider.

Read attributes back with `get`, `getString`, `getInt`, `getLong`, `getDouble` and `getBoolean`; each
`AttributeValue` also has `asString`, `asInt`, `asList`, `asStruct` and so on.

```scala
--8<-- "Context.scala:read"
```

## Context levels

From lowest to highest; each level wins over the ones above it in the table:

| Level | Set with | Applies to |
|---|---|---|
| Global | `FeatureFlags.setGlobalContext(ctx)` | every evaluation of this `FeatureFlags` instance (not shared with other instances or registry clients) |
| Transaction | `FeatureFlags.transaction(context = ctx)` | evaluations inside the [transaction](transactions.md) |
| Client | `FeatureFlags.setClientContext(ctx)` | evaluations of this `FeatureFlags` instance |
| Context source | `FeatureFlagsConfig().withContextSource(source)` | every evaluation, pulled when it runs |
| Scoped | `FeatureFlags.withContext(ctx) { ... }` | the block, on this fiber and the fibers it forks |
| Invocation | the `ctx` argument of a call | that call |

```scala
--8<-- "Context.scala:levels"
```

`globalContext` and `clientContext` read the current values back. A `withContext` inside another merges with the outer
one.

### How levels merge

A higher level wins on every attribute both set, and on the targeting key if it sets one; attributes only one level
sets are kept. Structs under the same key merge field by field (`ctx.merge(other)`: `other` wins). A
[hook](hooks.md)'s `before` stage may return extra context, merged on top of everything, the invocation included.

```scala
--8<-- "Context.scala:merge"
```

## Context sources

A `ContextSource` is an effect run on every evaluation and every `track` call, to pull in context the application
already holds elsewhere: log annotations, an MDC, a tracing baggage item.

```scala
--8<-- "Context.scala:context-source"
```

- It ranks above client and global context but below an explicit `withContext` or call context. A `before` hook cannot
  do this job: its addition always lands on top.
- `current` returns a `UIO`, so a source cannot fail an evaluation; return `EvaluationContext.empty` for nothing.
- It runs on every evaluation, so keep it cheap: read a `FiberRef` or thread local, never call a remote service.
- `a ++ b` combines sources and `b` wins on a shared key; `ContextSource.empty` adds nothing.

`FeatureFlags.fromAcquireAsync` bypasses `FeatureFlagsConfig`, so it takes `contextSource` as its own parameter.
