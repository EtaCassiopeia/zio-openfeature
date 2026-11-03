package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Gate tests for the config-driven factory (`FeatureFlags.fromProvider(provider, config)`). Each test exercises a
  * combination the earlier factory-overload surface could not express (T1-T5).
  *
  * Isolated `OpenFeatureAPI` instances (`OpenFeatureAPI.createIsolated()`) + the `private[openfeature]`
  * `FeatureFlags.fromProvider(provider, config, statusRef, apiOverride, onReady)` variant are used throughout so these
  * tests never touch the process-global singleton and can run in parallel with every other spec.
  */
object FactoryConfigSpec extends ZIOSpecDefault {

  private def uniqueDomain(label: String): String = s"cfg-$label-${java.util.UUID.randomUUID()}"

  /** A minimal tracking provider (mirrors `FeatureFlagsShutdownSpec`'s) so T4a/T4b can observe `shutdown()` directly,
    * independent of `TestFeatureProvider`'s own state-transition semantics.
    */
  private class TrackingProvider(name: String, shutCalled: AtomicBoolean) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = shutCalled.set(true)

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

  private def coreSuite = suite("Factory Config — FeatureFlagsConfig-driven fromProvider")(
    test("T1: domain + hooks (impossible before) — hooks fire AND clientMetadata reflects the domain") {
      for {
        provider  <- TestFeatureProvider.make(Map("flag" -> true))
        hookCalls <- Ref.make(0)
        hook = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            hookCalls.update(_ + 1)
        }
        domain = uniqueDomain("t1")
        config = FeatureFlagsConfig.default.withDomain(domain).withHooks(List(hook))
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap { env =>
              val ff = env.get[FeatureFlags]
              for {
                meta  <- ff.clientMetadata
                flag  <- ff.boolean("flag", default = false)
                calls <- hookCalls.get
              } yield assertTrue(meta.domain.contains(domain), flag, calls == 1)
            }
        }
      } yield result
    },
    test("T2: domain + evaluationTimeout — a slow provider fails with ProviderError(TimeoutException)") {
      for {
        provider <- TestFeatureProvider.make(Map("flag" -> true))
        _        <- provider.setDelay(500.millis)
        domain = uniqueDomain("t2")
        config = FeatureFlagsConfig.default.withDomain(domain).withEvaluationTimeout(20.millis)
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap(env => env.get[FeatureFlags].boolean("flag", default = false).either)
        }
      } yield assertTrue(
        result.left.toOption.exists {
          case FeatureFlagError.ProviderError(underlying) => underlying.isInstanceOf[TimeoutException]
          case _                                          => false
        }
      )
    } @@ withLiveClock,
    test("T3: initMode = Async — layer builds immediately; first eval fails ProviderNotReady, then succeeds") {
      for {
        provider <- TestFeatureProvider.makeNotReady(Map("flag" -> true))
        domain = uniqueDomain("t3")
        config = FeatureFlagsConfig.default.withAsyncInit.withDomain(domain)
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(
              provider,
              config,
              statusRef = Some(provider.statusRef),
              apiOverride = Some(OpenFeatureAPI.createIsolated()),
              onReady = provider.initDone
            )
            .build
            .flatMap { env =>
              val ff = env.get[FeatureFlags]
              for {
                before <- ff.boolean("flag", default = false).either
                _      <- provider.setStatus(ProviderStatus.Ready)
                after  <- ff.boolean("flag", default = false)
              } yield assertTrue(
                before == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
                after
              )
            }
        }
      } yield result
    } @@ withLiveClock,
    test("T4a: Auto + domain — closing the config-layer scope does NOT shut the shared api") {
      val shutA = new AtomicBoolean(false)
      val shutB = new AtomicBoolean(false)
      val api   = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          envA <- FeatureFlags
            .fromProvider(
              new TrackingProvider("A", shutA),
              FeatureFlagsConfig.default.withAsyncInit.withDomain(uniqueDomain("t4a-A")),
              statusRef = None,
              apiOverride = Some(api)
            )
            .build
          ffA = envA.get[FeatureFlags]
          ffB <- FeatureFlags
            .fromProvider(
              new TrackingProvider("B", shutB),
              FeatureFlagsConfig.default.withAsyncInit.withDomain(uniqueDomain("t4a-B")),
              statusRef = None,
              apiOverride = Some(api)
            )
            .build
            .map(_.get[FeatureFlags])
          _      <- ffA.awaitReady(5.seconds)
          _      <- ffB.awaitReady(5.seconds)
          _      <- envA.get[FeatureFlagsAdmin].shutdown
          sibRes <- ffB.boolean("flag", default = false)
        } yield assertTrue(
          !shutA.get(),   // own provider left to the (still-alive) shared api
          !shutB.get(),   // sibling untouched
          sibRes == false // sibling still evaluable after ffA's shutdown
        )
      }
    } @@ withLiveClock,
    test("T4b: Owned override + domain — scope close DOES shut that (private) api's provider") {
      val shut = new AtomicBoolean(false)
      for {
        // The inner `ZIO.scoped` fully closes (running finalizers, including the api-shutdown one) before this
        // for-comprehension continues — so `shut.get()` below observes the post-close state deterministically.
        _ <- ZIO.scoped {
          FeatureFlags
            .fromProvider(
              new TrackingProvider("owned", shut),
              FeatureFlagsConfig.default.withAsyncInit
                .withDomain(uniqueDomain("t4b"))
                .withApiOwnership(ApiOwnership.Owned),
              statusRef = None,
              apiOverride = Some(OpenFeatureAPI.createIsolated())
            )
            .build
            .flatMap(env => env.get[FeatureFlags].awaitReady(5.seconds))
        }
      } yield assertTrue(shut.get())
    } @@ withLiveClock,
    test("T5: withoutEvaluationTimeout — a provider slower than DefaultEvaluationTimeout does not time out") {
      for {
        provider <- TestFeatureProvider.make(Map("flag" -> true))
        _        <- provider.setDelay(FeatureFlags.DefaultEvaluationTimeout + 500.millis)
        domain = uniqueDomain("t5")
        config = FeatureFlagsConfig.default.withDomain(domain).withoutEvaluationTimeout
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap(env => env.get[FeatureFlags].boolean("flag", default = false))
        }
      } yield assertTrue(result)
    } @@ withLiveClock
  )

  def spec = coreSuite @@ TestAspect.sequential
}
