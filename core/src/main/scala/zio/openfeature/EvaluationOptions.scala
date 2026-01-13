package zio.openfeature

/** Options for flag evaluation, including invocation-level hooks.
  *
  * Per the OpenFeature spec, hooks can be registered at multiple levels: API, Client, Invocation, and Provider. This
  * class provides invocation-level hooks that apply to a single evaluation call.
  */
final case class EvaluationOptions(
  hooks: List[FeatureHook] = Nil,
  hookHints: HookHints = HookHints.empty
):
  def withHook(hook: FeatureHook): EvaluationOptions =
    copy(hooks = hooks :+ hook)

  def withHooks(newHooks: List[FeatureHook]): EvaluationOptions =
    copy(hooks = hooks ++ newHooks)

  def withHint(key: String, value: Any): EvaluationOptions =
    copy(hookHints = hookHints + (key -> value))

object EvaluationOptions:
  val empty: EvaluationOptions = EvaluationOptions()

  def apply(hook: FeatureHook): EvaluationOptions =
    EvaluationOptions(hooks = List(hook))

  def apply(hooks: FeatureHook*): EvaluationOptions =
    EvaluationOptions(hooks = hooks.toList)
