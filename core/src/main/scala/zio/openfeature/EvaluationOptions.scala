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
  *   Maximum duration for this evaluation. Overrides the global evaluation timeout set on the `FeatureFlags` instance.
  *   `None` means use the global default (which itself defaults to no timeout).
  */
final case class EvaluationOptions(
  hooks: List[FeatureHook] = Nil,
  hookHints: HookHints = HookHints.empty,
  timeout: Option[zio.Duration] = None
) {
  def withHook(hook: FeatureHook): EvaluationOptions =
    copy(hooks = hooks :+ hook)

  def withHooks(newHooks: List[FeatureHook]): EvaluationOptions =
    copy(hooks = hooks ++ newHooks)

  def withHint(key: String, value: Any): EvaluationOptions =
    copy(hookHints = hookHints + (key -> value))

  def withTimeout(duration: zio.Duration): EvaluationOptions =
    copy(timeout = Some(duration))
}

object EvaluationOptions {
  val empty: EvaluationOptions = EvaluationOptions()

  def apply(hook: FeatureHook): EvaluationOptions =
    EvaluationOptions(hooks = List(hook))

  def apply(hooks: FeatureHook*): EvaluationOptions =
    EvaluationOptions(hooks = hooks.toList)
}
