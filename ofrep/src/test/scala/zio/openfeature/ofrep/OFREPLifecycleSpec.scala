package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProvider
import zio._
import zio.openfeature.FeatureFlagError
import zio.test._

import java.time.Duration
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

/** Covers the two #268 gaps: the `layer`/`scoped` shutdown finalizer (Fix 1) and the `OFREPProviderConfig` factory (Fix
  * 2). The load-bearing property throughout is that no construction path leaks a non-daemon pool: if any test here
  * hangs at suite teardown, a pool was orphaned.
  */
object OFREPLifecycleSpec extends ZIOSpecDefault {

  private val ValidUrl = "http://localhost:9999"

  // A daemon executor we can hand to the provider via options and then observe: after the scope closes, the layer's
  // finalizer must have called shutdown() on the provider, which terminates this executor. Daemon so a regression
  // (finalizer not firing) surfaces as a failed assertion rather than a hung JVM.
  private def daemonExecutor(): ExecutorService = {
    val tf: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, "ofrep-lifecycle-test")
      t.setDaemon(true)
      t
    }
    Executors.newCachedThreadPool(tf)
  }

  def spec = suite("OFREPProvider lifecycle & config")(
    suite("Fix 1 — layer/scoped own the provider lifecycle")(
      test("scoped(baseUrl) builds and releases without hanging") {
        for {
          _ <- ZIO.scoped(OFREPProvider.scoped(ValidUrl).unit)
        } yield assertCompletes
      },
      test("layer(baseUrl) builds and releases without hanging") {
        for {
          _ <- ZIO.scoped(OFREPProvider.layer(ValidUrl).build.unit)
        } yield assertCompletes
      },
      test("layer(options) shuts the provider down on scope close (executor terminated)") {
        val exec = daemonExecutor()
        val opts = OFREPProvider.daemonOptionsBuilder().baseUrl(ValidUrl).executor(exec).build()
        for {
          // While the scope is open the executor is live; after it closes the finalizer's shutdown() terminates it.
          _          <- ZIO.scoped(OFREPProvider.layer(opts).build.unit)
          shutdown   <- ZIO.succeed(exec.isShutdown)
          terminated <- ZIO.attemptBlocking(exec.awaitTermination(5, TimeUnit.SECONDS)).orDie
        } yield assertTrue(shutdown, terminated)
      },
      test("layer(config) builds and releases without hanging") {
        val config = OFREPProviderConfig(ValidUrl, requestTimeout = Some(Duration.ofSeconds(3)))
        for {
          _ <- ZIO.scoped(OFREPProvider.layer(config).build.unit)
        } yield assertCompletes
      }
    ),
    suite("Fix 2 — OFREPProviderConfig factory")(
      test("make(config) with timeouts + a header builds an OFREP provider") {
        val config = OFREPProviderConfig(
          baseUrl = ValidUrl,
          requestTimeout = Some(Duration.ofSeconds(5)),
          connectTimeout = Some(Duration.ofSeconds(2)),
          headers = Map("Authorization" -> List("Bearer test-token"))
        )
        for {
          provider <- OFREPProvider.make(config)
        } yield try assertTrue(provider.getMetadata.getName.toLowerCase.contains("ofrep"))
        finally provider.shutdown()
      },
      test("make(config) with a proxy selector builds an OFREP provider") {
        val proxy  = java.net.ProxySelector.getDefault
        val config = OFREPProviderConfig(baseUrl = ValidUrl, proxy = Some(proxy))
        for {
          provider <- OFREPProvider.make(config)
        } yield try assertCompletes
        finally provider.shutdown()
      },
      test("make(config) with an invalid baseUrl fails fast with InvalidConfiguration (no pool leak)") {
        // Validation runs before any executor/options are built, so the rejection cannot orphan a pool.
        val config =
          OFREPProviderConfig(baseUrl = "ftp://flags.example.com", requestTimeout = Some(Duration.ofSeconds(5)))
        for {
          result <- OFREPProvider.make(config).either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.isInstanceOf[FeatureFlagError.InvalidConfiguration]),
          result.left.exists(_.message.toLowerCase.contains("unsupported scheme"))
        )
      },
      test("make(config) with a null baseUrl fails fast with InvalidConfiguration") {
        val config = OFREPProviderConfig(baseUrl = null)
        for {
          result <- OFREPProvider.make(config).either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("null"))
        )
      },
      test("scoped(config) builds and releases without hanging") {
        val config = OFREPProviderConfig(ValidUrl, headers = Map("X-Env" -> List("test")))
        for {
          _ <- ZIO.scoped(OFREPProvider.scoped(config).unit)
        } yield assertCompletes
      }
    )
  )
}
