package snippets

import dev.openfeature.sdk.{EvaluationContext as OFContext, NoOpProvider}
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object ProvidersSpec extends ZIOSpecDefault:

  private val banner = FeatureFlags.string("banner", "none")
  private val limit  = FeatureFlags.int("limit", 0)

  def spec = suite("Providers")(
    test("any provider: the program reads the flag through fromProvider") {
      for
        provider <- TestFeatureProvider.make(Map("new-checkout" -> true))
        result   <- Providers.run(provider)
      yield assertTrue(result)
    },
    test("multi-provider: a flag the first provider lacks falls through to the next") {
      for
        overrides <- TestFeatureProvider.makeNamed("overrides", Map("banner" -> "spring-sale"))
        remote    <- TestFeatureProvider.makeNamed("remote", Map("banner" -> "default", "limit" -> 5))
        result    <- (banner <*> limit).provide(Providers.layered(overrides, remote))
      yield assertTrue(result == ("spring-sale", 5))
    },
    test("fromAcquireAsync: the verified real provider is swapped in and AcquireStatus turns Live") {
      for
        real   <- TestFeatureProvider.makeNamed("real", Map("kill-switch" -> true, "banner" -> "from-real"))
        result <- (Providers.outcome <*> Providers.ready <*> banner)
          .provide(Providers.fallbackFirst(() => real))
      yield assertTrue(result == (Some(AcquireState.Live), true, "from-real"))
    } @@ TestAspect.withLiveClock,
    test("registry: a domain with its own provider is served by it") {
      for
        default <- TestFeatureProvider.makeNamed("default")
        billing <- TestFeatureProvider.makeNamed("billing", Map("new-pricing" -> true))
        result  <- Providers.withRegistry(default, billing)
      yield assertTrue(result)
    },
    test("registry: a domain without a provider falls back to the default one") {
      for
        default <- TestFeatureProvider.makeNamed("default", Map("new-pricing" -> true))
        result  <- Providers.billingFlag.provide(FeatureFlagRegistry.fromProvider(default))
      yield assertTrue(result)
    },
    test("hot swap: the new provider serves after setProvider") {
      for
        next   <- TestFeatureProvider.makeNamed("next", Map("banner" -> "from-next"))
        _      <- Providers.swap(next)
        result <- banner
        status <- FeatureFlags.providerStatus
      yield assertTrue(result == "from-next", status == ProviderStatus.Ready)
    }.provide(TestFeatureProvider.layer(Map("banner" -> "from-first"))),
    test("hot swap: a provider that fails to start leaves the previous one serving") {
      val broken = new NoOpProvider:
        override def initialize(ctx: OFContext): Unit = throw new RuntimeException("cannot start")
      for
        failed <- FeatureFlags.setProvider(broken).either
        _      <- Providers.swap(broken)
        result <- banner
        status <- FeatureFlags.providerStatus
      yield assertTrue(
        failed.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
        result == "from-first",
        status == ProviderStatus.Ready
      )
    }.provide(TestFeatureProvider.layer(Map("banner" -> "from-first"))),
    test("config: domain and version show up in clientMetadata") {
      for
        provider <- TestFeatureProvider.make
        text     <- Providers.describe.provide(Providers.configured(provider))
      yield assertTrue(text == "client checkout@2.4.0 served by TestFeatureProvider")
    }
  ) @@ TestAspect.sequential
