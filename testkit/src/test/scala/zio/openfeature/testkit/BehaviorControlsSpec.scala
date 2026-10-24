package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._

object BehaviorControlsSpec extends ZIOSpecDefault {

  private val testLayer = Scope.default >>> TestFeatureProvider.layer(Map("flag" -> true, "name" -> "test"))

  def spec = suite("Behavior Controls")(
    suite("imperative API")(
      // Since #388 the typed tier fails on a provider-reported error code; the total tier serves the default and keeps
      // the code on the resolution. Each control is asserted on both.
      test("setFailing causes a typed failure and an error resolution") {
        for {
          tp         <- ZIO.service[TestFeatureProvider]
          _          <- tp.setFailing(true)
          typed      <- FeatureFlags.booleanDetails("flag", default = false).either
          resolution <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
          _          <- tp.setFailing(false)
          ok         <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(
          typed.isLeft,
          resolution.errorCode.isDefined,
          resolution.reason == ResolutionReason.Error,
          resolution.value == false,
          ok == true
        )
      },
      test("setErrorMode(FlagNotFound) fails with FlagNotFound and reports the FlagNotFound error code") {
        for {
          tp         <- ZIO.service[TestFeatureProvider]
          _          <- tp.setErrorMode(TestFeatureProvider.ErrorMode.FlagNotFound)
          typed      <- FeatureFlags.booleanDetails("flag", default = false).either
          resolution <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
          _          <- tp.clearErrorMode
        } yield assertTrue(
          typed == Left(FeatureFlagError.FlagNotFound("flag")),
          resolution.errorCode.contains(ErrorCode.FlagNotFound),
          resolution.reason == ResolutionReason.Error
        )
      },
      test("setErrorMode(ProviderNotReady) fails with ProviderNotReady") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setErrorMode(TestFeatureProvider.ErrorMode.ProviderNotReady)
          result <- FeatureFlags.boolean("flag", default = false).either
          _      <- tp.clearErrorMode
        } yield assertTrue(
          result.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady])
        )
      },
      test("setFailureProbability(1.0) causes a typed failure and an error resolution") {
        for {
          tp         <- ZIO.service[TestFeatureProvider]
          _          <- tp.setFailureProbability(1.0)
          typed      <- FeatureFlags.booleanDetails("flag", default = false).either
          resolution <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
          _          <- tp.setFailureProbability(0.0)
        } yield assertTrue(typed.isLeft, resolution.errorCode.isDefined)
      },
      test("setFailureProbability(0.0) has no effect") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFailureProbability(0.0)
          value <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(value == true)
      },
      test("clearBehavior resets all controls") {
        for {
          tp     <- ZIO.service[TestFeatureProvider]
          _      <- tp.setFailing(true)
          _      <- tp.setFailureProbability(1.0)
          _      <- tp.clearBehavior
          result <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(result == true)
      },
      test("setDelay slows evaluations") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setDelay(100.millis)
          start <- Clock.nanoTime
          _     <- FeatureFlags.boolean("flag", default = false)
          end   <- Clock.nanoTime
          _     <- tp.clearDelay
          elapsed = Duration.fromNanos(end - start)
        } yield assertTrue(elapsed.toMillis >= 80L)
      },
      // Recording means "the provider was asked", not "the provider answered" (#457, reversing #106).
      test("failed evaluations are tracked") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFailing(true)
          _     <- FeatureFlags.boolean("flag", default = false).either
          _     <- tp.setFailing(false)
          count <- tp.evaluationCount("flag")
          was   <- tp.wasEvaluated("flag")
        } yield assertTrue(count == 1, was)
      }
    ).provide(testLayer),
    suite("TestAspect API")(
      // Since #388 the typed tier fails on a provider-reported error code, so a behaviour control that makes the provider
      // answer with a code is observed as a typed failure there — and as an error-coded resolution on the total tier.
      test("withFailures causes a typed failure on the typed tier and an error resolution on the total tier") {
        for {
          typed <- FeatureFlags.booleanDetails("flag", default = false).either
          total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        } yield assertTrue(typed.isLeft, total.errorCode.isDefined, total.reason == ResolutionReason.Error)
      } @@ TestFeatureProvider.withFailures,
      test("withErrorMode causes the specific typed error and error code") {
        for {
          typed <- FeatureFlags.booleanDetails("flag", default = false).either
          total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        } yield assertTrue(
          typed == Left(FeatureFlagError.FlagNotFound("flag")),
          total.errorCode.contains(ErrorCode.FlagNotFound)
        )
      } @@ TestFeatureProvider.withErrorMode(TestFeatureProvider.ErrorMode.FlagNotFound),
      test("withFailureProbability(1.0) causes a typed failure and an error resolution") {
        for {
          typed <- FeatureFlags.booleanDetails("flag", default = false).either
          total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        } yield assertTrue(typed.isLeft, total.errorCode.isDefined)
      } @@ TestFeatureProvider.withFailureProbability(1.0),
      test("withDelay slows evaluations") {
        for {
          start <- Clock.nanoTime
          _     <- FeatureFlags.boolean("flag", default = false)
          end   <- Clock.nanoTime
          elapsed = Duration.fromNanos(end - start)
        } yield assertTrue(elapsed.toMillis >= 80L)
      } @@ TestFeatureProvider.withDelay(100.millis),
      test("behavior is cleaned up after aspect") {
        for {
          value <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(value == true)
      },
      test("an evaluation failed by withFailures is still tracked") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- FeatureFlags.boolean("flag", default = false).either
          was   <- tp.wasEvaluated("flag")
          count <- tp.evaluationCount("flag")
        } yield assertTrue(was, count == 1)
      } @@ TestFeatureProvider.withFailures,
      test("withFailureProbability(NaN) is clamped to 0.0, like setFailureProbability") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          p = tp.behaviorRef.get().failureProbability
        } yield assertTrue(p == 0.0)
      } @@ TestFeatureProvider.withFailureProbability(Double.NaN),
      test("withFailureProbability(2.0) is clamped to 1.0") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          p = tp.behaviorRef.get().failureProbability
        } yield assertTrue(p == 1.0)
      } @@ TestFeatureProvider.withFailureProbability(2.0)
    ).provide(testLayer),
    // A test-level aspect restores the config it found, so it never wipes a suite-level aspect's settings (#457).
    (suite("nested aspects")(
      test("an inner withDelay keeps the outer withErrorMode, during and after the test") {
        for {
          total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        } yield assertTrue(total.errorCode.contains(ErrorCode.General))
      } @@ TestFeatureProvider.withDelay(10.millis) @@ TestAspect.after(outerErrorModeStillGeneral),
      test("a sibling test still sees the outer withErrorMode") {
        for {
          total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        } yield assertTrue(total.errorCode.contains(ErrorCode.General))
      }
    ) @@ TestFeatureProvider.withErrorMode(TestFeatureProvider.ErrorMode.General)).provide(testLayer)
  ) @@ TestAspect.withLiveClock

  // Runs after the inner aspect has restored its state and before the outer one restores its own.
  private val outerErrorModeStillGeneral: URIO[TestFeatureProvider, Unit] =
    ZIO.serviceWithZIO[TestFeatureProvider] { tp =>
      val mode = tp.behaviorRef.get().errorMode
      ZIO
        .dieMessage(s"inner aspect clobbered the outer config: errorMode = $mode")
        .unless(mode.contains(TestFeatureProvider.ErrorMode.General))
        .unit
    }
}
