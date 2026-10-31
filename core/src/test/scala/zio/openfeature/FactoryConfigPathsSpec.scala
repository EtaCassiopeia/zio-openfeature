package zio.openfeature

import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, Metadata, ProviderState, Value}

/** The two factories that used to take no `FeatureFlagsConfig` now honour one: a `FeatureFlagRegistry` applies it to
  * every client, and `fromAcquireAsync` reads its instance-wide knobs from it and the rest from an `AcquireConfig`.
  *
  * Shared (cross-compiled) test source dir: braces only.
  */
object FactoryConfigPathsSpec extends ZIOSpecDefault {

  // Answers `true` for every boolean, after `evalDelay`; `initialize()` takes `initDelay`.
  private class Provider(name: String, initDelay: Duration = Duration.Zero, evalDelay: Duration = Duration.Zero)
      extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = Thread.sleep(initDelay.toMillis)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      Thread.sleep(evalDelay.toMillis)
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  private def countingHook(n: AtomicInteger): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO.succeed(n.incrementAndGet()).as(None)
  }

  private def isTimeout(e: FeatureFlagError): Boolean =
    e.cause.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])

  def spec = suite("FactoryConfigPathsSpec")(
    suite("FeatureFlagRegistry.fromProvider(provider, config)")(
      test("every client, domain and default, runs the config's hooks") {
        val calls = new AtomicInteger(0)
        ZIO.scoped {
          for {
            registry <- FeatureFlagRegistry
              .fromProvider(new Provider("p"), FeatureFlagsConfig().withHook(countingHook(calls)))
              .build
              .map(_.get[FeatureFlagRegistry])
            a <- registry.getClient("a").flatMap(_.boolean("f", default = false))
            d <- registry.defaultClient.flatMap(_.boolean("f", default = false))
          } yield assertTrue(a, d, calls.get() == 2)
        }
      },
      test("the config's evaluation timeout bounds a client's reads") {
        ZIO.scoped {
          for {
            registry <- FeatureFlagRegistry
              .fromProvider(
                new Provider("slow", evalDelay = 2.seconds),
                FeatureFlagsConfig().withEvaluationTimeout(50.millis)
              )
              .build
              .map(_.get[FeatureFlagRegistry])
            read <- registry.getClient("a").flatMap(_.boolean("f", default = false).either)
          } yield assertTrue(read.left.toOption.exists(isTimeout))
        }
      },
      test("withAsyncInit hands a client out before its provider is ready") {
        ZIO.scoped {
          for {
            registry <- FeatureFlagRegistry
              .fromProvider(new Provider("starting", initDelay = 1.second), FeatureFlagsConfig().withAsyncInit)
              .build
              .map(_.get[FeatureFlagRegistry])
            got    <- registry.getClient("a").timed
            status <- got._2.providerStatus
            ready  <- got._2.awaitReady(5.seconds)
          } yield assertTrue(got._1 < 900.millis, status == ProviderStatus.NotReady, ready == ProviderStatus.Ready)
        }
      }
    ),
    suite("fromAcquireAsync(acquire, fallback, config, acquireConfig)")(
      test("the config's domain and hooks reach the instance, which the old form could not set") {
        val calls = new AtomicInteger(0)
        ZIO.scoped {
          for {
            ff <- FeatureFlags
              .fromAcquireAsync(
                ZIO.succeed(new Provider("real")),
                ZIO.succeed(new Provider("fallback")),
                FeatureFlagsConfig().withDomain("acquired").withHook(countingHook(calls)).withIsolatedApi,
                AcquireConfig.default
              )
              .build
              .map(_.get[FeatureFlags])
            meta <- ff.clientMetadata
            read <- ff.boolean("f", default = false)
          } yield assertTrue(meta.domain == Some("acquired"), read, calls.get() == 1)
        }
      },
      test("AcquireConfig carries the swap settings: a verify that rejects keeps the fallback and reports why") {
        ZIO.scoped {
          for {
            failed <- Promise.make[Nothing, Throwable]
            layer = FeatureFlags.fromAcquireAsync(
              ZIO.succeed(new Provider("real")),
              ZIO.succeed(new Provider("fallback")),
              FeatureFlagsConfig().withIsolatedApi,
              AcquireConfig.default
                .withVerify(_ => ZIO.fail(new IllegalStateException("not answering")))
                .withConstructionRetry(Schedule.stop)
                .withOnConstructionError(e => failed.succeed(e).unit)
            )
            env    <- layer.build
            reason <- failed.await.timeout(10.seconds)
            state  <- env.get[AcquireStatus].get
          } yield assertTrue(
            reason.exists(_.getMessage == "not answering"),
            state match {
              case AcquireState.Failed(_) => true
              case _                      => false
            }
          )
        }
      },
      test("the deprecated form still forwards its evaluation timeout") {
        ZIO.scoped {
          for {
            ff   <- deprecatedForm(new Provider("slow fallback", evalDelay = 2.seconds)).build.map(_.get[FeatureFlags])
            read <- ff.boolean("f", default = false).either
          } yield assertTrue(read.left.toOption.exists(isTimeout))
        }
      }
    )
  ) @@ withLiveClock @@ TestAspect.sequential

  @scala.annotation.nowarn("cat=deprecation")
  private def deprecatedForm(fallback: Provider) =
    FeatureFlags.fromAcquireAsync(ZIO.never, ZIO.succeed(fallback), evaluationTimeout = 50.millis)
}
