package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._

/** `RecordingHook` records the real ZIO hook pipeline, stage by stage. Shared test source dir → 2.13 and 3. */
object RecordingHookSpec extends ZIOSpecDefault {

  private def stage(call: HookCall): String = call match {
    case _: HookCall.Before  => "before"
    case _: HookCall.After   => "after"
    case _: HookCall.Error   => "error"
    case _: HookCall.Finally => "finally"
  }

  private def installed(types: Set[FlagValueType] = FlagValueType.allTypes): ZIO[FeatureFlags, Nothing, RecordingHook] =
    RecordingHook.make(types).tap(FeatureFlags.addHook(_))

  def spec = suite("RecordingHook")(
    test("a successful evaluation records before, after, finally with the flag's key and type") {
      for {
        recorder <- installed()
        _        <- FeatureFlags.boolean("flag", default = false)
        calls    <- recorder.calls
      } yield {
        val stages   = calls.map(stage)
        val keys     = calls.map(_.ctx.flagKey).toSet
        val types    = calls.map(_.ctx.flagType).toSet
        val resolved = calls.collect { case HookCall.After(_, resolution, _) => resolution.value }
        val finals   = calls.collect { case HookCall.Finally(_, resolution, _) => resolution.map(_.value) }
        assertTrue(
          stages == Chunk("before", "after", "finally"),
          keys == Set("flag"),
          types == Set[FlagValueType](FlagValueType.Boolean),
          resolved == Chunk[Any](true),
          finals == Chunk[Option[Any]](Some(true))
        )
      }
    },
    test("a provider failure records before, error, finally") {
      for {
        recorder <- installed()
        _        <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
        calls    <- recorder.calls
      } yield {
        val stages = calls.map(stage)
        assertTrue(stages == Chunk("before", "error", "finally"))
      }
    } @@ TestFeatureProvider.withFailures,
    test("an unset key records the error stage with FlagNotFound") {
      for {
        recorder <- installed()
        _        <- FeatureFlags.resolveOrDefault[Boolean]("missing", default = false)
        calls    <- recorder.calls
      } yield {
        val stages = calls.map(stage)
        val errors = calls.collect { case HookCall.Error(_, error, _) => error }
        assertTrue(
          stages == Chunk("before", "error", "finally"),
          errors == Chunk[FeatureFlagError](FeatureFlagError.FlagNotFound("missing"))
        )
      }
    },
    test("a type filter skips evaluations of other types") {
      for {
        recorder <- installed(Set(FlagValueType.Boolean))
        _        <- FeatureFlags.int("limit", 0)
        afterInt <- recorder.calls
        _        <- FeatureFlags.boolean("flag", default = false)
        afterAll <- recorder.calls
      } yield {
        val keys = afterAll.map(_.ctx.flagKey)
        assertTrue(afterInt.isEmpty, keys == Chunk("flag", "flag", "flag"))
      }
    },
    test("clear empties the record") {
      for {
        recorder <- installed()
        _        <- FeatureFlags.boolean("flag", default = false)
        before   <- recorder.calls
        _        <- recorder.clear
        after    <- recorder.calls
      } yield assertTrue(before.size == 3, after.isEmpty)
    },
    test("every stage records the hints passed through EvaluationOptions") {
      for {
        recorder <- installed()
        _ <- FeatureFlags.booleanDetails(
          "flag",
          default = false,
          options = EvaluationOptions.empty.withHint("request", "r-1")
        )
        calls <- recorder.calls
      } yield {
        val hints = calls.map(_.hints.get[String]("request"))
        assertTrue(hints == Chunk(Some("r-1"), Some("r-1"), Some("r-1")))
      }
    },
    test("before never changes the evaluation context") {
      for {
        recorder <- RecordingHook.make()
        ctx = HookContext(
          "flag",
          FlagValueType.Boolean,
          false,
          EvaluationContext("user-1"),
          ClientMetadata(None),
          ProviderMetadata("p", "1")
        )
        result <- recorder.before(ctx, HookHints.empty)
      } yield assertTrue(result.isEmpty)
    }
  ).provide(TestFeatureProvider.layer(Map("flag" -> true, "limit" -> 5)))
}
