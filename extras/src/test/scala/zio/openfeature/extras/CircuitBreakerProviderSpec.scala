package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
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
      ProviderEvaluation.builder[java.lang.Boolean]().value(v).reason("STATIC").build()
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluation.builder[String]().value(v).reason("STATIC").build()
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluation.builder[java.lang.Integer]().value(v).reason("STATIC").build()
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluation.builder[java.lang.Double]().value(v).reason("STATIC").build()
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      maybeFailOrDelay()
      val v = flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue)
      ProviderEvaluation.builder[Value]().value(v).reason("STATIC").build()
    }
  }

  /** A controllable clock for testing time-based transitions. */
  private class TestClock extends java.time.Clock {
    private val currentMillis = new AtomicReference[Long](java.lang.System.currentTimeMillis())

    def advance(duration: Duration): Unit =
      currentMillis.updateAndGet(_ + duration.toMillis)

    override def millis(): Long                                    = currentMillis.get()
    override def getZone: java.time.ZoneId                         = java.time.ZoneId.of("UTC")
    override def withZone(zone: java.time.ZoneId): java.time.Clock = this
    override def instant(): java.time.Instant                      = java.time.Instant.ofEpochMilli(millis())
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
        val config     = CircuitBreakerConfig(failureThreshold = 3)
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
        val config     = CircuitBreakerConfig(evaluationTimeout = 50.millis, failureThreshold = 100)
        val cb         = CircuitBreakerProvider(underlying)
        val result     = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure)
      } @@ TestAspect.withLiveClock,
      test("successful call within timeout works normally") {
        val underlying = new FailableProvider(Map("flag" -> true), delay = Some(5.millis))
        val config     = CircuitBreakerConfig(evaluationTimeout = 1.second)
        val cb         = CircuitBreakerProvider(underlying, config)
        val result     = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      } @@ TestAspect.withLiveClock
    ),
    suite("Failure counting and opening")(
      test("opens after failureThreshold consecutive failures") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val clock      = new TestClock()
        val config     = CircuitBreakerConfig(failureThreshold = 3, resetTimeout = 1.minute)
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
        val config     = CircuitBreakerConfig(failureThreshold = 5)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.minute)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 30.seconds)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.second, halfOpenMaxCalls = 1)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.second)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.second, halfOpenMaxCalls = 3)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
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
        val config     = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.minute)
        val cb         = CircuitBreakerProvider(underlying, config, clock)
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
        val config     = CircuitBreakerConfig(stalePolicy = StalePolicy.Open)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        val result = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx))
        assertTrue(result.isFailure) &&
        assertTrue(cb.getState == ProviderState.ERROR)
      },
      test("stalePolicy Ignore keeps circuit closed on STALE state") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerConfig(stalePolicy = StalePolicy.Ignore)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      },
      test("stalePolicy HalfOpen transitions to half-open on STALE state") {
        val underlying = new FailableProvider(Map("flag" -> true))
        val config     = CircuitBreakerConfig(stalePolicy = StalePolicy.HalfOpen)
        val cb         = CircuitBreakerProvider(underlying, config)
        underlying.setState(ProviderState.STALE)
        // First call allowed as probe
        val result = cb.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true)
      }
    ),
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
