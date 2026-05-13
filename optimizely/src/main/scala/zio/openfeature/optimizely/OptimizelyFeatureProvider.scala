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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
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
  *   - On `initialize()` we register an Optimizely `UpdateConfigNotification` handler. The first time it fires
  *     (datafile loaded), we count down an internal latch so the OpenFeature `setProviderAndWait` returns cleanly.
  *     Subsequent fires emit OpenFeature `PROVIDER_CONFIGURATION_CHANGED` events.
  *   - If the datafile never arrives within `initWait`, `initialize()` throws — propagated by the OpenFeature SDK as a
  *     failed init.
  *   - `shutdown()` removes the notification handler and closes the underlying client.
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
  closeOnShutdown: Boolean
) extends EventProvider {

  // Public construction is via the OptimizelyProvider factory in this package — the constructor is private to keep
  // lifecycle invariants (single initialize, registered handler) intact.

  private val stateRef           = new AtomicReference[ProviderState](ProviderState.NOT_READY)
  private val initialized        = new AtomicBoolean(false)
  private val notificationHandle = new AtomicInteger(-1)
  private val initLatch          = new CountDownLatch(1)

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = OptimizelyFeatureProvider.Name
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = stateRef.get()

  override def initialize(ctx: OFEvaluationContext): Unit = {
    if (!initialized.compareAndSet(false, true)) return

    val handlerId = optimizely.addUpdateConfigNotificationHandler { _ =>
      // The latch is single-use; subsequent updates are CONFIGURATION_CHANGED only.
      initLatch.countDown()
      emitProviderConfigurationChanged(ProviderEventDetails.builder().build())
    }
    // Optimizely's NotificationManager returns a non-positive id when registration fails (e.g. a duplicate handler
    // is already present). Without the handler we'd silently never count down the latch via the datafile-update
    // path and would always wait the full `initWait` window. Fail fast instead.
    if (handlerId <= 0) {
      stateRef.set(ProviderState.ERROR)
      throw new RuntimeException(
        s"Optimizely datafile update handler registration failed (returned id=$handlerId); cannot drive init"
      )
    }
    notificationHandle.set(handlerId)

    if (optimizely.isValid) initLatch.countDown()

    if (!initLatch.await(initWait.toMillis, TimeUnit.MILLISECONDS)) {
      stateRef.set(ProviderState.ERROR)
      throw new RuntimeException(
        s"Optimizely datafile did not load within $initWait; check the SDK key and network reachability"
      )
    }

    if (optimizely.isValid) stateRef.set(ProviderState.READY)
    else {
      stateRef.set(ProviderState.ERROR)
      throw new RuntimeException(
        "Optimizely client reported invalid configuration after datafile load (possible auth or parse failure)"
      )
    }
  }

  override def shutdown(): Unit = {
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
        ProviderEvaluation
          .builder[java.lang.Boolean]()
          .value(java.lang.Boolean.valueOf(d.getEnabled))
          .variant(d.getVariationKey)
          .reason(deriveReason(d))
          .flagMetadata(metadataFrom(d))
          .build()
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
        ProviderEvaluation
          .builder[String]()
          .value(value)
          .variant(d.getVariationKey)
          .reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
          .flagMetadata(metadataFrom(d))
          .build()
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
        ProviderEvaluation
          .builder[A]()
          .value(value)
          .variant(d.getVariationKey)
          .reason(if (usedDefault) Reason.DEFAULT.name() else deriveReason(d))
          .flagMetadata(metadataFrom(d))
          .build()
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
        val map   = Option(d.getVariables).map(_.toMap).getOrElse(java.util.Collections.emptyMap[String, Object]())
        val value = new Value(Structure.mapToStructure(map))
        ProviderEvaluation
          .builder[Value]()
          .value(value)
          .variant(d.getVariationKey)
          .reason(deriveReason(d))
          .flagMetadata(metadataFrom(d))
          .build()
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
    val transformed = ContextTransformer.transform(ctx)
    if (stateRef.get() != ProviderState.READY) Left("PROVIDER_NOT_READY")
    else if (transformed.userId.isEmpty) Left("TARGETING_KEY_MISSING")
    else if (!Try(optimizely.isValid).getOrElse(false)) Left("PROVIDER_NOT_READY")
    else
      Try(optimizely.createUserContext(transformed.userId, transformed.attributes).decide(key)).toEither.left
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
    ProviderEvaluation
      .builder[A]()
      .value(defaultValue)
      .reason(Reason.ERROR.name())
      .errorCode(mapped)
      .errorMessage(errorCode)
      .build()
  }
}

object OptimizelyFeatureProvider {
  val Name: String = "Optimizely"

  /** Context attribute key callers can set to override which Optimizely variable is read for typed evaluations. */
  val VariableKeyAttribute: String = "openfeature.variableKey"

  /** Default Optimizely variable name used by typed evaluations when the context doesn't override. */
  val DefaultVariableKey: String = "value"

  private[optimizely] def variableKey(ctx: OFEvaluationContext): String = {
    val v = Option(ctx).flatMap(c => Option(c.getValue(VariableKeyAttribute)))
    v.flatMap(value => Option(value.asString())).filter(_.nonEmpty).getOrElse(DefaultVariableKey)
  }
}
