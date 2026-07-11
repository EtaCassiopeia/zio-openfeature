package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}
import java.util.concurrent.atomic.AtomicReference

/** Spec §3.1.2 / §1.3.4: int-range `Long`s must not be silently coerced to `Double`. A small long in the context
  * reaches providers as an `Integer` (so `instanceof Integer` targeting rules match), and a long-typed flag evaluation
  * routes through the provider's integer resolver rather than its double resolver.
  */
object LongCoercionSpec extends ZIOSpecDefault {

  /** Records the runtime class of the `"id"` context attribute as the provider actually receives it. */
  private class InspectingProvider(seen: AtomicReference[String]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Inspecting" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      val v = c.getValue("id")
      seen.set(if (v == null) "absent" else v.asObject().getClass.getSimpleName)
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Returns distinct values from the integer vs double resolver, so we can tell which one `ff.long` dispatched to. */
  private class ResolverProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Resolver" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](Integer.valueOf(7), "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](java.lang.Double.valueOf(99.0), "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Records the runtime class of the `"id"` attribute as it arrives in the tracking details. */
  private class TrackInspectingProvider(seen: AtomicReference[String]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "TrackInspecting" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def track(
      eventName: String,
      ctx: OFEvaluationContext,
      details: OFTrackingEventDetails
    ): Unit = {
      val v = details.getValue("id")
      seen.set(if (v == null) "absent" else v.asObject().getClass.getSimpleName)
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPIFactory.create()
    FeatureFlags.build(
      provider,
      domain = Some(s"long-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("LongCoercionSpec")(
    test("an int-range Long context attribute reaches the provider as an Integer, not a Double") {
      val seen = new AtomicReference[String]("unset")
      ZIO.scoped {
        for {
          ff <- buildFF(new InspectingProvider(seen))
          ctx = EvaluationContext("user").withAttribute("id", AttributeValue.LongValue(42L))
          _ <- ff.boolean("flag", default = false, ctx)
        } yield assertTrue(seen.get() == "Integer")
      }
    },
    test("an out-of-int-range Long context attribute still reaches the provider as a Double") {
      val seen = new AtomicReference[String]("unset")
      ZIO.scoped {
        for {
          ff <- buildFF(new InspectingProvider(seen))
          ctx = EvaluationContext("user").withAttribute("id", AttributeValue.LongValue(Long.MaxValue))
          _ <- ff.boolean("flag", default = false, ctx)
        } yield assertTrue(seen.get() == "Double")
      }
    },
    test("an int-range long flag evaluation routes through the provider's integer resolver") {
      ZIO.scoped {
        for {
          ff <- buildFF(new ResolverProvider)
          v  <- ff.long("flag", default = 0L)
        } yield assertTrue(v == 7L) // 7 from the integer resolver, not 99 from the double resolver
      }
    },
    test("an out-of-int-range long flag evaluation still routes through the double resolver") {
      ZIO.scoped {
        for {
          ff <- buildFF(new ResolverProvider)
          v  <- ff.long("flag", default = Long.MaxValue)
        } yield assertTrue(v == 99L) // 99 from the double resolver
      }
    },
    test("an int-range Long tracking attribute is sent as an Integer, not a Double") {
      val seen = new AtomicReference[String]("unset")
      ZIO.scoped {
        for {
          ff <- buildFF(new TrackInspectingProvider(seen))
          _  <- ff.track("purchase", TrackingEventDetails(Map("id" -> 42L)))
        } yield assertTrue(seen.get() == "Integer")
      }
    }
  ) @@ sequential @@ withLiveClock
}
