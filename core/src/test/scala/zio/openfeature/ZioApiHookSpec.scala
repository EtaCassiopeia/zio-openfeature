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
import zio._
import zio.test._

object ZioApiHookSpec extends ZIOSpecDefault {

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

  private def buildIsolated(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"zio-api-hook-${java.util.UUID.randomUUID()}"
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

  private def recordingHook(label: String, log: Ref[List[String]]): FeatureHook =
    new FeatureHook {
      override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
        log.update(_ :+ label).as(None)

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        log.update(_ :+ s"$label-after")
    }

  def spec = suite("ZIO API-level hooks")(
    suite("ordering")(
      test("API hook runs before client hook in before-phase (spec §4.4.1: api -> client)") {
        ZIO.scoped {
          for {
            log    <- Ref.make(List.empty[String])
            ff     <- buildIsolated(new SimpleBooleanProvider("p", true))
            _      <- ff.addZioApiHook(recordingHook("api", log))
            _      <- ff.addHook(recordingHook("client", log))
            _      <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
            events <- log.get
          } yield assertTrue(events.take(2) == List("api", "client"))
        }
      },
      test("after-phase runs in reverse order (client-after before api-after)") {
        ZIO.scoped {
          for {
            log    <- Ref.make(List.empty[String])
            ff     <- buildIsolated(new SimpleBooleanProvider("p", true))
            _      <- ff.addZioApiHook(recordingHook("api", log))
            _      <- ff.addHook(recordingHook("client", log))
            _      <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
            events <- log.get
          } yield assertTrue(events == List("api", "client", "client-after", "api-after"))
        }
      }
    ),
    suite("management")(
      test("addZioApiHooks adds multiple hooks") {
        ZIO.scoped {
          for {
            log    <- Ref.make(List.empty[String])
            ff     <- buildIsolated(new SimpleBooleanProvider("p", true))
            _      <- ff.addZioApiHooks(List(recordingHook("a1", log), recordingHook("a2", log)))
            _      <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
            events <- log.get
          } yield assertTrue(events.take(2) == List("a1", "a2"))
        }
      },
      test("clearZioApiHooks removes all API-level hooks") {
        ZIO.scoped {
          for {
            log    <- Ref.make(List.empty[String])
            ff     <- buildIsolated(new SimpleBooleanProvider("p", true))
            _      <- ff.addZioApiHook(recordingHook("api", log))
            _      <- ff.clearZioApiHooks
            _      <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
            events <- log.get
          } yield assertTrue(events.isEmpty)
        }
      },
      test("clearZioApiHooks does not affect client-level hooks") {
        ZIO.scoped {
          for {
            log    <- Ref.make(List.empty[String])
            ff     <- buildIsolated(new SimpleBooleanProvider("p", true))
            _      <- ff.addZioApiHook(recordingHook("api", log))
            _      <- ff.addHook(recordingHook("client", log))
            _      <- ff.clearZioApiHooks
            _      <- ff.boolean("flag", default = false).provideEnvironment(ZEnvironment(ff))
            events <- log.get
          } yield assertTrue(events.take(1) == List("client"))
        }
      },
      test("zioApiHooks returns the registered hooks") {
        ZIO.scoped {
          for {
            log <- Ref.make(List.empty[String])
            ff  <- buildIsolated(new SimpleBooleanProvider("p", true))
            hook = recordingHook("api", log)
            _    <- ff.addZioApiHook(hook)
            list <- ff.zioApiHooks
          } yield assertTrue(list.length == 1)
        }
      }
    ),
    suite("FeatureFlagRegistry propagation")(
      test("addZioApiHook propagates to clients created after the call") {
        ZIO.scoped {
          for {
            log <- Ref.make(List.empty[String])
            registry <- FeatureFlagRegistry
              .fromProvider(new SimpleBooleanProvider("p", true))
              .build
              .map(_.get[FeatureFlagRegistry])
            _      <- registry.addZioApiHook(recordingHook("api", log))
            client <- registry.getClient(s"dom-${java.util.UUID.randomUUID()}")
            _      <- client.boolean("flag", default = false).provideEnvironment(ZEnvironment(client))
            events <- log.get
          } yield assertTrue(events.contains("api"))
        }
      },
      test("addZioApiHook propagates to already-existing clients") {
        ZIO.scoped {
          for {
            log <- Ref.make(List.empty[String])
            registry <- FeatureFlagRegistry
              .fromProvider(new SimpleBooleanProvider("p", true))
              .build
              .map(_.get[FeatureFlagRegistry])
            client <- registry.getClient(s"dom-${java.util.UUID.randomUUID()}")
            _      <- registry.addZioApiHook(recordingHook("api", log))
            _      <- client.boolean("flag", default = false).provideEnvironment(ZEnvironment(client))
            events <- log.get
          } yield assertTrue(events.contains("api"))
        }
      }
    )
  )
}
