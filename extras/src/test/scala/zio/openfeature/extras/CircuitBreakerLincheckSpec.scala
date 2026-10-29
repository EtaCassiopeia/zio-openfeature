package zio.openfeature.extras

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.{ModelCheckingOptions, Operation}
import zio._
import zio.openfeature.testsupport.PropertyBudget
import zio.test._

/** One `CircuitBreaker` driven by concurrent operations. Lincheck's model checker runs every interleaving it reaches
  * and checks each outcome matches some sequential order of the same calls.
  *
  * The clock is frozen, so the time-based transitions (an open circuit timing out, a wedged probe slot being stolen)
  * are out of scope here; `CircuitBreakerSpec` covers them, including a state change racing the probe claim. With time
  * still, half-open is reached through `toHalfOpen`, and single-flight probing is checked from there.
  */
class CircuitBreakerLincheckTest {
  private val breaker = CircuitBreaker(
    CircuitBreakerConfig(failureThreshold = 2, halfOpenMaxCalls = 2),
    new Ticker { def nanos(): Long = 0L }
  )

  @Operation def tryAcquire(): String     = breaker.tryAcquire.toString
  @Operation def recordSuccess(): Boolean = breaker.recordSuccess()
  @Operation def recordFailure(): Boolean = breaker.recordFailure()
  @Operation def recordReachable(): Unit  = breaker.recordReachable()
  @Operation def trip(): Unit             = breaker.trip()
  @Operation def tripStale(): Unit        = breaker.trip(OpenReason.External, stale = true)
  @Operation def tripPolledStale(): Unit  = breaker.trip(OpenReason.Polled, stale = true)
  @Operation def reset(): Unit            = breaker.reset()
  @Operation def toHalfOpen(): Unit       = breaker.transitionToHalfOpen()
  @Operation def state(): String          = breaker.currentState.toString
}

/** A counter whose increment reads and then writes: not atomic, so not linearizable. The model checker must reject it,
  * which shows the checker can still see races on this JDK and build.
  */
class RacyCounterLincheckTest {
  @volatile private var value = 0

  @Operation def increment(): Int = { val next = value + 1; value = next; next }
  @Operation def get(): Int       = value
}

object CircuitBreakerLincheckSpec extends ZIOSpecDefault {

  // A pull request checks a small sample of interleavings; the nightly property run multiplies it.
  private def options = new ModelCheckingOptions()
    .iterations(10 * PropertyBudget.scale)
    .invocationsPerIteration(500)

  def spec = suite("CircuitBreakerLincheckSpec")(
    test("every concurrent run of the breaker's operations is linearizable") {
      ZIO.attemptBlockingInterrupt(options.check(classOf[CircuitBreakerLincheckTest])).as(assertCompletes)
    },
    test("the checker rejects a counter whose increment is not atomic") {
      // Only a linearizability verdict counts: an internal checker failure (say, instrumentation broken on a new JDK)
      // must fail this test, not satisfy it.
      ZIO.attemptBlockingInterrupt(options.check(classOf[RacyCounterLincheckTest])).either.map { result =>
        assertTrue(result.left.exists {
          case e: LincheckAssertionError => e.getMessage.contains("Invalid execution results")
          case _                         => false
        })
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.tag(PropertyBudget.Tag) @@ PropertyBudget.timeout(5.minutes)
}
