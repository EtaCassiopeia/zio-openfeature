package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// #446: a delegate may report failure by RETURNING an error-coded evaluation instead of throwing (the contrib OFREP
// resolver does this for network errors). The breaker must classify those exactly like thrown errors.
object CircuitBreakerReturnedErrorSpec extends ZIOSpecDefault {

  /** Returns `true`, or an evaluation carrying `code` (message "coded failure") while one is set. Never throws. */
  private class CodedProvider extends FeatureProvider {
    val code            = new AtomicReference[Option[ErrorCode]](None)
    val evaluationCount = new AtomicInteger(0)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "coded" }

    private def respond[T](value: T): ProviderEvaluation[T] = {
      evaluationCount.incrementAndGet()
      code.get() match {
        case Some(c) => ProviderEvaluations.error(value, c, "coded failure")
        case None    => ProviderEvaluations.of(value, "STATIC")
      }
    }

    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      respond[java.lang.Boolean](java.lang.Boolean.TRUE)
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) = respond(default)
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      respond(default)
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      respond(default)
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) = respond(default)
  }

  private class ManualTicker extends Ticker {
    private val now                = new AtomicReference[Long](java.lang.System.nanoTime())
    def advance(d: Duration): Unit = { now.updateAndGet(_ + d.toNanos); () }
    override def nanos(): Long     = now.get()
  }

  private val ctx = new ImmutableContext()

  // A long state-check interval keeps the delegate's (default READY) getState() poll out of these tests.
  private val config = CircuitBreakerProviderConfig(
    failureThreshold = 3,
    resetTimeout = 1.second,
    halfOpenMaxCalls = 1,
    stateCheckInterval = 1.hour
  )

  private def tripWith(code: ErrorCode): (CodedProvider, CircuitBreakerProvider, ManualTicker) = {
    val delegate = new CodedProvider
    val ticker   = new ManualTicker
    val cb       = CircuitBreakerProvider(delegate, config, ticker)
    delegate.code.set(Some(code))
    (1 to config.failureThreshold).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
    (delegate, cb, ticker)
  }

  private def opensOn(code: ErrorCode): TestResult = {
    val (delegate, cb, _) = tripWith(code)
    val before            = delegate.evaluationCount.get()
    val rejected          = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)).isFailure
    assertTrue(rejected, delegate.evaluationCount.get() == before, cb.getState == ProviderState.ERROR)
  }

  def spec = suite("CircuitBreakerReturnedErrorSpec (#446)")(
    test("returned GENERAL errors open the circuit; the next call does not reach the delegate") {
      opensOn(ErrorCode.GENERAL)
    },
    test("returned PROVIDER_NOT_READY errors open the circuit") {
      opensOn(ErrorCode.PROVIDER_NOT_READY)
    },
    test("returned PROVIDER_FATAL errors open the circuit") {
      opensOn(ErrorCode.PROVIDER_FATAL)
    },
    test("returned application errors do not open the circuit") {
      val (delegate, cb, _) = tripWith(ErrorCode.FLAG_NOT_FOUND)
      val before            = delegate.evaluationCount.get()
      cb.getBooleanEvaluation("flag", false, ctx)
      assertTrue(delegate.evaluationCount.get() == before + 1)
    },
    test("a returned application error resets the consecutive-failure count") {
      val delegate = new CodedProvider
      val cb       = CircuitBreakerProvider(delegate, config, new ManualTicker)
      delegate.code.set(Some(ErrorCode.GENERAL))
      (1 until config.failureThreshold).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
      delegate.code.set(Some(ErrorCode.FLAG_NOT_FOUND))
      cb.getBooleanEvaluation("flag", false, ctx)
      delegate.code.set(Some(ErrorCode.GENERAL))
      // One short of a fresh threshold: had the count not reset, this would have opened the circuit.
      (1 until config.failureThreshold).foreach(_ => cb.getBooleanEvaluation("flag", false, ctx))
      val before = delegate.evaluationCount.get()
      cb.getBooleanEvaluation("flag", false, ctx)
      assertTrue(delegate.evaluationCount.get() == before + 1)
    },
    test("a half-open probe that returns an application error does not close the circuit") {
      val (delegate, cb, ticker) = tripWith(ErrorCode.GENERAL)
      ticker.advance(2.seconds)
      delegate.code.set(Some(ErrorCode.FLAG_NOT_FOUND))
      cb.getBooleanEvaluation("flag", false, ctx)
      // Still half-open (STALE), and the probe slot was freed: the next probe reaches the delegate.
      val stateAfterProbe = cb.getState
      val before          = delegate.evaluationCount.get()
      cb.getBooleanEvaluation("flag", false, ctx)
      assertTrue(stateAfterProbe == ProviderState.STALE, delegate.evaluationCount.get() == before + 1)
    },
    test("a half-open probe that returns an infrastructure error re-opens the circuit") {
      val (delegate, cb, ticker) = tripWith(ErrorCode.GENERAL)
      ticker.advance(2.seconds)
      cb.getBooleanEvaluation("flag", false, ctx)
      val before   = delegate.evaluationCount.get()
      val rejected = scala.util.Try(cb.getBooleanEvaluation("flag", false, ctx)).isFailure
      assertTrue(cb.getState == ProviderState.ERROR, rejected, delegate.evaluationCount.get() == before)
    },
    test("a returned error-coded evaluation is passed through unchanged") {
      val delegate = new CodedProvider
      val cb       = CircuitBreakerProvider(delegate, config, new ManualTicker)
      delegate.code.set(Some(ErrorCode.GENERAL))
      val result = cb.getBooleanEvaluation("flag", false, ctx)
      assertTrue(
        result.getErrorCode == ErrorCode.GENERAL,
        result.getErrorMessage == "coded failure",
        result.getValue == java.lang.Boolean.TRUE
      )
    }
  )
}
