package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.optimizelydecision.OptimizelyDecision
import dev.openfeature.sdk.exceptions.{GeneralError, ProviderNotReadyError, TargetingKeyMissingError}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableMetadata,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Reason,
  Structure,
  Value
}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{CountDownLatch, Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** OpenFeature `FeatureProvider` implementation that delegates to the Optimizely Java SDK.
  *
  * Construction is intentionally simple: take an already-configured `Optimizely` client and adapt its decision API to
  * OpenFeature's evaluation API. The Optimizely client is responsible for its own datafile polling, error handling, and
  * event dispatch; this class translates between the two worlds. Factory-built clients dispatch impressions and
  * conversions as `OptimizelyProviderConfig.events` says (batched to Optimizely by default); a client passed to
  * `OptimizelyProvider.fromOptimizelyClient` dispatches through whatever event processor it was built with.
  *
  * '''Lifecycle notes:'''
  *   - On `initialize()` we register an Optimizely `UpdateConfigNotification` handler. It counts down an internal latch
  *     so the OpenFeature `setProviderAndWait` returns cleanly once the datafile has loaded. The initial datafile load
  *     isn't a "change" and must not precede PROVIDER_READY, so it suppresses `PROVIDER_CONFIGURATION_CHANGED`;
  *     suppression is by datafile REVISION, not READY timing — the handler emits only when the current revision differs
  *     from the one the provider initialized with (captured at READY), so a late initial-load fire delivered post-READY
  *     is still suppressed and only genuine later revisions emit.
  *   - If the datafile never arrives within `initWait`, or the config is invalid, or the handler can't be registered,
  *     `initialize()` throws (propagated by the OpenFeature SDK as a failed init) and leaves the provider in a `Failed`
  *     state with its handler removed. A subsequent `initialize()` on the same instance cleanly re-attempts (`Failed ->
  *     Initialized`) instead of silently no-op'ing and leaving every evaluation `PROVIDER_NOT_READY`.
  *   - A `shutdown()` racing an in-flight `initialize()` aborts the init cleanly: the handler it registered is removed
  *     and the provider is left NOT_READY (it never reports READY after a shutdown).
  *   - `shutdown()` removes the notification handler and closes the underlying client (which stops datafile polling and
  *     the HTTP client for factory-built providers).
  *   - Factory-built providers (via `OptimizelyProvider.make`/`scoped`/`layer`) start fetching the datafile at
  *     construction — the Optimizely SDK offers no way to build its polling config manager paused. Prefer `scoped` or
  *     `layer`, which own the provider's shutdown.
  *
  * '''Decision mapping:'''
  *   - Boolean: returns `decision.getEnabled`.
  *   - String: returns the variable `variableKey` (default `"value"`) from `decision.getVariables`, falling back to
  *     `decision.getVariationKey`, then to the supplied default.
  *   - Integer / Double / Object: extracts the variable `variableKey` from `decision.getVariables` (typed).
  *
  * The convention of looking up a variable named `"value"` (overridable via an `"openfeature.variableKey"` attribute on
  * the evaluation context) mirrors what the OpenFeature contrib Optimizely provider does, so apps switching between
  * providers see consistent behaviour.
  */
final class OptimizelyFeatureProvider private[optimizely] (
  optimizely: Optimizely,
  initWait: java.time.Duration,
  closeOnShutdown: Boolean,
  // Present for factory-built providers: the SDK starts datafile polling at construction (see
  // OptimizelyProvider.buildClient); initialize() waits on it, and shutdown() stops it via Optimizely.close().
  // Caller-managed clients (fromOptimizelyClient) own their polling lifecycle.
  configManager: Option[com.optimizely.ab.config.HttpProjectConfigManager] = None,
  // Present only when the staleness watchdog is enabled (factory-built providers with datafile polling on).
  // When defined, initialize() starts a background daemon that flips READY <-> STALE based on whether datafile
  // fetches keep succeeding (see the watchdog section below). None = watchdog disabled.
  staleness: Option[OptimizelyFeatureProvider.StalenessConfig] = None,
  // Resources the factory built for this provider beyond the Optimizely client itself, closed in order after
  // `Optimizely.close()` when the provider owns the client: `Optimizely.close()` closes neither the CMAB service's
  // HTTP client nor the event processor's executor.
  ownedResources: List[java.io.Closeable] = Nil
) extends EventProvider {

  // Public construction is via the OptimizelyProvider factory in this package — the constructor is private to keep
  // lifecycle invariants (single initialize, registered handler) intact.

  import OptimizelyFeatureProvider.Lifecycle

  private val stateRef           = new AtomicReference[ProviderState](ProviderState.NOT_READY)
  private val lifecycle          = new AtomicReference[Lifecycle](Lifecycle.Fresh)
  private val notificationHandle = new AtomicInteger(-1)
  // Replaced on re-initialization (caller-managed clients only); each init cycle gets a single-use latch.
  private val initLatchRef = new AtomicReference(new CountDownLatch(1))
  // The datafile revision the provider initialized with (the "initial load"), captured when init reaches READY. The
  // update handler emits CONFIGURATION_CHANGED only for a DIFFERENT revision, so the initial-load fire is suppressed
  // deterministically — independent of whether that fire or the `optimizely.isValid` READY transition wins the race.
  // Null until the first successful init of a cycle; reset per init cycle.
  private val initialRevisionRef = new AtomicReference[String](null)
  // The staleness watchdog's scheduled executor, or null when no watchdog is running. Held so shutdown()/failInitialize
  // can cancel it — its thread is a daemon, so even a missed cancel can never keep the JVM alive.
  private val watchdogRef = new AtomicReference[ScheduledExecutorService](null)

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = OptimizelyFeatureProvider.Name
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = stateRef.get()

  override def initialize(ctx: OFEvaluationContext): Unit = {
    // Lifecycle transitions: Fresh -> Initialized (first init), Initialized -> no-op (idempotent),
    // ShutDown -> Initialized only for caller-managed clients (closeOnShutdown = false), and
    // Failed -> Initialized for a clean retry after a throwing init. A provider whose client was closed on
    // shutdown cannot be revived — fail loudly instead of silently never becoming READY (the Java SDK treats a
    // non-throwing initialize as success, so a silent no-op here would leave every subsequent evaluation failing
    // with PROVIDER_NOT_READY far from the cause).
    val transitioned =
      lifecycle.compareAndSet(Lifecycle.Fresh, Lifecycle.Initialized) || {
        if (lifecycle.get() == Lifecycle.ShutDown) {
          if (closeOnShutdown)
            throw new IllegalStateException(
              "OptimizelyFeatureProvider was shut down and its Optimizely client closed; create a new instance via OptimizelyProvider.make"
            )
          else if (lifecycle.compareAndSet(Lifecycle.ShutDown, Lifecycle.Initialized)) {
            initLatchRef.set(new CountDownLatch(1)) // fresh single-use latch for this init cycle
            true
          } else false
        } else if (lifecycle.compareAndSet(Lifecycle.Failed, Lifecycle.Initialized)) {
          // Clean retry after a failed init (mirrors the ShutDown caller-managed retry): a fresh latch and a
          // re-registered handler. `failInitialize` already removed the previous handler and set state ERROR.
          initLatchRef.set(new CountDownLatch(1))
          true
        } else false
      }
    if (!transitioned) return

    val initLatch = initLatchRef.get()
    initialRevisionRef.set(null) // fresh per init cycle; captured when this cycle reaches READY

    val handlerId = optimizely.addUpdateConfigNotificationHandler { _ =>
      // Suppress the CONFIGURATION_CHANGED event for the initial datafile load (which isn't a "change" and would
      // otherwise be delivered ahead of PROVIDER_READY); emit only for genuine later revisions. The discriminator is
      // the datafile REVISION, not READY timing: emit iff the current revision differs from the one the provider
      // initialized with. This is deterministic regardless of whether the initial-load fire or the `optimizely.isValid`
      // fast-path READY transition wins the init race — a late initial-load fire delivered post-READY still matches the
      // captured initial revision and is suppressed (the old `stateRef == READY` gate leaked it).
      initLatch.countDown()
      if (OptimizelyFeatureProvider.shouldEmitConfigChange(initialRevisionRef.get(), currentRevision()))
        emitProviderConfigurationChanged(ProviderEventDetails.builder().build()): Unit
    }
    // Optimizely's NotificationManager returns a non-positive id when registration fails (e.g. a duplicate handler
    // is already present). Without the handler we'd silently never count down the latch via the datafile-update
    // path and would always wait the full `initWait` window. Fail fast instead (no handler to remove — id <= 0).
    if (handlerId <= 0)
      failInitialize(
        s"Optimizely datafile update handler registration failed (returned id=$handlerId); cannot drive init"
      )
    notificationHandle.set(handlerId)

    // A shutdown() may have interleaved between registering the handler and recording its id above; its
    // getAndSet(-1) would then have observed the stale -1 and never removed our handler (a leak + duplicate
    // CONFIGURATION_CHANGED on any re-init). Re-check now: if shut down, remove the handler we just registered and
    // abort — do not start polling, await, or announce READY.
    if (lifecycle.get() == Lifecycle.ShutDown) {
      val handle = notificationHandle.getAndSet(-1)
      if (handle > 0) {
        val _ = Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
        ()
      }
      stateRef.set(ProviderState.NOT_READY)
      return
    }

    // The handler is registered before this point so a notification firing from here on can't be missed. A
    // factory-built manager is already polling — the SDK starts it at construction (see OptimizelyProvider#buildClient)
    // — so `start()` only revives one stopped externally (calling it on a running manager just logs a WARN). A
    // first fetch that completed before the handler was registered is caught by the check right below.
    configManager.foreach(m => if (!m.isRunning) m.start())

    // For a manager we own, read its cache: `optimizely.isValid` blocks up to the SDK's blocking timeout while no
    // datafile has loaded, which would stack in front of `initWait` and let the outer FeatureFlags init timeout fire
    // first, hiding the specific error below. A caller-managed client keeps `isValid`.
    if (configManager.fold(optimizely.isValid)(_.getCachedConfig != null)) initLatch.countDown()

    if (!initLatch.await(initWait.toMillis, TimeUnit.MILLISECONDS))
      failInitialize(
        s"Optimizely datafile did not load within $initWait; check the SDK key and network reachability"
      )

    if (optimizely.isValid) {
      // Capture the datafile revision that brought us to READY (the initial load) BEFORE announcing READY, so the
      // update handler can suppress that revision and emit only genuine later ones.
      initialRevisionRef.set(currentRevision())
      stateRef.set(ProviderState.READY)
      // Guard against a shutdown() that interleaved after the abort re-check above: shutdown sets
      // lifecycle = ShutDown first thing, so if it did, revert to NOT_READY rather than report READY on a
      // shut-down provider.
      if (lifecycle.get() != Lifecycle.Initialized) stateRef.set(ProviderState.NOT_READY)
      else
        // Start the staleness watchdog only after the provider has genuinely reached READY (never before, so we can't
        // emit STALE ahead of PROVIDER_READY). Reseed the fetch baseline to now: the initial datafile load just
        // succeeded, so staleness is measured from this known-good point rather than the observer's construction time.
        staleness.foreach { cfg =>
          cfg.observer.reset()
          startWatchdog(cfg)
          // A shutdown that interleaved after we set READY may have run stopWatchdog() before this executor existed
          // (watchdogRef was still null, so it cancelled nothing). Re-check and cancel the just-started executor so it
          // can't leak — mirrors the post-CAS shutdown guards elsewhere in the lifecycle.
          if (lifecycle.get() != Lifecycle.Initialized) stopWatchdog()
        }
    } else
      failInitialize(
        "Optimizely client reported invalid configuration after datafile load (possible auth or parse failure)"
      )
  }

  /** Current datafile revision as reported by the Optimizely client, or null when no valid config is loaded.
    * Best-effort (the SDK may not yet have a config): any failure or absent config reads as null, which the emit gate
    * treats as "cannot determine a change" and suppresses.
    */
  private def currentRevision(): String =
    try Option(optimizely.getOptimizelyConfig).map(_.getRevision).orNull
    catch { case scala.util.control.NonFatal(_) => null }

  /** Common failure path for `initialize()`: remove the handler registered this cycle (best-effort, as `shutdown` does)
    * so a retry doesn't leak it or fire duplicate CONFIGURATION_CHANGED events, mark the provider `Failed` (state
    * ERROR) so a subsequent `initialize()` cleanly re-attempts instead of silently no-op'ing, then throw.
    */
  private def failInitialize(msg: String): Nothing = {
    stopWatchdog()
    val handle = notificationHandle.getAndSet(-1)
    if (handle > 0) {
      val _ = Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
      ()
    }
    stateRef.set(ProviderState.ERROR)
    lifecycle.set(Lifecycle.Failed)
    throw new RuntimeException(msg)
  }

  override def shutdown(): Unit = {
    // Set ShutDown first so any watchdog tick already in flight sees it and won't emit STALE/READY after teardown,
    // then cancel the watchdog thread before touching the underlying client.
    lifecycle.set(Lifecycle.ShutDown)
    stopWatchdog()
    val handle = notificationHandle.getAndSet(-1)
    if (handle > 0) {
      // Removing the handler is best-effort; if the notification center is already shut down we ignore.
      val _ = Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
      ()
    }
    if (closeOnShutdown) {
      val _ = Try(optimizely.close())
      ownedResources.foreach(r => Try(r.close()))
    }
    stateRef.set(ProviderState.NOT_READY)
  }

  // Staleness watchdog
  //
  // The Optimizely SDK's config-changed notification fires only when the datafile actually CHANGES, so it cannot tell
  // "polling still succeeding but datafile unchanged" from "polling failing". Instead the observing HTTP client (see
  // com.optimizely.ab.ObservingOptimizelyHttpClient) stamps a timestamp on every successful datafile fetch, and this
  // watchdog periodically checks how long it has been since the last success. If that exceeds `staleAfter` it flips
  // the provider READY -> STALE and emits PROVIDER_STALE; once fetches resume it flips STALE -> READY and emits
  // PROVIDER_READY. STALE is an evaluable state — the provider keeps serving the last-known datafile (see `decide`).

  private def startWatchdog(cfg: OptimizelyFeatureProvider.StalenessConfig): Unit = {
    val exec = Executors.newSingleThreadScheduledExecutor(OptimizelyFeatureProvider.watchdogThreadFactory)
    // Only one watchdog runs at a time. A re-init while one is already running keeps the existing one (CAS fails, and
    // we discard the freshly-created executor); shutdown()/failInitialize null the ref so the next init starts fresh.
    if (watchdogRef.compareAndSet(null, exec)) {
      val periodMs = math.max(OptimizelyProvider.MinCheckIntervalMs, cfg.checkInterval.toMillis)
      // scheduleWithFixedDelay (not fixedRate) so ticks never pile up if one is slow; each tick is trivial anyway.
      exec.scheduleWithFixedDelay(() => watchdogTick(cfg), periodMs, periodMs, TimeUnit.MILLISECONDS)
      ()
    } else exec.shutdownNow(): Unit
  }

  private def stopWatchdog(): Unit = {
    val exec = watchdogRef.getAndSet(null)
    if (exec != null) exec.shutdownNow(): Unit
  }

  private def watchdogTick(cfg: OptimizelyFeatureProvider.StalenessConfig): Unit =
    // Guard the whole tick: `scheduleWithFixedDelay` silently cancels all future ticks if a run throws, which would
    // wedge the provider (e.g. stuck STALE, never recovering). Swallowing here keeps the loop self-healing.
    try tick(cfg)
    catch { case scala.util.control.NonFatal(_) => () }

  private def tick(cfg: OptimizelyFeatureProvider.StalenessConfig): Unit =
    // Never fight the lifecycle state machine: only act while genuinely Initialized. If a shutdown or failed init
    // has moved us elsewhere, do nothing — those paths own the state and the executor is being cancelled anyway.
    if (lifecycle.get() == Lifecycle.Initialized) {
      val idleNanos = System.nanoTime() - cfg.observer.lastSuccessNanos
      if (idleNanos >= cfg.staleAfter.toNanos) {
        // Datafile fetches have not succeeded within the window -> mark STALE exactly once (CAS off READY) and signal.
        if (stateRef.compareAndSet(ProviderState.READY, ProviderState.STALE)) {
          if (lifecycle.get() == Lifecycle.Initialized)
            emitProviderStale(ProviderEventDetails.builder().build()): Unit
          // A shutdown interleaved after our CAS: let its NOT_READY win rather than leave the provider STALE.
          else stateRef.compareAndSet(ProviderState.STALE, ProviderState.NOT_READY): Unit
        }
      } else {
        // A fresh successful fetch landed -> recover STALE -> READY exactly once and signal.
        if (stateRef.compareAndSet(ProviderState.STALE, ProviderState.READY)) {
          if (lifecycle.get() == Lifecycle.Initialized)
            emitProviderReady(ProviderEventDetails.builder().build()): Unit
          else stateRef.compareAndSet(ProviderState.READY, ProviderState.NOT_READY): Unit
        }
      }
    }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    decide(key, ctx) match {
      case Right(d)  => success(d, java.lang.Boolean.valueOf(d.getEnabled), usedDefault = false)
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  // Fallback order: typed variable → variation key (still a meaningful Optimizely-driven answer) → OF default. The
  // variation-key fallback applies only to an ABSENT variable; one of another type is a TYPE_MISMATCH.
  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    variableEvaluation(key, defaultValue, ctx, "String") { case s: String => s } { d =>
      Option(d.getVariationKey) match {
        case Some(vk) => success(d, vk, usedDefault = false)
        case None     => success(d, defaultValue, usedDefault = true)
      }
    }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    variableEvaluation(key, defaultValue, ctx, "Integer") { case i: java.lang.Integer => i }()

  // Optimizely has one integer variable type; the SDK hands back an Integer, or a Long when the value is beyond Int
  // range. Both widen exactly to Long.
  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    variableEvaluation(key, defaultValue, ctx, "Long") {
      case i: java.lang.Integer => java.lang.Long.valueOf(i.longValue)
      case l: java.lang.Long    => l
    }()

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    variableEvaluation(key, defaultValue, ctx, "Double") {
      case d: java.lang.Double  => d
      case i: java.lang.Integer => java.lang.Double.valueOf(i.doubleValue)
      case l: java.lang.Long    => java.lang.Double.valueOf(l.doubleValue)
    }()

  // Reads the single variable named by `variableKey` (a JSON object), not the whole variables map.
  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    variableEvaluation(key, defaultValue, ctx, "Object") { case m: java.util.Map[_, _] =>
      new Value(Structure.mapToStructure(m.asInstanceOf[java.util.Map[String, Object]]))
    }()

  /** Shared scaffold for variable-backed evaluations. The variable named by `variableKey` is looked up raw and
    * converted strictly and losslessly by `convert`:
    *   - absent → `onAbsent` (by default the OF default with reason `DEFAULT`);
    *   - convertible → the value, with the decision's reason;
    *   - present with another type → the OF default with `TYPE_MISMATCH` — never a parse ("42") or a truncation (1.5).
    */
  private def variableEvaluation[A](key: String, defaultValue: A, ctx: OFEvaluationContext, expected: String)(
    convert: PartialFunction[AnyRef, A]
  )(
    onAbsent: OptimizelyDecision => ProviderEvaluation[A] = (d: OptimizelyDecision) =>
      success(d, defaultValue, usedDefault = true)
  ): ProviderEvaluation[A] =
    decide(key, ctx) match {
      case Right(d) =>
        val name = OptimizelyFeatureProvider.variableKey(ctx)
        lookupVariable(d, name) match {
          case None      => onAbsent(d)
          case Some(raw) =>
            convert.lift(raw) match {
              case Some(v) => success(d, v, usedDefault = false)
              case None    =>
                typeMismatch(defaultValue, s"variable '$name' is ${raw.getClass.getSimpleName}, expected $expected")
            }
        }
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  /** The raw variable value as the SDK typed it from the datafile (String, Boolean, Integer or Long, Double, or a
    * `java.util.Map` for JSON); `None` when the variable is absent or null.
    */
  private def lookupVariable(d: OptimizelyDecision, name: String): Option[AnyRef] =
    Option(d.getVariables).flatMap(vs => Option(vs.toMap)).flatMap(m => Option(m.get(name)))

  private def success[A](d: OptimizelyDecision, value: A, usedDefault: Boolean): ProviderEvaluation[A] = {
    // Builder calls are split across statements (not chained) because the SDK's SuperBuilder-style self-type confuses
    // Scala 2.13's existential resolution past the second fluent call.
    val builder = ProviderEvaluation.builder[A]()
    builder.value(value)
    builder.variant(d.getVariationKey)
    builder.reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
    builder.flagMetadata(metadataFrom(d))
    builder.build().asInstanceOf[ProviderEvaluation[A]]
  }

  private def typeMismatch[A](defaultValue: A, message: String): ProviderEvaluation[A] = {
    val builder = ProviderEvaluation.builder[A]()
    builder.value(defaultValue)
    builder.reason(Reason.ERROR.name())
    builder.errorCode(dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH)
    builder.errorMessage(message)
    builder.build().asInstanceOf[ProviderEvaluation[A]]
  }

  // Common decision pipeline. Returns Left with an error reason string when no decision is possible.
  //
  // Check order:
  //   1. Provider state — if not READY (never initialized, failed init, post-shutdown) we surface PROVIDER_NOT_READY
  //      without touching the underlying SDK. Calling `optimizely.isValid` post-shutdown would re-enter the polling
  //      HTTP client, which is closed by then, and throw — bug observed by OptimizelyProviderLifecycleSpec.
  //   2. Targeting key — caller-supplied context error; only meaningful once the provider is actually usable.
  //   3. `optimizely.isValid` — defence-in-depth in case state says READY but the SDK silently went invalid.
  /** Sends the call to Optimizely as a conversion for the context's user. Fails — so `FeatureFlags.track` fails with a
    * `FeatureFlagError` — when the provider is not ready, when the context has no targeting key (Optimizely would
    * record user ""), or when the datafile has no event with this key (Optimizely would drop it with an INFO log). A
    * factory-built provider only delivers conversions once its client has an event dispatcher.
    */
  override def track(
    eventName: String,
    ctx: OFEvaluationContext,
    details: dev.openfeature.sdk.TrackingEventDetails
  ): Unit = {
    val state  = stateRef.get()
    val userId = ContextTransformer.userId(ctx)
    if (state != ProviderState.READY && state != ProviderState.STALE)
      throw new ProviderNotReadyError(s"Optimizely provider is not ready ($state)")
    if (userId.isEmpty) throw new TargetingKeyMissingError("Optimizely tracking needs a targeting key")
    if (!optimizely.getProjectConfig.getEventNameMapping.containsKey(eventName))
      throw new GeneralError(s"Optimizely event '$eventName' is not in the datafile")
    optimizely
      .createUserContext(userId, ContextTransformer.attributes(ctx))
      .trackEvent(eventName, ContextTransformer.eventTags(details))
  }

  private def decide(key: String, ctx: OFEvaluationContext): Either[String, OptimizelyDecision] = {
    // Extract only the targeting key up front — cheap — and gate on it before normalizing attributes, so the
    // NOT_READY / missing-key / invalid short-circuits don't pay for a full attribute conversion whose result is
    // discarded.
    val userId = ContextTransformer.userId(ctx)
    val state  = stateRef.get()
    // STALE is an evaluable state per OpenFeature semantics: the provider keeps serving the last-known (aging)
    // datafile while the watchdog waits for polling to recover. Only genuinely non-ready states short-circuit.
    if (state != ProviderState.READY && state != ProviderState.STALE) Left("PROVIDER_NOT_READY")
    else if (userId.isEmpty) Left("TARGETING_KEY_MISSING")
    else if (!Try(optimizely.isValid).getOrElse(false)) Left("PROVIDER_NOT_READY")
    else
      Try(optimizely.createUserContext(userId, ContextTransformer.attributes(ctx)).decide(key)).toEither.left
        .map(t => Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
        .flatMap { decision =>
          if (isFlagNotFound(decision)) Left("FLAG_NOT_FOUND")
          else cmabFailure(decision).toLeft(decision)
        }
  }

  /** Identify a "flag not found" outcome from Optimizely's decision reasons. The Java SDK emits messages like `No flag
    * was found for key "..."` (via `DecisionMessage.FLAG_KEY_INVALID`); we match on a stable substring and the
    * `FLAG_KEY_INVALID` symbol so a future formatting tweak doesn't silently re-break this path.
    */
  /** A CMAB experiment whose decision could not be fetched comes back with no variation and an error reason ("Failed to
    * fetch CMAB data for experiment …", SDK 4.6). That is an evaluation error, not a decision to serve the default.
    */
  private def cmabFailure(d: OptimizelyDecision): Option[String] =
    if (Option(d.getVariationKey).isDefined) None
    else
      Option(d.getReasons)
        .map(_.asScala.toList)
        .getOrElse(Nil)
        .find(_.toLowerCase(java.util.Locale.ROOT).contains("failed to fetch cmab"))

  private def isFlagNotFound(d: OptimizelyDecision): Boolean =
    if (Option(d.getVariationKey).isDefined) false
    else {
      val errs = Option(d.getReasons).map(_.asScala.toList).getOrElse(Nil)
      errs.exists { reason =>
        val lower = reason.toLowerCase(java.util.Locale.ROOT)
        lower.contains("no flag was found") || lower.contains("flag_key_invalid")
      }
    }

  private def deriveReason(d: OptimizelyDecision): String =
    if (Option(d.getVariationKey).isDefined) Reason.TARGETING_MATCH.name() else Reason.DEFAULT.name()

  private def metadataFrom(d: OptimizelyDecision): ImmutableMetadata = {
    val builder = ImmutableMetadata.builder()
    Option(d.getRuleKey).foreach(builder.addString("optimizely.ruleKey", _))
    Option(d.getFlagKey).foreach(builder.addString("optimizely.flagKey", _))
    builder.build()
  }

  private def failingEvaluation[A](defaultValue: A, errorCode: String): ProviderEvaluation[A] = {
    val mapped = errorCode match {
      case "TARGETING_KEY_MISSING" => dev.openfeature.sdk.ErrorCode.TARGETING_KEY_MISSING
      case "PROVIDER_NOT_READY"    => dev.openfeature.sdk.ErrorCode.PROVIDER_NOT_READY
      case "FLAG_NOT_FOUND"        => dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND
      case _                       => dev.openfeature.sdk.ErrorCode.GENERAL
    }
    val builder = ProviderEvaluation.builder[A]()
    builder.value(defaultValue)
    builder.reason(Reason.ERROR.name())
    builder.errorCode(mapped)
    builder.errorMessage(errorCode)
    builder.build().asInstanceOf[ProviderEvaluation[A]]
  }
}

object OptimizelyFeatureProvider {
  val Name: String = "Optimizely"

  /** Tracks the `System.nanoTime()` instant of the last successful Optimizely datafile fetch. Updated by the observing
    * HTTP client (`com.optimizely.ab.ObservingOptimizelyHttpClient`) on every fetch that returns HTTP status &lt; 400,
    * and read by the staleness watchdog. Seeded to construction time so there is always a sane baseline; `initialize()`
    * reseeds it the moment the provider reaches READY.
    */
  final private[optimizely] class FetchObserver {
    private val lastSuccess = new AtomicLong(System.nanoTime())

    /** Called by the observing HTTP client on each successful datafile fetch. */
    def recordSuccess(): Unit = lastSuccess.set(System.nanoTime())

    /** Reseed the baseline to now — used when the provider announces READY so staleness is measured from a known-good
      * point rather than the observer's construction time.
      */
    def reset(): Unit = lastSuccess.set(System.nanoTime())

    def lastSuccessNanos: Long = lastSuccess.get()
  }

  /** Everything the staleness watchdog needs. Present only when the watchdog is enabled (datafile polling on).
    *
    * @param observer
    *   the fetch-success signal, shared with the observing HTTP client
    * @param staleAfter
    *   how long without a successful fetch before the provider is declared STALE
    * @param checkInterval
    *   how often the watchdog re-evaluates staleness
    */
  final private[optimizely] case class StalenessConfig(
    observer: FetchObserver,
    staleAfter: java.time.Duration,
    checkInterval: java.time.Duration
  )

  /** Daemon thread factory for the watchdog. Daemon so a leaked watchdog can never keep the JVM (or a forked test run)
    * alive — this repo is sensitive to non-daemon thread leaks (see the pre-push hook).
    */
  private val watchdogThreadFactory: ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, "optimizely-staleness-watchdog")
    t.setDaemon(true)
    t
  }

  /** Provider lifecycle: `Fresh -> Initialized -> ShutDown`, plus `ShutDown -> Initialized` for caller-managed clients
    * and `Failed -> Initialized` for a clean retry after a failed init. Tracked explicitly (instead of a boolean) so
    * initialize-after-shutdown can fail loudly when the underlying client was closed, and so a failed init does not
    * leave the provider stuck as a silent no-op on retry.
    */
  sealed private[optimizely] trait Lifecycle
  private[optimizely] object Lifecycle {
    case object Fresh       extends Lifecycle
    case object Initialized extends Lifecycle
    case object ShutDown    extends Lifecycle
    // A throwing initialize() lands here (state ERROR, handler removed). A subsequent initialize() cleanly re-attempts.
    case object Failed extends Lifecycle
  }

  /** Deterministic initial-load suppression: a datafile-update fire emits `PROVIDER_CONFIGURATION_CHANGED` iff the
    * current datafile revision differs from the revision the provider initialized with. A null `initialRevision` (the
    * initial-load fire arriving before init captured the revision) or an equal revision (the same initial datafile,
    * including a post-READY late delivery of the initial-load notification) is suppressed; a null `currentRevision`
    * (revision indeterminate) is suppressed rather than risk a spurious emit. Only a genuinely different revision
    * emits.
    */
  private[optimizely] def shouldEmitConfigChange(initialRevision: String, currentRevision: String): Boolean =
    initialRevision != null && currentRevision != null && currentRevision != initialRevision

  /** Context attribute key callers can set to override which Optimizely variable is read for typed evaluations. */
  val VariableKeyAttribute: String = "openfeature.variableKey"

  /** Default Optimizely variable name used by typed evaluations when the context doesn't override. */
  val DefaultVariableKey: String = "value"

  private[optimizely] def variableKey(ctx: OFEvaluationContext): String = {
    val v = Option(ctx).flatMap(c => Option(c.getValue(VariableKeyAttribute)))
    v.flatMap(value => Option(value.asString())).filter(_.nonEmpty).getOrElse(DefaultVariableKey)
  }
}
