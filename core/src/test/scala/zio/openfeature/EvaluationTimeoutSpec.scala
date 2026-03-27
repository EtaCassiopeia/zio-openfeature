package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._

object EvaluationTimeoutSpec extends ZIOSpecDefault {

  /** A provider that delays all evaluations by a fixed duration. */
  private class SlowProvider(flags: Map[String, Any], delay: Duration) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = "SlowProvider"
    }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def eval[A](key: String, default: A): A = {
      Thread.sleep(delay.toMillis)
      flags.getOrElse(key, default).asInstanceOf[A]
    }

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] =
      ProviderEvaluation.builder[java.lang.Boolean]().value(eval(key, defaultValue)).reason("STATIC").build()

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      ProviderEvaluation.builder[String]().value(eval(key, defaultValue)).reason("STATIC").build()

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] =
      ProviderEvaluation.builder[java.lang.Integer]().value(eval(key, defaultValue)).reason("STATIC").build()

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] =
      ProviderEvaluation.builder[java.lang.Double]().value(eval(key, defaultValue)).reason("STATIC").build()

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] =
      ProviderEvaluation.builder[Value]().value(eval(key, defaultValue)).reason("STATIC").build()
  }

  def spec = suite("Evaluation Timeout")(
    test("global timeout causes slow evaluation to fail with ProviderError") {
      ZIO.scoped {
        val provider = new SlowProvider(Map("flag" -> true), delay = 2.seconds)
        FeatureFlags.fromProvider(provider, evaluationTimeout = 50.millis).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          ff.boolean("flag", default = false).either.map { result =>
            assertTrue(result.isLeft) && {
              val error = result.left.toOption.get
              assertTrue(error.isInstanceOf[FeatureFlagError.ProviderError])
            }
          }
        }
      }
    } @@ TestAspect.withLiveClock,
    test("evaluation within timeout succeeds") {
      ZIO.scoped {
        val provider = new SlowProvider(Map("flag" -> true), delay = 5.millis)
        FeatureFlags.fromProvider(provider, evaluationTimeout = 2.seconds).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          ff.boolean("flag", default = false).map { result =>
            assertTrue(result == true)
          }
        }
      }
    } @@ TestAspect.withLiveClock,
    test("no timeout by default — preserves backward compatibility") {
      ZIO.scoped {
        val provider = new SlowProvider(Map("flag" -> true), delay = 50.millis)
        FeatureFlags.fromProvider(provider).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          ff.boolean("flag", default = false).map { result =>
            assertTrue(result == true)
          }
        }
      }
    } @@ TestAspect.withLiveClock,
    test("per-call timeout overrides global timeout") {
      ZIO.scoped {
        val provider = new SlowProvider(Map("flag" -> true), delay = 2.seconds)
        // Global timeout is generous (10s), but per-call timeout is tight (50ms)
        FeatureFlags.fromProvider(provider, evaluationTimeout = 10.seconds).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          ff.booleanDetails(
            "flag",
            default = false,
            ctx = EvaluationContext.empty,
            options = EvaluationOptions.empty.withTimeout(50.millis)
          ).either
            .map { result =>
              assertTrue(result.isLeft)
            }
        }
      }
    } @@ TestAspect.withLiveClock,
    test("per-call timeout applies when no global timeout is set") {
      ZIO.scoped {
        val provider = new SlowProvider(Map("flag" -> true), delay = 2.seconds)
        FeatureFlags.fromProvider(provider).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          ff.booleanDetails(
            "flag",
            default = false,
            ctx = EvaluationContext.empty,
            options = EvaluationOptions.empty.withTimeout(50.millis)
          ).either
            .map { result =>
              assertTrue(result.isLeft)
            }
        }
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential
}
