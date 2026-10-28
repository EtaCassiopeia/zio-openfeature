package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import com.optimizely.ab.notification.NotificationCenter

import java.util.concurrent.TimeUnit

/** Shared setup for the WireMock-backed Optimizely specs. */
object OptimizelyTestSupport {

  /** initWait for a datafile load the test expects to succeed. The wait ends as soon as the datafile arrives, so a long
    * window costs nothing on a passing test and absorbs JVM stalls under parallel builds.
    */
  val InitWait: java.time.Duration = java.time.Duration.ofSeconds(15)

  /** Socket timeout for the datafile fetch. WireMock runs in the test JVM, so a stall delays its answer too; with 5s, a
    * load was still lost under parallel builds. Matching [[InitWait]] lets the fetch use the whole window.
    */
  val SocketTimeoutMillis: Int = InitWait.toMillis.toInt

  /** A client wired the way production `OptimizelyProvider.buildClient` wires it: one NotificationCenter shared by the
    * config manager and the client, so the datafile-update notification drives initialize() instead of a single
    * blocking `isValid` window that a slow fetch under load can miss.
    *
    * The HTTP client never retries and polls hourly (see `TestHttpClient`), so a failed first fetch fails the load for
    * good; the socket timeout is therefore as long as [[InitWait]]. A request against a stopped server still fails at
    * once (connection refused).
    *
    * @param deferStart
    *   `build(true)`: start the poller without blocking on the first fetch, as production does. The default `build()`
    *   blocks for up to `blockingTimeout` on it.
    * @param customize
    *   extra settings for the client builder (an event processor, a CMAB service)
    */
  def wiredClient(
    datafileUrl: String,
    sdkKey: String,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofHours(1),
    socketTimeoutMillis: Int = SocketTimeoutMillis,
    deferStart: Boolean = false,
    customize: Optimizely.Builder => Optimizely.Builder = identity
  ): Optimizely =
    wiredClientWithManager(
      datafileUrl,
      sdkKey,
      blockingTimeout,
      pollingInterval,
      socketTimeoutMillis,
      deferStart,
      customize
    )._1

  /** [[wiredClient]], also returning the manager, for specs that hand it to the provider as `configManager`. */
  def wiredClientWithManager(
    datafileUrl: String,
    sdkKey: String,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofHours(1),
    socketTimeoutMillis: Int = SocketTimeoutMillis,
    deferStart: Boolean = false,
    customize: Optimizely.Builder => Optimizely.Builder = identity
  ): (Optimizely, HttpProjectConfigManager) = {
    val center = new NotificationCenter()
    val manager = HttpProjectConfigManager
      .builder()
      .withSdkKey(sdkKey)
      .withUrl(datafileUrl)
      .withNotificationCenter(center)
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toMillis, TimeUnit.MILLISECONDS)
      .withOptimizelyHttpClient(TestHttpClient.failFast(socketTimeoutMillis))
      .build(deferStart)
    val client = customize(Optimizely.builder().withConfigManager(manager).withNotificationCenter(center)).build()
    (client, manager)
  }
}
