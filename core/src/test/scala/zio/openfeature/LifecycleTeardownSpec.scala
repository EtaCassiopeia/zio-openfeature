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

// #435: scope close ends event consumers; awaitReady() never waits forever on a shut-down instance; an interrupted
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

  private def buildIsolated(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"teardown-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  def spec = suite("LifecycleTeardownSpec (#435)")(
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
    test("a fiber already parked in awaitReady() returns when the instance shuts down") {
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val gated   = new BoolProvider() {
        override def initialize(ctx: OFEvaluationContext): Unit = { entered.countDown(); release.await() }
      }
      ZIO
        .scoped {
          for {
            ff     <- buildIsolated(new BoolProvider)
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
