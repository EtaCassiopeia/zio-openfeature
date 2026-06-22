package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  HookContext => JavaHookContext,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState
}
import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicInteger

object ProviderHookDuplicationSpec extends ZIOSpecDefault {

  private class ProviderWithCountingHook(name: String, value: Boolean, counter: AtomicInteger) extends EventProvider {

    private val providerHook = new dev.openfeature.sdk.Hook[java.lang.Boolean] {
      override def before(
        ctx: JavaHookContext[java.lang.Boolean],
        hints: java.util.Map[String, AnyRef]
      ): java.util.Optional[OFEvaluationContext] = {
        counter.incrementAndGet()
        java.util.Optional.empty()
      }
    }

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getProviderHooks(): java.util.List[dev.openfeature.sdk.Hook[_]] =
      java.util.Collections.singletonList(providerHook)

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] =
      ProviderEvaluations.of[java.lang.Boolean](value, "STATIC")

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      ProviderEvaluations.of[String](defaultValue, "STATIC")

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] =
      ProviderEvaluations.of[java.lang.Integer](defaultValue, "STATIC")

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] =
      ProviderEvaluations.of[java.lang.Double](defaultValue, "STATIC")

    override def getObjectEvaluation(
      key: String,
      defaultValue: dev.openfeature.sdk.Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[dev.openfeature.sdk.Value] =
      ProviderEvaluations.of[dev.openfeature.sdk.Value](defaultValue, "STATIC")
  }

  def spec = suite("Provider hook duplication")(
    test("provider hook runs exactly once per evaluation (not duplicated by ZIO pipeline)") {
      ZIO.scoped {
        val counter = new AtomicInteger(0)
        val domain  = s"prov-hook-${java.util.UUID.randomUUID()}"
        val api     = OpenFeatureAPIFactory.create()
        for {
          ff <- FeatureFlags.build(
            new ProviderWithCountingHook("p", value = true, counter),
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
        } yield assertTrue(counter.get() == 1)
      }
    },
    test("provider hook count stays at 1 across multiple evaluations") {
      ZIO.scoped {
        val counter = new AtomicInteger(0)
        val domain  = s"prov-hook-multi-${java.util.UUID.randomUUID()}"
        val api     = OpenFeatureAPIFactory.create()
        for {
          ff <- FeatureFlags.build(
            new ProviderWithCountingHook("p", value = true, counter),
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
        } yield assertTrue(counter.get() == 3)
      }
    }
  )
}
