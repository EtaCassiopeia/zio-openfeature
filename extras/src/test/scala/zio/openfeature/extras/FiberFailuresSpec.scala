package zio.openfeature.extras

import dev.openfeature.sdk.exceptions.{FlagNotFoundError, GeneralError, OpenFeatureError}
import zio._
import zio.test._

/** #258: `FiberFailures.toOpenFeatureError` is the contract that keeps a synchronously-run provider effect from ever
  * throwing a non-`Exception` into the SDK. `CachingProvider` only ever feeds it a `Fail(throwable)` cause, so these
  * same-package unit tests exercise the die / interrupt / empty-cause branches directly to prove the helper ALWAYS
  * yields an `OpenFeatureError` (⊂ `Exception`).
  */
object FiberFailuresSpec extends ZIOSpecDefault {

  def spec = suite("FiberFailures.toOpenFeatureError")(
    test("Fail(OpenFeatureError) is preserved as-is") {
      val ofe = new FlagNotFoundError("flag")
      assertTrue(FiberFailures.toOpenFeatureError(Cause.fail(ofe: Throwable)) eq ofe)
    },
    test("Fail(other throwable) is wrapped in GeneralError") {
      val result = FiberFailures.toOpenFeatureError(Cause.fail(new RuntimeException("boom")))
      assertTrue(result.isInstanceOf[GeneralError], result.getMessage == "boom")
    },
    test("Die(OpenFeatureError) is recovered and preserved") {
      val ofe = new FlagNotFoundError("flag")
      assertTrue(FiberFailures.toOpenFeatureError(Cause.die(ofe)) eq ofe)
    },
    test("Die(other throwable) is wrapped in GeneralError") {
      val result = FiberFailures.toOpenFeatureError(Cause.die(new IllegalStateException("bad")))
      assertTrue(result.isInstanceOf[GeneralError], result.getMessage == "bad")
    },
    test("an interruption is wrapped in GeneralError, never a non-Exception") {
      val result = FiberFailures.toOpenFeatureError(Cause.interrupt(FiberId.None))
      // The interrupt branch yields a TimeoutException, which is not an OpenFeatureError → wrapped in GeneralError.
      assertTrue(result.isInstanceOf[GeneralError], result.isInstanceOf[OpenFeatureError])
    }
  )
}
