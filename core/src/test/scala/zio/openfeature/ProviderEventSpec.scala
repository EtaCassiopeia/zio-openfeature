package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*

object ProviderEventSpec extends ZIOSpecDefault:

  val testMetadata = ProviderMetadata("TestProvider", "1.0")

  def spec = suite("ProviderEventSpec")(
    suite("ProviderMetadata")(
      test("toString without version") {
        val meta = ProviderMetadata("MyProvider", None)
        assertTrue(meta.toString == "MyProvider")
      },
      test("toString with version") {
        val meta = ProviderMetadata("MyProvider", Some("2.0"))
        assertTrue(meta.toString == "MyProvider v2.0")
      },
      test("apply with version string") {
        val meta = ProviderMetadata("MyProvider", "1.5")
        assertTrue(meta.version == Some("1.5"))
      }
    ),
    suite("ProviderEvent metadata extension")(
      test("Ready event has metadata") {
        val event = ProviderEvent.Ready(testMetadata)
        assertTrue(event.metadata == testMetadata)
      },
      test("Error event has metadata") {
        val event = ProviderEvent.Error(new RuntimeException("test"), testMetadata)
        assertTrue(event.metadata == testMetadata)
      },
      test("Stale event has metadata") {
        val event = ProviderEvent.Stale("cache expired", testMetadata)
        assertTrue(event.metadata == testMetadata)
      },
      test("ConfigurationChanged event has metadata") {
        val event = ProviderEvent.ConfigurationChanged(Set("flag1", "flag2"), testMetadata)
        assertTrue(event.metadata == testMetadata)
      },
      test("Reconnecting event has metadata") {
        val event = ProviderEvent.Reconnecting(testMetadata)
        assertTrue(event.metadata == testMetadata)
      }
    ),
    suite("ProviderEvent isError extension")(
      test("Error event is error") {
        val event = ProviderEvent.Error(new RuntimeException("test"), testMetadata)
        assertTrue(event.isError)
      },
      test("Ready event is not error") {
        val event = ProviderEvent.Ready(testMetadata)
        assertTrue(!event.isError)
      },
      test("Stale event is not error") {
        val event = ProviderEvent.Stale("reason", testMetadata)
        assertTrue(!event.isError)
      },
      test("ConfigurationChanged event is not error") {
        val event = ProviderEvent.ConfigurationChanged(Set.empty, testMetadata)
        assertTrue(!event.isError)
      },
      test("Reconnecting event is not error") {
        val event = ProviderEvent.Reconnecting(testMetadata)
        assertTrue(!event.isError)
      }
    ),
    suite("ProviderEvent isHealthy extension")(
      test("Ready event is healthy") {
        val event = ProviderEvent.Ready(testMetadata)
        assertTrue(event.isHealthy)
      },
      test("ConfigurationChanged event is healthy") {
        val event = ProviderEvent.ConfigurationChanged(Set.empty, testMetadata)
        assertTrue(event.isHealthy)
      },
      test("Error event is not healthy") {
        val event = ProviderEvent.Error(new RuntimeException("test"), testMetadata)
        assertTrue(!event.isHealthy)
      },
      test("Stale event is not healthy") {
        val event = ProviderEvent.Stale("reason", testMetadata)
        assertTrue(!event.isHealthy)
      },
      test("Reconnecting event is not healthy") {
        val event = ProviderEvent.Reconnecting(testMetadata)
        assertTrue(!event.isHealthy)
      }
    )
  )
