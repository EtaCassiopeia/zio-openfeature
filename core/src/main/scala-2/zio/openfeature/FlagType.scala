package zio.openfeature

import scala.util.Try

/** Codec between a domain type `A` and the value a provider carries — its ''wire'' value. For the built-in instances
  * the two coincide; a custom instance (`FlagType.mapped`, `FlagType.from`, or a hand-rolled one) may keep them apart,
  * e.g. a `Phase` enum carried as the string `"dual_write"`.
  *
  * '''Round-trip law:''' `decode(encode(a)) == Right(a)` for every `a: A`. Evaluation relies on it in three places, so
  * an instance that breaks it will see values come back different from what went in:
  *   - for a scalar [[wireType]] the default handed to the provider is `encode(default)`, and it comes back through
  *     `decode` when the provider serves it (`DEFAULT` reason); a custom object-backed type is resolved with an empty
  *     default;
  *   - a transaction caches `encode(value)` and serves a same-key re-read through `decode`;
  *   - a transaction override may be given as either the wire value or the domain value — the latter is accepted by
  *     round-tripping it through `encode` and `decode`, with `decode` the arbiter of what is an `A`.
  */
trait FlagType[A] {
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
}

object FlagType {
  def apply[A](implicit ft: FlagType[A]): FlagType[A] = ft
  def typeName[A](implicit ft: FlagType[A]): String   = ft.typeName

  // A Double is an exact Long when it is integral and inside [Long.MinValue, Long.MaxValue). The strict
  // upper bound rejects Long.MaxValue.toDouble, which rounds up to 2^63 and would saturate on .toLong.
  private def isExactLong(d: Double): Boolean =
    d == d.toLong.toDouble && d >= Long.MinValue.toDouble && d < Long.MaxValue.toDouble

  implicit val booleanFlagType: FlagType[Boolean] = new FlagType[Boolean] {
    def typeName: String      = "Boolean"
    def defaultValue: Boolean = false
    def decode(value: Any): Either[String, Boolean] = value match {
      case b: Boolean => Right(b)
      case s: String  => s.toBooleanOption.toRight(s"Cannot parse '$s' as Boolean")
      case null       => Left("Cannot convert null to Boolean")
      case _          => Left(s"Cannot convert ${value.getClass.getSimpleName} to Boolean")
    }
  }

  implicit val stringFlagType: FlagType[String] = new FlagType[String] {
    def typeName: String     = "String"
    def defaultValue: String = ""
    def decode(value: Any): Either[String, String] = value match {
      case s: String => Right(s)
      case null      => Left("Cannot convert null to String")
      case other     => Left(s"Cannot convert ${other.getClass.getSimpleName} to String")
    }
  }

  implicit val intFlagType: FlagType[Int] = new FlagType[Int] {
    def typeName: String  = "Int"
    def defaultValue: Int = 0
    def decode(value: Any): Either[String, Int] = value match {
      case i: Int                  => Right(i)
      case l: Long if l.isValidInt => Right(l.toInt)
      case l: Long                 => Left(s"Long value $l is out of Int range")
      case d: Double               => if (d.isValidInt) Right(d.toInt) else Left(s"Double value $d is not a valid Int")
      case n: Number =>
        val d = n.doubleValue()
        if (d.isValidInt) Right(d.toInt) else Left(s"Numeric value $n is not a valid Int")
      case s: String => s.toIntOption.toRight(s"Cannot parse '$s' as Int")
      case null      => Left("Cannot convert null to Int")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Int")
    }
  }

  implicit val longFlagType: FlagType[Long] = new FlagType[Long] {
    def typeName: String   = "Long"
    def defaultValue: Long = 0L
    def decode(value: Any): Either[String, Long] = value match {
      case l: Long   => Right(l)
      case i: Int    => Right(i.toLong)
      case d: Double => if (isExactLong(d)) Right(d.toLong) else Left(s"Double value $d is not a valid Long")
      case n: Number =>
        val d = n.doubleValue()
        if (isExactLong(d)) Right(d.toLong) else Left(s"Numeric value $n is not a valid Long")
      case s: String => s.toLongOption.toRight(s"Cannot parse '$s' as Long")
      case null      => Left("Cannot convert null to Long")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Long")
    }
  }

  implicit val doubleFlagType: FlagType[Double] = new FlagType[Double] {
    def typeName: String     = "Double"
    def defaultValue: Double = 0.0
    def decode(value: Any): Either[String, Double] = value match {
      case d: Double => Right(d)
      case f: Float  => Right(f.toDouble)
      case i: Int    => Right(i.toDouble)
      case l: Long   => Right(l.toDouble)
      case n: Number => Right(n.doubleValue())
      case s: String => s.toDoubleOption.toRight(s"Cannot parse '$s' as Double")
      case null      => Left("Cannot convert null to Double")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Double")
    }
  }

  implicit val floatFlagType: FlagType[Float] = new FlagType[Float] {
    def typeName: String    = "Float"
    def defaultValue: Float = 0.0f
    def decode(value: Any): Either[String, Float] = value match {
      case f: Float => Right(f)
      case d: Double =>
        // Rounding to Float precision is inherent and expected; only reject magnitude overflow,
        // which would silently turn a finite value into +/-Infinity.
        if (d.isNaN || d.isInfinity || math.abs(d) <= java.lang.Float.MAX_VALUE.toDouble) Right(d.toFloat)
        else Left(s"Double value $d is out of Float range")
      case i: Int    => Right(i.toFloat)
      case l: Long   => Right(l.toFloat)
      case s: String => s.toFloatOption.toRight(s"Cannot parse '$s' as Float")
      case null      => Left("Cannot convert null to Float")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Float")
    }
  }

  implicit val objectFlagType: FlagType[Map[String, Any]] = new FlagType[Map[String, Any]] {
    def typeName: String               = "Object"
    def defaultValue: Map[String, Any] = Map.empty
    def decode(value: Any): Either[String, Map[String, Any]] = value match {
      case m: Map[_, _] =>
        Try(m.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case m: java.util.Map[_, _] =>
        import scala.jdk.CollectionConverters._
        Try(m.asScala.toMap.asInstanceOf[Map[String, Any]]).toEither.left.map(_.getMessage)
      case null => Left("Cannot convert null to Object")
      case _ =>
        Left(s"Cannot convert ${value.getClass.getSimpleName} to Object")
    }
  }

  implicit def optionFlagType[A](implicit underlying: FlagType[A]): FlagType[Option[A]] =
    new FlagType[Option[A]] {
      def typeName: String        = s"Option[${underlying.typeName}]"
      def defaultValue: Option[A] = None
      def decode(value: Any): Either[String, Option[A]] = value match {
        case null    => Right(None)
        case None    => Right(None)
        case Some(v) => underlying.decode(v).map(Some(_))
        case other   => underlying.decode(other).map(Some(_))
      }
      // Carried as the underlying wire value or null — exactly what `decode` accepts back. The inherited identity
      // `encode` would hand `Some(domainValue)` to `decode`, breaking the round-trip law for `Option[Custom]` (#359).
      override def encode(value: Option[A]): Any = value match {
        case Some(a) => underlying.encode(a)
        case None    => null
      }
    }

  implicit def listFlagType[A](implicit underlying: FlagType[A]): FlagType[List[A]] =
    new FlagType[List[A]] {
      def typeName: String      = s"List[${underlying.typeName}]"
      def defaultValue: List[A] = List.empty
      def decode(value: Any): Either[String, List[A]] = value match {
        case list: List[_] =>
          list.foldRight[Either[String, List[A]]](Right(Nil)) { (elem, acc) =>
            for {
              decoded <- underlying.decode(elem)
              rest    <- acc
            } yield decoded :: rest
          }
        case seq: Seq[_]   => decode(seq.toList)
        case arr: Array[_] => decode(arr.toList)
        case jlist: java.util.List[_] =>
          import scala.jdk.CollectionConverters._
          decode(jlist.asScala.toList)
        case null => Left("Cannot convert null to List")
        case _ => Left(s"Cannot convert ${value.getClass.getSimpleName} to List")
      }
      override def encode(value: List[A]): Any = value.map(underlying.encode)
    }

  /** Builds an instance that leaves [[FlagType.wireType]] at `name`, so unless `name` happens to be one of the scalar
    * wire names it is resolved on the object path. For a type carried over the wire as a *scalar* — a string-backed
    * enum, a newtype over an int — prefer [[mapped]], which inherits its underlying `wireType` and keeps `encode`
    * consistent with it; failing that, override `wireType` and `encode` together.
    */
  def from[A](
    name: String,
    default: A,
    decoder: Any => Either[String, A],
    encoder: A => Any = identity[A] _
  ): FlagType[A] = new FlagType[A] {
    def typeName: String                      = name
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = decoder(value)
    override def encode(value: A): Any        = encoder(value)
  }

  def mapped[A, B](name: String, default: A)(map: B => A, contramap: A => B)(implicit
    underlying: FlagType[B]
  ): FlagType[A] = new FlagType[A] {
    def typeName: String = name
    // A mapped instance is by construction carried over the wire as its underlying type, so it inherits that
    // wire type — this is what makes `mapped` scalar-backed types evaluatable without any user action.
    override def wireType: String             = underlying.wireType
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = underlying.decode(value).map(map)
    override def encode(value: A): Any        = underlying.encode(contramap(value))
  }
}
