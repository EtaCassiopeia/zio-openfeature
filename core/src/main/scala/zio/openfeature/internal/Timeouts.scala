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
}
