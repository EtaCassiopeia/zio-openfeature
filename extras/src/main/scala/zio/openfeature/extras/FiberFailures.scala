package zio.openfeature.extras

import dev.openfeature.sdk.exceptions.{GeneralError, OpenFeatureError}
import java.util.concurrent.TimeoutException

/** Shared handling for the `zio.FiberFailure` that `Runtime.unsafe.run(...).getOrThrow*` produces.
  *
  * A `FiberFailure` extends `Throwable`, NOT `Exception`, so it escapes the Java SDK's `catch (Exception)` around
  * provider evaluation and throws straight into application code — breaking the spec's never-throw evaluation contract
  * and bypassing the error hooks. Providers that run a ZIO effect synchronously (behind the blocking Java
  * `FeatureProvider` API) must therefore unwrap the `FiberFailure` back to the original error before rethrowing.
  */
private[extras] object FiberFailures {

  /** Unwrap a `FiberFailure` to the throwable the fiber actually failed with; pass any other throwable through. */
  def unwrap(e: Throwable): Throwable = e match {
    case ff: zio.FiberFailure =>
      ff.cause.failureOption match {
        case Some(t: Throwable) => t
        case _ =>
          ff.cause.dieOption match {
            case Some(t) => t
            case None =>
              if (ff.cause.isInterrupted) new TimeoutException("Evaluation was interrupted")
              else ff
          }
      }
    case other => other
  }

  /** Convert a failed effect's `Cause` into the `OpenFeatureError` to rethrow: an original `OpenFeatureError` is
    * preserved as-is (so the SDK maps it to the right error code and reason), and anything else is wrapped in
    * `GeneralError`. Both extend `Exception`, so the SDK's `catch (Exception)` catches them and returns the default
    * value with error details instead of letting the throwable escape.
    */
  def toOpenFeatureError(cause: zio.Cause[Throwable]): OpenFeatureError =
    unwrap(new zio.FiberFailure(cause)) match {
      case ofe: OpenFeatureError => ofe
      case other                 => new GeneralError(Option(other.getMessage).getOrElse("evaluation failed"))
    }
}
