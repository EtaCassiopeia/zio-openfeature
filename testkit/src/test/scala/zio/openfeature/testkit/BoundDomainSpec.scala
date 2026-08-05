package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.TestAspect.sequential
import zio.openfeature._

/** SDK 1.22.0 supplies the bound domain to `FeatureProvider.initialize(ctx, domain)` (java-sdk#1982).
  *
  * This library already registers providers with `setProviderAndWait(domain, provider)`, so the domain should reach the
  * provider with no API change on our side — but "should" is the whole reason to pin it: nothing else in the codebase
  * would notice if the SDK stopped delivering it, or if a wrapper swallowed the two-argument overload on the way
  * through.
  */
object BoundDomainSpec extends ZIOSpecDefault {

  def spec = suite("bound domain")(
    test("a domain-registered client delivers its domain to the provider") {
      ZIO.scoped {
        for {
          provider <- TestFeatureProvider.make
          _        <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig(domain = Some("checkout"))).build
          domain   <- provider.boundDomain
        } yield assertTrue(domain.contains("checkout"))
      }
    },
    test("a provider registered without a domain reports None") {
      ZIO.scoped {
        for {
          provider <- TestFeatureProvider.make
          _        <- FeatureFlags.fromProvider(provider).build
          domain   <- provider.boundDomain
        } yield assertTrue(domain.isEmpty)
      }
    }
  ) @@ sequential
}
