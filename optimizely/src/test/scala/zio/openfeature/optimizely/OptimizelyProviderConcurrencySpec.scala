package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{ErrorCode, ImmutableContext, ProviderState, Value}
import zio._
import zio.test._

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** Stress tests for `OptimizelyFeatureProvider`. WireMock-backed so the suite runs on every PR. Verifies that:
  *   - Many parallel evaluations after init produce consistent results without exceptions.
  *   - Mixed-type evaluations (boolean / string / int / double / object) interleave safely.
  *   - Evaluations racing against `initialize` either succeed or surface `PROVIDER_NOT_READY`; no exception escapes.
  *   - Evaluations racing against `shutdown` likewise return clean errors and never throw.
  */
object OptimizelyProviderConcurrencySpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/concurrency-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")
  private val Flag          = "lifecycle_flag"

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

  // Build the client the same way `OptimizelyProvider.buildClient` does: `build(true)` skips the blocking initial
  // fetch and does NOT leave a running poller — `mgr.stop()` ensures construction performs no network activity. The
  // poller is owned by the provider (started by `initialize()`, stopped by `shutdown()` via `Optimizely.close()`), so
  // it can never become an orphaned "retries forever" thread. The old no-arg `.build()` started a live poller at
  // construction; when WireMock stopped, its Apache HttpClient retried against the dead socket on a thread that
  // outlived every test and kept the (even forked) JVM from exiting — hanging `sbt test` until CI's timeout.
  private def buildClient(
    server: WireMockServer,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofSeconds(3600)
  ): (Optimizely, HttpProjectConfigManager) = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey("concurrency-key")
      .withUrl(datafileUrl(server))
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
      .build(true)
    mgr.stop()
    (Optimizely.builder().withConfigManager(mgr).build(), mgr)
  }

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: OptimizelyFeatureProvider): ProviderState = p.getState

  /** Spawn `n` worker threads, release them simultaneously via a latch, and collect outcomes. */
  private def race[A](n: Int)(work: Int => A): IndexedSeq[Either[Throwable, A]] = {
    val pool      = Executors.newFixedThreadPool(math.min(n, 64))
    val release   = new CountDownLatch(1)
    val started   = new CountDownLatch(n)
    val results   = new Array[Either[Throwable, A]](n)
    val countdown = new CountDownLatch(n)
    try {
      (0 until n).foreach { i =>
        pool.submit(new Runnable {
          override def run(): Unit = {
            started.countDown()
            release.await()
            results(i) =
              try Right(work(i))
              catch { case t: Throwable => Left(t) }
            countdown.countDown()
          }
        })
        ()
      }
      started.await(10, TimeUnit.SECONDS)
      release.countDown()
      countdown.await(30, TimeUnit.SECONDS)
      results.toIndexedSeq
    } finally {
      // Reap worker threads deterministically: `shutdownNow` interrupts, then join so no evaluation thread outlives
      // the test and races provider/WireMock teardown. `Executors.newFixedThreadPool` threads are non-daemon.
      pool.shutdownNow()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      ()
    }
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider concurrency")(
    test("N concurrent boolean evaluations after init return consistent results without exceptions") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val (client, mgr) = buildClient(server)
        val provider =
          new OptimizelyFeatureProvider(
            client,
            java.time.Duration.ofSeconds(3),
            closeOnShutdown = true,
            configManager = Some(mgr)
          )
        try {
          provider.initialize(new ImmutableContext())
          val N    = 1000
          val ctxs = (0 until N).map(i => new ImmutableContext(s"user-$i"))
          val outcomes = race(N) { i =>
            provider.getBooleanEvaluation(Flag, java.lang.Boolean.FALSE, ctxs(i))
          }
          val anyThrew      = outcomes.exists(_.isLeft)
          val allEnabled    = outcomes.collect { case Right(e) => e.getValue }.forall(_ == java.lang.Boolean.TRUE)
          val countReturned = outcomes.size
          assertTrue(
            !anyThrew,
            countReturned == N,
            allEnabled
          )
        } finally provider.shutdown()
      }
    },
    test("N concurrent mixed-type evaluations don't trip over each other") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val (client, mgr) = buildClient(server)
        val provider =
          new OptimizelyFeatureProvider(
            client,
            java.time.Duration.ofSeconds(3),
            closeOnShutdown = true,
            configManager = Some(mgr)
          )
        try {
          provider.initialize(new ImmutableContext())
          val N    = 500
          val ctxs = (0 until N).map(i => new ImmutableContext(s"user-$i"))
          val outcomes = race(N) { i =>
            val ctx = ctxs(i)
            i % 5 match {
              case 0 => provider.getBooleanEvaluation(Flag, java.lang.Boolean.FALSE, ctx).getValue.toString
              case 1 => provider.getStringEvaluation(Flag, "fallback", ctx).getValue
              case 2 => provider.getIntegerEvaluation(Flag, java.lang.Integer.valueOf(-1), ctx).getValue.toString
              case 3 => provider.getDoubleEvaluation(Flag, java.lang.Double.valueOf(-1.0), ctx).getValue.toString
              case _ => provider.getObjectEvaluation(Flag, new Value(), ctx).getValue.toString
            }
          }
          val anyThrew    = outcomes.exists(_.isLeft)
          val allReturned = outcomes.size == N
          assertTrue(!anyThrew, allReturned)
        } finally provider.shutdown()
      }
    },
    test("evaluations racing initialize never throw — each is either ready or PROVIDER_NOT_READY") {
      withMockServer { server =>
        // Delay the datafile response so initialize is genuinely in-flight while evaluators race against it.
        server.stubFor(
          get(urlEqualTo(DatafilePath))
            .willReturn(okJson(ValidDatafile).withFixedDelay(200))
        )
        val (client, mgr) = buildClient(server)
        val provider =
          new OptimizelyFeatureProvider(
            client,
            java.time.Duration.ofSeconds(2),
            closeOnShutdown = true,
            configManager = Some(mgr)
          )
        try {
          val N           = 200
          val initStarted = new CountDownLatch(1)
          val initThread = new Thread(() => {
            initStarted.countDown()
            try provider.initialize(new ImmutableContext())
            catch { case _: Throwable => () }
          })
          initThread.start()
          initStarted.await()
          val ctxs = (0 until N).map(i => new ImmutableContext(s"racer-$i"))
          val outcomes = race(N) { i =>
            provider.getBooleanEvaluation(Flag, java.lang.Boolean.FALSE, ctxs(i))
          }
          initThread.join(5000)
          val anyThrew = outcomes.exists(_.isLeft)
          val classified = outcomes.collect { case Right(e) =>
            if (e.getErrorCode == ErrorCode.PROVIDER_NOT_READY) "not-ready"
            else if (e.getValue == java.lang.Boolean.TRUE) "ready-true"
            else "other"
          }
          val onlyKnownStates = classified.forall(s => s == "not-ready" || s == "ready-true")
          val finalState      = stateOf(provider)
          assertTrue(!anyThrew, onlyKnownStates, finalState == ProviderState.READY)
        } finally provider.shutdown()
      }
    },
    test("evaluations racing shutdown never throw — they surface PROVIDER_NOT_READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val (client, mgr) = buildClient(server)
        val provider =
          new OptimizelyFeatureProvider(
            client,
            java.time.Duration.ofSeconds(3),
            closeOnShutdown = true,
            configManager = Some(mgr)
          )
        val pool = Executors.newFixedThreadPool(32)
        try {
          provider.initialize(new ImmutableContext())
          val N      = 200
          val live   = new AtomicInteger(0)
          val ctxs   = (0 until N).map(i => new ImmutableContext(s"shutdown-racer-$i"))
          val gate   = new CountDownLatch(1)
          val done   = new CountDownLatch(N)
          val errors = new AtomicInteger(0)
          (0 until N).foreach { i =>
            pool.submit(new Runnable {
              override def run(): Unit = {
                gate.await()
                try {
                  val e = provider.getBooleanEvaluation(Flag, java.lang.Boolean.FALSE, ctxs(i))
                  if (e.getErrorCode == null && e.getValue == java.lang.Boolean.TRUE) {
                    val _ = live.incrementAndGet()
                  }
                } catch { case _: Throwable => val _ = errors.incrementAndGet() }
                done.countDown()
              }
            })
            ()
          }
          // Fire the racers and call shutdown shortly after — some evaluations land before, some after.
          gate.countDown()
          Thread.sleep(5)
          provider.shutdown()
          val finished   = done.await(15, TimeUnit.SECONDS)
          val stateAfter = stateOf(provider)
          assertTrue(
            finished,
            errors.get() == 0,
            stateAfter == ProviderState.NOT_READY
          )
        } finally {
          // Join our worker threads and ensure the client is closed (idempotent) before WireMock stops, so no
          // evaluation or HTTP retry thread races the server teardown — even if an assertion above threw.
          pool.shutdownNow()
          pool.awaitTermination(5, TimeUnit.SECONDS)
          provider.shutdown()
        }
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
