package zio.openfeature

import java.util.concurrent.CountDownLatch

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

// Interrupting setProvider mid-swap must not leave status stuck at NotReady or the refs half rolled back. The
// swap body runs to its (bounded) outcome; waiting for the swap lock stays interruptible.
object SetProviderInterruptSpec extends ZIOSpecDefault {

  private class BoolProvider(name: String, value: Boolean) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(java.lang.Boolean.valueOf(value), "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  /** initialize() signals `entered`, then waits for `release`; afterwards it succeeds or throws. */
  private class GatedProvider(name: String, failAfterRelease: Boolean) extends BoolProvider(name, true) {
    val entered                                             = new CountDownLatch(1)
    val release                                             = new CountDownLatch(1)
    override def initialize(ctx: OFEvaluationContext): Unit = {
      entered.countDown()
      release.await()
      if (failAfterRelease) throw new RuntimeException("init failed after release")
    }
  }

  private def buildIsolated(initTimeout: Duration = 10.seconds): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      new BoolProvider("old", false),
      domain = Some(s"interrupt-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def interruptMidInit(ff: FeatureFlagsLive, provider: GatedProvider): UIO[Unit] =
    for {
      fiber       <- ff.setProvider(provider).either.fork
      _           <- ZIO.attemptBlocking(provider.entered.await()).orDie
      interrupter <- fiber.interrupt.fork // the swap is mid-initialize; the interrupt must wait for its outcome
      _           <- Live.live(ZIO.sleep(100.millis))
      _           <- ZIO.succeed(provider.release.countDown())
      _           <- interrupter.join
    } yield ()

  /** A build on an isolated API with `probe`, handing back the API and domain. */
  private def buildProbed(
    probe: zio.openfeature.internal.LifecycleProbe,
    initTimeout: Duration
  ): ZIO[Scope, Throwable, (FeatureFlagsLive, OpenFeatureAPI, String)] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"interrupt-${java.util.UUID.randomUUID()}"
    zio.openfeature.internal.FeatureFlagsFactories
      .build(
        new BoolProvider("old", false),
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        initTimeout = initTimeout,
        probe = probe
      )
      .map(ff => (ff, api, domain))
  }

  def spec = suite("SetProviderInterruptSpec")(
    test("interrupted during a swap that times out, a READY dropped while the gate was up is still applied") {
      import zio.openfeature.internal.LifecycleProbe.Point
      val late = new GatedProvider("late", failAfterRelease = false)
      Live.live(
        ZIO
          .scoped {
            for {
              held    <- Promise.make[Nothing, Unit]
              release <- Promise.make[Nothing, Unit]
              points = new java.util.concurrent.ConcurrentLinkedQueue[String]()
              probe  = new zio.openfeature.internal.LifecycleProbe {
                def at(p: Point): UIO[Unit] =
                  ZIO.succeed(points.add(p.toString)) *>
                    (if (p == Point.SwapTimeout) held.succeed(()) *> release.await else ZIO.unit)
              }
              built <- buildProbed(probe, initTimeout = 300.millis)
              (ff, api, domain) = built
              fiber <- ff.setProvider(late).either.fork
              // The swap timed out and its gate is still up: interrupt the caller now, which waits for the body.
              _ <- held.await
              _ <- fiber.interruptFork
              // The provider finishes initializing; its READY reaches the bridge while the gate is up and is dropped.
              _       <- ZIO.succeed(late.release.countDown())
              dropped <- ZIO
                .succeed(points.toArray.exists(_.toString.contains("PROVIDER_READY")))
                .repeatUntil(identity)
                .timeout(5.seconds)
                .map(_.isDefined)
              slot = api.getClient(domain).getProviderState
              _    <- release.succeed(())
              exit <- fiber.await
              // Only the swap's re-read of the slot can apply that READY now.
              status <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds)
            } yield assertTrue(
              dropped,
              slot == dev.openfeature.sdk.ProviderState.READY,
              exit.isInterrupted,
              status.contains(ProviderStatus.Ready)
            )
          }
          .ensuring(ZIO.succeed(late.release.countDown()))
      )
    },
    test("interrupted while the new provider initializes, then it succeeds: Ready on the new provider") {
      val next = new GatedProvider("next", failAfterRelease = false)
      ZIO.scoped {
        for {
          ff     <- buildIsolated()
          _      <- interruptMidInit(ff, next)
          status <- ff.providerStatus
          meta   <- ff.providerMetadata
          value  <- ff.boolean("x", false)
        } yield assertTrue(status == ProviderStatus.Ready, meta.name == "next", value)
      }
    },
    test("interrupted while the new provider initializes, then it fails: rolled back to the previous provider") {
      val next = new GatedProvider("next", failAfterRelease = true)
      ZIO.scoped {
        for {
          ff     <- buildIsolated()
          _      <- interruptMidInit(ff, next)
          status <- ff.providerStatus
          meta   <- ff.providerMetadata
          value  <- ff.boolean("x", true)
        } yield assertTrue(status == ProviderStatus.Ready, meta.name == "old", !value)
      }
    },
    test("an interrupt during a never-finishing init completes within the init timeout bound") {
      val stuck = new GatedProvider("stuck", failAfterRelease = false)
      Live.live(
        ZIO
          .scoped {
            for {
              ff     <- buildIsolated(initTimeout = 300.millis)
              fiber  <- ff.setProvider(stuck).either.fork
              _      <- ZIO.attemptBlocking(stuck.entered.await()).orDie
              start  <- Clock.nanoTime
              _      <- fiber.interrupt
              end    <- Clock.nanoTime
              status <- ff.providerStatus
            } yield assertTrue((end - start) / 1000000L < 3000L, status == ProviderStatus.Error)
          }
          .ensuring(ZIO.succeed(stuck.release.countDown()))
      )
    },
    test("a fiber waiting for the swap lock can still be interrupted promptly") {
      val first = new GatedProvider("first", failAfterRelease = false)
      Live.live(
        ZIO.scoped {
          for {
            ff     <- buildIsolated()
            holder <- ff.setProvider(first).fork
            _      <- ZIO.attemptBlocking(first.entered.await()).orDie
            waiter <- ff.setProvider(new BoolProvider("second", true)).fork
            _      <- ZIO.sleep(100.millis)
            start  <- Clock.nanoTime
            _      <- waiter.interrupt
            end    <- Clock.nanoTime
            _      <- ZIO.succeed(first.release.countDown())
            _      <- holder.join
          } yield assertTrue((end - start) / 1000000L < 1000L)
        }
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}
