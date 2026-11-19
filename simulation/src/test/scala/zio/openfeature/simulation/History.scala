package zio.openfeature.simulation

import zio.Chunk

/** One client-visible operation of a concurrent run, stamped with the run's logical clock: `invokeAt` is taken in the
  * calling fiber just before the operation, `completeAt` just after. The clock is linearizable, so
  * `a.completeAt < b.invokeAt` means `a` finished before `b` started in real time; that is the only order the checker
  * relies on.
  */
sealed trait Op extends Product with Serializable {
  def invokeAt: Long
  def completeAt: Long
}
object Op {

  /** A successful evaluation. `fp` is the rendered context the leaf receives. */
  final case class Eval(key: String, kind: Kind, fp: String, invokeAt: Long, completeAt: Long, outcome: Outcome)
      extends Op

  /** A flag change on a leaf, by leaf id; `None` removes the flag. `invalidated` is whether a change event was emitted
    * and accepted.
    */
  final case class Write(
    leaf: Int,
    key: String,
    value: Option[Stored],
    invalidated: Boolean,
    invokeAt: Long,
    completeAt: Long
  ) extends Op

  /** A successful `setProvider` to a numbered stack. */
  final case class Swap(spec: Spec, invokeAt: Long, completeAt: Long) extends Op

  def show(op: Op): String = op match {
    case e: Eval =>
      s"eval ${e.key}:${e.kind} fp=${e.fp} [t${e.invokeAt},t${e.completeAt}] got ${e.outcome}"
    case w: Write =>
      val v = w.value.fold("removed")(_.toString)
      s"write L${w.leaf}:${w.key}=$v${if (w.invalidated) " +event" else ""} [t${w.invokeAt},t${w.completeAt}]"
    case s: Swap => s"swap to ${Spec.show(s.spec)} [t${s.invokeAt},t${s.completeAt}]"
  }
}

/** A leaf's initial flags, and whether its change events clear the cache above it before the emit returns: exactly one
  * cache above it, wrapping it directly.
  */
final case class LeafInfo(initial: Map[String, Stored], syncCached: Boolean)

/** An evaluation whose answer the history cannot explain. `from` is where the window of allowed values started. */
final case class Violation(
  eval: Op.Eval,
  rule: String,
  from: Long,
  allowed: Set[String],
  floor: Option[Op.Write],
  overlapping: List[Op]
) {
  def render: String =
    (s"history: $rule: ${Op.show(eval)}; allowed ${allowed.toList.sorted.mkString("{", "|", "}")} " +
      s"from t$from to t${eval.completeAt}" + floor.fold("")(f => s"; floor ${Op.show(f)}") ::
      overlapping.map(o => s"  ${Op.show(o)}")).mkString("\n")
}

/** Checks each successful evaluation of a concurrent run against the recorded history:
  *   1. the answering leaf (from the variant) belongs to a stack installed at some point during the evaluation;
  *   1. a live read (reason not `CACHED`) returns a value the leaf held during the evaluation;
  *   1. a cache hit returns a value the leaf held at some point since it existed, or, when the leaf's change events
  *      clear that cache before the emit returns, since the last such change that completed before the evaluation
  *      started.
  *
  * The mutator is a single fiber, so writes never overlap one another. Pure.
  */
object History {

  final case class Report(violations: List[Violation], checked: Int, cachedFloored: Int)

  def leafInfo(spec: Spec): Map[Int, LeafInfo] = {
    val reach = Model.reach(spec).values.toList
    Spec
      .leaves(spec)
      .map { l =>
        val above = reach.flatMap(_.get(l.id))
        l.id -> LeafInfo(l.initial, above == List(Reach.Sync))
      }
      .toMap
  }

  // "L3:k1" or "L3:k1@late" -> (3, "k1")
  private val Variant = """L(\d+):(.*)""".r

  private def answeredBy(variant: String): Option[(Int, String)] =
    variant.stripSuffix(SimLeaf.LateSuffix) match {
      case Variant(id, key) => Some((id.toInt, key))
      case _                => None
    }

  def check(initial: Spec, ops: Chunk[Op]): Report = {
    val swaps  = ops.collect { case s: Op.Swap => s }.sortBy(_.invokeAt).toVector
    val stacks = (Op.Swap(initial, 0L, 0L) +: swaps).toVector
    val owner  = stacks.zipWithIndex.flatMap { case (s, j) => Spec.leaves(s.spec).map(_.id -> j) }.toMap
    val info   = stacks.flatMap(s => leafInfo(s.spec)).toMap
    val writes = ops.collect { case w: Op.Write => w }.sortBy(_.invokeAt).toList
    val evals  = ops.collect { case e: Op.Eval if e.outcome.isSuccess => e }.toList

    def overlaps(o: Op, a: Long, b: Long): Boolean = o.invokeAt <= b && o.completeAt >= a

    def writesTo(l: Int, k: String): List[Op.Write] = writes.filter(w => w.leaf == l && w.key == k)

    // Values (l, k) held at some point in [a, b]: the last write completed before a, else the initial value, and every
    // write overlapping the interval.
    def held(l: Int, k: String, a: Long, b: Long): Set[Stored] = {
      val ws     = writesTo(l, k)
      val before = ws.filter(_.completeAt < a).lastOption.fold(info(l).initial.get(k))(_.value)
      (before.toList ++ ws.filter(overlaps(_, a, b)).flatMap(_.value)).toSet
    }

    // Values (l, k) held at some point from f's change on until b. Writes do not overlap, so every write to the leaf
    // invoked no later than f has completed by f's change.
    def heldSince(f: Op.Write, l: Int, k: String, b: Long): Set[Stored] = {
      val ws   = writesTo(l, k)
      val base = ws.filter(_.invokeAt <= f.invokeAt).lastOption.fold(info(l).initial.get(k))(_.value)
      (base.toList ++ ws.filter(w => w.invokeAt > f.invokeAt && w.invokeAt <= b).flatMap(_.value)).toSet
    }

    def rendered(e: Op.Eval, values: Set[Stored]): Set[String] =
      values.flatMap(s => Harness.lift(e.kind, PResult.Ok(Model.renderStored(s, e.fp), "", "")).value)

    def swapsDuring(a: Long, b: Long): List[Op] = swaps.filter(overlaps(_, a, b)).toList

    // A violation, if any, and whether a freshness floor applied.
    def checkOne(e: Op.Eval): (Option[Violation], Boolean) = {
      val (i, c)                   = (e.invokeAt, e.completeAt)
      def ok(allowed: Set[String]) = e.outcome.value.exists(allowed.contains)
      def upTo(ws: List[Op.Write]) = ws.filter(_.invokeAt <= c)
      def violation(rule: String, from: Long, allowed: Set[String], floor: Option[Op.Write], more: List[Op]) =
        Some(Violation(e, rule, from, allowed, floor, more))
      e.outcome.variant.flatMap(answeredBy) match {
        case None =>
          (violation("the answer names no leaf", i, Set.empty, None, swapsDuring(i, c)), false)
        case Some((l, k)) if k != e.key =>
          (violation(s"the answer is L$l's value for another key, $k", i, Set.empty, None, Nil), false)
        case Some((l, _)) if !owner.contains(l) =>
          (
            violation(s"the answer comes from L$l, which no installed stack holds", i, Set.empty, None, swaps.toList),
            false
          )
        case Some((l, k)) =>
          val j    = owner(l)
          val next = stacks.lift(j + 1)
          if (stacks(j).invokeAt > c || next.exists(_.completeAt < i))
            (
              violation(
                s"the answering leaf L$l belongs to a stack not installed during the evaluation",
                i,
                Set.empty,
                None,
                (if (j > 0) List(stacks(j)) else Nil) ++ next.toList
              ),
              false
            )
          else if (e.outcome.reason != "CACHED") {
            val allowed = rendered(e, held(l, k, i, c))
            val bad     = !ok(allowed)
            (
              if (bad)
                violation("a live read returned a value its leaf did not hold", i, allowed, None, upTo(writesTo(l, k)))
              else None,
              false
            )
          } else {
            val floor =
              if (info(l).syncCached) writes.filter(w => w.leaf == l && w.invalidated && w.completeAt < i).lastOption
              else None
            val from    = floor.fold(0L)(_.invokeAt)
            val allowed = rendered(e, floor.fold(held(l, k, 0L, c))(heldSince(_, l, k, c)))
            (
              if (ok(allowed)) None
              else
                violation(
                  "a cache hit returned a value older than the last completed invalidation",
                  from,
                  allowed,
                  floor,
                  upTo(writesTo(l, k)) ++ swapsDuring(from, c)
                ),
              floor.isDefined
            )
          }
      }
    }

    val results = evals.map(checkOne)
    Report(results.flatMap(_._1), evals.size, results.count(_._2))
  }
}
