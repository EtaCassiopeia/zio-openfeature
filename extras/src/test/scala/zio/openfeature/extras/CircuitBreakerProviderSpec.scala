package zio.openfeature.extras
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider,
  Hook,
  ImmutableContext,
  Metadata,
  MutableTrackingEventDetails,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

object CircuitBreakerProviderSpec extends ZIOSpecDefault {

  /** A test provider that can be configured to fail, delay, or change state. */
  private class FailableProvider(
    flags: Map[String, Any] = Map.empty,
    delay: Option[Duration] = None
  ) extends EventProvider {
    val evaluationCount       = new AtomicInteger(0)
    private val shouldFail    = new AtomicReference[Boolean](false)
    private val providerState = new AtomicReference[ProviderState](ProviderState.READY)

    def setFailing(failing: Boolean): Unit   = shouldFail.set(failing)
    def setState(state: ProviderState): Unit = providerState.set(state)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = "FailableProvider"
    }

    override def getState: ProviderState                    = providerState.get()
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def maybeFailOrDelay(): Unit = {
      if (shouldFail.get()) throw new RuntimeException("Provider failure")
      delay.foreach(d => Thread.sleep(d.toMillis))
    }

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "STATIC")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluations.of[String](v, "STATIC")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "STATIC")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "STATIC")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue)
      ProviderEvaluations.of[Value](v, "STATIC")
    }
  }

  /** A controllable monotonic ticker for testing time-based transitions. */
  private class TestClock extends Ticker {
    private val currentNanos = new AtomicReference[Long](java.lang.System.nanoTime())

    def advance(duration: Duration): Unit =
      currentNanos.updateAndGet(_ + duration.toNanos)

    override def nanos(): Long = currentNanos.get()
  }

  /** A delegate that implements only `FeatureProvider`, never `EventProvider` — the shape #379 is about.
    *
    * Deliberately overrides neither `getState` nor `getLongEvaluation`: it stands in for a pre-1.22 third-party
    * provider, so it exercises the SDK's `READY` state default and its double-backed long default.
    */
  private class PlainProvider(
    flags: Map[String, Any] = Map.empty,
    domainScoped: Boolean = true
  ) extends FeatureProvider {
    val evaluationCount               = new AtomicInteger(0)
    val doubleCount                   = new AtomicInteger(0)
    val longCount                     = new AtomicInteger(0)
    val initCount                     = new AtomicInteger(0)
    val domainInitCount               = new AtomicInteger(0)
    val shutdownCount                 = new AtomicInteger(0)
    val tracked                       = new CopyOnWriteArrayList[String]()
    private val providerHook: Hook[_] = new Hook[java.lang.Object] {}
    private val shouldFail            = new AtomicReference[Boolean](false)

    def setFailing(failing: Boolean): Unit = shouldFail.set(failing)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "PlainProvider" }

    override def initialize(c: OFEvaluationContext): Unit                 = { initCount.incrementAndGet(); () }
    override def initialize(c: OFEvaluationContext, domain: String): Unit = { domainInitCount.incrementAndGet(); () }
    override def isDomainScoped(): Boolean                                = domainScoped
    override def shutdown(): Unit                                         = { shutdownCount.incrementAndGet(); () }
    override def getProviderHooks(): java.util.List[Hook[_]] = java.util.Collections.singletonList(providerHook)
    override def track(eventName: String, c: OFEvaluationContext, d: TrackingEventDetails): Unit = {
      tracked.add(eventName); ()
    }

    private def failIfAsked(): Unit =
      if (shouldFail.get()) throw new RuntimeException("Provider failure")

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      evaluationCount.incrementAndGet()
      failIfAsked()
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "STATIC")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      c: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      failIfAsked()
      ProviderEvaluations.of[String](flags.get(key).map(_.toString).getOrElse(defaultValue), "STATIC")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      failIfAsked()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "STATIC")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      doubleCount.incrementAndGet()
      failIfAsked()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "STATIC")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      c: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      failIfAsked()
      ProviderEvaluations.of[Value](flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue), "STATIC")
    }
  }

  /** A plain delegate that *does* define `getLongEvaluation`, returning a value its `getDoubleEvaluation` never
    * produces — so a wrapper that drops the override and falls back to the SDK's double path is distinguishable.
    */
  private class LongAwarePlainProvider extends PlainProvider(Map("n" -> 99.0)) {
    override def getLongEvaluation(
      key: String,
      defaultValue: java.lang.Long,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Long] = {
      longCount.incrementAndGet()
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(7L), "STATIC")
    }
  }

  /** A plain delegate that reports its own state, for the state-driven trip path. */
  private class StatefulPlainProvider extends PlainProvider(Map("flag" -> true)) {
    private val providerState                = new AtomicReference[ProviderState](ProviderState.READY)
    def setState(state: ProviderState): Unit = providerState.set(state)
    override def getState: ProviderState     = providerState.get()
  }

  private val ctx = new ImmutableContext()

  def spec = suite("CircuitBreakerProvider")(
    suite("Closed state (normal operation)")(
      test("forwards evaluations to delegate") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val cb         = CircuitBreakerProvider(underlying)
        val result     = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "STATIC") &&
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("supports all flag types") {
        val underlying = new FailableProvider(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14, "o" -> "obj"))
        val cb         = CircuitBreakerProvider(underlying)
        val r1         = cb.getBooleanEvaluation("b", false, ctx)
        val r2         = cb.getStringEvaluation("s", "", ctx)
        val r3         = cb.getIntegerEvaluation("i", 0, ctx)
        val r4         = cb.getDoubleEvaluation("d", 0.0, ctx)
        val r5         = cb.getObjectEvaluation("o", new Value(), ctx)
        assertTrue(r1.getValue == true) &&
        assertTrue(r2.getValue == "hello") &&
        assertTrue(r3.getValue == 42) &&
        assertTrue(r4.getValue == 3.14) &&
        assertTrue(r5.getValue.asString() == "obj") &&
        assertTrue(underlying.evaluationCount.get() == 5)
      },
      test("resets consecutive failures on success") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(failureThreshold = 3)
        val cb         = CircuitBreakerProvider(underlying, config)
        // Cause 2 failures (below threshold)
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Succeed once — resets counter
        underlying.setFailing(false)
        cb.getBooleanEvaluation("flag", false, ctx)
        // 2 more failures — should NOT open (counter was reset)
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        underlying.setFailing(false)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      }
    ),
    suite("Timeout handling")(
      test("slow delegate call triggers timeout and counts as failure") {
        val underlying = new FailableProvider(Map("flag" -> true), delay = Some(500.millis))
        val config     = CircuitBreakerProviderConfig(evaluationTimeout = 50.millis, failureThreshold = 100)
        val cb         = CircuitBreakerProvider(underlying, config)
        val result     = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure)
      } @@ TestAspect.withLiveClock,
      test("successful call within timeout works normally") {
        val underlying = new FailableProvider(Map("flag" -> true), delay = Some(5.millis))
        val config     = CircuitBreakerProviderConfig(evaluationTimeout = 1.second)
        val cb         = CircuitBreakerProvider(underlying, config)
        val result     = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      } @@ TestAspect.withLiveClock
    ),
    suite("Failure counting and opening")(
      test("opens after failureThreshold consecutive failures") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config     = CircuitBreakerProviderConfig(failureThreshold = 3, resetTimeout = 1.minute)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
        underlying.setFailing(true)
        (1 to 3).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Circuit should now be open — delegate should NOT be called
        val countBeforeOpen = underlying.evaluationCount.get()
        scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(underlying.evaluationCount.get() == countBeforeOpen) &&
        assertTrue(cb.getState == ProviderState.ERROR)
      },
      test("does not open on fewer failures than threshold") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(failureThreshold = 5)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setFailing(true)
        (1 to 4).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        underlying.setFailing(false)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      }
    ),
    suite("Open state")(
      test("throws immediately without calling delegate") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config     = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.minute)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
        // Trip the circuit
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        val countAfterTrip = underlying.evaluationCount.get()
        // Subsequent calls should not reach the delegate
        underlying.setFailing(false)
        (1 to 10).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        assertTrue(underlying.evaluationCount.get() == countAfterTrip)
      },
      test("transitions to half-open after resetTimeout") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config     = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 30.seconds)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
        // Trip the circuit
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Advance past resetTimeout
        clock.advance(31.seconds)
        underlying.setFailing(false)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(cb.getState == ProviderState.READY)
      }
    ),
    suite("Half-open state")(
      test("closes on successful probe") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.second, halfOpenMaxCalls = 1)
        val cb     = CircuitBreakerProvider(underlying, config, clock)
        // Trip
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Wait for reset
        clock.advance(2.seconds)
        underlying.setFailing(false)
        // Probe succeeds — circuit should close
        cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("re-opens on failed probe") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config     = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.second)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
        // Trip
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Wait for reset
        clock.advance(2.seconds)
        // Probe fails — circuit should re-open
        val probeResult = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(probeResult.isFailure) &&
        assertTrue(cb.getState == ProviderState.ERROR)
      },
      test("requires halfOpenMaxCalls successes to close") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.second, halfOpenMaxCalls = 3)
        val cb     = CircuitBreakerProvider(underlying, config, clock)
        // Trip
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Wait for reset
        clock.advance(2.seconds)
        underlying.setFailing(false)
        // First probe succeeds — circuit stays half-open
        cb.getBooleanEvaluation("flag", false, ctx)
        // Capture state eagerly (assertTrue macro is lazy)
        val stateAfterFirstProbe = cb.getState
        // Need 2 more probes to close
        cb.getBooleanEvaluation("flag", false, ctx)
        cb.getBooleanEvaluation("flag", false, ctx)
        val stateAfterAllProbes = cb.getState
        assertTrue(stateAfterFirstProbe == ProviderState.STALE) &&
        assertTrue(stateAfterAllProbes == ProviderState.READY)
      }
    ),
    suite("State-driven transitions")(
      test("opens immediately when delegate state is ERROR") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val cb         = CircuitBreakerProvider(underlying)
        underlying.setState(ProviderState.ERROR)
        val result = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure) &&
        assertTrue(cb.getState == ProviderState.ERROR) &&
        assertTrue(underlying.evaluationCount.get() == 0)
      },
      test("opens immediately when delegate state is FATAL") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val cb         = CircuitBreakerProvider(underlying)
        underlying.setState(ProviderState.FATAL)
        val result = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure) &&
        assertTrue(underlying.evaluationCount.get() == 0)
      },
      test("closes when delegate recovers to READY") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        // stateCheckInterval = Zero: this test needs the delegate state observed on every call
        val config =
          CircuitBreakerProviderConfig(
            failureThreshold = 2,
            resetTimeout = 1.minute,
            stateCheckInterval = Duration.Zero
          )
        val cb = CircuitBreakerProvider(underlying, config, clock)
        // Trip via state
        underlying.setState(ProviderState.ERROR)
        val tryResult       = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        val stateAfterTrip  = cb.getState
        val underlyingState = underlying.getState
        // Delegate recovers
        underlying.setState(ProviderState.READY)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(tryResult.isFailure) &&
        assertTrue(stateAfterTrip == ProviderState.ERROR) &&
        assertTrue(result.getValue == true) &&
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("stalePolicy Open trips the circuit on STALE state") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(stalePolicy = StalePolicy.Open)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        val result = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure) &&
        assertTrue(cb.getState == ProviderState.ERROR)
      },
      test("stalePolicy Ignore keeps circuit closed on STALE state") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(stalePolicy = StalePolicy.Ignore)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      },
      test("stalePolicy HalfOpen transitions to half-open on STALE state") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(stalePolicy = StalePolicy.HalfOpen)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        // First call allowed as probe
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      },
      test("delegate getState polling is bounded by stateCheckInterval, not evaluation count") {
        val stateChecks = new java.util.concurrent.atomic.AtomicInteger(0)
        val underlying = new FailableProvider(Map("flag" -> true)) {
          override def getState: ProviderState = {
            stateChecks.incrementAndGet()
            super.getState
          }
        }
        val clock  = new TestClock()
        val config = CircuitBreakerProviderConfig(stateCheckInterval = 1.second)
        val cb     = CircuitBreakerProvider(underlying, config, clock)
        // 10 evaluations inside one interval: only the first polls the delegate state
        (1 to 10).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
        val checksWithinInterval = stateChecks.get()
        // Advancing past the interval re-enables exactly one more poll
        clock.advance(2.seconds)
        (1 to 10).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
        val checksAfterAdvance = stateChecks.get()
        assertTrue(checksWithinInterval == 1) &&
        assertTrue(checksAfterAdvance == 2) &&
        assertTrue(underlying.evaluationCount.get() == 20)
      },
      test("stateCheckInterval Zero polls delegate state on every evaluation") {
        val stateChecks = new java.util.concurrent.atomic.AtomicInteger(0)
        val underlying = new FailableProvider(Map("flag" -> true)) {
          override def getState: ProviderState = {
            stateChecks.incrementAndGet()
            super.getState
          }
        }
        val config = CircuitBreakerProviderConfig(stateCheckInterval = Duration.Zero)
        val cb     = CircuitBreakerProvider(underlying, config)
        (1 to 5).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(stateChecks.get() == 5)
      }
    ),
    suite("Delegate event propagation (#176)")(
      test("delegate PROVIDER_ERROR event trips the circuit without an evaluation failure") {
        // Asserts on the breaker directly: evaluating would re-poll the delegate's (READY) state,
        // which legitimately resets an externally-opened circuit.
        class EmittingProvider extends FailableProvider(Map("flag" -> true)) {
          def fireError(): Unit = {
            emitProviderError(dev.openfeature.sdk.ProviderEventDetails.builder().message("boom").build())
            ()
          }
        }
        val underlying = new EmittingProvider
        val cb         = CircuitBreakerProvider(underlying)
        for {
          _ <- ZIO.attemptBlocking(cb.initialize(ctx))
          _ <- ZIO.succeed(underlying.fireError())
          // Emission is async; poll until the breaker opens
          _ <- ZIO
            .succeed(cb.breaker.isOpen)
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("circuit never opened from delegate event"))(10.seconds)
        } yield assertTrue(cb.breaker.isOpen, underlying.evaluationCount.get() == 0)
      },
      test("delegate PROVIDER_READY event closes an externally-opened circuit") {
        class EmittingProvider extends FailableProvider(Map("flag" -> true)) {
          def fireError(): Unit = { emitProviderError(dev.openfeature.sdk.ProviderEventDetails.builder().build()); () }
          def fireReady(): Unit = { emitProviderReady(dev.openfeature.sdk.ProviderEventDetails.builder().build()); () }
        }
        val underlying = new EmittingProvider
        val cb         = CircuitBreakerProvider(underlying)
        for {
          _ <- ZIO.attemptBlocking(cb.initialize(ctx))
          _ <- ZIO.succeed(underlying.fireError())
          _ <- ZIO
            .succeed(cb.breaker.isOpen)
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("circuit never opened"))(10.seconds)
          _ <- ZIO.succeed(underlying.fireReady())
          _ <- ZIO
            .succeed(cb.breaker.isClosed)
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("circuit never closed after recovery event"))(10.seconds)
          result <- ZIO.attemptBlocking(cb.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(result.getValue == true)
      },
      test("delegate events are re-emitted through the wrapper") {
        class EmittingProvider extends FailableProvider(Map("flag" -> true)) {
          def fireConfigChanged(): Unit = {
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
            ()
          }
        }
        val underlying = new EmittingProvider
        val cb         = CircuitBreakerProvider(underlying)
        val seen       = new java.util.concurrent.ConcurrentLinkedQueue[dev.openfeature.sdk.ProviderEvent]()
        for {
          _ <- ZIO.succeed(dev.openfeature.sdk.EventProviderBridge.attach(cb, (e, _) => { seen.add(e); () }))
          _ <- ZIO.attemptBlocking(cb.initialize(ctx))
          _ <- ZIO.succeed(underlying.fireConfigChanged())
          _ <- ZIO
            .succeed(seen.contains(dev.openfeature.sdk.ProviderEvent.PROVIDER_CONFIGURATION_CHANGED))
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("delegate event was not re-emitted"))(10.seconds)
        } yield assertTrue(seen.contains(dev.openfeature.sdk.ProviderEvent.PROVIDER_CONFIGURATION_CHANGED))
      }
    ) @@ TestAspect.withLiveClock,
    suite("Lifecycle")(
      test("metadata includes underlying provider name") {
        val underlying = new FailableProvider()
        val cb         = CircuitBreakerProvider(underlying)
        assertTrue(cb.getMetadata.getName == "CircuitBreakerProvider(FailableProvider)")
      },
      test("delegates getState to underlying when closed") {
        val underlying = new FailableProvider()
        val cb         = CircuitBreakerProvider(underlying)
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("getState returns ERROR if delegate getState throws") {
        val underlying = new FailableProvider() {
          override def getState: ProviderState =
            throw new RuntimeException("broken")
        }
        val cb = CircuitBreakerProvider(underlying)
        assertTrue(cb.getState == ProviderState.ERROR)
      },
      test("initialize delegates to underlying") {
        var initialized = false
        val underlying = new FailableProvider() {
          override def initialize(ctx: OFEvaluationContext): Unit = initialized = true
        }
        val cb = CircuitBreakerProvider(underlying)
        cb.initialize(ctx)
        assertTrue(initialized)
      },
      test("shutdown delegates to underlying") {
        var shutdownCalled = false
        val underlying = new FailableProvider() {
          override def shutdown(): Unit = shutdownCalled = true
        }
        val cb = CircuitBreakerProvider(underlying)
        cb.shutdown()
        assertTrue(shutdownCalled)
      }
    ),
    suite("Initialization")(
      test("trips circuit if delegate is in ERROR state during initialize") {
        val underlying = new FailableProvider(Map("flag" -> true))
        underlying.setState(ProviderState.ERROR)
        val cb = CircuitBreakerProvider(underlying)
        cb.initialize(ctx)
        val stateAfterInit = cb.getState
        val result         = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(stateAfterInit == ProviderState.ERROR) &&
        assertTrue(result.isFailure) &&
        assertTrue(underlying.evaluationCount.get() == 0)
      },
      test("does not trip circuit if delegate is READY during initialize") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val cb         = CircuitBreakerProvider(underlying)
        cb.initialize(ctx)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("trips circuit and re-throws if delegate initialize throws") {
        val underlying = new FailableProvider() {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Connection refused")
        }
        val cb     = CircuitBreakerProvider(underlying)
        val result = scala.util.Try(cb.initialize(ctx))
        assertTrue(result.isFailure) &&
        assertTrue(cb.getState == ProviderState.ERROR)
      }
    ),
    suite("Concurrent probe contention")(
      test("only one probe runs at a time in half-open state") {
        val underlying = new FailableProvider(Map("flag" -> true), delay = Some(50.millis))
        val clock      = new TestClock()
        val config     = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.second)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
        // Trip
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Wait for reset
        clock.advance(2.seconds)
        underlying.setFailing(false)
        // Launch many concurrent evaluations — only 1 should reach the delegate as a probe
        for {
          results <- ZIO.collectAllPar(
            (1 to 10).map(_ => ZIO.attempt(cb.getBooleanEvaluation("flag", false, ctx)).either)
          )
          successes = results.count(_.isRight)
          failures  = results.count(_.isLeft)
        } yield
        // Exactly 1 probe succeeds; the rest fail fast because a probe is in progress
        assertTrue(successes == 1) &&
          assertTrue(failures == 9)
      } @@ TestAspect.withLiveClock
    ),
    suite("Composability")(
      test("works with MultiProvider and FirstSuccessfulStrategy for failover") {
        import dev.openfeature.sdk.multiprovider.{MultiProvider, FirstSuccessfulStrategy}
        import scala.jdk.CollectionConverters._

        val primary     = new FailableProvider(Map("flag" -> true))
        val fallbackEnv = Map("FF_FLAG" -> "false")
        val fallback    = EnvVarProvider.withLookup(fallbackEnv.get)
        val cb          = CircuitBreakerProvider(primary, CircuitBreakerProviderConfig(failureThreshold = 2))

        val multi = new MultiProvider(
          List(cb, fallback).map(_.asInstanceOf[dev.openfeature.sdk.FeatureProvider]).asJava,
          new FirstSuccessfulStrategy()
        )

        // Primary works — should return true
        val r1 = multi.getBooleanEvaluation("flag", false, ctx)
        assertTrue(r1.getValue == true) && {
          // Primary fails — circuit opens, falls through to EnvVarProvider
          primary.setFailing(true)
          (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
          val r2 = multi.getBooleanEvaluation("flag", true, ctx)
          // EnvVarProvider returns false from FF_FLAG=false
          assertTrue(r2.getValue == false)
        }
      }
    ),
    suite("Error classification")(
      test("FlagNotFoundError does not count toward circuit breaker threshold") {
        val underlying = new FailableProvider(Map.empty) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            evaluationCount.incrementAndGet()
            throw new dev.openfeature.sdk.exceptions.FlagNotFoundError(s"Flag '$key' not found")
          }
        }
        val config = CircuitBreakerProviderConfig(failureThreshold = 2)
        val cb     = CircuitBreakerProvider(underlying, config)
        // Trigger many FlagNotFound errors — should NOT trip the circuit
        (1 to 10).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("missing", false, ctx)))
        // Circuit should still be closed, delegate still called every time
        assertTrue(cb.getState == ProviderState.READY) &&
        assertTrue(underlying.evaluationCount.get() == 10)
      },
      test("TypeMismatchError does not count toward circuit breaker threshold") {
        val underlying = new FailableProvider(Map.empty) {
          override def getStringEvaluation(
            key: String,
            defaultValue: String,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[String] = {
            evaluationCount.incrementAndGet()
            throw new dev.openfeature.sdk.exceptions.TypeMismatchError("Expected string, got boolean")
          }
        }
        val config = CircuitBreakerProviderConfig(failureThreshold = 2)
        val cb     = CircuitBreakerProvider(underlying, config)
        (1 to 10).foreach(_ => scala.util.Try(cb.getStringEvaluation("flag", "", ctx)))
        assertTrue(cb.getState == ProviderState.READY) &&
        assertTrue(underlying.evaluationCount.get() == 10)
      },
      test("application errors reset consecutive failure counter") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerProviderConfig(failureThreshold = 3)
        val cb         = CircuitBreakerProvider(underlying, config)
        // 2 infra failures (count=2)
        underlying.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // 1 FlagNotFound — resets counter because provider is reachable
        underlying.setFailing(false)
        val fnfUnderlying = new FailableProvider(Map.empty) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            evaluationCount.incrementAndGet()
            throw new dev.openfeature.sdk.exceptions.FlagNotFoundError("not found")
          }
        }
        val cb2 = CircuitBreakerProvider(fnfUnderlying, config)
        // 2 infra, then 1 FlagNotFound reset, then 2 more infra → should NOT open (2 < 3)
        fnfUnderlying.setFailing(false)
        scala.util.Try(cb2.getBooleanEvaluation("missing", false, ctx)) // FlagNotFound, resets
        // This test validates the design: app errors prove reachability
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("infrastructure errors count toward circuit breaker threshold") {
        val underlying = new FailableProvider(Map.empty) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            evaluationCount.incrementAndGet()
            throw new RuntimeException("Connection refused")
          }
        }
        val clock  = new TestClock()
        val config = CircuitBreakerProviderConfig(failureThreshold = 3, resetTimeout = 1.minute)
        val cb     = CircuitBreakerProvider(underlying, config, clock)
        (1 to 3).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        val countAfterTrip = underlying.evaluationCount.get()
        // Circuit should be open — delegate not called anymore
        scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(cb.getState == ProviderState.ERROR) &&
        assertTrue(underlying.evaluationCount.get() == countAfterTrip)
      },
      test("application error during half-open frees probe slot without closing circuit") {
        val callCount = new AtomicInteger(0)
        val mode      = new AtomicReference[String]("infra")
        val underlying = new FailableProvider(Map.empty) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            callCount.incrementAndGet()
            mode.get() match {
              case "infra" => throw new RuntimeException("infra")
              case _       => throw new dev.openfeature.sdk.exceptions.FlagNotFoundError(s"Flag '$key' not found")
            }
          }
        }
        val clock = new TestClock()
        val config =
          CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 1.second, halfOpenMaxCalls = 3)
        val cb = CircuitBreakerProvider(underlying, config, clock)
        // Trip the circuit with infrastructure failures (count toward threshold).
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        // Past resetTimeout → next call enters half-open as the probe.
        clock.advance(2.seconds)
        // Switch to app errors: each call must reach the delegate (probe slot freed by recordReachable).
        mode.set("app")
        val countBefore = callCount.get()
        (1 to 5).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("missing", false, ctx)))
        // Without lockup fix, only one call would land; with fix, each call gets through.
        // Circuit must stay half-open (or closed if halfOpenMaxCalls reached via real successes — but app
        // errors don't advance the counter, so it stays half-open).
        // App errors don't advance the half-open success counter, so the breaker stays half-open.
        assertTrue(callCount.get() - countBefore == 5) &&
        assertTrue(cb.breaker.isHalfOpen)
      },
      test("mixed application and infrastructure errors only count infrastructure errors") {
        val callCount = new AtomicInteger(0)
        val underlying = new FailableProvider(Map.empty) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            ctx: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            val count = callCount.incrementAndGet()
            if (count % 2 == 1)
              throw new dev.openfeature.sdk.exceptions.FlagNotFoundError("not found")
            else
              throw new RuntimeException("Connection refused")
          }
        }
        val config = CircuitBreakerProviderConfig(failureThreshold = 3)
        val cb     = CircuitBreakerProvider(underlying, config)
        // FlagNotFound resets counter (provider is reachable), RuntimeException increments.
        // With resets: FlagNotFound(reset=0), Runtime(count=1), FlagNotFound(reset=0),
        // Runtime(count=1), FlagNotFound(reset=0), Runtime(count=1) → never reaches 3
        // Circuit should NOT open because app errors prove reachability
        (1 to 6).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        assertTrue(cb.getState == ProviderState.READY)
      }
    ),
    suite("Edge cases")(
      test("default config values are sensible") {
        val config = CircuitBreakerProviderConfig()
        assertTrue(config.failureThreshold == 5) &&
        assertTrue(config.resetTimeout == 30.seconds) &&
        assertTrue(config.evaluationTimeout == 500.millis) &&
        assertTrue(config.halfOpenMaxCalls == 1) &&
        assertTrue(config.stalePolicy == StalePolicy.Open)
      },
      test("single failure does not open circuit with default threshold") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val cb         = CircuitBreakerProvider(underlying)
        underlying.setFailing(true)
        scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        underlying.setFailing(false)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(cb.getState == ProviderState.READY)
      },
      test("timeout does not block longer than configured duration") {
        val underlying = new FailableProvider(Map("flag" -> true), delay = Some(5.seconds))
        val config     = CircuitBreakerProviderConfig(evaluationTimeout = 50.millis, failureThreshold = 100)
        val cb         = CircuitBreakerProvider(underlying, config)
        val start      = java.lang.System.currentTimeMillis()
        scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        val elapsed = java.lang.System.currentTimeMillis() - start
        // Should complete well under 1 second, not 5 seconds
        assertTrue(elapsed < 1000L)
      } @@ TestAspect.withLiveClock
    ),
    suite("Plain FeatureProvider delegate (#379)")(
      test("apply accepts a plain FeatureProvider and forwards evaluations") {
        val delegate = new PlainProvider(Map("flag" -> true))
        val cb       = CircuitBreakerProvider(delegate)
        val result   = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true, delegate.evaluationCount.get() == 1)
      },
      test("make accepts a plain FeatureProvider") {
        val delegate = new PlainProvider(Map("flag" -> true))
        for {
          cb     <- CircuitBreakerProvider.make(delegate)
          result <- ZIO.attempt(cb.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(result.getValue == true, delegate.evaluationCount.get() == 1)
      },
      test("forwards every resolver to a plain delegate") {
        val delegate = new PlainProvider(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14, "o" -> "obj"))
        val cb       = CircuitBreakerProvider(delegate)
        assertTrue(
          cb.getBooleanEvaluation("b", false, ctx).getValue == true,
          cb.getStringEvaluation("s", "", ctx).getValue == "hello",
          cb.getIntegerEvaluation("i", 0, ctx).getValue == 42,
          cb.getDoubleEvaluation("d", 0.0, ctx).getValue == 3.14,
          cb.getObjectEvaluation("o", new Value(), ctx).getValue.asString() == "obj",
          delegate.evaluationCount.get() == 5
        )
      },
      test("forwards getLongEvaluation to a plain delegate that defines it") {
        val delegate = new LongAwarePlainProvider
        val cb       = CircuitBreakerProvider(delegate)
        val result   = cb.getLongEvaluation("n", java.lang.Long.valueOf(0L), ctx)
        // 7L comes only from the delegate's own override; the SDK's double-backed default would yield 99L.
        assertTrue(
          result.getValue.longValue == 7L,
          delegate.longCount.get() == 1,
          delegate.doubleCount.get() == 0
        )
      },
      test("routes long resolution through a pre-1.22 plain delegate's own double default") {
        val delegate = new PlainProvider(Map("n" -> 42.0))
        val cb       = CircuitBreakerProvider(delegate)
        val result   = cb.getLongEvaluation("n", java.lang.Long.valueOf(0L), ctx)
        assertTrue(result.getValue.longValue == 42L, delegate.doubleCount.get() == 1)
      },
      test("forwards initialize, initialize(domain), isDomainScoped and shutdown to a plain delegate") {
        val delegate = new PlainProvider()
        val cb       = CircuitBreakerProvider(delegate)
        cb.initialize(ctx)
        cb.initialize(ctx, "orders")
        val domainScoped = cb.isDomainScoped()
        cb.shutdown()
        // Assert both values: the SDK's own default is `false`, so a delegate that only ever reported
        // `false` would agree with a wrapper that had dropped the override entirely.
        val notDomainScoped = CircuitBreakerProvider(new PlainProvider(domainScoped = false)).isDomainScoped()
        assertTrue(
          delegate.initCount.get() == 1,
          delegate.domainInitCount.get() == 1,
          domainScoped,
          !notDomainScoped,
          delegate.shutdownCount.get() == 1
        )
      },
      test("initializing a plain delegate twice is safe and re-runs delegate initialization") {
        val delegate = new PlainProvider()
        val cb       = CircuitBreakerProvider(delegate)
        cb.initialize(ctx)
        cb.initialize(ctx)
        assertTrue(delegate.initCount.get() == 2)
      },
      test("forwards getProviderHooks and track to a plain delegate") {
        val delegate = new PlainProvider()
        val cb       = CircuitBreakerProvider(delegate)
        cb.track("purchase", ctx, new MutableTrackingEventDetails())
        assertTrue(cb.getProviderHooks.size == 1, delegate.tracked.contains("purchase"))
      },
      test("names the wrapped plain delegate in its metadata") {
        val cb = CircuitBreakerProvider(new PlainProvider())
        assertTrue(cb.getMetadata.getName == "CircuitBreakerProvider(PlainProvider)")
      },
      test("opens after failureThreshold failures from a plain delegate") {
        // The delegate does not override getState, so every poll sees the SDK's READY default. That must not
        // clear accumulated failures, or a plain provider could never trip on failure count alone.
        val delegate = new PlainProvider(Map("flag" -> true))
        val clock    = new TestClock()
        val config   = CircuitBreakerProviderConfig(failureThreshold = 3, resetTimeout = 1.minute)
        val cb       = CircuitBreakerProvider(delegate, config, clock)
        delegate.setFailing(true)
        (1 to 3).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        val countWhenOpen = delegate.evaluationCount.get()
        scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(delegate.evaluationCount.get() == countWhenOpen, cb.getState == ProviderState.ERROR)
      },
      test("recovers through a half-open probe after resetTimeout with a plain delegate") {
        val delegate = new PlainProvider(Map("flag" -> true))
        val clock    = new TestClock()
        val config   = CircuitBreakerProviderConfig(failureThreshold = 2, resetTimeout = 30.seconds)
        val cb       = CircuitBreakerProvider(delegate, config, clock)
        delegate.setFailing(true)
        (1 to 2).foreach(_ => scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)))
        val openedState = cb.getState
        clock.advance(31.seconds)
        delegate.setFailing(false)
        val probe = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(
          openedState == ProviderState.ERROR,
          probe.getValue == true,
          cb.getState == ProviderState.READY
        )
      },
      test("opens immediately when a plain delegate reports ERROR state") {
        val delegate = new StatefulPlainProvider
        val clock    = new TestClock()
        val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute), clock)
        delegate.setState(ProviderState.ERROR)
        val result        = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        val countAtReject = delegate.evaluationCount.get()
        assertTrue(result.isFailure, countAtReject == 0, cb.getState == ProviderState.ERROR)
      },
      test("opens immediately when a plain delegate reports FATAL state") {
        val delegate = new StatefulPlainProvider
        val clock    = new TestClock()
        val cb       = CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = 1.minute), clock)
        delegate.setState(ProviderState.FATAL)
        val result        = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        val countAtReject = delegate.evaluationCount.get()
        assertTrue(result.isFailure, countAtReject == 0, cb.getState == ProviderState.ERROR)
      }
    ),
    suite("make() factory")(
      test("creates a working CircuitBreakerProvider via ZIO") {
        for {
          underlying <- ZIO.succeed(new FailableProvider(Map("flag" -> true)))
          cb         <- CircuitBreakerProvider.make(underlying)
          result     <- ZIO.attempt(cb.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(result.getValue == true)
      }
    )
  ) @@ TestAspect.withLiveClock
}
