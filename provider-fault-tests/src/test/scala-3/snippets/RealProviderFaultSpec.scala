package snippets

import dev.openfeature.sdk.FeatureProvider
// --8<-- [start:suite-imports]
import rift.dsl.*
import rift.zio.testkit.aspects as riftAspects
import rift.zio.{InterceptHandle, Rift}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
// --8<-- [end:suite-imports]
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.openfeature.optimizely.{OptimizelyEvents, OptimizelyProvider, OptimizelyProviderConfig}
import zio.test.*

/** The "A suite" example of the "Testing real providers" page, against the real Optimizely provider (whose construction
  * fetches its datafile, which is what a stalled or rejected network breaks). The full matrix of faults, for Optimizely
  * and for OFREP, is in [[OptimizelyFaultSpec]] and [[OfrepFaultSpec]].
  */
object RealProviderFaultSpec extends ZIOSpecDefault:

  private val datafile = """{"version":"4","revision":"1","featureFlags":[],"rollouts":[],"experiments":[]}"""

  // The provider under test: a real vendor SDK, pointed at a host the rules below intercept.
  private val vendorProvider: RIO[Scope, FeatureProvider] =
    acquired(
      OptimizelyProvider.scoped(
        OptimizelyProviderConfig(
          sdkKey = "fault-test-key",
          datafileUrl = Some("https://cdn.example.com/datafiles/fault-test-key.json"),
          initWait = java.time.Duration.ofSeconds(2),
          events = OptimizelyEvents.Disabled // datafile behaviour only; keep events off the network
        )
      )
    )

  private def seededFallback: FeatureProvider = EnvVarProvider.withLookup(Map("FF_MY_FLAG" -> "true").get)

  private def flagsLayer(acquire: RIO[Scope, FeatureProvider]): URLayer[Any, FeatureFlags & AcquireStatus] =
    FeatureFlags.fromAcquireAsync(acquire = acquire, fallback = ZIO.succeed(seededFallback))

  private val bootDidNotBlock = FeatureFlags.boolean("my-flag", default = false).map(flag => assertTrue(flag))
  private val neverFails = FeatureFlags.boolean("my-flag", default = false).exit.map(exit => assertTrue(exit.isSuccess))

  // --8<-- [start:suite]
  // provideShared + sequential: the proxy settings are JVM-wide, so one engine, one test at a time.
  // clearRules first: rules accumulate on the handle, and an earlier test's rule may match first.
  // withLiveClock: waits and faults happen in the engine, in real time. embeddedOnly: ignored where it cannot load.
  def spec = suite("real provider under network faults")(
    test("stalled fetch: the fallback answers at once") {
      for
        engine <- ZIO.service[Rift]
        handle <- ZIO.service[InterceptHandle]
        _      <- handle.clearRules
        slow   <- engine.create(
          imposter("slow-cdn").stub(
            get("/datafiles/fault-test-key.json").reply(ok.json(datafile).after(FiniteDuration(30, SECONDS)))
          )
        )
        _      <- handle.rule("cdn.example.com").redirectTo(slow)
        result <- bootDidNotBlock.provide(flagsLayer(vendorProvider))
      yield result
    },
    test("rejected credentials: evaluation never fails") {
      for
        handle <- ZIO.service[InterceptHandle]
        _      <- handle.clearRules
        _      <- handle.rule("cdn.example.com").serve(status(401).json("""{"error":"unauthorized"}"""))
        result <- neverFails.provide(flagsLayer(vendorProvider))
      yield result
    }
  ).provideShared(RiftFixture.layer) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ riftAspects.embeddedOnly
  // --8<-- [end:suite]
