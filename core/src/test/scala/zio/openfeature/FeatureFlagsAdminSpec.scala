package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, Value}

/** [[FeatureFlagsAdmin]] administers the [[FeatureFlags]] a factory built, added with `>+> FeatureFlagsAdmin.layer`;
  * the factories' own layer types are unchanged.
  *
  * Shared (cross-compiled) test source dir: braces only.
  */
object FeatureFlagsAdminSpec extends ZIOSpecDefault {

  // Answers `value` for every boolean.
  private class Fixed(name: String, value: Boolean) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.valueOf(value), "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  private val config = FeatureFlagsConfig().withIsolatedApi

  def spec = suite("FeatureFlagsAdminSpec")(
    test("FeatureFlagsAdmin.layer administers the instance the factory built") {
      val program =
        for {
          before <- FeatureFlags.boolean("f", default = false)
          _      <- FeatureFlagsAdmin.setProvider(new Fixed("second", value = false))
          after  <- FeatureFlags.boolean("f", default = true)
          _      <- FeatureFlagsAdmin.shutdown
          status <- FeatureFlags.providerStatus
        } yield assertTrue(before, !after, status == ProviderStatus.NotReady)
      program.provide(FeatureFlags.fromProvider(new Fixed("first", value = true), config) >+> FeatureFlagsAdmin.layer)
    },
    test("the admin of a built instance is that instance") {
      ZIO.scoped {
        FeatureFlags.fromProvider(new Fixed("p", value = true), config).build.map { env =>
          val ff = env.get[FeatureFlags]
          assertTrue(FeatureFlagsAdmin.of(ff).asInstanceOf[AnyRef] eq ff)
        }
      }
    },
    test("an implementor that is not one gets an adapter over the members FeatureFlags keeps for 1.x") {
      val stub     = new RecordingFlags
      val admin    = FeatureFlagsAdmin.of(stub)
      val provider = new Fixed("swapped", value = false)
      for {
        _ <- admin.setProvider(provider)
        _ <- admin.shutdown
      } yield assertTrue(
        stub.installed.get() eq provider,
        stub.shutdowns.get() == 1,
        !(admin.asInstanceOf[AnyRef] eq stub)
      )
    },
    test("a factory's layer still has its 1.2 type: build.map(_.get) needs no type argument") {
      ZIO.scoped {
        // The intersection type an earlier design gave these layers made `_.get` fail to infer a Tag.
        FeatureFlags.fromProvider(new Fixed("p", value = true), config).build.map(_.get).flatMap { ff =>
          ff.boolean("f", default = false).map(read => assertTrue(read))
        }
      }
    },
    test("a program that needs only FeatureFlags cannot reach FeatureFlagsAdmin") {
      typeCheck("""
        val reader: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.boolean("f", default = false)
        val admin: ZIO[FeatureFlags, Nothing, Unit]               = FeatureFlagsAdmin.shutdown
      """).map(result => assertTrue(result.isLeft))
    }
  ) @@ withLiveClock

  // Records the two admin members the adapter test calls; nothing else is reached.
  @scala.annotation.nowarn("cat=deprecation")
  final private class RecordingFlags extends FeatureFlags {
    val installed = new java.util.concurrent.atomic.AtomicReference[FeatureProvider](null)
    val shutdowns = new java.util.concurrent.atomic.AtomicInteger(0)

    override def setProvider(p: FeatureProvider): IO[FeatureFlagError, Unit] = ZIO.succeed(installed.set(p))
    override def shutdown: UIO[Unit] = ZIO.succeed(shutdowns.incrementAndGet()).unit

    override def booleanDetails(k: String, d: Boolean, ctx: EvaluationContext, o: EvaluationOptions)      = ???
    override def stringDetails(k: String, d: String, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def intDetails(k: String, d: Int, ctx: EvaluationContext, o: EvaluationOptions)              = ???
    override def longDetails(k: String, d: Long, ctx: EvaluationContext, o: EvaluationOptions)            = ???
    override def doubleDetails(k: String, d: Double, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def objDetails(k: String, d: Map[String, Any], ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def globalContext: UIO[EvaluationContext]                                                    = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def clientContext: UIO[EvaluationContext]                                                    = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]            = ???
    override def transaction[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, _root_.zio.openfeature.internal.Compat.OrError[E, FeatureFlagError], TransactionResult[A]] = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]]                                         = ???
    override def inTransaction: UIO[Boolean]                                                             = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                              = ???
    override def events: zio.stream.ZStream[Any, Nothing, ProviderEvent]                                 = ???
    override def providerStatus: UIO[ProviderStatus]                                                     = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                       = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                 = ???
    override def clientMetadata: UIO[ClientMetadata]                                                     = ???
    override def onProviderReady(h: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]                       = ???
    override def onProviderError(h: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]          = ???
    override def onProviderStale(h: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]             = ???
    override def onConfigurationChanged(h: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = ???
    override def on(t: ProviderEventType, h: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]                 = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                   = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                           = ???
    override def clearHooks: UIO[Unit]                                                                   = ???
    override def hooks: UIO[List[FeatureHook]]                                                           = ???
    override def addZioApiHook(hook: FeatureHook): UIO[Unit]                                             = ???
    override def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]                                     = ???
    override def clearZioApiHooks: UIO[Unit]                                                             = ???
    override def zioApiHooks: UIO[List[FeatureHook]]                                                     = ???
    override def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]                                = ???
    override def clearApiHooks: UIO[Unit]                                                                = ???
    override def track(eventName: String): IO[FeatureFlagError, Unit]                                    = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]        = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]     = ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError, Unit] =
      ???
  }
}
