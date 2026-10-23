package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicLong

// #475: since ZIO 2.1.24, interrupting a thread blocked in Runtime.unsafe.run interrupts the fiber and rethrows
// InterruptedException. A CachingProvider (which calls unsafe.run) nested in a CircuitBreakerProvider whose evaluation
// timeout interrupts its blocking thread must still fail that call cleanly and keep serving afterwards.
object NestedWrapperTimeoutSpec extends ZIOSpecDefault {

  private class SlowThenFast extends FeatureProvider {
    val delayMillis = new AtomicLong(2000L)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata   = new Metadata { override def getName: String = "slow" }
    override def getState: ProviderState = ProviderState.READY
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      Thread.sleep(delayMillis.get())
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
  }

  private val ctx = new ImmutableContext("user-1")

  def spec = suite("NestedWrapperTimeoutSpec (#475)")(
    test("a timed-out call through a cache nested in a breaker fails cleanly, and the next call is served") {
      val delegate = new SlowThenFast
      val breaker = CircuitBreakerProvider(
        CachingProvider(delegate),
        CircuitBreakerProviderConfig(evaluationTimeout = 200.millis, failureThreshold = 5)
      )
      for {
        first <- ZIO.attemptBlocking(breaker.getBooleanEvaluation("flag", false, ctx)).either.timeout(10.seconds)
        _     <- ZIO.succeed(delegate.delayMillis.set(0L))
        next  <- ZIO.attemptBlocking(breaker.getBooleanEvaluation("flag", false, ctx)).timeout(10.seconds)
      } yield assertTrue(
        first.exists(_.isLeft),
        next.exists(_.getValue.booleanValue())
      )
    } @@ TestAspect.withLiveClock
  )
}
