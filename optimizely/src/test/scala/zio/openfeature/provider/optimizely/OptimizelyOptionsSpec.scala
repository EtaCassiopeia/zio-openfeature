package zio.openfeature.provider.optimizely

import zio.test.*
import zio.test.Assertion.*

import scala.concurrent.duration.*

object OptimizelyOptionsSpec extends ZIOSpecDefault:

  // Use explicit Duration to avoid ambiguity with ZIO Duration
  private val fiveMinutes   = Duration(5, MINUTES)
  private val tenSeconds    = Duration(10, SECONDS)
  private val thirtySeconds = Duration(30, SECONDS)
  private val sixtySeconds  = Duration(60, SECONDS)
  private val twoMinutes    = Duration(2, MINUTES)

  def spec = suite("OptimizelyOptionsSpec")(
    suite("construction")(
      test("creates options with SDK key only") {
        val options = OptimizelyOptions("test-sdk-key")
        assertTrue(
          options.sdkKey == "test-sdk-key",
          options.accessToken.isEmpty,
          options.pollingInterval == fiveMinutes,
          options.blockingTimeout == tenSeconds
        )
      },
      test("creates options with SDK key and access token") {
        val options = OptimizelyOptions("test-sdk-key", "test-token")
        assertTrue(
          options.sdkKey == "test-sdk-key",
          options.accessToken.contains("test-token")
        )
      },
      test("creates options with all parameters") {
        val options = OptimizelyOptions("sdk", "token", 30)
        assertTrue(
          options.sdkKey == "sdk",
          options.accessToken.contains("token"),
          options.pollingInterval == thirtySeconds
        )
      },
      test("fails for empty SDK key") {
        assertTrue(
          scala.util.Try(OptimizelyOptions("")).isFailure
        )
      },
      test("fails for negative polling interval") {
        assertTrue(
          scala.util
            .Try(
              OptimizelyOptions("sdk", None, Duration(-1, SECONDS), tenSeconds)
            )
            .isFailure
        )
      }
    ),
    suite("builder")(
      test("builds options with all settings") {
        val options = OptimizelyOptions
          .builder()
          .withSdkKey("builder-sdk-key")
          .withAccessToken("builder-token")
          .withPollingIntervalSeconds(60)
          .withBlockingTimeout(sixtySeconds)
          .build()

        assertTrue(
          options.sdkKey == "builder-sdk-key",
          options.accessToken.contains("builder-token"),
          options.pollingInterval == sixtySeconds,
          options.blockingTimeout == sixtySeconds
        )
      },
      test("builds options with SDK key only") {
        val options = OptimizelyOptions
          .builder()
          .withSdkKey("minimal-key")
          .build()

        assertTrue(
          options.sdkKey == "minimal-key",
          options.accessToken.isEmpty
        )
      },
      test("uses duration for polling interval") {
        val options = OptimizelyOptions
          .builder()
          .withSdkKey("key")
          .withPollingInterval(twoMinutes)
          .build()

        assertTrue(options.pollingInterval == twoMinutes)
      }
    )
  )
