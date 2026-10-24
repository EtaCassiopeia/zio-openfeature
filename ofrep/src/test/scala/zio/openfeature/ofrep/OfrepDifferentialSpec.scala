package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.FeatureProvider
import zio._
import zio.openfeature.ErrorCode
import zio.openfeature.testkit.DifferentialLaws
import zio.openfeature.testkit.DifferentialLaws._
import zio.test._

/** The OFREP provider against `TestFeatureProvider` (#526): an in-process WireMock stub serves each generated flag set
  * in the OFREP wire format (a single-flag success body per key, `404 FLAG_NOT_FOUND` for any other key), and the real
  * contrib client built by `OFREPProvider.scoped` reads it over HTTP.
  *
  * One stub serves the whole spec; its mappings are replaced for every sample (samples run one after another).
  *
  * The deviations are limits of JSON as a wire format, plus one behaviour of the upstream contrib client, each with its
  * reason. Anything else is a bug in one of the two providers.
  */
object OfrepDifferentialSpec extends ZIOSpecDefault {

  private val oracle = "ofrep"

  private lazy val server: WireMockServer = {
    val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    s.start()
    s
  }

  private def seed(flags: FlagSet): URIO[Scope, FeatureProvider] =
    ZIO.attempt(stub(flags)).orDie *>
      OFREPProvider
        .scoped(s"http://localhost:${server.port()}")
        .orDieWith(e => new IllegalStateException(s"OFREP provider for the stub failed to build: $e"))

  def spec = suite("OfrepDifferentialSpec")(
    DifferentialLaws.agreesWithTestkit(oracle)(seed, deviations)
  ) @@ TestAspect.afterAll(ZIO.succeed(server.stop())) @@ TestAspect.withLiveClock

  // Deviations

  private def isNonFinite(v: Any): Boolean = v match {
    case d: Double => d.isNaN || d.isInfinite
    case _         => false
  }

  /** What a value becomes on the wire: JSON has no NaN or infinity, so the stub sends their text. */
  private def asJsonLiteral(v: Any): Any = v match {
    case d: Double if isNonFinite(d) => d.toString
    case m: Map[_, _]                => m.map { case (k, x) => k -> asJsonLiteral(x) }
    case l: List[_]                  => l.map(asJsonLiteral)
    case other                       => other
  }

  private def same(a: Observed, b: Observed): Boolean =
    a.reasonClass == b.reasonClass && a.errorCode == b.errorCode && DifferentialLaws.sameValue(a.value, b.value)

  private val SafeLong: Long = (1L << 53) - 1

  /** What a long read gets from the contrib client: it does not override `getLongEvaluation`, so the SDK's default
    * runs. That default rejects a default value outside +-(2^53 - 1) before resolving anything, then delegates to
    * `getDoubleEvaluation` and serves the result only if it is a whole number within that range. The contrib resolver
    * serves a double read only when Jackson parsed the JSON value as a `Double`, so any JSON integer (an `Int` or a
    * `Long` flag) is TYPE_MISMATCH, and a whole double (`2.0`, `-0.0`) is served as a long.
    */
  private def sdkDoubleBackedLong(flags: FlagSet, key: String, default: Long): Observed = {
    val mismatch = Observed(default, ReasonClass.Error, Some(ErrorCode.TypeMismatch))
    if (default > SafeLong || default < -SafeLong) mismatch
    else
      flags.flags.get(key) match {
        case None => Observed(default, ReasonClass.Error, Some(ErrorCode.FlagNotFound))
        case Some(d: Double) if !isNonFinite(d) && d == math.floor(d) && math.abs(d) <= SafeLong.toDouble =>
          Observed(d.toLong, ReasonClass.Served, None)
        case Some(_) => mismatch
      }
  }

  val deviations: List[Deviation] = List(
    Deviation(
      oracle,
      (flags, request, testkit, ofrep) =>
        request.accessor.isInstanceOf[Accessor.IntRead] && testkit.errorCode.contains(ErrorCode.TypeMismatch) &&
          ofrep.reasonClass == ReasonClass.Served &&
          flags.flags.get(request.key).exists {
            case l: Long => l.isValidInt && DifferentialLaws.sameValue(ofrep.value, l.toInt)
            case _       => false
          },
      "JSON numbers are untyped: a Long flag inside the Int range is the same JSON integer as an Int, which the " +
        "client parses as an Int, so an int read serves it where the testkit keeps Long and Int apart."
    ),
    Deviation(
      oracle,
      (flags, request, testkit, ofrep) =>
        (flags.flags.get(request.key).exists(isNonFinite) && (request.accessor match {
          case _: Accessor.StringRead =>
            testkit.errorCode.contains(ErrorCode.TypeMismatch) && ofrep.reasonClass == ReasonClass.Served &&
            flags.flags.get(request.key).exists(v => ofrep.value == v.toString)
          case _: Accessor.DoubleRead =>
            testkit.reasonClass == ReasonClass.Served && ofrep.errorCode.contains(ErrorCode.TypeMismatch)
          case _ => false
        })) ||
          (request.accessor.isInstanceOf[Accessor.ObjectRead] &&
            same(testkit.copy(value = asJsonLiteral(testkit.value)), ofrep)),
      "JSON has no NaN or +-Infinity: the stub sends their text, so a double read is TYPE_MISMATCH, a string read " +
        "serves the text, and inside an object they come back as strings."
    ),
    Deviation(
      oracle,
      (flags, request, _, ofrep) =>
        request.accessor match {
          case Accessor.LongRead(default) => same(ofrep, sdkDoubleBackedLong(flags, request.key, default))
          case _                          => false
        },
      "Upstream: the OFREP contrib client (0.0.2) does not override getLongEvaluation, so the SDK's double-backed " +
        "default runs over the contrib's strict double resolver. A long read of any JSON integer is TYPE_MISMATCH, " +
        "a whole double (2.0, -0.0) is served as a long, and an absent key read with a default outside " +
        "+-(2^53 - 1) is TYPE_MISMATCH instead of FLAG_NOT_FOUND."
    )
  )

  // Rendering

  /** Point the stub at a flag set: one single-flag success mapping per key, and 404 FLAG_NOT_FOUND for any other. */
  private def stub(flags: FlagSet): Unit = {
    server.resetMappings()
    server.stubFor(
      post(urlPathMatching("/ofrep/v1/evaluate/flags/.*"))
        .atPriority(10)
        .willReturn(
          aResponse()
            .withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"errorCode":"FLAG_NOT_FOUND","errorDetails":"flag not found"}""")
        )
    )
    flags.flags.foreach { case (key, value) =>
      server.stubFor(
        post(urlEqualTo(s"/ofrep/v1/evaluate/flags/$key"))
          .atPriority(1)
          .willReturn(okJson(successBody(key, value)))
      )
    }
  }

  /** A single-flag OFREP success body. */
  def successBody(key: String, value: Any): String =
    s"""{"key":${quote(key)},"value":${renderValue(value)},"reason":"STATIC","variant":"generated"}"""

  private def renderValue(v: Any): String = v match {
    case null       => "null"
    case b: Boolean => b.toString
    case s: String  => quote(s)
    case i: Int     => i.toString
    case l: Long    => l.toString
    // JSON has no literal for NaN or the infinities; send the text, as a JSON-encoding server would have to.
    case d: Double  => if (isNonFinite(d)) quote(d.toString) else d.toString
    case l: List[_] => l.map(renderValue).mkString("[", ",", "]")
    case m: Map[_, _] =>
      m.toList.map { case (k, x) => s"${quote(k.toString)}:${renderValue(x)}" }.mkString("{", ",", "}")
    case other => sys.error(s"unexpected generated value $other")
  }

  /** A JSON string literal, ASCII only: quotes, backslashes, control characters and everything outside printable ASCII
    * written as a unicode escape, so the body survives any transport encoding.
    */
  private def quote(s: String): String =
    "\"" + s.flatMap {
      case '"'                     => "\\\""
      case '\\'                    => "\\\\"
      case c if c < ' ' || c > '~' => f"\\u${c.toInt}%04x"
      case c                       => c.toString
    } + "\""
}
