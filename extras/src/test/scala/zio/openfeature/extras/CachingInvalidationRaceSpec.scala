package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  ProviderEventDetails,
  ProviderState,
  Reason,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// #449: zio-cache writes a lookup's result unconditionally when it completes, so a miss in flight across
// `invalidateAll` used to write the pre-invalidation value back and serve it as CACHED for the whole TTL.
object CachingInvalidationRaceSpec extends ZIOSpecDefault {

  /** Answers `current`; while `gate` is armed, the first call parks inside the delegate until released. */
  private class GatedProvider extends EventProvider {
    val current                           = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
    val calls                             = new AtomicInteger(0)
    @volatile var entered: CountDownLatch = new CountDownLatch(0)
    @volatile var gate: CountDownLatch    = new CountDownLatch(0)

    def arm(): Unit = { entered = new CountDownLatch(1); gate = new CountDownLatch(1) }

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "gated" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) = {
      calls.incrementAndGet()
      val value = current.get() // read before parking: this is the "pre-change" value the race writes back
      entered.countDown()
      gate.await()
      ProviderEvaluations.of[java.lang.Boolean](value, Reason.STATIC.toString)
    }
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")

    def configChanged(): Unit = {
      emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await()
      ()
    }
  }

  private val ctx = new ImmutableContext("user-1")

  /** Starts a miss that parks in the delegate, runs `invalidate`, flips the delegate to `true`, releases the miss, and
    * returns (in-flight result, next evaluation).
    */
  private def raceAcross(
    delegate: GatedProvider,
    cache: CachingProvider,
    invalidate: UIO[Unit]
  ): Task[(Boolean, (Boolean, String))] =
    for {
      _        <- ZIO.succeed(delegate.arm())
      inFlight <- ZIO.attemptBlocking(cache.getBooleanEvaluation("flag", false, ctx).getValue.booleanValue()).fork
      _        <- ZIO.attemptBlocking(delegate.entered.await())
      _        <- invalidate
      _        <- ZIO.succeed(delegate.current.set(java.lang.Boolean.TRUE))
      _        <- ZIO.succeed(delegate.gate.countDown())
      first    <- inFlight.join
      next     <- ZIO.attemptBlocking(cache.getBooleanEvaluation("flag", false, ctx))
    } yield (first, (next.getValue.booleanValue(), next.getReason))

  def spec = suite("CachingInvalidationRaceSpec (#449)")(
    test("a miss in flight across invalidateAll does not repopulate the cache with the old value") {
      val delegate = new GatedProvider
      val cache    = CachingProvider(delegate)
      raceAcross(delegate, cache, cache.invalidateAll).map { case (first, (nextValue, nextReason)) =>
        assertTrue(!first, nextValue, nextReason != Reason.CACHED.toString, delegate.calls.get() == 2)
      }
    },
    test("a miss in flight across a delegate configuration change does not repopulate the cache") {
      val delegate = new GatedProvider
      val cache    = CachingProvider(delegate)
      for {
        _      <- ZIO.attemptBlocking(cache.initialize(ctx)) // attaches the delegate's event channel
        result <- raceAcross(delegate, cache, ZIO.attemptBlocking(delegate.configChanged()).orDie)
        (first, (nextValue, nextReason)) = result
      } yield assertTrue(!first, nextValue, nextReason != Reason.CACHED.toString)
    },
    test("without invalidation a repeated evaluation is still a cache hit") {
      val delegate = new GatedProvider
      val cache    = CachingProvider(delegate)
      for {
        first  <- ZIO.attemptBlocking(cache.getBooleanEvaluation("flag", false, ctx))
        second <- ZIO.attemptBlocking(cache.getBooleanEvaluation("flag", false, ctx))
      } yield assertTrue(
        first.getReason != Reason.CACHED.toString,
        second.getReason == Reason.CACHED.toString,
        delegate.calls.get() == 1
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
