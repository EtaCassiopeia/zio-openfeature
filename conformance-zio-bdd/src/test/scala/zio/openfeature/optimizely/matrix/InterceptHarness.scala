package zio.openfeature.optimizely.matrix

import zio._
import zio.bdd.mock._
import zio.bdd.mock.rift.embedded.EmbeddedRift
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients

/** Builds a per-scenario `ZLayer[Any, Throwable, FeatureFlags]` in which the Optimizely SDK fetches its
  * datafile over its REAL default CDN URL (`https://cdn.optimizely.com/datafiles/<key>.json`, `datafileUrl =
  * None`) — the datafile is delivered by Rift's TLS-MITM intercept engine instead of the real CDN.
  *
  * This exercises the production transport path (default URL template + HTTPS + TLS trust) that the direct-URL
  * matrix suites (`MatrixHarness`) deliberately bypass by overriding `datafileUrl`.
  *
  * Everything is per-provider-instance — the SDK is handed an `OptimizelyHttpClient` that trusts Rift's CA and
  * routes through the intercept listener. No `javax.net.ssl.trustStore` / `https.proxyHost` system properties are
  * set, so nothing leaks across suites (the JVM-global-proxy leakage that #278 removed stays gone).
  *
  * Lives in package `zio.openfeature.optimizely.matrix` so it can inject the client via the
  * `private[optimizely]` `OptimizelyProvider.scoped(config, httpClient)` seam.
  */
object InterceptHarness {

  private val CdnHost = "cdn.optimizely.com"

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile fixture not found on classpath: $path")
    try scala.io.Source.fromInputStream(is).mkString
    finally is.close()
  }

  /** Build an SSLContext that trusts the CA behind Rift's intercept-generated (PKCS12) truststore. */
  private def sslContextTrusting(trust: TrustStore): SSLContext = {
    val ks  = KeyStore.getInstance("PKCS12")
    val fis = new FileInputStream(trust.path.toFile)
    try ks.load(fis, trust.password.toCharArray)
    finally fis.close()
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)
    ctx
  }

  def layerForDatafile(name: String): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        mc <- (Provisioning.live >>> EmbeddedRift.layer(EmbeddedRift.InterceptConfig())).build
                .map(_.get[MockControl])
                .mapError(e => new RuntimeException(s"intercept engine init failed: $e"))
        icept <- mc.intercept.mapError(e => new RuntimeException(s"intercept unsupported: $e"))
        _ <- icept
               .respondWith(
                 CdnHost,
                 InterceptStub(200, Map("Content-Type" -> "application/json"), Some(loadDatafile(name)))
               )
               .mapError(e => new RuntimeException(s"intercept respondWith failed: $e"))
        endpoint <- icept.proxyEndpoint.mapError(e => new RuntimeException(s"proxyEndpoint failed: $e"))
        (proxyHost, proxyPort) = endpoint
        trust <- icept.trustStore().mapError(e => new RuntimeException(s"trustStore export failed: $e"))
        ssl   <- ZIO.attemptBlocking(sslContextTrusting(trust)).mapError(e => new RuntimeException(s"SSLContext build failed: $e"))
        // Explicit connect/socket timeouts: bypassing OptimizelyHttpClient.Builder also drops its default 10s
        // timeouts, so an unreachable intercept endpoint would otherwise only fail via the provider's init latch.
        // These make a broken MITM fail fast at the HTTP layer instead.
        requestConfig = RequestConfig
                          .custom()
                          .setConnectTimeout(5000)
                          .setSocketTimeout(5000)
                          .setConnectionRequestTimeout(5000)
                          .build()
        apache <- ZIO
                    .attempt(
                      HttpClients
                        .custom()
                        .setSSLContext(ssl)
                        .setProxy(new HttpHost(proxyHost, proxyPort.toInt))
                        .setDefaultRequestConfig(requestConfig)
                        .build()
                    )
                    .mapError(e => new RuntimeException(s"HTTP client build failed: $e"))
        // Bound the close like the production releaseProvider: a client wedged mid-handshake must not hang scope
        // teardown, and finalizers run uninterruptibly, so `.disconnect` lets the timeout still fire.
        _ <- ZIO.addFinalizer(
               ZIO.attempt(apache.close()).disconnect.timeout(5.seconds).ignore
             )
        optClient <- ZIO.succeed(com.optimizely.ab.OptimizelyHttpClients.fromApache(apache))
        config = OptimizelyProviderConfig(
                   sdkKey = "intercept-matrix-key",
                   datafileUrl = None, // real default CDN URL — MITM'd by Rift
                   initWait = java.time.Duration.ofSeconds(10),
                   pollingInterval = Some(java.time.Duration.ofSeconds(3600)),
                   blockingTimeout = Some(java.time.Duration.ofSeconds(5))
                 )
        provider <- OptimizelyProvider.scoped(config, Some(optClient)).mapError(e => new RuntimeException(e.message))
        domain    = s"intercept-matrix-${java.util.UUID.randomUUID()}"
        env      <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain(domain)).build
      } yield env.get[FeatureFlags]
    }
}
