package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ImmutableContext, Reason}
import zio._
import zio.test._

// A seeded client is ready without its fetch: a spec that only needs a ready provider does not ride on one HTTP round
// trip with a wall-clock budget.
object OptimizelyTestSupportSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/typed-variables-key.json"

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  def spec = suite("OptimizelyTestSupportSpec")(
    test("a seeded client initializes at once and serves while its fetch has not answered") {
      ZIO
        .attemptBlocking {
          val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
          server.start()
          try {
            val json = readResource("/datafiles/typed-variables.json")
            // The fetch answers only after the whole init wait: an unseeded client could not become ready in time.
            server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(json).withFixedDelay(30000)))
            val client = OptimizelyTestSupport.wiredClient(
              s"http://localhost:${server.port()}$DatafilePath",
              "typed-variables-key",
              datafile = Some(json)
            )
            val provider = new OptimizelyFeatureProvider(client, OptimizelyTestSupport.InitWait, closeOnShutdown = true)
            try {
              val start = java.lang.System.nanoTime()
              provider.initialize(new ImmutableContext())
              val took = java.time.Duration.ofNanos(java.lang.System.nanoTime() - start)
              val eval = provider.getBooleanEvaluation("typed_flag", false, new ImmutableContext("user-1"))
              (took, eval.getReason, eval.getErrorCode)
            } finally provider.shutdown()
          } finally server.stop()
        }
        .map { case (took, reason, errorCode) =>
          // Well inside the init wait: an unseeded client would wait it out and throw.
          assertTrue(
            took.compareTo(OptimizelyTestSupport.InitWait.dividedBy(2)) < 0,
            errorCode == null,
            reason == Reason.TARGETING_MATCH.name
          )
        }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(1.minute)
}
