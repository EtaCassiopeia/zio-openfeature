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
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch

/** #254: transaction overrides (and cached evaluations) resolve purely locally and must still succeed while the
  * provider is NotReady / Fatal / shutting down — that fallback role is the whole point of overrides. Only evaluations
  * that must reach the provider stay behind the readiness gate.
  */
object TransactionOverrideFallbackSpec extends ZIOSpecDefault {

  /** Never becomes ready: `initialize` blocks on a gate so the SDK never fires PROVIDER_READY; status stays NotReady.
    * The gate is released on shutdown (via the api-shutdown finalizer at scope close), so no executor thread leaks.
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

  private def buildNotReadyFF: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .buildAsync(
        new NotReadyProvider,
        domain = Some(s"tx-${java.util.UUID.randomUUID()}"),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(OpenFeatureAPIFactory.create()),
        initTimeout = 1.hour // don't let the watchdog flip to Fatal during the test (live clock)
      )

  def spec = suite("TransactionOverrideFallbackSpec")(
    test("a transaction override succeeds while the provider is NotReady") {
      ZIO.scoped {
        buildNotReadyFF.flatMap { ff =>
          for {
            status <- ff.providerStatus
            tx     <- ff.transaction(overrides = Map("flag" -> true))(ff.boolean("flag", default = false))
          } yield assertTrue(status == ProviderStatus.NotReady, tx.result)
        }
      }
    },
    test("a non-overridden evaluation inside a transaction still fails ProviderNotReady (gate preserved)") {
      ZIO.scoped {
        buildNotReadyFF.flatMap { ff =>
          for {
            r <- ff.transaction(overrides = Map("flag" -> true))(ff.boolean("other-flag", default = false)).either
          } yield assertTrue( // "other-flag" has no override, so it must reach the NotReady provider
            r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady])
          )
        }
      }
    },
    test("a type-mismatched override fails OverrideTypeMismatch, not ProviderNotReady, while NotReady") {
      ZIO.scoped {
        buildNotReadyFF.flatMap { ff =>
          for {
            // The override resolves locally, so the failure is the local decode error — the readiness gate never runs.
            r <- ff.transaction(overrides = Map("flag" -> "not-a-bool"))(ff.boolean("flag", default = false)).either
          } yield assertTrue(r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.OverrideTypeMismatch]))
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
