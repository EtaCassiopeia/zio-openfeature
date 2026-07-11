package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.optimizelydecision.OptimizelyDecision
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
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** OpenFeature `FeatureProvider` implementation that delegates to the Optimizely Java SDK.
  *
  * Construction is intentionally simple: take an already-configured `Optimizely` client and adapt its decision API to
  * OpenFeature's evaluation API. The Optimizely client is responsible for its own datafile polling, error handling, and
  * event dispatch; this class translates between the two worlds.
  *
  * '''Lifecycle notes:'''
  *   - On `initialize()` we register an Optimizely `UpdateConfigNotification` handler. It counts down an internal latch
  *     so the OpenFeature `setProviderAndWait` returns cleanly once the datafile has loaded. Fires that occur before
  *     the provider announces READY (the initial datafile load) suppress the `PROVIDER_CONFIGURATION_CHANGED` event —
  *     it isn't a change and must not precede PROVIDER_READY. Only fires once the provider is READY (genuine datafile
  *     revisions) emit `PROVIDER_CONFIGURATION_CHANGED`.
  *   - If the datafile never arrives within `initWait`, or the config is invalid, or the handler can't be registered,
  *     `initialize()` throws (propagated by the OpenFeature SDK as a failed init) and leaves the provider in a `Failed`
  *     state with its handler removed. A subsequent `initialize()` on the same instance cleanly re-attempts (`Failed ->
  *     Initialized`) instead of silently no-op'ing and leaving every evaluation `PROVIDER_NOT_READY`.
  *   - A `shutdown()` racing an in-flight `initialize()` aborts the init cleanly: the handler it registered is removed
  *     and the provider is left NOT_READY (it never reports READY after a shutdown).
  *   - `shutdown()` removes the notification handler and closes the underlying client (which stops datafile polling and
  *     the HTTP client for factory-built providers).
  *   - Factory-built providers (via `OptimizelyProvider.make`/`scoped`/`layer`) perform no network activity and run no
  *     background polling until `initialize()` — a provider that is constructed but never registered does not fetch,
  *     retry, or log against an unreachable CDN.
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
  // Present for factory-built providers: datafile polling is deliberately NOT running at construction
  // (see OptimizelyProvider.buildClient); initialize() starts it, and shutdown() stops it via
  // Optimizely.close(). Caller-managed clients (fromOptimizelyClient) own their polling lifecycle.
  configManager: Option[com.optimizely.ab.config.HttpProjectConfigManager] = None
) extends EventProvider {

  // Public construction is via the OptimizelyProvider factory in this package — the constructor is private to keep
  // lifecycle invariants (single initialize, registered handler) intact.

  import OptimizelyFeatureProvider.Lifecycle

  private val stateRef           = new AtomicReference[ProviderState](ProviderState.NOT_READY)
  private val lifecycle          = new AtomicReference[Lifecycle](Lifecycle.Fresh)
  private val notificationHandle = new AtomicInteger(-1)
  // Replaced on re-initialization (caller-managed clients only); each init cycle gets a single-use latch.
  private val initLatchRef = new AtomicReference(new CountDownLatch(1))

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

    val handlerId = optimizely.addUpdateConfigNotificationHandler { _ =>
      // Suppress the CONFIGURATION_CHANGED event for any fire before the provider is READY — the initial datafile
      // load isn't a "change" and would otherwise be delivered ahead of PROVIDER_READY. Only fires once the provider
      // has announced READY (genuine datafile revisions) emit. Reading the state BEFORE counting the latch down is
      // deliberate: the initial-load fire that opens the latch is, at that instant, still pre-READY (init is blocked
      // on `initLatch.await` below and only sets READY after it returns), so it is reliably suppressed. A first fire
      // that is instead observed via the `optimizely.isValid` fast-path below (handler never sees the initial load,
      // e.g. the datafile loaded before registration) simply never reaches this handler, so nothing is emitted.
      val alreadyReady = stateRef.get() == ProviderState.READY
      initLatch.countDown()
      if (alreadyReady) emitProviderConfigurationChanged(ProviderEventDetails.builder().build())
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
        Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
        ()
      }
      stateRef.set(ProviderState.NOT_READY)
      return
    }

    // The handler is registered before this call so a notification firing from here on can't be missed. The SDK
    // may already have a fetch in flight or completed from construction time (see OptimizelyProvider#buildClient),
    // in which case this `start()` is just a no-op (idempotent) — the `optimizely.isValid` check right below is
    // what catches that already-completed case.
    configManager.foreach(_.start())

    if (optimizely.isValid) initLatch.countDown()

    if (!initLatch.await(initWait.toMillis, TimeUnit.MILLISECONDS))
      failInitialize(
        s"Optimizely datafile did not load within $initWait; check the SDK key and network reachability"
      )

    if (optimizely.isValid) {
      stateRef.set(ProviderState.READY)
      // Guard against a shutdown() that interleaved after the abort re-check above: shutdown sets
      // lifecycle = ShutDown first thing, so if it did, revert to NOT_READY rather than report READY on a
      // shut-down provider.
      if (lifecycle.get() != Lifecycle.Initialized) stateRef.set(ProviderState.NOT_READY)
    } else
      failInitialize(
        "Optimizely client reported invalid configuration after datafile load (possible auth or parse failure)"
      )
  }

  /** Common failure path for `initialize()`: remove the handler registered this cycle (best-effort, as `shutdown` does)
    * so a retry doesn't leak it or fire duplicate CONFIGURATION_CHANGED events, mark the provider `Failed` (state
    * ERROR) so a subsequent `initialize()` cleanly re-attempts instead of silently no-op'ing, then throw.
    */
  private def failInitialize(msg: String): Nothing = {
    val handle = notificationHandle.getAndSet(-1)
    if (handle > 0) {
      Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
      ()
    }
    stateRef.set(ProviderState.ERROR)
    lifecycle.set(Lifecycle.Failed)
    throw new RuntimeException(msg)
  }

  override def shutdown(): Unit = {
    lifecycle.set(Lifecycle.ShutDown)
    val handle = notificationHandle.getAndSet(-1)
    if (handle > 0) {
      // Removing the handler is best-effort; if the notification center is already shut down we ignore.
      Try(optimizely.getNotificationCenter.removeNotificationListener(handle))
      ()
    }
    if (closeOnShutdown) Try(optimizely.close())
    stateRef.set(ProviderState.NOT_READY)
  }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    decide(key, ctx) match {
      case Right(d) =>
        // Builder calls are split across statements (not chained) because the SDK's SuperBuilder-style
        // self-type confuses Scala 2.13's existential resolution past the second fluent call.
        val builder = ProviderEvaluation.builder[java.lang.Boolean]()
        builder.value(java.lang.Boolean.valueOf(d.getEnabled))
        builder.variant(d.getVariationKey)
        builder.reason(deriveReason(d))
        builder.flagMetadata(metadataFrom(d))
        builder.build().asInstanceOf[ProviderEvaluation[java.lang.Boolean]]
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    decide(key, ctx) match {
      case Right(d) =>
        val variableKey = OptimizelyFeatureProvider.variableKey(ctx)
        val variable    = readVariable[String](d, variableKey, classOf[String])
        // Fallback order: typed variable → variation key (still a meaningful Optimizely-driven answer) → OF default.
        val (value, usedDefault) = variable match {
          case Some(v) => (v, false)
          case None =>
            Option(d.getVariationKey) match {
              case Some(vk) => (vk, false)
              case None     => (defaultValue, true)
            }
        }
        val builder = ProviderEvaluation.builder[String]()
        builder.value(value)
        builder.variant(d.getVariationKey)
        builder.reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
        builder.flagMetadata(metadataFrom(d))
        builder.build().asInstanceOf[ProviderEvaluation[String]]
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    typedEvaluation[java.lang.Integer](key, defaultValue, ctx, classOf[java.lang.Integer])

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    typedEvaluation[java.lang.Double](key, defaultValue, ctx, classOf[java.lang.Double])

  /** Shared scaffold for Integer/Double evaluations: extract the named variable, fall back to the OF default with a
    * `DEFAULT` reason if the variable is missing. The Optimizely SDK throws `JsonParseException` from `getValue` when
    * the key is absent, so we wrap in `Try` and treat any failure as a missing variable.
    */
  private def typedEvaluation[A](
    key: String,
    defaultValue: A,
    ctx: OFEvaluationContext,
    clazz: Class[A]
  ): ProviderEvaluation[A] =
    decide(key, ctx) match {
      case Right(d) =>
        val variableKey = OptimizelyFeatureProvider.variableKey(ctx)
        val variable    = readVariable[A](d, variableKey, clazz)
        val (value, usedDefault) = variable match {
          case Some(v) => (v, false)
          case None    => (defaultValue, true)
        }
        val builder = ProviderEvaluation.builder[A]()
        builder.value(value)
        builder.variant(d.getVariationKey)
        builder.reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
        builder.flagMetadata(metadataFrom(d))
        builder.build().asInstanceOf[ProviderEvaluation[A]]
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  /** Reads the named variable as type `A`, swallowing the JSON-parse failures Optimizely throws when the key is absent.
    * `clazz` tells the Optimizely SDK which Java type to extract.
    */
  private def readVariable[A](d: OptimizelyDecision, name: String, clazz: Class[A]): Option[A] =
    Try(d.getVariables.getValue(name, clazz)).toOption.flatMap(Option(_))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    decide(key, ctx) match {
      case Right(d) =>
        // Mirror the other typed paths: read the single variable named by `variableKey` (a JSON object) rather than
        // returning the whole variables map, and fall back to the OF default with reason DEFAULT when it is absent.
        // Previously object evaluation ignored `variableKey` and never reached `defaultValue` (#264).
        val variableKey = OptimizelyFeatureProvider.variableKey(ctx)
        val variable    = readVariable[java.util.Map[_, _]](d, variableKey, classOf[java.util.Map[_, _]])
        val (value, usedDefault) = variable match {
          case Some(m) => (new Value(Structure.mapToStructure(m.asInstanceOf[java.util.Map[String, Object]])), false)
          case None    => (defaultValue, true)
        }
        val builder = ProviderEvaluation.builder[Value]()
        builder.value(value)
        builder.variant(d.getVariationKey)
        builder.reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
        builder.flagMetadata(metadataFrom(d))
        builder.build().asInstanceOf[ProviderEvaluation[Value]]
      case Left(err) => failingEvaluation(defaultValue, err)
    }

  // Common decision pipeline. Returns Left with an error reason string when no decision is possible.
  //
  // Check order:
  //   1. Provider state — if not READY (never initialized, failed init, post-shutdown) we surface PROVIDER_NOT_READY
  //      without touching the underlying SDK. Calling `optimizely.isValid` post-shutdown would re-enter the polling
  //      HTTP client, which is closed by then, and throw — bug observed by OptimizelyProviderLifecycleSpec.
  //   2. Targeting key — caller-supplied context error; only meaningful once the provider is actually usable.
  //   3. `optimizely.isValid` — defence-in-depth in case state says READY but the SDK silently went invalid.
  private def decide(key: String, ctx: OFEvaluationContext): Either[String, OptimizelyDecision] = {
    // Extract only the targeting key up front — cheap — and gate on it before normalizing attributes, so the
    // NOT_READY / missing-key / invalid short-circuits don't pay for a full attribute conversion whose result is
    // discarded (#266).
    val userId = ContextTransformer.userId(ctx)
    if (stateRef.get() != ProviderState.READY) Left("PROVIDER_NOT_READY")
    else if (userId.isEmpty) Left("TARGETING_KEY_MISSING")
    else if (!Try(optimizely.isValid).getOrElse(false)) Left("PROVIDER_NOT_READY")
    else
      Try(optimizely.createUserContext(userId, ContextTransformer.attributes(ctx)).decide(key)).toEither.left
        .map(t => Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
        .flatMap { decision =>
          if (isFlagNotFound(decision)) Left("FLAG_NOT_FOUND")
          else Right(decision)
        }
  }

  /** Identify a "flag not found" outcome from Optimizely's decision reasons. The Java SDK emits messages like `No flag
    * was found for key "..."` (via `DecisionMessage.FLAG_KEY_INVALID`); we match on a stable substring and the
    * `FLAG_KEY_INVALID` symbol so a future formatting tweak doesn't silently re-break this path.
    */
  private def isFlagNotFound(d: OptimizelyDecision): Boolean =
    if (Option(d.getVariationKey).isDefined) false
    else {
      val errs = Option(d.getReasons).map(_.asScala.toList).getOrElse(Nil)
      errs.exists { reason =>
        val lower = reason.toLowerCase
        lower.contains("no flag was found") || lower.contains("flag_key_invalid")
      }
    }

  private def deriveReason(d: OptimizelyDecision): String = {
    val errs = Option(d.getReasons).map(_.asScala.toList).getOrElse(Nil)
    if (errs.isEmpty && Option(d.getVariationKey).isDefined) Reason.TARGETING_MATCH.name()
    else if (Option(d.getVariationKey).isEmpty) Reason.DEFAULT.name()
    else Reason.TARGETING_MATCH.name()
  }

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

  /** Context attribute key callers can set to override which Optimizely variable is read for typed evaluations. */
  val VariableKeyAttribute: String = "openfeature.variableKey"

  /** Default Optimizely variable name used by typed evaluations when the context doesn't override. */
  val DefaultVariableKey: String = "value"

  private[optimizely] def variableKey(ctx: OFEvaluationContext): String = {
    val v = Option(ctx).flatMap(c => Option(c.getValue(VariableKeyAttribute)))
    v.flatMap(value => Option(value.asString())).filter(_.nonEmpty).getOrElse(DefaultVariableKey)
  }
}
