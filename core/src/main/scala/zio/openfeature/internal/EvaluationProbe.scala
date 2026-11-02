package zio.openfeature.internal

import zio._

/** Named points on the evaluation path where a test can hold the evaluating fiber and act before letting it go, as
  * [[LifecycleProbe]] does for the lifecycle. Production instances use [[EvaluationProbe.none]], whose `at` is a
  * constant no-op: the hot path pays one call per point.
  *
  * The points, in pipeline order, are the steps `docs/architecture.md` lists under "One evaluation, step by step".
  */
private[openfeature] trait EvaluationProbe {
  def at(point: EvaluationProbe.Point): UIO[Unit]
}

private[openfeature] object EvaluationProbe {

  sealed trait Point extends Product with Serializable {

    /** The point's name. */
    def name: String = productPrefix
  }

  object Point {

    /** The contexts are merged; no hook has run. */
    case object ContextMerged extends Point

    /** The `before` hooks are about to run, inside the stage whose exit runs `finallyAfter`. */
    case object BeforeHooks extends Point

    /** About to read the transaction, whose override or cached evaluation would answer without the provider. */
    case object TransactionLookup extends Point

    /** About to check the status; the swap generation the answer is checked against is read. */
    case object StatusGate extends Point

    /** The provider call is about to run and is counted as running: on the forked call fiber when the evaluation is
      * timed, on the caller otherwise.
      */
    case object CallForked extends Point

    /** The provider call returned, timed out or failed; the caller has its outcome. */
    case object CallReturned extends Point

    /** The answer is decoded into the flag's type. */
    case object Decoded extends Point

    /** The answer is in hand and the swap check that may reject it has not run. */
    case object GateRechecked extends Point

    /** The `after` hooks are about to run on a clean resolution. */
    case object AfterHooks extends Point

    /** The `error` hooks are about to run on a failure. */
    case object ErrorHooks extends Point

    /** `finallyAfter` is about to run, on every exit of the stage `BeforeHooks` entered. */
    case object FinallyAfter extends Point

    val all: List[Point] = List(
      ContextMerged,
      BeforeHooks,
      TransactionLookup,
      StatusGate,
      CallForked,
      CallReturned,
      Decoded,
      GateRechecked,
      AfterHooks,
      ErrorHooks,
      FinallyAfter
    )
  }

  val none: EvaluationProbe = new EvaluationProbe {
    def at(point: Point): UIO[Unit] = ZIO.unit
  }
}
