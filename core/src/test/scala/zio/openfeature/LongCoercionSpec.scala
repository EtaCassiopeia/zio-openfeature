package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}
import java.util.concurrent.atomic.AtomicReference

/** Spec §3.1.2 / §1.3.4: int-range `Long`s must not be silently coerced to `Double`. A small long in the context
  * reaches providers as an `Integer` (so `instanceof Integer` targeting rules match).
  *
  * Flag *evaluation* changed with SDK 1.22.0 (#333). `ff.long` now calls the native `client.getLongDetails`, so the
  * provider's own `getLongEvaluation` decides the result instead of this library choosing a resolver for it:
  *   - a provider that overrides it resolves the full 64-bit range **exactly** (was: silently lossy past 2^53);
  *   - one that does not inherits the SDK's double-backed default, which answers from `getDoubleEvaluation` and returns
  *     a loud `TYPE_MISMATCH` outside ±(2^53−1) rather than a quietly wrong number.
  * Every provider this library ships overrides it. A third-party provider that does not can be wrapped in `extras`'
  * `IntegerWideningLongProvider` to restore the old int-range routing — covered by that module's spec, since `core`
  * cannot depend on `extras`.
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

  /** Overrides `getLongEvaluation` natively, as every provider this library ships now does. Returns a value beyond 2^53
    * so an exact result is distinguishable from anything that went through a Double.
    */
  private class NativeLongProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "NativeLong" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(NativeLongProvider.Exact), "STATIC")
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

  private object NativeLongProvider {

    /** Not representable exactly as a Double: round-tripping it through one yields 9007199254740993 -> ...92. */
    val Exact: Long = 9007199254740993L
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
    val api = OpenFeatureAPI.createIsolated()
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
    test("a provider with a native long resolver resolves beyond 2^53 exactly") {
      ZIO.scoped {
        for {
          ff <- buildFF(new NativeLongProvider)
          v  <- ff.long("flag", default = 0L)
        } yield assertTrue(
          v == NativeLongProvider.Exact,
          // The point of going native: this value is not representable as a Double, so the old
          // route through getDoubleDetails could never have returned it.
          v.toDouble.toLong != NativeLongProvider.Exact
        )
      }
    },
    test("a provider without a long resolver falls to the SDK's double-backed default") {
      ZIO.scoped {
        for {
          ff <- buildFF(new ResolverProvider)
          v  <- ff.long("flag", default = 0L)
        } yield assertTrue(v == 99L) // 99 from the double resolver, via the SDK's default getLongEvaluation
      }
    },
    test("an out-of-safe-range default against a resolver-less provider is a loud TYPE_MISMATCH, not a wrong number") {
      ZIO.scoped {
        for {
          ff    <- buildFF(new ResolverProvider)
          det   <- ff.longDetails("flag", default = Long.MaxValue).either
          total <- ff.resolveOrDefault[Long]("flag", default = Long.MaxValue)
        } yield assertTrue(
          // The SDK refuses to answer rather than silently truncating through a Double, and the typed tier surfaces
          // that refusal as a typed failure (#388)...
          det.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]),
          // ...while the total tier still hands the caller's own default back untouched, with the code.
          total.errorCode.contains(ErrorCode.TypeMismatch),
          total.value == Long.MaxValue
        )
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
    },
    test("an out-of-int-range Long tracking attribute is sent as a Long, not a lossy Double") {
      val seen = new AtomicReference[String]("unset")
      ZIO.scoped {
        for {
          ff <- buildFF(new TrackInspectingProvider(seen))
          _  <- ff.track("purchase", TrackingEventDetails(Map("id" -> Long.MaxValue)))
        } yield assertTrue(seen.get() == "Long")
      }
    }
  ) @@ sequential @@ withLiveClock
}
