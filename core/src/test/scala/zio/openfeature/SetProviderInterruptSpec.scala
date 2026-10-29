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

  private def buildIsolated(initTimeout: Duration = 10.seconds): ZIO[Scope, Throwable, FeatureFlags] =
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

  private def interruptMidInit(ff: FeatureFlags, provider: GatedProvider): UIO[Unit] =
    for {
      fiber       <- ff.setProvider(provider).either.fork
      _           <- ZIO.attemptBlocking(provider.entered.await()).orDie
      interrupter <- fiber.interrupt.fork // the swap is mid-initialize; the interrupt must wait for its outcome
      _           <- Live.live(ZIO.sleep(100.millis))
      _           <- ZIO.succeed(provider.release.countDown())
      _           <- interrupter.join
    } yield ()

  def spec = suite("SetProviderInterruptSpec")(
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
