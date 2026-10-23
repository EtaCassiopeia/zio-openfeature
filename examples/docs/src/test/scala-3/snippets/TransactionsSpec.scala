package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object TransactionsSpec extends ZIOSpecDefault:

  private val provider = ZIO.service[TestFeatureProvider]

  def spec = suite("Transactions")(
    test("basic: overrides are served and counted") {
      for out <- Transactions.report
      yield assertTrue(out == "result=(true,50) flags=2 overridden=2")
    },
    test("mixed: a key without an override comes from the provider") {
      for
        tx <- Transactions.mixed
        p  <- provider
        a  <- p.evaluationCount("feature-a")
        b  <- p.evaluationCount("feature-b")
      yield assertTrue(
        tx.result == (true, true),
        a == 0,
        b == 1,
        tx.providerEvaluatedKeys == Set("feature-b"),
        tx.overriddenFlags == Set("feature-a"),
        Transactions.describe(tx).startsWith("provider=Set(feature-b) overridden=Set(feature-a)")
      )
    },
    test("with context: the provider sees the transaction context") {
      for
        _   <- Transactions.withTxContext
        ctx <- provider.flatMap(_.getEvaluations).map(_.last._2)
      yield assertTrue(ctx.targetingKey.contains("user-123"), ctx.getString("plan").contains("premium"))
    },
    test("caching: one provider call, then Cached") {
      for
        tx <- Transactions.cached
        n  <- provider.flatMap(_.evaluationCount("feature"))
      yield assertTrue(
        n == 1,
        tx.result.map(_.reason).drop(1) == List(ResolutionReason.Cached, ResolutionReason.Cached),
        tx.result.head.reason != ResolutionReason.Cached
      )
    },
    test("no caching: every read calls the provider") {
      for
        _ <- Transactions.uncached
        n <- provider.flatMap(_.evaluationCount("feature"))
      yield assertTrue(n == 2)
    },
    test("an error-coded result is recorded but not served from the cache") {
      for
        tx <- FeatureFlags.transaction()(
          FeatureFlags.booleanOrDefault("missing", false) *> FeatureFlags.booleanOrDefault("missing", false)
        )
        n <- provider.flatMap(_.evaluationCount("missing"))
      yield assertTrue(n == 2, tx.wasEvaluated("missing"))
    },
    test("type mismatch fails with OverrideTypeMismatch") {
      for res <- Transactions.mismatch.either
      yield assertTrue(res.left.exists(_.isInstanceOf[FeatureFlagError.OverrideTypeMismatch]))
    },
    test("custom type: by value and by wire value") {
      for
        a <- Transactions.byValue
        b <- Transactions.byWireValue
      yield assertTrue(a.result == Transactions.Phase.DualWrite, b.result == Transactions.Phase.DualWrite)
    },
    test("overrides are served while the provider is not ready") {
      for
        _  <- provider.flatMap(_.setStatus(ProviderStatus.NotReady))
        tx <- Transactions.run
      yield assertTrue(tx.result == (true, 50))
    },
    test("either: the caller's error is Left") {
      for
        _   <- provider.flatMap(_.removeFlag("feature"))
        res <- FeatureFlags.transactionEither()(ZIO.fail("boom")).either
        ok  <- Transactions.either.either
      yield assertTrue(res == Left(Left("boom")), ok.isLeft)
    },
    test("nested: Fail by default, Reuse joins the outer transaction") {
      for
        failed <- FeatureFlags.transaction()(FeatureFlags.transaction()(ZIO.unit)).either
        tx     <- Transactions.outer
      yield assertTrue(failed == Left(FeatureFlagError.NestedTransactionNotAllowed), tx.result)
    },
    test("state: None outside, Some inside") {
      for
        outside <- Transactions.auditFlags
        out     <- Transactions.state
        current <- FeatureFlags.currentEvaluatedFlags
      yield assertTrue(outside.isEmpty, out == (false, Some(Set("feature"))), current.isEmpty)
    },
    test("fibers: forked reads share the transaction") {
      for tx <- Transactions.parallel
      yield assertTrue(
        tx.result == List(true, true, false),
        tx.allFlagKeys == Set("feature-a", "feature-b", "feature-c"),
        tx.overrideCount == 1
      )
    },
    test("cache: a key read again as another type") {
      for
        tx <- FeatureFlags.transaction()(
          FeatureFlags.int("n", 0) *> FeatureFlags.longDetails("n", 0L) <*> FeatureFlags.string("feature", "x").either
        )
        n <- provider.flatMap(_.evaluationCount("n"))
        f <- provider.flatMap(_.evaluationCount("feature"))
        d <- FeatureFlags.transaction()(
          FeatureFlags.boolean("feature", false) *> FeatureFlags.boolean("feature", false, EvaluationContext("other"))
        )
        f2 <- provider.flatMap(_.evaluationCount("feature"))
      yield assertTrue(
        n == 1,
        tx.result._1.value == 7L,
        tx.result._1.reason == ResolutionReason.Cached,
        f == 1,
        f2 == 2,
        d.flagCount == 1
      )
    }
  ).provide(
    TestFeatureProvider.scopedLayer(
      Map("feature" -> true, "feature-b" -> true, "feature-c" -> false, "premium-feature" -> true, "n" -> 7)
    )
  )
