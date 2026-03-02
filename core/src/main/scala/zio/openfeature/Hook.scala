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

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO
        .foldLeft(hooks)((ctx.evaluationContext, hints, false)) { case ((currentCtx, currentHints, modified), hook) =>
          hook.before(ctxForHook(ctx.copy(evaluationContext = currentCtx), hook), currentHints).map {
            case Some((newCtx, newHints)) => (currentCtx.merge(newCtx), newHints, true)
            case None                     => (currentCtx, currentHints, modified)
          }
        }
        .map { case (finalCtx, finalHints, wasModified) =>
          if (wasModified) Some((finalCtx, finalHints)) else None
        }

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks.reverse)(hook => hook.after(ctxForHook(ctx, hook), details, hints))

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks.reverse)(hook => hook.error(ctxForHook(ctx, hook), err, hints))

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks.reverse)(hook => hook.finallyAfter(ctxForHook(ctx, hook), details, hints))
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
