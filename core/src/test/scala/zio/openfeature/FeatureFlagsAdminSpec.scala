package zio.openfeature

import zio._
import zio.test._
import zio.test.Assertion.{anything, dies, isSubtype}
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, Value}

/** [[FeatureFlagsAdmin]] administers the [[FeatureFlags]] a factory built: the factories' layers provide both, for the
  * same instance.
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
    test("FeatureFlagsAdmin administers the instance the factory built") {
      val program =
        for {
          before <- FeatureFlags.boolean("f", default = false)
          _      <- FeatureFlagsAdmin.setProvider(new Fixed("second", value = false))
          after  <- FeatureFlags.boolean("f", default = true)
          _      <- FeatureFlagsAdmin.shutdown
          status <- FeatureFlags.providerStatus
        } yield assertTrue(before, !after, status == ProviderStatus.NotReady)
      program.provide(FeatureFlags.fromProvider(new Fixed("first", value = true), config))
    },
    test("a factory's layer provides FeatureFlagsAdmin, the same instance as its FeatureFlags") {
      ZIO.scoped {
        FeatureFlags.fromProvider(new Fixed("p", value = true), config).build.map { env =>
          assertTrue(env.get[FeatureFlagsAdmin].asInstanceOf[AnyRef] eq env.get[FeatureFlags])
        }
      }
    },
    test("FeatureFlagsAdmin.layer over a built instance returns that instance") {
      ZIO.scoped {
        for {
          env <- FeatureFlags.fromProvider(new Fixed("p", value = true), config).build
          ff = env.get[FeatureFlags]
          admin <- (ZLayer.succeed[FeatureFlags](ff) >>> FeatureFlagsAdmin.layer).build.map(_.get[FeatureFlagsAdmin])
        } yield assertTrue(admin.asInstanceOf[AnyRef] eq ff)
      }
    },
    test("FeatureFlagsAdmin.layer over a hand-written FeatureFlags dies") {
      ZIO
        .scoped((ZLayer.succeed[FeatureFlags](new Stub) >>> FeatureFlagsAdmin.layer).build)
        .exit
        .map(exit => assert(exit)(dies(isSubtype[IllegalArgumentException](anything))))
    },
    test("a program that needs only FeatureFlags cannot reach FeatureFlagsAdmin") {
      typeCheck("""
        val reader: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.boolean("f", default = false)
        val admin: ZIO[FeatureFlags, Nothing, Unit]               = FeatureFlagsAdmin.shutdown
      """).map(result => assertTrue(result.isLeft))
    }
  ) @@ withLiveClock

  // A FeatureFlags that is not an administrable instance; no member is reached.
  final private class Stub extends FeatureFlags {
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
    ): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]]                                     = ???
    override def inTransaction: UIO[Boolean]                                                         = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                          = ???
    override def events: zio.stream.ZStream[Any, Nothing, ProviderEvent]                             = ???
    override def providerStatus: UIO[ProviderStatus]                                                 = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                   = ???
    override def providerMetadata: UIO[ProviderMetadata]                                             = ???
    override def clientMetadata: UIO[ClientMetadata]                                                 = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                               = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                       = ???
    override def clearHooks: UIO[Unit]                                                               = ???
    override def hooks: UIO[List[FeatureHook]]                                                       = ???
    override def track(eventName: String): IO[FeatureFlagError, Unit]                                = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]    = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit] = ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError, Unit] =
      ???
  }
}
