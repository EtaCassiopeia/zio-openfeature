package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProvider
import dev.openfeature.contrib.providers.ofrep.OfrepProviderOptions
import dev.openfeature.sdk.ProviderState
import zio._
import zio.openfeature.{FeatureFlagError, FeatureFlags}
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

  @scala.annotation.nowarn("msg=deprecated")
  private def legacyApply(): OfrepProvider = OFREPProvider()
  @scala.annotation.nowarn("msg=deprecated")
  private def legacyApply(baseUrl: String): OfrepProvider = OFREPProvider(baseUrl)
  @scala.annotation.nowarn("msg=deprecated")
  private def legacyFromOptions(o: OfrepProviderOptions): OfrepProvider = OFREPProvider.fromOptions(o)

  def spec = suite("OFREPProvider factories")(
    suite("legacy throwing factories (deprecated, kept for backwards compatibility)")(
      test("apply() returns an OfrepProvider with ofrep metadata") {
        val provider = legacyApply()
        try
          assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep")) &&
            assertTrue(stateOf(provider) != null)
        finally provider.shutdown()
      },
      test("apply(baseUrl) returns a configured OfrepProvider") {
        val provider = legacyApply("http://localhost:9999")
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
        val provider = legacyFromOptions(opts)
        try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      },
      test("returned provider integrates with FeatureFlags.fromProviderAsync (compile-time check)") {
        val _: ZLayer[Scope, Throwable, FeatureFlags] =
          FeatureFlags.fromProviderAsync(legacyApply("http://localhost:9999"))
        assertCompletes
      }
    ),
    suite("make(baseUrl) — validated construction")(
      test("rejects null baseUrl") {
        for {
          result <- OFREPProvider.make(null: String).either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("null"))
        )
      },
      test("rejects empty baseUrl") {
        for {
          result <- OFREPProvider.make("").either
        } yield assertTrue(result.isLeft, result.left.exists(_.message.toLowerCase.contains("empty")))
      },
      test("rejects whitespace-only baseUrl") {
        for {
          result <- OFREPProvider.make("   ").either
        } yield assertTrue(result.isLeft, result.left.exists(_.message.toLowerCase.contains("empty")))
      },
      test("rejects unsupported scheme (ftp)") {
        for {
          result <- OFREPProvider.make("ftp://example.com").either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("unsupported scheme"))
        )
      },
      test("rejects malformed URL") {
        for {
          result <- OFREPProvider.make("not a url at all").either
        } yield assertTrue(result.isLeft)
      },
      test("rejects URL with no host") {
        for {
          result <- OFREPProvider.make("http:///path").either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("no host"))
        )
      },
      test("accepts valid http URL") {
        for {
          provider <- OFREPProvider.make("http://localhost:9999")
        } yield try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      },
      test("accepts valid https URL with path") {
        for {
          provider <- OFREPProvider.make("https://flags.example.com/api/ofrep")
        } yield try assertCompletes
        finally provider.shutdown()
      },
      test("accepts URL with uppercase scheme") {
        for {
          provider <- OFREPProvider.make("HTTPS://flags.example.com")
        } yield try assertCompletes
        finally provider.shutdown()
      }
    ),
    suite("make(OfrepProviderOptions)")(
      test("rejects options with bad scheme") {
        val opts = OfrepProviderOptions.builder().baseUrl("ftp://flags.example.com").build()
        for {
          result <- OFREPProvider.make(opts).either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("unsupported scheme"))
        )
      },
      test("accepts options with a valid baseUrl") {
        val opts = OfrepProviderOptions
          .builder()
          .baseUrl("http://localhost:9999")
          .requestTimeout(Duration.ofSeconds(5))
          .build()
        for {
          provider <- OFREPProvider.make(opts)
        } yield try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      }
    ),
    suite("layer")(
      test("layer wraps make and produces a typed error on bad input") {
        val build = ZIO.scoped(OFREPProvider.layer("ftp://example.com").build).either
        for {
          result <- build
        } yield assertTrue(
          result.isLeft,
          // ZLayer build wraps the typed error inside a Cause; we check the leaf type.
          result.left.exists(_.isInstanceOf[FeatureFlagError.InvalidConfiguration])
        )
      },
      test("layer accepts a valid URL and yields an OfrepProvider in the environment") {
        val build = ZIO.scoped(
          OFREPProvider
            .layer("http://localhost:9999")
            .build
            .map(_.get[OfrepProvider])
        )
        for {
          provider <- build
        } yield try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      }
    )
  )
}
