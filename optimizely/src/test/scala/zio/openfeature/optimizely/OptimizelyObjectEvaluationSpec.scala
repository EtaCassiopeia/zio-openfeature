package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{ImmutableContext, MutableStructure, Reason, Value}
import zio._
import zio.test._

import java.util.concurrent.TimeUnit

/** Object-evaluation tests for `OptimizelyFeatureProvider`, guarding the fix for #264.
  *
  * Before the fix, `getObjectEvaluation` returned the ENTIRE `decision.getVariables` map as a `Structure` and never
  * fell back to the OpenFeature default. It now mirrors the other typed paths: it reads the single variable named by
  * `variableKey` (default `"value"`) as a JSON object and falls back to `defaultValue` with reason `DEFAULT` when that
  * variable is absent or not a JSON object.
  *
  * WireMock-backed (no Docker) so this runs on every PR, using the same fail-fast HTTP + shutdown-in-finally hygiene as
  * the lifecycle/concurrency specs.
  */
object OptimizelyObjectEvaluationSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/object-eval-key.json"
  // `decide` needs a userId; a bare targeting key is enough for these rollout-only flags.
  private val targetedContext = new ImmutableContext("user-object")

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private def withMockServer[A](body: WireMockServer => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try body(server)
    finally server.stop()
  }

  private def datafileUrl(server: WireMockServer): String =
    s"http://localhost:${server.port()}$DatafilePath"

  /** Same fail-fast client build as the lifecycle spec: aggressive timeouts and a fail-fast HTTP client so a poll in
    * flight when WireMock stops fails immediately instead of retrying forever on a non-daemon thread.
    */
  private def buildClient(
    server: WireMockServer,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofSeconds(3600)
  ): Optimizely = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey("object-eval-key")
      .withUrl(datafileUrl(server))
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
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

  // Optimizely parses JSON numbers into either Integer or Double depending on the JSON library on the classpath, so
  // accept both representations of `10`.
  private def numericEquals(v: Value, expected: Int): Boolean =
    Option(v.asInteger).exists(_.intValue == expected) ||
      Option(v.asDouble).exists(_.doubleValue == expected.toDouble)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider object evaluation")(
    test("falls back to the OF default (reason DEFAULT) when the variable is not a JSON object (#264)") {
      withMockServer { server =>
        // `lifecycle_flag`'s `value` variable is a STRING, so reading it as a Map fails -> the fix must return the
        // supplied default, NOT a Structure wrapping the whole variables map (the old bug).
        val provider = readyProvider(server, readResource("/test-datafile-with-flag.json"))
        try {
          val sentinel = new Value(new MutableStructure().add("sentinel", "dflt"))
          val result   = provider.getObjectEvaluation("lifecycle_flag", sentinel, targetedContext)
          val struct   = result.getValue.asStructure
          assertTrue(
            // Fell back to the caller's default...
            result.getReason == Reason.DEFAULT.name(),
            struct.getValue("sentinel").asString == "dflt",
            // ...and did NOT smuggle the flag's `value` string back as a whole-map Structure (old #264 behaviour).
            struct.getValue("value") == null
          )
        } finally provider.shutdown()
      }
    },
    test("reads the JSON variable named by variableKey into a Structure") {
      withMockServer { server =>
        val provider = readyProvider(server, readResource("/datafiles/object-flag.json"))
        try {
          val default = new Value(new MutableStructure().add("sentinel", "dflt"))
          val result  = provider.getObjectEvaluation("object_flag", default, targetedContext)
          val struct  = result.getValue.asStructure
          assertTrue(
            result.getReason != Reason.DEFAULT.name(),
            struct.getValue("theme").asString == "dark",
            numericEquals(struct.getValue("limit"), 10),
            // Not the fallback default.
            struct.getValue("sentinel") == null
          )
        } finally provider.shutdown()
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
