package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import java.util.concurrent.atomic.AtomicBoolean

/** Covers shutdown ownership: `shutdown` on a client that does NOT own its `OpenFeatureAPI` must not tear down the
  * shared api or its provider (both belong to the api owner) — otherwise it kills sibling clients. A sole-owner client
  * still shuts the api (which cascades to every provider registered on it).
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

  /** Counts `initialize()` and `shutdown()` calls. */
  private class CountingProvider(name: String) extends TrackingProvider(name, new AtomicBoolean(false)) {
    val inits                                               = new java.util.concurrent.atomic.AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit = { inits.incrementAndGet(); () }
  }

  /** A probe that holds the fiber reaching `point` until `release` completes, completing `held` first. */
  private def holdAt(
    point: zio.openfeature.internal.LifecycleProbe.Point,
    held: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit]
  ): zio.openfeature.internal.LifecycleProbe =
    new zio.openfeature.internal.LifecycleProbe {
      def at(p: zio.openfeature.internal.LifecycleProbe.Point): UIO[Unit] =
        if (p == point) held.succeed(()) *> release.await else ZIO.unit
    }

  private def buildOwning(
    provider: dev.openfeature.sdk.FeatureProvider,
    probe: zio.openfeature.internal.LifecycleProbe = zio.openfeature.internal.LifecycleProbe.none
  ): ZIO[Scope, Throwable, (FeatureFlags, dev.openfeature.sdk.OpenFeatureAPI, String)] = {
    val api    = dev.openfeature.sdk.OpenFeatureAPI.createIsolated()
    val domain = uniqueDomain("revive")
    zio.openfeature.internal.FeatureFlagsFactories
      .buildWithTracking(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        probe = probe
      )
      .map(ff => (ff, api, domain))
  }

  def spec = suite("FeatureFlagsShutdownSpec")(
    test("setProvider after shutdown fails with ProviderNotReady and does not touch the new provider") {
      ZIO.scoped {
        val p2 = new CountingProvider("p2")
        for {
          built <- buildOwning(new CountingProvider("p1"))
          (ff, api, domain) = built
          _      <- ff.shutdown
          swap   <- ff.setProvider(p2).either
          status <- ff.providerStatus
          eval   <- ff.boolean("flag", default = false).either
          bound  <- ZIO.succeed(api.getProvider(domain))
          inits = p2.inits.get()
        } yield assertTrue(
          swap == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          status == ProviderStatus.NotReady,
          eval == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          inits == 0,
          !(bound eq p2)
        )
      }
    },
    test("a setProvider queued behind shutdown is refused once the shutdown completes") {
      ZIO.scoped {
        val p2 = new CountingProvider("p2")
        for {
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          built   <- buildOwning(
            new CountingProvider("p1"),
            holdAt(zio.openfeature.internal.LifecycleProbe.Point.ShutdownDone, held, release)
          )
          (ff, _, _) = built
          shut <- ff.shutdown.fork
          _    <- held.await.timeout(5.seconds)
          swap <- ff.setProvider(p2).either.fork
          _    <- ZIO.sleep(100.millis)
          _    <- release.succeed(())
          _    <- shut.join
          out  <- swap.join
          inits = p2.inits.get()
        } yield assertTrue(out == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)), inits == 0)
      }
    } @@ withLiveClock,
    test("shutdown on a non-owning (shared-api) client shuts down neither the shared api nor any provider") {
      val shutA = new AtomicBoolean(false)
      val shutB = new AtomicBoolean(false)
      val api   = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          ffA       <- buildShared("A", shutA, owns = false, api)
          _         <- buildShared("B", shutB, owns = false, api)
          _         <- ffA.shutdown
          afterShut <- ffA.providerStatus
          // A late PROVIDER_READY from the just-shut provider must NOT resurrect the terminal NotReady:
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
    test("shutdown on a sole-owner client shuts the shared api, cascading to siblings") {
      val shutOwn = new AtomicBoolean(false)
      val shutSib = new AtomicBoolean(false)
      val api     = OpenFeatureAPI.createIsolated()
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
