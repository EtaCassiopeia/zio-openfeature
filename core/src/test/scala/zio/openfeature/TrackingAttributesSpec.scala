package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._

// #441: tracking attributes are converted to OpenFeature values by type — AttributeValue included — and an unsupported
// type fails `track` instead of being sent as its toString.
object TrackingAttributesSpec extends ZIOSpecDefault {

  private class CapturingProvider extends EventProvider {
    val captured = new AtomicReference[Option[OFTrackingEventDetails]](None)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "capturing" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit =
      captured.set(Some(details))
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"tracking-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def trackAndCapture(details: TrackingEventDetails): ZIO[Scope, Any, OFTrackingEventDetails] =
    for {
      provider <- ZIO.succeed(new CapturingProvider)
      ff       <- buildFF(provider)
      _        <- ff.track("purchase", details)
      captured <- ZIO.fromOption(provider.captured.get())
    } yield captured

  final private case class NotAnAttribute(n: Int)

  def spec = suite("TrackingAttributesSpec (#441)")(
    test("an AttributeValue string arrives as the string, not its toString") {
      ZIO.scoped(trackAndCapture(TrackingEventDetails.empty.withAttribute("plan", AttributeValue.string("pro")))).map {
        d => assertTrue(d.getValue("plan").asString == "pro")
      }
    },
    test("numeric, boolean and instant AttributeValues arrive with the matching kind") {
      val now = java.time.Instant.parse("2026-10-23T12:00:00Z")
      val details = TrackingEventDetails(
        Map(
          "i"   -> AttributeValue.IntValue(3),
          "big" -> AttributeValue.LongValue(Long.MaxValue),
          "d"   -> AttributeValue.DoubleValue(1.5),
          "b"   -> AttributeValue.BoolValue(true),
          "t"   -> AttributeValue.InstantValue(now)
        )
      )
      ZIO.scoped(trackAndCapture(details)).map { d =>
        assertTrue(
          d.getValue("i").asInteger == 3,
          d.getValue("big").asObject == java.lang.Long.valueOf(Long.MaxValue),
          d.getValue("d").asDouble == 1.5,
          d.getValue("b").asBoolean == true,
          d.getValue("t").asInstant == now
        )
      }
    },
    test("a nested StructValue arrives as a structure with typed members") {
      val details = TrackingEventDetails(
        Map(
          "cart" -> AttributeValue.StructValue(
            Map(
              "a" -> AttributeValue.IntValue(1),
              "b" -> AttributeValue.ListValue(List(AttributeValue.StringValue("x")))
            )
          )
        )
      )
      ZIO.scoped(trackAndCapture(details)).map { d =>
        val cart = d.getValue("cart").asStructure
        assertTrue(
          cart.getValue("a").asInteger == 1,
          cart.getValue("b").asList.asScala.map(_.asString).toList == List("x")
        )
      }
    },
    test("plain Scala collections holding AttributeValues and Options convert their members") {
      val details = TrackingEventDetails(
        Map(
          "tags" -> List(AttributeValue.StringValue("a"), Some("b")),
          "meta" -> Map("n" -> AttributeValue.IntValue(2), "o" -> Some(true))
        )
      )
      ZIO.scoped(trackAndCapture(details)).map { d =>
        val meta = d.getValue("meta").asStructure
        assertTrue(
          d.getValue("tags").asList.asScala.map(_.asString).toList == List("a", "b"),
          meta.getValue("n").asInteger == 2,
          meta.getValue("o").asBoolean == true
        )
      }
    },
    test("an unsupported attribute type fails track with InvalidContext and nothing is sent or recorded") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new CapturingProvider)
          ff       <- buildFF(provider)
          result   <- ff.track("purchase", TrackingEventDetails(Map("odd" -> NotAnAttribute(1)))).either
          recorded <- ff.trackedEvents
        } yield assertTrue(
          result.left.toOption.exists {
            case FeatureFlagError.InvalidContext(reason) => reason.contains("odd")
            case _                                       => false
          },
          provider.captured.get().isEmpty,
          recorded.isEmpty
        )
      }
    },
    test("null and None list elements keep their positions") {
      ZIO.scoped(trackAndCapture(TrackingEventDetails(Map("xs" -> List("a", null, None, "d"))))).map { d =>
        val xs = d.getValue("xs").asList.asScala.toList
        assertTrue(xs.size == 4, xs.head.asString == "a", xs(1).isNull, xs(2).isNull, xs(3).asString == "d")
      }
    },
    test("an unsupported value nested in a collection fails track and names the attribute") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new CapturingProvider)
          ff       <- buildFF(provider)
          nested   <- ff.track("p", TrackingEventDetails(Map("deep" -> Map("k" -> List(NotAnAttribute(1)))))).either
          badKey   <- ff.track("p", TrackingEventDetails(Map("keys" -> Map(1 -> "one")))).either
        } yield assertTrue(
          nested.left.toOption.exists(_.message.contains("deep")),
          badKey.left.toOption.exists(_.message.contains("keys")),
          provider.captured.get().isEmpty
        )
      }
    },
    test("Float, Some, None and an SDK Value at the top level convert") {
      val details = TrackingEventDetails(
        Map("f" -> 1.5f, "some" -> Some(2), "none" -> None, "raw" -> new Value("v"))
      )
      ZIO.scoped(trackAndCapture(details)).map { d =>
        assertTrue(
          d.getValue("f").asDouble == 1.5,
          d.getValue("some").asInteger == 2,
          d.getValue("none") == null,
          d.getValue("raw").asString == "v"
        )
      }
    },
    test("a null attribute is dropped and the others are still sent") {
      ZIO.scoped(trackAndCapture(TrackingEventDetails(Map("gone" -> null, "kept" -> "yes")))).map { d =>
        assertTrue(d.getValue("gone") == null, d.getValue("kept").asString == "yes")
      }
    }
  )
}
