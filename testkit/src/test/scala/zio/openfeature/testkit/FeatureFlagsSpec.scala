package zio.openfeature.testkit

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.openfeature.*

object FeatureFlagsSpec extends ZIOSpecDefault:

  private def testLayer(
    flags: Map[String, Any] = Map.empty
  ): ZLayer[Any, Throwable, TestFeatureProvider & FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(flags)

  def spec = suite("FeatureFlagsSpec")(
    suite("Simple Evaluation")(
      test("boolean returns flag value") {
        for result <- FeatureFlags.boolean("dark-mode", default = false)
        yield assertTrue(result == true)
      }.provide(testLayer(Map("dark-mode" -> true))),
      test("boolean returns default when flag not found") {
        for result <- FeatureFlags.boolean("missing-flag", default = false)
        yield assertTrue(result == false)
      }.provide(testLayer()),
      test("string returns flag value") {
        for result <- FeatureFlags.string("welcome-message", default = "Hello")
        yield assertTrue(result == "Welcome!")
      }.provide(testLayer(Map("welcome-message" -> "Welcome!"))),
      test("int returns flag value") {
        for result <- FeatureFlags.int("max-items", default = 10)
        yield assertTrue(result == 50)
      }.provide(testLayer(Map("max-items" -> 50))),
      test("double returns flag value") {
        for result <- FeatureFlags.double("rate-limit", default = 1.0)
        yield assertTrue(result == 2.5)
      }.provide(testLayer(Map("rate-limit" -> 2.5))),
      test("long returns flag value via int conversion") {
        for result <- FeatureFlags.long("max-bytes", default = 1000L)
        yield assertTrue(result == 999999L)
      }.provide(testLayer(Map("max-bytes" -> 999999)))
    ),
    suite("Detailed Evaluation")(
      test("booleanDetails returns FlagResolution") {
        for resolution <- FeatureFlags.booleanDetails("feature-x", default = false)
        yield assertTrue(resolution.value == true) &&
          assertTrue(resolution.flagKey == "feature-x") &&
          assertTrue(resolution.reason == ResolutionReason.TargetingMatch)
      }.provide(testLayer(Map("feature-x" -> true))),
      test("stringDetails returns default reason when not found") {
        for resolution <- FeatureFlags.stringDetails("missing", default = "default-value")
        yield assertTrue(resolution.value == "default-value") &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      }.provide(testLayer()),
      test("intDetails includes variant") {
        for resolution <- FeatureFlags.intDetails("variant-flag", default = 0)
        yield assertTrue(resolution.value == 42)
      }.provide(testLayer(Map("variant-flag" -> 42)))
    ),
    suite("Context Management")(
      test("setGlobalContext and globalContext work") {
        val ctx = EvaluationContext("user-123")
        for
          _      <- FeatureFlags.setGlobalContext(ctx)
          result <- FeatureFlags.globalContext
        yield assertTrue(result.targetingKey.contains("user-123"))
      }.provide(testLayer()),
      test("withContext scopes context to block") {
        val globalCtx = EvaluationContext("global-user")
        val localCtx  = EvaluationContext("local-user")
        for
          _         <- FeatureFlags.setGlobalContext(globalCtx)
          globalKey <- FeatureFlags.globalContext.map(_.targetingKey)
          localResult <- FeatureFlags.withContext(localCtx) {
            FeatureFlags.globalContext
          }
        yield assertTrue(globalKey.contains("global-user")) &&
          assertTrue(localResult.targetingKey.contains("global-user"))
      }.provide(testLayer())
    ),
    suite("Transaction")(
      test("transaction returns result and evaluations") {
        for txResult <- FeatureFlags.transaction() {
            for
              a <- FeatureFlags.boolean("flag-a", default = false)
              b <- FeatureFlags.int("flag-b", default = 0)
            yield (a, b)
          }
        yield assertTrue(txResult.result == (true, 42)) &&
          assertTrue(txResult.flagCount == 2) &&
          assertTrue(txResult.allFlagKeys == Set("flag-a", "flag-b"))
      }.provide(testLayer(Map("flag-a" -> true, "flag-b" -> 42))),
      test("transaction with overrides returns override values") {
        for txResult <- FeatureFlags.transaction(
            overrides = Map("flag-a" -> false, "flag-b" -> 100)
          ) {
            for
              a <- FeatureFlags.boolean("flag-a", default = true)
              b <- FeatureFlags.int("flag-b", default = 0)
            yield (a, b)
          }
        yield assertTrue(txResult.result == (false, 100)) &&
          assertTrue(txResult.overrideCount == 2) &&
          assertTrue(txResult.wasOverridden("flag-a")) &&
          assertTrue(txResult.wasOverridden("flag-b"))
      }.provide(testLayer(Map("flag-a" -> true, "flag-b" -> 42))),
      test("transaction with context applies context") {
        val txCtx = EvaluationContext("tx-user")
        for txResult <- FeatureFlags.transaction(context = txCtx) {
            FeatureFlags.boolean("flag", default = false)
          }
        yield assertTrue(txResult.result == true)
      }.provide(testLayer(Map("flag" -> true))),
      test("nested transaction fails") {
        val effect = FeatureFlags.transaction() {
          FeatureFlags.transaction() {
            ZIO.unit
          }
        }
        for result <- effect.exit
        yield assertTrue(result.isFailure)
      }.provide(testLayer()),
      test("inTransaction returns true inside transaction") {
        for
          outside <- FeatureFlags.inTransaction
          inside <- FeatureFlags.transaction() {
            FeatureFlags.inTransaction
          }
        yield assertTrue(!outside) &&
          assertTrue(inside.result == true)
      }.provide(testLayer()),
      test("currentEvaluatedFlags returns flags inside transaction") {
        for
          outsideFlags <- FeatureFlags.currentEvaluatedFlags
          insideResult <- FeatureFlags.transaction() {
            for
              _     <- FeatureFlags.boolean("flag-x", default = false)
              flags <- FeatureFlags.currentEvaluatedFlags
            yield flags
          }
        yield assertTrue(outsideFlags.isEmpty) &&
          assertTrue(insideResult.result.contains("flag-x"))
      }.provide(testLayer(Map("flag-x" -> true))),
      test("transaction caches evaluated flags for subsequent calls") {
        // This test verifies that within a transaction, a flag is only evaluated once
        // and subsequent evaluations return the cached value
        for txResult <- FeatureFlags.transaction() {
            for
              first  <- FeatureFlags.boolean("cached-flag", default = false)
              second <- FeatureFlags.boolean("cached-flag", default = false)
              third  <- FeatureFlags.boolean("cached-flag", default = false)
            yield (first, second, third)
          }
        yield
        // All three evaluations should return the same value
        assertTrue(txResult.result == (true, true, true)) &&
          // Only one evaluation should be recorded (the first one, subsequent ones are cached)
          assertTrue(txResult.flagCount == 1) &&
          // The flag should not be marked as overridden (it was evaluated from provider, then cached)
          assertTrue(!txResult.wasOverridden("cached-flag"))
      }.provide(testLayer(Map("cached-flag" -> true))),
      test("transaction caching returns cached reason for subsequent evaluations") {
        for txResult <- FeatureFlags.transaction() {
            for
              first  <- FeatureFlags.booleanDetails("detail-flag", default = false)
              second <- FeatureFlags.booleanDetails("detail-flag", default = false)
            yield (first, second)
          }
        yield
          val (firstRes, secondRes) = txResult.result
          // First evaluation should be from provider (TargetingMatch)
          assertTrue(firstRes.reason == ResolutionReason.TargetingMatch) &&
          // Second evaluation should be cached
          assertTrue(secondRes.reason == ResolutionReason.Cached) &&
          // Both should have the same value
          assertTrue(firstRes.value == secondRes.value)
      }.provide(testLayer(Map("detail-flag" -> true))),
      test("transaction with cacheEvaluations=false does not cache") {
        for txResult <- FeatureFlags.transaction(cacheEvaluations = false) {
            for
              first  <- FeatureFlags.booleanDetails("no-cache-flag", default = false)
              second <- FeatureFlags.booleanDetails("no-cache-flag", default = false)
            yield (first, second)
          }
        yield
          val (firstRes, secondRes) = txResult.result
          // Both evaluations should be from provider (TargetingMatch), not cached
          assertTrue(firstRes.reason == ResolutionReason.TargetingMatch) &&
          assertTrue(secondRes.reason == ResolutionReason.TargetingMatch) &&
          // Both should have the same value
          assertTrue(firstRes.value == secondRes.value) &&
          // With caching disabled, each evaluation is recorded separately
          // but since they have the same key, the map will have only 1 entry (last one wins)
          assertTrue(txResult.flagCount == 1)
      }.provide(testLayer(Map("no-cache-flag" -> true)))
    ),
    suite("Hooks")(
      test("addHook and hooks work") {
        val hook = FeatureHook.noop
        for
          initial <- FeatureFlags.hooks
          _       <- FeatureFlags.addHook(hook)
          after   <- FeatureFlags.hooks
        yield assertTrue(initial.isEmpty) &&
          assertTrue(after.length == 1)
      }.provide(testLayer()),
      test("clearHooks removes all hooks") {
        val hook = FeatureHook.noop
        for
          _      <- FeatureFlags.addHook(hook)
          _      <- FeatureFlags.addHook(hook)
          before <- FeatureFlags.hooks
          _      <- FeatureFlags.clearHooks
          after  <- FeatureFlags.hooks
        yield assertTrue(before.length == 2) &&
          assertTrue(after.isEmpty)
      }.provide(testLayer()),
      test("hooks are called during evaluation") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        val trackingHook = new FeatureHook:
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ s"before:${ctx.flagKey}").as(None)

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ s"after:${ctx.flagKey}")

        for
          _     <- FeatureFlags.addHook(trackingHook)
          _     <- FeatureFlags.boolean("test-flag", default = false)
          calls <- callsRef.get
        yield assertTrue(calls == List("before:test-flag", "after:test-flag"))
      }.provide(testLayer(Map("test-flag" -> true)))
    ),
    suite("Provider Status")(
      test("providerStatus returns current status") {
        for status <- FeatureFlags.providerStatus
        yield assertTrue(status == ProviderStatus.Ready)
      }.provide(testLayer()),
      test("providerMetadata returns provider info") {
        for metadata <- FeatureFlags.providerMetadata
        yield assertTrue(metadata.name == "TestFeatureProvider")
      }.provide(testLayer())
    ),
    suite("Evaluation with Context")(
      test("boolean with context passes context to provider") {
        val ctx = EvaluationContext("ctx-user")
        for result <- FeatureFlags.boolean("flag", default = false, ctx)
        yield assertTrue(result == true)
      }.provide(testLayer(Map("flag" -> true))),
      test("string with context works") {
        val ctx = EvaluationContext.empty
        for result <- FeatureFlags.string("msg", default = "default", ctx)
        yield assertTrue(result == "hello")
      }.provide(testLayer(Map("msg" -> "hello"))),
      test("int with context works") {
        val ctx = EvaluationContext.empty
        for result <- FeatureFlags.int("num", default = 0, ctx)
        yield assertTrue(result == 123)
      }.provide(testLayer(Map("num" -> 123))),
      test("double with context works") {
        val ctx = EvaluationContext.empty
        for result <- FeatureFlags.double("rate", default = 0.0, ctx)
        yield assertTrue(result == 3.14)
      }.provide(testLayer(Map("rate" -> 3.14)))
    ),
    suite("Generic Value Evaluation")(
      test("value with FlagType works") {
        for result <- FeatureFlags.value[Boolean]("bool-flag", default = false)
        yield assertTrue(result == true)
      }.provide(testLayer(Map("bool-flag" -> true))),
      test("valueDetails with FlagType works") {
        for resolution <- FeatureFlags.valueDetails[Int]("int-flag", default = 0)
        yield assertTrue(resolution.value == 99) &&
          assertTrue(resolution.flagKey == "int-flag")
      }.provide(testLayer(Map("int-flag" -> 99))),
      test("value with context works") {
        val ctx = EvaluationContext.empty
        for result <- FeatureFlags.value[String]("str-flag", default = "none", ctx)
        yield assertTrue(result == "found")
      }.provide(testLayer(Map("str-flag" -> "found")))
    )
  )
