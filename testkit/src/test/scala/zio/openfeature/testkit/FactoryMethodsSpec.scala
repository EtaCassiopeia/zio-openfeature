package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

/** Tests for public factory methods that use the global OpenFeatureAPI singleton.
  *
  * These tests must run sequentially because they share the singleton's default provider slot. Isolated from other
  * specs to avoid cross-test contamination.
  *
  * Exercises the `FeatureFlagsConfig`-driven `fromProvider(provider, config)` factory (#253). The behavioral
  * equivalence between this and the now-deprecated overloads it replaced is pinned separately, under
  * `@nowarn("cat=deprecation")`, by `FactoryConfigSpec`'s T6 suite.
  */
object FactoryMethodsSpec extends ZIOSpecDefault {

  def spec = suite("Factory Methods — global singleton API")(
    suite("fromProvider with hooks")(
      test("layer created with initial hooks has those hooks") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
        }

        val hook = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ + 1)
        }

        for {
          provider <- TestFeatureProvider.make(Map("flag" -> true))
          layer = FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withHooks(List(hook)))
          _     <- FeatureFlags.boolean("flag", default = false).provide(Scope.default >>> layer)
          calls <- callsRef.get
        } yield assertTrue(calls == 1)
      }
    ),
    suite("fromProvider with domain")(
      test("domain + version config exposes version in clientMetadata") {
        for {
          provider <- TestFeatureProvider.make(Map("flag" -> true))
          domain = s"test-versioned-${java.util.UUID.randomUUID()}"
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(provider, FeatureFlagsConfig().withDomain(domain).withVersion("2.0.0"))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                for {
                  meta <- ff.clientMetadata
                  flag <- ff.boolean("flag", default = false)
                } yield assertTrue(meta.domain.contains(domain)) &&
                  assertTrue(meta.version.contains("2.0.0")) &&
                  assertTrue(flag == true)
              }
          }
        } yield result
      }
    ),
    suite("fromProvider with FeatureFlags.multiProvider")(
      test("multiProvider creates a usable layer (first-match default)") {
        ZIO.scoped {
          for {
            provider <- TestFeatureProvider.make(Map("flag" -> true))
            ff <- FeatureFlags
              .fromProvider(FeatureFlags.multiProvider(List(provider)), FeatureFlagsConfig())
              .build
              .map(_.get)
            result <- ff.boolean("flag", default = false)
          } yield assertTrue(result == true)
        }
      },
      test("multiProvider with a custom strategy creates a usable layer") {
        import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy
        ZIO.scoped {
          for {
            provider <- TestFeatureProvider.make(Map("flag" -> "hello"))
            ff <- FeatureFlags
              .fromProvider(
                FeatureFlags.multiProvider(List(provider), new FirstSuccessfulStrategy()),
                FeatureFlagsConfig()
              )
              .build
              .map(_.get)
            result <- ff.string("flag", default = "none")
          } yield assertTrue(result == "hello")
        }
      }
    ),
    suite("fromProviderAsync / async config")(
      test("fromProviderAsync creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          result <- ZIO.scoped {
            FeatureFlags.fromProviderAsync(tp).build.flatMap { env =>
              val ff = env.get[FeatureFlags]
              ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                ff.boolean("flag", default = false)
            }
          }
        } yield assertTrue(result == true)
      },
      test("async config with hooks includes hooks") {
        for {
          hookCalled <- Ref.make(false)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              hookCalled.set(true).as(None)
          }
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(tp, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(List(hook)))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                  ff.boolean("flag", default = false) *>
                  hookCalled.get
              }
          }
        } yield assertTrue(result)
      },
      test("async config with domain creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          domain = s"test-async-factory-${java.util.UUID.randomUUID()}"
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(tp, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                  ff.boolean("flag", default = false)
              }
          }
        } yield assertTrue(result == true)
      },
      test("async config with domain and version creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          domain = s"test-async-versioned-${java.util.UUID.randomUUID()}"
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(
                tp,
                FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain).withVersion("1.2.3")
              )
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                  ff.boolean("flag", default = false)
              }
          }
        } yield assertTrue(result == true)
      },
      test("async config with FeatureFlags.multiProvider creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(FeatureFlags.multiProvider(List(tp)), FeatureFlagsConfig(initMode = InitMode.Async))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                  ff.boolean("flag", default = false)
              }
          }
        } yield assertTrue(result == true)
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
