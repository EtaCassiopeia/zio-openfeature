package zio.openfeature.optimizely

import com.optimizely.ab.OptimizelyHttpClient
import org.apache.http.client.HttpRequestRetryHandler
import org.apache.http.protocol.HttpContext
import java.io.IOException

/** Fail-fast Optimizely HTTP client for the WireMock-backed tests.
  *
  * The SDK's default client retries on I/O errors (sensible against a real CDN with transient blips). In tests the
  * "CDN" is a WireMock server that is stopped at teardown, so a poll in flight at that moment would otherwise retry
  * forever against the dead socket on the poller thread, and — combined with a short polling interval re-scheduling new
  * fetches — keep the test JVM alive until the CI job times out.
  *
  * This client never retries and uses a short socket/connect timeout, so a request against a stopped server fails
  * immediately; the poller thread (which the SDK runs as a daemon) then returns and the JVM can exit. Test-only —
  * production uses the SDK default so transient CDN failures are still retried.
  */
object TestHttpClient {
  private val noRetry: HttpRequestRetryHandler =
    (_: IOException, _: Int, _: HttpContext) => false

  def failFast(timeoutMillis: Int = 800): OptimizelyHttpClient =
    OptimizelyHttpClient
      .builder()
      .withRetryHandler(noRetry)
      .setTimeoutMillis(timeoutMillis)
      .build()
}
