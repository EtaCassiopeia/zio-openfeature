package zio.openfeature.internal

import zio._
import zio.openfeature._

/** The hook pipeline of a [[FeatureFlagsLive]] (#445): orders the API, client and invocation hooks and runs their
  * stages around one evaluation.
  */
final private[openfeature] class HookPipeline(
  state: FeatureFlagsState,
  providerNameRef: java.util.concurrent.atomic.AtomicReference[String],
  domain: Option[String],
  version: Option[String]
) {

  private[openfeature] def runWithHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]],
    extraHooks: List[FeatureHook] = Nil,
    initialHints: HookHints = HookHints.empty
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      apiHooks     <- state.zioApiHooksRef.get
      currentHooks <- state.hooksRef.get
      pName        <- ZIO.succeed(providerNameRef.get())
      flagType = FlagValueType.fromFlagType[A]
      // Order per spec §4.4.1: API -> Client -> Invocation. Provider hooks run inside the Java SDK call.
      // Pre-filtering by flag type (spec 4.4.2.1) lets evaluations with no applicable hooks skip the
      // pipeline entirely; the composed hook's own per-stage filter then has nothing left to drop.
      allHooks   = (apiHooks ++ currentHooks ++ extraHooks).filter(_.supportedFlagTypes.contains(flagType))
      metadata   = ProviderMetadata(pName)
      clientMeta = ClientMetadata(domain, version)
      hookCtx    = HookContext(
        flagKey = key,
        flagType = flagType,
        defaultValue = default,
        evaluationContext = context,
        clientMetadata = clientMeta,
        providerMetadata = metadata
      )
      result <-
        if (allHooks.isEmpty) evaluate(context)
        else runHookPipeline(hookCtx, allHooks, context, initialHints, evaluate)
    } yield result

  private def runHookPipeline[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    initialHints: HookHints,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val composedHook = FeatureHook.compose(hooks)

    // `finallyAfter` must run on EVERY exit — including an interruption of `before` itself — so it is attached as one
    // outer `onExit` wrapping the whole pipeline (an `onExit` nested inside the foldCauseZIO branches would be skipped
    // when `before` is interrupted before that branch's effect is even entered). Per spec §4.3.5-4.3.8 the finally
    // stage must observe the context as modified by the before hooks; that modified `(ctx, hints)` is recorded in a Ref
    // once `before` succeeds, so the outer finalizer uses it, falling back to the original context when `before` never
    // completed. The Ref's third slot holds the details to hand `finallyAfter` when the pipeline fails after a
    // resolution was already replaced by the default (an `after`-hook defect, spec §4.4.8).
    Ref.make((hookCtx, initialHints, Option.empty[FlagResolution[_]])).flatMap { finallyCtxRef =>
      composedHook
        .before(hookCtx, initialHints)
        .foldCauseZIO(
          // `before` is a UIO, so a non-success cause is a Die or an Interrupt. Only a Die is abnormal execution that
          // runs the `error` stage (spec §4.3.8/§4.4.7) — a pure interruption (timeout / scope-close) is a cancellation,
          // not a hook failure, so it must not be reported to `error`. The original cause is always preserved: even if
          // the `error` stage itself defects, its cause is combined with (never replaces) `beforeCause`.
          beforeCause =>
            beforeCause.dieOption match {
              case Some(defect) =>
                composedHook
                  .error(hookCtx, FeatureFlagError.ProviderError(defect), initialHints)
                  .foldCauseZIO(
                    errCause => ZIO.refailCause(beforeCause ++ errCause),
                    _ => ZIO.refailCause(beforeCause)
                  )
              case None => ZIO.refailCause(beforeCause)
            },
          beforeResult => {
            val effectiveCtx = beforeResult.getOrElse(context)
            val hints        = initialHints
            val stageCtx     = hookCtx.copy(evaluationContext = effectiveCtx)
            finallyCtxRef.set((stageCtx, hints, None)) *>
              evaluate(effectiveCtx)
                .tapBoth(
                  err => composedHook.error(stageCtx, err, hints),
                  // A returned resolution can still be *abnormal* execution — it carries an error code (FLAG_NOT_FOUND,
                  // TYPE_MISMATCH, ...) that the Java SDK surfaces as a default value plus a code rather than a throw.
                  // Per spec §4.3.6/§4.4.6 that is the `error` stage's domain, NOT `after`: run `error` for an
                  // error-code resolution and `after` only for a clean one.
                  res =>
                    res.errorCode match {
                      case Some(code) =>
                        composedHook.error(
                          stageCtx,
                          Evaluator
                            .errorFromResolution(res.flagKey, code, res.errorMessage, expected = hookCtx.flagType.name),
                          hints
                        )
                      case None =>
                        // Mirrors the `before` branch: `after` is a UIO, so only a Die is abnormal execution. It runs
                        // the `error` stage (spec §4.4.5) and replaces the resolution with the default (§4.4.8) — the
                        // same ERROR/GENERAL details the total tier serves for an absorbed defect, so `finallyAfter`
                        // and the caller agree. The defect itself still propagates, as `FeatureHook` documents.
                        composedHook.after(stageCtx, res, hints).catchAllCause { afterCause =>
                          afterCause.dieOption match {
                            case Some(defect) =>
                              val abnormal =
                                FlagResolution
                                  .error(res.flagKey, hookCtx.defaultValue, ErrorCode.General, defect.toString)
                              finallyCtxRef.set((stageCtx, hints, Some(abnormal))) *>
                                composedHook
                                  .error(stageCtx, FeatureFlagError.ProviderError(defect), hints)
                                  .foldCauseZIO(
                                    errCause => ZIO.refailCause(afterCause ++ errCause),
                                    _ => ZIO.refailCause(afterCause)
                                  )
                            case None => ZIO.refailCause(afterCause)
                          }
                        }
                    }
                )
          }
        )
        .onExit { exit =>
          finallyCtxRef.get.flatMap { case (ctx, hints, abnormal) =>
            val details: Option[FlagResolution[_]] = exit.foldExit(_ => abnormal, res => Some(res))
            composedHook.finallyAfter(ctx, details, hints)
          }
        }
    }
  }
}
