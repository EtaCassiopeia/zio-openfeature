package snippets

import zio.*
import zio.openfeature.*

/** Code shown on the Transactions page. */
object Transactions:

  // --8<-- [start:basic]
  val run: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[(Boolean, Int)]] =
    FeatureFlags.transaction(overrides = Map("feature-a" -> true, "max-items" -> 50)) {
      for
        a <- FeatureFlags.boolean("feature-a", default = false) // true: overridden
        n <- FeatureFlags.int("max-items", default = 10)        // 50: overridden
      yield (a, n)
    }

  val report: ZIO[FeatureFlags, FeatureFlagError, String] =
    run.map { tx =>
      s"result=${tx.result} flags=${tx.flagCount} overridden=${tx.overrideCount}"
      // result=(true,50) flags=2 overridden=2
    }
  // --8<-- [end:basic]

  // --8<-- [start:mixed]
  // Keys without an override go to the provider as usual
  val mixed: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[(Boolean, Boolean)]] =
    FeatureFlags.transaction(Map("feature-a" -> true)) {
      FeatureFlags.boolean("feature-a", false) <*> FeatureFlags.boolean("feature-b", false)
    }
  // --8<-- [end:mixed]

  // --8<-- [start:with-context]
  // Context for every evaluation in the block. It ranks just above the global context, so the client context,
  // `withContext` and the call's own context all win over it.
  val withTxContext: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Boolean]] =
    FeatureFlags.transaction(context = EvaluationContext("user-123").withAttribute("plan", "premium")) {
      FeatureFlags.boolean("premium-feature", false)
    }
  // --8<-- [end:with-context]

  // --8<-- [start:caching]
  // The first read goes to the provider; the next two are served from the transaction (reason = Cached)
  val cached: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[List[FlagResolution[Boolean]]]] =
    FeatureFlags.transaction() {
      ZIO.foreach(List(1, 2, 3))(_ => FeatureFlags.booleanDetails("feature", false))
    }

  // Every read goes to the provider, so a change between reads is visible
  val uncached: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[(Boolean, Boolean)]] =
    FeatureFlags.transaction(cacheEvaluations = false) {
      FeatureFlags.boolean("feature", false) <*> FeatureFlags.boolean("feature", false)
    }
  // --8<-- [end:caching]

  // --8<-- [start:type-mismatch]
  // "count" is read as an Int, but the override is a String: the read fails with OverrideTypeMismatch
  val mismatch: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Int]] =
    FeatureFlags.transaction(Map("count" -> "not-a-number")) {
      FeatureFlags.int("count", 0)
    }
  // --8<-- [end:type-mismatch]

  // --8<-- [start:custom-type]
  enum Phase derives FlagType:
    case Off, DualWrite, ShardOnly

  // A custom type can be overridden by its value, or by what the provider would store; both give Phase.DualWrite
  val byValue: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Phase]] =
    FeatureFlags.transaction(Map("rollout-phase" -> Phase.DualWrite)) {
      FeatureFlags.value[Phase]("rollout-phase", Phase.Off)
    }

  val byWireValue: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Phase]] =
    FeatureFlags.transaction(Map("rollout-phase" -> "DualWrite")) {
      FeatureFlags.value[Phase]("rollout-phase", Phase.Off)
    }
  // --8<-- [end:custom-type]

  // --8<-- [start:result]
  def describe(tx: TransactionResult[?]): String =
    val providerKeys = tx.providerEvaluatedKeys // read from the provider
    val overridden   = tx.overriddenFlags       // served from an override
    val values       = tx.toValueMap            // key -> value, for every flag read
    val details      = tx.getEvaluation("feature-b").map(e => s"${e.value} at ${e.timestamp}")
    s"provider=$providerKeys overridden=$overridden values=$values feature-b=${details.getOrElse("not read")}"
  // --8<-- [end:result]

  // --8<-- [start:either]
  // transactionEither keeps your error (Left) apart from the transaction's own (Right), on Scala 2.13 too
  val either: ZIO[FeatureFlags, Either[String, FeatureFlagError], TransactionResult[Boolean]] =
    FeatureFlags.transactionEither() {
      FeatureFlags.boolean("feature", false).orElseFail("could not read the flag")
    }
  // --8<-- [end:either]

  // --8<-- [start:nested]
  val inner: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Boolean]] =
    FeatureFlags.transaction(nested = NestedPolicy.Reuse) {
      FeatureFlags.boolean("feature-a", false)
    }

  // With the default, NestedPolicy.Fail, the inner call would fail with NestedTransactionNotAllowed.
  // With Reuse it joins the outer transaction: it sees the outer overrides and its own arguments are ignored.
  val outer: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[Boolean]] =
    FeatureFlags.transaction(Map("feature-a" -> true)) {
      inner.map(_.result) // true, from the outer override
    }
  // --8<-- [end:nested]

  // --8<-- [start:state]
  // For an audit record: None outside a transaction, Some(flags) inside one (possibly empty)
  val auditFlags: ZIO[FeatureFlags, Nothing, Option[Set[String]]] =
    FeatureFlags.transactionEvaluations.map(_.map(_.keySet))

  val state: ZIO[FeatureFlags, FeatureFlagError, (Boolean, Option[Set[String]])] =
    for
      inside <- FeatureFlags.inTransaction // false here
      tx     <- FeatureFlags.transaction() {
        FeatureFlags.boolean("feature", false) *> auditFlags // Some(Set("feature"))
      }
    yield (inside, tx.result)
  // --8<-- [end:state]

  // --8<-- [start:fibers]
  // Fibers forked inside the block share the transaction: their reads use its overrides and are recorded in it
  val parallel: ZIO[FeatureFlags, FeatureFlagError, TransactionResult[List[Boolean]]] =
    FeatureFlags.transaction(Map("feature-a" -> true)) {
      ZIO.foreachPar(List("feature-a", "feature-b", "feature-c"))(key => FeatureFlags.boolean(key, false))
    }
  // --8<-- [end:fibers]
