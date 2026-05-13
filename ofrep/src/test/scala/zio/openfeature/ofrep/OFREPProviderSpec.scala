package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions
import zio._
import zio.openfeature.FeatureFlags
import zio.test._
import java.time.Duration

@scala.annotation.nowarn("msg=deprecated")
object OFREPProviderSpec extends ZIOSpecDefault {

  def spec = suite("OFREPProvider factories")(
    test("apply() with default arg returns an OfrepProvider with ofrep metadata") {
      val provider = OFREPProvider()
      try
        assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep")) &&
          assertTrue(provider.getState != null)
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
