package zio.openfeature.extras

import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI, ProviderEvent => OFProviderEvent, ProviderEventDetails}
import dev.openfeature.sdk.exceptions.{GeneralError, ProviderNotReadyError}
import zio._
import zio.openfeature._
import zio.openfeature.testkit.{Fault, FaultyProvider, Op}
import zio.openfeature.testsupport.PropertyBudget
import zio.openfeature.testsupport.LeakCheck.{awaitNoLeaks, snapshot}
import zio.test._

/** Generated wrapper stacks over a faulting provider.
  *
  * Each case stacks zero to three wrappers (cache, circuit breaker, deferred construction, a multi-provider chain) over
  * a [[FaultyProvider]], scripts faults into its initialization, its evaluations and its shutdown, and drives a real
  * `FeatureFlags` through them. Whatever the stack and the faults:
  *   - building either succeeds, or fails with a typed error where it may (see `buildMayFail`);
  *   - every `booleanOrDefault` returns, without a defect, within a bound of the evaluation timeout;
  *   - an error the provider reports reaches the status (unless a layer forwards no events: a multi-provider chain or a
  *     deferred provider), and once the faults stop and the provider reports ready, evaluations serve the flag and the
  *     status is `Ready`;
  *   - the scope closes in bounded time and leaves no fiber or non-daemon thread behind.
  */
object WrapperStackFaultSpec extends ZIOSpecDefault {

  sealed trait Layer
  object Layer {
    case object Cache    extends Layer
    case object Breaker  extends Layer
    case object Deferred extends Layer
    case object Multi    extends Layer
  }

  final case class Case(layers: List[Layer], init: Fault, evaluations: List[Fault], shutdown: Fault, calls: Int)

  private val EvaluationTimeout = 300.millis
  // The documented bound is the evaluation timeout; the rest is slack for a loaded CI machine.
  private val CallBound     = 3.seconds
  private val ConvergeBound = 5.seconds
  private val CloseBound    = 20.seconds
  private val LeakBound     = 5.seconds
  // How long an emitted event's handlers get to run before the next event, which the SDK might otherwise overtake.
  private val HandlerSettle = 200.millis

  private def event(e: OFProviderEvent, andThen: Fault = Fault.Succeed): Fault =
    Fault.Emit(e, ProviderEventDetails.builder().build(), andThen)

  private val genLayer: Gen[Any, Layer] = Gen.elements(Layer.Cache, Layer.Breaker, Layer.Deferred, Layer.Multi)

  private val genEvaluationFault: Gen[Any, Fault] = Gen.elements(
    Fault.Succeed,
    Fault.Throw(new GeneralError("boom")),
    Fault.Throw(new ProviderNotReadyError("not ready")),
    Fault.Throw(new RuntimeException("unexpected")),
    Fault.Delay(50.millis),
    Fault.Delay(1.second),
    Fault.HangUntilReleased,
    event(OFProviderEvent.PROVIDER_ERROR),
    event(OFProviderEvent.PROVIDER_STALE),
    event(OFProviderEvent.PROVIDER_READY),
    event(OFProviderEvent.PROVIDER_CONFIGURATION_CHANGED),
    event(OFProviderEvent.PROVIDER_ERROR, andThen = Fault.Throw(new GeneralError("boom"))),
    event(OFProviderEvent.PROVIDER_STALE, andThen = Fault.HangUntilReleased)
  )

  // Mostly a clean start, so most cases reach the evaluations; the rest fail, hang past the init timeout, or report an
  // event while starting.
  private val genInitFault: Gen[Any, Fault] = Gen.weighted(
    Gen.const[Fault](Fault.Succeed)                                                  -> 6.0,
    Gen.const[Fault](Fault.Delay(100.millis))                                        -> 1.0,
    Gen.const[Fault](event(OFProviderEvent.PROVIDER_STALE, andThen = Fault.Succeed)) -> 1.0,
    Gen.const[Fault](Fault.Throw(new GeneralError("init failed")))                   -> 1.0,
    Gen.const[Fault](Fault.HangUntilReleased)                                        -> 1.0
  )

  private val genShutdownFault: Gen[Any, Fault] = Gen.elements(
    Fault.Succeed,
    Fault.Throw(new RuntimeException("shutdown failed")),
    Fault.Delay(500.millis),
    Fault.HangUntilReleased
  )

  private val genCase: Gen[Any, Case] =
    for {
      layers      <- Gen.listOfBounded(0, 3)(genLayer)
      init        <- genInitFault
      evaluations <- Gen.listOfBounded(1, 6)(genEvaluationFault)
      shutdown    <- genShutdownFault
      calls       <- Gen.int(1, 6)
    } yield Case(layers, init, evaluations, shutdown, calls)

  private def wrap(provider: FeatureProvider, layer: Layer): UIO[FeatureProvider] = layer match {
    case Layer.Cache =>
      CachingProvider
        .make(provider, CachingConfig(ttl = 200.millis))
        .orDieWith(e => new IllegalArgumentException(e.message))
    case Layer.Breaker =>
      CircuitBreakerProvider
        .make(
          provider,
          CircuitBreakerProviderConfig(
            failureThreshold = 2,
            resetTimeout = 200.millis,
            evaluationTimeout = EvaluationTimeout
          )
        )
        .orDieWith(e => new IllegalArgumentException(e.message))
    case Layer.Deferred => ZIO.succeed(DeferredProvider("deferred")(() => provider))
    case Layer.Multi    => ZIO.succeed(FeatureFlags.multiProvider(List(provider)))
  }

  /** Poll `check` until it holds or `within` passes. A plain deadline loop: `timeout` would fork root fibers. */
  private def eventually(within: Duration)(check: UIO[Boolean]): UIO[Boolean] = {
    def loop(deadline: Long): UIO[Boolean] =
      check.flatMap { ok =>
        if (ok) ZIO.succeed(true)
        else Clock.nanoTime.flatMap(now => if (now >= deadline) ZIO.succeed(false) else loop(deadline).delay(50.millis))
      }
    Clock.nanoTime.flatMap(now => loop(now + within.toNanos))
  }

  /** Whether a layer passes its delegate's events on: the SDK's MultiProvider and DeferredProvider do not. */
  private def forwardsEvents(layer: Layer): Boolean = layer == Layer.Cache || layer == Layer.Breaker

  /** Whether the build may fail: initialization fails or hangs past the init timeout. A `STALE` at startup does not
    * fail it, even where a circuit breaker sees it and opens: an open circuit reports the `STALE` that opened it.
    */
  private def buildMayFail(c: Case): Boolean = c.init match {
    case _: Fault.Throw | Fault.HangUntilReleased => true
    case _                                        => false
  }

  private def drive(c: Case): ZIO[Scope, Nothing, Chunk[String]] =
    for {
      inner <- FaultyProvider.scoped(Map("flag" -> true), name = "inner")
      _     <- inner.script(Op.Initialize, c.init)
      _     <- inner.script(Op.Shutdown, c.shutdown)
      stack <- ZIO.foldLeft(c.layers)(inner: FeatureProvider)(wrap)
      built <- FeatureFlags
        .build(
          stack,
          domain = Some("stack"),
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated()),
          evaluationTimeout = Some(EvaluationTimeout),
          initTimeout = 2.seconds
        )
        .exit
      found <- built match {
        case Exit.Success(ff)    => evaluate(c, inner, ff)
        case Exit.Failure(cause) =>
          ZIO.succeed(
            if (cause.isDie) Chunk(s"building died: ${cause.prettyPrint}")
            else if (!buildMayFail(c)) Chunk(s"building failed although it should not: ${cause.prettyPrint}")
            else Chunk.empty
          )
      }
    } yield found

  /** Emit `event` from the provider, wait until the SDK has taken it, then give its handlers a moment to run. */
  private def emitAndWait(inner: FaultyProvider[_], event: OFProviderEvent): UIO[Unit] =
    ZIO.attemptBlocking(inner.emit(event, ProviderEventDetails.builder().build()).await()).orDie *>
      ZIO.sleep(HandlerSettle)

  private def evaluate(c: Case, inner: FaultyProvider[_], ff: FeatureFlags): UIO[Chunk[String]] =
    for {
      _     <- inner.script(Op.EvaluateBoolean, c.evaluations: _*)
      calls <- ZIO.foreach(Chunk.fromIterable(1 to c.calls)) { i =>
        ff.booleanOrDefault("flag", default = false).exit.timed.map { case (took, exit) =>
          Chunk.fromIterable(
            exit.causeOption.map(cause => s"call $i did not return a value: ${cause.prettyPrint}") ++
              (if (took > CallBound) Some(s"call $i took ${took.toMillis} ms, over $CallBound") else None)
          )
        }
      }
      // The faults stop: later calls delegate, and parked calls are let go.
      _ <- inner.script(Op.EvaluateBoolean)
      _ <- inner.release
      // An error the provider reports reaches the status through every wrapper, so the recovery below is a real one.
      // Not past a layer that forwards no events: the SDK's MultiProvider forwards no child event, and
      // DeferredProvider documents that it does not forward its delegate's.
      //
      // The SDK hands a provider's events to their handlers on a thread pool, in no fixed order, so two events emitted
      // back to back can land in either order and leave the status on the first. Each event here is therefore emitted
      // only once the one before it has been delivered and handled.
      _     <- emitAndWait(inner, OFProviderEvent.PROVIDER_ERROR)
      erred <-
        if (!c.layers.forall(forwardsEvents)) ZIO.succeed(true)
        else eventually(ConvergeBound)(ff.providerStatus.map(_ == ProviderStatus.Error))
      // Then it reports it is ready again.
      _      <- emitAndWait(inner, OFProviderEvent.PROVIDER_READY)
      served <- eventually(ConvergeBound)(ff.booleanOrDefault("flag", default = false))
      ready  <- eventually(ConvergeBound)(ff.providerStatus.map(_ == ProviderStatus.Ready))
      status <- ff.providerStatus
    } yield calls.flatten ++
      (if (erred) Chunk.empty else Chunk("a PROVIDER_ERROR from the provider did not reach the status")) ++
      (if (served) Chunk.empty else Chunk(s"the flag was not served within $ConvergeBound of the faults stopping")) ++
      (if (ready) Chunk.empty else Chunk(s"the status stayed $status, not Ready, after the faults stopped"))

  /** Run one case; returns every violation, including a slow scope close and leaks after it. */
  def runCase(c: Case): UIO[Chunk[String]] =
    for {
      baseline <- snapshot
      scope    <- Scope.make
      found    <- drive(c).provideEnvironment(ZEnvironment[Scope](scope))
      closed   <- scope.close(Exit.unit).timed.timeout(CloseBound)
      closing = closed match {
        case None    => Chunk(s"scope close did not complete within $CloseBound")
        case Some(_) => Chunk.empty
      }
      leaked <- awaitNoLeaks(baseline, LeakBound)
    } yield found ++ closing ++ Chunk.fromIterable(leaked).map(l => s"after scope close: $l")

  private def report(c: Case, found: Chunk[String]): String =
    if (found.isEmpty) "" else (s"case: $c" +: found).mkString("\n")

  def spec = suite("WrapperStackFaultSpec")(
    test("faults in the innermost provider never escape a generated wrapper stack") {
      check(genCase)(c => runCase(c).map(found => assertTrue(report(c, found) == "")))
    } @@ PropertyBudget(8) @@ TestAspect.shrinks(10) @@ PropertyBudget.timeout(5.minutes)
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
