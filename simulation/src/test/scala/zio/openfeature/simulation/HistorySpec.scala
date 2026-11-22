package zio.openfeature.simulation

import zio.Chunk
import zio.test.{Spec => _, _}

object HistorySpec extends ZIOSpecDefault {

  private val a = Stored.SStr("a")
  private val b = Stored.SStr("b")
  private val c = Stored.SStr("c")

  // Leaf 1 holds k=a; its change events clear Cache 0 before the emit returns.
  private val cacheLeaf = Spec.number(Spec.Cache(-1, Spec.Leaf(-1, Map("k" -> a))))
  // The same, under a breaker: the cache still wraps the leaf directly.
  private val breakerCacheLeaf =
    Spec.number(Spec.Breaker(-1, 1, false, Spec.Cache(-1, Spec.Leaf(-1, Map("k" -> a)))))
  // A cache that the leaf's events reach only through the breaker, later.
  private val cacheBreakerLeaf =
    Spec.number(Spec.Cache(-1, Spec.Breaker(-1, 1, false, Spec.Leaf(-1, Map("k" -> a)))))

  private def leafId(spec: Spec): Int = Spec.leaves(spec).head.id

  private def eval(spec: Spec, value: String, reason: String, from: Long, to: Long): Op.Eval =
    Op.Eval("k", Kind.KStr, "-|", from, to, Outcome(Some(value), reason, Some(s"L${leafId(spec)}:k"), None))

  private def write(spec: Spec, v: Stored, invalidated: Boolean, from: Long, to: Long): Op.Write =
    Op.Write(leafId(spec), "k", Some(v), invalidated, from, to)

  private def violations(spec: Spec, ops: Op*): List[Violation] =
    History.check(spec, Chunk.fromIterable(ops)).violations

  def spec = suite("History")(
    test("a cached value older than a completed synchronous invalidation is a violation") {
      val vs = violations(cacheLeaf, write(cacheLeaf, b, true, 1, 2), eval(cacheLeaf, "a", "CACHED", 3, 4))
      assertTrue(vs.size == 1, vs.head.allowed == Set("b"), vs.head.floor.isDefined)
    },
    test(
      "a cached value from a lookup that overlapped the invalidation is allowed for a caller that started before it completed"
    ) {
      assertTrue(
        violations(cacheLeaf, write(cacheLeaf, b, true, 2, 5), eval(cacheLeaf, "a", "CACHED", 3, 6)).isEmpty
      )
    },
    test(
      "a caller that started after the invalidation completed may not get the pre-invalidation value, even if a lookup was in flight"
    ) {
      val s  = breakerCacheLeaf
      val vs = violations(
        s,
        eval(s, "a", "STATIC", 1, 6),
        write(s, b, true, 2, 3),
        eval(s, "a", "CACHED", 4, 7)
      )
      assertTrue(vs.map(_.eval.invokeAt) == List(4L))
    },
    test("a stale cached value after a change without an event is allowed") {
      assertTrue(
        violations(cacheLeaf, write(cacheLeaf, b, false, 1, 2), eval(cacheLeaf, "a", "CACHED", 3, 4)).isEmpty
      )
    },
    test("a cache reached only asynchronously has no freshness floor") {
      val s = cacheBreakerLeaf
      assertTrue(violations(s, write(s, b, true, 1, 2), eval(s, "a", "CACHED", 3, 4)).isEmpty)
    },
    test("a live read must return a value the leaf held during the evaluation") {
      val vs = violations(
        cacheLeaf,
        write(cacheLeaf, b, false, 1, 2),
        eval(cacheLeaf, "a", "STATIC", 3, 4),
        eval(cacheLeaf, "a", "STATIC", 2, 4)
      )
      assertTrue(vs.map(_.eval.invokeAt) == List(3L))
    },
    test("an answer from a stack replaced before the evaluation started is a violation") {
      val next = Spec.number(Spec.Leaf(-1, Map("k" -> a)), Spec.maxId(cacheLeaf) + 1)
      val vs   = violations(
        cacheLeaf,
        Op.Swap(next, 1, 2),
        eval(cacheLeaf, "a", "STATIC", 1, 3),
        eval(cacheLeaf, "a", "STATIC", 3, 4)
      )
      assertTrue(vs.map(_.eval.invokeAt) == List(3L))
    },
    test("a stack installed in two intervals answers in either, and not in between") {
      val s0    = Spec.number(Spec.Leaf(-1, Map("k" -> a)))
      val other = Spec.number(Spec.Leaf(-1, Map("k" -> b)), Spec.maxId(s0) + 1)
      val vs    = violations(
        s0,
        Op.Swap(other, 2, 3),
        Op.Swap(s0, 6, 7),
        eval(s0, "a", "STATIC", 1, 2),
        eval(s0, "a", "STATIC", 4, 5),
        eval(s0, "a", "STATIC", 8, 9)
      )
      assertTrue(vs.map(_.eval.invokeAt) == List(4L))
    },
    test("a rejected emit does not count as an invalidation") {
      // The second change's emit was rejected, so the floor is still the first change: its value stays allowed.
      assertTrue(
        violations(
          cacheLeaf,
          write(cacheLeaf, b, true, 1, 2),
          write(cacheLeaf, c, false, 3, 4),
          eval(cacheLeaf, "b", "CACHED", 5, 6)
        ).isEmpty
      )
    }
  )
}
