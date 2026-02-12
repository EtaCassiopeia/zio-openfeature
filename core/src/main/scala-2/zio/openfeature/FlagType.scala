package zio.openfeature

import scala.util.Try

trait FlagType[A] {
  def typeName: String
  def decode(value: Any): Either[String, A]
  def encode(value: A): Any = value
  def defaultValue: A
}

object FlagType {
  def apply[A](implicit ft: FlagType[A]): FlagType[A] = ft
  def typeName[A](implicit ft: FlagType[A]): String   = ft.typeName

  implicit val booleanFlagType: FlagType[Boolean] = new FlagType[Boolean] {
    def typeName: String      = "Boolean"
    def defaultValue: Boolean = false
    def decode(value: Any): Either[String, Boolean] = value match {
      case b: Boolean => Right(b)
      case s: String  => s.toBooleanOption.toRight(s"Cannot parse '$s' as Boolean")
      case n: Number  => Right(n.intValue() != 0)
      case _          => Left(s"Cannot convert ${value.getClass.getSimpleName} to Boolean")
    }
  }

  implicit val stringFlagType: FlagType[String] = new FlagType[String] {
    def typeName: String     = "String"
    def defaultValue: String = ""
    def decode(value: Any): Either[String, String] = value match {
      case s: String => Right(s)
      case null      => Right("")
      case other     => Right(other.toString)
    }
  }

  implicit val intFlagType: FlagType[Int] = new FlagType[Int] {
    def typeName: String  = "Int"
    def defaultValue: Int = 0
    def decode(value: Any): Either[String, Int] = value match {
      case i: Int    => Right(i)
      case l: Long   => Right(l.toInt)
      case d: Double => Right(d.toInt)
      case n: Number => Right(n.intValue())
      case s: String => s.toIntOption.toRight(s"Cannot parse '$s' as Int")
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Int")
    }
  }

  implicit val longFlagType: FlagType[Long] = new FlagType[Long] {
    def typeName: String   = "Long"
    def defaultValue: Long = 0L
    def decode(value: Any): Either[String, Long] = value match {
      case l: Long   => Right(l)
      case i: Int    => Right(i.toLong)
      case d: Double => Right(d.toLong)
      case n: Number => Right(n.longValue())
      case s: String => s.toLongOption.toRight(s"Cannot parse '$s' as Long")
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
      case _         => Left(s"Cannot convert ${value.getClass.getSimpleName} to Double")
    }
  }

  implicit val floatFlagType: FlagType[Float] = new FlagType[Float] {
    def typeName: String    = "Float"
    def defaultValue: Float = 0.0f
    def decode(value: Any): Either[String, Float] = value match {
      case f: Float  => Right(f)
      case d: Double => Right(d.toFloat)
      case i: Int    => Right(i.toFloat)
      case l: Long   => Right(l.toFloat)
      case n: Number => Right(n.floatValue())
      case s: String => s.toFloatOption.toRight(s"Cannot parse '$s' as Float")
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
        case _ => Left(s"Cannot convert ${value.getClass.getSimpleName} to List")
      }
    }

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
    def typeName: String                      = name
    def defaultValue: A                       = default
    def decode(value: Any): Either[String, A] = underlying.decode(value).map(map)
    override def encode(value: A): Any        = underlying.encode(contramap(value))
  }
}
