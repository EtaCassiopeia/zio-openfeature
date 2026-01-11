package zio.openfeature.provider.optimizely

import scala.concurrent.duration.*

/** Configuration options for the Optimizely provider.
  *
  * @param sdkKey
  *   The Optimizely SDK key (required)
  * @param accessToken
  *   Optional datafile access token for authenticated polling (required for some Optimizely configurations)
  * @param pollingInterval
  *   How often to poll for datafile updates (default: 5 minutes)
  * @param blockingTimeout
  *   How long to wait for initial datafile fetch (default: 10 seconds)
  */
final case class OptimizelyOptions(
  sdkKey: String,
  accessToken: Option[String] = None,
  pollingInterval: Duration = 5.minutes,
  blockingTimeout: Duration = 10.seconds
):
  require(sdkKey.nonEmpty, "SDK key must not be empty")
  require(pollingInterval > Duration.Zero, "Polling interval must be positive")
  require(blockingTimeout >= Duration.Zero, "Blocking timeout must be non-negative")

object OptimizelyOptions:
  /** Create options with just an SDK key (for public datafiles) */
  def apply(sdkKey: String): OptimizelyOptions =
    new OptimizelyOptions(sdkKey)

  /** Create options with SDK key and access token (for authenticated datafiles) */
  def apply(sdkKey: String, accessToken: String): OptimizelyOptions =
    new OptimizelyOptions(sdkKey, Some(accessToken))

  /** Create options with SDK key, access token, and custom polling interval */
  def apply(sdkKey: String, accessToken: String, pollingIntervalSeconds: Int): OptimizelyOptions =
    new OptimizelyOptions(
      sdkKey,
      Some(accessToken),
      pollingIntervalSeconds.seconds
    )

  /** Builder for OptimizelyOptions */
  final class Builder private[OptimizelyOptions] ():
    private var sdkKey: String              = ""
    private var accessToken: Option[String] = None
    private var pollingInterval: Duration   = 5.minutes
    private var blockingTimeout: Duration   = 10.seconds

    def withSdkKey(key: String): Builder =
      sdkKey = key
      this

    def withAccessToken(token: String): Builder =
      accessToken = Some(token)
      this

    def withPollingInterval(interval: Duration): Builder =
      pollingInterval = interval
      this

    def withPollingIntervalSeconds(seconds: Int): Builder =
      pollingInterval = seconds.seconds
      this

    def withBlockingTimeout(timeout: Duration): Builder =
      blockingTimeout = timeout
      this

    def build(): OptimizelyOptions =
      OptimizelyOptions(sdkKey, accessToken, pollingInterval, blockingTimeout)

  def builder(): Builder = new Builder()
