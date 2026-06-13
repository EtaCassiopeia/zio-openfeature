package zio.openfeature.conformance

import zio.*
import zio.test.*
import zio.openfeature.*

/** Conformance port of the spec's `evaluation_v2.feature` (upstream main @ 203c25f93495).
  *
  * Drives the ZIO `FeatureFlags` API against the canonical in-memory fixtures and asserts the value, variant, reason,
  * and error-code that each spec scenario requires. Test names carry the gherkin scenario and the `@spec-*` tag.
  *
  * Out of scope (excluded, matching the Java SDK's own harness): provider-status scenarios (`@provider-status` — needs
  * lifecycle simulation, covered by ProviderStatusSpec / ProviderInitHardeningSpec), `@reason-codes-cached`,
  * `@evaluation-options` (see HooksConformanceSpec), `@immutability`, `@async`, and null-context values (the typed
  * `EvaluationContext` has no null attribute representation).
  */
object EvaluationConformanceSpec extends ZIOSpecDefault:

  private val targetingCtx: EvaluationContext =
    EvaluationContext.builder.attribute("email", "ballmer@macrosoft.com").build

  private val nonMatchingCtx: EvaluationContext =
    EvaluationContext.builder.attribute("email", "ballmer@none.com").build

  private val templateObject: Map[String, Any] =
    Map("showImages" -> true, "title" -> "Check out these pics!", "imagesPerPage" -> 100.0)

  def spec = suite("EvaluationConformanceSpec")(
    suite("Resolve values (spec 1.3.1.1, 1.4.1.1)")(
      test("boolean-flag resolves true") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-flag", false))
        yield assertTrue(r.value == true)
      },
      test("string-flag resolves hi") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("string-flag", "bye"))
        yield assertTrue(r.value == "hi")
      },
      test("integer-flag resolves 10") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-flag", 1))
        yield assertTrue(r.value == 10)
      },
      test("float-flag resolves 0.5") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.doubleDetails("float-flag", 0.1))
        yield assertTrue(r.value == 0.5)
      },
      test("object-flag resolves template") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.objDetails("object-flag", Map.empty))
        yield assertTrue(r.value == templateObject)
      }
    ),
    suite("Resolves zero value with reason STATIC (spec 1.4.7)")(
      test("boolean-zero-flag → false, STATIC") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-zero-flag", true))
        yield assertTrue(r.value == false, r.reason == ResolutionReason.Static)
      },
      test("string-zero-flag → empty, STATIC") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("string-zero-flag", "hi"))
        yield assertTrue(r.value == "", r.reason == ResolutionReason.Static)
      },
      test("integer-zero-flag → 0, STATIC") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-zero-flag", 1))
        yield assertTrue(r.value == 0, r.reason == ResolutionReason.Static)
      },
      test("float-zero-flag → 0.0, STATIC") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.doubleDetails("float-zero-flag", 0.1))
        yield assertTrue(r.value == 0.0, r.reason == ResolutionReason.Static)
      }
    ),
    suite("Resolves zero value with targeting → TARGETING_MATCH (spec 1.4.7)")(
      test("boolean-targeted-zero-flag matches email") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-targeted-zero-flag", true, targetingCtx))
        yield assertTrue(r.value == false, r.reason == ResolutionReason.TargetingMatch)
      },
      test("integer-targeted-zero-flag matches email") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-targeted-zero-flag", 1, targetingCtx))
        yield assertTrue(r.value == 0, r.reason == ResolutionReason.TargetingMatch)
      }
    ),
    suite("Resolves with targeting using default → DEFAULT (spec 1.4.7)")(
      test("boolean-targeted-zero-flag with non-matching email") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-targeted-zero-flag", true, nonMatchingCtx))
        yield assertTrue(r.value == false, r.reason == ResolutionReason.Default)
      },
      test("integer-targeted-zero-flag with non-matching email") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-targeted-zero-flag", 1, nonMatchingCtx))
        yield assertTrue(r.value == 0, r.reason == ResolutionReason.Default)
      }
    ),
    suite("Empty evaluation context → DEFAULT (spec 1.3.1.1)")(
      test("targeted flag with no context falls back to default") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-targeted-zero-flag", true))
        yield assertTrue(r.value == false, r.reason == ResolutionReason.Default)
      }
    ),
    suite("Flag not found error (spec 1.4.8, 1.4.9, 1.4.13)")(
      test("missing flag returns default, ERROR, FLAG_NOT_FOUND") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("non-existent-flag", false))
        yield assertTrue(
          r.value == false,
          r.reason == ResolutionReason.Error,
          r.errorCode.contains(ErrorCode.FlagNotFound)
        )
      }
    ),
    suite("Type mismatch error (spec 1.3.4)")(
      test("string-flag evaluated as boolean returns default, ERROR, TYPE_MISMATCH") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("string-flag", false))
        yield assertTrue(
          r.value == false,
          r.reason == ResolutionReason.Error,
          r.errorCode.contains(ErrorCode.TypeMismatch)
        )
      }
    ),
    suite("Complete evaluation details structure (spec 1.4.3, 1.4.5, 1.4.6)")(
      test("boolean-flag carries value, flagKey, variant, STATIC") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-flag", false))
        yield assertTrue(
          r.value == true,
          r.flagKey == "boolean-flag",
          r.variant.contains("on"),
          r.reason == ResolutionReason.Static
        )
      },
      test("string-flag variant is greeting") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("string-flag", "bye"))
        yield assertTrue(r.variant.contains("greeting"), r.value == "hi")
      },
      test("integer-flag variant is ten") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.intDetails("integer-flag", 1))
        yield assertTrue(r.variant.contains("ten"), r.value == 10)
      },
      test("object-flag variant is template") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.objDetails("object-flag", Map.empty))
        yield assertTrue(r.variant.contains("template"))
      }
    ),
    suite("Multiple context attributes targeting (spec 1.3.1.1)")(
      test("complex-targeted resolves INTERNAL, TARGETING_MATCH") {
        val ctx = EvaluationContext.builder
          .attribute("email", "ballmer@macrosoft.com")
          .attribute("role", "admin")
          .attribute("age", 65)
          .attribute("customer", false)
          .build
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("complex-targeted", "default", ctx))
        yield assertTrue(r.value == "INTERNAL", r.reason == ResolutionReason.TargetingMatch)
      }
    ),
    suite("DISABLED reason (spec 1.4.7)")(
      test("boolean-disabled-flag returns default, DISABLED") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("boolean-disabled-flag", false))
        yield assertTrue(r.value == false, r.reason == ResolutionReason.Disabled)
      },
      test("string-disabled-flag returns default, DISABLED") {
        for r <- ZIO.serviceWithZIO[FeatureFlags](_.stringDetails("string-disabled-flag", "bye"))
        yield assertTrue(r.value == "bye", r.reason == ResolutionReason.Disabled)
      }
    )
  ).provide(ConformanceFixtures.layer) @@ TestAspect.withLiveClock
