package zio.openfeature.internal

import zio._

/** Timeouts whose timer ends with the caller.
  *
  * ZIO's `timeoutFail` leaves its timer fiber sleeping until the timeout has passed when the caller is interrupted
  * meanwhile. A timed evaluation interrupted by its caller would leave one such fiber per call. A race interrupts both
  * sides when the caller is interrupted, so the timer goes with it.
  */
private[openfeature] object Timeouts {

  /** `effect.timeoutFail(error)(d)`: the first of `effect`'s outcome and `error` after `d`, the other side interrupted.
    */
  def failAfter[R, E, A](effect: ZIO[R, E, A], d: Duration, error: => E)(implicit trace: Trace): ZIO[R, E, A] =
    effect.raceFirst(ZIO.sleep(d) *> ZIO.fail(error))

  /** `effect.timeout(d)`: `effect`'s value, or `None` once `d` has passed, the other side interrupted. An infinite `d`
    * races nothing.
    */
  def within[R, E, A](effect: ZIO[R, E, A], d: Duration)(implicit trace: Trace): ZIO[R, E, Option[A]] =
    if (d == Duration.Infinity) effect.map(Some(_))
    else effect.map(Some(_): Option[A]).raceFirst(ZIO.sleep(d).as(None))

  /** Bounds `effect` inside a scope finalizer: `Some` of its value, or `None` once `bound` has passed (after running
    * `onTimeout`) or when it failed. A failure is not logged here: a caller that wants it logged taps `effect`, which
    * also logs the eventual failure of a call the bound abandoned.
    *
    * A finalizer runs uninterruptibly, and so does everything it forks, so a plain `timeout` there cannot abandon the
    * call: once the bound passes, the timer waits to interrupt a side that cannot be interrupted, and a hung call hangs
    * the scope close with it. Here `effect` runs `.disconnect`ed, so the blocking call is left to finish in the
    * background, inside an `.interruptible` region, so the bound can abandon it. The whole thing runs on a daemon
    * fiber: a finalizer running because its fiber was interrupted (the normal application-shutdown path) carries that
    * pending interrupt, which would otherwise fire on entering the interruptible region and skip `effect` altogether.
    */
  def boundedFinalizer[A](effect: Task[A], bound: Duration)(onTimeout: => UIO[Any])(implicit
    trace: Trace
  ): UIO[Option[A]] =
    within(effect.disconnect, bound)
      .tap(result => ZIO.when(result.isEmpty)(onTimeout))
      .interruptible
      .forkDaemon
      .flatMap(_.join)
      .catchAllCause(_ => ZIO.none)
}
