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
}

object HookData {
  def empty: HookData = new HookData
}

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

trait FeatureHook {

  /** The set of flag value types this hook supports. Hooks are only invoked for evaluations whose flag type is in this
    * set. By default all types are supported, matching the Java SDK's `Hook.supportsFlagValueType()` (spec 4.4.2.1).
    */
  def supportedFlagTypes: Set[FlagValueType] = FlagValueType.allTypes

  def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
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

  def compose(hooks: List[FeatureHook]): FeatureHook = new FeatureHook {
    // Each hook gets its own HookData instance (spec 4.6.1 - scoped to individual hook)
    private lazy val hookDataMap: Map[FeatureHook, HookData] =
      hooks.map(h => h -> HookData.empty).toMap

    private def ctxForHook(ctx: HookContext, hook: FeatureHook): HookContext =
      ctx.copy(hookData = hookDataMap.getOrElse(hook, HookData.empty))

    // Filter hooks to those that support the given flag type (spec 4.4.2.1)
    private def applicableHooks(flagType: FlagValueType): List[FeatureHook] =
      hooks.filter(_.supportedFlagTypes.contains(flagType))

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO
        .foldLeft(applicableHooks(ctx.flagType))((ctx.evaluationContext, hints, false)) {
          case ((currentCtx, currentHints, modified), hook) =>
            hook.before(ctxForHook(ctx.copy(evaluationContext = currentCtx), hook), currentHints).map {
              case Some((newCtx, newHints)) => (currentCtx.merge(newCtx), newHints, true)
              case None                     => (currentCtx, currentHints, modified)
            }
        }
        .map { case (finalCtx, finalHints, wasModified) =>
          if (wasModified) Some((finalCtx, finalHints)) else None
        }

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicableHooks(ctx.flagType).reverse)(hook =>
        hook.after(ctxForHook(ctx, hook), details, hints)
      )

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicableHooks(ctx.flagType).reverse)(hook => hook.error(ctxForHook(ctx, hook), err, hints))

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(applicableHooks(ctx.flagType).reverse)(hook =>
        hook.finallyAfter(ctxForHook(ctx, hook), details, hints)
      )
  }

  def logging(
    logBefore: Boolean = false,
    logAfter: Boolean = true,
    logError: Boolean = true
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
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
    private val startTimeKey = TypedKey[Long]("structuredLogging.startTime")

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
          val v = if (redactKeys.contains(key)) "[REDACTED]" else value.toString
          zio.LogAnnotation(s"flag.context.$key", v)
        }.toSet
        targeting ++ attrs
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

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      beforeLevel match {
        case Some(level) =>
          for {
            now <- Clock.nanoTime
            _ <- annotate(baseAnnotations(ctx) ++ contextAnnotations(ctx))(
              logAtLevel(level, s"Evaluating flag '${ctx.flagKey}'")
            )
          } yield Some((ctx.evaluationContext, hints.add(startTimeKey, now)))
        case None =>
          Clock.nanoTime.map(now => Some((ctx.evaluationContext, hints.add(startTimeKey, now))))
      }

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      afterLevel match {
        case Some(level) =>
          for {
            end <- Clock.nanoTime
            start    = hints.getOrElse(startTimeKey, end)
            duration = Duration.fromNanos(end - start)
            annotations = baseAnnotations(ctx) ++ Set(
              zio.LogAnnotation("flag.value", String.valueOf(details.value)),
              zio.LogAnnotation("flag.reason", details.reason.toString),
              zio.LogAnnotation("flag.duration_ms", duration.toMillis.toString)
            ) ++ details.variant.map(v => zio.LogAnnotation("flag.variant", v)).toSet
            _ <- annotate(annotations)(
              logAtLevel(level, s"Flag '${ctx.flagKey}' = ${details.value} (${details.reason}, ${duration.toMillis}ms)")
            )
          } yield ()
        case None => ZIO.unit
      }

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      errorLevel match {
        case Some(level) =>
          for {
            end <- Clock.nanoTime
            start    = hints.getOrElse(startTimeKey, end)
            duration = Duration.fromNanos(end - start)
            annotations = baseAnnotations(ctx) ++ Set(
              zio.LogAnnotation("flag.error", err.message),
              zio.LogAnnotation("flag.error.type", err.getClass.getSimpleName),
              zio.LogAnnotation("flag.duration_ms", duration.toMillis.toString)
            )
            _ <- annotate(annotations)(
              logAtLevel(level, s"Flag '${ctx.flagKey}' evaluation failed: ${err.message}")
            )
          } yield ()
        case None => ZIO.unit
      }
  }

  def metrics(onEvaluation: (String, Duration, Boolean) => UIO[Unit]): FeatureHook =
    new FeatureHook {
      private val startTimeKey = TypedKey[Long]("metrics.startTime")

      override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
        Clock.nanoTime.map { start =>
          Some((ctx.evaluationContext, hints.add(startTimeKey, start)))
        }

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        for {
          end <- Clock.nanoTime
          start    = hints.getOrElse(startTimeKey, end)
          duration = Duration.fromNanos(end - start)
          _ <- onEvaluation(ctx.flagKey, duration, true)
        } yield ()

      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        for {
          end <- Clock.nanoTime
          start    = hints.getOrElse(startTimeKey, end)
          duration = Duration.fromNanos(end - start)
          _ <- onEvaluation(ctx.flagKey, duration, false)
        } yield ()
    }

  def contextValidator(
    requireTargetingKey: Boolean = false,
    requiredAttributes: List[String] = Nil
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] = {
      val warnings = List.newBuilder[String]

      if (requireTargetingKey && ctx.evaluationContext.targetingKey.isEmpty)
        warnings += s"Missing targeting key for flag '${ctx.flagKey}'"

      for (attr <- requiredAttributes)
        if (!ctx.evaluationContext.attributes.contains(attr))
          warnings += s"Missing required attribute '$attr' for flag '${ctx.flagKey}'"

      val warningList = warnings.result()
      ZIO.foreachDiscard(warningList)(msg => ZIO.logWarning(msg)).as(None)
    }
  }
}
