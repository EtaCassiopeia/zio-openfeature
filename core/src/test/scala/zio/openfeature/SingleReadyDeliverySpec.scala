package zio.openfeature

import java.util.concurrent.CountDownLatch

import zio._
import zio.stream.SubscriptionRef
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError

// #531: the event bridge is attached before the provider is registered, so one initialization delivers exactly one
// PROVIDER_READY to it. It used to be attached afterwards, where the SDK's post-init emission and its replay for an
// already-ready provider could both reach it, in either order. Each READY from the current provider counts down the
// `onReady` latch once, so a latch of 2 left at 1 means exactly one delivery.
object SingleReadyDeliverySpec extends ZIOSpecDefault {

  private class BoolProvider(name: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(
      key: String,
      default: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(
      key: String,
      default: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      ProviderEvaluations.of[String](default, "DEFAULT")
    override def getIntegerEvaluation(
      key: String,
      default: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = ProviderEvaluations.of[java.lang.Integer](default, "DEFAULT")
    override def getDoubleEvaluation(
      key: String,
      default: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = ProviderEvaluations.of[java.lang.Double](default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext): ProviderEvaluation[Value] =
      ProviderEvaluations.of[Value](default, "DEFAULT")
  }

  /** initialize() waits for the test to release it. */
  private class GatedProvider(name: String) extends BoolProvider(name) {
    private val gate                                        = new CountDownLatch(1)
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = gate.countDown()
    def release(): Unit                                     = gate.countDown()
  }

  /** Emits PROVIDER_ERROR from inside initialize(), then fails it. */
  private class ErrorEmittingProvider(name: String) extends BoolProvider(name) {
    override def initialize(ctx: OFEvaluationContext): Unit = {
      emitProviderError(ProviderEventDetails.builder().message("init exploded").build())
      throw new GeneralError("init exploded")
    }
  }

  // Time for a second READY to arrive if one were coming: the SDK dispatches both deliveries back to back.
  private val grace = 20.millis

  private def domain(): String = s"single-ready-${java.util.UUID.randomUUID()}"

  /** Wait (wall clock, bounded) until the latch has been counted down to `target`. */
  private def awaitCount(latch: CountDownLatch, target: Long): UIO[Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
      while (latch.getCount > target && java.lang.System.nanoTime() - deadline < 0) Thread.sleep(1)
    }.orDie

  /** Ready deliveries after the first one has arrived and a second has had time to. */
  private def readyDeliveries(latch: CountDownLatch): UIO[Long] =
    awaitCount(latch, 1) *> Live.live(ZIO.sleep(grace)) *> ZIO.succeed(2 - latch.getCount)

  private def syncBuild(
    provider: BoolProvider,
    dom: Option[String],
    api: OpenFeatureAPI,
    latch: CountDownLatch
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = dom,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      onReady = Some(latch)
    )

  private def asyncBuild(
    provider: BoolProvider,
    dom: Option[String],
    api: OpenFeatureAPI,
    latch: CountDownLatch
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.buildAsync(
      provider,
      domain = dom,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      onReady = Some(latch)
    )

  def spec = suite("SingleReadyDeliverySpec")(
    test("sync build on the default slot: exactly one Ready per initialization") {
      ZIO.scoped {
        for {
          latch  <- ZIO.succeed(new CountDownLatch(2))
          ff     <- syncBuild(new BoolProvider("p"), None, OpenFeatureAPI.createIsolated(), latch)
          count  <- readyDeliveries(latch)
          status <- ff.providerStatus
        } yield assertTrue(count == 1L, status == ProviderStatus.Ready)
      }
    } @@ TestAspect.nonFlaky(200),
    test("sync build on a domain slot: exactly one Ready per initialization") {
      ZIO.scoped {
        for {
          latch  <- ZIO.succeed(new CountDownLatch(2))
          ff     <- syncBuild(new BoolProvider("p"), Some(domain()), OpenFeatureAPI.createIsolated(), latch)
          count  <- readyDeliveries(latch)
          status <- ff.providerStatus
        } yield assertTrue(count == 1L, status == ProviderStatus.Ready)
      }
    } @@ TestAspect.nonFlaky(200),
    test("async build: exactly one Ready per initialization") {
      ZIO.scoped {
        for {
          latch  <- ZIO.succeed(new CountDownLatch(2))
          ff     <- asyncBuild(new BoolProvider("p"), Some(domain()), OpenFeatureAPI.createIsolated(), latch)
          count  <- readyDeliveries(latch)
          status <- ff.providerStatus
        } yield assertTrue(count == 1L, status == ProviderStatus.Ready)
      }
    } @@ TestAspect.nonFlaky(200),
    test("a provider already ready in the slot is reported Ready by the SDK's single replay") {
      ZIO.scoped {
        for {
          api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          provider <- ZIO.succeed(new BoolProvider("shared"))
          _        <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
          latch    <- ZIO.succeed(new CountDownLatch(2))
          ff <- FeatureFlags.buildAsync(
            provider,
            domain = Some(domain()),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            onReady = Some(latch)
          )
          count  <- readyDeliveries(latch)
          status <- ff.providerStatus
        } yield assertTrue(count == 1L, status == ProviderStatus.Ready)
      }
    } @@ TestAspect.nonFlaky(100),
    test("a registry client reusing the initialized default provider reaches Ready") {
      ZIO.scoped {
        for {
          registry <- FeatureFlagRegistry
            .fromProvider(new BoolProvider("default"))
            .build
            .map(_.get[FeatureFlagRegistry])
          default <- registry.defaultClient
          other   <- registry.getClient("reuses-default")
          s1      <- default.providerStatus
          s2      <- other.providerStatus
        } yield assertTrue(s1 == ProviderStatus.Ready, s2 == ProviderStatus.Ready)
      }
    },
    test("a domain built over a different, ready default provider is not reported Ready before its own init") {
      ZIO.scoped {
        for {
          api   <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          _     <- ZIO.attemptBlocking(api.setProviderAndWait(new BoolProvider("default")))
          gated <- ZIO.succeed(new GatedProvider("gated"))
          latch <- ZIO.succeed(new CountDownLatch(2))
          ff <- FeatureFlags.buildAsync(
            gated,
            domain = Some(domain()),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            onReady = Some(latch)
          )
          _      <- Live.live(ZIO.sleep(50.millis))
          before <- ff.providerStatus
          _      <- ZIO.succeed(gated.release())
          count  <- readyDeliveries(latch)
          after  <- ff.providerStatus
        } yield assertTrue(before == ProviderStatus.NotReady, count == 1L, after == ProviderStatus.Ready)
      }
    } @@ TestAspect.nonFlaky(20),
    test("a PROVIDER_ERROR from inside initialize() fails the build, sets Error, and leaves no bridge handler") {
      ZIO.scoped {
        for {
          api       <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          dom       <- ZIO.succeed(domain())
          statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
          latch     <- ZIO.succeed(new CountDownLatch(1))
          exit <- FeatureFlags
            .fromProviderWithDomain(new ErrorEmittingProvider("same"), dom, statusRef, Some(api), Some(latch))
            .build
            .exit
          errored <- statusRef.changes.filter(_ == ProviderStatus.Error).runHead.timeout(5.seconds)
          // A provider with the same name becomes ready in the same domain: a bridge left behind by the failed build
          // would take its READY as the current provider's and flip the status back to Ready.
          _     <- ZIO.attemptBlocking(api.setProviderAndWait(dom, new BoolProvider("same")))
          _     <- Live.live(ZIO.sleep(100.millis))
          after <- statusRef.get
          left  <- ZIO.succeed(latch.getCount)
        } yield assertTrue(
          exit.isFailure,
          errored.flatten.contains(ProviderStatus.Error),
          after == ProviderStatus.Error,
          left == 1L
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
}
