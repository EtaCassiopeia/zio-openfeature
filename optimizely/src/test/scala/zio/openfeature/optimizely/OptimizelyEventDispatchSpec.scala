package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.RedirectingHttpClient
import com.optimizely.ab.event.{AsyncEventHandler, EventHandler, LogEvent}
import dev.openfeature.sdk.{ImmutableContext, Value}
import zio._
import zio.test._

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, Executors, TimeUnit}
import scala.jdk.CollectionConverters._

// #453: factory-built providers send impressions and conversions to Optimizely, batched, unless events are disabled.
object OptimizelyEventDispatchSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/events-key.json"
  private val EventsPath   = "/v1/events"

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private val user = new ImmutableContext("user-1", Map("plan" -> new Value("pro")).asJava)

  final private class Recording extends EventHandler {
    val events                                    = new CopyOnWriteArrayList[LogEvent]()
    override def dispatchEvent(e: LogEvent): Unit = { events.add(e); () }
    def bodies: List[String]                      = events.asScala.toList.map(_.getBody)
  }

  // Never returns from a dispatch until its thread is interrupted.
  final private class Blocking extends EventHandler {
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    override def dispatchEvent(e: LogEvent): Unit = {
      entered.countDown()
      try new CountDownLatch(1).await()
      catch { case _: InterruptedException => interrupted.countDown() }
    }
  }

  private val server: ZIO[Scope, Throwable, WireMockServer] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      s.start()
      s.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(readResource("/datafiles/track.json"))))
      s.stubFor(post(urlEqualTo(EventsPath)).willReturn(aResponse().withStatus(204)))
      s
    })(s => ZIO.attemptBlocking(s.stop()).ignore)

  private def config(server: WireMockServer, events: OptimizelyEvents): OptimizelyProviderConfig =
    OptimizelyProviderConfig(
      sdkKey = "events-key",
      datafileUrl = Some(s"http://localhost:${server.port()}$DatafilePath"),
      initWait = java.time.Duration.ofSeconds(5),
      blockingTimeout = Some(java.time.Duration.ofSeconds(2)),
      events = events
    )

  // A generous socket timeout: the client never retries, so one slow WireMock answer under load would otherwise fail
  // initialize() for good.
  private def datafileClient = Some(TestHttpClient.failFast(timeoutMillis = 5000))

  /** Builds the provider through the factory's scoped path, initializes it, and runs `use`. The provider's scope has
    * closed when this returns, so batched events have been flushed.
    */
  private def withProvider(server: WireMockServer, events: OptimizelyEvents, handler: EventHandler)(
    use: OptimizelyFeatureProvider => Unit
  ): IO[Any, Unit] =
    ZIO.scoped {
      OptimizelyProvider
        .scoped(config(server, events), datafileClient, Some(handler))
        .flatMap(p => ZIO.attemptBlocking { p.initialize(new ImmutableContext()); use(p) })
    }

  private def evaluate(p: OptimizelyFeatureProvider): Boolean =
    p.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.FALSE, user).getValue.booleanValue

  private def rejected(events: OptimizelyEvents): UIO[Option[String]] =
    OptimizelyProvider.make(OptimizelyProviderConfig("events-key", events = events)).flip.option.map(_.map(_.message))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyEventDispatchSpec (#453)")(
    test("a decision is sent as an impression for the user, flushed when the provider closes") {
      ZIO.scoped {
        for {
          s <- server
          recorder = new Recording
          enabled  = new AtomicBoolean(false)
          _ <- withProvider(s, OptimizelyEvents.Batched(), recorder)(p => enabled.set(evaluate(p)))
          value  = enabled.get
          bodies = recorder.bodies
          body   = bodies.mkString
        } yield assertTrue(
          value,
          bodies.size == 1,
          body.contains("\"visitor_id\":\"user-1\""),
          body.contains("\"type\":\"campaign_activated\""),
          body.contains("\"flag_key\":\"lifecycle_flag\"")
        )
      }
    },
    test("a track call is sent as a conversion") {
      ZIO.scoped {
        for {
          s <- server
          recorder = new Recording
          _ <- withProvider(s, OptimizelyEvents.Batched(), recorder)(_.track("purchase", user, null))
          bodies = recorder.bodies
          body   = bodies.mkString
        } yield assertTrue(
          bodies.size == 1,
          body.contains("\"visitor_id\":\"user-1\""),
          body.contains("\"key\":\"purchase\""),
          !body.contains("\"campaign_activated\"")
        )
      }
    },
    test("Disabled sends neither impressions nor conversions") {
      ZIO.scoped {
        for {
          s <- server
          recorder = new Recording
          _ <- withProvider(s, OptimizelyEvents.Disabled, recorder) { p =>
            evaluate(p): Unit
            p.track("purchase", user, null)
          }
          count = recorder.events.size
        } yield assertTrue(count == 0)
      }
    },
    test("events are batched by default") {
      val events = OptimizelyProviderConfig("events-key").events
      assertTrue(
        events == OptimizelyEvents.Batched(
          batchSize = 10,
          flushInterval = java.time.Duration.ofSeconds(30),
          closeTimeout = java.time.Duration.ofSeconds(3)
        )
      )
    },
    test("the SDK's async handler posts the batch, and no event thread outlives the provider's scope") {
      // Every thread the provider starts is created, directly or by a thread it started, from the thread that builds
      // it. Building on a thread in a dedicated group makes them identifiable, whatever else runs in this JVM.
      val group = new ThreadGroup("optimizely-events-spec")
      val builder = Executors.newSingleThreadExecutor { (r: Runnable) =>
        val t = new Thread(group, r, "optimizely-events-spec-builder")
        t.setDaemon(true)
        t
      }
      def lingering: List[Thread] = {
        val threads = new Array[Thread](group.activeCount() + 16)
        threads.take(group.enumerate(threads)).toList.filter { t =>
          t.isAlive && t.getName != "optimizely-events-spec-builder" &&
          (t.getName.startsWith("optimizely-event-") || !t.isDaemon)
        }
      }
      ZIO.scoped {
        for {
          s <- server
          handler = AsyncEventHandler
            .builder()
            .withCloseTimeout(3000L, TimeUnit.MILLISECONDS)
            .withOptimizelyHttpClient(RedirectingHttpClient.toLocalhost(s.port()))
            .build()
          _ <- ZIO.scoped {
            OptimizelyProvider
              .scoped(config(s, OptimizelyEvents.Batched()), datafileClient, Some(handler))
              .onExecutor(Executor.fromJavaExecutor(builder))
              .flatMap(p => ZIO.attemptBlocking { p.initialize(new ImmutableContext()); evaluate(p) })
          }
          _ <- ZIO.succeed(builder.shutdownNow())
          // shutdownNow does not wait for its thread to finish; allow a moment for threads to exit.
          left <- (ZIO.sleep(50.millis) *> ZIO.succeed(lingering))
            .repeatUntil(_.isEmpty)
            .timeout(5.seconds)
            .map(_.getOrElse(lingering))
          posts <- ZIO.succeed(s.findAll(postRequestedFor(urlEqualTo(EventsPath))).asScala.toList)
          body = posts.map(_.getBodyAsString).mkString
        } yield assertTrue(
          posts.size == 1,
          body.contains("\"campaign_activated\""),
          left.map(_.getName) == Nil
        )
      }
    },
    test("shutdown stays bounded when the event handler never returns") {
      ZIO.scoped {
        for {
          s <- server
          handler = new Blocking
          provider <- ZIO.acquireRelease(
            OptimizelyProvider.make(config(s, OptimizelyEvents.Batched()), datafileClient, Some(handler))
          )(p => ZIO.attemptBlocking(p.shutdown()).ignore)
          _       <- ZIO.attemptBlocking { provider.initialize(new ImmutableContext()); evaluate(provider) }
          elapsed <- ZIO.attemptBlocking(provider.shutdown()).timed.map(_._1)
          entered = handler.entered.getCount == 0L
          interrupted <- ZIO.attemptBlocking(handler.interrupted.await(5L, TimeUnit.SECONDS))
        } yield assertTrue(
          entered,
          // the provider's ShutdownTimeout
          elapsed < 10.seconds,
          // the provider shuts the processor's executor down, which interrupts the stuck dispatch
          interrupted
        )
      }
    },
    test("each Batched bound is validated") {
      val second = java.time.Duration.ofSeconds(1)
      for {
        batchSize     <- rejected(OptimizelyEvents.Batched(batchSize = 0))
        flushZero     <- rejected(OptimizelyEvents.Batched(flushInterval = java.time.Duration.ZERO))
        flushNegative <- rejected(OptimizelyEvents.Batched(flushInterval = second.negated()))
        closeZero     <- rejected(OptimizelyEvents.Batched(closeTimeout = java.time.Duration.ZERO))
        closeTooLong  <- rejected(OptimizelyEvents.Batched(closeTimeout = java.time.Duration.ofSeconds(5)))
        closeJustOver <- rejected(OptimizelyEvents.Batched(closeTimeout = java.time.Duration.ofMillis(4001)))
        flushNull     <- rejected(OptimizelyEvents.Batched(flushInterval = null))
        atBounds <- OptimizelyProvider
          .validateEvents(
            OptimizelyEvents.Batched(1, java.time.Duration.ofMillis(1), java.time.Duration.ofSeconds(4))
          )
          .either
        disabled <- OptimizelyProvider.validateEvents(OptimizelyEvents.Disabled).either
      } yield assertTrue(
        batchSize.exists(_.contains("events.batchSize")),
        flushZero.exists(_.contains("events.flushInterval")),
        flushNegative.exists(_.contains("events.flushInterval")),
        closeZero.exists(_.contains("events.closeTimeout")),
        closeTooLong.exists(_.contains("events.closeTimeout must be at most")),
        closeJustOver.exists(_.contains("events.closeTimeout must be at most")),
        flushNull.exists(_.contains("events.flushInterval is null")),
        atBounds.isRight,
        disabled.isRight
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
}
