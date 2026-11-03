package zio.openfeature

import zio._
import zio.test._

import scala.util.Try

/** The configs are final classes built from `default` with `with*` methods: the defaults are the documented ones, every
  * `with*` returns a copy, an invalid value is rejected on every path that sets it, and `toString` names the fields.
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only.
  */
object ConfigShapeSpec extends ZIOSpecDefault {

  private def rejects(build: => Any): Boolean =
    Try(build).failed.toOption.exists(_.isInstanceOf[IllegalArgumentException])

  def spec = suite("ConfigShapeSpec")(
    suite("FeatureFlagsConfig")(
      test("default carries the documented defaults") {
        val c = FeatureFlagsConfig.default
        assertTrue(
          c.domain.isEmpty,
          c.version.isEmpty,
          c.initialHooks.isEmpty,
          c.evaluationTimeout == EvaluationTimeout.Default,
          c.initTimeout == FeatureFlags.DefaultInitTimeout,
          c.initMode == InitMode.Sync,
          c.apiOwnership == ApiOwnership.Auto,
          c.contextSource eq ContextSource.empty,
          c.fallbackLogging == FallbackLogging.Default,
          c.maxAbandonedCalls == FeatureFlags.DefaultMaxAbandonedCalls
        )
      },
      test("each with* returns a copy and leaves the receiver as it was") {
        val base    = FeatureFlagsConfig.default
        val changed = base.withDomain("d").withVersion("v").withAsyncInit.withMaxAbandonedCalls(7)
        assertTrue(
          changed.domain.contains("d"),
          changed.version.contains("v"),
          changed.initMode == InitMode.Async,
          changed.maxAbandonedCalls == 7,
          base.domain.isEmpty,
          base.initMode == InitMode.Sync,
          base.maxAbandonedCalls == FeatureFlags.DefaultMaxAbandonedCalls
        )
      },
      test("withMaxAbandonedCalls rejects a value below 1, as the constructor did") {
        assertTrue(
          rejects(FeatureFlagsConfig.default.withMaxAbandonedCalls(0)),
          rejects(FeatureFlagsConfig.default.withMaxAbandonedCalls(-5)),
          FeatureFlagsConfig.default.withMaxAbandonedCalls(1).maxAbandonedCalls == 1,
          FeatureFlagsConfig.default.withMaxAbandonedCalls(Int.MaxValue).maxAbandonedCalls == Int.MaxValue
        )
      },
      test("the rejection names the field and the value") {
        val message = Try(FeatureFlagsConfig.default.withMaxAbandonedCalls(0)).failed.toOption.map(_.getMessage)
        assertTrue(message.contains("requirement failed: maxAbandonedCalls must be at least 1, got 0"))
      },
      test("toString names every field") {
        val text = FeatureFlagsConfig.default.withDomain("checkout").withAsyncInit.toString
        assertTrue(
          text.startsWith("FeatureFlagsConfig("),
          text.contains("domain=Some(checkout)"),
          text.contains("version=None"),
          text.contains("initMode=Async"),
          text.contains("maxAbandonedCalls=" + FeatureFlags.DefaultMaxAbandonedCalls)
        )
      }
    ),
    suite("EvaluationOptions")(
      test("empty carries no hooks, no hints and the default timeout") {
        val o = EvaluationOptions.empty
        assertTrue(o.hooks.isEmpty, o.hookHints == HookHints.empty, o.timeout == EvaluationTimeout.Default)
      },
      test("each with* returns a copy and leaves the receiver as it was") {
        val base    = EvaluationOptions.empty
        val changed = base.withHint("k", "v").withoutTimeout
        assertTrue(
          changed.hookHints.get[String]("k").contains("v"),
          changed.timeout == EvaluationTimeout.Disabled,
          base.hookHints == HookHints.empty,
          base.timeout == EvaluationTimeout.Default
        )
      },
      test("withHookHints replaces the hints") {
        val o = EvaluationOptions.empty.withHint("old", 1).withHookHints(HookHints("new" -> 2))
        assertTrue(o.hookHints.get[Int]("new").contains(2), o.hookHints.get[Int]("old").isEmpty)
      },
      test("toString names every field") {
        val text = EvaluationOptions.empty.withTimeout(2.seconds).toString
        assertTrue(text.startsWith("EvaluationOptions("), text.contains("hooks=List()"), text.contains("timeout="))
      }
    )
  )
}
