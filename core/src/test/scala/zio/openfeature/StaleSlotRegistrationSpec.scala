package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.{FeatureFlagsFactories, ProviderEvaluations}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}

/** A client built on a provider the API already holds takes the SDK's state for it at once: no lifecycle event follows
  * a registration that reuses an initialized provider.
  */
object StaleSlotRegistrationSpec extends ZIOSpecDefault {

  final private class Emitting(name: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  def spec = suite("StaleSlotRegistrationSpec")(
    test("a client built on a provider the SDK already reports STALE is Stale, not NotReady") {
      ZIO.scoped {
        for {
          api <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          _   <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
          domain   = s"stale-slot-${java.util.UUID.randomUUID()}"
          provider = new Emitting("stale-provider")
          _ <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
          _ <- ZIO.attemptBlocking(
            provider.emit(OFProviderEvent.PROVIDER_STALE, ProviderEventDetails.builder().build()).await()
          )
          slot <- ZIO.succeed(api.getClient(domain).getProviderState)
          ff   <- FeatureFlagsFactories.buildAsync(
            provider,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
          status <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Stale).timeout(3.seconds)
          last   <- ff.providerStatus
        } yield assertTrue(slot == ProviderState.STALE, status.contains(ProviderStatus.Stale), last.canEvaluate)
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.timeout(1.minute)
}
