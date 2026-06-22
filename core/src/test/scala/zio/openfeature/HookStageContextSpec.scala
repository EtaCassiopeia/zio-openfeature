package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState
}
import dev.openfeature.sdk.exceptions.ProviderNotReadyError
import zio._
import zio.test._

/** Verifies spec §4.3.5-4.3.8: the after/error/finally hook stages observe the evaluation context as modified by the
  * before hooks, not the original invocation context.
  */
object HookStageContextSpec extends ZIOSpecDefault {

  private class StaticBooleanProvider(name: String, value: Boolean, failKeys: Set[String] = Set.empty)
      extends EventProvider {
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
      if (failKeys.contains(key)) throw new ProviderNotReadyError(s"simulated failure for $key")
      else ProviderEvaluations.of[java.lang.Boolean](value, "STATIC")

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

  private def buildIsolated(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"hook-stage-ctx-${java.util.UUID.randomUUID()}"
    FeatureFlags.build(
      provider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = false,
      apiOverride = Some(api)
    )
  }

  private val marker = "added-by-before"

  /** A hook whose before stage adds a marker attribute and whose later stages record whether they saw it. */
  private def contextProbeHook(
    sawInAfter: Ref[Option[Boolean]],
    sawInError: Ref[Option[Boolean]],
    sawInFinally: Ref[Option[Boolean]]
  ): FeatureHook =
    new FeatureHook {
      override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
        ZIO.some((ctx.evaluationContext.withAttribute(marker, AttributeValue.BoolValue(true)), hints))

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        sawInAfter.set(Some(ctx.evaluationContext.getBoolean(marker).contains(true)))

      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        sawInError.set(Some(ctx.evaluationContext.getBoolean(marker).contains(true)))

      override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
        sawInFinally.set(Some(ctx.evaluationContext.getBoolean(marker).contains(true)))
    }

  def spec = suite("Hook stage context (spec 4.3.5-4.3.8)")(
    test("after and finally stages see the context modified by before hooks") {
      ZIO.scoped {
        for {
          sawAfter   <- Ref.make[Option[Boolean]](None)
          sawError   <- Ref.make[Option[Boolean]](None)
          sawFinally <- Ref.make[Option[Boolean]](None)
          ff         <- buildIsolated(new StaticBooleanProvider("p", value = true))
          _          <- ff.addHook(contextProbeHook(sawAfter, sawError, sawFinally))
          value      <- ff.boolean("flag", default = false)
          after      <- sawAfter.get
          fin        <- sawFinally.get
        } yield assertTrue(value, after.contains(true), fin.contains(true))
      }
    },
    test("error and finally stages see the context modified by before hooks") {
      ZIO.scoped {
        for {
          sawAfter   <- Ref.make[Option[Boolean]](None)
          sawError   <- Ref.make[Option[Boolean]](None)
          sawFinally <- Ref.make[Option[Boolean]](None)
          ff         <- buildIsolated(new StaticBooleanProvider("p", value = true, failKeys = Set("boom")))
          _          <- ff.addHook(contextProbeHook(sawAfter, sawError, sawFinally))
          result     <- ff.boolean("boom", default = false).exit
          err        <- sawError.get
          fin        <- sawFinally.get
        } yield assertTrue(result.isFailure, err.contains(true), fin.contains(true))
      }
    }
  )
}
