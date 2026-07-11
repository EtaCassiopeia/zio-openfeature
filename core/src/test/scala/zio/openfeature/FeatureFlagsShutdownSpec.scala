package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import java.util.concurrent.atomic.AtomicBoolean

/** Covers shutdown ownership (#243): `shutdown` on a client that does NOT own its `OpenFeatureAPI` must not tear down
  * the shared api or its provider (both belong to the api owner) — otherwise it kills sibling clients. A sole-owner
  * client still shuts the api (which cascades to every provider registered on it).
  */
object FeatureFlagsShutdownSpec extends ZIOSpecDefault {

  private class TrackingProvider(name: String, shutCalled: AtomicBoolean) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = shutCalled.set(true)

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

  private def uniqueDomain(label: String): String = s"shutdown-$label-${java.util.UUID.randomUUID()}"

  private def readyEvent(name: String): EventDetails =
    EventDetails.builder().providerName(name).build().asInstanceOf[EventDetails]

  private def buildShared(name: String, shut: AtomicBoolean, owns: Boolean, api: dev.openfeature.sdk.OpenFeatureAPI) =
    FeatureFlags.buildAsync(
      new TrackingProvider(name, shut),
      domain = Some(uniqueDomain(name)),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owns,
      apiOverride = Some(api)
    )

  def spec = suite("FeatureFlagsShutdownSpec")(
    test("shutdown on a non-owning (shared-api) client shuts down neither the shared api nor any provider (#243)") {
      val shutA = new AtomicBoolean(false)
      val shutB = new AtomicBoolean(false)
      val api   = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          ffA       <- buildShared("A", shutA, owns = false, api)
          _         <- buildShared("B", shutB, owns = false, api)
          _         <- ffA.shutdown
          afterShut <- ffA.providerStatus
          // A late PROVIDER_READY from the just-shut provider must NOT resurrect the terminal NotReady (#285/#244):
          // drive it explicitly so the assertion is deterministic instead of racing the SDK event bridge. The event
          // hub is closed post-shutdown, so the publish step interrupts — tolerate it with .exit (as production's
          // runHandler swallows it).
          _          <- ffA.onReadyEvent(readyEvent("A")).exit
          afterReady <- ffA.providerStatus
        } yield assertTrue(
          !shutA.get(),                         // own provider is left to the api owner
          !shutB.get(),                         // sibling's provider is untouched
          afterShut == ProviderStatus.NotReady, // ...but ffA did release its own state
          afterReady == ProviderStatus.NotReady // and a late event cannot move it off terminal NotReady
        )
      }
    } @@ withLiveClock,
    test("shutdown on a sole-owner client shuts the shared api, cascading to siblings (#243 regression)") {
      val shutOwn = new AtomicBoolean(false)
      val shutSib = new AtomicBoolean(false)
      val api     = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          ffOwn <- buildShared("Own", shutOwn, owns = true, api)
          _     <- buildShared("Sib", shutSib, owns = false, api)
          _     <- ffOwn.shutdown
        } yield assertTrue(
          shutOwn.get(), // api.shutdown() shut the owner's provider
          shutSib.get()  // ...and cascaded to every provider on the api — proving api.shutdown() actually fired
        )
      }
    } @@ withLiveClock
  )
}
