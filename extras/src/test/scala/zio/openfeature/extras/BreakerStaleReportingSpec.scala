package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testkit.{Fault, FaultyProvider, Op}
import zio.openfeature.{FeatureFlags, ProviderStatus}

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicReference

// What an open circuit reports from getState: STALE when a STALE from the delegate opened it, ERROR otherwise.
object BreakerStaleReportingSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  final private class Settable extends EventProvider {
    val state        = new AtomicReference[ProviderState](ProviderState.READY)
    val failInit     = new AtomicReference[Option[RuntimeException]](None)
    val failEvaluate = new AtomicReference[Boolean](false)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "settable" }
    override def getState: ProviderState                    = state.get()
    override def initialize(ctx: OFEvaluationContext): Unit = failInit.get().foreach(e => throw e)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      if (failEvaluate.get()) throw new RuntimeException("down")
      else ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  private val polling = CircuitBreakerProviderConfig(stateCheckInterval = Duration.Zero, resetTimeout = 1.minute)

  private def rejected(cb: CircuitBreakerProvider): Boolean =
    scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)).isFailure

  private def emitted(delegate: Settable, event: OFProviderEvent): UIO[Unit] =
    ZIO.attemptBlocking(delegate.emit(event, ProviderEventDetails.builder().build()).await()).orDie

  def spec = suite("CircuitBreakerProvider open-circuit state")(
    test("a circuit a polled STALE opened reports STALE and rejects calls") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, polling)
      delegate.state.set(ProviderState.STALE)
      val wasRejected = rejected(cb)
      assertTrue(wasRejected, cb.getState == ProviderState.STALE)
    },
    test("a circuit a STALE event opened reports STALE") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute))
      for {
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_STALE)
      } yield assertTrue(rejected(cb), cb.getState == ProviderState.STALE)
    },
    test("a circuit an ERROR event opened reports ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute))
      for {
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_ERROR)
      } yield assertTrue(rejected(cb), cb.getState == ProviderState.ERROR)
    },
    test("an ERROR after a STALE opened the circuit makes it report ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute))
      for {
        _     <- ZIO.attemptBlocking(cb.initialize(ctx))
        _     <- emitted(delegate, OFProviderEvent.PROVIDER_STALE)
        stale <- ZIO.succeed(cb.getState)
        _     <- emitted(delegate, OFProviderEvent.PROVIDER_ERROR)
      } yield assertTrue(stale == ProviderState.STALE, cb.getState == ProviderState.ERROR)
    },
    test("a STALE after an ERROR opened the circuit leaves it reporting ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute))
      for {
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_ERROR)
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_STALE)
      } yield assertTrue(cb.getState == ProviderState.ERROR)
    },
    test("a polled ERROR after a polled STALE makes it report ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, polling)
      delegate.state.set(ProviderState.STALE)
      val _     = rejected(cb)
      val stale = cb.getState
      delegate.state.set(ProviderState.ERROR)
      val _ = rejected(cb)
      assertTrue(stale == ProviderState.STALE, cb.getState == ProviderState.ERROR)
    },
    test("a STALE event on a circuit a polled STALE opened keeps it reporting STALE") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, polling)
      for {
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- ZIO.succeed { delegate.state.set(ProviderState.STALE); rejected(cb) }
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_STALE)
      } yield assertTrue(cb.getState == ProviderState.STALE)
    },
    test("a STALE event on a circuit a polled ERROR opened leaves it reporting ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, polling)
      for {
        _ <- ZIO.attemptBlocking(cb.initialize(ctx))
        _ <- ZIO.succeed { delegate.state.set(ProviderState.ERROR); rejected(cb) }
        _ <- emitted(delegate, OFProviderEvent.PROVIDER_STALE)
      } yield assertTrue(cb.getState == ProviderState.ERROR)
    },
    test("a circuit consecutive failures opened reports ERROR") {
      val delegate = new Settable
      val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(failureThreshold = 2))
      delegate.failEvaluate.set(true)
      val _ = (1 to 3).map(_ => rejected(cb))
      assertTrue(cb.getState == ProviderState.ERROR)
    },
    test("a circuit a failed initialize opened reports ERROR") {
      val delegate = new Settable
      delegate.failInit.set(Some(new RuntimeException("cannot start")))
      val cb = CircuitBreakerProvider(delegate)
      val _  = scala.util.Try(cb.initialize(ctx))
      assertTrue(cb.getState == ProviderState.ERROR)
    },
    test("a sync build over a default breaker whose delegate reports STALE at startup succeeds with status Stale") {
      ZIO.scoped {
        for {
          inner <- FaultyProvider.scoped(Map("flag" -> true), name = "inner")
          _     <- inner.script(Op.Initialize, Fault.Emit(OFProviderEvent.PROVIDER_STALE))
          ff    <- FeatureFlags
            .build(
              CircuitBreakerProvider(inner),
              domain = Some(s"breaker-stale-${java.util.UUID.randomUUID()}"),
              version = None,
              initialHooks = Nil,
              statusRef = None,
              addShutdownFinalizer = true,
              apiOverride = Some(OpenFeatureAPI.createIsolated()),
              initTimeout = 5.seconds
            )
            .either
          status <- ff.fold(_ => ZIO.none, _.providerStatus.asSome)
        } yield assertTrue(ff.isRight, status.contains(ProviderStatus.Stale))
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
}
