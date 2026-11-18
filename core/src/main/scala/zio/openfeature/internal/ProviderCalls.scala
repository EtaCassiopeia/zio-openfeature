package zio.openfeature.internal

import zio._

/** Calls into provider code that ZIO must not see a `StackOverflowError` from.
  *
  * ZIO treats every `VirtualMachineError` that reaches a fiber as fatal and, by default, exits the JVM. The Java SDK's
  * client catches `Exception` only, so a provider's `StackOverflowError` (a recursive walk over a deeply nested value)
  * would otherwise end the process from one flag read. Unlike `OutOfMemoryError`, it belongs to the thread that threw
  * it and is over once that stack has unwound, so it is rethrown as an ordinary exception here. Every other
  * `VirtualMachineError` is left fatal.
  */
private[openfeature] object ProviderCalls {

  /** A provider's `StackOverflowError`, carried as a non-fatal exception. [[zio.openfeature.FeatureFlagError.classify]]
    * unwraps it.
    */
  final class ProviderStackOverflow(val error: StackOverflowError)
      extends RuntimeException(s"provider threw ${error.getClass.getName}", error)

  /** Run `f`, rethrowing a `StackOverflowError` as a [[ProviderStackOverflow]]. */
  def guard[A](f: => A): A =
    try f
    catch { case e: StackOverflowError => throw new ProviderStackOverflow(e) }

  /** The `StackOverflowError` a [[ProviderStackOverflow]] carries, or `t` itself. */
  def unwrap(t: Throwable): Throwable = t match {
    case overflow: ProviderStackOverflow => overflow.error
    case other                           => other
  }

  /** `ZIO.attemptBlocking` over [[guard]], failing with the provider's own `StackOverflowError` as a value: ZIO treats
    * it as fatal only when it is thrown, and no error the library returns carries the private wrapper.
    */
  def blocking[A](f: => A)(implicit trace: Trace): Task[A] =
    ZIO.attemptBlocking(guard(f)).mapError(unwrap)
}
