package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

/** Tests for public factory methods that use the global OpenFeatureAPI singleton.
  *
  * These tests must run sequentially because they share the singleton's default provider slot. Isolated from other
  * specs to avoid cross-test contamination.
  */
object FactoryMethodsSpec extends ZIOSpecDefault {

  def spec = suite("Factory Methods — global singleton API")(
    suite("fromProviderWithHooks")(
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
          layer = FeatureFlags.fromProviderWithHooks(provider, List(hook))
          _     <- FeatureFlags.boolean("flag", default = false).provide(Scope.default >>> layer)
          calls <- callsRef.get
        } yield assertTrue(calls == 1)
      }
    ),
    suite("fromMultiProvider")(
      test("fromMultiProvider creates a usable layer") {
        ZIO.scoped {
          for {
            provider <- TestFeatureProvider.make(Map("flag" -> true))
            ff       <- FeatureFlags.fromMultiProvider(List(provider)).build.map(_.get)
            result   <- ff.boolean("flag", default = false)
          } yield assertTrue(result == true)
        }
      },
      test("fromMultiProvider with custom strategy creates a usable layer") {
        import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy
        ZIO.scoped {
          for {
            provider <- TestFeatureProvider.make(Map("flag" -> "hello"))
            ff <- FeatureFlags
              .fromMultiProvider(List(provider), new FirstSuccessfulStrategy())
              .build
              .map(_.get)
            result <- ff.string("flag", default = "none")
          } yield assertTrue(result == "hello")
        }
      }
    ),
    suite("fromProviderAsync")(
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
      test("fromProviderWithHooksAsync includes hooks") {
        for {
          hookCalled <- Ref.make(false)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
              hookCalled.set(true).as(None)
          }
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          result <- ZIO.scoped {
            FeatureFlags.fromProviderWithHooksAsync(tp, List(hook)).build.flatMap { env =>
              val ff = env.get[FeatureFlags]
              ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                ff.boolean("flag", default = false) *>
                hookCalled.get
            }
          }
        } yield assertTrue(result)
      },
      test("fromProviderWithDomainAsync creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          domain = s"test-async-factory-${java.util.UUID.randomUUID()}"
          result <- ZIO.scoped {
            FeatureFlags.fromProviderWithDomainAsync(tp, domain).build.flatMap { env =>
              val ff = env.get[FeatureFlags]
              ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds) *>
                ff.boolean("flag", default = false)
            }
          }
        } yield assertTrue(result == true)
      },
      test("fromMultiProviderAsync creates a working layer") {
        for {
          tp <- TestFeatureProvider.make(Map("flag" -> true))
          result <- ZIO.scoped {
            FeatureFlags.fromMultiProviderAsync(List(tp)).build.flatMap { env =>
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
