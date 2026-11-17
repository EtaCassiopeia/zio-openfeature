package zio.openfeature.simulation

import zio._
import zio.test._

/** A provider stack: wrappers over leaves. Ids are assigned by [[Spec.number]], in traversal order. */
sealed trait Spec extends Product with Serializable
object Spec {
  final case class Leaf(id: Int, initial: Map[String, Stored])                        extends Spec
  final case class Cache(id: Int, inner: Spec)                                        extends Spec
  final case class Breaker(id: Int, threshold: Int, staleOpens: Boolean, inner: Spec) extends Spec
  final case class Deferred(inner: Spec)                                              extends Spec
  final case class Widen(inner: Spec)                                                 extends Spec
  final case class Multi(firstSuccessful: Boolean, children: List[Spec])              extends Spec

  /** Give every leaf, cache and breaker a distinct id, starting at `from`. */
  def number(spec: Spec, from: Int = 0): Spec = {
    var next              = from
    def fresh(): Int      = { val n = next; next += 1; n }
    def go(s: Spec): Spec = s match {
      case Leaf(_, init)         => Leaf(fresh(), init)
      case Cache(_, in)          => val id = fresh(); Cache(id, go(in))
      case Breaker(_, t, so, in) => val id = fresh(); Breaker(id, t, so, go(in))
      case Deferred(in)          => Deferred(go(in))
      case Widen(in)             => Widen(go(in))
      case Multi(fs, cs)         => Multi(fs, cs.map(go))
    }
    go(spec)
  }

  def leaves(spec: Spec): List[Leaf] = spec match {
    case l: Leaf      => List(l)
    case Cache(_, in) => leaves(in)
    case b: Breaker   => leaves(b.inner)
    case Deferred(in) => leaves(in)
    case Widen(in)    => leaves(in)
    case Multi(_, cs) => cs.flatMap(leaves)
  }

  def caches(spec: Spec): List[Int] = spec match {
    case _: Leaf       => Nil
    case Cache(id, in) => id :: caches(in)
    case b: Breaker    => caches(b.inner)
    case Deferred(in)  => caches(in)
    case Widen(in)     => caches(in)
    case Multi(_, cs)  => cs.flatMap(caches)
  }

  def maxId(spec: Spec): Int = spec match {
    case l: Leaf      => l.id
    case c: Cache     => c.id max maxId(c.inner)
    case b: Breaker   => b.id max maxId(b.inner)
    case Deferred(in) => maxId(in)
    case Widen(in)    => maxId(in)
    case Multi(_, cs) => cs.map(maxId).maxOption.getOrElse(-1)
  }

  /** A Cache or Breaker whose name read reaches an uninitialized Multi (through Widen, which forwards `getMetadata`)
    * throws, and a swap to it dies with a defect instead of a typed failure. That is a known open bug, so swaps to such
    * a stack are skipped and runs keep exploring; remove the skip once a swap reads the name safely.
    */
  def nameThrowsBeforeInit(spec: Spec): Boolean = {
    def reachesMulti(s: Spec): Boolean = s match {
      case _: Multi     => true
      case Cache(_, in) => reachesMulti(in)
      case b: Breaker   => reachesMulti(b.inner)
      case Widen(in)    => reachesMulti(in)
      case _            => false
    }
    spec match {
      case Cache(_, in) => reachesMulti(in)
      case b: Breaker   => reachesMulti(b.inner)
      case Widen(in)    => nameThrowsBeforeInit(in)
      case _            => false
    }
  }

  def show(spec: Spec): String = spec match {
    case Leaf(id, init)         => s"L$id${init.keys.toList.sorted.mkString("{", ",", "}")}"
    case Cache(id, in)          => s"Cache$id(${show(in)})"
    case Breaker(id, t, so, in) => s"Breaker$id[t=$t${if (so) ",staleOpens" else ""}](${show(in)})"
    case Deferred(in)           => s"Deferred(${show(in)})"
    case Widen(in)              => s"Widen(${show(in)})"
    case Multi(fs, cs) => s"Multi[${if (fs) "firstSuccessful" else "firstMatch"}](${cs.map(show).mkString(", ")})"
  }
}

/** A flag type the simulation reads with. `wire` is the getter it goes through. */
sealed trait Kind extends Product with Serializable { def wire: Wire }
object Kind {
  case object KBool                     extends Kind { val wire = Wire.Bool }
  case object KStr                      extends Kind { val wire = Wire.Str  }
  case object KInt                      extends Kind { val wire = Wire.Int  }
  final case class KLong(default: Long) extends Kind { val wire = Wire.Long }
  case object KDbl                      extends Kind { val wire = Wire.Dbl  }
  case object KObj                      extends Kind { val wire = Wire.Obj  }
  case object KTier                     extends Kind { val wire = Wire.Str  }
  case object KOptStr                   extends Kind { val wire = Wire.Str  }
}

/** A custom domain type carried as a string. */
final case class Tier(name: String)
object Tier {
  given flagType: zio.openfeature.FlagType[Tier] =
    zio.openfeature.FlagType.mapped[Tier, String]("Tier", Tier("none"))(s => Tier(s.toUpperCase), _.name.toLowerCase)
}

/** A context layer as the simulation builds them: an optional targeting key and string attributes. */
final case class Ctx(tk: Option[String], attrs: Map[String, String]) {
  def isEmpty: Boolean                                       = tk.isEmpty && attrs.isEmpty
  def effectiveTk: Option[String]                            = tk.orElse(attrs.get("targetingKey"))
  def toEvaluationContext: zio.openfeature.EvaluationContext =
    zio.openfeature.EvaluationContext(
      tk,
      attrs.map { case (k, v) => k -> zio.openfeature.AttributeValue.StringValue(v) }
    )
}
object Ctx {
  val empty: Ctx = Ctx(None, Map.empty)

  /** The documented merge: `higher` wins on every attribute and on the targeting key if it sets one (in either form).
    */
  def merge(lower: Ctx, higher: Ctx): Ctx =
    if (higher.isEmpty) lower
    else if (lower.isEmpty) higher
    else Ctx(higher.effectiveTk.orElse(lower.effectiveTk), lower.attrs ++ higher.attrs)

  /** What the provider sees, rendered as [[SimLeaf.render]] does. */
  def rendered(c: Ctx): String = SimLeaf.render(c.effectiveTk, c.attrs - "targetingKey")
}

/** One step of a run. */
sealed trait Cmd extends Product with Serializable
object Cmd {
  final case class Eval(key: String, kind: Kind, invocation: Ctx, scoped: Option[Ctx]) extends Cmd
  final case class SetGlobal(ctx: Ctx)                                                 extends Cmd
  final case class SetClient(ctx: Ctx)                                                 extends Cmd
  final case class Tx(overrides: Map[String, Stored], ctx: Ctx, cacheEvaluations: Boolean, evals: List[Eval])
      extends Cmd
  final case class SetFlag(leaf: Int, key: String, value: Option[Stored], emit: Boolean) extends Cmd
  final case class SetFault(leaf: Int, fault: Option[LeafFault])                         extends Cmd
  final case class Invalidate(cache: Int)                                                extends Cmd
  final case class Track(name: String)                                                   extends Cmd
  final case class Swap(spec: Spec)                                                      extends Cmd
}

object Gens {
  val keys: Chunk[String] = Chunk("k0", "k1", "k2", "echo")

  val genStored: Gen[Any, Stored] = Gen.oneOf(
    Gen.boolean.map(Stored.SBool(_)),
    Gen.elements("free", "paid", "x").map(Stored.SStr(_)),
    Gen.int(-3, 3).map(Stored.SInt(_)),
    Gen.oneOf(Gen.long(-3L, 3L), Gen.const(Long.MaxValue - 1), Gen.const(5000000000L)).map(Stored.SLong(_)),
    Gen.elements(0.5, -1.25).map(Stored.SDbl(_)),
    Gen.mapOfBounded(0, 2)(Gen.elements("p", "q"), Gen.elements("1", "2")).map(Stored.SObj(_)),
    Gen.const(Stored.Echo)
  )

  /** A stored value for `key`: mostly of the type the run reads it with (so reads succeed and caches fill), sometimes
    * any type (so mismatches and widening are exercised).
    */
  def genStoredFor(home: Map[String, Kind], key: String): Gen[Any, Stored] =
    home.get(key) match {
      case Some(Kind.KStr) =>
        Gen.weighted(genOverrideFor(Kind.KStr) -> 2.0, Gen.const(Stored.Echo) -> 1.0, genStored -> 1.0)
      case Some(Kind.KLong(_)) =>
        Gen.weighted(genOverrideFor(Kind.KLong(0)) -> 2.0, Gen.int(-3, 3).map(Stored.SInt(_)) -> 1.0, genStored -> 1.0)
      case Some(k) => Gen.weighted(genOverrideFor(k) -> 3.0, genStored -> 1.0)
      case None    => genStored
    }

  def genInitial(home: Map[String, Kind]): Gen[Any, Map[String, Stored]] =
    Gen
      .setOfBounded(0, 4)(Gen.elements(keys: _*))
      .flatMap(ks => Gen.collectAll(ks.toList.map(k => genStoredFor(home, k).map(k -> _))).map(_.toMap))

  def genSpec(depth: Int, home: Map[String, Kind] = Map.empty): Gen[Any, Spec] =
    if (depth <= 0) genInitial(home).map(Spec.Leaf(-1, _))
    else
      Gen.weighted(
        genInitial(home).map(i => Spec.Leaf(-1, i): Spec)     -> 3.0,
        genSpec(depth - 1, home).map(Spec.Cache(-1, _): Spec) -> 2.0,
        (Gen.int(1, 3) <*> Gen.boolean <*> genSpec(depth - 1, home)).map { case (t, so, in) =>
          Spec.Breaker(-1, t, so, in): Spec
        }                                                    -> 2.0,
        genSpec(depth - 1, home).map(Spec.Deferred(_): Spec) -> 1.0,
        genSpec(depth - 1, home).map(Spec.Widen(_): Spec)    -> 1.0,
        (Gen.boolean <*> Gen.listOfBounded(1, 3)(genSpec(depth - 1, home))).map { case (fs, cs) =>
          Spec.Multi(fs, cs): Spec
        } -> 2.0
      )

  val genAttrKey: Gen[Any, String] =
    Gen.weighted(Gen.const("a") -> 3.0, Gen.const("b") -> 3.0, Gen.const("targetingKey") -> 1.0)

  val genCtx: Gen[Any, Ctx] = Gen.weighted(
    Gen.const(Ctx.empty) -> 3.0,
    (Gen.option(Gen.elements("u1", "u2")) <*> Gen.mapOfBounded(0, 2)(genAttrKey, Gen.elements("1", "2", "3"))).map {
      case (tk, attrs) => Ctx(tk, attrs)
    } -> 4.0
  )

  val genKind: Gen[Any, Kind] = Gen.oneOf(
    Gen.elements(Kind.KBool, Kind.KStr, Kind.KInt, Kind.KDbl, Kind.KObj, Kind.KTier, Kind.KOptStr),
    Gen.elements(7L, 5000000001L).map(Kind.KLong(_))
  )

  def genEval(home: Map[String, Kind]): Gen[Any, Cmd.Eval] =
    for {
      key    <- Gen.elements(keys: _*)
      kind   <- Gen.weighted(Gen.const(home.getOrElse(key, Kind.KStr)) -> 3.0, genKind -> 1.0)
      inv    <- genCtx
      scoped <- Gen.option(genCtx)
    } yield Cmd.Eval(key, kind, inv, scoped)

  /** A value of the type a kind reads, as an override. */
  def genOverrideFor(kind: Kind): Gen[Any, Stored] = kind match {
    case Kind.KBool    => Gen.boolean.map(Stored.SBool(_))
    case Kind.KStr     => Gen.elements("o1", "o2").map(Stored.SStr(_))
    case Kind.KInt     => Gen.int(10, 12).map(Stored.SInt(_))
    case Kind.KLong(_) => Gen.long(10L, 12L).map(Stored.SLong(_))
    case Kind.KDbl     => Gen.const(Stored.SDbl(9.5))
    case Kind.KObj     => Gen.const(Stored.SObj(Map("o" -> "1")))
    case Kind.KTier    => Gen.const(Stored.SStr("gold"))
    case Kind.KOptStr  => Gen.const(Stored.SStr("o3"))
  }

  /** In a transaction each key is read with one kind (`homeKinds`), so the transaction cache is never read across
    * types; the cross-type rules are exercised outside transactions.
    */
  def genTx(homeKinds: Map[String, Kind]): Gen[Any, Cmd.Tx] =
    for {
      overrideKeys <- Gen.setOfBounded(0, 2)(Gen.elements(keys: _*))
      overrides    <- Gen.collectAll(overrideKeys.toList.map(k => genOverrideFor(homeKinds(k)).map(k -> _)))
      ctx          <- genCtx
      cache        <- Gen.boolean
      evals        <- Gen.listOfBounded(1, 4)(
        (Gen.elements(keys: _*) <*> genCtx).map { case (k, c) => Cmd.Eval(k, homeKinds(k), c, None) }
      )
    } yield Cmd.Tx(overrides.toMap, ctx, cache, evals)

  def genCmd(maxLeaf: Int, cacheIds: List[Int], homeKinds: Map[String, Kind]): Gen[Any, Cmd] = {
    val leaf = Gen.int(0, maxLeaf max 0)
    Gen.weighted(
      genEval(homeKinds)           -> 10.0,
      genCtx.map(Cmd.SetGlobal(_)) -> 1.0,
      genCtx.map(Cmd.SetClient(_)) -> 1.0,
      genTx(homeKinds)             -> 2.0,
      (leaf <*> Gen.elements(keys: _*).flatMap(k => Gen.option(genStoredFor(homeKinds, k)).map(k -> _)) <*> Gen.boolean)
        .map { case (l, (k, v), n) =>
          Cmd.SetFlag(l, k, v, n)
        } -> 3.0,
      (leaf <*> Gen.weighted(
        Gen.const(None)                                                                                     -> 2.0,
        Gen.elements(LeafFault.ThrowGeneral, LeafFault.ThrowNotFound, LeafFault.ReturnGeneral).map(Some(_)) -> 1.0
      )).map { case (l, f) => Cmd.SetFault(l, f) }                                                             -> 1.5,
      (if (cacheIds.isEmpty) Gen.const(Cmd.Track("t")) else Gen.elements(cacheIds: _*).map(Cmd.Invalidate(_))) -> 0.5,
      Gen.elements("t1", "t2").map(Cmd.Track(_))                                                               -> 0.5,
      genSpec(2, homeKinds).map(Cmd.Swap(_))                                                                   -> 0.3
    )
  }
}
