package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProvider
import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions
import dev.openfeature.sdk.ProviderState
import zio._
import zio.openfeature.FeatureFlags
import zio.test._
import java.time.Duration

object OFREPProviderSpec extends ZIOSpecDefault {

  // The contrib provider 0.0.1 still exposes `getState` (deprecated) rather than the newer `getStatus`. Scope the
  // suppression here so any other deprecation that appears in this spec stays visible.
  private def stateOf(p: OfrepProvider): ProviderState = {
    @scala.annotation.nowarn("msg=deprecated")
    val s = p.getState
    s
  }

  def spec = suite("OFREPProvider factories")(
    test("apply() returns an OfrepProvider with ofrep metadata") {
      val provider = OFREPProvider()
      try
        assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep")) &&
          assertTrue(stateOf(provider) != null)
      finally provider.shutdown()
    },
    test("apply(baseUrl) returns a configured OfrepProvider") {
      val provider = OFREPProvider("http://localhost:9999")
      try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
      finally provider.shutdown()
    },
    test("fromOptions accepts a fully-built OfrepProviderOptions") {
      val opts = OfrepProviderOptions
        .builder()
        .baseUrl("http://localhost:9999")
        .requestTimeout(Duration.ofSeconds(5))
        .connectTimeout(Duration.ofSeconds(5))
        .build()
      val provider = OFREPProvider.fromOptions(opts)
      try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
      finally provider.shutdown()
    },
    test("returned provider integrates with FeatureFlags.fromProviderAsync (compile-time check)") {
      // If the factory's return type isn't FeatureProvider-compatible, this won't compile.
      val _: ZLayer[Scope, Throwable, FeatureFlags] =
        FeatureFlags.fromProviderAsync(OFREPProvider("http://localhost:9999"))
      assertCompletes
    }
  )
}
