package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.ofrep.{OFREPProvider, OFREPProviderConfig}
import zio.openfeature.optimizely.{OptimizelyEvents, OptimizelyProvider, OptimizelyProviderConfig}
import zio.test.*

/** The OFREP and Optimizely pages' validation claims. Construction never touches the network, so these run offline. */
object ProvidersModulesSpec extends ZIOSpecDefault:

  private def rejected[A](make: IO[FeatureFlagError.InvalidConfiguration, A]) =
    make.flip.map(_.isInstanceOf[FeatureFlagError.InvalidConfiguration])

  def spec = suite("OFREP and Optimizely construction")(
    test("OFREPProvider.make rejects a URL that is empty, not http(s), or has no host") {
      for
        empty  <- rejected(OFREPProvider.make(""))
        scheme <- rejected(OFREPProvider.make("ftp://flags.example.com"))
        host   <- rejected(OFREPProvider.make(OFREPProviderConfig(baseUrl = "https://")))
      yield assertTrue(empty, scheme, host)
    },
    test("OFREPProvider.scoped accepts a valid URL without contacting it") {
      ZIO.scoped(OFREPProvider.scoped("https://flags.example.com")).as(assertCompletes)
    },
    test("OptimizelyProvider.make rejects placeholder, short and malformed keys, and a bad datafile URL") {
      for
        placeholder <- rejected(OptimizelyProvider.make("YOUR_SDK_KEY"))
        short       <- rejected(OptimizelyProvider.make("abc"))
        spaces      <- rejected(OptimizelyProvider.make("abcd 1234efgh"))
        url         <- rejected(OptimizelyProvider.make("abcd1234efgh5678", "not a url"))
        polling <- rejected(
          OptimizelyProvider.make(
            OptimizelyProviderConfig("abcd1234efgh5678", pollingInterval = Some(java.time.Duration.ZERO))
          )
        )
        closeTimeout <- rejected(
          OptimizelyProvider.make(
            OptimizelyProviderConfig(
              "abcd1234efgh5678",
              events = OptimizelyEvents.Batched(closeTimeout = java.time.Duration.ofSeconds(5))
            )
          )
        )
      yield assertTrue(placeholder, short, spaces, url, polling, closeTimeout)
    },
    test("Optimizely events are batched by default, with a 3 s shutdown wait") {
      val events = OptimizelyProviderConfig("abcd1234efgh5678").events
      assertTrue(events == OptimizelyEvents.Batched(10, java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(3)))
    },
    test("OptimizelyProvider.scoped accepts a well-formed key and shuts the provider down with the scope") {
      ZIO.scoped(OptimizelyProvider.scoped("abcd1234efgh5678")).as(assertCompletes)
    }
  )
