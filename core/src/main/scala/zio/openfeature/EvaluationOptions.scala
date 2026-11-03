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
final class EvaluationOptions private (
  val hooks: List[FeatureHook],
  val hookHints: HookHints,
  val timeout: EvaluationTimeout
) {
  private def copy(
    hooks: List[FeatureHook] = hooks,
    hookHints: HookHints = hookHints,
    timeout: EvaluationTimeout = timeout
  ): EvaluationOptions = new EvaluationOptions(hooks, hookHints, timeout)

  def withHook(hook: FeatureHook): EvaluationOptions =
    copy(hooks = hooks :+ hook)

  def withHooks(newHooks: List[FeatureHook]): EvaluationOptions =
    copy(hooks = hooks ++ newHooks)

  def withHint(key: String, value: Any): EvaluationOptions =
    copy(hookHints = hookHints + (key -> value))

  /** Replace all hints. */
  def withHookHints(hints: HookHints): EvaluationOptions =
    copy(hookHints = hints)

  /** Bound this evaluation at `duration`, overriding the global default. */
  def withTimeout(duration: zio.Duration): EvaluationOptions =
    copy(timeout = EvaluationTimeout.After(duration))

  /** Run this evaluation with no timeout, overriding the global default. */
  def withoutTimeout: EvaluationOptions =
    copy(timeout = EvaluationTimeout.Disabled)

  override def toString: String =
    s"EvaluationOptions(hooks=$hooks, hookHints=$hookHints, timeout=$timeout)"
}

object EvaluationOptions {
  val empty: EvaluationOptions = new EvaluationOptions(Nil, HookHints.empty, EvaluationTimeout.Default)

  def apply(hook: FeatureHook): EvaluationOptions =
    empty.withHook(hook)

  def apply(hooks: FeatureHook*): EvaluationOptions =
    empty.withHooks(hooks.toList)
}
