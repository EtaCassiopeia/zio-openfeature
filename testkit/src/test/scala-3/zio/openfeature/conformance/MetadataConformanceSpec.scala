package zio.openfeature.conformance

import zio.*
import zio.test.*
import zio.openfeature.*

/** Conformance port of the spec's `metadata.feature` (upstream main @ 203c25f93495).
  *
  * Asserts that `FlagResolution.metadata` carries provider-supplied flag metadata when present, and is empty otherwise.
  */
object MetadataConformanceSpec extends ZIOSpecDefault:

  def spec = suite("MetadataConformanceSpec")(
    test("Returns metadata for metadata-flag (spec 1.4.14)") {
      for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("metadata-flag", true))
      yield assertTrue(
        r.metadata.getString("string").contains("1.0.2"),
        r.metadata.getInt("integer").contains(2),
        r.metadata.get("float").contains(MetadataValue.FloatValue(0.1f)),
        r.metadata.getBoolean("boolean").contains(true)
      )
    },
    suite("Returns no metadata")(
      test("boolean-flag has empty metadata") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-flag", true))
        yield assertTrue(r.metadata.isEmpty)
      },
      test("integer-flag has empty metadata") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-flag", 23))
        yield assertTrue(r.metadata.isEmpty)
      },
      test("float-flag has empty metadata") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.doubleDetails("float-flag", 2.3))
        yield assertTrue(r.metadata.isEmpty)
      },
      test("string-flag has empty metadata") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("string-flag", "value"))
        yield assertTrue(r.metadata.isEmpty)
      }
    )
  ).provide(ConformanceFixtures.layer) @@ TestAspect.withLiveClock
