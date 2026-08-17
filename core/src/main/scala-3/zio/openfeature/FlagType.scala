package zio.openfeature

import scala.util.Try

trait FlagType[A]:
  def typeName: String

  /** The representation the *provider* is asked for, which is not always the domain type.
    *
    * Evaluation dispatches on this, not on [[typeName]]: a domain type backed by a scalar — an enum-valued flag stored
    * as a string, a newtype over an int — must be resolved through that scalar's SDK method and then decoded. One of
    * `"Boolean" | "String" | "Int" | "Long" | "Float" | "Double"` selects the matching resolver; anything else
    * (including `"Object"`) is resolved on the object path.
    *
    * Defaults to [[typeName]], so every instance that does not set it keeps its existing behaviour.
    *
    * '''If you override this, [[encode]] must return the matching boxed type''' — `java.lang.Boolean`, `String`,
    * `java.lang.Integer`, `java.lang.Long`, `java.lang.Float` or `java.lang.Double` respectively. The pairing is not
    * checked at compile time and a mismatch surfaces as a `ClassCastException` when the provider is called.
    * [[FlagType.mapped]] gets the pairing right by construction, so prefer it where it fits.
    */
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
    def typeName: String      = "Boolean"
    def defaultValue: Boolean = false
    def decode(value: Any): Either[String, Boolean] = value match
      case b: Boolean => Right(b)
      case s: String  => s.toBooleanOption.toRight(s"Cannot parse '$s' as Boolean")
      case null       => Left("Cannot convert null to Boolean")
      case _          => Left(s"Cannot convert ${value.getClass.getSimpleName} to Boolean")

  given stringFlagType: FlagType[String] with
    def typeName: String     = "String"
    def defaultValue: String = ""
    def decode(value: Any): Either[String, String] = value match
      case s: String => Right(s)
      case null      => Left("Cannot convert null to String")
      case other     => Left(s"Cannot convert ${other.getClass.getSimpleName} to String")

  given intFlagType: FlagType[Int] with
    def typeName: String  = "Int"
    def defaultValue: Int = 0
    def decode(value: Any): Either[String, Int] = value match
      case i: Int                  => Right(i)
      case l: Long if l.isValidInt => Right(l.toInt)
      case l: Long                 => Left(s"Long value $l is out of Int range")
      case d: Double => if d.isValidInt then Right(d.toInt) else Left(s"Double value $d is not a valid Int")
      case n: Number =>
        val d = n.doubleValue()
        if d.isValidInt then Right(d.toInt) else Left(s"Numeric value $n is not a valid Int")
      case s: String => s.toIntOption.toRight(s"Cannot parse '$s' as Int")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Int")

  given longFlagType: FlagType[Long] with
    def typeName: String   = "Long"
    def defaultValue: Long = 0L
    def decode(value: Any): Either[String, Long] = value match
      case l: Long   => Right(l)
      case i: Int    => Right(i.toLong)
      case d: Double => if isExactLong(d) then Right(d.toLong) else Left(s"Double value $d is not a valid Long")
      case n: Number =>
        val d = n.doubleValue()
        if isExactLong(d) then Right(d.toLong) else Left(s"Numeric value $n is not a valid Long")
      case s: String => s.toLongOption.toRight(s"Cannot parse '$s' as Long")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Long")

  given doubleFlagType: FlagType[Double] with
    def typeName: String     = "Double"
    def defaultValue: Double = 0.0
    def decode(value: Any): Either[String, Double] = value match
      case d: Double => Right(d)
      case f: Float  => Right(f.toDouble)
      case i: Int    => Right(i.toDouble)
      case l: Long   => Right(l.toDouble)
      case n: Number => Right(n.doubleValue())
      case s: String => s.toDoubleOption.toRight(s"Cannot parse '$s' as Double")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Double")

  given floatFlagType: FlagType[Float] with
    def typeName: String    = "Float"
    def defaultValue: Float = 0.0f
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
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Float")

  given objectFlagType: FlagType[Map[String, Any]] with
    def typeName: String               = "Object"
    def defaultValue: Map[String, Any] = Map.empty
    def decode(value: Any): Either[String, Map[String, Any]] = value match
      case m: Map[?, ?] =>
        Try(m.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case m: java.util.Map[?, ?] =>
        import scala.jdk.CollectionConverters.*
        Try(m.asScala.toMap.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case _ =>
        Left(s"Cannot convert ${value.getClass.getSimpleName} to Object")

  given optionFlagType[A](using underlying: FlagType[A]): FlagType[Option[A]] with
    def typeName: String        = s"Option[${underlying.typeName}]"
    def defaultValue: Option[A] = None
    def decode(value: Any): Either[String, Option[A]] = value match
      case null    => Right(None)
      case None    => Right(None)
      case Some(v) => underlying.decode(v).map(Some(_))
      case other   => underlying.decode(other).map(Some(_))

  given listFlagType[A](using underlying: FlagType[A]): FlagType[List[A]] with
    def typeName: String      = s"List[${underlying.typeName}]"
    def defaultValue: List[A] = List.empty
    def decode(value: Any): Either[String, List[A]] = value match
      case list: List[?] =>
        list.foldRight[Either[String, List[A]]](Right(Nil)) { (elem, acc) =>
          for
            decoded <- underlying.decode(elem)
            rest    <- acc
          yield decoded :: rest
        }
      case seq: Seq[?]   => decode(seq.toList)
      case arr: Array[?] => decode(arr.toList)
      case jlist: java.util.List[?] =>
        import scala.jdk.CollectionConverters.*
        decode(jlist.asScala.toList)
      case _ => Left(s"Cannot convert ${value.getClass.getSimpleName} to List")

  /** Builds an instance that leaves [[FlagType.wireType]] at `name`, so unless `name` happens to be one of the scalar
    * wire names it is resolved on the object path. For a type carried over the wire as a *scalar* — a string-backed
    * enum, a newtype over an int — prefer [[mapped]], which inherits its underlying `wireType` and keeps `encode`
    * consistent with it; failing that, override `wireType` and `encode` together.
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

  def mapped[A, B](name: String, default: A)(map: B => A, contramap: A => B)(using
    underlying: FlagType[B]
  ): FlagType[A] = new FlagType[A]:
    def typeName: String = name
    // A mapped instance is by construction carried over the wire as its underlying type, so it inherits that
    // wire type — this is what makes `mapped` scalar-backed types evaluatable without any user action.
    override def wireType: String             = underlying.wireType
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = underlying.decode(value).map(map)
    override def encode(value: A): Any        = underlying.encode(contramap(value))
