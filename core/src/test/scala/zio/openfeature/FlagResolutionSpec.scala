package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*

object FlagResolutionSpec extends ZIOSpecDefault:

  def spec = suite("FlagResolutionSpec")(
    suite("ResolutionReason enum")(
      test("all values exist") {
        assertTrue(ResolutionReason.Static != null) &&
        assertTrue(ResolutionReason.Default != null) &&
        assertTrue(ResolutionReason.TargetingMatch != null) &&
        assertTrue(ResolutionReason.Split != null) &&
        assertTrue(ResolutionReason.Cached != null) &&
        assertTrue(ResolutionReason.Disabled != null) &&
        assertTrue(ResolutionReason.Unknown != null) &&
        assertTrue(ResolutionReason.Error != null)
      }
    ),
    suite("FlagMetadata")(
      test("empty metadata") {
        val meta = FlagMetadata.empty
        assertTrue(meta.isEmpty) &&
        assertTrue(!meta.nonEmpty) &&
        assertTrue(meta.get("key") == None)
      },
      test("metadata with values") {
        val meta = FlagMetadata("key1" -> "value1", "key2" -> "value2")
        assertTrue(!meta.isEmpty) &&
        assertTrue(meta.nonEmpty) &&
        assertTrue(meta.get("key1") == Some("value1")) &&
        assertTrue(meta.get("key2") == Some("value2")) &&
        assertTrue(meta.get("missing") == None)
      },
      test("metadata from map") {
        val meta = FlagMetadata(Map("a" -> "b"))
        assertTrue(meta.get("a") == Some("b"))
      }
    ),
    suite("ErrorCode enum")(
      test("all error codes exist") {
        assertTrue(ErrorCode.ProviderNotReady != null) &&
        assertTrue(ErrorCode.FlagNotFound != null) &&
        assertTrue(ErrorCode.ParseError != null) &&
        assertTrue(ErrorCode.TypeMismatch != null) &&
        assertTrue(ErrorCode.TargetingKeyMissing != null) &&
        assertTrue(ErrorCode.InvalidContext != null) &&
        assertTrue(ErrorCode.General != null)
      }
    ),
    suite("FlagResolution")(
      test("isError returns true when errorCode is set") {
        val resolution = FlagResolution(
          value = false,
          variant = None,
          reason = ResolutionReason.Default,
          metadata = FlagMetadata.empty,
          flagKey = "test",
          errorCode = Some(ErrorCode.FlagNotFound),
          errorMessage = Some("Not found")
        )
        assertTrue(resolution.isError) &&
        assertTrue(!resolution.isSuccess)
      },
      test("isError returns true when reason is Error") {
        val resolution = FlagResolution(
          value = false,
          variant = None,
          reason = ResolutionReason.Error,
          metadata = FlagMetadata.empty,
          flagKey = "test"
        )
        assertTrue(resolution.isError) &&
        assertTrue(!resolution.isSuccess)
      },
      test("isSuccess returns true for normal resolution") {
        val resolution = FlagResolution.targetingMatch("test", true)
        assertTrue(resolution.isSuccess) &&
        assertTrue(!resolution.isError)
      },
      test("isDefault returns true for default reason") {
        val resolution = FlagResolution.default("test", false)
        assertTrue(resolution.isDefault)
      },
      test("isDefault returns false for targeting match") {
        val resolution = FlagResolution.targetingMatch("test", true)
        assertTrue(!resolution.isDefault)
      },
      test("isCached returns true for cached reason") {
        val resolution = FlagResolution.cached("test", "value")
        assertTrue(resolution.isCached)
      },
      test("isCached returns false for other reasons") {
        val resolution = FlagResolution.default("test", false)
        assertTrue(!resolution.isCached)
      },
      test("map transforms value") {
        val resolution = FlagResolution.targetingMatch("test", 42)
        val mapped     = resolution.map(_ * 2)
        assertTrue(mapped.value == 84) &&
        assertTrue(mapped.flagKey == "test") &&
        assertTrue(mapped.reason == ResolutionReason.TargetingMatch)
      }
    ),
    suite("FlagResolution factory methods")(
      test("targetingMatch creates correct resolution") {
        val resolution = FlagResolution.targetingMatch("my-flag", true, Some("variant-a"))
        assertTrue(resolution.value == true) &&
        assertTrue(resolution.flagKey == "my-flag") &&
        assertTrue(resolution.variant == Some("variant-a")) &&
        assertTrue(resolution.reason == ResolutionReason.TargetingMatch) &&
        assertTrue(resolution.errorCode == None)
      },
      test("targetingMatch with metadata") {
        val meta       = FlagMetadata("source" -> "config")
        val resolution = FlagResolution.targetingMatch("my-flag", "value", None, meta)
        assertTrue(resolution.metadata.get("source") == Some("config"))
      },
      test("default creates correct resolution") {
        val resolution = FlagResolution.default("my-flag", 42)
        assertTrue(resolution.value == 42) &&
        assertTrue(resolution.flagKey == "my-flag") &&
        assertTrue(resolution.variant == None) &&
        assertTrue(resolution.reason == ResolutionReason.Default) &&
        assertTrue(resolution.metadata.isEmpty)
      },
      test("cached creates correct resolution") {
        val resolution = FlagResolution.cached("my-flag", Map("key" -> "value"))
        assertTrue(resolution.value == Map("key" -> "value")) &&
        assertTrue(resolution.reason == ResolutionReason.Cached)
      },
      test("error creates correct resolution") {
        val resolution = FlagResolution.error("my-flag", false, ErrorCode.FlagNotFound, "Flag not found")
        assertTrue(resolution.value == false) &&
        assertTrue(resolution.reason == ResolutionReason.Error) &&
        assertTrue(resolution.errorCode == Some(ErrorCode.FlagNotFound)) &&
        assertTrue(resolution.errorMessage == Some("Flag not found"))
      }
    )
  )
