package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch

/** #256 / spec §1.4.10 / §1.1.7: total ("never-fails") evaluation. `*OrDefault` and `resolveOrDefault` MUST NOT fail —
  * any evaluation error is absorbed into the supplied default. The `UIO` return type is itself the primary guarantee
  * (the effect has no typed error channel); these tests confirm the runtime behavior on both the success and error
  * paths.
  */
object TotalEvaluationSpec extends ZIOSpecDefault {

  private class ReadyBoolProvider(value: Boolean) extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Ready" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](value, "TARGETING_MATCH")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Never becomes ready: `initialize` blocks so the SDK never fires PROVIDER_READY; every evaluation fails
    * `ProviderNotReady` (a typed error). The gate is released on shutdown via the api-shutdown finalizer.
    */
  private class NotReadyProvider extends EventProvider {
    private val gate                                        = new CountDownLatch(1)
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "NotReady" }
    override def getState: ProviderState                    = ProviderState.NOT_READY
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = gate.countDown()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildReady(value: Boolean): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      new ReadyBoolProvider(value),
      domain = Some(s"total-r-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def buildNotReady: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.buildAsync(
      new NotReadyProvider,
      domain = Some(s"total-nr-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = 1.hour
    )

  def spec = suite("TotalEvaluationSpec")(
    test("booleanOrDefault returns the provider value on success (does not clobber with the default)") {
      ZIO.scoped {
        buildReady(true).flatMap(ff => ff.booleanOrDefault("flag", default = false).map(v => assertTrue(v)))
      }
    },
    test("booleanOrDefault returns the default (never fails) when the provider is NotReady") {
      ZIO.scoped {
        buildNotReady.flatMap { ff =>
          // No `.either` — the effect is UIO, so it cannot fail; the value IS the default.
          ff.booleanOrDefault("flag", default = true).map(v => assertTrue(v))
        }
      }
    },
    test("resolveOrDefault yields reason=Error with errorCode populated and value=default on a typed failure") {
      ZIO.scoped {
        buildNotReady.flatMap { ff =>
          ff.resolveOrDefault[Boolean]("flag", default = true).map { res =>
            assertTrue(
              res.value,
              res.reason == ResolutionReason.Error,
              res.errorCode.contains(ErrorCode.ProviderNotReady),
              res.errorMessage.isDefined
            )
          }
        }
      }
    },
    test("resolveOrDefault preserves the successful resolution (reason is not Error) when evaluation succeeds") {
      ZIO.scoped {
        buildReady(true).flatMap { ff =>
          ff.resolveOrDefault[Boolean]("flag", default = false).map { res =>
            assertTrue(res.value, res.reason != ResolutionReason.Error, res.errorCode.isEmpty)
          }
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
