package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.event.{EventHandler, ForwardingEventProcessor, LogEvent}
import dev.openfeature.sdk.{ImmutableContext, MutableTrackingEventDetails, Value}
import dev.openfeature.sdk.exceptions.{GeneralError, ProviderNotReadyError, TargetingKeyMissingError}
import zio._
import zio.openfeature.{EvaluationContext, FeatureFlagError, FeatureFlags, FeatureFlagsConfig, TrackingEventDetails}
import zio.test._

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

// OptimizelyFeatureProvider sends `track` calls to Optimizely as conversions instead of dropping them.
object OptimizelyTrackSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/track-key.json"

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  final private class Recording extends EventHandler {
    val events                                    = new CopyOnWriteArrayList[LogEvent]()
    override def dispatchEvent(e: LogEvent): Unit = { events.add(e); () }
    def bodies: List[String]                      = events.asScala.toList.map(_.getBody)
  }

  private def withProvider[A](initialize: Boolean = true)(body: (OptimizelyFeatureProvider, Recording) => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try {
      val events = new Recording
      val client = OptimizelyTestSupport.readyClient(
        server,
        DatafilePath,
        readResource("/datafiles/track.json"),
        "track-key",
        customize = _.withEventProcessor(new ForwardingEventProcessor(events, null))
      )
      val provider = new OptimizelyFeatureProvider(client, OptimizelyTestSupport.InitWait, closeOnShutdown = true)
      if (initialize) provider.initialize(new ImmutableContext())
      try body(provider, events)
      finally provider.shutdown()
    } finally server.stop()
  }

  private def user(attrs: (String, String)*): ImmutableContext =
    new ImmutableContext("user-1", attrs.map { case (k, v) => k -> new Value(v) }.toMap.asJava)

  def spec = suite("OptimizelyTrackSpec")(
    test("a track call is sent as a conversion with the user, attributes, value, revenue and tags") {
      withProvider() { (provider, events) =>
        val details = new MutableTrackingEventDetails(12.5).add("revenue", Integer.valueOf(1999)).add("sku", "x")
        provider.track("purchase", user("plan" -> "pro"), details)
        val body = events.bodies.mkString
        assertTrue(
          events.events.size == 1,
          body.contains("\"user-1\""),
          body.contains("\"purchase\""),
          body.contains("\"pro\""),
          body.contains("\"sku\":\"x\""),
          body.contains("\"revenue\":1999"),
          body.contains("\"value\":12.5")
        )
      }
    },
    test("a track call without details sends a conversion with no tags") {
      withProvider() { (provider, events) =>
        provider.track("purchase", user(), null)
        assertTrue(events.events.size == 1, events.bodies.head.contains("\"purchase\""))
      }
    },
    test("a context without a targeting key fails and sends nothing") {
      withProvider() { (provider, events) =>
        val thrown = scala.util.Try(provider.track("purchase", new ImmutableContext(), null)).failed.toOption
        assertTrue(thrown.exists(_.isInstanceOf[TargetingKeyMissingError]), events.events.isEmpty)
      }
    },
    test("a provider that is not ready fails and sends nothing") {
      withProvider(initialize = false) { (provider, events) =>
        val thrown = scala.util.Try(provider.track("purchase", user(), null)).failed.toOption
        assertTrue(thrown.exists(_.isInstanceOf[ProviderNotReadyError]), events.events.isEmpty)
      }
    },
    test("an event key the datafile does not define fails and sends nothing") {
      withProvider() { (provider, events) =>
        val thrown = scala.util.Try(provider.track("purchsae", user(), null)).failed.toOption
        assertTrue(thrown.exists(_.isInstanceOf[GeneralError]), events.events.isEmpty)
      }
    },
    test("FeatureFlags.track reaches Optimizely, and an unknown event fails the effect") {
      withProvider() { (provider, events) =>
        val program = for {
          ok  <- FeatureFlags.track("purchase", EvaluationContext("user-1"), TrackingEventDetails(5.0)).either
          bad <- FeatureFlags.track("nope", EvaluationContext("user-1")).either
        } yield (ok, bad)
        val (ok, bad) = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe
            .run(
              program.provide(
                FeatureFlags.fromProvider(
                  provider,
                  FeatureFlagsConfig(),
                  statusRef = None,
                  apiOverride = Some(dev.openfeature.sdk.OpenFeatureAPI.createIsolated())
                )
              )
            )
            .getOrThrowFiberFailure()
        }
        assertTrue(ok.isRight, bad.left.toOption.exists(_.isInstanceOf[FeatureFlagError]), events.events.size == 1)
      }
    }
  ) @@ TestAspect.sequential
}
