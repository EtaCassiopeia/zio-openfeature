package zio.openfeature

import scala.compiletime.{constValue, constValueTuple, summonAll}
import scala.deriving.Mirror
import scala.annotation.nowarn
import scala.util.Try

/** Codec between a domain type `A` and the value a provider carries — its ''wire'' value. For the built-in instances
  * the two coincide; a custom instance (`FlagType.mapped`, `FlagType.from`, or a hand-rolled one) may keep them apart,
  * e.g. a `Phase` enum carried as the string `"dual_write"`.
  *
  * '''Round-trip law:''' `decode(encode(a)) == Right(a)` for every `a: A`. Evaluation relies on it in three places, so
  * an instance that breaks it will see values come back different from what went in:
  *   - the default handed to the provider is `encode(default)`, and it comes back through `decode` when the provider
  *     serves it (`DEFAULT` reason);
  *   - a transaction caches `encode(value)` and serves a same-key re-read through `decode`;
  *   - a transaction override may be given as either the wire value or the domain value — the latter is accepted by
  *     round-tripping it through `encode` and `decode`, with `decode` the arbiter of what is an `A`.
  */
trait FlagType[A]:
  def typeName: String

  /** The representation the *provider* is asked for, which is not always the domain type.
    *
    * Evaluation dispatches on this, not on [[typeName]]: a domain type backed by a scalar (an enum-valued flag stored
    * as a string, a newtype over an int) must be resolved through that scalar's SDK method and then decoded. A scalar
    * case selects the matching resolver; [[WireType.Object]] selects the object path.
    *
    * The built-in instances, [[FlagType.mapped]] (which inherits its underlying instance's wire type) and `Option[A]`
    * (which takes `A`'s) set this themselves. The default reads the deprecated [[wireType]] through
    * [[WireType.fromName]], so an instance that overrides neither is resolved on the object path, and a legacy
    * `wireType` override keeps working (its spelling is matched case-insensitively).
    *
    * '''If you override this with a scalar, [[encode]] must return the matching boxed type''': `java.lang.Boolean`,
    * `String`, `java.lang.Integer`, `java.lang.Long`, `java.lang.Float` or `java.lang.Double` respectively. The pairing
    * is not checked at compile time; at evaluation time a mismatch fails with a `TypeMismatch` that names the domain
    * type, the declared wire type, and the type `encode` actually produced (#360). [[FlagType.mapped]] gets the pairing
    * right by construction, so prefer it where it fits.
    */
  @nowarn("cat=deprecation")
  def wire: WireType = WireType.fromName(wireType)

  /** The wire type as a free-form name, read only by the default [[wire]] through [[WireType.fromName]]. Defaults to
    * [[typeName]]. Override [[wire]] instead.
    */
  @deprecated("Override `wire` instead; `wireType` is read only through wire's default", "1.2.0")
  def wireType: String = typeName

  def decode(value: Any): Either[String, A]
  def encode(value: A): Any = value
  def defaultValue: A

object FlagType:
  def apply[A](using ft: FlagType[A]): FlagType[A] = ft
  def typeName[A](using ft: FlagType[A]): String   = ft.typeName

  // A Double is an exact Long when it is integral and inside [Long.MinValue, Long.MaxValue). The strict
  // upper bound rejects Long.MaxValue.toDouble, which rounds up to 2^63 and would saturate on .toLong.
  private def isExactLong(d: Double): Boolean =
    d == d.toLong.toDouble && d >= Long.MinValue.toDouble && d < Long.MaxValue.toDouble

  given booleanFlagType: FlagType[Boolean] with
    def typeName: String                            = "Boolean"
    override def wire: WireType                     = WireType.Boolean
    def defaultValue: Boolean                       = false
    def decode(value: Any): Either[String, Boolean] = value match
      case b: Boolean => Right(b)
      case s: String  => s.toBooleanOption.toRight(s"Cannot parse '$s' as Boolean")
      case null       => Left("Cannot convert null to Boolean")
      case _          => Left(s"Cannot convert ${value.getClass.getSimpleName} to Boolean")

  given stringFlagType: FlagType[String] with
    def typeName: String                           = "String"
    override def wire: WireType                    = WireType.String
    def defaultValue: String                       = ""
    def decode(value: Any): Either[String, String] = value match
      case s: String => Right(s)
      case null      => Left("Cannot convert null to String")
      case other     => Left(s"Cannot convert ${other.getClass.getSimpleName} to String")

  given intFlagType: FlagType[Int] with
    def typeName: String                        = "Int"
    override def wire: WireType                 = WireType.Int
    def defaultValue: Int                       = 0
    def decode(value: Any): Either[String, Int] = value match
      case i: Int                  => Right(i)
      case l: Long if l.isValidInt => Right(l.toInt)
      case l: Long                 => Left(s"Long value $l is out of Int range")
      case d: Double => if d.isValidInt then Right(d.toInt) else Left(s"Double value $d is not a valid Int")
      case n: Number =>
        val d = n.doubleValue()
        if d.isValidInt then Right(d.toInt) else Left(s"Numeric value $n is not a valid Int")
      case s: String => s.toIntOption.toRight(s"Cannot parse '$s' as Int")
      case null      => Left("Cannot convert null to Int")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Int")

  given longFlagType: FlagType[Long] with
    def typeName: String                         = "Long"
    override def wire: WireType                  = WireType.Long
    def defaultValue: Long                       = 0L
    def decode(value: Any): Either[String, Long] = value match
      case l: Long   => Right(l)
      case i: Int    => Right(i.toLong)
      case d: Double => if isExactLong(d) then Right(d.toLong) else Left(s"Double value $d is not a valid Long")
      case n: Number =>
        val d = n.doubleValue()
        if isExactLong(d) then Right(d.toLong) else Left(s"Numeric value $n is not a valid Long")
      case s: String => s.toLongOption.toRight(s"Cannot parse '$s' as Long")
      case null      => Left("Cannot convert null to Long")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Long")

  given doubleFlagType: FlagType[Double] with
    def typeName: String                           = "Double"
    override def wire: WireType                    = WireType.Double
    def defaultValue: Double                       = 0.0
    def decode(value: Any): Either[String, Double] = value match
      case d: Double => Right(d)
      case f: Float  => Right(f.toDouble)
      case i: Int    => Right(i.toDouble)
      case l: Long   => Right(l.toDouble)
      case n: Number => Right(n.doubleValue())
      case s: String => s.toDoubleOption.toRight(s"Cannot parse '$s' as Double")
      case null      => Left("Cannot convert null to Double")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Double")

  given floatFlagType: FlagType[Float] with
    def typeName: String                          = "Float"
    override def wire: WireType                   = WireType.Float
    def defaultValue: Float                       = 0.0f
    def decode(value: Any): Either[String, Float] = value match
      case f: Float  => Right(f)
      case d: Double =>
        // Rounding to Float precision is inherent and expected; only reject magnitude overflow,
        // which would silently turn a finite value into +/-Infinity.
        if d.isNaN || d.isInfinity || math.abs(d) <= java.lang.Float.MAX_VALUE.toDouble then Right(d.toFloat)
        else Left(s"Double value $d is out of Float range")
      case i: Int    => Right(i.toFloat)
      case l: Long   => Right(l.toFloat)
      case s: String => s.toFloatOption.toRight(s"Cannot parse '$s' as Float")
      case null      => Left("Cannot convert null to Float")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Float")

  given objectFlagType: FlagType[Map[String, Any]] with
    def typeName: String                                     = "Object"
    override def wire: WireType                              = WireType.Object
    def defaultValue: Map[String, Any]                       = Map.empty
    def decode(value: Any): Either[String, Map[String, Any]] = value match
      case m: Map[?, ?] =>
        Try(m.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case m: java.util.Map[?, ?] =>
        import scala.jdk.CollectionConverters.*
        Try(m.asScala.toMap.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case null => Left("Cannot convert null to Object")
      case _    =>
        Left(s"Cannot convert ${value.getClass.getSimpleName} to Object")

  given optionFlagType[A](using elem: FlagType[A]): FlagType[Option[A]] with OptionalFlagType[A] with
    def underlying: FlagType[A] = elem
    def typeName: String        = s"Option[${underlying.typeName}]"
    // Resolved through the element's resolver, so `Option[String]` reaches the provider's string method. A `None`
    // default is sent as a sentinel and read back as `None` by evaluation (see `OptionalFlagType`, #439).
    override def wire: WireType                       = underlying.wire
    def defaultValue: Option[A]                       = None
    def decode(value: Any): Either[String, Option[A]] = value match
      case null    => Right(None)
      case None    => Right(None)
      case Some(v) => underlying.decode(v).map(Some(_))
      case other   => underlying.decode(other).map(Some(_))
    // Carried as the underlying wire value or null — exactly what `decode` accepts back. The inherited identity
    // `encode` would hand `Some(domainValue)` to `decode`, breaking the round-trip law for `Option[Custom]` (#359).
    override def encode(value: Option[A]): Any = value match
      case Some(a) => underlying.encode(a)
      case None    => null

  given listFlagType[A](using underlying: FlagType[A]): FlagType[List[A]] with
    def typeName: String                            = s"List[${underlying.typeName}]"
    def defaultValue: List[A]                       = List.empty
    def decode(value: Any): Either[String, List[A]] = value match
      case list: List[?] =>
        list.foldRight[Either[String, List[A]]](Right(Nil)) { (elem, acc) =>
          for
            decoded <- underlying.decode(elem)
            rest    <- acc
          yield decoded :: rest
        }
      case seq: Seq[?]              => decode(seq.toList)
      case arr: Array[?]            => decode(arr.toList)
      case jlist: java.util.List[?] =>
        import scala.jdk.CollectionConverters.*
        decode(jlist.asScala.toList)
      case null => Left("Cannot convert null to List")
      case _    => Left(s"Cannot convert ${value.getClass.getSimpleName} to List")
    override def encode(value: List[A]): Any = value.map(underlying.encode)

  /** Builds an instance whose [[FlagType.wire]] is read from `name` by [[WireType.fromName]], so unless `name` is one
    * of the scalar names it is resolved on the object path. Only the built-in [[objectFlagType]] takes the raw-map
    * shortcut there: an instance built here always has its `decode` run, even when its name is `"Object"`. For a type
    * carried over the wire as a *scalar* (a string-backed enum, a newtype over an int) prefer [[mapped]], which
    * inherits its underlying wire type and keeps `encode` consistent with it; failing that, override `wire` and
    * `encode` together.
    */
  def from[A](
    name: String,
    default: A,
    decoder: Any => Either[String, A],
    encoder: A => Any = identity[A]
  ): FlagType[A] = new FlagType[A]:
    def typeName: String                      = name
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = decoder(value)
    override def encode(value: A): Any        = encoder(value)

  /** Derives a `FlagType[A]` from `A`'s structure (#348). Scala 3 only — the 2.13 twin keeps `from`/`mapped`.
    *
    * {{{
    * enum Plan derives FlagType:
    *   case Free, Premium, Enterprise
    *
    * final case class Rollout(tier: String, pct: Int = 10, note: Option[String]) derives FlagType
    * }}}
    *
    * ==Sum of singleton cases==
    *
    * An `enum` whose cases take no parameters derives a '''string''' codec over the case labels: `wire` is
    * `WireType.String`, so the flag is resolved through the provider's string method and decoded — the single most
    * common feature-flag shape. `encode` emits the canonical label as declared; `decode` matches case-insensitively.
    * `defaultValue` is the '''first declared case'''.
    *
    * Only parameterless cases are supported. A sum with a parameterised case fails to compile with a missing `ValueOf`
    * instance for that case; write such a type with [[from]] or [[mapped]] instead.
    *
    * ==Product==
    *
    * A product derives a `Map[String, Any]` codec, field by field through each field's own `FlagType` (so nested
    * products, `Option` and `List` fields all work). `wire` is `WireType.Object` whatever the type is called, so it is
    * resolved on the object path. Unknown keys in the payload are ignored, which keeps forward-compatible payloads
    * working.
    *
    * An absent key resolves in this order: the field's declared Scala default if it has one; otherwise whatever the
    * field's own instance makes of `null` — which is how an `Option` field becomes `None`; otherwise a `Left` naming
    * the field.
    *
    * `defaultValue` is built from the fields' own `defaultValue`s, i.e. a zero (`""`, `0`, `None`) rather than the
    * declared Scala defaults. That asymmetry with decoding is deliberate: `defaultValue` is a type-level zero that is
    * never consulted on the evaluation path, whereas the Scala defaults describe how to read a real payload.
    *
    * ==Precedence==
    *
    * This is intentionally not a `given`, so it never enters implicit search and the built-in instances keep priority
    * for their own shapes — `Option` has a `Mirror.SumOf`, so an implicit derivation would otherwise hijack it.
    */
  // NOTE: both branches are expanded here rather than delegated to `derivedSum`/`derivedProduct` helpers. Passing the
  // mirror to a helper whose parameter is declared `Mirror.SumOf[A]` WIDENS it, which erases the concrete
  // `MirroredElemTypes`/`MirroredElemLabels` this derivation reads — `summonFrom` then sees an abstract type, finds
  // nothing, and the whole thing fails as if every enum case were parameterised. The refinement only survives inside
  // this inline body.
  inline def derived[A](using m: Mirror.Of[A]): FlagType[A] =
    inline m match
      case s: Mirror.SumOf[A] =>
        sumInstance[A](
          constValue[s.MirroredLabel],
          constValueTuple[s.MirroredElemLabels].productIterator.map(_.asInstanceOf[String]).toVector,
          summonAll[Tuple.Map[s.MirroredElemTypes, ValueOf]].productIterator
            .map(_.asInstanceOf[ValueOf[A]].value)
            .toVector,
          s
        )
      case p: Mirror.ProductOf[A] =>
        productInstance[A](
          constValue[p.MirroredLabel],
          constValueTuple[p.MirroredElemLabels].productIterator.map(_.asInstanceOf[String]).toVector,
          summonAll[Tuple.Map[p.MirroredElemTypes, FlagType]].productIterator
            .map(_.asInstanceOf[FlagType[Any]])
            .toVector,
          p
        )

  // Split out of the inline body so the code duplicated at each derivation site stays small. These take
  // already-computed values, so widening is harmless here.
  private def sumInstance[A](
    name: String,
    labels: Vector[String],
    cases: Vector[A],
    mirror: Mirror.SumOf[A]
  ): FlagType[A] = new FlagType[A]:
    def typeName: String        = name
    override def wire: WireType = WireType.String
    @nowarn("cat=deprecation")
    override def wireType: String      = wire.name
    def defaultValue: A                = cases.head
    override def encode(value: A): Any = labels(mirror.ordinal(value))

    def decode(value: Any): Either[String, A] = value match
      case str: String =>
        labels.indexWhere(_.equalsIgnoreCase(str)) match
          case -1 => Left(s"Unknown $name: '$str' (expected one of ${labels.mkString(", ")})")
          case i  => Right(cases(i))
      case null  => Left(s"Cannot convert null to $name")
      case other => Left(s"Cannot convert ${other.getClass.getSimpleName} to $name")

  private def productInstance[A](
    name: String,
    labels: Vector[String],
    fields: Vector[FlagType[Any]],
    mirror: Mirror.ProductOf[A]
  ): FlagType[A] = new FlagType[A]:

    // For a case class the Mirror IS the companion object, so Scala-declared field defaults are reachable as
    // `apply$default$N`. Probed by name rather than by catching NoSuchMethodException, so a type with no defaults
    // costs nothing and no exception is swallowed.
    // Matched by EXACT name, never by suffix. `<methodName>$default$<n>` is the scheme for every defaulted
    // parameter on the class, not just the constructor's — so a companion holding an unrelated defaulted method
    // (`def parse(raw: String, strict: Boolean = true)` emits `parse$default$2`) would be picked up by position and
    // supply that method's default as field 2's, either throwing from `fromProduct` or, when the types happen to
    // agree, decoding a silently wrong value. Both constructor names are tried because either may be the one
    // emitted. Resolved once per instance, so only `invoke` is per-decode.
    private val defaults: Vector[Option[() => Any]] =
      val byName = mirror.getClass.getMethods.iterator
        .filter(_.getParameterCount == 0)
        .map(m => m.getName -> m)
        .toMap
      labels.indices.map { i =>
        val n = i + 1
        byName
          .get(s"apply$$default$$$n")
          .orElse(byName.get(s"$$lessinit$$greater$$default$$$n"))
          .map(m => () => m.invoke(mirror))
      }.toVector

    def typeName: String = name
    // Fixed, not read from the name: a product called `Double` is still a map on the wire.
    override def wire: WireType = WireType.Object

    private lazy val zero: A = mirror.fromProduct(Tuple.fromArray(fields.map(_.defaultValue).toArray))
    def defaultValue: A      = zero

    override def encode(value: A): Any =
      labels.iterator
        .zip(value.asInstanceOf[Product].productIterator)
        .zip(fields.iterator)
        .map { case ((label, fieldValue), ft) => label -> ft.encode(fieldValue) }
        .toMap

    def decode(value: Any): Either[String, A] = value match
      case m: Map[?, ?]           => decodeFields(m.asInstanceOf[Map[String, Any]])
      case m: java.util.Map[?, ?] =>
        import scala.jdk.CollectionConverters.*
        decodeFields(m.asScala.toMap.asInstanceOf[Map[String, Any]])
      case null  => Left(s"Cannot convert null to $name")
      case other => Left(s"Cannot convert ${other.getClass.getSimpleName} to $name")

    private def decodeFields(payload: Map[String, Any]): Either[String, A] =
      labels.indices
        .foldLeft[Either[String, List[Any]]](Right(Nil)) { (acc, i) =>
          acc.flatMap(values => fieldValue(payload, i).map(_ :: values))
        }
        .map(values => mirror.fromProduct(Tuple.fromArray(values.reverse.toArray)))

    private def fieldValue(payload: Map[String, Any], index: Int): Either[String, Any] =
      val label = labels(index)
      val ft    = fields(index)
      payload.get(label) match
        case Some(raw) => ft.decode(raw).left.map(reason => s"$name.$label: $reason")
        case None      =>
          defaults(index) match
            // `invoke` is guarded: a reflective call can fail (an inaccessible companion, a module boundary) and
            // that must stay inside the `Either` contract rather than escaping `decode` as a defect.
            case Some(readDefault) =>
              Try(readDefault()).toEither.left.map(e => s"$name.$label: reading the declared default failed: $e")
            case None =>
              // `decode(null)` is a capability probe, not a data-path decode: an instance that accepts null (every
              // `Option`) treats an absent key as its empty value. Wrapped in `Try` because the instance may be a
              // third-party one that throws on null rather than returning a `Left`.
              Try(ft.decode(null)).toOption.flatMap(_.toOption) match
                case Some(empty) => Right(empty)
                case None        => Left(s"$name.$label: missing key '$label'")

  def mapped[A, B](name: String, default: A)(map: B => A, contramap: A => B)(using
    underlying: FlagType[B]
  ): FlagType[A] = new FlagType[A]:
    def typeName: String = name
    // A mapped instance is by construction carried over the wire as its underlying type, so it inherits that
    // wire type — this is what makes `mapped` scalar-backed types evaluatable without any user action.
    override def wire: WireType = underlying.wire
    // Kept in step with `wire` for code that still reads the deprecated name.
    @nowarn("cat=deprecation")
    override def wireType: String             = wire.name
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = underlying.decode(value).map(map)
    override def encode(value: A): Any        = underlying.encode(contramap(value))
