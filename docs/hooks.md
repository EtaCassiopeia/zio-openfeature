# Hooks

A hook runs your code around every flag evaluation: to log it, time it, audit it, or add context to it. Hooks are
plain ZIO values, written once and registered where they should apply.

![One evaluation through three hooks: before runs api, client, invocation in order; the provider is called; after and finallyAfter run in reverse order. On the error path, the error stage runs instead of after.](assets/hooks.svg)

## Writing a hook

Extend `FeatureHook` and override the stages you need. Every stage has a no-op default.

```scala
--8<-- "Hooks.scala:custom"
```

| Stage | Runs | Receives |
|---|---|---|
| `before` | before the provider is called | the context so far; may return extra context |
| `after` | when the provider answered with a value and no error code | the `FlagResolution` |
| `error` | when the evaluation failed or the provider answered with an error code | the `FeatureFlagError` |
| `finallyAfter` | always, last | `Some(resolution)` if the provider answered, otherwise `None` |

Every stage also receives the `HookContext` (below) and the evaluation's `HookHints`.

## Registering hooks

```scala
--8<-- "Hooks.scala:register"
```

| Level | Register with | Applies to |
|---|---|---|
| ZIO API | `addZioApiHook`, `addZioApiHooks` | every evaluation of this instance, ahead of client hooks; a [`FeatureFlagRegistry`](providers.md) copies them to all its clients |
| Client | `addHook`, `addHooks`, `FeatureFlagsConfig().withHook(...)` / `withHooks(...)` | every evaluation of this instance |
| Invocation | `EvaluationOptions(hooks*)`, `.withHook`, `.withHooks` | one call, through its `*Details` method |
| Java API | `addApiHook(javaHook)` | a `dev.openfeature.sdk.Hook`, run by the Java SDK |
| Provider | the provider itself | run by the Java SDK |

`FeatureFlags.hooks` and `zioApiHooks` list what is registered; `clearHooks`, `clearZioApiHooks` and `clearApiHooks`
remove it. The Java API is shared by every instance on the same `OpenFeatureAPI`, so `clearApiHooks` from a shared
instance also clears its siblings' API hooks, and logs a warning when it does.

```scala
--8<-- "Hooks.scala:manage"
```

### Hooks written for the Java SDK

Hooks from the Java ecosystem can be reused. They run inside the SDK call, together with the provider's own hooks, so
from the ZIO hooks' point of view they are part of "calling the provider".

```scala
--8<-- "Hooks.scala:java-hook"
```

## Order

- `before` runs ZIO API hooks, then client hooks, then invocation hooks, each in the order they were added.
- The provider is called. Java API hooks and provider hooks run here, inside the SDK.
- `after`, `error` and `finallyAfter` run in the **reverse** order: invocation, client, ZIO API.

So the first hook to see an evaluation is the last to see its outcome, like nested `try` / `finally` blocks.

## When each stage runs

- A clean answer runs `after`, then `finallyAfter`.
- An answer that carries an error code (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`, ...) runs `error`, not `after`. This holds
  for both the typed and the total [tier](evaluating.md): `booleanOrDefault` still serves your default, and the hooks
  still see the error.
- A failure without an answer (provider not ready, timeout, exception) runs `error`, and `finallyAfter` gets `None`.
- Hooks run before the provider status is checked, so an evaluation that fails because the provider is not ready still
  runs `before`, `error` and `finallyAfter`.
- Hooks also run for values served by a [transaction](transactions.md) override or its cache.

Stages return `UIO`: a hook cannot fail an evaluation, and a misbehaving logger never takes your flags down. Handle
anything that may fail inside the hook:

```scala
--8<-- "Hooks.scala:safe"
```

A defect (a thrown exception) is different. A defect in `before` or `after` skips the rest of that stage, runs `error`
with a `ProviderError`, runs `finallyAfter`, and then fails the evaluation with that defect. After an `after` defect,
`finallyAfter` receives your default with reason `Error` and code `General`.

## Adding context in `before`

`before` may return extra context. It is merged on top of the evaluation's context, so it wins over every
[context level](context.md). Each later hook sees the context as changed by the earlier ones.

```scala
--8<-- "Hooks.scala:enrich"
```

To add context from somewhere the application already holds it, without letting it override what callers pass
explicitly, use a [context source](context.md#context-sources) instead.

## Passing data between stages

`HookData` is a small store private to one hook during one evaluation. Write in `before`, read in a later stage. Use a
`TypedKey` to keep the value's type. This holds inside `FeatureHook.compose` too: each hook in a composite gets its own
`HookData` per evaluation, even when the composite is registered once and shared, or lists the same hook twice.

```scala
--8<-- "Hooks.scala:hook-data"
```

`HookHints` are read-only values the caller hands to every hook of one evaluation, through `EvaluationOptions`:

```scala
--8<-- "Hooks.scala:hints"
```

Pass them with `EvaluationOptions(...).withHint("requestId", "r-17")`, as in the registration example above.

## Filtering by flag type

A hook runs only for evaluations whose type is in its `supportedFlagTypes` (all types by default). The type is the
one the provider is asked for, so a string-backed enum counts as `String`.

```scala
--8<-- "Hooks.scala:flag-types"
```

`FlagValueType` has `Boolean`, `String`, `Int`, `Long`, `Double` and `Object`.

## Built-in hooks

```scala
--8<-- "Hooks.scala:builtins"
```

| Hook | What it does |
|---|---|
| `FeatureHook.logging(logBefore, logAfter, logError)` | plain log lines: `before` at debug (off by default), `after` at info, `error` at error |
| `FeatureHook.structuredLogging(beforeLevel, afterLevel, errorLevel, logContext, redactKeys)` | log lines with annotations: `flag.key`, `flag.type`, `flag.provider`, `flag.domain`, `flag.value`, `flag.reason`, `flag.variant`, `flag.duration_ms`, `flag.error`, and with `logContext` the context, minus `redactKeys` |
| `FeatureHook.metrics((key, duration, success) => ...)` | calls you once per evaluation with its duration |
| `FeatureHook.metricsDetailed(onSuccess, onError)` | the same, with the full `HookContext` and resolution or error, to build metric tags |
| `FeatureHook.contextValidator(requireTargetingKey, requiredAttributes)` | logs a warning when the targeting key or an attribute is missing; never fails the evaluation |
| `FeatureHook.noop` | does nothing |
| `FeatureHook.compose(hooks)` | one hook that runs several, in the order above |

## `HookContext`

| Field | Meaning |
|---|---|
| `flagKey` | the flag being evaluated |
| `flagType` | the `FlagValueType` the provider is asked for |
| `defaultValue` | the caller's default, as the caller's type (a custom type, not its wire value) |
| `evaluationContext` | the merged context, including earlier `before` additions |
| `clientMetadata` | the client's `domain` and `version` |
| `providerMetadata` | the provider's `name` and `version` |
| `hookData` | this hook's `HookData` for this evaluation |

## Keep hooks fast

Hooks run on the calling fiber, in sequence, on every evaluation. Keep them in memory; fork slow work (such as sending
analytics) with `.forkDaemon`. An evaluation with no applicable hooks skips the hook machinery entirely.
