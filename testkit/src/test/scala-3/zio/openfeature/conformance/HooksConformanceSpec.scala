package zio.openfeature.conformance

import zio.*
import zio.test.*
import zio.openfeature.*
import zio.openfeature.testkit.TestFeatureProvider

/** Conformance port of the spec's `hooks.feature` (upstream main @ 203c25f93495).
  *
  * Covers hook stage ordering, the evaluation details handed to `after`/`finally`, before-hook context mutation, and
  * per-hook `HookData` propagation across stages.
  *
  * '''Documented divergence (spec §4.4.6).''' This wrapper's ZIO hooks are infallible (`UIO`) and the `error` stage
  * fires only when the evaluation fails through the typed error channel (`ProviderNotReady`/`ProviderFatal`). Provider
  * error-codes that the spec's gherkin routes to the `error` hook — `FLAG_NOT_FOUND` and `TYPE_MISMATCH` — are surfaced
  * here as a *successful* `FlagResolution` carrying the error code, so `after`/`finally` observe them while `error`
  * does not. The scenarios below assert that adapted behavior, and a dedicated test exercises the real `error` stage
  * via a provider that fails the evaluation outright.
  */
object HooksConformanceSpec extends ZIOSpecDefault:

  final private case class Recorder(
    stages: Ref[Chunk[String]],
    afterDetails: Ref[Option[FlagResolution[Any]]],
    finallyDetails: Ref[Option[FlagResolution[Any]]]
  )

  private def makeRecorder: UIO[Recorder] =
    for
      s <- Ref.make(Chunk.empty[String])
      a <- Ref.make(Option.empty[FlagResolution[Any]])
      f <- Ref.make(Option.empty[FlagResolution[Any]])
    yield Recorder(s, a, f)

  private def recordingHook(r: Recorder): FeatureHook = new FeatureHook:
    override def before(c: HookContext, h: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      r.stages.update(_ :+ "before").as(None)
    override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] =
      r.stages.update(_ :+ "after") *> r.afterDetails.set(Some(d.asInstanceOf[FlagResolution[Any]]))
    override def error(c: HookContext, e: FeatureFlagError, h: HookHints): UIO[Unit] =
      r.stages.update(_ :+ "error").unit
    override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
      r.stages.update(_ :+ "finally") *> r.finallyDetails.set(d.map(_.asInstanceOf[FlagResolution[Any]]))

  private val inMemorySuite = suite("after/finally observe evaluation details (InMemoryProvider)")(
    test("Passes evaluation details to after and finally on success") {
      for
        r        <- makeRecorder
        ff       <- ZIO.service[FeatureFlags]
        _        <- ff.clearHooks
        _        <- ff.addHook(recordingHook(r))
        _        <- ff.booleanDetails("boolean-flag", false)
        stages   <- r.stages.get
        after    <- r.afterDetails.get
        finished <- r.finallyDetails.get
      yield assertTrue(
        stages == Chunk("before", "after", "finally"),
        after.exists(d => d.value == true && d.variant.contains("on") && d.reason == ResolutionReason.Static),
        after.exists(_.errorCode.isEmpty),
        finished.exists(_.reason == ResolutionReason.Static)
      )
    },
    test("Flag not found surfaces via after/finally with FLAG_NOT_FOUND (adapted from §4.4.6)") {
      for
        r      <- makeRecorder
        ff     <- ZIO.service[FeatureFlags]
        _      <- ff.clearHooks
        _      <- ff.addHook(recordingHook(r))
        _      <- ff.stringDetails("missing-flag", "uh-oh")
        stages <- r.stages.get
        after  <- r.afterDetails.get
      yield assertTrue(
        stages == Chunk("before", "after", "finally"),
        !stages.contains("error"),
        after.exists(d => d.value == "uh-oh" && d.reason == ResolutionReason.Error),
        after.exists(_.errorCode.contains(ErrorCode.FlagNotFound))
      )
    },
    test("Type error surfaces via after/finally with TYPE_MISMATCH (adapted from §4.4.6)") {
      for
        r      <- makeRecorder
        ff     <- ZIO.service[FeatureFlags]
        _      <- ff.clearHooks
        _      <- ff.addHook(recordingHook(r))
        _      <- ff.booleanDetails("wrong-flag", false)
        stages <- r.stages.get
        after  <- r.afterDetails.get
      yield assertTrue(
        stages == Chunk("before", "after", "finally"),
        !stages.contains("error"),
        after.exists(d => d.value == false && d.reason == ResolutionReason.Error),
        after.exists(_.errorCode.contains(ErrorCode.TypeMismatch))
      )
    },
    test("before-hook context mutation reaches the provider") {
      // No invocation context: only the before-hook supplies the targeting email, so a TARGETING_MATCH proves the
      // mutated context was passed down to the provider.
      val emailHook = new FeatureHook:
        override def before(c: HookContext, h: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
          ZIO.some((EvaluationContext.builder.attribute("email", "ballmer@macrosoft.com").build, h))
      for
        ff <- ZIO.service[FeatureFlags]
        _  <- ff.clearHooks
        _  <- ff.addHook(emailHook)
        r  <- ff.booleanDetails("boolean-targeted-zero-flag", true)
      yield assertTrue(r.reason == ResolutionReason.TargetingMatch, r.value == false)
    },
    test("per-hook HookData persists from before to after (spec 4.6.1)") {
      val dataKey = TypedKey[String]("conformance.greeting")
      for
        captured <- Ref.make(Option.empty[String])
        dataHook = new FeatureHook:
          override def before(c: HookContext, h: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed(c.hookData.set(dataKey, "hello")).as(None)
          override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] =
            captured.set(c.hookData.get(dataKey))
        ff   <- ZIO.service[FeatureFlags]
        _    <- ff.clearHooks
        _    <- ff.addHook(dataHook)
        _    <- ff.booleanDetails("boolean-flag", false)
        seen <- captured.get
      yield assertTrue(seen.contains("hello"))
    }
  ).provide(ConformanceFixtures.layer) @@ TestAspect.withLiveClock @@ TestAspect.sequential

  private val errorStageSuite = suite("error/finally on a failed evaluation (typed error channel)")(
    test("provider failure fires before, error, finally — not after") {
      for
        r        <- makeRecorder
        tp       <- ZIO.service[TestFeatureProvider]
        ff       <- ZIO.service[FeatureFlags]
        _        <- ff.addHook(recordingHook(r))
        _        <- tp.setErrorMode(TestFeatureProvider.ErrorMode.ProviderNotReady)
        _        <- ff.booleanDetails("boolean-flag", false).either
        stages   <- r.stages.get
        finished <- r.finallyDetails.get
      yield assertTrue(
        stages == Chunk("before", "error", "finally"),
        !stages.contains("after"),
        finished.isEmpty
      )
    }
  ).provide(Scope.default >>> TestFeatureProvider.layer(Map("boolean-flag" -> true))) @@
    TestAspect.withLiveClock @@ TestAspect.sequential

  def spec = suite("HooksConformanceSpec")(inMemorySuite, errorStageSuite)
