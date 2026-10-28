package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.OptimizelyUserContext
import com.optimizely.ab.cmab.service.{CmabDecision, CmabService}
import com.optimizely.ab.config.ProjectConfig
import com.optimizely.ab.event.LogEvent
import com.optimizely.ab.event.internal.payload.EventBatch
import com.optimizely.ab.optimizelydecision.OptimizelyDecideOption
import dev.openfeature.sdk.{ErrorCode, ImmutableContext, Reason}
import zio.test._

// Optimizely SDK 4.6.0 — CMAB experiments decide through a CmabService, a CMAB fetch failure is an evaluation
// ERROR (not a silent DEFAULT), and event payloads serialize with this build's Jackson.
object OptimizelyCmabSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/cmab-key.json"

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private def withProvider[A](cmab: CmabService)(body: OptimizelyFeatureProvider => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try {
      server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(readResource("/datafiles/cmab.json"))))
      val client = OptimizelyTestSupport.wiredClient(
        s"http://localhost:${server.port()}$DatafilePath",
        "cmab-key",
        customize = _.withCmabService(cmab)
      )
      val provider = new OptimizelyFeatureProvider(client, OptimizelyTestSupport.InitWait, closeOnShutdown = true)
      provider.initialize(new ImmutableContext())
      try body(provider)
      finally provider.shutdown()
    } finally server.stop()
  }

  private def stub(decide: () => CmabDecision): CmabService = new CmabService {
    override def getDecision(
      config: ProjectConfig,
      user: OptimizelyUserContext,
      ruleId: String,
      options: java.util.List[OptimizelyDecideOption]
    ): CmabDecision = decide()
  }

  private val user = new ImmutableContext("user-1")

  def spec = suite("OptimizelyCmabSpec")(
    test("a CMAB experiment serves the variation the CMAB service chose") {
      withProvider(stub(() => new CmabDecision("410", "uuid-1"))) { provider =>
        val eval = provider.getBooleanEvaluation("cmab_flag", false, user)
        assertTrue(
          eval.getValue.booleanValue(),
          eval.getVariant == "treatment",
          eval.getReason == Reason.TARGETING_MATCH.name(),
          eval.getErrorCode == null
        )
      }
    },
    test("a CMAB fetch failure is an evaluation error, never the '$' placeholder or a silent DEFAULT") {
      withProvider(stub(() => throw new RuntimeException("cmab endpoint down"))) { provider =>
        val eval = provider.getBooleanEvaluation("cmab_flag", false, user)
        assertTrue(
          eval.getReason == Reason.ERROR.name(),
          eval.getErrorCode == ErrorCode.GENERAL,
          !eval.getValue.booleanValue(),
          eval.getVariant != "$"
        )
      }
    },
    test("an event payload serializes with this build's Jackson") {
      val batch = new EventBatch.Builder()
        .setClientVersion("4.6.0")
        .setAccountId("test")
        .setProjectId("test")
        .setRevision("1")
        .setVisitors(java.util.Collections.emptyList())
        .build()
      val body = new LogEvent(
        LogEvent.RequestMethod.POST,
        "http://localhost/events",
        java.util.Collections.emptyMap(),
        batch
      ).getBody
      assertTrue(body.contains("\"account_id\""))
    }
  ) @@ TestAspect.sequential
}
