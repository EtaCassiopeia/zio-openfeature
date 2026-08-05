package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

object AsyncInitSpec extends ZIOSpecDefault {

  private def asyncLayer(
    flags: Map[String, Any] = Map.empty
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.asyncLayer(flags)

  def spec = suite("Async Provider Initialization")(
    suite("Status before ready")(
      test("providerStatus starts as NotReady") {
        for {
          status <- FeatureFlags.providerStatus
        } yield assertTrue(status == ProviderStatus.NotReady)
      }.provide(asyncLayer()),
      test("evaluations fail with ProviderNotReady before provider is ready") {
        for {
          result <- FeatureFlags.boolean("flag", default = false).either
        } yield assertTrue(
          result.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady]
        )
      }.provide(asyncLayer(Map("flag" -> true))),
      test("all flag types fail with ProviderNotReady before provider is ready") {
        for {
          boolResult   <- FeatureFlags.boolean("b", default = false).either
          stringResult <- FeatureFlags.string("s", default = "x").either
          intResult    <- FeatureFlags.int("i", default = 0).either
          doubleResult <- FeatureFlags.double("d", default = 0.0).either
        } yield assertTrue(
          boolResult.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady],
          stringResult.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady],
          intResult.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady],
          doubleResult.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady]
        )
      }.provide(asyncLayer(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14))),
      test("detailed evaluations also fail with ProviderNotReady") {
        for {
          result <- FeatureFlags.booleanDetails("flag", default = false).either
        } yield assertTrue(
          result.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady]
        )
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Status after ready")(
      test("evaluations succeed after provider becomes ready") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setStatus(ProviderStatus.Ready)
          result <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(result == true)
      }.provide(asyncLayer(Map("flag" -> true))),
      test("all flag types work after provider becomes ready") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          _  <- tp.setStatus(ProviderStatus.Ready)
          b  <- FeatureFlags.boolean("b", default = false)
          s  <- FeatureFlags.string("s", default = "x")
          i  <- FeatureFlags.int("i", default = 0)
          d  <- FeatureFlags.double("d", default = 0.0)
        } yield assertTrue(b == true, s == "hello", i == 42, d == 3.14)
      }.provide(asyncLayer(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14))),
      test("providerStatus transitions to Ready after setStatus") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          before <- FeatureFlags.providerStatus
          _      <- tp.setStatus(ProviderStatus.Ready)
          after  <- FeatureFlags.providerStatus
        } yield assertTrue(before == ProviderStatus.NotReady, after == ProviderStatus.Ready)
      }.provide(asyncLayer()),
      test("detailed evaluations work after provider becomes ready") {
        for {
          tp         <- ZIO.service[TestFeatureProvider]
          _          <- tp.setStatus(ProviderStatus.Ready)
          resolution <- FeatureFlags.booleanDetails("flag", default = false)
        } yield assertTrue(
          resolution.value == true,
          resolution.flagKey == "flag",
          resolution.reason == ResolutionReason.TargetingMatch
        )
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Error state")(
      test("evaluations proceed when a ready provider transitions to Error state (library policy)") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setStatus(ProviderStatus.Ready) // provider becomes ready and serves
          _      <- tp.setStatus(ProviderStatus.Error) // then a transient error — evaluations must still proceed
          result <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(result == true)
      }.provide(asyncLayer(Map("flag" -> true))),
      test("evaluations fail with ProviderFatal when provider is in Fatal state") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setStatus(ProviderStatus.Fatal)
          result <- FeatureFlags.boolean("flag", default = false).either
        } yield assertTrue(
          result.is(_.left) == FeatureFlagError.ProviderFatal
        )
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Recovery")(
      test("evaluations keep serving through an Error blip and remain healthy on recovery to Ready") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setStatus(ProviderStatus.Ready)
          _      <- tp.setStatus(ProviderStatus.Error) // transient blip — evaluations still proceed (library policy)
          during <- FeatureFlags.boolean("flag", default = false)
          _      <- tp.setStatus(ProviderStatus.Ready) // recovered
          ok     <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(during == true, ok == true)
      }.provide(asyncLayer(Map("flag" -> true))),
      test("evaluations recover after provider transitions from NotReady to Ready") {
        for {
          tp  <- ZIO.service[TestFeatureProvider]
          err <- FeatureFlags.boolean("flag", default = false).either
          _   <- tp.setStatus(ProviderStatus.Ready)
          ok  <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(
          err.is(_.left).isInstanceOf[FeatureFlagError.ProviderNotReady],
          ok == true
        )
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Hooks with async initialization")(
      test("hooks are applied after provider becomes ready") {
        for {
          hookCalled <- Ref.make(false)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              hookCalled.set(true).as(None)
          }
          tp     <- ZIO.service[TestFeatureProvider]
          ff     <- ZIO.service[FeatureFlags]
          _      <- ff.addHook(hook)
          _      <- tp.setStatus(ProviderStatus.Ready)
          _      <- FeatureFlags.boolean("flag", default = false)
          called <- hookCalled.get
        } yield assertTrue(called)
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Context with async initialization")(
      test("evaluation context works after provider becomes ready") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          _  <- tp.setStatus(ProviderStatus.Ready)
          ctx = EvaluationContext("user-1").withAttribute("plan", "premium")
          result <- FeatureFlags.boolean("flag", default = false, ctx)
        } yield assertTrue(result == true)
      }.provide(asyncLayer(Map("flag" -> true)))
    ),
    suite("Stale state")(
      test("evaluations succeed when provider is in Stale state") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setStatus(ProviderStatus.Ready)
          _      <- tp.setStatus(ProviderStatus.Stale)
          result <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(result == true)
      }.provide(asyncLayer(Map("flag" -> true)))
    )
  ) @@ TestAspect.withLiveClock
}
