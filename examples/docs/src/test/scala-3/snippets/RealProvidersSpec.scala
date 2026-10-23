package snippets

import dev.openfeature.sdk.FeatureProvider
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.openfeature.testkit.*
import zio.test.*

/** The library side of the "Testing real providers" page. The rift-scala fixtures are not a dependency of this build,
  * so a stalled or failing network is stood in for by an `acquire` that never finishes or fails; what the page asserts
  * about `fromAcquireAsync` is checked here against those.
  */
object RealProvidersSpec extends ZIOSpecDefault:

  // --8<-- [start:flags-layer]
  // Seeded, so its answer differs from the `default = false` used below. A fallback that agrees with the default
  // would make every assertion pass even if the fault never reached the provider.
  def seededFallback: FeatureProvider = EnvVarProvider.withLookup(Map("FF_MY_FLAG" -> "true").get)

  def flagsLayer(acquire: RIO[Scope, FeatureProvider]): URLayer[Scope, FeatureFlags & AcquireStatus] =
    FeatureFlags.fromAcquireAsync(
      acquire = acquire,                     // e.g. ZIO.attemptBlocking(new VendorProvider(sdkKey))
      fallback = ZIO.succeed(seededFallback) // a fresh instance each time it runs
    )
  // --8<-- [end:flags-layer]

  // Stand-ins for what the intercept proxy does to a real provider
  val stalled: RIO[Scope, FeatureProvider]  = ZIO.never
  val rejected: RIO[Scope, FeatureProvider] = ZIO.fail(new RuntimeException("401 Unauthorized"))

  // --8<-- [start:assert-boot]
  // "Boot did not block": the fallback answers at once, with its configured value
  val bootDidNotBlock =
    FeatureFlags.boolean("my-flag", default = false).map(flag => assertTrue(flag))
  // --8<-- [end:assert-boot]

  // --8<-- [start:assert-never-fails]
  // "Never fails": assert on the exit, not the value
  val neverFails =
    FeatureFlags.boolean("my-flag", default = false).exit.map(exit => assertTrue(exit.isSuccess))
  // --8<-- [end:assert-never-fails]

  // --8<-- [start:assert-swapped]
  // "The real provider swapped in": AcquireStatus says so, no polling needed
  val swappedIn =
    ZIO.serviceWithZIO[AcquireStatus](_.changes.takeUntil(_ == AcquireState.Live).runDrain)
  // --8<-- [end:assert-swapped]

  def spec = suite("Testing real providers page")(
    test("stalled fetch: the fallback answers immediately") {
      bootDidNotBlock.provideSome[Scope](flagsLayer(stalled))
    },
    test("rejected credentials: evaluation never fails") {
      neverFails.provideSome[Scope](flagsLayer(rejected))
    },
    test("healthy: the real provider is swapped in") {
      for
        real <- TestFeatureProvider.make(Map("my-flag" -> false))
        flag <- (swappedIn *> FeatureFlags.boolean("my-flag", default = true))
          .provideSome[Scope](flagsLayer(ZIO.succeed(real)))
      yield assertTrue(!flag) // the real provider's value, not the fallback's
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
