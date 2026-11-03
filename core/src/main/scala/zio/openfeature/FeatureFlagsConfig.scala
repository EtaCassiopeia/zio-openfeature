package zio.openfeature

import zio._

/** Everything the `FeatureFlags.fromProvider(provider, config)` factory can configure, in one place. Built from
  * [[FeatureFlagsConfig.default]] with the `with*` methods, each returning a copy; `FeatureFlagsConfig.default`
  * reproduces `FeatureFlags.fromProvider(provider)` exactly. It holds functions through its hooks and context source,
  * so it has no structural equality.
  *
  * `maxAbandonedCalls` caps the provider calls an instance has abandoned to its `evaluationTimeout` that are still
  * running, each holding a blocking-pool thread; at the cap, timed evaluations fail fast with `ProviderError` wrapping
  * a `RejectedExecutionException` instead of parking another thread. Default [[FeatureFlags.DefaultMaxAbandonedCalls]]
  * (256); `Int.MaxValue` disables it. It must be at least 1: setting a smaller value throws `IllegalArgumentException`.
  */
final class FeatureFlagsConfig private (
  val domain: Option[String],
  val version: Option[String],
  val initialHooks: List[FeatureHook],
  val evaluationTimeout: EvaluationTimeout,
  val initTimeout: Duration,
  val initMode: InitMode,
  val apiOwnership: ApiOwnership,
  val contextSource: ContextSource,
  val fallbackLogging: FallbackLogging,
  val maxAbandonedCalls: Int
) {
  require(maxAbandonedCalls >= 1, s"maxAbandonedCalls must be at least 1, got $maxAbandonedCalls")

  private def copy(
    domain: Option[String] = domain,
    version: Option[String] = version,
    initialHooks: List[FeatureHook] = initialHooks,
    evaluationTimeout: EvaluationTimeout = evaluationTimeout,
    initTimeout: Duration = initTimeout,
    initMode: InitMode = initMode,
    apiOwnership: ApiOwnership = apiOwnership,
    contextSource: ContextSource = contextSource,
    fallbackLogging: FallbackLogging = fallbackLogging,
    maxAbandonedCalls: Int = maxAbandonedCalls
  ): FeatureFlagsConfig =
    new FeatureFlagsConfig(
      domain,
      version,
      initialHooks,
      evaluationTimeout,
      initTimeout,
      initMode,
      apiOwnership,
      contextSource,
      fallbackLogging,
      maxAbandonedCalls
    )

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

  /** A private `OpenFeatureAPI` for this instance instead of the process-global one; see [[ApiOwnership.Isolated]]. */
  def withIsolatedApi: FeatureFlagsConfig = copy(apiOwnership = ApiOwnership.Isolated)

  /** Consult `source` for ambient context on every evaluation, merged between the client and fiber-local contexts — see
    * [[ContextSource]] for the full precedence and why that slot matters. Replaces any source already set; compose with
    * `++` to add to one.
    */
  def withContextSource(source: ContextSource): FeatureFlagsConfig = copy(contextSource = source)

  /** How the total tier logs served-default fallbacks — see [[FallbackLogging]]. */
  def withFallbackLogging(policy: FallbackLogging): FeatureFlagsConfig = copy(fallbackLogging = policy)

  /** Cap abandoned provider calls at `n` — see [[FeatureFlags.DefaultMaxAbandonedCalls]]. Must be at least 1. */
  def withMaxAbandonedCalls(n: Int): FeatureFlagsConfig = copy(maxAbandonedCalls = n)

  override def toString: String =
    s"FeatureFlagsConfig(domain=$domain, version=$version, initialHooks=$initialHooks, " +
      s"evaluationTimeout=$evaluationTimeout, initTimeout=$initTimeout, initMode=$initMode, " +
      s"apiOwnership=$apiOwnership, contextSource=$contextSource, fallbackLogging=$fallbackLogging, " +
      s"maxAbandonedCalls=$maxAbandonedCalls)"
}

object FeatureFlagsConfig {

  /** No domain or version, no hooks, the default evaluation timeout, a synchronous initialization bounded by
    * [[FeatureFlags.DefaultInitTimeout]], [[ApiOwnership.Auto]], an empty context source, the default fallback logging
    * and [[FeatureFlags.DefaultMaxAbandonedCalls]].
    */
  val default: FeatureFlagsConfig = new FeatureFlagsConfig(
    None,
    None,
    Nil,
    EvaluationTimeout.Default,
    FeatureFlags.DefaultInitTimeout,
    InitMode.Sync,
    ApiOwnership.Auto,
    ContextSource.empty,
    FallbackLogging.Default,
    FeatureFlags.DefaultMaxAbandonedCalls
  )
}

/** How the provider is initialized.
  *
  *   - [[InitMode.Sync]] — the layer build blocks until the provider reaches `READY`/`STALE` or `initTimeout` elapses;
  *     misconfiguration (a bad SDK key, an unreachable endpoint) fails the layer build itself, so it surfaces at
  *     startup rather than at first evaluation.
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
  * '''Auto (default) truth table''' — mirrors the earlier implicit behavior, now made explicit:
  *
  *   - `domain.isEmpty` -> [[ApiOwnership.Owned]]: this is a sole-owner client on the process-global (or
  *     caller-supplied) API. Nobody else can reasonably be sharing an unnamed default client, so scope close /
  *     `shutdown` tears down the API (and its provider) — matching `FeatureFlags.fromProvider`'s historical
  *     `addShutdownFinalizer = true`.
  *   - `domain.isDefined` -> [[ApiOwnership.Shared]]: a named domain almost always means multiple domain clients are
  *     sharing one process-global `OpenFeatureAPI`. If a domain client shut the API down, every sibling domain's
  *     provider would die with it. So by default a domain-scoped client leaves the API alone; the API's actual owner
  *     (e.g. `FeatureFlagRegistry`, or whoever constructed it) is responsible for shutting it down once.
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

  /** A private `OpenFeatureAPI` created for this instance, which always owns it: nothing else on the JVM sees its
    * provider, and binding it touches no other instance's slot. With a domain set, the domain is on that private API.
    */
  case object Isolated extends ApiOwnership
}
