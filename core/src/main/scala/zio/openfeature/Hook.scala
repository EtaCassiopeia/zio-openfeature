package zio.openfeature

import zio.*

enum FlagValueType:
  case Boolean
  case String
  case Int
  case Double
  case Object

  def name: String = this match
    case Boolean => "Boolean"
    case String  => "String"
    case Int     => "Int"
    case Double  => "Double"
    case Object  => "Object"

object FlagValueType:
  def fromFlagType[A](using ft: FlagType[A]): FlagValueType =
    ft.typeName match
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Double"  => Double
      case _         => Object

final case class HookContext(
  flagKey: String,
  flagType: FlagValueType,
  defaultValue: Any,
  evaluationContext: EvaluationContext,
  providerMetadata: ProviderMetadata
)

final case class HookHints(values: Map[String, Any]):
  def get[A](key: String): Option[A] =
    values.get(key).map(_.asInstanceOf[A])

  def getOrElse[A](key: String, default: => A): A =
    get[A](key).getOrElse(default)

  def +(entry: (String, Any)): HookHints =
    HookHints(values + entry)

  def ++(other: HookHints): HookHints =
    HookHints(values ++ other.values)

object HookHints:
  val empty: HookHints = HookHints(Map.empty)

  def apply(entries: (String, Any)*): HookHints =
    HookHints(entries.toMap)

trait FeatureHook:
  def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
    ZIO.none

  def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
    ZIO.unit

  def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
    ZIO.unit

  def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
    ZIO.unit

object FeatureHook:

  val noop: FeatureHook = new FeatureHook {}

  def compose(hooks: List[FeatureHook]): FeatureHook = new FeatureHook:
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO
        .foldLeft(hooks)((ctx.evaluationContext, hints, false)) { case ((currentCtx, currentHints, modified), hook) =>
          hook.before(ctx.copy(evaluationContext = currentCtx), currentHints).map {
            case Some((newCtx, newHints)) => (newCtx, newHints, true)
            case None                     => (currentCtx, currentHints, modified)
          }
        }
        .map { case (finalCtx, finalHints, wasModified) =>
          if wasModified then Some((finalCtx, finalHints)) else None
        }

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks)(_.after(ctx, details, hints))

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks)(_.error(ctx, err, hints))

    override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
      ZIO.foreachDiscard(hooks)(_.finallyAfter(ctx, hints))

  def logging(
    logBefore: Boolean = false,
    logAfter: Boolean = true,
    logError: Boolean = true
  ): FeatureHook = new FeatureHook:
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

  def metrics(onEvaluation: (String, Duration, Boolean) => UIO[Unit]): FeatureHook =
    new FeatureHook:
      private val startTimeKey = "metrics.startTime"

      override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
        Clock.nanoTime.map { start =>
          Some((ctx.evaluationContext, hints + (startTimeKey -> start)))
        }

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        for
          end <- Clock.nanoTime
          start    = hints.getOrElse[Long](startTimeKey, end)
          duration = Duration.fromNanos(end - start)
          _ <- onEvaluation(ctx.flagKey, duration, true)
        yield ()

      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        for
          end <- Clock.nanoTime
          start    = hints.getOrElse[Long](startTimeKey, end)
          duration = Duration.fromNanos(end - start)
          _ <- onEvaluation(ctx.flagKey, duration, false)
        yield ()

  def contextValidator(
    requireTargetingKey: Boolean = false,
    requiredAttributes: List[String] = Nil
  ): FeatureHook = new FeatureHook:
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      val warnings = List.newBuilder[String]

      if requireTargetingKey && ctx.evaluationContext.targetingKey.isEmpty then
        warnings += s"Missing targeting key for flag '${ctx.flagKey}'"

      for attr <- requiredAttributes do
        if !ctx.evaluationContext.attributes.contains(attr) then
          warnings += s"Missing required attribute '$attr' for flag '${ctx.flagKey}'"

      val warningList = warnings.result()
      ZIO.foreachDiscard(warningList)(msg => ZIO.logWarning(msg)).as(None)
