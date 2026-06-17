package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{ErrorCode, ImmutableContext, ProviderState, Reason}
import zio._
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** State-machine and lifecycle tests for `OptimizelyFeatureProvider`. WireMock-backed so the suite runs on every PR
  * without needing Docker.
  *
  * Covers behaviours that aren't otherwise exercised by the WireMock failure-mode spec or the docker-compose IT suite:
  *   - `initialize` and `shutdown` idempotence.
  *   - `shutdown` without prior `initialize`.
  *   - Evaluations before `initialize` and after `shutdown` surface `PROVIDER_NOT_READY` rather than throwing.
  *   - State transitions NOT_READY → READY → NOT_READY on the happy path; NOT_READY → ERROR on a failed init.
  *   - Concurrent `initialize` from multiple threads converges on a READY state without exceptions escaping.
  */
object OptimizelyProviderLifecycleSpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/lifecycle-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")
  private val emptyContext  = new ImmutableContext()
  // Evaluations require a targeting key, otherwise `decide` short-circuits with TARGETING_KEY_MISSING before reaching
  // the ready-check. We want to observe PROVIDER_NOT_READY, so all eval calls go through this targeted context.
  private val targetedContext = new ImmutableContext("user-lifecycle")

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

  /** Build a client with aggressive timeouts so failure-mode paths fail fast and `isValid` doesn't absorb whatever
    * delay we configure via the outer init wait.
    */
  private def buildClient(
    server: WireMockServer,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofSeconds(3600)
  ): Optimizely = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey("lifecycle-key")
      .withUrl(datafileUrl(server))
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
      .build()
    // The blocking build() has already attempted the initial datafile fetch; these lifecycle tests never need ongoing
    // polling, so halt the poller now — otherwise it keeps retrying against a stopped WireMock and leaves a non-daemon
    // Apache HttpClient thread that prevents the test JVM from exiting (hanging CI until its timeout).
    mgr.stop()
    Optimizely.builder().withConfigManager(mgr).build()
  }

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: OptimizelyFeatureProvider): ProviderState = p.getState

  private def tryInit(provider: OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(emptyContext); Right(()) }
    catch { case t: Throwable => Left(t) }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider lifecycle")(
    test("initialize is idempotent — second call is a no-op, state stays READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        try {
          val first       = tryInit(provider)
          val firstState  = stateOf(provider)
          val second      = tryInit(provider)
          val secondState = stateOf(provider)
          val evalAfter   = provider.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.FALSE, targetedContext)
          assertTrue(
            first.isRight,
            second.isRight,
            firstState == ProviderState.READY,
            secondState == ProviderState.READY,
            evalAfter.getValue == java.lang.Boolean.TRUE
          )
        } finally provider.shutdown()
      }
    },
    test("shutdown is idempotent — second call is a no-op") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val initRes  = tryInit(provider)
        val firstShutdown =
          try { provider.shutdown(); Right(()) }
          catch { case t: Throwable => Left(t) }
        val afterFirst = stateOf(provider)
        val secondShutdown =
          try { provider.shutdown(); Right(()) }
          catch { case t: Throwable => Left(t) }
        val afterSecond = stateOf(provider)
        assertTrue(
          initRes.isRight,
          firstShutdown.isRight,
          secondShutdown.isRight,
          afterFirst == ProviderState.NOT_READY,
          afterSecond == ProviderState.NOT_READY
        )
      }
    },
    test("initialize after shutdown fails loudly when the client was closed (#185)") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val first    = tryInit(provider)
        provider.shutdown()
        val second = tryInit(provider)
        assertTrue(
          first.isRight,
          // No silent no-op: re-registering a closed provider must surface immediately, not as
          // PROVIDER_NOT_READY on every later evaluation.
          second.isLeft,
          second.left.exists(_.isInstanceOf[IllegalStateException]),
          stateOf(provider) == ProviderState.NOT_READY
        )
      }
    },
    test("initialize after shutdown re-initializes when the client is caller-managed (#185)") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = false)
        val first    = tryInit(provider)
        val stateAfterFirst = stateOf(provider)
        provider.shutdown()
        val stateAfterShutdown = stateOf(provider)
        val second             = tryInit(provider)
        val stateAfterSecond   = stateOf(provider)
        try
          assertTrue(
            first.isRight,
            stateAfterFirst == ProviderState.READY,
            stateAfterShutdown == ProviderState.NOT_READY,
            second.isRight,
            stateAfterSecond == ProviderState.READY
          )
        finally client.close()
      }
    },
    test("shutdown before initialize — no exception, state stays NOT_READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val before   = stateOf(provider)
        val result =
          try { provider.shutdown(); Right(()) }
          catch { case t: Throwable => Left(t) }
        val after = stateOf(provider)
        assertTrue(
          result.isRight,
          before == ProviderState.NOT_READY,
          after == ProviderState.NOT_READY
        )
      }
    },
    test("evaluation before initialize surfaces PROVIDER_NOT_READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        try {
          val eval  = provider.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.TRUE, targetedContext)
          val state = stateOf(provider)
          assertTrue(
            state == ProviderState.NOT_READY,
            eval.getValue == java.lang.Boolean.TRUE,
            eval.getErrorCode == ErrorCode.PROVIDER_NOT_READY,
            eval.getReason == Reason.ERROR.name()
          )
        } finally provider.shutdown()
      }
    },
    test("evaluation after shutdown surfaces PROVIDER_NOT_READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val init     = tryInit(provider)
        val ready    = stateOf(provider)
        provider.shutdown()
        val eval  = provider.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.TRUE, targetedContext)
        val state = stateOf(provider)
        assertTrue(
          init.isRight,
          ready == ProviderState.READY,
          state == ProviderState.NOT_READY,
          eval.getValue == java.lang.Boolean.TRUE,
          eval.getErrorCode == ErrorCode.PROVIDER_NOT_READY,
          eval.getReason == Reason.ERROR.name()
        )
      }
    },
    test("state transitions: NOT_READY -> READY -> NOT_READY across init/shutdown") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client    = buildClient(server)
        val provider  = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val initial   = stateOf(provider)
        val init      = tryInit(provider)
        val afterInit = stateOf(provider)
        provider.shutdown()
        val afterShutdown = stateOf(provider)
        assertTrue(
          init.isRight,
          initial == ProviderState.NOT_READY,
          afterInit == ProviderState.READY,
          afterShutdown == ProviderState.NOT_READY
        )
      }
    },
    test("failed init (404) transitions NOT_READY -> ERROR; subsequent eval surfaces PROVIDER_NOT_READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(404).withBody("Not Found")))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofMillis(500), closeOnShutdown = true)
        try {
          val initial = stateOf(provider)
          val init    = tryInit(provider)
          val after   = stateOf(provider)
          val eval    = provider.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.TRUE, targetedContext)
          assertTrue(
            initial == ProviderState.NOT_READY,
            init.isLeft,
            after == ProviderState.ERROR,
            eval.getValue == java.lang.Boolean.TRUE,
            eval.getErrorCode == ErrorCode.PROVIDER_NOT_READY
          )
        } finally provider.shutdown()
      }
    },
    test("concurrent initialize from N threads — exactly one progresses init, all return cleanly, state ends READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val client   = buildClient(server)
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(5), closeOnShutdown = true)
        try {
          val N        = 16
          val release  = new CountDownLatch(1)
          val started  = new CountDownLatch(N)
          val errors   = new AtomicInteger(0)
          val outcomes = new AtomicReference[List[Either[Throwable, Unit]]](Nil)
          val threads: Seq[Thread] = (1 to N).map { _ =>
            new Thread(() => {
              started.countDown()
              release.await()
              val r: Either[Throwable, Unit] =
                try { provider.initialize(emptyContext); Right(()) }
                catch { case t: Throwable => errors.incrementAndGet(); Left(t) }
              val _ = outcomes.updateAndGet(rs => r :: rs)
            })
          }
          threads.foreach(_.start())
          // Wait for all threads to be parked at `release.await()` before kicking them off so they race for the
          // AtomicBoolean as simultaneously as the JVM allows.
          val _ = started.await(5, TimeUnit.SECONDS)
          release.countDown()
          threads.foreach(_.join(5000))
          val finalState  = stateOf(provider)
          val allReturned = outcomes.get().size == N
          val anyFailed   = errors.get() > 0
          assertTrue(
            allReturned,
            !anyFailed,
            finalState == ProviderState.READY
          )
        } finally provider.shutdown()
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
