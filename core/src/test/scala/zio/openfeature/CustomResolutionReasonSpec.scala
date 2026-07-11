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

/** Spec §1.4.7: resolution reasons are provider-extensible strings. A provider-specific reason must be passed through
  * verbatim as `ResolutionReason.Other(value)`, not collapsed to `Unknown` (which is reserved for an absent reason).
  */
object CustomResolutionReasonSpec extends ZIOSpecDefault {

  private class ReasonProvider(reason: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Reason" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, reason)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, reason)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, reason)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, reason)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, reason)
  }

  private def buildFF(reason: String): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPIFactory.create()
    FeatureFlags.build(
      new ReasonProvider(reason),
      domain = Some(s"reason-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("CustomResolutionReasonSpec")(
    test("a provider-specific reason is passed through as Other, not collapsed to Unknown") {
      ZIO.scoped {
        for {
          ff  <- buildFF("PROVIDER_SPECIFIC")
          res <- ff.booleanDetails("flag", default = false)
        } yield assertTrue(res.reason == ResolutionReason.Other("PROVIDER_SPECIFIC"))
      }
    },
    test("a recognized reason still maps to its canonical case") {
      ZIO.scoped {
        for {
          ff  <- buildFF("TARGETING_MATCH")
          res <- ff.booleanDetails("flag", default = false)
        } yield assertTrue(res.reason == ResolutionReason.TargetingMatch)
      }
    },
    test("a null reason maps to Unknown, not Other") {
      ZIO.scoped {
        for {
          ff  <- buildFF(null)
          res <- ff.booleanDetails("flag", default = false)
        } yield assertTrue(res.reason == ResolutionReason.Unknown)
      }
    }
  ) @@ sequential @@ withLiveClock
}
