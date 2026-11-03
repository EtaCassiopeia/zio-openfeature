package zio.openfeature.ofrep

import zio._
import zio.openfeature.FeatureFlagError
import zio.test._
import java.time.Duration

object OFREPProviderSpec extends ZIOSpecDefault {

  def spec = suite("OFREPProvider factories")(
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
        } yield
          try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
          finally provider.shutdown()
      },
      test("accepts valid https URL with path") {
        for {
          provider <- OFREPProvider.make("https://flags.example.com/api/ofrep")
        } yield
          try assertCompletes
          finally provider.shutdown()
      },
      test("accepts URL with uppercase scheme") {
        for {
          provider <- OFREPProvider.make("HTTPS://flags.example.com")
        } yield
          try assertCompletes
          finally provider.shutdown()
      }
    ),
    suite("make(OfrepProviderOptions)")(
      test("rejects options with bad scheme") {
        // Use daemonOptionsBuilder() instead of OfrepProviderOptions.builder(): `make` validates and rejects the
        // ftp scheme BEFORE constructing a provider, so shutdown() never runs. The contrib default builder would
        // orphan a non-daemon 5-thread pool here and hang `sbt +test`.
        val opts = OFREPProvider.daemonOptionsBuilder().baseUrl("ftp://flags.example.com").build()
        for {
          result <- OFREPProvider.make(opts).either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("unsupported scheme"))
        )
      },
      test("accepts options with a valid baseUrl") {
        val opts = OFREPProvider
          .daemonOptionsBuilder()
          .baseUrl("http://localhost:9999")
          .requestTimeout(Duration.ofSeconds(5))
          .build()
        for {
          provider <- OFREPProvider.make(opts)
        } yield
          try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
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
      test("layer accepts a valid URL and yields an OFREPFeatureProvider in the environment") {
        val build = ZIO.scoped(
          OFREPProvider
            .layer("http://localhost:9999")
            .build
            .map(_.get[OFREPFeatureProvider])
        )
        for {
          provider <- build
        } yield
          try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
          finally provider.shutdown()
      }
    )
  )
}
