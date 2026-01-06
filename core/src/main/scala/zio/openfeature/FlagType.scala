package zio.openfeature

import scala.util.Try

trait FlagType[A]:
  def typeName: String
  def decode(value: Any): Either[String, A]
  def encode(value: A): Any = value
  def defaultValue: A

object FlagType:
  def apply[A](using ft: FlagType[A]): FlagType[A] = ft
  def typeName[A](using ft: FlagType[A]): String   = ft.typeName

  given booleanFlagType: FlagType[Boolean] with
    def typeName: String      = "Boolean"
    def defaultValue: Boolean = false
    def decode(value: Any): Either[String, Boolean] = value match
      case b: Boolean => Right(b)
      case s: String  => s.toBooleanOption.toRight(s"Cannot parse '$s' as Boolean")
      case n: Number  => Right(n.intValue() != 0)
      case _          => Left(s"Cannot convert ${value.getClass.getSimpleName} to Boolean")

  given stringFlagType: FlagType[String] with
    def typeName: String     = "String"
    def defaultValue: String = ""
    def decode(value: Any): Either[String, String] = value match
      case s: String => Right(s)
      case null      => Right("")
      case other     => Right(other.toString)

  given intFlagType: FlagType[Int] with
    def typeName: String  = "Int"
    def defaultValue: Int = 0
    def decode(value: Any): Either[String, Int] = value match
      case i: Int    => Right(i)
      case l: Long   => Right(l.toInt)
      case d: Double => Right(d.toInt)
      case n: Number => Right(n.intValue())
      case s: String => s.toIntOption.toRight(s"Cannot parse '$s' as Int")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Int")

  given longFlagType: FlagType[Long] with
    def typeName: String   = "Long"
    def defaultValue: Long = 0L
    def decode(value: Any): Either[String, Long] = value match
      case l: Long   => Right(l)
      case i: Int    => Right(i.toLong)
      case d: Double => Right(d.toLong)
      case n: Number => Right(n.longValue())
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
      case d: Double => Right(d.toFloat)
      case i: Int    => Right(i.toFloat)
      case l: Long   => Right(l.toFloat)
      case n: Number => Right(n.floatValue())
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
    def typeName: String                      = name
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = underlying.decode(value).map(map)
    override def encode(value: A): Any        = underlying.encode(contramap(value))
