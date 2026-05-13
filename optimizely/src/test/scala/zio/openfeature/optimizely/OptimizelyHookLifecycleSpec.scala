package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{FlagEvaluationDetails, Hook, HookContext, ImmutableContext, OpenFeatureAPI}
import zio._
import zio.test._

import java.util.{Optional, UUID}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verifies OpenFeature hook lifecycle around real Optimizely evaluations. Hooks must fire whether the evaluation
  * succeeds (real flag, READY provider) or surfaces an error code (unknown flag, missing targeting key).
  *
  * `error` hook only fires when the provider *throws*. Our provider catches everything and surfaces errors via
  * `FlagEvaluationDetails.errorCode`, so the `error` hook should NOT fire — this is verified explicitly.
  */
object OptimizelyHookLifecycleSpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/hooks-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")
  private val Flag          = "lifecycle_flag"
  private val targetedCtx   = new ImmutableContext("user-hooks")

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
      .withSdkKey("hooks-key")
      .withUrl(s"http://localhost:${server.port()}$DatafilePath")
      .withBlockingTimeout(2000, TimeUnit.MILLISECONDS)
      .withPollingInterval(3600, TimeUnit.SECONDS)
      .build()
    Optimizely.builder().withConfigManager(mgr).build()
  }

  final private class CountingHook extends Hook[Object] {
    val before       = new AtomicInteger(0)
    val after        = new AtomicInteger(0)
    val error        = new AtomicInteger(0)
    val finallyAfter = new AtomicInteger(0)

    override def before(
      ctx: HookContext[Object],
      hints: java.util.Map[String, Object]
    ): Optional[dev.openfeature.sdk.EvaluationContext] = {
      val _ = this.before.incrementAndGet()
      Optional.empty()
    }

    override def after(
      ctx: HookContext[Object],
      details: FlagEvaluationDetails[Object],
      hints: java.util.Map[String, Object]
    ): Unit = { val _ = this.after.incrementAndGet() }

    override def error(
      ctx: HookContext[Object],
      ex: Exception,
      hints: java.util.Map[String, Object]
    ): Unit = { val _ = this.error.incrementAndGet() }

    override def finallyAfter(
      ctx: HookContext[Object],
      details: FlagEvaluationDetails[Object],
      hints: java.util.Map[String, Object]
    ): Unit = { val _ = this.finallyAfter.incrementAndGet() }
  }

  private def runWithProvider[A](registerErrorStub: Boolean = false)(
    body: (dev.openfeature.sdk.Client, CountingHook) => A
  ): A = withMockServer { server =>
    if (registerErrorStub) {
      server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(404).withBody("Not Found")))
    } else {
      server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
    }
    val provider =
      new OptimizelyFeatureProvider(buildClient(server), java.time.Duration.ofSeconds(3), closeOnShutdown = true)
    val api    = OpenFeatureAPI.getInstance()
    val domain = s"optimizely-hooks-${UUID.randomUUID()}"
    val hook   = new CountingHook
    try {
      val client = api.getClient(domain)
      client.addHooks(hook)
      // setProviderAndWait blocks until the provider is READY (or throws if init fails). Suits our hooks tests
      // which need the provider live before they evaluate.
      api.setProviderAndWait(domain, provider)
      body(client, hook)
    } finally {
      scala.util.Try(api.shutdown())
      ()
    }
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider OpenFeature hook lifecycle")(
    test("successful boolean eval -> before, after, finallyAfter fire; error does NOT fire") {
      runWithProvider() { (client, hook) =>
        val details = client.getBooleanDetails(Flag, java.lang.Boolean.FALSE, targetedCtx)
        assertTrue(
          details.getValue == java.lang.Boolean.TRUE,
          hook.before.get() == 1,
          hook.after.get() == 1,
          hook.finallyAfter.get() == 1,
          hook.error.get() == 0
        )
      }
    },
    test("unknown flag -> before + error + finallyAfter fire; after does NOT fire (errorCode is set)") {
      runWithProvider() { (client, hook) =>
        val details = client.getBooleanDetails("flag-that-does-not-exist", java.lang.Boolean.TRUE, targetedCtx)
        assertTrue(
          details.getValue == java.lang.Boolean.TRUE,
          details.getErrorCode != null,
          hook.before.get() == 1,
          hook.after.get() == 0,
          hook.error.get() == 1,
          hook.finallyAfter.get() == 1
        )
      }
    },
    test("missing targeting key -> before + error + finallyAfter fire; provider surfaces TARGETING_KEY_MISSING") {
      runWithProvider() { (client, hook) =>
        val details = client.getBooleanDetails(Flag, java.lang.Boolean.TRUE, new ImmutableContext())
        assertTrue(
          details.getErrorCode == dev.openfeature.sdk.ErrorCode.TARGETING_KEY_MISSING,
          hook.before.get() == 1,
          hook.after.get() == 0,
          hook.error.get() == 1,
          hook.finallyAfter.get() == 1
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
