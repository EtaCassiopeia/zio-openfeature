package zio.openfeature.extras

import zio.openfeature.testkit.{Fault, FaultyProvider, Op}

import dev.openfeature.sdk.ImmutableContext
import zio._
import zio.test._

// #475: since ZIO 2.1.24, interrupting a thread blocked in Runtime.unsafe.run interrupts the fiber and rethrows
// InterruptedException. A CachingProvider (which calls unsafe.run) nested in a CircuitBreakerProvider whose evaluation
// timeout interrupts its blocking thread must still fail that call cleanly and keep serving afterwards.
object NestedWrapperTimeoutSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext("user-1")

  def spec = suite("NestedWrapperTimeoutSpec (#475)")(
    test("a timed-out call through a cache nested in a breaker fails cleanly, and the next call is served") {
      for {
        delegate <- FaultyProvider.scoped(Map("flag" -> true), name = "slow")
        _        <- delegate.script(Op.EvaluateBoolean, Fault.Delay(2.seconds), Fault.Succeed) // slow once, then fast
        breaker = CircuitBreakerProvider(
          CachingProvider(delegate),
          CircuitBreakerProviderConfig(evaluationTimeout = 200.millis, failureThreshold = 5)
        )
        first <- ZIO.attemptBlocking(breaker.getBooleanEvaluation("flag", false, ctx)).either.timeout(10.seconds)
        next  <- ZIO.attemptBlocking(breaker.getBooleanEvaluation("flag", false, ctx)).timeout(10.seconds)
      } yield assertTrue(
        first.exists(_.isLeft),
        next.exists(_.getValue.booleanValue())
      )
    } @@ TestAspect.withLiveClock
  )
}
