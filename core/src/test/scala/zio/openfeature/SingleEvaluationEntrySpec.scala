package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicReference

/** `valueDetails` is the only evaluation method an implementor writes; every other evaluation method on the trait is
  * defined through it, and the per-type ones are final aliases. Braces only: this spec compiles on 2.13 and 3.
  */
object SingleEvaluationEntrySpec extends ZIOSpecDefault {

  /** Answers every evaluation with the caller's default and records the key and `FlagType` it was asked for. */
  final private class RecordingFlags extends FeatureFlags {
    val seen = new AtomicReference[List[(String, String)]](Nil)

    override def valueDetails[A: FlagType](
      k: String,
      d: A,
      ctx: EvaluationContext,
      o: EvaluationOptions
    ): IO[FeatureFlagError.EvaluationError, FlagResolution[A]] = {
      seen.updateAndGet(prev => (k, FlagType[A].typeName) :: prev)
      ZIO.succeed(FlagResolution.targetingMatch(k, d))
    }

    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def globalContext: UIO[EvaluationContext]                                         = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def clientContext: UIO[EvaluationContext]                                         = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError.TransactionError], TransactionResult[A]]                                 = ???
    override def inTransaction: UIO[Boolean]                                                                      = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                                       = ???
    override def events: zio.stream.ZStream[Any, Nothing, ProviderEvent]                                          = ???
    override def providerStatus: UIO[ProviderStatus]                                                              = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                                = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                          = ???
    override def clientMetadata: UIO[ClientMetadata]                                                              = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                            = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                                    = ???
    override def clearHooks: UIO[Unit]                                                                            = ???
    override def hooks: UIO[List[FeatureHook]]                                                                    = ???
    override def track(eventName: String): IO[FeatureFlagError.EvaluationError, Unit]                             = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError.EvaluationError, Unit] = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
  }

  /** `true` for booleans, 123 for ints and 9000000000 for longs, all as targeting matches; the rest echo the default.
    */
  private class ScalarProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Scalar" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "TARGETING_MATCH")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](Int.box(123), "TARGETING_MATCH")
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(9000000000L), "TARGETING_MATCH")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      provider,
      domain = Some(s"single-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  private val ctx = EvaluationContext.empty

  def spec = suite("SingleEvaluationEntrySpec")(
    test("an implementor that defines only valueDetails answers every evaluation method through it") {
      val ff    = new RecordingFlags
      val fixed = Map[String, Any]("k" -> "v")
      for {
        b       <- ff.boolean("b", true)
        s       <- ff.string("s", "str")
        i       <- ff.int("i", 7)
        l       <- ff.long("l", 8L)
        d       <- ff.double("d", 2.5)
        o       <- ff.obj("o", fixed)
        v       <- ff.value[Int]("v", 9)
        bd      <- ff.booleanDetails("bd", false)
        sd      <- ff.stringDetails("sd", "x")
        id      <- ff.intDetails("id", 1)
        ld      <- ff.longDetails("ld", 2L)
        dd      <- ff.doubleDetails("dd", 3.5)
        od      <- ff.objDetails("od", fixed)
        vd      <- ff.valueDetails("vd", "y")
        bo      <- ff.booleanOrDefault("bo", true)
        ro      <- ff.resolveOrDefault[Long]("ro", 5L)
        fromOpt <- ff.intOrDefault("io", 4, ctx, EvaluationOptions.empty)
      } yield assertTrue(
        b,
        s == "str",
        i == 7,
        l == 8L,
        d == 2.5,
        o == fixed,
        v == 9,
        bd.value == false,
        sd.value == "x",
        id.value == 1,
        ld.value == 2L,
        dd.value == 3.5,
        od.value == fixed,
        vd.value == "y",
        vd.reason == ResolutionReason.TargetingMatch,
        bo,
        ro.value == 5L,
        ro.reason == ResolutionReason.TargetingMatch,
        fromOpt == 4,
        ff.seen.get.reverse == List(
          ("b", "Boolean"),
          ("s", "String"),
          ("i", "Int"),
          ("l", "Long"),
          ("d", "Double"),
          ("o", "Object"),
          ("v", "Int"),
          ("bd", "Boolean"),
          ("sd", "String"),
          ("id", "Int"),
          ("ld", "Long"),
          ("dd", "Double"),
          ("od", "Object"),
          ("vd", "String"),
          ("bo", "Boolean"),
          ("ro", "Long"),
          ("io", "Int")
        )
      )
    },
    test("boolean and booleanOrDefault take every arity of context and options, and the options' hook runs") {
      ZIO.scoped {
        for {
          ff    <- buildFF(new ScalarProvider)
          calls <- Ref.make(0)
          hook = new FeatureHook {
            override def before(c: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              calls.update(_ + 1).as(None)
          }
          opts    = EvaluationOptions(hook)
          withCtx = EvaluationContext.empty.withTargetingKey("u")
          b1       <- ff.boolean("f", false)
          b2       <- ff.boolean("f", false, withCtx)
          afterTwo <- calls.get
          b3       <- ff.boolean("f", false, withCtx, opts)
          b4       <- ff.boolean("f", false, options = opts)
          o1       <- ff.booleanOrDefault("f", false)
          o2       <- ff.booleanOrDefault("f", false, withCtx)
          o3       <- ff.booleanOrDefault("f", false, withCtx, opts)
          o4       <- ff.booleanOrDefault("f", false, options = opts)
          total    <- calls.get
        } yield assertTrue(b1, b2, b3, b4, o1, o2, o3, o4, afterTwo == 0, total == 4)
      }
    },
    test("Int and Long resolve the same through the per-type details and valueDetails") {
      ZIO.scoped {
        for {
          ff <- buildFF(new ScalarProvider)
          i1 <- ff.intDetails("n", 1)
          i2 <- ff.valueDetails("n", 1)
          l1 <- ff.longDetails("n", 1L)
          l2 <- ff.valueDetails("n", 1L)
        } yield assertTrue(
          i1.value == 123,
          i2.value == 123,
          i1.reason == ResolutionReason.TargetingMatch,
          i2.reason == ResolutionReason.TargetingMatch,
          l1.value == 9000000000L,
          l2.value == 9000000000L,
          l1.reason == ResolutionReason.TargetingMatch,
          l2.reason == ResolutionReason.TargetingMatch
        )
      }
    }
  )
}
