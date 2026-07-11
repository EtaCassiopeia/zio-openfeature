package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.TimeoutException

/** #251: the per-evaluation timeout is expressed by the `EvaluationTimeout` ADT. `Default` uses the instance's global
  * timeout (1s unless overridden), `After(d)` bounds a single call, and `Disabled` (via `withoutTimeout`) runs with no
  * timeout — skipping the timeout scaffolding entirely.
  */
object EvaluationTimeoutAdtSpec extends ZIOSpecDefault {

  private class SlowProvider(sleepMillis: Long) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Slow" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      Thread.sleep(sleepMillis)
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(globalTimeout: Option[Duration]): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPIFactory.create()
    FeatureFlags.build(
      new SlowProvider(200L),
      domain = Some(s"timeout-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = globalTimeout
    )
  }

  private def isTimeout(e: FeatureFlagError): Boolean = e match {
    case FeatureFlagError.ProviderError(t) => t.isInstanceOf[TimeoutException]
    case _                                 => false
  }

  def spec = suite("EvaluationTimeoutAdtSpec")(
    test("Default uses the global timeout: a slow evaluation times out") {
      ZIO.scoped {
        buildFF(Some(50.millis)).flatMap { ff =>
          ff.booleanDetails("flag", default = false)
            .either
            .map(r => assertTrue(r.isLeft, r.left.exists(isTimeout)))
        }
      }
    },
    test("withoutTimeout (Disabled) runs with no timeout: the slow evaluation succeeds") {
      ZIO.scoped {
        buildFF(Some(50.millis)).flatMap { ff =>
          ff.booleanDetails("flag", default = false, EvaluationContext.empty, EvaluationOptions.empty.withoutTimeout)
            .map(r => assertTrue(r.value))
        }
      }
    },
    test("withTimeout(d) bounds a single evaluation and can time it out even when the global is disabled") {
      ZIO.scoped {
        buildFF(None).flatMap { ff =>
          ff.booleanDetails(
            "flag",
            default = false,
            EvaluationContext.empty,
            EvaluationOptions.empty.withTimeout(30.millis)
          ).either
            .map(r => assertTrue(r.isLeft, r.left.exists(isTimeout)))
        }
      }
    },
    test("withTimeout(d) with a generous bound lets the evaluation complete") {
      ZIO.scoped {
        buildFF(Some(50.millis)).flatMap { ff =>
          ff.booleanDetails(
            "flag",
            default = false,
            EvaluationContext.empty,
            EvaluationOptions.empty.withTimeout(2.seconds)
          ).map(r => assertTrue(r.value))
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
