package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// #447 part 2: since SDK 1.22 `FeatureProvider.getState()` defaults to READY, so a delegate that reports health only
// through events polls as READY forever. A polled READY must not undo a trip that an event caused.
object CircuitBreakerTripSourceSpec extends ZIOSpecDefault {

  /** Emits events; deliberately does NOT override getState, so polls see the SDK's READY default. */
  private class EventOnlyProvider extends EventProvider {
    val evaluations = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "events" }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) = {
      evaluations.incrementAndGet()
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    }
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")

    def error(): Unit = { emitProviderError(ProviderEventDetails.builder().message("down").build()).await(); () }
    def ready(): Unit = { emitProviderReady(ProviderEventDetails.builder().build()).await(); () }
  }

  /** getState is truthful and scripted. */
  private class PolledProvider extends EventOnlyProvider {
    val state = new AtomicReference[ProviderState](ProviderState.READY)
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState = state.get()
  }

  private class ManualTicker extends Ticker {
    private val now                = new AtomicReference[Long](0L)
    def advance(d: Duration): Unit = { now.updateAndGet(_ + d.toNanos); () }
    override def nanos(): Long     = now.get()
  }

  private val ctx    = new ImmutableContext()
  private val config = CircuitBreakerProviderConfig(resetTimeout = 1.minute, stateCheckInterval = 1.second)

  private def rejected(cb: CircuitBreakerProvider): Boolean =
    scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)).isFailure

  def spec = suite("CircuitBreakerTripSourceSpec (#447)")(
    test("an event-driven trip survives a polled READY from a delegate whose getState is the SDK default") {
      val delegate = new EventOnlyProvider
      val ticker   = new ManualTicker
      val cb       = CircuitBreakerProvider(delegate, config, ticker)
      cb.initialize(ctx)
      delegate.error()
      ticker.advance(2.seconds) // past stateCheckInterval: the next evaluation polls getState (READY by default)
      val before = delegate.evaluations.get()
      assertTrue(rejected(cb), delegate.evaluations.get() == before, cb.breaker.isOpen)
    },
    test("an event PROVIDER_READY still closes an event-driven trip") {
      val delegate = new EventOnlyProvider
      val ticker   = new ManualTicker
      val cb       = CircuitBreakerProvider(delegate, config, ticker)
      cb.initialize(ctx)
      delegate.error()
      delegate.ready()
      assertTrue(!rejected(cb), cb.breaker.isClosed)
    },
    test("without an event READY, the reset timeout and a successful probe close an event-driven trip") {
      val delegate = new EventOnlyProvider
      val ticker   = new ManualTicker
      val cb       = CircuitBreakerProvider(delegate, config, ticker)
      cb.initialize(ctx)
      delegate.error()
      ticker.advance(2.minutes)
      assertTrue(!rejected(cb), cb.breaker.isClosed)
    },
    test("a poll-driven trip is still reset by a later polled READY") {
      val delegate = new PolledProvider
      val ticker   = new ManualTicker
      val cb       = CircuitBreakerProvider(delegate, config, ticker)
      delegate.state.set(ProviderState.ERROR)
      val trippedByPoll = rejected(cb)
      delegate.state.set(ProviderState.READY)
      ticker.advance(2.seconds)
      assertTrue(trippedByPoll, !rejected(cb), cb.breaker.isClosed)
    },
    test("an event ERROR after a poll-driven trip is not undone by a polled READY") {
      val delegate = new PolledProvider
      val ticker   = new ManualTicker
      val cb       = CircuitBreakerProvider(delegate, config, ticker)
      cb.initialize(ctx)
      delegate.state.set(ProviderState.ERROR)
      rejected(cb)     // poll trips
      delegate.error() // the delegate also says so by event
      delegate.state.set(ProviderState.READY)
      ticker.advance(2.seconds)
      assertTrue(rejected(cb), cb.breaker.isOpen)
    }
  )
}
