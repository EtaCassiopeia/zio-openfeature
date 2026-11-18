package zio.openfeature

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  ProviderEvaluation,
  Value
}

// A Ready handler registered while a swap is between the SDK's READY for the new provider and the swap's
// own outcome.
object ReadyHandlerDuringSwapSpec extends ZIOSpecDefault {

  /** A plain provider. Once `initialize()` has returned, a `getMetadata` call from a thread that is not the SDK's
    * blocks until `hold` is released (bounded, so a mistake shows as a slow test, not a hang). The swap reads the new
    * provider's name (`readName`) on a ZIO thread after `initialize()` returns and before it applies its outcome, so
    * the hold parks the swap there, after the SDK has dispatched its READY.
    */
  class HoldingProvider(name: String) extends OFFeatureProvider {
    val initialized = new AtomicBoolean(false)
    val hold        = new CountDownLatch(1)
    val held        = new CountDownLatch(1)
    val heldCalls   = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = {
      val sdkThread = Thread.currentThread().getName.startsWith("openfeature-")
      if (initialized.get() && !sdkThread && hold.getCount > 0) {
        heldCalls.incrementAndGet()
        held.countDown()
        hold.await(10, TimeUnit.SECONDS)
      }
      new Metadata { override def getName: String = name }
    }
    override def initialize(ctx: OFEvaluationContext): Unit = initialized.set(true)
    override def shutdown(): Unit                           = hold.countDown()
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

  final class Plain(name: String) extends HoldingProvider(name) {
    hold.countDown()
  }

  private def await(latch: CountDownLatch): UIO[Boolean] =
    ZIO.attemptBlocking(latch.await(10, TimeUnit.SECONDS)).orDie

  /** Swap A -> B, parking the swap after the SDK's READY for B reached the event stream. `register` runs while it is
    * parked; `observe` runs after the swap, while what `register` acquired is still held. Returns what it observed, the
    * swap's result and the status after.
    */
  private def parkedSwap[A, B](register: FeatureFlagsAdmin => ZIO[Scope, Nothing, A])(observe: A => UIO[B]) =
    ZIO.scoped {
      for {
        env <- FeatureFlags.fromProvider(new Plain("A")).build
        ff    = env.get[FeatureFlags]
        admin = env.get[FeatureFlagsAdmin]
        _ <- ff.awaitReady(5.seconds)
        b = new HoldingProvider("B")
        stream <- ff.subscribeEvents
        sawB   <- Promise.make[Nothing, Unit]
        _      <- stream
          .collect { case ProviderEvent.Ready(m, _) if m.name == "B" => () }
          .take(1)
          .runDrain
          .zipRight(sawB.succeed(()))
          .forkScoped
        swap <- admin.setProvider(b).either.fork
        // B's READY is on the stream, and the swap is parked before its outcome.
        _      <- sawB.await.timeout(10.seconds)
        parked <- await(b.held)
        during <- ff.providerStatus
        a      <- register(admin)
        _      <- ZIO.succeed(b.hold.countDown())
        result <- swap.join
        after  <- ff.providerStatus
        b      <- observe(a)
      } yield (parked, during, b, result, after)
    }

  def spec = suite("ReadyHandlerDuringSwapSpec")(
    test("a Ready handler registered after the new provider's READY but before the swap's outcome runs once Ready") {
      for {
        calls <- Ref.make(0)
        // Bounded wait, while the handler is registered, for a delivery from the stream or the re-check.
        out <- parkedSwap(admin =>
          ZIO.acquireRelease(admin.on(ProviderEventType.Ready, _ => calls.update(_ + 1)))(c => c)
        )(_ => calls.get.repeatUntil(_ > 0).timeout(1.second))
        (parked, during, ran, result, after) = out
      } yield assertTrue(
        parked,
        during == ProviderStatus.NotReady,
        result.isRight,
        after == ProviderStatus.Ready,
        ran.isDefined
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.nonFlaky(50),
    test("control: a Ready handler registered before the swap gets the new provider's READY") {
      ZIO.scoped {
        for {
          env <- FeatureFlags.fromProvider(new Plain("A")).build
          ff    = env.get[FeatureFlags]
          admin = env.get[FeatureFlagsAdmin]
          _    <- ff.awaitReady(5.seconds)
          gotB <- Promise.make[Nothing, Unit]
          _    <- ZIO.acquireRelease(
            admin.on(ProviderEventType.Ready, e => ZIO.when(e.metadata.name == "B")(gotB.succeed(())).unit)
          )(c => c)
          result <- admin.setProvider(new Plain("B")).either
          got    <- gotB.await.timeout(5.seconds)
        } yield assertTrue(result.isRight, got.isDefined)
      }
    } @@ TestAspect.withLiveClock,
    test("control: a Ready handler registered after the swap runs at once (5.3.3)") {
      ZIO.scoped {
        for {
          env <- FeatureFlags.fromProvider(new Plain("A")).build
          ff    = env.get[FeatureFlags]
          admin = env.get[FeatureFlagsAdmin]
          _      <- ff.awaitReady(5.seconds)
          result <- admin.setProvider(new Plain("B")).either
          calls  <- Ref.make(0)
          _      <- ZIO.acquireRelease(admin.on(ProviderEventType.Ready, _ => calls.update(_ + 1)))(c => c)
          n      <- calls.get
        } yield assertTrue(result.isRight, n >= 1)
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
}
