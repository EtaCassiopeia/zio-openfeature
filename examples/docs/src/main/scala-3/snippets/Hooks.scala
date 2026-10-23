package snippets

import zio.*
import zio.openfeature.*

/** Code shown on the Hooks page. */
object Hooks:

  // --8<-- [start:custom]
  val audit: FeatureHook = new FeatureHook:
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.logInfo(s"${ctx.evaluationContext.targetingKey.getOrElse("anonymous")} got ${ctx.flagKey} = ${details.value}")

    override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ZIO.logWarning(s"${ctx.flagKey} failed: ${error.message}")
  // --8<-- [end:custom]

  // --8<-- [start:register]
  val register: ZIO[FeatureFlags, Nothing, Unit] =
    for
      _ <- FeatureFlags.addHook(audit)                                            // client: this FeatureFlags instance
      _ <- FeatureFlags.addHooks(List(FeatureHook.logging()))                     // several at once, run in list order
      _ <- FeatureFlags.addZioApiHook(FeatureHook.metrics((_, _, _) => ZIO.unit)) // runs before client hooks
    yield ()

  // Invocation: hooks and hints for one call only
  val once: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    FeatureFlags.booleanDetails(
      "new-ui",
      false,
      EvaluationContext.empty,
      EvaluationOptions(audit).withHint("requestId", "r-17")
    )

  // At construction, before the first evaluation
  val config: FeatureFlagsConfig = FeatureFlagsConfig().withHook(audit)
  // --8<-- [end:register]

  // --8<-- [start:builtins]
  val logging: FeatureHook = FeatureHook.logging(logBefore = false, logAfter = true, logError = true)

  val structured: FeatureHook = FeatureHook.structuredLogging(
    afterLevel = Some(LogLevel.Info),
    logContext = true,
    redactKeys = Set("email") // logged as [REDACTED]
  )

  val metrics: FeatureHook = FeatureHook.metrics { (flagKey, duration, success) =>
    ZIO.logInfo(s"$flagKey took ${duration.toMillis} ms (success = $success)")
  }

  val detailedMetrics: FeatureHook = FeatureHook.metricsDetailed(
    onSuccess = (ctx, resolution, duration) => ZIO.logInfo(s"${ctx.flagKey} -> ${resolution.reason} in $duration"),
    onError = (ctx, error, duration) => ZIO.logWarning(s"${ctx.flagKey} failed after $duration: ${error.message}")
  )

  // Logs a warning when the context lacks these; it never fails the evaluation
  val validator: FeatureHook = FeatureHook.contextValidator(
    requireTargetingKey = true,
    requiredAttributes = List("plan")
  )
  // --8<-- [end:builtins]

  // --8<-- [start:enrich]
  // `before` may return extra context; it is merged on top of everything else
  def stampRegion(region: String): FeatureHook = new FeatureHook:
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO.some(EvaluationContext.empty.withAttribute("region", region))
  // --8<-- [end:enrich]

  // --8<-- [start:hook-data]
  // HookData is private to one hook for one evaluation: set it in `before`, read it in a later stage
  val timer: FeatureHook = new FeatureHook:
    private val startedAt = TypedKey[Long]("timer.startedAt")

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      Clock.nanoTime.map(now => ctx.hookData.set(startedAt, now)).as(None)

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      Clock.nanoTime.flatMap { now =>
        val took = ctx.hookData.get(startedAt).fold(0L)(now - _)
        ZIO.logDebug(s"${ctx.flagKey} took ${took / 1000} µs")
      }
  // --8<-- [end:hook-data]

  // --8<-- [start:hints]
  // Hints are read-only values the caller hands to every hook of one evaluation
  val requestIdLogger: FeatureHook = new FeatureHook:
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.logInfo(s"[${hints.getOrElse[String]("requestId", "-")}] ${ctx.flagKey} = ${details.value}")
  // --8<-- [end:hints]

  // --8<-- [start:flag-types]
  // Only runs for String flags (and string-backed custom types such as enums)
  val stringsOnly: FeatureHook = new FeatureHook:
    override def supportedFlagTypes: Set[FlagValueType] = Set(FlagValueType.String)

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.logInfo(s"variant ${details.value} for ${ctx.flagKey}")
  // --8<-- [end:flag-types]

  // --8<-- [start:java-hook]
  // A hook written against the OpenFeature Java SDK runs inside the SDK call
  val javaHook: dev.openfeature.sdk.Hook[Object] = new dev.openfeature.sdk.Hook[Object] {}

  val registerJava: ZIO[FeatureFlags, Nothing, Unit] = FeatureFlags.addApiHook(javaHook)
  // --8<-- [end:java-hook]

  // --8<-- [start:manage]
  val manage: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    for
      client <- FeatureFlags.hooks         // the client hooks, in order
      api    <- FeatureFlags.zioApiHooks   // the ZIO API hooks
      _      <- FeatureFlags.clearHooks
      _      <- FeatureFlags.clearZioApiHooks
      _      <- FeatureFlags.clearApiHooks // the Java SDK hooks
    yield api ++ client
  // --8<-- [end:manage]

  // --8<-- [start:safe]
  // Stages return UIO: a hook cannot fail an evaluation. Handle anything that may fail inside it.
  def safe(send: String => Task[Unit]): FeatureHook = new FeatureHook:
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      send(ctx.flagKey).ignoreLogged
  // --8<-- [end:safe]
