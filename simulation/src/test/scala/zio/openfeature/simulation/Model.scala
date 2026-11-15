package zio.openfeature.simulation

/** What a provider-level call produces, abstracted: a value, a returned error code, or a thrown one. Codes are the SDK
  * names (`GENERAL`, `FLAG_NOT_FOUND`, `TYPE_MISMATCH`, ...).
  */
sealed trait PResult extends Product with Serializable
object PResult {
  final case class Ok(value: String, reason: String, variant: String) extends PResult
  final case class Ret(code: String)                                  extends PResult
  final case class Thr(code: String)                                  extends PResult
}

/** How a leaf's configuration-change event reaches a cache above it. */
sealed trait Reach extends Product with Serializable
object Reach {
  case object Sync  extends Reach // the cache wraps the leaf directly: cleared before the emit returns
  case object Async extends Reach // through a breaker, multi-provider or cache lane: cleared later
  case object Never extends Reach // blocked by a Deferred or Widen on the way
}

/** The model's state for one installed stack. */
final case class StackState(
  spec: Spec,
  leafFlags: Map[Int, Map[String, Stored]],
  leafFaults: Map[Int, LeafFault],
  // cache id -> (key, wire, fingerprint) -> served value
  cacheEntries: Map[Int, Map[(String, Wire, String), PResult.Ok]],
  // caches whose entries may have been cleared by an event still in flight
  uncertain: Set[Int],
  breakerFailures: Map[Int, Int],
  breakerOpen: Set[Int]
)

object StackState {
  def initial(spec: Spec): StackState =
    StackState(
      spec,
      Spec.leaves(spec).map(l => l.id -> l.initial).toMap,
      Map.empty,
      Spec.caches(spec).map(_ -> Map.empty[(String, Wire, String), PResult.Ok]).toMap,
      Set.empty,
      Map.empty,
      Set.empty
    )
}

/** The pure reference model of a provider stack. */
object Model {

  private val appCodes =
    Set("FLAG_NOT_FOUND", "TYPE_MISMATCH", "PARSE_ERROR", "TARGETING_KEY_MISSING", "INVALID_CONTEXT")

  def renderStored(s: Stored, ctx: String): String = s match {
    case Stored.SBool(v) => v.toString
    case Stored.SStr(v)  => v
    case Stored.SInt(v)  => v.toString
    case Stored.SLong(v) => v.toString
    case Stored.SDbl(v)  => v.toString
    case Stored.SObj(m)  => m.toList.sorted.map { case (k, v) => s"$k=$v" }.mkString("{", ",", "}")
    case Stored.Echo     => ctx
  }

  private def leafAnswer(st: StackState, id: Int, wire: Wire, key: String, fp: String): PResult =
    st.leafFaults.get(id) match {
      case Some(LeafFault.ThrowGeneral)  => PResult.Thr("GENERAL")
      case Some(LeafFault.ThrowNotFound) => PResult.Thr("FLAG_NOT_FOUND")
      case Some(LeafFault.ReturnGeneral) => PResult.Ret("GENERAL")
      case None                          =>
        st.leafFlags.getOrElse(id, Map.empty).get(key) match {
          case None         => PResult.Ret("FLAG_NOT_FOUND")
          case Some(stored) =>
            val matches = (wire, stored) match {
              case (Wire.Bool, _: Stored.SBool) | (Wire.Str, _: Stored.SStr) | (Wire.Str, Stored.Echo) |
                  (Wire.Int, _: Stored.SInt) | (Wire.Long, _: Stored.SLong) | (Wire.Dbl, _: Stored.SDbl) |
                  (Wire.Obj, _: Stored.SObj) =>
                true
              case _ => false
            }
            if (!matches) PResult.Ret("TYPE_MISMATCH")
            else
              PResult.Ok(
                renderStored(stored, fp),
                if (stored == Stored.Echo) "TARGETING_MATCH" else "STATIC",
                s"L$id:$key"
              )
        }
    }

  /** Evaluate `spec` for (wire, key, fingerprint). `longFitsInt` is whether a long read's default fits an Int. Returns
    * every possible (result, state) pair: an uncertain cache may or may not still hold an entry.
    */
  def eval(
    spec: Spec,
    wire: Wire,
    key: String,
    fp: String,
    longFitsInt: Boolean,
    st: StackState
  ): List[(PResult, StackState)] = spec match {
    case Spec.Leaf(id, _) => List(leafAnswer(st, id, wire, key, fp) -> st)

    case Spec.Cache(id, inner) =>
      val ck                                               = (key, wire, fp)
      val entry                                            = st.cacheEntries.getOrElse(id, Map.empty).get(ck)
      def miss(s: StackState): List[(PResult, StackState)] =
        eval(inner, wire, key, fp, longFitsInt, s).map {
          case (ok: PResult.Ok, s2) =>
            ok -> s2.copy(cacheEntries =
              s2.cacheEntries.updated(id, s2.cacheEntries.getOrElse(id, Map.empty).updated(ck, ok))
            )
          case other => other
        }
      entry match {
        case Some(ok) =>
          val hit = List((ok.copy(reason = "CACHED"): PResult) -> st)
          if (st.uncertain.contains(id))
            hit ++ miss(st.copy(cacheEntries = st.cacheEntries.updated(id, Map.empty)))
          else hit
        case None => miss(st)
      }

    case Spec.Breaker(id, threshold, _, inner) =>
      if (st.breakerOpen.contains(id)) List(PResult.Thr("GENERAL") -> st)
      else
        eval(inner, wire, key, fp, longFitsInt, st).map { case (r, s) =>
          def reset   = s.copy(breakerFailures = s.breakerFailures.updated(id, 0))
          def failure = {
            val n  = s.breakerFailures.getOrElse(id, 0) + 1
            val s2 = s.copy(breakerFailures = s.breakerFailures.updated(id, n))
            if (n >= threshold) s2.copy(breakerOpen = s2.breakerOpen + id) else s2
          }
          r match {
            case _: PResult.Ok                          => r                      -> reset
            case PResult.Ret(c) if appCodes.contains(c) => r                      -> reset
            case PResult.Ret(_)                         => r                      -> failure
            case PResult.Thr(c) if appCodes.contains(c) => r                      -> reset
            case PResult.Thr(_)                         => PResult.Thr("GENERAL") -> failure
          }
        }

    case Spec.Deferred(inner) => eval(inner, wire, key, fp, longFitsInt, st)

    case Spec.Widen(inner) =>
      if (wire == Wire.Long && longFitsInt)
        eval(inner, Wire.Int, key, fp, longFitsInt, st).flatMap {
          case (PResult.Ret("TYPE_MISMATCH"), s) => eval(inner, Wire.Long, key, fp, longFitsInt, s)
          case other                             => List(other)
        }
      else eval(inner, wire, key, fp, longFitsInt, st)

    case Spec.Multi(firstSuccessful, children) =>
      def loop(cs: List[Spec], s: StackState): List[(PResult, StackState)] = cs match {
        case Nil =>
          List((if (firstSuccessful) PResult.Ret("GENERAL") else PResult.Ret("FLAG_NOT_FOUND")) -> s)
        case c :: rest =>
          eval(c, wire, key, fp, longFitsInt, s).flatMap { case (r, s2) =>
            r match {
              case _: PResult.Ok                                                 => List(r -> s2)
              case _ if firstSuccessful                                          => loop(rest, s2)
              case PResult.Ret("FLAG_NOT_FOUND") | PResult.Thr("FLAG_NOT_FOUND") => loop(rest, s2)
              case other                                                         => List(other -> s2)
            }
          }
      }
      loop(children, st)
  }

  /** How each leaf's configuration change reaches each cache: cache id -> leaf id -> reach. */
  def reach(spec: Spec): Map[Int, Map[Int, Reach]] = {
    // reach from the node `s` down to every leaf, as seen from directly above `s`
    def down(s: Spec): Map[Int, Reach] = s match {
      case Spec.Leaf(id, _)  => Map(id -> Reach.Sync)
      case Spec.Cache(_, in) => down(in).map { case (l, r) => l -> worse(r, Reach.Async) }
      case b: Spec.Breaker   => down(b.inner).map { case (l, r) => l -> worse(r, Reach.Async) }
      case Spec.Deferred(in) => down(in).map { case (l, _) => l -> Reach.Never }
      case Spec.Widen(in)    => down(in).map { case (l, _) => l -> Reach.Never }
      case Spec.Multi(_, cs) => cs.flatMap(down).toMap.map { case (l, r) => l -> worse(r, Reach.Async) }
    }
    def collect(s: Spec): Map[Int, Map[Int, Reach]] = s match {
      case _: Spec.Leaf       => Map.empty
      case Spec.Cache(id, in) => collect(in) + (id -> down(in))
      case b: Spec.Breaker    => collect(b.inner)
      case Spec.Deferred(in)  => collect(in)
      case Spec.Widen(in)     => collect(in)
      case Spec.Multi(_, cs)  => cs.map(collect).foldLeft(Map.empty[Int, Map[Int, Reach]])(_ ++ _)
    }
    collect(spec)
  }

  private def worse(a: Reach, b: Reach): Reach = (a, b) match {
    case (Reach.Never, _) | (_, Reach.Never) => Reach.Never
    case (Reach.Async, _) | (_, Reach.Async) => Reach.Async
    case _                                   => Reach.Sync
  }

  /** Apply a configuration-change event emitted by `leaf`. */
  def notify(st: StackState, leaf: Int): StackState = {
    val r = reach(st.spec)
    r.foldLeft(st) { case (s, (cache, leaves)) =>
      leaves.get(leaf) match {
        case Some(Reach.Sync)  => s.copy(cacheEntries = s.cacheEntries.updated(cache, Map.empty))
        case Some(Reach.Async) => s.copy(uncertain = s.uncertain + cache)
        case _                 => s
      }
    }
  }
}
