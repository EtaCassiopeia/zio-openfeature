# Transactions

A transaction runs a block of code with a consistent view of your flags. Inside it you can:

- **override** flags, without touching the provider;
- **cache** each flag, so every read in the block sees the same value;
- **record** every flag the block read, for an audit trail or a test assertion.

![A transaction block: an overridden flag is served without calling the provider; the first read of another flag calls the provider and is cached; the repeat read is served from the cache; every read is recorded and returned in the TransactionResult.](assets/transaction.svg)

## A first transaction

```scala
--8<-- "Transactions.scala:basic"
```

`transaction` returns a `TransactionResult` holding the block's result and what it read. Keys without an override are
evaluated by the provider as usual:

```scala
--8<-- "Transactions.scala:mixed"
```

### Parameters

| Parameter | Default | Meaning |
|---|---|---|
| `overrides` | `Map.empty` | flag key → value to serve instead of asking the provider |
| `context` | `EvaluationContext.empty` | context for every evaluation in the block |
| `cacheEvaluations` | `true` | serve repeated reads of a key from the transaction |
| `nested` | `NestedPolicy.Fail` | what to do inside another transaction; see [nesting](#nesting) |

## Overrides

An override is served as is: the provider is not asked, and the result has reason `Cached`. Overrides work even while
the provider is not ready, which makes them a way to force known values in tests or during an outage.

The override must match the type the flag is read as; otherwise the read fails with `OverrideTypeMismatch`:

```scala
--8<-- "Transactions.scala:type-mismatch"
```

A [custom flag type](typed-flags.md) can be overridden with its value or with the value the provider would store:

```scala
--8<-- "Transactions.scala:custom-type"
```

## Caching

With `cacheEvaluations = true` (the default), the first read of a key calls the provider and the next reads of that
key in the block are served from the transaction with reason `Cached`. The block then sees one value per flag even if
the flag changes meanwhile.

```scala
--8<-- "Transactions.scala:caching"
```

- The cache is keyed by flag key only. A second read with a different context gets the cached value.
- An answer that carries an error code (such as `FLAG_NOT_FOUND`) is recorded but never served from the cache; the
  next read asks the provider again.
- A key read again as another type is served from the cache when the cached value converts (an `Int` read as a
  `Long`), and from the provider otherwise.

## Transaction context

```scala
--8<-- "Transactions.scala:with-context"
```

In the [merge order](context.md#how-levels-merge) the transaction context ranks just above the global context: the
client context, a context source, `withContext` and the call's own context all win over it.

## What a transaction returns

```scala
--8<-- "Transactions.scala:result"
```

| `TransactionResult` member | Meaning |
|---|---|
| `result` | the block's result |
| `evaluatedFlags` | key → `FlagEvaluation`, for every flag read |
| `overriddenFlags` | keys served from an override |
| `allFlagKeys` | every key read |
| `providerEvaluatedKeys` | keys read from the provider |
| `flagCount`, `overrideCount` | how many of each |
| `getEvaluation(key)` | that key's `FlagEvaluation`, if read |
| `wasEvaluated(key)`, `wasOverridden(key)` | membership checks |
| `toValueMap` | key → value |
| `map(f)` | transform `result` |

A `FlagEvaluation` holds `key`, `value`, the full `resolution`, `wasOverridden` (and its opposite, `wasEvaluated`) and a
`timestamp`. A cached re-read does not add a second entry.

## Errors

On Scala 3, `transaction` fails with your block's error or a `FeatureFlagError` (a union type). `transactionEither`
keeps them apart, with your error as `Left` and the transaction's as `Right`. Use it on Scala 2.13, where
`transaction`'s error type is `Any`.

```scala
--8<-- "Transactions.scala:either"
```

## Nesting

By default, opening a transaction inside another fails with `NestedTransactionNotAllowed` before the inner block runs.
With `nested = NestedPolicy.Reuse`, the inner call joins the outer transaction instead. That makes a transaction safe
to use as middleware around code that may open its own.

```scala
--8<-- "Transactions.scala:nested"
```

!!! warning
    Under `Reuse` the inner call's `overrides`, `context` and `cacheEvaluations` are ignored, and its result describes
    the outer transaction so far.

## Is there a transaction?

```scala
--8<-- "Transactions.scala:state"
```

| Method | Outside a transaction | Inside one |
|---|---|---|
| `inTransaction` | `false` | `true` |
| `transactionEvaluations` | `None` | `Some(flags read so far)` |
| `currentEvaluatedFlags` | `Map.empty` | flags read so far |

For an audit record prefer `transactionEvaluations`: it cannot confuse "no transaction" with "no flags read".

## Fibers

A transaction belongs to the fiber that opened it and to the fibers it forks. Forked fibers use its overrides and cache,
and their reads are recorded in it. Other fibers are not affected.

```scala
--8<-- "Transactions.scala:fibers"
```
