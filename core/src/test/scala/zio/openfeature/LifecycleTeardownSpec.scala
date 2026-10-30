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

// Scope close ends event consumers; awaitReady() never waits forever on a shut-down instance; an interrupted
// shutdown still completes.
object LifecycleTeardownSpec extends ZIOSpecDefault {

  private class BoolProvider(onShutdown: () => Unit = () => ()) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "p" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = onShutdown()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  private def buildIsolated(
    provider: FeatureProvider,
    initTimeout: Duration = FeatureFlags.DefaultInitTimeout
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"teardown-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  /** Counts `initialize()` and `shutdown()` calls; when `blocking`, `initialize()` waits for `release`. */
  private class Counting(name: String, blocking: Boolean) extends BoolProvider {
    val release                                             = new CountDownLatch(1)
    val inits                                               = new java.util.concurrent.atomic.AtomicInteger(0)
    val shutdowns                                           = new java.util.concurrent.atomic.AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      if (blocking) release.await()
      ()
    }
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
  }

  private def awaitTrue(cond: => Boolean): UIO[Boolean] =
    ZIO
      .succeed(cond)
      .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[Boolean](!_))
      .timeout(5.seconds)
      .map(_.isDefined)

  def spec = suite("LifecycleTeardownSpec")(
    test("closing the scope during an in-flight swap still shuts the previous provider down exactly once") {
      val a = new Counting("A", blocking = false)
      val b = new Counting("B", blocking = true)
      for {
        bound <- Promise.make[Nothing, Unit]
        probe = new zio.openfeature.internal.LifecycleProbe {
          def at(p: zio.openfeature.internal.LifecycleProbe.Point): UIO[Unit] =
            if (p == zio.openfeature.internal.LifecycleProbe.Point.SdkBind) bound.succeed(()).unit else ZIO.unit
        }
        _ <- ZIO.scoped {
          for {
            ff <- zio.openfeature.internal.FeatureFlagsFactories.buildWithTracking(
              a,
              domain = Some(s"teardown-${java.util.UUID.randomUUID()}"),
              version = None,
              initialHooks = Nil,
              statusRef = None,
              addShutdownFinalizer = true,
              apiOverride = Some(OpenFeatureAPI.createIsolated()),
              initTimeout = 500.millis,
              probe = probe
            )
            _ <- ff.setProvider(b).ignore.forkDaemon
            _ <- bound.await
          } yield ()
        }
        aDown <- awaitTrue(a.shutdowns.get() == 1)
        _     <- ZIO.succeed(b.release.countDown())
        bDown <- awaitTrue(b.shutdowns.get() >= 1)
        _     <- ZIO.sleep(200.millis)
        aOnce = a.shutdowns.get() == 1
      } yield assertTrue(aDown, bDown, aOnce)
    },
    test("setProvider after the layer scope closes fails with ProviderNotReady and does not touch the provider") {
      val p2 = new Counting("p2", blocking = false)
      for {
        ff <- ZIO.scoped {
          zio.openfeature.internal.FeatureFlagsFactories.buildWithTracking(
            new Counting("p1", blocking = false),
            domain = Some(s"teardown-${java.util.UUID.randomUUID()}"),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(OpenFeatureAPI.createIsolated())
          )
        }
        swap <- ff.setProvider(p2).either
        inits = p2.inits.get()
      } yield assertTrue(swap == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)), inits == 0)
    },
    test("closing the layer's scope ends an events stream consumer") {
      for {
        scope    <- Scope.make
        ff       <- scope.extend[Any](buildIsolated(new BoolProvider))
        consumer <- ff.events.runDrain.fork
        _        <- ZIO.sleep(50.millis)
        _        <- scope.close(Exit.unit)
        joined   <- consumer.join.timeout(5.seconds)
      } yield assertTrue(joined.isDefined)
    },
    test("awaitReady() on a shut-down instance returns instead of waiting forever") {
      ZIO.scoped {
        for {
          ff     <- buildIsolated(new BoolProvider)
          _      <- ff.shutdown
          status <- ff.awaitReady().timeout(5.seconds)
        } yield assertTrue(status.contains(ProviderStatus.NotReady))
      }
    },
    // The shutdown waits for the swap to time out (status Error, which does not end the wait) and then ends it.
    test("a fiber already parked in awaitReady() during a swap returns when the instance shuts down") {
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val gated   = new BoolProvider() {
        override def initialize(ctx: OFEvaluationContext): Unit = { entered.countDown(); release.await() }
      }
      ZIO
        .scoped {
          for {
            ff     <- buildIsolated(new BoolProvider, initTimeout = 1.second)
            swap   <- ff.setProvider(gated).either.fork // holds the instance NotReady while `gated` initializes
            _      <- ZIO.attemptBlocking(entered.await()).orDie
            waiter <- ff.awaitReady().fork
            _      <- ZIO.sleep(100.millis)
            parked <- waiter.poll
            _      <- ff.shutdown
            status <- waiter.join.timeout(5.seconds)
            _      <- ZIO.succeed(release.countDown())
            _      <- swap.join
          } yield assertTrue(
            parked.isEmpty,
            status.exists(s => s == ProviderStatus.NotReady || s == ProviderStatus.ShuttingDown)
          )
        }
        .ensuring(ZIO.succeed(release.countDown()))
    },
    test("an interrupted shutdown still completes to NotReady") {
      val entered  = new CountDownLatch(1)
      val release  = new CountDownLatch(1)
      val provider = new BoolProvider(() => { entered.countDown(); release.await() })
      ZIO.scoped {
        for {
          ff     <- buildIsolated(provider)
          fiber  <- ff.shutdown.fork
          _      <- ZIO.attemptBlocking(entered.await()).orDie
          killer <- fiber.interrupt.fork
          _      <- ZIO.sleep(100.millis)
          _      <- ZIO.succeed(release.countDown())
          _      <- killer.join
          status <- ff.providerStatus
        } yield assertTrue(status == ProviderStatus.NotReady)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
