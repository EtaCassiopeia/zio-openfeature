package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{get, okJson, urlEqualTo}
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import com.optimizely.ab.notification.NotificationCenter

import java.util.concurrent.TimeUnit

/** Shared setup for the WireMock-backed Optimizely specs. */
object OptimizelyTestSupport {

  /** initWait for a datafile load the test expects to succeed, in the specs that test the fetch itself. The wait ends
    * as soon as the datafile arrives, so a long window costs nothing on a passing test. Specs that only need a ready
    * provider seed the datafile instead ([[readyClient]]) and never wait on the fetch.
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
    * @param datafile
    *   a datafile the manager starts with, before its first fetch: `getConfig` returns at once and the client is valid,
    *   so `initialize` reaches READY without waiting for the poller. Its first fetch of the same revision changes
    *   nothing and emits no configuration change.
    */
  def wiredClient(
    datafileUrl: String,
    sdkKey: String,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofHours(1),
    socketTimeoutMillis: Int = SocketTimeoutMillis,
    deferStart: Boolean = false,
    customize: Optimizely.Builder => Optimizely.Builder = identity,
    datafile: Option[String] = None
  ): Optimizely =
    wiredClientWithManager(
      datafileUrl,
      sdkKey,
      blockingTimeout,
      pollingInterval,
      socketTimeoutMillis,
      deferStart,
      customize,
      datafile
    )._1

  /** [[wiredClient]], also returning the manager, for specs that hand it to the provider as `configManager`. */
  def wiredClientWithManager(
    datafileUrl: String,
    sdkKey: String,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofHours(1),
    socketTimeoutMillis: Int = SocketTimeoutMillis,
    deferStart: Boolean = false,
    customize: Optimizely.Builder => Optimizely.Builder = identity,
    datafile: Option[String] = None
  ): (Optimizely, HttpProjectConfigManager) = {
    val center  = new NotificationCenter()
    val builder = HttpProjectConfigManager.builder()
    datafile.foreach(builder.withDatafile)
    val manager = builder
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

  /** A client for a spec that only needs a ready provider: `path` on `server` serves `json`, and the manager starts
    * with the same datafile, so readiness never rides on the fetch. Specs that test the fetch, its timeouts or its
    * failure use [[wiredClient]] without a datafile.
    */
  def readyClient(
    server: WireMockServer,
    path: String,
    json: String,
    sdkKey: String,
    customize: Optimizely.Builder => Optimizely.Builder = identity
  ): Optimizely =
    readyClientWithManager(server, path, json, sdkKey, customize)._1

  /** [[readyClient]], also returning the manager. */
  def readyClientWithManager(
    server: WireMockServer,
    path: String,
    json: String,
    sdkKey: String,
    customize: Optimizely.Builder => Optimizely.Builder = identity
  ): (Optimizely, HttpProjectConfigManager) = {
    server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json)))
    wiredClientWithManager(
      s"http://localhost:${server.port()}$path",
      sdkKey,
      customize = customize,
      datafile = Some(json)
    )
  }
}
