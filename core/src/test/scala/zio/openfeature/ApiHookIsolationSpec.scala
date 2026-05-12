package zio.openfeature

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

object ApiHookIsolationSpec extends ZIOSpecDefault {

  private class SimpleBooleanProvider(name: String, value: Boolean) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] =
      ProviderEvaluation.builder[java.lang.Boolean]().value(value).reason("STATIC").build()

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      ProviderEvaluation.builder[String]().value(defaultValue).reason("STATIC").build()

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] =
      ProviderEvaluation.builder[java.lang.Integer]().value(defaultValue).reason("STATIC").build()

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] =
      ProviderEvaluation.builder[java.lang.Double]().value(defaultValue).reason("STATIC").build()

    override def getObjectEvaluation(
      key: String,
      defaultValue: dev.openfeature.sdk.Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[dev.openfeature.sdk.Value] =
      ProviderEvaluation.builder[dev.openfeature.sdk.Value]().value(defaultValue).reason("STATIC").build()
  }

  // Counts before-hook invocations from the Java SDK thread.
  private class CountingJavaHook extends dev.openfeature.sdk.Hook[java.lang.Boolean] {
    val count = new AtomicInteger(0)
    override def before(
      ctx: JavaHookContext[java.lang.Boolean],
      hints: java.util.Map[String, AnyRef]
    ): java.util.Optional[OFEvaluationContext] = {
      count.incrementAndGet()
      java.util.Optional.empty()
    }
  }

  private def buildIsolated(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"api-hook-iso-${java.util.UUID.randomUUID()}"
    for {
      ff <- FeatureFlags.build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false,
        apiOverride = Some(api)
      )
      _ <- ZIO.attemptBlocking(Thread.sleep(50)).ignore
    } yield ff
  }

  def spec = suite("API Hook Isolation")(
    test("addApiHook installs the hook on this FeatureFlags' isolated API") {
      ZIO.scoped {
        for {
          ff <- buildIsolated(new SimpleBooleanProvider("p", true))
          hook = new CountingJavaHook
          _ <- ff.addApiHook(hook)
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
        } yield assertTrue(hook.count.get() == 1)
      }
    },
    test("addApiHook on one isolated FeatureFlags does not leak to another") {
      ZIO.scoped {
        for {
          ffA <- buildIsolated(new SimpleBooleanProvider("pA", true))
          ffB <- buildIsolated(new SimpleBooleanProvider("pB", true))
          hookA = new CountingJavaHook
          _ <- ffA.addApiHook(hookA)
          _ <- ffA.boolean("flag", default = false).provideEnvironment(ZEnvironment(ffA))
          _ <- ffB.boolean("flag", default = false).provideEnvironment(ZEnvironment(ffB))
        } yield assertTrue(hookA.count.get() == 1)
      }
    },
    test("clearApiHooks removes previously-installed API hooks") {
      ZIO.scoped {
        for {
          ff <- buildIsolated(new SimpleBooleanProvider("p", true))
          hook = new CountingJavaHook
          _ <- ff.addApiHook(hook)
          _ <- ff.clearApiHooks
          _ <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
        } yield assertTrue(hook.count.get() == 0)
      }
    }
  )
}
