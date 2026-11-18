package zio.openfeature

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import zio._
import zio.test._
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}

/** A handler with an associated state, registered while a `setProvider` swap is in flight, runs when the swap leaves
  * the status in that state, though the swap publishes nothing for its own status write.
  */
object HandlerDuringSwapSpec extends ZIOSpecDefault {

  private def dflt[T](v: T): ProviderEvaluation[T] = {
    val b = ProviderEvaluation.builder[T]()
    b.value(v)
    b.reason("DEFAULT")
    b.build().asInstanceOf[ProviderEvaluation[T]]
  }

  abstract private class Plain(name: String) extends OFFeatureProvider {
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = dflt(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = dflt(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = dflt(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = dflt(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = dflt(d)
  }

  private class Instant(name: String) extends Plain(name)

  /** Once initialized, the first `getMetadata` from a thread that is not the SDK's (the swap reading the name, after
    * the SDK published READY and before the swap's outcome) parks until `hold` is released.
    */
  private class HoldsNameRead(name: String) extends Plain(name) {
    private val initialized = new AtomicBoolean(false)
    val hold                = new CountDownLatch(1)
    val held                = new CountDownLatch(1)

    override def getMetadata: Metadata = {
      val sdkThread = Thread.currentThread().getName.startsWith("openfeature-")
      if (initialized.get() && !sdkThread && hold.getCount > 0) {
        held.countDown()
        hold.await(10, TimeUnit.SECONDS)
      }
      super.getMetadata
    }
    override def initialize(ctx: OFEvaluationContext): Unit = initialized.set(true)
    override def shutdown(): Unit                           = hold.countDown()
  }

  /** `initialize()` blocks until released, so a swap to it times out. */
  private class HangsInit(name: String) extends Plain(name) {
    val release                                             = new CountDownLatch(1)
    val entered                                             = new CountDownLatch(1)
    override def initialize(ctx: OFEvaluationContext): Unit = {
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      ()
    }
  }

  private def await(l: CountDownLatch) = ZIO.attemptBlocking(l.await(10, TimeUnit.SECONDS)).orDie

  private def instance(provider: OFFeatureProvider, initTimeout: Duration = FeatureFlags.DefaultInitTimeout) =
    FeatureFlags.build(
      provider,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  def spec = suite("HandlerDuringSwapSpec")(
    test("a Ready handler registered after the new provider's READY, before the swap's outcome, runs") {
      ZIO.scoped {
        for {
          ff <- instance(new Instant("A"))
          _  <- ff.awaitReady(5.seconds)
          b = new HoldsNameRead("B")
          sawB   <- Promise.make[Nothing, Unit]
          stream <- ff.subscribeEvents
          _      <- stream
            .collect { case ProviderEvent.Ready(m, _) if m.name == "B" => () }
            .take(1)
            .runDrain
            .zipRight(sawB.succeed(()))
            .forkScoped
          swap   <- ff.setProvider(b).either.fork
          _      <- sawB.await
          parked <- await(b.held)
          during <- ff.providerStatus
          calls  <- Ref.make(0)
          _      <- ZIO.acquireRelease(ff.on(ProviderEventType.Ready, _ => calls.update(_ + 1)))(c => c)
          _      <- ZIO.succeed(b.hold.countDown())
          result <- swap.join
          after  <- ff.providerStatus
          ran    <- calls.get.repeatUntil(_ > 0).timeout(1.second)
        } yield assertTrue(
          parked,
          during == ProviderStatus.NotReady,
          result.isRight,
          after == ProviderStatus.Ready,
          ran.isDefined
        )
      }
    } @@ TestAspect.nonFlaky(10),
    test("an Error handler registered during a swap that times out runs once the status is Error") {
      ZIO.scoped {
        for {
          ff <- instance(new Instant("A"), initTimeout = 300.millis)
          _  <- ff.awaitReady(5.seconds)
          b = new HangsInit("B")
          swap   <- ff.setProvider(b).either.fork
          _      <- await(b.entered)
          during <- ff.providerStatus
          seen   <- Promise.make[Nothing, ProviderMetadata]
          _      <- ZIO.acquireRelease(ff.onProviderError((_, m) => seen.succeed(m).unit))(c => c)
          result <- swap.join
          after  <- ff.providerStatus
          ran    <- seen.await.timeout(1.second)
          _      <- ZIO.succeed(b.release.countDown())
        } yield assertTrue(
          during == ProviderStatus.NotReady,
          result.isLeft,
          after == ProviderStatus.Error,
          ran.map(_.name).contains("B")
        )
      }
    },
    test("a handler registered while no swap runs, whose state does not match, is not run when a later swap ends") {
      ZIO.scoped {
        for {
          ff    <- instance(new Instant("A"))
          _     <- ff.awaitReady(5.seconds)
          calls <- Ref.make(0)
          _     <- ZIO.acquireRelease(ff.on(ProviderEventType.Stale, _ => calls.update(_ + 1)))(c => c)
          swap  <- ff.setProvider(new Instant("B")).either
          after <- ff.providerStatus
          _     <- ZIO.sleep(300.millis)
          ran   <- calls.get
        } yield assertTrue(swap.isRight, after == ProviderStatus.Ready, ran == 0)
      }
    },
    test("cancelling a handler registered during a swap stops its re-check") {
      ZIO.scoped {
        for {
          ff <- instance(new Instant("A"))
          _  <- ff.awaitReady(5.seconds)
          b = new HoldsNameRead("B")
          swap   <- ff.setProvider(b).either.fork
          parked <- await(b.held)
          calls  <- Ref.make(0)
          cancel <- ff.on(ProviderEventType.Ready, _ => calls.update(_ + 1))
          _      <- cancel
          _      <- ZIO.succeed(b.hold.countDown())
          result <- swap.join
          after  <- ff.providerStatus
          _      <- ZIO.sleep(300.millis)
          ran    <- calls.get
        } yield assertTrue(parked, result.isRight, after == ProviderStatus.Ready, ran == 0)
      }
    } @@ TestAspect.nonFlaky(10)
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
}
