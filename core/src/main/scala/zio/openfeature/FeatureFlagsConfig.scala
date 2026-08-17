package zio.openfeature

import zio._

/** Everything the `FeatureFlags.fromProvider(provider, config)` factory can configure, in one place. All fields have
  * defaults; `FeatureFlagsConfig()` reproduces `FeatureFlags.fromProvider(provider)` exactly.
  */
final case class FeatureFlagsConfig(
  domain: Option[String] = None,
  version: Option[String] = None,
  initialHooks: List[FeatureHook] = Nil,
  evaluationTimeout: EvaluationTimeout = EvaluationTimeout.Default,
  initTimeout: Duration = FeatureFlags.DefaultInitTimeout,
  initMode: InitMode = InitMode.Sync,
  apiOwnership: ApiOwnership = ApiOwnership.Auto,
  contextSource: ContextSource = ContextSource.empty
) {
  def withDomain(d: String): FeatureFlagsConfig               = copy(domain = Some(d))
  def withVersion(v: String): FeatureFlagsConfig              = copy(version = Some(v))
  def withHooks(hooks: List[FeatureHook]): FeatureFlagsConfig = copy(initialHooks = hooks)
  def withHook(hook: FeatureHook): FeatureFlagsConfig         = copy(initialHooks = initialHooks :+ hook)
  def withEvaluationTimeout(d: Duration): FeatureFlagsConfig  = copy(evaluationTimeout = EvaluationTimeout.After(d))
  def withoutEvaluationTimeout: FeatureFlagsConfig            = copy(evaluationTimeout = EvaluationTimeout.Disabled)
  def withInitTimeout(d: Duration): FeatureFlagsConfig        = copy(initTimeout = d)
  def withAsyncInit: FeatureFlagsConfig                       = copy(initMode = InitMode.Async)
  def withSyncInit: FeatureFlagsConfig                        = copy(initMode = InitMode.Sync)
  def withApiOwnership(o: ApiOwnership): FeatureFlagsConfig   = copy(apiOwnership = o)

  /** Consult `source` for ambient context on every evaluation, merged between the client and fiber-local contexts — see
    * [[ContextSource]] for the full precedence and why that slot matters. Replaces any source already set; compose with
    * `++` to add to one.
    */
  def withContextSource(source: ContextSource): FeatureFlagsConfig = copy(contextSource = source)
}

/** How the provider is initialized.
  *
  *   - [[InitMode.Sync]] — `setProviderAndWait`. The layer build blocks until the provider reaches `READY`/`STALE` or
  *     `initTimeout` elapses; misconfiguration (a bad SDK key, an unreachable endpoint) fails the layer build itself,
  *     so it surfaces at startup rather than at first evaluation.
  *   - [[InitMode.Async]] — `setProvider`. The layer builds immediately; evaluations fail with `ProviderNotReady` until
  *     the provider becomes `Ready`. The `initTimeout` watchdog still runs in the background and flips status to
  *     `Fatal` if the provider never becomes usable within that window.
  */
sealed trait InitMode
object InitMode {
  case object Sync  extends InitMode
  case object Async extends InitMode
}

/** Who owns the underlying `OpenFeatureAPI`'s lifecycle — i.e. whether this instance's scope closing (or an explicit
  * `shutdown` call) also shuts down the API, and with it every provider registered on it.
  *
  * '''Auto (default) truth table''' — mirrors the pre-#253 implicit behavior, now made explicit:
  *
  *   - `domain.isEmpty` -> [[ApiOwnership.Owned]]: this is a sole-owner client on the process-global (or
  *     caller-supplied) API. Nobody else can reasonably be sharing an unnamed default client, so scope close /
  *     `shutdown` tears down the API (and its provider) — matching `FeatureFlags.fromProvider`'s historical
  *     `addShutdownFinalizer = true`.
  *   - `domain.isDefined` -> [[ApiOwnership.Shared]]: a named domain almost always means multiple domain clients are
  *     sharing one process-global `OpenFeatureAPI` (see #243). If a domain client shut the API down, every sibling
  *     domain's provider would die with it. So by default a domain-scoped client leaves the API alone; the API's actual
  *     owner (e.g. `FeatureFlagRegistry`, or whoever constructed it) is responsible for shutting it down once.
  *
  * Override the default when the truth table doesn't match your topology:
  *
  *   - [[ApiOwnership.Owned]] — force ownership even with a domain set (e.g. a single-domain app that still wants
  *     scope-close to tear the API down).
  *   - [[ApiOwnership.Shared]] — force non-ownership even without a domain (e.g. a caller-supplied `apiOverride` that
  *     something else already owns).
  */
sealed trait ApiOwnership
object ApiOwnership {

  /** `domain.isEmpty => Owned`, `domain.isDefined => Shared` — see the [[ApiOwnership]] scaladoc for the full
    * rationale.
    */
  case object Auto extends ApiOwnership

  /** Scope close / `shutdown` tears down the underlying API (and every provider registered on it). */
  case object Owned extends ApiOwnership

  /** Scope close / `shutdown` leaves the underlying API untouched — sibling clients sharing it keep working. */
  case object Shared extends ApiOwnership
}
