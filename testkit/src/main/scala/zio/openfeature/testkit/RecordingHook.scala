package zio.openfeature.testkit

import zio._
import zio.openfeature._

/** One stage call a [[RecordingHook]] observed. Each case carries its stage's full arguments, hints included. */
sealed trait HookCall extends Product with Serializable {
  def ctx: HookContext
  def hints: HookHints
}

object HookCall {

  /** The `before` stage. */
  final case class Before(ctx: HookContext, hints: HookHints) extends HookCall

  /** The `after` stage: a clean resolution. */
  final case class After(ctx: HookContext, resolution: FlagResolution[_], hints: HookHints) extends HookCall

  /** The `error` stage: a typed failure or a resolution carrying an error code (`FLAG_NOT_FOUND`, ...). */
  final case class Error(ctx: HookContext, error: FeatureFlagError, hints: HookHints) extends HookCall

  /** The `finallyAfter` stage, which runs last on every exit. */
  final case class Finally(ctx: HookContext, resolution: Option[FlagResolution[_]], hints: HookHints) extends HookCall
}

/** A [[zio.openfeature.FeatureHook]] that records every stage call it receives, in order, so a test can assert on the
  * real ZIO hook pipeline instead of hand-writing a hook that pushes into a `Ref`.
  *
  * Install it like any hook — `FeatureFlags.addHook(recorder)`, `FeatureFlagsConfig.withHook`, or per call through
  * `EvaluationOptions` — so it works with any `FeatureFlags`, not only the testkit layers. `before` always returns
  * `None`: the recorder never changes the evaluation context.
  *
  * {{{
  * for {
  *   recorder <- RecordingHook.make()
  *   _        <- FeatureFlags.addHook(recorder)
  *   _        <- FeatureFlags.boolean("flag", default = false)
  *   calls    <- recorder.calls
  * } yield assertTrue(calls.map(_.ctx.flagKey) == Chunk("flag", "flag", "flag"))
  * }}}
  */
final class RecordingHook private (ref: Ref[Chunk[HookCall]], types: Set[FlagValueType]) extends FeatureHook {

  override def supportedFlagTypes: Set[FlagValueType] = types

  /** Every call recorded so far, oldest first. */
  def calls: UIO[Chunk[HookCall]] = ref.get

  /** Forget every recorded call. */
  def clear: UIO[Unit] = ref.set(Chunk.empty)

  private def record(call: HookCall): UIO[Unit] = ref.update(_ :+ call)

  override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
    record(HookCall.Before(ctx, hints)).as(None)

  override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    record(HookCall.After(ctx, details, hints))

  override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    record(HookCall.Error(ctx, error, hints))

  override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
    record(HookCall.Finally(ctx, details, hints))
}

object RecordingHook {

  /** A recorder that is invoked for evaluations of these flag types only (all of them by default). */
  def make(types: Set[FlagValueType] = FlagValueType.allTypes): UIO[RecordingHook] =
    Ref.make(Chunk.empty[HookCall]).map(new RecordingHook(_, types))
}
