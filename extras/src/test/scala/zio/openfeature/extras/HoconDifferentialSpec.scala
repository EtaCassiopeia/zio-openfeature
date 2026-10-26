package zio.openfeature.extras

import com.typesafe.config.ConfigFactory
import zio._
import zio.openfeature.ErrorCode
import zio.openfeature.testkit.DifferentialLaws
import zio.openfeature.testkit.DifferentialLaws._
import zio.test._

/** `HoconProvider` against `TestFeatureProvider` (#467): the generated flag sets are rendered to HOCON text, parsed,
  * and served by `HoconProvider.fromConfig`.
  *
  * The deviations are limits of what HOCON can represent, each with its reason. Anything else is a bug in one of the
  * two providers.
  */
object HoconDifferentialSpec extends ZIOSpecDefault {

  private val oracle = "hocon"

  def spec = suite("HoconDifferentialSpec")(
    DifferentialLaws.agreesWithTestkit(oracle)(
      seed = flags => ZIO.attempt(HoconProvider.fromConfig(ConfigFactory.parseString(render(flags)))).orDie,
      deviations = deviations
    )
  )

  // Deviations

  private def isBooleanOrNumber(v: Any): Boolean = v match {
    case _: Boolean | _: Int | _: Long | _: Double => true
    case _                                         => false
  }

  private def exactNumber(v: Any): Option[BigDecimal] = v match {
    case i: Int                                 => Some(BigDecimal(i))
    case l: Long                                => Some(BigDecimal(l))
    case d: Double if !d.isNaN && !d.isInfinite => Some(BigDecimal.exact(d))
    case _                                      => None
  }

  private def isScalarRead(r: Request): Boolean = r.accessor match {
    case _: Accessor.BooleanRead | _: Accessor.IntRead | _: Accessor.LongRead | _: Accessor.DoubleRead => true
    case _                                                                                             => false
  }

  private def isNumberRead(r: Request): Boolean = r.accessor match {
    case _: Accessor.IntRead | _: Accessor.LongRead | _: Accessor.DoubleRead => true
    case _                                                                   => false
  }

  private def mismatchedButServed(testkit: Observed, hocon: Observed): Boolean =
    testkit.errorCode.contains(ErrorCode.TypeMismatch) && hocon.reasonClass == ReasonClass.Served

  /** What a value becomes once written as a HOCON number literal: no NaN or infinity (held as text), no -0.0. */
  private def asHoconLiteral(v: Any): Any = v match {
    case d: Double if d.isNaN || d.isInfinite => d.toString
    case d: Double if d == 0.0                => 0.0
    case m: Map[_, _]                         => m.map { case (k, x) => k -> asHoconLiteral(x) }
    case l: List[_]                           => l.map(asHoconLiteral)
    case other                                => other
  }

  val deviations: List[Deviation] = List(
    Deviation(
      oracle,
      (flags, request, testkit, hocon) =>
        isNumberRead(request) && mismatchedButServed(testkit, hocon) &&
          flags.flags.get(request.key).flatMap(exactNumber).exists(n => exactNumber(hocon.value).contains(n)),
      "HOCON has one number type: an int, long or double read of any number is served when the value is unchanged, " +
        "where the testkit keeps Int, Long and Double apart (#457). Typesafe Config even folds 2.0 into an int."
    ),
    Deviation(
      oracle,
      (flags, request, testkit, hocon) =>
        request.accessor.isInstanceOf[Accessor.StringRead] && mismatchedButServed(testkit, hocon) &&
          flags.flags.get(request.key).exists(v => isBooleanOrNumber(v) && hocon.value == v.toString),
      "The HOCON spec's automatic type conversions: a string read of a boolean or a number serves its text."
    ),
    Deviation(
      oracle,
      (flags, request, testkit, hocon) =>
        isScalarRead(request) && mismatchedButServed(testkit, hocon) &&
          flags.flags.get(request.key).exists(_.isInstanceOf[String]),
      "The HOCON spec's automatic type conversions: a boolean or number read of a string that parses as one " +
        "(\"true\", \"42\", \"1e3\") serves the parsed value."
    ),
    Deviation(
      oracle,
      (_, _, testkit, hocon) =>
        testkit.reasonClass == hocon.reasonClass && testkit.errorCode == hocon.errorCode &&
          DifferentialLaws.sameValue(asHoconLiteral(testkit.value), hocon.value),
      "HOCON number literals cannot hold NaN or the infinities (rendered as text, so they come back as strings), " +
        "and Typesafe Config parses -0.0 as the integer 0, losing the sign."
    )
  )

  // Rendering

  /** HOCON text for a flag set: one quoted key per flag, values in JSON syntax (which is valid HOCON). */
  def render(flags: FlagSet): String =
    flags.flags.toList.sortBy(_._1).map { case (k, v) => s"${quote(k)} = ${renderValue(v)}" }.mkString("\n")

  private def renderValue(v: Any): String = v match {
    case null       => "null"
    case b: Boolean => b.toString
    case s: String  => quote(s)
    case i: Int     => i.toString
    case l: Long    => l.toString
    // HOCON has no literal for NaN or the infinities; the closest it can hold is the text, which Typesafe Config
    // parses back to the number on a double read.
    case d: Double    => if (d.isNaN || d.isInfinite) quote(d.toString) else d.toString
    case l: List[_]   => l.map(renderValue).mkString("[", ", ", "]")
    case m: Map[_, _] =>
      m.toList.map { case (k, x) => s"${quote(k.toString)} : ${renderValue(x)}" }.mkString("{", ", ", "}")
    case other => sys.error(s"unexpected generated value $other")
  }

  /** A JSON string literal: quotes, backslashes and every control character escaped. */
  private def quote(s: String): String =
    "\"" + s.flatMap {
      case '"'            => "\\\""
      case '\\'           => "\\\\"
      case c if c < ' '   => f"\\u${c.toInt}%04x"
      case c if c == 0x7f => "\\u007f"
      case c              => c.toString
    } + "\""
}
