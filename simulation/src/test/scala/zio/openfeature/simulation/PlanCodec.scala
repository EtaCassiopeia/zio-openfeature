package zio.openfeature.simulation

import scala.compiletime.{constValueTuple, erasedValue, summonFrom}
import scala.deriving.Mirror

import zio.openfeature.testsupport.ModelBehaviours.Json

/** A JSON codec for simulation plans, derived from the case classes, so a new command or fault serializes with no codec
  * change. A product is an object of its fields; a case of a sealed trait is tagged by its name; a case object is its
  * name. A missing field reads as null, so a new `Option` field keeps older corpus entries readable.
  */
trait PlanCodec[A] {
  def encode(a: A): Json
  def decode(j: Json): Either[String, A]
}

object PlanCodec {

  def apply[A](using c: PlanCodec[A]): PlanCodec[A] = c

  /** One compact line with sorted keys, so an entry is easy to copy from a log and diffs stay small. */
  def render(j: Json): String = j match {
    case Json.Str(s)     => quote(s)
    case Json.Num(n)     => n.toString
    case Json.Bool(b)    => b.toString
    case Json.Null       => "null"
    case Json.Arr(items) => items.map(render).mkString("[", ",", "]")
    case Json.Obj(fs)    =>
      fs.toList.sortBy(_._1).map { case (k, v) => s"${quote(k)}:${render(v)}" }.mkString("{", ",", "}")
  }

  private def quote(s: String): String = {
    val b = new StringBuilder("\"")
    s.foreach {
      case '"'          => b ++= "\\\""
      case '\\'         => b ++= "\\\\"
      case '\n'         => b ++= "\\n"
      case c if c < ' ' => b ++= f"\\u${c.toInt}%04x"
      case c            => b += c
    }
    b += '"'
    b.toString
  }

  def write[A: PlanCodec](a: A): String                   = render(PlanCodec[A].encode(a))
  def read[A: PlanCodec](text: String): Either[String, A] = Json.parse(text).flatMap(PlanCodec[A].decode)

  given PlanCodec[Int] with {
    def encode(a: Int)  = Json.Num(a.toLong)
    def decode(j: Json) = j match {
      case Json.Num(n) if n.isValidInt => Right(n.toInt)
      case other                       => Left(s"expected an Int, got $other")
    }
  }
  given PlanCodec[Long] with {
    def encode(a: Long) = Json.Num(a)
    def decode(j: Json) = j match {
      case Json.Num(n) => Right(n)
      case other       => Left(s"expected a Long, got $other")
    }
  }
  // The reader takes integers only; a double is written as its exact decimal string.
  given PlanCodec[Double] with {
    def encode(a: Double) = Json.Str(java.lang.Double.toString(a))
    def decode(j: Json)   = j match {
      case Json.Str(s) => s.toDoubleOption.toRight(s"expected a Double, got $s")
      case other       => Left(s"expected a Double, got $other")
    }
  }
  given PlanCodec[Boolean] with {
    def encode(a: Boolean) = Json.Bool(a)
    def decode(j: Json)    = j match {
      case Json.Bool(b) => Right(b)
      case other        => Left(s"expected a Boolean, got $other")
    }
  }
  given PlanCodec[String] with {
    def encode(a: String) = Json.Str(a)
    def decode(j: Json)   = j match {
      case Json.Str(s) => Right(s)
      case other       => Left(s"expected a String, got $other")
    }
  }
  given [A](using c: PlanCodec[A]): PlanCodec[Option[A]] with {
    def encode(a: Option[A]) = a.fold[Json](Json.Null)(c.encode)
    def decode(j: Json)      = j match {
      case Json.Null => Right(None)
      case other     => c.decode(other).map(Some(_))
    }
  }
  given [A](using c: PlanCodec[A]): PlanCodec[List[A]] with {
    def encode(a: List[A]) = Json.Arr(a.map(c.encode).toVector)
    def decode(j: Json)    = j match {
      case Json.Arr(items) => traverse(items.toList)(c.decode)
      case other           => Left(s"expected an array, got $other")
    }
  }
  given [A](using c: PlanCodec[A]): PlanCodec[Map[String, A]] with {
    def encode(a: Map[String, A]) = Json.Obj(a.map { case (k, v) => k -> c.encode(v) })
    def decode(j: Json)           = j match {
      case Json.Obj(fs) =>
        traverse(fs.toList) { case (k, v) => c.decode(v).left.map(e => s"$k: $e").map(k -> _) }.map(_.toMap)
      case other => Left(s"expected an object, got $other")
    }
  }

  private def traverse[A, B](as: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    as.foldRight[Either[String, List[B]]](Right(Nil))((a, acc) =>
      for {
        b  <- f(a)
        bs <- acc
      } yield b :: bs
    )

  private inline def labels[T <: Tuple]: List[String] = constValueTuple[T].productIterator.map(_.toString).toList

  private inline def codecs[T <: Tuple]: List[PlanCodec[Any]] = inline erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (h *: t)   => codecOf[h].asInstanceOf[PlanCodec[Any]] :: codecs[t]
  }

  // A given if there is one (the recursive plan types), else derived in place (the cases of a sum).
  private inline def codecOf[A]: PlanCodec[A] = summonFrom {
    case c: PlanCodec[A] => c
    case m: Mirror.Of[A] => derived[A](using m)
  }

  inline def derived[A](using m: Mirror.Of[A]): PlanCodec[A] = {
    lazy val names = labels[m.MirroredElemLabels]
    lazy val elems = codecs[m.MirroredElemTypes]
    inline m match {
      case s: Mirror.SumOf[A]     => sum(s, names, elems)
      case p: Mirror.ProductOf[A] => product(p, names, elems)
    }
  }

  private def product[A](p: Mirror.ProductOf[A], names: => List[String], elems: => List[PlanCodec[Any]]) =
    new PlanCodec[A] {
      def encode(a: A) = {
        val values = a.asInstanceOf[Product].productIterator.toList
        Json.Obj(names.zip(elems).zip(values).map { case ((n, c), v) => n -> c.encode(v) }.toMap)
      }
      def decode(j: Json) = j match {
        // A missing field reads as null, so a new Option field keeps older entries readable.
        case Json.Obj(fs) =>
          traverse(names.zip(elems)) { case (n, c) => c.decode(fs.getOrElse(n, Json.Null)).left.map(e => s"$n: $e") }
            .map(vs => p.fromProduct(Tuple.fromArray(vs.toArray[Any])))
        case Json.Str(_) if names.isEmpty => Right(p.fromProduct(EmptyTuple))
        case other                        => Left(s"expected an object, got $other")
      }
    }

  // A case object is its name; any other case is a one-field object, its name to its fields.
  private def sum[A](s: Mirror.SumOf[A], names: => List[String], elems: => List[PlanCodec[Any]]) =
    new PlanCodec[A] {
      def encode(a: A) = {
        val i = s.ordinal(a)
        elems(i).encode(a) match {
          case Json.Obj(fs) if fs.isEmpty && a.asInstanceOf[Product].productArity == 0 => Json.Str(names(i))
          case body => Json.Obj(Map(names(i) -> body))
        }
      }
      def decode(j: Json) = j match {
        case Json.Str(n) =>
          names.indexOf(n) match {
            case -1 => Left(s"unknown case $n")
            case i  => elems(i).decode(j).map(_.asInstanceOf[A])
          }
        case Json.Obj(fs) if fs.size == 1 =>
          val (n, body) = fs.head
          names.indexOf(n) match {
            case -1 => Left(s"unknown case $n")
            case i  => elems(i).decode(body).map(_.asInstanceOf[A]).left.map(e => s"$n.$e")
          }
        case other => Left(s"expected a case, got $other")
      }
    }

  // The plan types. Recursive (`Spec` holds `Spec`s): each is a lazy given, so derivation finds itself.
  given PlanCodec[Stored]                           = derived
  given PlanCodec[LeafFault]                        = derived
  given PlanCodec[InitFault]                        = derived
  given PlanCodec[ShutdownFault]                    = derived
  given PlanCodec[Kind]                             = derived
  given PlanCodec[Ctx]                              = derived
  given PlanCodec[Spec]                             = derived
  given PlanCodec[Cmd.Eval]                         = derived
  given PlanCodec[Cmd]                              = derived
  given PlanCodec[SeqPlan]                          = derived
  given PlanCodec[ConcurrentSimulationSpec.Release] = derived
  given PlanCodec[ConcurrentSimulationSpec.Plan]    = derived
}
