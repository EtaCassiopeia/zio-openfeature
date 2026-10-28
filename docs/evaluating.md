# Evaluating flags

Every method on `FeatureFlags` has a twin on its companion object that takes the service from the environment. The
examples use the companion.

## Read a value

There is a method per flag type, and `value[A]` for any type with a [`FlagType`](typed-flags.md). Each also takes an
[`EvaluationContext`](context.md) to target a user or request.

```scala
--8<-- "Evaluating.scala:typed"
```

```scala
--8<-- "Evaluating.scala:with-context"
```

## Two tiers

When a flag is missing, has the wrong type, or the provider is down, the method you called decides what happens.

![A missing flag evaluated through both tiers: the provider answers FLAG_NOT_FOUND; boolean fails with FlagNotFound, booleanOrDefault serves false with reason ERROR and error code FLAG_NOT_FOUND, and logs a warning](assets/tiers.svg)

The **typed tier** (`boolean`, `string`, `int`, `long`, `double`, `obj`, `value[A]` and every `*Details`) fails with a
`FeatureFlagError` whenever it cannot produce a real value, even when a provider *answers* with an error code instead of
throwing, which is how most providers report a missing flag. Use it for gates that must fail closed:

```scala
--8<-- "Evaluating.scala:fail-closed"
```

The **total tier** never fails and serves your default. Each method has an `*OrDefault` twin (`booleanOrDefault`,
`stringOrDefault`, …, `valueOrDefault[A]`), and `resolveOrDefault[A]` returns the full resolution, so you can still see
why:

```scala
--8<-- "Evaluating.scala:total"
```

!!! note "Both tiers run the same evaluation"
    Context, hooks, transactions and timeouts are identical, and hooks see a failed evaluation in their `error` stage
    either way. The total tier absorbs typed errors and defects; only interruption propagates. Both tiers also take a
    [`FlagDef`](typed-flags.md) in place of the key and default.

## The resolution

The `*Details` methods and `resolveOrDefault` return a `FlagResolution[A]`:

```scala
--8<-- "Evaluating.scala:details"
```

| Field | Meaning |
|---|---|
| `value` | the flag's value, or your default |
| `variant` | the variant the provider picked, if it names one |
| `reason` | a `ResolutionReason`: `Static`, `Default`, `TargetingMatch`, `Split`, `Cached`, `Disabled`, `Stale`, `Error`, `Unknown`, or `Other(value)` for a provider's own |
| `errorCode` | an `ErrorCode`, set when a default was served: `FlagNotFound`, `TypeMismatch`, `ParseError`, `TargetingKeyMissing`, `InvalidContext`, `ProviderNotReady`, `ProviderFatal`, `General` |
| `errorMessage` | the provider's or the library's explanation |
| `metadata` | the provider's `FlagMetadata` |
| `flagKey` | the key evaluated |

`isError` is true when `errorCode` is set or the reason is `Error`; `isDefault` and `isCached` test only the reason. On
the typed tier `errorCode` is always empty, since an error code fails the effect. `FlagMetadata` reads a
`MetadataValue` with `get(key)`, or a typed `Option` with `getString`, `getBoolean`, `getInt`, `getLong` or `getDouble`:

```scala
--8<-- "Evaluating.scala:metadata"
```

## Errors

| `FeatureFlagError` | When |
|---|---|
| `FlagNotFound(key)` | the provider has no such flag |
| `TypeMismatch(key, expected, actual)` | the flag has another type, or a custom `FlagType` rejected it |
| `ParseError(key, underlying)` | the provider could not parse the flag |
| `TargetingKeyMissing(key)` | the flag needs a targeting key; the context has none |
| `InvalidContext(reason)` | the provider rejected the evaluation context |
| `ProviderNotReady(status)` | the provider is not started or is shutting down |
| `ProviderFatal` | the provider failed for good, e.g. never started within `initTimeout` |
| `ProviderInitializationFailed(underlying)` | `setProvider` or a `FeatureFlagRegistry` client: the new provider failed or did not start within `initTimeout` |
| `Unauthorized(reason)` | the provider refused us, typically a wrong SDK key (401, 403) |
| `Unreachable(cause0)` | DNS failure, connection refused, no route |
| `ProviderError(underlying)` | anything else the provider threw, including a timeout |
| `InvalidConfiguration(reason)` | bad settings building an [OFREP](ofrep.md) or [Optimizely](optimizely.md) provider |
| `OverrideTypeMismatch(key, expected, actual)` | a [transaction](transactions.md) override of the wrong type |
| `NestedTransactionNotAllowed` | a transaction inside another, under the default nesting policy |

Every error has a `message`, and a `cause` when there is an underlying exception. `isFlagError` and `isProviderError`
on `FeatureFlagError` group them by flag and by provider.

`FeatureFlagError.classify` sorts what a provider throws: network failures become `Unreachable`, HTTP 401 and 403
`Unauthorized`, the rest `ProviderError`. Recover from the cases you expect and let the rest fail:

```scala
--8<-- "Evaluating.scala:recover"
```

## Options for one call

`EvaluationOptions` adds hooks, hints or a timeout to a single evaluation. Pass it after the context to any `*Details`,
`*OrDefault`, `value` or `resolveOrDefault` method, `FlagDef` forms included:

```scala
--8<-- "Evaluating.scala:options"
```

| Option | Effect |
|---|---|
| `withHook(hook)`, `withHooks(hooks)` | run these [hooks](hooks.md) for this call, as well as the instance's own |
| `withHint(key, value)` | data the hooks read from their hints |
| `withTimeout(duration)` | bound this call |
| `withoutTimeout` | no timeout, and no timer overhead |
| (neither) | the instance's timeout, **1 second** unless configured |

Set instance-wide defaults on the `FeatureFlagsConfig` (all fields are in the [config table](providers.md)):

```scala
--8<-- "Evaluating.scala:instance-config"
```

??? note "Slow or hung providers"
    A timed-out evaluation fails with `ProviderError` wrapping a `TimeoutException`. The provider's call is abandoned,
    not cancelled, and holds a blocking-pool thread until it returns. So each instance caps abandoned calls still
    running: **256** by default (`withMaxAbandonedCalls(n)`, or `fromAcquireAsync`'s `maxAbandonedCalls`). At the cap, a
    timed evaluation fails at once with `ProviderError` wrapping a `RejectedExecutionException`, without calling the
    provider; slots free as calls return. Untimed evaluations are never rejected. For a remote provider, consider the
    [circuit breaker](extras.md#circuit-breaker-provider) too.

## Logging served defaults

Each time the total tier serves a default, it logs a warning, rate-limited per flag so an outage does not flood the
log:

```
Flag 'dark-mode' fell back to its default false (FlagNotFound: Flag 'dark-mode' not found)
```

| `FallbackLogging` | Logs |
|---|---|
| `Default` (`Throttled(60.seconds)`) | one line per flag per minute, then `(suppressed N similar)` |
| `Throttled(window)` | one line per flag per `window` |
| `Always` | every fallback |
| `Off` | no line |

A defect absorbed by the total tier is a bug, so it is logged with its cause even under `Off`. Hooks and metrics see
every evaluation whatever the policy.

The spec says evaluation should not write logs; `FallbackLogging.Off` is the conformant setting. See
[Spec compliance](spec-compliance.md).
