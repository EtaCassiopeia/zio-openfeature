package zio.openfeature

import zio._
import zio.test._
import zio.test.Assertion._

object FlagResolutionSpec extends ZIOSpecDefault {

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
      test("metadata with string values") {
        val meta = FlagMetadata(
          "key1" -> MetadataValue.StringValue("value1"),
          "key2" -> MetadataValue.StringValue("value2")
        )
        assertTrue(!meta.isEmpty) &&
        assertTrue(meta.nonEmpty) &&
        assertTrue(meta.getString("key1") == Some("value1")) &&
        assertTrue(meta.getString("key2") == Some("value2")) &&
        assertTrue(meta.get("missing") == None)
      },
      test("metadata with boolean values") {
        val meta = FlagMetadata("enabled" -> MetadataValue.BooleanValue(true))
        assertTrue(meta.getBoolean("enabled") == Some(true))
      },
      test("metadata with numeric values") {
        val meta = FlagMetadata(
          "count"  -> MetadataValue.IntValue(42),
          "rate"   -> MetadataValue.DoubleValue(3.14),
          "bigNum" -> MetadataValue.LongValue(999999999L)
        )
        assertTrue(meta.getInt("count") == Some(42)) &&
        assertTrue(meta.getDouble("rate") == Some(3.14)) &&
        assertTrue(meta.getLong("bigNum") == Some(999999999L))
      },
      test("fromStrings creates string metadata") {
        val meta = FlagMetadata.fromStrings("a" -> "b")
        assertTrue(meta.getString("a") == Some("b"))
      },
      test("asDouble returns numeric value for int") {
        val meta = FlagMetadata("count" -> MetadataValue.IntValue(42))
        assertTrue(meta.getDouble("count") == Some(42.0))
      },
      test("asLong returns long value for int") {
        val meta = FlagMetadata("count" -> MetadataValue.IntValue(42))
        assertTrue(meta.getLong("count") == Some(42L))
      }
    ),
    suite("ErrorCode enum")(
      test("all error codes exist") {
        assertTrue(ErrorCode.ProviderNotReady != null) &&
        assertTrue(ErrorCode.ProviderFatal != null) &&
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
        val meta       = FlagMetadata("source" -> MetadataValue.StringValue("config"))
        val resolution = FlagResolution.targetingMatch("my-flag", "value", None, meta)
        assertTrue(resolution.metadata.getString("source") == Some("config"))
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
}
