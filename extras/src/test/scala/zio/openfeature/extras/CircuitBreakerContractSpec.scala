package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderBridge,
  ImmutableContext,
  Metadata,
  ProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

// What docs/extras.md promises about CircuitBreakerProvider's rejection messages, its state polling and the events a
// failure emits, pinned on one thread with a scripted clock.
object CircuitBreakerContractSpec extends ZIOSpecDefault {

  /** Scripted state, events, and a switch that makes every evaluation throw. Counts polls and evaluations. */
  private class Delegate extends EventProvider {
    val state       = new AtomicReference[ProviderState](ProviderState.READY)
    val failing     = new AtomicBoolean(false)
    val polls       = new AtomicInteger(0)
    val evaluations = new AtomicInteger(0)
    // While set, an evaluation counts down `entered` and waits for `release`.
    val hold = new AtomicReference[Option[(CountDownLatch, CountDownLatch)]](None)

    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState = { polls.incrementAndGet(); state.get() }
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "scripted" }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def respond[T](value: T) = {
      evaluations.incrementAndGet()
      hold.get().foreach { case (entered, release) => entered.countDown(); release.await(10, TimeUnit.SECONDS) }
      if (failing.get()) throw new RuntimeException("delegate down")
      ProviderEvaluations.of(value, "STATIC")
    }
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      respond[java.lang.Boolean](java.lang.Boolean.TRUE)
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) = respond(default)
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      respond(default)
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      respond(default)
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) = respond(default)

    def error(): Unit  = { emitProviderError(ProviderEventDetails.builder().message("down").build()).await(); () }
    def stale(): Unit  = { emitProviderStale(ProviderEventDetails.builder().message("old").build()).await(); () }
    def marker(): Unit = { emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await(); () }
  }

  /** A clock that moves only when told to. Armed with `onFirstRead`, its next read runs that effect first. */
  private class ManualTicker extends Ticker {
    private val now                      = new AtomicReference[Long](0L)
    private val reentry                  = new AtomicReference[Option[() => Unit]](None)
    def advance(d: Duration): Unit       = { now.updateAndGet(_ + d.toNanos); () }
    def onFirstRead(f: () => Unit): Unit = reentry.set(Some(f))
    def armed: Boolean                   = reentry.get().isDefined
    override def nanos(): Long           = { reentry.getAndSet(None).foreach(_()); now.get() }
  }

  private val ctx = new ImmutableContext()

  // On the calling thread (no evaluation timeout), and no poll after the first unless a test asks for one.
  private val base = CircuitBreakerProviderConfig.default
    .withFailureThreshold(1)
    .withResetTimeout(30.seconds)
    .withEvaluationTimeout(Duration.Infinity)
    .withStateCheckInterval(1.hour)

  private def make(config: CircuitBreakerProviderConfig = base) = {
    val delegate = new Delegate
    val ticker   = new ManualTicker
    (delegate, ticker, CircuitBreakerProvider(delegate, config, ticker))
  }

  private def evaluate(cb: CircuitBreakerProvider): Either[String, Boolean] =
    scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)).toEither.left.map(_.getMessage).map(_.getValue)

  private def openFor(cause: String) = s"Circuit breaker rejected: open for 4210ms due to $cause, resets after 30000ms"

  def spec = suite("CircuitBreakerContractSpec")(
    suite("a rejected call names why the circuit is open")(
      test("consecutive failures") {
        val (delegate, ticker, cb) = make()
        delegate.failing.set(true)
        val _ = evaluate(cb)
        ticker.advance(4210.millis)
        assertTrue(evaluate(cb) == Left(openFor("consecutive failures")))
      },
      test("the delegate's PROVIDER_ERROR") {
        val (delegate, ticker, cb) = make()
        cb.initialize(ctx)
        delegate.error()
        ticker.advance(4210.millis)
        assertTrue(evaluate(cb) == Left(openFor("delegate reported unhealthy state")))
      },
      test("the delegate's PROVIDER_STALE") {
        val (delegate, ticker, cb) = make()
        cb.initialize(ctx)
        delegate.stale()
        ticker.advance(4210.millis)
        assertTrue(evaluate(cb) == Left(openFor("delegate reported STALE")))
      },
      test("a polled ERROR") {
        val (delegate, ticker, cb) = make()
        delegate.state.set(ProviderState.ERROR)
        val _ = evaluate(cb)
        ticker.advance(4210.millis)
        assertTrue(evaluate(cb) == Left(openFor("delegate state poll reported unhealthy")))
      },
      test("a polled STALE") {
        val (delegate, ticker, cb) = make()
        delegate.state.set(ProviderState.STALE)
        val _ = evaluate(cb)
        ticker.advance(4210.millis)
        assertTrue(evaluate(cb) == Left(openFor("delegate state poll reported STALE")))
      },
      test("a half-open probe in progress") {
        val (delegate, ticker, cb) = make()
        delegate.failing.set(true)
        val _ = evaluate(cb)
        delegate.failing.set(false)
        ticker.advance(31.seconds)
        val entered = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        delegate.hold.set(Some((entered, release)))
        for {
          probe    <- ZIO.attemptBlocking(evaluate(cb)).fork
          _        <- ZIO.attemptBlocking(entered.await(10, TimeUnit.SECONDS))
          rejected <- ZIO.succeed(evaluate(cb))
          _        <- ZIO.succeed(release.countDown())
          probed   <- probe.join
        } yield assertTrue(
          rejected == Left("Circuit breaker rejected: half-open, probe in progress"),
          probed == Right(true)
        )
      }
    ),
    suite("polling the delegate's state")(
      test("with a zero interval every call polls, even one whose poll slot a concurrent call took") {
        val (delegate, ticker, cb) = make(base.withStateCheckInterval(Duration.Zero))
        // The first clock read made during the outer call runs a second call to completion first.
        ticker.onFirstRead { () =>
          val _ = evaluate(cb)
        }
        val _ = evaluate(cb)
        if (ticker.armed) { val _ = evaluate(cb) }
        assertTrue(delegate.evaluations.get() == 2, delegate.polls.get() == 2)
      },
      test("a poll happens once exactly the interval has passed") {
        val (delegate, ticker, cb) = make(base.withStateCheckInterval(1.second))
        val _                      = evaluate(cb)
        ticker.advance(1.second)
        val _ = evaluate(cb)
        assertTrue(delegate.polls.get() == 2)
      },
      test("only a polled READY closes a circuit a poll opened") {
        val (delegate, ticker, cb) =
          make(base.withStateCheckInterval(1.second).withStalePolicy(StalePolicy.Ignore))
        delegate.state.set(ProviderState.ERROR)
        val tripped = evaluate(cb).isLeft
        delegate.state.set(ProviderState.NOT_READY)
        ticker.advance(2.seconds)
        val afterNotReady = evaluate(cb).isLeft
        delegate.state.set(ProviderState.STALE)
        ticker.advance(2.seconds)
        val afterIgnoredStale = evaluate(cb).isLeft
        delegate.state.set(ProviderState.READY)
        ticker.advance(2.seconds)
        assertTrue(tripped, afterNotReady, afterIgnoredStale, evaluate(cb) == Right(true))
      }
    ),
    test("a thrown failure below the threshold emits no PROVIDER_STALE; the one that opens it emits one") {
      val (delegate, _, cb)                  = make(base.withFailureThreshold(2))
      val seen                               = new ConcurrentLinkedQueue[ProviderEvent]()
      def stales                             = seen.toArray.count(_ == ProviderEvent.PROVIDER_STALE)
      def until(p: => Boolean, what: String) =
        ZIO.succeed(p).repeatUntil(identity).timeoutFail(new RuntimeException(what))(10.seconds)
      for {
        _ <- ZIO.succeed(EventProviderBridge.attach(cb, (e, _) => { seen.add(e); () }))
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- ZIO.succeed(delegate.failing.set(true))
        _ <- ZIO.succeed(evaluate(cb))
        // Delivered after anything the failure emitted, by the same lane: once it is seen, nothing else is coming.
        _ <- ZIO.succeed(delegate.marker())
        _ <- until(seen.contains(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED), "the marker never arrived")
        below = stales
        _ <- ZIO.succeed(evaluate(cb))
        _ <- until(stales > 0, "the opening failure emitted no PROVIDER_STALE")
      } yield assertTrue(below == 0, stales == 1)
    } @@ TestAspect.withLiveClock
  )
}
