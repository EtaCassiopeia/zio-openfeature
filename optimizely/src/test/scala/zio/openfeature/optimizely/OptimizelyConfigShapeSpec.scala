package zio.openfeature.optimizely

import zio._
import zio.test._

import java.time.{Duration => JDuration}

object OptimizelyConfigShapeSpec extends ZIOSpecDefault {

  private val key = "abcd1234efgh5678"

  def spec = suite("OptimizelyProviderConfig")(
    test("apply takes the SDK key and carries the documented defaults") {
      val c = OptimizelyProviderConfig(key)
      assertTrue(
        c.sdkKey == key,
        c.datafileUrl.isEmpty,
        c.initWait == OptimizelyProvider.DefaultInitWait,
        c.pollingInterval.isEmpty,
        c.blockingTimeout.isEmpty,
        c.staleAfter.isEmpty,
        c.events == OptimizelyEvents.Batched()
      )
    },
    test("each with* returns a copy and leaves the receiver as it was") {
      val base    = OptimizelyProviderConfig(key)
      val changed = base
        .withSdkKey("other-key-123")
        .withDatafileUrl("https://agent.example.com/datafile")
        .withInitWait(JDuration.ofSeconds(3))
        .withPollingInterval(JDuration.ofMinutes(1))
        .withBlockingTimeout(JDuration.ofSeconds(2))
        .withStaleAfter(JDuration.ofMinutes(5))
        .withEvents(OptimizelyEvents.Disabled)
      assertTrue(
        changed.sdkKey == "other-key-123",
        changed.datafileUrl.contains("https://agent.example.com/datafile"),
        changed.initWait == JDuration.ofSeconds(3),
        changed.pollingInterval.contains(JDuration.ofMinutes(1)),
        changed.blockingTimeout.contains(JDuration.ofSeconds(2)),
        changed.staleAfter.contains(JDuration.ofMinutes(5)),
        changed.events == OptimizelyEvents.Disabled,
        base.sdkKey == key,
        base.datafileUrl.isEmpty,
        base.events == OptimizelyEvents.Batched()
      )
    },
    test("a value set through a with* is validated when the provider is built, as it was in the constructor") {
      for {
        badKey     <- OptimizelyProvider.make(OptimizelyProviderConfig(key).withSdkKey("")).exit
        badPolling <- OptimizelyProvider.make(OptimizelyProviderConfig(key).withPollingInterval(JDuration.ZERO)).exit
        badStale <- OptimizelyProvider.make(OptimizelyProviderConfig(key).withStaleAfter(JDuration.ofSeconds(-1))).exit
      } yield assertTrue(badKey.isFailure, badPolling.isFailure, badStale.isFailure)
    },
    test("toString names the fields and keeps the SDK key out") {
      val text = OptimizelyProviderConfig(key).withPollingInterval(JDuration.ofMinutes(1)).toString
      assertTrue(
        text.startsWith("OptimizelyProviderConfig(sdkKey=<redacted>, datafileUrl=None"),
        text.contains("pollingInterval=Some(PT1M)"),
        !text.contains(key)
      )
    }
  )
}
