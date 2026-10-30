package zio.openfeature.ofrep

import zio.openfeature.testsupport.PropertyBudget

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ImmutableContext, Value}
import zio._
import zio.openfeature.{ErrorCode, FeatureFlagError, FeatureFlags}
import zio.test._

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try
import scala.jdk.CollectionConverters._

/** Hostile-input sweeps through OFREP response decoding, against a WireMock stub.
  *
  * Each property serves a generated JSON value and asserts the decoded result is EXACT or a typed error (a populated
  * `errorCode` with the caller's default) -- never a silently rounded, truncated or stringified answer. Strings are
  * sent with every UTF-16 unit `\\u`-escaped so any generated string (lone surrogates, NUL, line separators) is valid
  * JSON. NaN and Infinity are not JSON and are therefore not sent.
  *
  * Object keys follow two contracts: a well-formed key round-trips exactly, and a key holding an unpaired UTF-16
  * surrogate is a `GENERAL` error, the body not being OFREP JSON (Jackson 2.22.4 rejects it,
  * https://github.com/FasterXML/jackson-core/issues/1683). String values with lone surrogates still decode, an
  * asymmetry that is upstream's and pinned here so a change to it shows up.
  */
object OFREPHostileResponseSpec extends ZIOSpecDefault {

  private val emptyContext = new ImmutableContext()
  private val flagCounter  = new AtomicInteger(0)

  private val serverScoped: ZIO[Scope, Nothing, WireMockServer] =
    ZIO.acquireRelease(ZIO.succeed {
      val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      server.start()
      server
    })(server => ZIO.succeed(server.stop()))

  private def providerFor(server: WireMockServer): ZIO[Scope, Nothing, OFREPFeatureProvider] =
    OFREPProvider.scoped(s"http://localhost:${server.port()}").mapError(e => new RuntimeException(e.toString)).orDie

  /** Stub a fresh flag returning `valueJson` and return its key. */
  private def serve(server: WireMockServer, valueJson: String): String = {
    val key = s"hostile-${flagCounter.incrementAndGet()}"
    server.stubFor(
      post(urlEqualTo(s"/ofrep/v1/evaluate/flags/$key"))
        .willReturn(okJson(s"""{"key":"$key","value":$valueJson,"reason":"STATIC","variant":"v"}"""))
    )
    key
  }

  // JSON building

  private def jsonString(s: String): String =
    s.map(c => f"\\u${c.toInt}%04x").mkString("\"", "", "\"")

  sealed private trait Json
  private case object JNull                                extends Json
  final private case class JBool(b: Boolean)               extends Json
  final private case class JStr(s: String)                 extends Json
  final private case class JInt(i: Int)                    extends Json
  final private case class JNum(raw: String)               extends Json // number text exactly as sent
  final private case class JList(xs: List[Json])           extends Json
  final private case class JObj(fields: Map[String, Json]) extends Json

  private def render(j: Json): String = j match {
    case JNull     => "null"
    case JBool(b)  => b.toString
    case JStr(s)   => jsonString(s)
    case JInt(i)   => i.toString
    case JNum(raw) => raw
    case JList(xs) => xs.map(render).mkString("[", ",", "]")
    case JObj(fs)  => fs.map { case (k, v) => s"${jsonString(k)}:${render(v)}" }.mkString("{", ",", "}")
  }

  /** Exact numeric value of the sent JSON number text. */
  private def number(raw: String): Any = ("num", BigDecimal(raw))

  private def expected(j: Json): Any = j match {
    case JNull     => null
    case JBool(b)  => ("bool", b)
    case JStr(s)   => ("str", s)
    case JInt(i)   => number(i.toString)
    case JNum(raw) => number(raw)
    case JList(xs) => xs.map(expected)
    case JObj(fs)  => fs.map { case (k, v) => k -> expected(v) }
  }

  private def actual(v: Value): Any =
    if (v == null) null
    else if (v.isList) v.asList().asScala.toList.map(actual)
    else if (v.isStructure) v.asStructure().asMap().asScala.toMap.map { case (k, x) => k -> actual(x) }
    else if (v.isBoolean) ("bool", v.asBoolean().booleanValue())
    else if (v.isString) ("str", v.asString())
    else if (v.isNumber) v.asObject() match {
      case n: java.lang.Number => number(n.toString)
      case other               => ("non-number", other)
    }
    else null

  // Generators

  private val hostileStrings: List[String] = List(
    "",
    "\u0000",
    "a\u0000b",
    "\u0001\u001f\u007f",
    "\u0085\u2028\u2029",
    0xd83d.toChar.toString, // lone high surrogate (a literal escape does not parse in scalafmt)
    0xde00.toChar.toString, // lone low surrogate
    "\uD83D\uDE00",
    "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
    "\u65e5\u672c\u8a9e",
    "\\\"'/",
    "null",
    "\r\n\t"
  )

  private val hostileString: Gen[Any, String] =
    Gen.oneOf(Gen.fromIterable(hostileStrings), Gen.string(Gen.unicodeChar), Gen.string(Gen.char))

  /** No unpaired surrogate: `codePoints` yields a lone surrogate as its own code point, so this rejects exactly them.
    */
  private def isWellFormed(s: String): Boolean =
    s.codePoints().noneMatch(cp => Character.isSurrogate(cp.toChar))

  private val wellFormedKey: Gen[Any, String] = hostileString.filter(isWellFormed)

  private val loneHigh: Char = 0xd83d.toChar
  private val loneLow: Char  = 0xde00.toChar

  /** A well-formed key with one lone surrogate inserted at the start, the middle (a code-point boundary), the end, or
    * the surrogate alone.
    */
  private val loneSurrogateKey: Gen[Any, String] =
    for {
      base      <- wellFormedKey
      surrogate <- Gen.elements(loneHigh, loneLow)
      position  <- Gen.elements("start", "middle", "end", "alone")
    } yield {
      val lone = surrogate.toString
      position match {
        case "start"  => lone + base
        case "middle" =>
          val mid = base.offsetByCodePoints(0, base.codePointCount(0, base.length) / 2)
          base.substring(0, mid) + lone + base.substring(mid)
        case "end" => base + lone
        case _     => lone
      }
    }

  private val negativeZero: Double = java.lang.Double.longBitsToDouble(0x8000000000000000L)

  private val finiteDouble: Gen[Any, Double] = Gen
    .oneOf(
      Gen.fromIterable(
        List(
          negativeZero,
          0.0,
          Double.MinValue,
          Double.MaxValue,
          java.lang.Double.MIN_VALUE,
          1e-320,
          9007199254740992.0,
          1e19
        )
      ),
      Gen.double(-1e18, 1e18),
      Gen.double(-1e300, 1e300)
    )
    .filter(d => !d.isNaN && !d.isInfinity)

  private val safeBound = 1L << 53

  private val boundaryLongs: List[Long] = List(
    Long.MinValue,
    Long.MaxValue,
    Int.MinValue.toLong - 1,
    Int.MaxValue.toLong + 1,
    -safeBound - 1,
    safeBound + 1,
    (1L << 62) + 1
  )

  private val outOfIntLong: Gen[Any, Long] = Gen.oneOf(
    Gen.fromIterable(boundaryLongs),
    Gen.long(Int.MaxValue.toLong + 1, Long.MaxValue),
    Gen.long(Long.MinValue, Int.MinValue.toLong - 1)
  )

  private def jsonGen(depth: Int): Gen[Any, Json] = {
    val leaf: Gen[Any, Json] = Gen.oneOf(
      Gen.boolean.map(b => JBool(b): Json),
      hostileString.map(s => JStr(s): Json),
      Gen.int.map(i => JInt(i): Json),
      finiteDouble.map(d => JNum(d.toString): Json)
    )
    if (depth <= 0) leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfBounded(0, 4)(jsonGen(depth - 1)).map(xs => JList(xs): Json),
        Gen.mapOfBounded(0, 4)(wellFormedKey, jsonGen(depth - 1)).map(m => JObj(m): Json)
      )
  }

  def spec = suite("OFREP hostile response decoding (WireMock)")(
    test("a string value of any shape decodes exactly: empty, emoji, control characters, lone surrogates") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(hostileString) { s =>
            val key    = serve(server, jsonString(s))
            val result = provider.getStringEvaluation(key, "DEFAULT", emptyContext)
            val value  = result.getValue
            val code   = result.getErrorCode
            assertTrue(value == s, code == null)
          }
        } yield r
      }
    },
    test("a double value decodes bit-for-bit, including -0.0, subnormals and the extremes") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(finiteDouble) { d =>
            val key    = serve(server, d.toString)
            val result = provider.getDoubleEvaluation(key, java.lang.Double.valueOf(12345.0), emptyContext)
            val value  = result.getValue
            val code   = result.getErrorCode
            assertTrue(code == null, java.lang.Double.compare(value.doubleValue(), d) == 0)
          }
        } yield r
      }
    },
    test("every Int value decodes exactly") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(Gen.oneOf(Gen.fromIterable(List(Int.MinValue, -1, 0, 1, Int.MaxValue)), Gen.int)) { i =>
            val key    = serve(server, i.toString)
            val result = provider.getIntegerEvaluation(key, Integer.valueOf(7), emptyContext)
            val value  = result.getValue
            val code   = result.getErrorCode
            assertTrue(code == null, value.intValue() == i)
          }
        } yield r
      }
    },
    test("an integer outside the Int range is a typed error with the default, never a truncated Int") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(outOfIntLong) { l =>
            val key    = serve(server, l.toString)
            val result = provider.getIntegerEvaluation(key, Integer.valueOf(7), emptyContext)
            val value  = result.getValue
            val code   = result.getErrorCode
            // Exact is impossible (it does not fit); the only acceptable outcome is a loud error carrying the default.
            assertTrue(code != null, value == null || value.intValue() == 7)
          }
        } yield r
      }
    },
    test("a Long value decodes exactly or is a typed error -- never rounded through a Double") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(Gen.oneOf(Gen.fromIterable(boundaryLongs), Gen.long)) { l =>
            val key    = serve(server, l.toString)
            val result = provider.getLongEvaluation(key, java.lang.Long.valueOf(7L), emptyContext)
            val value  = result.getValue
            val code   = result.getErrorCode
            assertTrue((code == null && value.longValue() == l) || (code != null && value.longValue() == 7L))
          }
        } yield r
      }
    },
    test(
      "an object value with well-formed hostile keys, strings, nulls and nesting decodes with every value preserved"
    ) {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          r        <- check(Gen.int(0, 3).flatMap(d => Gen.mapOfBounded(0, 4)(wellFormedKey, jsonGen(d)))) { fields =>
            val json   = JObj(fields)
            val key    = serve(server, render(json))
            val result = provider.getObjectEvaluation(key, new Value(), emptyContext)
            val got    = actual(result.getValue)
            val want   = expected(json)
            val code   = result.getErrorCode
            assertTrue(code == null, got == want)
          }
        } yield r
      }
    },
    // Jackson rejects the key, so the body is not OFREP JSON: the provider answers `GENERAL` with the default rather
    // than throwing, and callers see a server error through `FeatureFlags`.
    test("an object key with a lone surrogate, at depth 1 or 2, is a GENERAL error with the default and never throws") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          env      <- FeatureFlags.fromProviderAsync(provider).build.orDie
          ff = env.get[FeatureFlags]
          r <- check(loneSurrogateKey, Gen.boolean, jsonGen(0)) { (badKey, nested, member) =>
            val inner                  = JObj(Map(badKey -> member))
            val json                   = if (nested) JObj(Map("outer" -> inner)) else inner
            val key                    = serve(server, render(json))
            val default                = Map[String, Any]("fallback" -> "DEFAULT")
            val direct                 = Try(provider.getObjectEvaluation(key, new Value(), emptyContext))
            val providerAnswersGeneral = direct.toOption.exists(_.getErrorCode == dev.openfeature.sdk.ErrorCode.GENERAL)
            for {
              typed <- ff.objDetails(key, default).either
              total <- ff.resolveOrDefault[Map[String, Any]](key, default)
            } yield {
              val typedIsProviderError = typed match {
                case Left(_: FeatureFlagError.ProviderError) => true
                case _                                       => false
              }
              val code  = total.errorCode
              val value = total.value
              assertTrue(
                !isWellFormed(badKey),
                providerAnswersGeneral,
                typedIsProviderError,
                code == Some(ErrorCode.General),
                value == default
              )
            }
          }
        } yield r
      }
    },
    test("null members inside an object and a list keep their position and key") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          json   = JObj(Map("n" -> JNull, "l" -> JList(List(JNull, JStr("x"), JNull)), "" -> JNull))
          key    = serve(server, render(json))
          result = provider.getObjectEvaluation(key, new Value(), emptyContext)
          got    = actual(result.getValue)
          want   = expected(json)
        } yield assertTrue(got == want)
      }
    },
    test("deep nesting (200 levels) and large payloads (5000-key object, 20000-element list) decode intact") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          deep    = (1 to 200).foldLeft[Json](JStr("\uD83D\uDE00"))((acc, _) => JObj(Map("k" -> JList(List(acc)))))
          bigObj  = JObj((0 until 5000).map(i => s"k$i\u0000\uD83D\uDE00" -> (JInt(i): Json)).toMap)
          bigList = JObj(Map("xs" -> JList((0 until 20000).map(i => JInt(i): Json).toList)))
          decode  = (j: Json) =>
            actual(provider.getObjectEvaluation(serve(server, render(j)), new Value(), emptyContext).getValue)
          deepOk    = decode(deep) == expected(deep)
          bigObjOk  = decode(bigObj) == expected(bigObj)
          bigListOk = decode(bigList) == expected(bigList)
        } yield assertTrue(deepOk, bigObjOk, bigListOk)
      }
    },
    // A number inside an object that does not fit a Double exactly (9007199254740993 = 2^53 + 1). If the decoder hands
    // back 9007199254740992 this is a silent rounding; the exactness property is kept as documentation until triaged.
    test("an integer beyond 2^53 inside an object keeps its exact value") {
      ZIO.scoped {
        for {
          server   <- serverScoped
          provider <- providerFor(server)
          json   = JObj(Map("big" -> JNum("9007199254740993"), "max" -> JNum(Long.MaxValue.toString)))
          key    = serve(server, render(json))
          result = provider.getObjectEvaluation(key, new Value(), emptyContext)
          got    = actual(result.getValue)
          want   = expected(json)
        } yield assertTrue(got == want)
      }
    }
  ) @@ PropertyBudget(40) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
}
