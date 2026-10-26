package zio.openfeature

import zio._

/** A type-safe key for storing and retrieving values from HookData and HookHints.
  *
  * Using TypedKey instead of raw String keys ensures that the type of the value is tracked at compile time, avoiding
  * unsafe `asInstanceOf` casts.
  */
final case class TypedKey[A](name: String)

/** Per-hook mutable state that persists across hook stages within a single evaluation (spec 4.6.1).
  *
  * Unlike HookHints (which are read-only and shared), HookData is scoped to an individual hook instance. A hook can
  * store state in `before` and retrieve it in `after`, `error`, or `finallyAfter`.
  */
final class HookData {
  private val data = new java.util.concurrent.atomic.AtomicReference(Map.empty[String, Any])

  def set(key: String, value: Any): Unit = {
    data.updateAndGet(_ + (key -> value))
    ()
  }

  def get[A](key: String): Option[A] =
    data.get().get(key).map(_.asInstanceOf[A])

  def getOrElse[A](key: String, default: => A): A =
    get[A](key).getOrElse(default)

  def remove(key: String): Unit = {
    data.updateAndGet(_ - key)
    ()
  }

  def clear(): Unit =
    data.set(Map.empty)

  // Type-safe API

  def set[A](key: TypedKey[A], value: A): Unit = {
    data.updateAndGet(_ + (key.name -> value))
    ()
  }

  def get[A](key: TypedKey[A]): Option[A] =
    data.get().get(key.name).map(_.asInstanceOf[A])

  def getOrElse[A](key: TypedKey[A], default: => A): A =
    get(key).getOrElse(default)

  def remove[A](key: TypedKey[A]): Unit = {
    data.updateAndGet(_ - key.name)
    ()
  }

  /** The value stored under `key`, storing `create` first if absent. Atomic: concurrent callers get the same value. */
  private[openfeature] def getOrElseUpdate[A](key: TypedKey[A], create: => A): A = {
    lazy val fresh = create
    data
      .updateAndGet(m => if (m.contains(key.name)) m else m + (key.name -> fresh))
      .apply(key.name)
      .asInstanceOf[A]
  }
}

object HookData {
  def empty: HookData = new HookData
}

/** @param flagType
  *   the '''wire''' type the provider was asked for (`FlagType.wire`), which is what `supportedFlagTypes` filters on.
  *   For a scalar-backed custom type this is the scalar — e.g. `String` for a string-backed enum — while
  *   [[defaultValue]] here and `FlagResolution.value` in the `after` stage carry the '''domain''' value (the enum). So
  *   do not assume `defaultValue` has the class `flagType` names; casting it on that assumption throws.
  * @param defaultValue
  *   the caller's default, as the domain type
  * @param hookData
  *   this hook's private store for one evaluation. Pass the same instance to every stage of that evaluation; a
  *   composite (`FeatureHook.compose`) keeps its inner hooks' data inside it.
  */
final case class HookContext(
  flagKey: String,
  flagType: FlagValueType,
  defaultValue: Any,
  evaluationContext: EvaluationContext,
  clientMetadata: ClientMetadata,
  providerMetadata: ProviderMetadata,
  hookData: HookData = HookData.empty
)

final case class HookHints(values: Map[String, Any]) {
  def get[A](key: String): Option[A] =
    values.get(key).map(_.asInstanceOf[A])

  def getOrElse[A](key: String, default: => A): A =
    get[A](key).getOrElse(default)

  def +(entry: (String, Any)): HookHints =
    HookHints(values + entry)

  def ++(other: HookHints): HookHints =
    HookHints(values ++ other.values)

  // Type-safe API

  def get[A](key: TypedKey[A]): Option[A] =
    values.get(key.name).map(_.asInstanceOf[A])

  def getOrElse[A](key: TypedKey[A], default: => A): A =
    get(key).getOrElse(default)

  def add[A](key: TypedKey[A], value: A): HookHints =
    HookHints(values + (key.name -> value))
}

object HookHints {
  val empty: HookHints = HookHints(Map.empty[String, Any])

  def apply(entries: (String, Any)*): HookHints =
    HookHints(entries.toMap)
}

/** A ZIO-level evaluation hook (spec §4).
  *
  * '''Error semantics''': every stage returns `UIO` — hooks are infallible by construction and cannot abort an
  * evaluation. This is an intentional deviation from spec §4.4.6 (which aborts evaluation when a before/after hook
  * errors): a misbehaving observer (logging, metrics, audit) should never take flag evaluation down with it. Handle
  * failures inside the hook (`.catchAll`, `.ignoreLogged`, retries) — anything a stage must not crash on has to be
  * dealt with there, because there is no error channel for the pipeline to react to. Defects (`die`) in a hook will
  * still propagate and fail the evaluation fiber, so wrap genuinely untrusted code in `ZIO.attempt(...).ignoreLogged`
  * or similar.
  *
  * '''Error stage and error-code resolutions''' (spec §4.3.6/§4.4.6): the `error` stage runs both when an evaluation
  * fails through the typed error channel (`ProviderNotReady`/`ProviderFatal`) and when it returns a `FlagResolution`
  * carrying an error code (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`, ...). An error-code resolution is abnormal execution, so
  * it runs `error`, NOT `after` — `after` runs only for a clean resolution. A defect in a `before` or `after` hook also
  * runs `error` (spec §4.4.5), and the remaining hooks of that stage are skipped. `finallyAfter` always runs last, on
  * every exit; after an `after`-hook defect it receives the default value with reason `ERROR` and code `GENERAL` (spec
  * §4.4.8) — what the total tier serves — rather than the resolution `after` rejected.
  *
  * Provider-level hooks are not modeled here: they run inside the Java SDK evaluation call, per the OpenFeature
  * architecture (see #167). Java `dev.openfeature.sdk.Hook` instances can be registered at the Java API level via
  * `FeatureFlags.addApiHook` and likewise run inside the SDK.
  */
trait FeatureHook {

  /** The set of flag value types this hook supports. Hooks are only invoked for evaluations whose flag type is in this
    * set. By default all types are supported, matching the Java SDK's `Hook.supportsFlagValueType()` (spec 4.4.2.1).
    */
  def supportedFlagTypes: Set[FlagValueType] = FlagValueType.allTypes

  def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
    ZIO.none

  def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.unit

  def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.unit

  def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
    ZIO.unit
}

object FeatureHook {

  val noop: FeatureHook = new FeatureHook {}

  /** Per-evaluation timing for the timing hooks (#444): `start` in `before`, `elapsed` in a later stage. Kept in the
    * hook's own HookData, so it never leaks between hooks or evaluations.
    */
  final private class Stopwatch(name: String) {
    private val startKey = TypedKey[Long](s"$name.startTime")

    def start(ctx: HookContext): UIO[Unit] = Clock.nanoTime.flatMap(now => ZIO.succeed(ctx.hookData.set(startKey, now)))

    def elapsed(ctx: HookContext): UIO[Duration] =
      Clock.nanoTime.map(end => Duration.fromNanos(end - ctx.hookData.get(startKey).getOrElse(end)))
  }

  private val composeIds = new java.util.concurrent.atomic.AtomicLong(0L)

  /** Run `hooks` as one hook: `before` in order, the other stages in reverse.
    *
    * Each inner hook gets its own [[HookData]] per evaluation (spec 4.3.2), even when the composite is registered once
    * and shared, and even when the same hook instance appears twice. The per-hook data lives inside the `hookData` of
    * the context the composite receives, so callers that invoke a composite's stages directly must pass the same
    * `ctx.hookData` to every stage of one evaluation, as the evaluation pipeline does.
    */
  def compose(hooks: List[FeatureHook]): FeatureHook = new FeatureHook {
    private val indexed = hooks.zipWithIndex

    // One HookData per position (not per hook instance, so duplicates stay apart), created on first use in this
    // evaluation and stored in the HookData this composite was given for it.
    // The key is unique to this composite, so two composites handed the same HookData never read each other's slots.
    private val slotsKey = TypedKey[Vector[HookData]](s"zio.openfeature.compose.slots.${composeIds.incrementAndGet()}")

    private def slots(ctx: HookContext): Vector[HookData] =
      ctx.hookData.getOrElseUpdate(slotsKey, Vector.fill(hooks.size)(HookData.empty))

    // Filter hooks to those that support the given flag type (spec 4.4.2.1), keeping each one's position
    private def applicable(ctx: HookContext): List[(FeatureHook, HookData)] = {
      val data = slots(ctx)
      indexed.collect { case (hook, i) if hook.supportedFlagTypes.contains(ctx.flagType) => (hook, data(i)) }
    }

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO
        .foldLeft(applicable(ctx))((ctx.evaluationContext, false)) { case ((currentCtx, modified), (hook, data)) =>
          hook.before(ctx.copy(evaluationContext = currentCtx, hookData = data), hints).map {
            case Some(newCtx) => (currentCtx.merge(newCtx), true)
            case None         => (currentCtx, modified)
          }
        }
        .map { case (finalCtx, wasModified) => if (wasModified) Some(finalCtx) else None }

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicable(ctx).reverse) { case (hook, data) =>
        hook.after(ctx.copy(hookData = data), details, hints)
      }

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicable(ctx).reverse) { case (hook, data) =>
        hook.error(ctx.copy(hookData = data), err, hints)
      }

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicable(ctx).reverse) { case (hook, data) =>
        hook.finallyAfter(ctx.copy(hookData = data), details, hints)
      }
  }

  /** A hook that logs flag evaluations. `after` logs at Info; for per-stage levels use [[structuredLogging]]. */
  def logging(
    logBefore: Boolean = false,
    logAfter: Boolean = true,
    logError: Boolean = true
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO
        .when(logBefore)(
          ZIO.logDebug(s"Evaluating flag '${ctx.flagKey}' (${ctx.flagType.name})")
        )
        .as(None)

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO
        .when(logAfter)(
          ZIO.logInfo(s"Flag '${ctx.flagKey}' = ${details.value} (${details.reason})")
        )
        .unit

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO
        .when(logError)(
          ZIO.logError(s"Flag '${ctx.flagKey}' evaluation failed: ${err.message}")
        )
        .unit
  }

  /** A structured logging hook that adds machine-readable annotations to log output.
    *
    * Unlike `logging()` which produces plain text messages, this hook uses `ZIO.logAnnotate` to attach structured
    * fields (flag key, type, provider, reason, variant, duration) that are preserved by `zio-logging` backends (JSON,
    * SLF4J MDC, etc.). This enables filtering and querying flag evaluations in log aggregation systems.
    *
    * @param beforeLevel
    *   Log level for pre-evaluation messages. `None` disables before logging.
    * @param afterLevel
    *   Log level for successful evaluation messages.
    * @param errorLevel
    *   Log level for evaluation error messages.
    * @param logContext
    *   Whether to include the evaluation context (targeting key + attributes) in log annotations.
    * @param redactKeys
    *   Attribute keys to redact from logged context (e.g., `Set("email", "ip")`). Values are replaced with
    *   `"[REDACTED]"`. Only applies when `logContext` is true.
    */
  def structuredLogging(
    beforeLevel: Option[LogLevel] = Some(LogLevel.Debug),
    afterLevel: Option[LogLevel] = Some(LogLevel.Debug),
    errorLevel: Option[LogLevel] = Some(LogLevel.Warning),
    logContext: Boolean = false,
    redactKeys: Set[String] = Set.empty
  ): FeatureHook = new FeatureHook {
    private val stopwatch = new Stopwatch("structuredLogging")

    private def annotate(annotations: Set[zio.LogAnnotation])(effect: UIO[Unit]): UIO[Unit] =
      ZIO.logAnnotate(annotations)(effect)

    private def baseAnnotations(ctx: HookContext): Set[zio.LogAnnotation] =
      Set(
        zio.LogAnnotation("flag.key", ctx.flagKey),
        zio.LogAnnotation("flag.type", ctx.flagType.name),
        zio.LogAnnotation("flag.provider", ctx.providerMetadata.name)
      ) ++ ctx.clientMetadata.domain.map(d => zio.LogAnnotation("flag.domain", d)).toSet

    private def contextAnnotations(ctx: HookContext): Set[zio.LogAnnotation] =
      if (!logContext) Set.empty
      else {
        val targeting = ctx.evaluationContext.targetingKey
          .map(k => zio.LogAnnotation("flag.context.targetingKey", k))
          .toSet
        val attrs = ctx.evaluationContext.attributes.map { case (key, value) =>
          val v = if (redactKeys.contains(key)) "[REDACTED]" else renderAttributeValue(value)
          zio.LogAnnotation(s"flag.context.$key", v)
        }.toSet
        targeting ++ attrs
      }

    private def renderAttributeValue(attr: AttributeValue): String = attr match {
      case AttributeValue.BoolValue(v)    => v.toString
      case AttributeValue.StringValue(v)  => v
      case AttributeValue.IntValue(v)     => v.toString
      case AttributeValue.LongValue(v)    => v.toString
      case AttributeValue.DoubleValue(v)  => v.toString
      case AttributeValue.InstantValue(v) => v.toString
      case AttributeValue.ListValue(vs)   => vs.map(renderAttributeValue).mkString("[", ", ", "]")
      case AttributeValue.StructValue(m)  =>
        m.map { case (k, v) => s"$k=${renderAttributeValue(v)}" }.mkString("{", ", ", "}")
    }

    private def logAtLevel(level: LogLevel, message: String): UIO[Unit] =
      level match {
        case LogLevel.Trace   => ZIO.logTrace(message)
        case LogLevel.Debug   => ZIO.logDebug(message)
        case LogLevel.Info    => ZIO.logInfo(message)
        case LogLevel.Warning => ZIO.logWarning(message)
        case LogLevel.Error   => ZIO.logError(message)
        case _                => ZIO.logInfo(message)
      }

    // Uses HookData (per-hook mutable state) for start time instead of HookHints,
    // so that `before` can return None and avoid corrupting the compose pipeline's
    // context-modified tracking.
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      for {
        _ <- stopwatch.start(ctx)
        _ <- beforeLevel match {
          case Some(level) =>
            annotate(baseAnnotations(ctx) ++ contextAnnotations(ctx))(
              logAtLevel(level, s"Evaluating flag '${ctx.flagKey}'")
            )
          case None => ZIO.unit
        }
      } yield None

    private def elapsed(ctx: HookContext): UIO[Duration] = stopwatch.elapsed(ctx)

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      afterLevel match {
        case Some(level) =>
          elapsed(ctx).flatMap { duration =>
            val annotations = baseAnnotations(ctx) ++ contextAnnotations(ctx) ++ Set(
              zio.LogAnnotation("flag.value", String.valueOf(details.value)),
              zio.LogAnnotation("flag.reason", details.reason.toString),
              zio.LogAnnotation("flag.duration_ms", duration.toMillis.toString)
            ) ++ details.variant.map(v => zio.LogAnnotation("flag.variant", v)).toSet
            annotate(annotations)(
              logAtLevel(level, s"Flag '${ctx.flagKey}' = ${details.value} (${details.reason}, ${duration.toMillis}ms)")
            )
          }
        case None => ZIO.unit
      }

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      errorLevel match {
        case Some(level) =>
          elapsed(ctx).flatMap { duration =>
            val annotations = baseAnnotations(ctx) ++ contextAnnotations(ctx) ++ Set(
              zio.LogAnnotation("flag.error", err.message),
              zio.LogAnnotation("flag.error.type", err.getClass.getSimpleName),
              zio.LogAnnotation("flag.duration_ms", duration.toMillis.toString)
            )
            annotate(annotations)(
              logAtLevel(level, s"Flag '${ctx.flagKey}' evaluation failed: ${err.message}")
            )
          }
        case None => ZIO.unit
      }
  }

  def metrics(onEvaluation: (String, Duration, Boolean) => UIO[Unit]): FeatureHook =
    new FeatureHook {
      private val stopwatch = new Stopwatch("metrics")

      override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
        stopwatch.start(ctx).as(None)

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        stopwatch.elapsed(ctx).flatMap(onEvaluation(ctx.flagKey, _, true))

      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        stopwatch.elapsed(ctx).flatMap(onEvaluation(ctx.flagKey, _, false))
    }

  /** A detailed metrics hook that provides full evaluation context for building rich metric tags.
    *
    * Unlike `metrics()` which only passes the flag key, duration, and a success boolean, this hook gives callbacks
    * access to the full `HookContext` (flag key, type, provider, client metadata) and
    * `FlagResolution`/`FeatureFlagError`. This makes it easy to integrate with any metrics system that needs structured
    * tags.
    *
    * @param onSuccess
    *   Called after a successful evaluation with the hook context, resolution details, and duration
    * @param onError
    *   Called after a failed evaluation with the hook context, error, and duration
    */
  def metricsDetailed(
    onSuccess: (HookContext, FlagResolution[_], Duration) => UIO[Unit],
    onError: (HookContext, FeatureFlagError, Duration) => UIO[Unit]
  ): FeatureHook = new FeatureHook {
    private val stopwatch = new Stopwatch("metricsDetailed")

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      stopwatch.start(ctx).as(None)

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      stopwatch.elapsed(ctx).flatMap(onSuccess(ctx, details, _))

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      stopwatch.elapsed(ctx).flatMap(onError(ctx, err, _))
  }

  /** Logs a warning when an evaluation's context lacks the targeting key or attributes you require. It does not reject
    * the evaluation: hooks cannot abort one (see the hook error semantics on [[FeatureHook]]).
    */
  def contextValidator(
    requireTargetingKey: Boolean = false,
    requiredAttributes: List[String] = Nil
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] = {
      val keyWarning = Option
        .when(requireTargetingKey && ctx.evaluationContext.targetingKey.isEmpty)(
          s"Missing targeting key for flag '${ctx.flagKey}'"
        )
        .toList

      val attrWarnings = requiredAttributes
        .filterNot(ctx.evaluationContext.attributes.contains)
        .map(attr => s"Missing required attribute '$attr' for flag '${ctx.flagKey}'")

      ZIO.foreachDiscard(keyWarning ++ attrWarnings)(ZIO.logWarning(_)).as(None)
    }
  }
}
