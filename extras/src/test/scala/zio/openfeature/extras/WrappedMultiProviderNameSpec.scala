package zio.openfeature.extras

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature._
import zio.openfeature.extras._
import zio.openfeature.testkit.TestFeatureProvider
import zio.test._

/** A CircuitBreakerProvider or CachingProvider around `FeatureFlags.multiProvider` reads the MultiProvider's metadata,
  * which is `null` until it initializes. The wrapper then has no name, rather than throwing, and the swap reads the
  * name again once the provider has initialized.
  */
object WrappedMultiProviderNameSpec extends ZIOSpecDefault {

  private val config = FeatureFlagsConfig.default.withIsolatedApi

  private def multi(name: String): UIO[OFFeatureProvider] =
    TestFeatureProvider.makeNamed(name, Map("f" -> true)).map(p => FeatureFlags.multiProvider(List(p)))

  private def breaker(p: OFFeatureProvider): OFFeatureProvider = CircuitBreakerProvider(p)
  private def cache(p: OFFeatureProvider): OFFeatureProvider   = CachingProvider(p)

  /** Build on a plain provider, then swap to `wrap(multiProvider)`: the swap's exit, then a read. */
  private def swapTo(wrap: OFFeatureProvider => OFFeatureProvider) =
    ZIO.scoped {
      for {
        first <- TestFeatureProvider.makeNamed("first", Map("f" -> false))
        env   <- FeatureFlags.fromProvider(first, config).build
        ff = env.get[FeatureFlags]
        ad = env.get[FeatureFlagsAdmin]
        next  <- multi("member")
        exit  <- ad.setProvider(wrap(next)).exit
        value <- ff.boolean("f", false).either
        st    <- ff.providerStatus
      } yield (exit, value, st)
    }

  def spec = suite("WrappedMultiProviderNameSpec")(
    test("control: the sync build accepts a breaker around a multiProvider") {
      ZIO.scoped {
        for {
          p   <- multi("m")
          env <- FeatureFlags.fromProvider(breaker(p), config).build
          v   <- env.get[FeatureFlags].boolean("f", false)
        } yield assertTrue(v)
      }
    },
    test("control: a swap to a bare multiProvider succeeds") {
      swapTo(identity).map { case (exit, value, _) => assertTrue(exit.isSuccess, value == Right(true)) }
    },
    test("a swap to a breaker around a multiProvider succeeds") {
      swapTo(breaker).map { case (exit, value, st) =>
        assertTrue(exit.isSuccess, value == Right(true), st == ProviderStatus.Ready)
      }
    },
    test("a swap to a cache around a multiProvider succeeds") {
      swapTo(cache).map { case (exit, value, st) =>
        assertTrue(exit.isSuccess, value == Right(true), st == ProviderStatus.Ready)
      }
    },
    test("control: the sync build accepts a cache around a multiProvider") {
      ZIO.scoped {
        for {
          p   <- multi("m")
          env <- FeatureFlags.fromProvider(cache(p), config).build
          v   <- env.get[FeatureFlags].boolean("f", false)
        } yield assertTrue(v)
      }
    },
    test("a breaker or a cache around a multiProvider has no name before it initializes, and throws nothing") {
      for {
        p1 <- multi("m1")
        p2 <- multi("m2")
        names = List(breaker(p1), cache(p2)).map(w => scala.util.Try(w.getMetadata.getName))
      } yield assertTrue(names.forall(_ == scala.util.Success(null)))
    },
    test("a registry's setProvider installs a breaker around a multiProvider in a live client") {
      ZIO.scoped {
        for {
          first    <- TestFeatureProvider.makeNamed("first", Map("f" -> false))
          registry <- FeatureFlagRegistry.fromProvider(first).build.map(_.get[FeatureFlagRegistry])
          client   <- registry.getClient("d")
          next     <- multi("member")
          swapped  <- registry.setProvider("d", breaker(next)).either
          value    <- client.boolean("f", false).either
        } yield assertTrue(swapped.isRight, value == Right(true))
      }
    },
    test("the async build accepts a breaker around a multiProvider") {
      ZIO.scoped {
        for {
          p    <- multi("m")
          exit <- FeatureFlags.fromProvider(breaker(p), config.withAsyncInit).build.exit
        } yield assertTrue(exit.isSuccess)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
