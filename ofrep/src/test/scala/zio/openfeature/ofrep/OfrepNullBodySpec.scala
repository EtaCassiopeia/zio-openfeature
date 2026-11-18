package zio.openfeature.ofrep

import com.sun.net.httpserver.HttpServer
import dev.openfeature.sdk.exceptions.OpenFeatureError
import dev.openfeature.sdk.{ImmutableContext, Value}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference
import zio._
import zio.openfeature._
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

/** A body that is valid JSON but deserializes to a null `OfrepResponse` (`null`, with or without trailing garbage)
  * escapes the contrib provider as a NullPointerException instead of the `ParseError` the wrapper maps to GENERAL
  * "response is not OFREP JSON"; the wrapper must type it the same way.
  */
object OfrepNullBodySpec extends ZIOSpecDefault {

  private val response = new AtomicReference[(Int, Array[Byte])]((200, Array.emptyByteArray))

  private lazy val server: HttpServer = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext(
      "/",
      exchange => {
        val (status, body) = response.get()
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (body.isEmpty) -1L else body.length.toLong)
        if (body.nonEmpty) exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    s.start()
    s
  }

  private def url = s"http://127.0.0.1:${server.getAddress.getPort}"

  private val ctx = new ImmutableContext("user-1")

  /** One raw `FeatureProvider` read: Right(errorCode | message) or Left(the exception that escaped). */
  private def raw(read: => dev.openfeature.sdk.ProviderEvaluation[_]): String =
    try {
      val e = read
      s"eval(code=${e.getErrorCode}, msg=${e.getErrorMessage})"
    } catch {
      case t: OpenFeatureError => s"typed-throw(${t.getClass.getSimpleName}: ${t.getMessage})"
      case t: Throwable        => s"UNTYPED-THROW(${t.getClass.getName}: ${t.getMessage})"
    }

  private def rawReads(p: OFREPFeatureProvider): List[(String, String)] = List(
    "boolean" -> raw(p.getBooleanEvaluation("flag", false, ctx)),
    "string"  -> raw(p.getStringEvaluation("flag", "d", ctx)),
    "integer" -> raw(p.getIntegerEvaluation("flag", 0, ctx)),
    "double"  -> raw(p.getDoubleEvaluation("flag", 0.0, ctx)),
    "object"  -> raw(p.getObjectEvaluation("flag", new Value(), ctx)),
    "long"    -> raw(p.getLongEvaluation("flag", 0L, ctx))
  )

  private def describe[A](exit: Exit[FeatureFlagError, FlagResolution[A]]): String = exit match {
    case Exit.Success(r)                    => s"Right(errorCode=${r.errorCode}, value=${r.value})"
    case Exit.Failure(c) if c.isDie         => s"DIE(${c.dieOption.map(_.toString)})"
    case Exit.Failure(c) if c.isInterrupted => "INTERRUPTED"
    case Exit.Failure(c)                    =>
      c.failureOption match {
        case Some(e: FeatureFlagError.ProviderError) =>
          s"Left(ProviderError(${e.underlying.getClass.getSimpleName}: ${e.underlying.getMessage}))"
        case Some(e) => s"Left(${e.getClass.getSimpleName}: $e)"
        case None    => s"FAILURE($c)"
      }
  }

  private def apiReads(ff: FeatureFlags): UIO[List[(String, String)]] =
    ZIO.collectAll(
      List(
        ff.booleanDetails("flag", false).exit.map(e => "boolean" -> describe(e)),
        ff.stringDetails("flag", "d").exit.map(e => "string" -> describe(e)),
        ff.intDetails("flag", 0).exit.map(e => "int" -> describe(e)),
        ff.longDetails("flag", 0L).exit.map(e => "long" -> describe(e)),
        ff.doubleDetails("flag", 0.0).exit.map(e => "double" -> describe(e)),
        ff.objDetails("flag", Map.empty).exit.map(e => "obj" -> describe(e)),
        ff.booleanOrDefault("flag", true).map(v => "booleanOrDefault" -> s"value=$v")
      )
    )

  private val bodies: List[(String, Int, Array[Byte])] = List(
    ("control: malformed `{`", 200, "{".getBytes(UTF_8)),
    ("`null`, 200", 200, "null".getBytes(UTF_8)),
    ("`null`, 503", 503, "null".getBytes(UTF_8)),
    ("`null`, 404", 404, "null".getBytes(UTF_8)),
    ("` null \\n`, 200", 200, " null \n".getBytes(UTF_8)),
    ("`null` then NUL and an object, 200", 200, "null}\u0000{\"key\":\"\",\"value\":null}".getBytes(UTF_8)),
    ("`null garbage`, 200", 200, "null garbage".getBytes(UTF_8)),
    ("`[]`, 200", 200, "[]".getBytes(UTF_8)),
    ("`1`, 200", 200, "1".getBytes(UTF_8)),
    ("`\"x\"`, 200", 200, "\"x\"".getBytes(UTF_8)),
    ("`\"\"`, 200", 200, "\"\"".getBytes(UTF_8)),
    ("`true`, 200", 200, "true".getBytes(UTF_8)),
    ("`{}`, 200", 200, "{}".getBytes(UTF_8))
  )

  def spec = suite("OfrepNullBodySpec")(
    test("every read of a non-OFREP body is typed, and `null` reads like `{`") {
      ZIO.scoped {
        for {
          provider <- OFREPProvider.make(url)
          env      <- FeatureFlags.fromProvider(provider).build
          ff = env.get[FeatureFlags]
          results <- ZIO.foreach(bodies) { case (name, status, body) =>
            for {
              _   <- ZIO.succeed(response.set((status, body)))
              rs  <- ZIO.attemptBlocking(rawReads(provider)).orDie
              api <- apiReads(ff)
            } yield (name, rs, api)
          }
          untyped = results.flatMap { case (n, rs, _) =>
            rs.collect { case (k, v) if v.startsWith("UNTYPED") => s"$n/$k" }
          }
          // The documented outcome for a body that is not OFREP JSON: GENERAL "response is not OFREP JSON".
          mislabeled = results.flatMap { case (n, _, api) =>
            api.collect { case (k, v) if !k.endsWith("OrDefault") && !v.contains("not OFREP JSON") => s"$n/$k" }
          }
          controlOk = results.head._3.forall { case (k, v) => k.endsWith("OrDefault") || v.contains("not OFREP JSON") }
        } yield assertTrue(controlOk) && assertTrue(untyped.isEmpty) && assertTrue(
          mislabeled.filter(_.contains("null")).isEmpty
        )
      }
    },
    test("only the contrib's NullPointerException for a null response is relabeled") {
      def at(cls: String, method: String): NullPointerException = {
        val e = new NullPointerException("synthetic")
        e.setStackTrace(Array(new StackTraceElement(cls, method, "X.java", 1)))
        e
      }
      val inResolution = at("dev.openfeature.contrib.providers.ofrep.internal.Resolution", "<init>")
      val elsewhere    = at("dev.openfeature.contrib.providers.ofrep.internal.Resolver", "resolve")
      val noFrames     = { val e = new NullPointerException(); e.setStackTrace(Array.empty); e }
      assertTrue(
        OFREPFeatureProvider.isNullBody(inResolution),
        !OFREPFeatureProvider.isNullBody(elsewhere),
        OFREPFeatureProvider.isNullBody(noFrames)
      )
    }
  ) @@ sequential @@ withLiveClock @@ timeout(2.minutes)
}
