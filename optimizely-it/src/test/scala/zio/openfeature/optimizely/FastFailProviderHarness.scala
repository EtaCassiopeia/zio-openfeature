package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager

import java.time.Duration
import java.util.concurrent.TimeUnit

/** Test-only helper that lives in `zio.openfeature.optimizely` so it can construct an `OptimizelyFeatureProvider`
  * directly via the package-private constructor with a custom blocking timeout — needed for the auth/transport-failure
  * IT spec, where toxic-induced latencies have to exceed the SDK's `getConfig` blocking timeout for `isValid` to
  * actually report false before our outer init-wait fires.
  *
  * Not part of the public API; built by the `optimizely-it` test sources only.
  */
object FastFailProviderHarness {

  /** Build an Optimizely client pointed at `datafileUrl` with a short blocking timeout so transport-fault tests fail
    * fast instead of being absorbed by the SDK's default 10-second wait.
    */
  def buildFastFailClient(
    sdkKey: String,
    datafileUrl: String,
    blockingTimeout: Duration = Duration.ofMillis(300),
    pollingInterval: Duration = Duration.ofSeconds(3600)
  ): Optimizely = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey(sdkKey)
      .withUrl(datafileUrl)
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
      .build()
    Optimizely.builder().withConfigManager(mgr).build()
  }

  /** Build a provider directly via the package-private constructor with the specified `initWait` and
    * `closeOnShutdown=true`. Use with `buildFastFailClient` for failure-mode tests.
    */
  def newProvider(
    client: Optimizely,
    initWait: Duration
  ): OptimizelyFeatureProvider =
    new OptimizelyFeatureProvider(client, initWait, closeOnShutdown = true)
}
