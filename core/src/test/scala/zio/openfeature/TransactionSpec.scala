package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

object TransactionSpec extends ZIOSpecDefault:

  def spec = suite("TransactionSpec")(
    suite("FlagEvaluation")(
      test("evaluated creates record with wasOverridden = false") {
        val resolution = FlagResolution.targetingMatch("test-flag", true, Some("variant-a"))
        for eval <- FlagEvaluation.evaluated("test-flag", resolution)
        yield assertTrue(eval.key == "test-flag") &&
          assertTrue(eval.value == true) &&
          assertTrue(eval.wasOverridden == false) &&
          assertTrue(eval.wasEvaluated == true)
      },
      test("overridden creates record with wasOverridden = true") {
        for eval <- FlagEvaluation.overridden("override-flag", 42)
        yield assertTrue(eval.key == "override-flag") &&
          assertTrue(eval.value == 42) &&
          assertTrue(eval.wasOverridden == true) &&
          assertTrue(eval.wasEvaluated == false)
      },
      test("evaluation has timestamp") {
        val resolution = FlagResolution.default("test", true)
        for
          before <- Clock.instant
          eval   <- FlagEvaluation.evaluated("test", resolution)
          after  <- Clock.instant
        yield assertTrue(!eval.timestamp.isBefore(before)) &&
          assertTrue(!eval.timestamp.isAfter(after))
      }
    ),
    suite("TransactionResult")(
      test("empty creates result with no evaluations") {
        val result = TransactionResult.empty("result-value")
        assertTrue(result.result == "result-value") &&
        assertTrue(result.flagCount == 0) &&
        assertTrue(result.overrideCount == 0) &&
        assertTrue(result.allFlagKeys.isEmpty)
      },
      test("flagCount returns number of evaluated flags") {
        val now   = Instant.now()
        val eval1 = FlagEvaluation("a", true, FlagResolution.default("a", true), false, now)
        val eval2 = FlagEvaluation("b", 42, FlagResolution.default("b", 42), false, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("a" -> eval1, "b" -> eval2),
          overriddenFlags = Set.empty
        )
        assertTrue(result.flagCount == 2)
      },
      test("overrideCount returns number of overridden flags") {
        val now   = Instant.now()
        val eval1 = FlagEvaluation("a", true, FlagResolution.cached("a", true), true, now)
        val eval2 = FlagEvaluation("b", 42, FlagResolution.default("b", 42), false, now)
        val eval3 = FlagEvaluation("c", "x", FlagResolution.cached("c", "x"), true, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("a" -> eval1, "b" -> eval2, "c" -> eval3),
          overriddenFlags = Set("a", "c")
        )
        assertTrue(result.overrideCount == 2)
      },
      test("allFlagKeys returns all evaluated flag keys") {
        val now   = Instant.now()
        val eval1 = FlagEvaluation("flag-a", true, FlagResolution.default("flag-a", true), false, now)
        val eval2 = FlagEvaluation("flag-b", 42, FlagResolution.default("flag-b", 42), false, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("flag-a" -> eval1, "flag-b" -> eval2),
          overriddenFlags = Set.empty
        )
        assertTrue(result.allFlagKeys == Set("flag-a", "flag-b"))
      },
      test("providerEvaluatedKeys excludes overridden flags") {
        val now   = Instant.now()
        val eval1 = FlagEvaluation("provider", true, FlagResolution.default("provider", true), false, now)
        val eval2 = FlagEvaluation("override", 42, FlagResolution.cached("override", 42), true, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("provider" -> eval1, "override" -> eval2),
          overriddenFlags = Set("override")
        )
        assertTrue(result.providerEvaluatedKeys == Set("provider"))
      },
      test("wasEvaluated returns true for evaluated flags") {
        val now  = Instant.now()
        val eval = FlagEvaluation("checked", true, FlagResolution.default("checked", true), false, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("checked" -> eval),
          overriddenFlags = Set.empty
        )
        assertTrue(result.wasEvaluated("checked")) &&
        assertTrue(!result.wasEvaluated("not-checked"))
      },
      test("wasOverridden returns true for overridden flags") {
        val now  = Instant.now()
        val eval = FlagEvaluation("overridden", true, FlagResolution.cached("overridden", true), true, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("overridden" -> eval),
          overriddenFlags = Set("overridden")
        )
        assertTrue(result.wasOverridden("overridden")) &&
        assertTrue(!result.wasOverridden("not-overridden"))
      },
      test("getEvaluation returns evaluation record") {
        val now  = Instant.now()
        val eval = FlagEvaluation("get-test", "value", FlagResolution.default("get-test", "value"), false, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("get-test" -> eval),
          overriddenFlags = Set.empty
        )
        assertTrue(result.getEvaluation("get-test").isDefined) &&
        assertTrue(result.getEvaluation("get-test").get.value == "value") &&
        assertTrue(result.getEvaluation("missing").isEmpty)
      },
      test("map transforms result value") {
        val result = TransactionResult.empty(10)
        val mapped = result.map(_ * 2)
        assertTrue(mapped.result == 20)
      },
      test("toValueMap returns simple value map") {
        val now   = Instant.now()
        val eval1 = FlagEvaluation("a", true, FlagResolution.default("a", true), false, now)
        val eval2 = FlagEvaluation("b", 42, FlagResolution.default("b", 42), false, now)
        val result = TransactionResult(
          result = "test",
          evaluatedFlags = Map("a" -> eval1, "b" -> eval2),
          overriddenFlags = Set.empty
        )
        val valueMap = result.toValueMap
        assertTrue(valueMap("a") == true) &&
        assertTrue(valueMap("b") == 42)
      }
    ),
    suite("TransactionState")(
      test("make creates empty state") {
        for
          state <- TransactionState.make(Map.empty, EvaluationContext.empty)
          evals <- state.getEvaluations
        yield assertTrue(evals.isEmpty)
      },
      test("make stores overrides") {
        for state <- TransactionState.make(Map("a" -> 1, "b" -> "two"), EvaluationContext.empty)
        yield assertTrue(state.getOverride("a").contains(1)) &&
          assertTrue(state.getOverride("b").contains("two")) &&
          assertTrue(state.getOverride("c").isEmpty)
      },
      test("make stores context") {
        val ctx = EvaluationContext("user-123")
        for state <- TransactionState.make(Map.empty, ctx)
        yield assertTrue(state.context.targetingKey.contains("user-123"))
      },
      test("record adds evaluation") {
        for
          state <- TransactionState.make(Map.empty, EvaluationContext.empty)
          eval  <- FlagEvaluation.evaluated("test", FlagResolution.default("test", true))
          _     <- state.record(eval)
          evals <- state.getEvaluations
        yield assertTrue(evals.size == 1) &&
          assertTrue(evals.contains("test"))
      },
      test("toResult builds TransactionResult") {
        for
          state  <- TransactionState.make(Map("override" -> 42), EvaluationContext.empty)
          eval1  <- FlagEvaluation.evaluated("provider", FlagResolution.default("provider", true))
          eval2  <- FlagEvaluation.overridden("override", 42)
          _      <- state.record(eval1)
          _      <- state.record(eval2)
          result <- state.toResult("done")
        yield assertTrue(result.result == "done") &&
          assertTrue(result.flagCount == 2) &&
          assertTrue(result.overrideCount == 1) &&
          assertTrue(result.wasOverridden("override")) &&
          assertTrue(!result.wasOverridden("provider"))
      }
    )
  )
