package zio.openfeature

/** Options for flag evaluation, including invocation-level hooks and timeout.
  *
  * Per the OpenFeature spec, hooks can be registered at multiple levels: API, Client, Invocation, and Provider. This
  * class provides invocation-level hooks that apply to a single evaluation call.
  *
  * @param hooks
  *   Invocation-level hooks for this evaluation
  * @param hookHints
  *   Read-only hints passed to hooks
  * @param timeout
  *   Timeout selection for this evaluation. [[EvaluationTimeout.Default]] uses the global evaluation timeout set on the
  *   `FeatureFlags` instance — which itself defaults to **1 second** (not "no timeout"). Use
  *   [[EvaluationTimeout.After]] (via `withTimeout`) to bound this call, or [[EvaluationTimeout.Disabled]] (via
  *   `withoutTimeout`) to run it with no timeout at all.
  */
final case class EvaluationOptions(
  hooks: List[FeatureHook] = Nil,
  hookHints: HookHints = HookHints.empty,
  timeout: EvaluationTimeout = EvaluationTimeout.Default
) {
  def withHook(hook: FeatureHook): EvaluationOptions =
    copy(hooks = hooks :+ hook)

  def withHooks(newHooks: List[FeatureHook]): EvaluationOptions =
    copy(hooks = hooks ++ newHooks)

  def withHint(key: String, value: Any): EvaluationOptions =
    copy(hookHints = hookHints + (key -> value))

  /** Bound this evaluation at `duration`, overriding the global default. */
  def withTimeout(duration: zio.Duration): EvaluationOptions =
    copy(timeout = EvaluationTimeout.After(duration))

  /** Run this evaluation with no timeout, overriding the global default. */
  def withoutTimeout: EvaluationOptions =
    copy(timeout = EvaluationTimeout.Disabled)
}

object EvaluationOptions {
  val empty: EvaluationOptions = EvaluationOptions()

  def apply(hook: FeatureHook): EvaluationOptions =
    EvaluationOptions(hooks = List(hook))

  def apply(hooks: FeatureHook*): EvaluationOptions =
    EvaluationOptions(hooks = hooks.toList)
}
