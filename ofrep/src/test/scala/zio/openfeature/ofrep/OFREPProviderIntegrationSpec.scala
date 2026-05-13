package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ErrorCode, ImmutableContext, MutableContext, Value}
import zio.test._

/** End-to-end tests against a WireMock-backed OFREP server. Verifies that the OFREP contrib provider's wire calls match
  * the OFREP protocol and that responses round-trip correctly through `OFREPProvider`'s factories.
  *
  * Each test owns its own WireMock instance for isolation; the small per-test startup cost is paid back in
  * test-independence. Tests run sequentially (`TestAspect.sequential`) because the contrib provider 0.0.1's internal
  * executor handling is order-sensitive — see PR #119 review notes.
  */
object OFREPProviderIntegrationSpec extends ZIOSpecDefault {

  private val emptyContext = new ImmutableContext()

  private def withMockServer[A](body: WireMockServer => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try body(server)
    finally server.stop()
  }

  private def baseUrl(server: WireMockServer): String =
    s"http://localhost:${server.port()}"

  def spec = suite("OFREP integration (WireMock)")(
    test("getBooleanEvaluation returns the server's value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/bool-flag"))
            .willReturn(
              okJson("""{"key":"bool-flag","value":true,"variant":"on","reason":"TARGETING_MATCH"}""")
            )
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getBooleanEvaluation("bool-flag", java.lang.Boolean.FALSE, emptyContext)
          assertTrue(result.getValue == java.lang.Boolean.TRUE) &&
          assertTrue(result.getVariant == "on")
        } finally provider.shutdown()
      }
    },
    test("getStringEvaluation returns the server's value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/greeting"))
            .willReturn(okJson("""{"key":"greeting","value":"hello","reason":"STATIC"}"""))
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getStringEvaluation("greeting", "default", emptyContext)
          assertTrue(result.getValue == "hello")
        } finally provider.shutdown()
      }
    },
    test("getIntegerEvaluation returns the server's value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/max-items"))
            .willReturn(okJson("""{"key":"max-items","value":42,"reason":"STATIC"}"""))
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getIntegerEvaluation("max-items", Integer.valueOf(0), emptyContext)
          assertTrue(result.getValue == Integer.valueOf(42))
        } finally provider.shutdown()
      }
    },
    test("getDoubleEvaluation returns the server's value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/rate"))
            .willReturn(okJson("""{"key":"rate","value":2.5,"reason":"STATIC"}"""))
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getDoubleEvaluation("rate", java.lang.Double.valueOf(0.0), emptyContext)
          assertTrue(result.getValue == java.lang.Double.valueOf(2.5))
        } finally provider.shutdown()
      }
    },
    test("getObjectEvaluation returns the server's object value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/config"))
            .willReturn(
              okJson("""{"key":"config","value":{"timeout":30,"retries":3},"reason":"STATIC"}""")
            )
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getObjectEvaluation("config", new Value(), emptyContext)
          assertTrue(result.getValue != null) &&
          assertTrue(result.getReason == "STATIC")
        } finally provider.shutdown()
      }
    },
    test("404 returns FLAG_NOT_FOUND error code and default value") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/missing-flag"))
            .willReturn(
              aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"errorCode":"FLAG_NOT_FOUND","errorDetails":"Flag not found"}""")
            )
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getBooleanEvaluation("missing-flag", java.lang.Boolean.FALSE, emptyContext)
          assertTrue(result.getValue == java.lang.Boolean.FALSE) &&
          assertTrue(result.getErrorCode == ErrorCode.FLAG_NOT_FOUND)
        } finally provider.shutdown()
      }
    },
    test("401 surfaces a non-null error code and returns default") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/protected-flag"))
            .willReturn(
              aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"errorCode":"GENERAL","errorDetails":"unauthorized"}""")
            )
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val result = provider.getBooleanEvaluation("protected-flag", java.lang.Boolean.FALSE, emptyContext)
          assertTrue(result.getValue == java.lang.Boolean.FALSE) &&
          assertTrue(result.getErrorCode != null)
        } finally provider.shutdown()
      }
    },
    test("evaluation forwards targeting key and attributes to the server") {
      withMockServer { server =>
        // Match any body — we just assert the call was made with a JSON body that contains the expected fields.
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/personalised"))
            .withRequestBody(matchingJsonPath("$.context.targetingKey", equalTo("user-42")))
            .withRequestBody(matchingJsonPath("$.context.plan", equalTo("premium")))
            .willReturn(okJson("""{"key":"personalised","value":true,"reason":"TARGETING_MATCH"}"""))
        )
        val provider = OFREPProvider(baseUrl(server))
        try {
          val ctx = new MutableContext("user-42")
          ctx.add("plan", "premium")
          val result = provider.getBooleanEvaluation("personalised", java.lang.Boolean.FALSE, ctx)
          assertTrue(result.getValue == java.lang.Boolean.TRUE)
        } finally provider.shutdown()
      }
    }
  ) @@ TestAspect.sequential
}
