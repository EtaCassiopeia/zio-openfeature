package zio.openfeature.fuzz

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.code_intelligence.jazzer.api.{BugDetectors, FuzzedDataProvider}
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.{
  ConfigException,
  ConfigFactory,
  ConfigIncludeContext,
  ConfigIncluder,
  ConfigObject,
  ConfigParseOptions
}
import dev.openfeature.sdk.{FeatureProvider, ImmutableContext, Value}
import dev.openfeature.sdk.exceptions.OpenFeatureError
import zio.*
import zio.openfeature.extras.{EnvVarProvider, HoconProvider}
import zio.openfeature.ofrep.OFREPProvider

/** The invariant every target checks: whatever the input, each typed read of a provider either returns an evaluation or
  * throws an `OpenFeatureError`, the typed failure the SDK turns into an error code. Any other throwable escapes to
  * Jazzer, which reports it as a finding with the input that caused it.
  */
private[fuzz] object Invariant {

  // The keys a target reads. Keys come from application code, not from the untrusted source, so they are fixed: one
  // plain, one dotted (a HOCON path), one the input is unlikely to define.
  val keys: List[String] = List("flag", "group.flag", "missing")

  private val ctx = new ImmutableContext("user-1")

  def readsAreTyped(provider: FeatureProvider): Unit =
    keys.foreach { key =>
      typed(provider.getBooleanEvaluation(key, false, ctx))
      typed(provider.getStringEvaluation(key, "default", ctx))
      typed(provider.getIntegerEvaluation(key, 0, ctx))
      typed(provider.getDoubleEvaluation(key, 0.0, ctx))
      typed(provider.getObjectEvaluation(key, new Value(), ctx))
    }

  private def typed(read: => Any): Unit =
    try { val _ = read }
    catch { case _: OpenFeatureError => () }
}

/** Environment variables: every variable `EnvVarProvider` reads holds the fuzzed text. */
object EnvVarFuzz {
  def fuzzerTestOneInput(data: FuzzedDataProvider): Unit = {
    val value = data.consumeRemainingAsString()
    Invariant.readsAreTyped(EnvVarProvider.withLookup(_ => Some(value)))
  }
}

/** HOCON: the fuzzed text as the provider's configuration. Text Typesafe Config rejects (malformed, or with a
  * substitution it cannot resolve) is refused at construction, as documented, so it is skipped; everything it accepts
  * must read cleanly.
  */
object HoconFuzz {

  // `include file(...)` / `url(...)` would read the host's files or the network: refuse every include, so a run stays
  // hermetic and only the text itself is under test.
  private val noIncludes = ConfigParseOptions
    .defaults()
    .setIncluder(new ConfigIncluder {
      def withFallback(fallback: ConfigIncluder): ConfigIncluder             = this
      def include(context: ConfigIncludeContext, what: String): ConfigObject =
        throw new ConfigException.Generic(s"includes are refused while fuzzing: $what")
    })

  def fuzzerTestOneInput(data: FuzzedDataProvider): Unit = {
    val text     = data.consumeRemainingAsString()
    val provider =
      try Some(HoconProvider.fromConfig(ConfigFactory.parseString(text, noIncludes)))
      catch { case _: ConfigException => None }
    provider.foreach(Invariant.readsAreTyped)
  }
}

/** OFREP: a local server answers every evaluation request with a fuzzed status and body, and the provider reads it. */
object OfrepFuzz {

  // 429 is left out: the provider then backs off and stops asking, which would end exploration for the rest of a run.
  private val statuses = Array(200, 200, 200, 200, 400, 401, 403, 404, 500, 503)

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

  private lazy val provider: FeatureProvider = Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe
      .run(OFREPProvider.make(s"http://127.0.0.1:${server.getAddress.getPort}"))
      .getOrThrowFiberFailure()
  }

  /** Called once by Jazzer before the first input. The provider's requests to this target's own server are the point,
    * so the server-side request forgery detector may let exactly those through; it still reports any other host.
    */
  def fuzzerInitialize(): Unit = {
    // The returned handle is never closed on purpose: the permission lasts for the whole run.
    val _ = BugDetectors.allowNetworkConnections((host, port) =>
      (host == "127.0.0.1" || host == "localhost") && port == server.getAddress.getPort
    )
  }

  def fuzzerTestOneInput(data: FuzzedDataProvider): Unit = {
    val status = data.pickValue(statuses)
    response.set((status, data.consumeRemainingAsBytes()))
    Invariant.readsAreTyped(provider)
  }
}
