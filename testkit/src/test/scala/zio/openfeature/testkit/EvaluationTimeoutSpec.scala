package zio.openfeature.testkit

import dev.openfeature.sdk.OpenFeatureAPIFactory
import zio._
import zio.test._
import zio.openfeature._

object EvaluationTimeoutSpec extends ZIOSpecDefault {

  private val flags = Map("flag" -> true)

  private def layerWithTimeout(
    delay: Duration,
    evaluationTimeout: Option[Duration] = None
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> ZLayer
      .scoped {
        for {
          tp <- TestFeatureProvider.make(flags)
          _  <- tp.setDelay(delay)
          ff <- FeatureFlags.build(
            tp,
            domain = Some(s"test-timeout-${java.util.UUID.randomUUID()}"),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(OpenFeatureAPIFactory.create()),
            evaluationTimeout = evaluationTimeout
          )
        } yield (tp, ff)
      }
      .flatMap { env =>
        val (tp, ff) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(tp) ++ ZLayer.succeed(ff)
      }

  def spec = suite("Evaluation Timeout")(
    test("global timeout causes slow evaluation to fail with ProviderError") {
      for {
        result <- FeatureFlags.boolean("flag", default = false).either
      } yield assertTrue(result.isLeft) && {
        val error = result.left.toOption.get
        assertTrue(error.isInstanceOf[FeatureFlagError.ProviderError])
      }
    }.provide(layerWithTimeout(delay = 2.seconds, evaluationTimeout = Some(50.millis))),
    test("evaluation within timeout succeeds") {
      for {
        result <- FeatureFlags.boolean("flag", default = false)
      } yield assertTrue(result == true)
    }.provide(layerWithTimeout(delay = 5.millis, evaluationTimeout = Some(2.seconds))),
    test("no timeout by default — preserves backward compatibility") {
      for {
        result <- FeatureFlags.boolean("flag", default = false)
      } yield assertTrue(result == true)
    }.provide(layerWithTimeout(delay = 50.millis)),
    test("per-call timeout overrides global timeout") {
      for {
        result <- FeatureFlags
          .booleanDetails(
            "flag",
            default = false,
            ctx = EvaluationContext.empty,
            options = EvaluationOptions.empty.withTimeout(50.millis)
          )
          .either
      } yield assertTrue(result.isLeft)
    }.provide(layerWithTimeout(delay = 2.seconds, evaluationTimeout = Some(10.seconds))),
    test("per-call timeout applies when no global timeout is set") {
      for {
        result <- FeatureFlags
          .booleanDetails(
            "flag",
            default = false,
            ctx = EvaluationContext.empty,
            options = EvaluationOptions.empty.withTimeout(50.millis)
          )
          .either
      } yield assertTrue(result.isLeft)
    }.provide(layerWithTimeout(delay = 2.seconds))
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
