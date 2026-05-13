package zio.openfeature.extras

import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions
import zio.test._
import java.time.Duration

object OFREPProviderSpec extends ZIOSpecDefault {

  def spec = suite("OFREPProvider")(
    suite("construction")(
      test("default() returns a non-null provider with the contrib metadata name") {
        val provider = OFREPProvider.default()
        try
          assertTrue(provider != null) &&
            assertTrue(provider.getMetadata != null) &&
            assertTrue(provider.getMetadata.getName != null) &&
            assertTrue(provider.getMetadata.getName.nonEmpty)
        finally provider.shutdown()
      },
      test("apply(baseUrl) returns a provider; baseUrl is accepted without throwing") {
        val provider = OFREPProvider("http://localhost:9999")
        try assertTrue(provider != null)
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
        try assertTrue(provider != null)
        finally provider.shutdown()
      }
    ),
    suite("delegation")(
      test("metadata name matches the underlying OfrepProvider") {
        val provider = OFREPProvider("http://localhost:9999")
        try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      }
    )
  )
}
