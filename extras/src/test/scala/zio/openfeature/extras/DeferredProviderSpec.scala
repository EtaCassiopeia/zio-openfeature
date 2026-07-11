package zio.openfeature.extras

import zio._
import zio.test._
import zio.openfeature.{FeatureFlags, ProviderStatus}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ErrorCode,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

object DeferredProviderSpec extends ZIOSpecDefault {

  private val ctx: OFEvaluationContext = new ImmutableContext()

  /** A ready delegate answering a fixed boolean; records its initialize() thread and shutdown() count. */
  private class RecordingProvider(
    value: Boolean,
    shutdowns: AtomicInteger = new AtomicInteger(0),
    initThread: AtomicReference[String] = new AtomicReference[String](null)
  ) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                    = new Metadata { override def getName: String = "delegate" }
    override def getState: ProviderState                  = ProviderState.READY
    override def initialize(c: OFEvaluationContext): Unit = { initThread.set(Thread.currentThread().getName); () }
    override def shutdown(): Unit                         = { shutdowns.incrementAndGet(); () }
    def shutdownCount: Int                                = shutdowns.get()
    def initThreadName: String                            = initThread.get()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(java.lang.Boolean.valueOf(value), "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  def spec = suite("DeferredProviderSpec")(
    // AC5: no NPE pre-construction; typed PROVIDER_NOT_READY
    test("pre-construction evaluation returns a typed PROVIDER_NOT_READY, not an NPE") {
      val dp   = DeferredProvider("d")(() => new RecordingProvider(true))
      val eval = dp.getBooleanEvaluation("flag", false, ctx)
      assertTrue(
        eval.getErrorCode == ErrorCode.PROVIDER_NOT_READY,
        !eval.getValue.booleanValue(),
        dp.getState == ProviderState.NOT_READY,
        dp.getMetadata.getName == "d"
      )
    },
    // AC5: shutdown() racing an in-flight initialize() shuts the delegate down exactly once
    test("shutdown during initialize shuts the freshly built delegate down once") {
      val constructing = new CountDownLatch(1)
      val release      = new CountDownLatch(1)
      val delegate     = new RecordingProvider(true)
      val dp = DeferredProvider("d") { () =>
        constructing.countDown()
        release.await()
        delegate
      }
      for {
        fiber <- ZIO.attemptBlocking(dp.initialize(ctx)).fork
        _     <- ZIO.attemptBlocking(constructing.await())
        _     <- ZIO.succeed(dp.shutdown())
        _     <- ZIO.succeed(release.countDown())
        _     <- fiber.join
        sd    <- ZIO.succeed(delegate.shutdownCount)
        st    <- ZIO.succeed(dp.getState)
      } yield assertTrue(sd == 1, st == ProviderState.NOT_READY)
    } @@ TestAspect.withLiveClock,
    // AC5: delegate.initialize throwing shuts the delegate down once and does not wedge in Constructing
    test("delegate.initialize throwing shuts the delegate down and reports not-ready") {
      val delegateShutdowns = new AtomicInteger(0)
      val delegate = new RecordingProvider(true, delegateShutdowns) {
        override def initialize(c: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
      }
      val dp = DeferredProvider("d")(() => delegate)
      for {
        res <- ZIO.attempt(dp.initialize(ctx)).either
        sd  <- ZIO.succeed(delegateShutdowns.get())
        st  <- ZIO.succeed(dp.getState)
      } yield assertTrue(res.isLeft, sd == 1, st == ProviderState.NOT_READY)
    },
    // AC5: works through fromProviderAsync; initialize runs off the caller thread (on the SDK executor)
    test("works through fromProviderAsync and constructs off the caller thread") {
      val delegate = new RecordingProvider(true)
      val dp       = DeferredProvider("deferred")(() => delegate)
      for {
        callerThread <- ZIO.succeed(Thread.currentThread().getName)
        result <- ZIO.scoped {
          FeatureFlags.fromProviderAsync(dp).build.map(_.get[FeatureFlags]).flatMap { ff =>
            for {
              status <- ff.awaitReady(10.seconds)
              v      <- ff.boolean("flag", false)
              it     <- ZIO.succeed(delegate.initThreadName)
            } yield assertTrue(
              status == ProviderStatus.Ready,
              v,                 // delegate answers true once active
              it != null,        // initialize actually ran
              it != callerThread // ...on the SDK executor, not the caller's thread
            )
          }
        }
      } yield result
    } @@ TestAspect.withLiveClock,
    // AC5: works through fromMultiProviderAsync
    test("works through fromMultiProviderAsync") {
      val delegate = new RecordingProvider(true)
      val dp       = DeferredProvider("deferred-multi")(() => delegate)
      val other    = new RecordingProvider(false)
      ZIO.scoped {
        FeatureFlags.fromMultiProviderAsync(List(dp, other)).build.map(_.get[FeatureFlags]).flatMap { ff =>
          for {
            status <- ff.awaitReady(10.seconds)
            v      <- ff.boolean("flag", false)
          } yield assertTrue(status == ProviderStatus.Ready, v)
        }
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential
}
