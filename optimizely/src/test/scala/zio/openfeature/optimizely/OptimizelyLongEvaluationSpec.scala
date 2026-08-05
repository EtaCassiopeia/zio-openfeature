package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{ImmutableContext, Reason, Value}
import zio._
import zio.test._

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

/** Long evaluation for `OptimizelyFeatureProvider` (SDK 1.22.0 native `getLongEvaluation`).
  *
  * Optimizely has no long-typed variable, so the provider reads the integer variable and widens — exact, because Int ->
  * Long cannot lose precision. The risk is entirely on the *default*: `typedEvaluation` hands its default straight back
  * both when the variable is absent and on the failure path, so seeding that read with a narrowed
  * `defaultValue.intValue()` would silently truncate any default outside Int range and return it as the answer.
  *
  * WireMock-backed (no Docker), mirroring the object-evaluation spec's fail-fast HTTP + shutdown-in-finally hygiene.
  */
object OptimizelyLongEvaluationSpec extends ZIOSpecDefault {

  private val DatafilePath    = "/datafiles/long-eval-key.json"
  private val targetedContext = new ImmutableContext("user-long")

  /** Comfortably outside Int range: narrowing this to Int yields 705032704. */
  private val BigDefault = 5000000000L

  /** A flag whose `value` variable is an integer (100 for premium users, 10 by default). */
  private val RateLimitFlag = "recommendation_rate_limit"

  /** Deliberately distinct from every value the datafile can produce, so a test can tell "resolved the variable" apart
    * from "handed the caller's default back".
    */
  private val Sentinel = java.lang.Long.valueOf(-987654321L)

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private def withMockServer[A](body: WireMockServer => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try body(server)
    finally server.stop()
  }

  private def buildClient(server: WireMockServer): Optimizely = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey("long-eval-key")
      .withUrl(s"http://localhost:${server.port()}$DatafilePath")
      .withBlockingTimeout(2000, TimeUnit.MILLISECONDS)
      .withPollingInterval(3600, TimeUnit.SECONDS)
      .withOptimizelyHttpClient(TestHttpClient.failFast())
      .build()
    Optimizely.builder().withConfigManager(mgr).build()
  }

  private def readyProvider(server: WireMockServer, datafile: String): OptimizelyFeatureProvider = {
    server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(datafile)))
    val client   = buildClient(server)
    val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
    provider.initialize(new ImmutableContext())
    provider
  }

  // One WireMock server + Optimizely client per DATAFILE rather than per test. Each pair costs a real HTTP server and
  // a polling-capable SDK client, and this module already runs a load-sensitive polling-lifecycle spec concurrently —
  // spinning five of them to make eight assertions measurably raises the odds of that spec's in-flight-poll race.
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider long evaluation")(
    test("falls back to the caller's Long default without narrowing it") {
      withMockServer { server =>
        // `lifecycle_flag`'s `value` variable is a STRING, so reading it as an Integer fails and the provider must
        // fall back to the caller's default. Seeding the integer read with a narrowed default would return
        // 705032704 for the out-of-range cases.
        val provider = readyProvider(server, readResource("/test-datafile-with-flag.json"))
        try {
          val big = provider.getLongEvaluation("lifecycle_flag", java.lang.Long.valueOf(BigDefault), targetedContext)
          val unknown = provider.getLongEvaluation("no-such-flag", java.lang.Long.valueOf(BigDefault), targetedContext)
          val small   = provider.getLongEvaluation("lifecycle_flag", java.lang.Long.valueOf(42L), targetedContext)
          assertTrue(
            big.getReason == Reason.DEFAULT.name(),
            big.getValue.longValue == BigDefault,
            unknown.getValue.longValue == BigDefault,
            small.getReason == Reason.DEFAULT.name(),
            small.getValue.longValue == 42L
          )
        } finally provider.shutdown()
      }
    },
    // The case above only exercises the *fallback* branch. This one covers the branch where a variable really
    // resolves — the one an earlier implementation got wrong by inferring "did it resolve?" from the reason, which
    // reports DEFAULT whenever a decision carries no variation key even though the variable read succeeded. Parity
    // with `getIntegerEvaluation` is the point: the two must never disagree about the same flag.
    test("a resolved integer variable is widened, never replaced by the default") {
      withMockServer { server =>
        val provider = readyProvider(server, readResource("/datafiles/audience-premium.json"))
        try {
          val premium  = new ImmutableContext("user-alice", Map("plan" -> new Value("premium")).asJava)
          val standard = new ImmutableContext("user-bob", Map("plan" -> new Value("standard")).asJava)
          val pLong    = provider.getLongEvaluation(RateLimitFlag, Sentinel, premium)
          val pInt     = provider.getIntegerEvaluation(RateLimitFlag, java.lang.Integer.valueOf(-1), premium)
          val sLong    = provider.getLongEvaluation(RateLimitFlag, Sentinel, standard)
          val sInt     = provider.getIntegerEvaluation(RateLimitFlag, java.lang.Integer.valueOf(-1), standard)
          assertTrue(
            pLong.getValue.longValue == 100L,
            pLong.getValue.longValue == pInt.getValue.longValue,
            pLong.getReason == pInt.getReason,
            // The variable's own default (10), NOT the caller's sentinel — the case where the decision may carry no
            // variation key while the variable still reads successfully.
            sLong.getValue.longValue == 10L,
            sLong.getValue.longValue != Sentinel.longValue,
            sLong.getValue.longValue == sInt.getValue.longValue,
            sLong.getReason == sInt.getReason
          )
        } finally provider.shutdown()
      }
    }
  ) @@ TestAspect.sequential
}
