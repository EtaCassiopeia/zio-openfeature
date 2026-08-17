package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.FeatureFlagsState
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, ProviderState}
import dev.openfeature.sdk.multiprovider.{MultiProvider, Strategy, FirstMatchStrategy, FirstSuccessfulStrategy}
import java.util.concurrent.TimeoutException

trait FeatureFlags {

  // Abstract detailed evaluation methods (one per type, with defaults for ctx and options)

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Boolean]]

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[String]]

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Int]]

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Long]]

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Double]]

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[A]]

  // Concrete simple evaluation methods (delegate to details)

  def boolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean] =
    booleanDetails(key, default).map(_.value)

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): IO[FeatureFlagError, Boolean] =
    booleanDetails(key, default, ctx).map(_.value)

  def string(key: String, default: String): IO[FeatureFlagError, String] =
    stringDetails(key, default).map(_.value)

  def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String] =
    stringDetails(key, default, ctx).map(_.value)

  def int(key: String, default: Int): IO[FeatureFlagError, Int] =
    intDetails(key, default).map(_.value)

  def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int] =
    intDetails(key, default, ctx).map(_.value)

  def long(key: String, default: Long): IO[FeatureFlagError, Long] =
    longDetails(key, default).map(_.value)

  def long(key: String, default: Long, ctx: EvaluationContext): IO[FeatureFlagError, Long] =
    longDetails(key, default, ctx).map(_.value)

  def double(key: String, default: Double): IO[FeatureFlagError, Double] =
    doubleDetails(key, default).map(_.value)

  def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double] =
    doubleDetails(key, default, ctx).map(_.value)

  def obj(key: String, default: Map[String, Any]): IO[FeatureFlagError, Map[String, Any]] =
    objDetails(key, default).map(_.value)

  def obj(key: String, default: Map[String, Any], ctx: EvaluationContext): IO[FeatureFlagError, Map[String, Any]] =
    objDetails(key, default, ctx).map(_.value)

  def value[A: FlagType](key: String, default: A): IO[FeatureFlagError, A] =
    valueDetails(key, default).map(_.value)

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A] =
    valueDetails(key, default, ctx).map(_.value)

  // Total (never-fails) evaluation variants (spec §1.4.10 / §1.1.7: evaluation "MUST NOT throw ... always return the
  // default value"). Any evaluation error is absorbed into the supplied default: `*OrDefault` returns the value,
  // `resolveOrDefault` returns the full `FlagResolution` with `reason = Error` and `errorCode`/`errorMessage` populated
  // so the caller can still see WHY the default was served. Both typed `FeatureFlagError`s and defects (unexpected
  // exceptions) are absorbed — the opt-in "give me a value no matter what" contract — but fiber interruption is always
  // propagated, so cancellation still works. The non-total methods above remain for callers who want to handle errors.

  def booleanOrDefault(key: String, default: Boolean, ctx: EvaluationContext = EvaluationContext.empty): UIO[Boolean] =
    totalResolution(booleanDetails(key, default, ctx), key, default).map(_.value)

  def stringOrDefault(key: String, default: String, ctx: EvaluationContext = EvaluationContext.empty): UIO[String] =
    totalResolution(stringDetails(key, default, ctx), key, default).map(_.value)

  def intOrDefault(key: String, default: Int, ctx: EvaluationContext = EvaluationContext.empty): UIO[Int] =
    totalResolution(intDetails(key, default, ctx), key, default).map(_.value)

  def longOrDefault(key: String, default: Long, ctx: EvaluationContext = EvaluationContext.empty): UIO[Long] =
    totalResolution(longDetails(key, default, ctx), key, default).map(_.value)

  def doubleOrDefault(key: String, default: Double, ctx: EvaluationContext = EvaluationContext.empty): UIO[Double] =
    totalResolution(doubleDetails(key, default, ctx), key, default).map(_.value)

  def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty
  ): UIO[Map[String, Any]] =
    totalResolution(objDetails(key, default, ctx), key, default).map(_.value)

  def valueOrDefault[A: FlagType](key: String, default: A, ctx: EvaluationContext = EvaluationContext.empty): UIO[A] =
    totalResolution(valueDetails(key, default, ctx), key, default).map(_.value)

  /** Total details variant: like [[valueDetails]] but never fails — an evaluation error yields a `FlagResolution` whose
    * `reason` is `Error`, `value` is `default`, and `errorCode`/`errorMessage` describe the failure (spec §1.4.10).
    */
  def resolveOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[FlagResolution[A]] =
    totalResolution(valueDetails(key, default, ctx, options), key, default)

  // Typed flag definition overloads (#347)
  //
  // Each delegates to the key-based generic tier above, passing `flag.default` as the evaluation default and
  // `flag.flagType` as the decoding instance. There is no new evaluation machinery here, and no new failure mode:
  // the partial overloads keep the `FeatureFlagError` channel, the total ones keep the never-fails `UIO` contract.
  //
  // Spelled as default-free arity pairs rather than with default arguments, because the key-based `valueOrDefault` /
  // `resolveOrDefault` / `valueDetails` already carry defaults and Scala rejects two overloads of one method that
  // both have default arguments. This also matches how `boolean`/`string`/`int` are spelled.
  //
  // The local `implicit val` is a cross-build necessity rather than a style lapse: this file compiles for both 2.13
  // and 3, and passing a context-bound instance explicitly would need `(using flag.flagType)` on 3 but
  // `(flag.flagType)` on 2.13 — which cannot be written once. An `implicit val` in scope satisfies both.

  def value[A](flag: FlagDef[A]): IO[FeatureFlagError, A] = {
    implicit val ft: FlagType[A] = flag.flagType
    value(flag.key, flag.default)
  }

  def value[A](flag: FlagDef[A], ctx: EvaluationContext): IO[FeatureFlagError, A] = {
    implicit val ft: FlagType[A] = flag.flagType
    value(flag.key, flag.default, ctx)
  }

  def valueOrDefault[A](flag: FlagDef[A]): UIO[A] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueOrDefault(flag.key, flag.default)
  }

  def valueOrDefault[A](flag: FlagDef[A], ctx: EvaluationContext): UIO[A] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueOrDefault(flag.key, flag.default, ctx)
  }

  def resolveOrDefault[A](flag: FlagDef[A]): UIO[FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    resolveOrDefault(flag.key, flag.default)
  }

  def resolveOrDefault[A](flag: FlagDef[A], ctx: EvaluationContext): UIO[FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    resolveOrDefault(flag.key, flag.default, ctx)
  }

  def resolveOrDefault[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): UIO[FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    resolveOrDefault(flag.key, flag.default, ctx, options)
  }

  def valueDetails[A](flag: FlagDef[A]): IO[FeatureFlagError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default)
  }

  def valueDetails[A](flag: FlagDef[A], ctx: EvaluationContext): IO[FeatureFlagError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default, ctx)
  }

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default, ctx, options)
  }

  private def totalResolution[A](
    details: IO[FeatureFlagError, FlagResolution[A]],
    key: String,
    default: A
  ): UIO[FlagResolution[A]] =
    details.foldCauseZIO(
      cause =>
        // Propagate ONLY pure external cancellation, so structured cancellation still works. A collateral interrupt that
        // co-occurs with a typed failure or defect (e.g. from parallelism inside a `*Details` implementation) is not
        // cancellation — the real outcome is that failure, which we absorb into the default per the never-fails
        // contract. In this branch the cause has no `Fail` nodes, so `stripFailures` only re-types it to `Cause[Nothing]`
        // (keeping the effect in the `UIO` channel).
        if (cause.isInterruptedOnly) ZIO.refailCause(cause.stripFailures)
        else
          cause.failureOption match {
            case Some(e) =>
              ZIO.succeed(FlagResolution.error(key, default, FeatureFlagError.toErrorCode(e), e.message))
            case None =>
              // A defect is an unexpected bug, not an expected provider error. Absorb it per spec §1.1.7, but leave a
              // breadcrumb — the value-only `*OrDefault` variants discard the resolution, so without this a swallowed
              // bug would be entirely invisible.
              ZIO.logWarningCause(s"Total evaluation of '$key' absorbed a defect; serving the default", cause) *>
                ZIO.succeed(
                  FlagResolution.error(
                    key,
                    default,
                    ErrorCode.General,
                    cause.dieOption.fold("evaluation defect")(_.toString)
                  )
                )
          },
      ZIO.succeed(_)
    )

  def setGlobalContext(ctx: EvaluationContext): UIO[Unit]
  def globalContext: UIO[EvaluationContext]

  /** Set the client-level evaluation context.
    *
    * Per OpenFeature spec, context merges in order: API (global) -> Transaction -> Client -> Invocation. Client context
    * is persisted on this FeatureFlags instance.
    */
  def setClientContext(ctx: EvaluationContext): UIO[Unit]

  /** Get the client-level evaluation context. */
  def clientContext: UIO[EvaluationContext]

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]

  /** Run `zio` inside a flag transaction with optional overrides and per-call evaluation caching.
    *
    * Error channel: on Scala 3, `Compat.OrError[E, FeatureFlagError]` is the union `E | FeatureFlagError`. Scala 2.13
    * has no union types, so there it erases to `Any`, which disables typed recovery. For a typed error channel on both
    * versions, use [[transactionEither]], whose `Either[E, FeatureFlagError]` this method is built on.
    */
  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]]

  /** Like [[transaction]], but with a uniform, cross-version typed error channel: `Either[E, FeatureFlagError]` on both
    * Scala 2.13 and 3. `Left(e)` carries the caller's own error from `zio`; `Right(ffe)` carries a
    * transaction-machinery error (e.g. `NestedTransactionNotAllowed`). Prefer this on Scala 2.13, where
    * [[transaction]]'s error channel erases to `Any` (no union types) and disables typed recovery. On both versions
    * this is the source-tagged form `transaction` is built on, so the two never disagree about which side an error came
    * from.
    */
  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]]

  def inTransaction: UIO[Boolean]
  def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]

  def events: ZStream[Any, Nothing, ProviderEvent]
  def providerStatus: UIO[ProviderStatus]

  /** Semantically block until the provider reaches an evaluable state (`canEvaluate` — Ready/Stale), returns early on
    * `Fatal`, or `within` elapses — returning the status at that moment in every case. Backed by the status change
    * stream (no polling), and safe for many concurrent waiters. Ideal for `/ready` probes:
    * `ff.awaitReady(5.seconds).map(_.canEvaluate)`.
    */
  def awaitReady(within: Duration = Duration.Infinity): UIO[ProviderStatus]

  def providerMetadata: UIO[ProviderMetadata]
  def clientMetadata: UIO[ClientMetadata]

  // Event Handlers - return a cancellation effect
  //
  // Delivery semantics: the event subscription is established before the registration effect returns, so no event
  // published afterwards is lost. Handlers with an "associated state" (ready/error/stale) also run immediately when
  // the provider is already in that state (spec 5.3.3); an event arriving during registration may therefore invoke
  // the handler twice — delivery is at-least-once and handlers should be idempotent.

  /** Register a handler for provider ready events. Returns a cancellation effect.
    *
    * Per OpenFeature spec 5.2.1 and 5.2.7, handlers can be registered and removed. Delivery is at-least-once — see the
    * note on event handlers above.
    */
  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider error events. Returns a cancellation effect. */
  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider stale events. Returns a cancellation effect. */
  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for configuration changed events. Returns a cancellation effect. */
  def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for any provider event type. Returns a cancellation effect.
    *
    * This is a generic alternative to the specific event handler methods (onProviderReady, etc.).
    */
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]

  def addHook(hook: FeatureHook): UIO[Unit]
  def addHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearHooks: UIO[Unit]
  def hooks: UIO[List[FeatureHook]]

  /** Register a ZIO API-level hook (spec §4.4.1 level 1). Runs before client-level hooks on every evaluation. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
  def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearZioApiHooks: UIO[Unit]
  def zioApiHooks: UIO[List[FeatureHook]]

  /** Add an API-level hook that applies to all clients sharing this instance's OpenFeatureAPI. */
  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]

  /** Clear all API-level hooks on this instance's OpenFeatureAPI. */
  def clearApiHooks: UIO[Unit]

  /** Replace the underlying provider at runtime.
    *
    * The old provider is shut down, the new provider is initialized, and the status transitions through `NotReady`
    * during the swap. Hooks, context, and event handlers are preserved. Evaluations that start during the swap fail
    * with `ProviderNotReady`.
    */
  def setProvider(provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  // Shutdown API (spec 1.6.1)

  /** Shut down this instance (spec 1.6.1, 1.6.2).
    *
    * The status transitions to `ShuttingDown` for the duration of the teardown (evaluations started in that window fail
    * with `ProviderNotReady(ShuttingDown)`) and ends at `NotReady`. Client-level and ZIO API-level hooks, the global
    * and client contexts, and the tracked-events recorder are cleared, and the event hub is shut down.
    *
    * Provider/API teardown depends on ownership. An instance created by a sole-owner factory (e.g. `fromProvider` /
    * `fromProviderAsync`) shuts down the underlying OpenFeature API, and with it its provider. An instance that shares
    * its API with siblings — a client obtained from `FeatureFlagRegistry`, or any domain-scoped client — leaves the
    * shared API and its provider untouched (both belong to whatever owns the API, which tears every provider down once
    * when its own scope closes), releasing only this instance's own state; such clients should be retired via whatever
    * owns the API's lifecycle (e.g. the registry). Note that a domain-scoped client created directly on the
    * process-global API (e.g. `fromProviderWithDomain`) has no such owner: neither `shutdown` nor scope close reclaims
    * its provider, so for a resource-heavy provider prefer a sole-owner factory (or the registry), or shut the global
    * API down yourself. Fiber-local context and any in-flight transaction state are fiber-scoped and unaffected.
    */
  def shutdown: UIO[Unit]

  // Tracking API
  def track(eventName: String): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]
  def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): IO[FeatureFlagError, Unit]

  /** The most recent tracking events recorded by this instance, oldest first.
    *
    * The recorder is a bounded test/debug affordance: only the last 1000 events are retained; older events are dropped.
    * It is not a delivery guarantee mechanism — providers receive every `track` call regardless.
    */
  def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]]
}

object FeatureFlags {

  // Service Accessors - simple evaluation

  def boolean(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default))

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default, ctx))

  def string(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default))

  def string(key: String, default: String, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default, ctx))

  def int(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default))

  def int(key: String, default: Int, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default, ctx))

  def long(key: String, default: Long): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default))

  def long(key: String, default: Long, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default, ctx))

  def double(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default))

  def double(key: String, default: Double, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default, ctx))

  def obj(key: String, default: Map[String, Any]): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default))

  def obj(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default, ctx))

  def value[A: FlagType](key: String, default: A): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default))

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx))

  // Service Accessors - total (never-fails) evaluation (spec §1.4.10)

  def booleanOrDefault(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.booleanOrDefault(key, default, ctx))

  def stringOrDefault(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, String] =
    ZIO.serviceWithZIO(_.stringOrDefault(key, default, ctx))

  def intOrDefault(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, Int] =
    ZIO.serviceWithZIO(_.intOrDefault(key, default, ctx))

  def longOrDefault(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, Long] =
    ZIO.serviceWithZIO(_.longOrDefault(key, default, ctx))

  def doubleOrDefault(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, Double] =
    ZIO.serviceWithZIO(_.doubleOrDefault(key, default, ctx))

  def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, Map[String, Any]] =
    ZIO.serviceWithZIO(_.objOrDefault(key, default, ctx))

  def valueOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty
  ): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(key, default, ctx))

  def resolveOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.resolveOrDefault(key, default, ctx, options))

  // Service Accessors - detailed evaluation (with default parameters)

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default, ctx, options))

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default, ctx, options))

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default, ctx, options))

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default, ctx, options))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx, options))

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default, ctx, options))

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default, ctx, options))

  // Service Accessors - typed flag definitions (#347)
  //
  // No `implicit val` is needed here (unlike the trait methods): the `FlagDef` carries its own `FlagType`, and the
  // trait overload it delegates to takes no context bound.

  def value[A](flag: FlagDef[A]): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(flag))

  def value[A](flag: FlagDef[A], ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(flag, ctx))

  def valueOrDefault[A](flag: FlagDef[A]): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(flag))

  def valueOrDefault[A](flag: FlagDef[A], ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(flag, ctx))

  def resolveOrDefault[A](flag: FlagDef[A]): ZIO[FeatureFlags, Nothing, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.resolveOrDefault(flag))

  def resolveOrDefault[A](
    flag: FlagDef[A],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, Nothing, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.resolveOrDefault(flag, ctx))

  def resolveOrDefault[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.resolveOrDefault(flag, ctx, options))

  def valueDetails[A](flag: FlagDef[A]): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(flag))

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(flag, ctx))

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(flag, ctx, options))

  def setGlobalContext(ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setGlobalContext(ctx))

  def globalContext: ZIO[FeatureFlags, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO(_.globalContext)

  def setClientContext(ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setClientContext(ctx))

  def clientContext: ZIO[FeatureFlags, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO(_.clientContext)

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, E, A] =
    ZIO.serviceWithZIO[FeatureFlags](_.withContext(ctx)(zio))

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transaction(overrides, context, cacheEvaluations)(zio))

  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Either[E, FeatureFlagError], TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transactionEither(overrides, context, cacheEvaluations)(zio))

  def inTransaction: ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.inTransaction)

  def currentEvaluatedFlags: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[_]]] =
    ZIO.serviceWithZIO(_.currentEvaluatedFlags)

  def events: ZStream[FeatureFlags, Nothing, ProviderEvent] =
    ZStream.serviceWithStream(_.events)

  def providerStatus: ZIO[FeatureFlags, Nothing, ProviderStatus] =
    ZIO.serviceWithZIO(_.providerStatus)

  def awaitReady(within: Duration = Duration.Infinity): ZIO[FeatureFlags, Nothing, ProviderStatus] =
    ZIO.serviceWithZIO(_.awaitReady(within))

  def providerMetadata: ZIO[FeatureFlags, Nothing, ProviderMetadata] =
    ZIO.serviceWithZIO(_.providerMetadata)

  def clientMetadata: ZIO[FeatureFlags, Nothing, ClientMetadata] =
    ZIO.serviceWithZIO(_.clientMetadata)

  // Event Handlers - return cancellation effects

  /** Register a handler for provider ready events. Returns a cancellation effect. */
  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderReady(handler))

  /** Register a handler for provider error events. Returns a cancellation effect. */
  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderError(handler))

  /** Register a handler for provider stale events. Returns a cancellation effect. */
  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderStale(handler))

  /** Register a handler for configuration changed events. Returns a cancellation effect. */
  def onConfigurationChanged(
    handler: (Set[String], ProviderMetadata) => UIO[Unit]
  ): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onConfigurationChanged(handler))

  /** Register a handler for any provider event type. Returns a cancellation effect. */
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.on(eventType, handler))

  def shutdown: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.shutdown)

  def addHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHook(hook))

  def addHooks(hooks: List[FeatureHook]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHooks(hooks))

  def clearHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearHooks)

  def hooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.hooks)

  def addZioApiHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHook(hook))

  def addZioApiHooks(hooks: List[FeatureHook]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHooks(hooks))

  def clearZioApiHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearZioApiHooks)

  def zioApiHooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.zioApiHooks)

  // API-level Hooks (per OpenFeature spec 4.4.1)

  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addApiHook(hook))

  def clearApiHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearApiHooks)

  // Tracking API

  def track(eventName: String): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName))

  def track(eventName: String, context: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context))

  def track(eventName: String, details: TrackingEventDetails): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, details))

  def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context, details))

  def trackedEvents: ZIO[FeatureFlags, Nothing, List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    ZIO.serviceWithZIO(_.trackedEvents)

  def setProvider(provider: OFFeatureProvider): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(provider))

  // Factory Methods

  /** Default cap on how long initialization may take before the layer build fails or — for async layers — transitions
    * to `Fatal`. Mirrors the documented default referenced in every factory's ScalaDoc.
    */
  private[openfeature] val DefaultInitTimeout: Duration = 30.seconds

  /** Fallback provider name stamped when a provider exposes no metadata/name at registration time. The event-bridge
    * identity guard (`FeatureFlagsLive.fromCurrentProvider`) treats this as an indeterminate identity and fails open,
    * so a provider whose real name only appears after `initialize()` (e.g. the SDK's MultiProvider) still drives its
    * own status.
    */
  private[openfeature] val UnknownProviderName: String = "unknown"

  /** Default per-evaluation timeout: **1 second, applied to every evaluation unless overridden**. Any provider call
    * that takes longer is interrupted and the effect fails with `FeatureFlagError.ProviderError` wrapping a
    * `TimeoutException` — so a remote provider with cold-start latency can fail its first evaluations out of the box.
    * To change it: pass `evaluationTimeout = Some(otherDuration)` (or `None` to disable globally) to a factory method;
    * per call, `EvaluationOptions.empty.withTimeout(d)` bounds a single evaluation and `.withoutTimeout` disables it
    * (the latter also skips the timeout scaffolding, which matters for microsecond-latency in-memory providers).
    */
  val DefaultEvaluationTimeout: Duration = 1.second

  /** Verify the provider reached a usable state after sync initialization. Anything outside `READY` / `STALE` causes
    * the layer build to fail so misconfiguration surfaces at startup, not at first evaluation. Returns the ZIO-level
    * `ProviderStatus` that should be reflected for a usable state.
    */
  @scala.annotation.nowarn("msg=deprecated")
  private def verifyInitState(provider: OFFeatureProvider): ZIO[Any, Throwable, ProviderStatus] =
    ZIO.attempt(provider.getState).flatMap {
      case ProviderState.READY => ZIO.succeed(ProviderStatus.Ready)
      case ProviderState.STALE => ZIO.succeed(ProviderStatus.Stale)
      case bad @ (ProviderState.ERROR | ProviderState.FATAL | ProviderState.NOT_READY) =>
        ZIO.fail(new IllegalStateException(s"Provider in $bad state after initialization"))
    }

  /** Shared initialization logic for all factory methods. */
  private[openfeature] def build(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE initiating provider registration. If init times out,
      // the provider reports a bad state, or getClient fails, the scope close still tears down whatever
      // the (possibly still-running, disconnected) setProviderAndWait managed to register.
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore))
      setAndWait = domain match {
        case Some(d) => ZIO.attemptBlocking(api.setProviderAndWait(d, provider))
        case None    => ZIO.attemptBlocking(api.setProviderAndWait(provider))
      }
      // Bound the blocking init. `.disconnect` ensures the timeout returns promptly even though
      // `attemptBlocking` runs on the blocking pool; the underlying call may still run to completion
      // in the background. On any init failure the provider itself is shut down (best-effort) so it
      // doesn't keep threads/connections alive — this also covers the domain/registry paths where no
      // API finalizer is registered.
      verified <- (setAndWait.disconnect
        .timeoutFail(new TimeoutException(s"Provider initialization exceeded $initTimeout"))(initTimeout) *>
        verifyInitState(provider))
        .tapError(_ => ZIO.attemptBlocking(provider.shutdown()).ignore)
      client <- (domain, version) match {
        case (Some(d), Some(v)) => ZIO.attempt(api.getClient(d, v))
        case (Some(d), None)    => ZIO.attempt(api.getClient(d))
        case _                  => ZIO.attempt(api.getClient())
      }
      providerName = Option(provider.getMetadata).map(_.getName).getOrElse(UnknownProviderName)
      providerRef <- Ref.make(provider)
      providerNameRef = new java.util.concurrent.atomic.AtomicReference(providerName)
      swapLock  <- Semaphore.make(1)
      baseState <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _ <- state.hooksRef.set(initialHooks)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        // Owns the api iff we also register the api-shutdown scope finalizer — keeps explicit `shutdown` and
        // scope-close teardown consistent, so a shared-api (registry/domain) client never shuts the whole api.
        ownsApi = addShutdownFinalizer,
        swapLock,
        onReady,
        evaluationTimeout
      )
      // Only seed status when the caller didn't hand us a shared ref (testkit shares one). Routed through
      // `seedStatus` so the machine's `everReady` flag records a Ready/Stale seed.
      _ <- statusRef.fold(ff.seedStatus(verified))(_ => ZIO.unit)
      _ <- ff.startEventBridge
    } yield ff

  /** The config-driven factory: every retired overload forwards here. See [[FeatureFlagsConfig]] for field semantics
    * (`domain`, `version`, `initialHooks`, `evaluationTimeout`, `initTimeout`, `initMode`, `apiOwnership`).
    *
    * `FeatureFlagsConfig()` reproduces `fromProvider(provider)` exactly (sync init, no domain, `Owned` API — the `Auto`
    * truth table's `domain.isEmpty` branch).
    */
  def fromProvider(provider: OFFeatureProvider, config: FeatureFlagsConfig): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, config, statusRef = None)

  /** `private[openfeature]` variant of [[fromProvider(OFFeatureProvider, FeatureFlagsConfig)]] that additionally
    * accepts a shared `statusRef`, an `apiOverride` (a private `OpenFeatureAPI` instead of the process-global
    * singleton), and an `onReady` latch — the same knobs the legacy `private[openfeature]` statusRef overloads exposed,
    * now available alongside the full `FeatureFlagsConfig` surface (e.g. `apiOwnership` overrides). Used by the testkit
    * and by tests that need to observe `ApiOwnership` semantics against an isolated API.
    */
  private[openfeature] def fromProvider(
    provider: OFFeatureProvider,
    config: FeatureFlagsConfig,
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      val evalTimeout = config.evaluationTimeout match {
        case EvaluationTimeout.Default  => Some(DefaultEvaluationTimeout)
        case EvaluationTimeout.Disabled => None
        case EvaluationTimeout.After(d) => Some(d)
      }
      val ownsApi = config.apiOwnership match {
        case ApiOwnership.Auto   => config.domain.isEmpty
        case ApiOwnership.Owned  => true
        case ApiOwnership.Shared => false
      }
      config.initMode match {
        case InitMode.Sync =>
          build(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = apiOverride,
            evaluationTimeout = evalTimeout,
            initTimeout = config.initTimeout,
            onReady = onReady
          )
        case InitMode.Async =>
          buildAsync(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = apiOverride,
            onReady = onReady,
            evaluationTimeout = evalTimeout,
            initTimeout = config.initTimeout
          )
      }
    }

  /** Combine multiple providers into one via the SDK's `MultiProvider`, defaulting to a first-match strategy. Pairs
    * with [[fromProvider(OFFeatureProvider, FeatureFlagsConfig)]] to express combinations `fromMultiProvider` could
    * not, e.g. multi-provider + domain: `fromProvider(multiProvider(ps), FeatureFlagsConfig(domain =
    * Some("checkout")))`.
    *
    * '''Each provider in the chain needs a distinct metadata name.''' The SDK's `MultiProvider` keys its providers by
    * `getMetadata.getName`, so two instances of the same provider type — two `HoconProvider`s over different configs,
    * say — collapse into one, and the survivor is the '''last''' of them regardless of the strategy. The SDK logs
    * `duplicated provider name` at INFO and otherwise carries on, so this is easy to miss. Wrap one of them in a
    * provider that reports a different name if you need both.
    *
    * For the chain to advance past a provider, that provider must report `FLAG_NOT_FOUND` for a key it does not hold;
    * see [[MultiProviderStrategy.firstMatch]] for what does and does not cause fall-through.
    */
  def multiProvider(
    providers: List[OFFeatureProvider],
    strategy: Strategy = new FirstMatchStrategy()
  ): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new MultiProvider(providers.asJava, strategy)
  }

  /** Create a FeatureFlags layer from any OpenFeature provider.
    *
    * Initialization is bounded by the default 30s init timeout: if `setProviderAndWait` takes longer, or the provider
    * reports `ERROR`/`FATAL`/`NOT_READY` afterwards, the layer build fails with a `TimeoutException` or
    * `IllegalStateException` wrapped at the layer boundary. Use `fromProvider(provider, FeatureFlagsConfig())` with
    * `.withInitTimeout(...)` to override.
    */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig())

  /** Create a FeatureFlags layer with a global evaluation timeout.
    *
    * If a provider evaluation takes longer than `evaluationTimeout`, it fails with `ProviderError` containing a
    * `TimeoutException`. This prevents hung providers from blocking fibers indefinitely. Per-call timeouts set via
    * `EvaluationOptions.timeout` override this global default. Initialization uses the default 30s init timeout.
    */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withEvaluationTimeout(evalTimeout))", "0.2.0")
  def fromProvider(provider: OFFeatureProvider, evaluationTimeout: Duration): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withEvaluationTimeout(evaluationTimeout))

  /** Create a FeatureFlags layer with explicit evaluation and initialization timeouts.
    *
    * `initTimeout` bounds the sync init: if `setProviderAndWait` takes longer, the layer build fails with a
    * `TimeoutException`. After init, if the provider reports `ERROR`/`FATAL`/`NOT_READY`, the build also fails so
    * misconfiguration surfaces at startup rather than at first evaluation. Pass a very large duration to effectively
    * disable the init timeout.
    */
  @deprecated(
    "Use fromProvider(p, FeatureFlagsConfig().withEvaluationTimeout(evalT).withInitTimeout(initT))",
    "0.2.0"
  )
  def fromProvider(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration,
    initTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withEvaluationTimeout(evaluationTimeout).withInitTimeout(initTimeout))

  /** Create a FeatureFlags layer with a named domain/client. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withDomain(d))", "0.2.0")
  def fromProviderWithDomain(provider: OFFeatureProvider, domain: String): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withDomain(domain))

  /** Create a FeatureFlags layer with a named domain/client and version. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withDomain(d).withVersion(v))", "0.2.0")
  def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    version: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withDomain(domain).withVersion(version))

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  private[openfeature] def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady
      )
    )

  /** Create a FeatureFlags layer from multiple providers using the first-match strategy. */
  @deprecated("Use fromProvider(FeatureFlags.multiProvider(ps), FeatureFlagsConfig())", "0.2.0")
  def fromMultiProvider(providers: List[OFFeatureProvider]): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers), FeatureFlagsConfig())

  /** Create a FeatureFlags layer from multiple providers with a custom strategy. */
  @deprecated("Use fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig())", "0.2.0")
  def fromMultiProvider(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers, strategy), FeatureFlagsConfig())

  /** Create a FeatureFlags layer with initial hooks. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withHooks(hooks))", "0.2.0")
  def fromProviderWithHooks(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withHooks(initialHooks))

  // Async Factory Methods (non-blocking provider initialization)

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. To avoid a race
    * where the provider becomes ready before the event bridge is registered, we start the event bridge first and then
    * check the provider's actual state.
    */
  private[openfeature] def buildAsync(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE the provider, so a failure later in the build
      // (e.g. getClient) still tears down the registered provider when the scope closes.
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore))
      // Register provider FIRST so the client binds to it (not the NoOp default). Use `ZIO.attempt` (not
      // `ZIO.succeed`) so a synchronous throw from registration — a null provider, or a provider whose
      // `getMetadata` throws while the SDK reads its name — surfaces in the typed Throwable channel instead of
      // becoming a defect. As a defect it would escape `buildClient`'s `mapError` and the registry's build
      // fiber would die with its promise never settled, hanging every `getClient` for that domain forever.
      // `ZIO.attempt` (not `attemptBlocking`): `setProvider` is the non-blocking registration call — it submits
      // `initialize()` to the SDK's background executor and returns after cheap bookkeeping, so there is no
      // blocking I/O to move off the compute pool. (The sync `build` path uses `attemptBlocking` because
      // `setProviderAndWait` genuinely blocks the thread for the whole initialization.)
      _ <- domain match {
        case Some(d) => ZIO.attempt(api.setProvider(d, provider))
        case None    => ZIO.attempt(api.setProvider(provider))
      }
      client <- (domain, version) match {
        case (Some(d), Some(v)) => ZIO.attempt(api.getClient(d, v))
        case (Some(d), None)    => ZIO.attempt(api.getClient(d))
        case _                  => ZIO.attempt(api.getClient())
      }
      // `ZIO.attempt` for the same reason as the registration above: a throwing `getMetadata` here must be a
      // typed failure, not a defect that strands the registry build fiber (see the `setProvider` note).
      providerName <- ZIO.attempt(Option(provider.getMetadata).map(_.getName).getOrElse(UnknownProviderName))
      providerRef  <- Ref.make(provider)
      providerNameRef = new java.util.concurrent.atomic.AtomicReference(providerName)
      swapLock  <- Semaphore.make(1)
      baseState <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _ <- state.hooksRef.set(initialHooks)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        // Owns the api iff we also register the api-shutdown scope finalizer — keeps explicit `shutdown` and
        // scope-close teardown consistent, so a shared-api (registry/domain) client never shuts the whole api.
        ownsApi = addShutdownFinalizer,
        swapLock,
        onReady,
        evaluationTimeout
      )
      // Start event bridge — if provider is already ready, replay fires immediately
      _ <- ff.startEventBridge
      // Init watchdog (#244): after initTimeout, escalate to Fatal ONLY if the provider never became usable
      // (`everReady` gate inside the machine), shutting it down and releasing the onReady latch. A provider that was
      // Ready and later dipped to a transient Error is left running. The fiber is forked into the layer's Scope, so
      // it is also interrupted when the layer is released.
      _ <- (ZIO.sleep(initTimeout) *> ff.escalateInitTimeout(provider, initTimeout)).forkScoped
    } yield ff

  /** Create a FeatureFlags layer from any OpenFeature provider (non-blocking).
    *
    * The provider initializes in the background. Evaluations fail with `ProviderNotReady` until the provider is ready.
    * Use `onProviderReady` or `providerStatus` to detect when the provider becomes available. If the provider has not
    * become ready within the default 30s init timeout, status atomically transitions to `Fatal` so callers polling
    * `providerStatus` stop waiting. Use `fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async))` with
    * `.withInitTimeout(...)` to override.
    */
  def fromProviderAsync(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async))

  /** Create a FeatureFlags layer with a global evaluation timeout (non-blocking).
    *
    * Combines async initialization with evaluation timeout protection. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until ready. Once ready, evaluations that exceed `evaluationTimeout` fail
    * with `ProviderError` containing a `TimeoutException`. The init-side default 30s watchdog still applies.
    */
  @deprecated(
    "Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(evalT))",
    "0.2.0"
  )
  def fromProviderAsync(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(evaluationTimeout))

  /** Create a FeatureFlags layer with explicit evaluation and initialization timeouts (non-blocking).
    *
    * `initTimeout` bounds the async ready window: after that duration, if status is still `NotReady` or `Error`, it
    * atomically transitions to `Fatal`. Pass a very large duration to effectively disable the watchdog.
    */
  @deprecated(
    "Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(evalT).withInitTimeout(initT))",
    "0.2.0"
  )
  def fromProviderAsync(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration,
    initTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(
      provider,
      FeatureFlagsConfig(initMode = InitMode.Async)
        .withEvaluationTimeout(evaluationTimeout)
        .withInitTimeout(initTimeout)
    )

  /** Create a FeatureFlags layer with a named domain (non-blocking). */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(d))", "0.2.0")
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain))

  /** Create a FeatureFlags layer with a named domain and version (non-blocking). */
  @deprecated(
    "Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(d).withVersion(v))",
    "0.2.0"
  )
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    version: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain).withVersion(version))

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  private[openfeature] def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady
      )
    )

  /** Create a FeatureFlags layer with initial hooks (non-blocking). */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(hooks))", "0.2.0")
  def fromProviderWithHooksAsync(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(initialHooks))

  /** Create a FeatureFlags layer from multiple providers (non-blocking, first-match strategy). */
  @deprecated(
    "Use fromProvider(FeatureFlags.multiProvider(ps), FeatureFlagsConfig(initMode = InitMode.Async))",
    "0.2.0"
  )
  def fromMultiProviderAsync(providers: List[OFFeatureProvider]): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers), FeatureFlagsConfig(initMode = InitMode.Async))

  /** Create a FeatureFlags layer from multiple providers with a custom strategy (non-blocking). */
  @deprecated(
    "Use fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig(initMode = InitMode.Async))",
    "0.2.0"
  )
  def fromMultiProviderAsync(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers, strategy), FeatureFlagsConfig(initMode = InitMode.Async))

  /** Default `compose` for [[fromAcquireAsync]]: layer the real provider over the fallback with a first-successful
    * strategy, so a sick real provider transparently falls through to the fallback. `MultiProvider` keys children by
    * their metadata name and silently drops duplicates — the real and fallback providers must therefore have distinct
    * names (rename one if they collide).
    */
  def defaultAcquireCompose(real: OFFeatureProvider, fallback: OFFeatureProvider): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new MultiProvider(List(real, fallback).asJava, new FirstSuccessfulStrategy())
  }

  /** Fallback-first async factory: build the layer immediately on a fresh `fallback`, construct the real provider in a
    * background scoped fiber, and hot-swap it in when ready.
    *
    * Unlike every other factory, this takes the real provider **as an effect** (`acquire`) rather than a strict,
    * already-constructed instance — so a provider whose Java constructor does network I/O never runs on the
    * application's boot path. The layer's error channel is `Nothing` (`URLayer`): a failing real provider is handled
    * internally (retried, then `onConstructionError`, then left on the fallback), so the compiler proves no
    * provider-related failure can propagate into the application's layer graph.
    *
    *   - Status is `Ready` from time zero; evaluations answer fallback values (never `ProviderNotReady`) until the
    *     swap.
    *   - `acquire` runs with `.retry(constructionRetry)`, each attempt bounded by `constructionTimeout`. On terminal
    *     failure, `onConstructionError` runs and the instance stays on the fallback.
    *   - The composed stack uses a **fresh** fallback instance (the SDK shuts down the replaced provider on swap, so
    *     the first fallback must not be reused). Hooks, contexts, and event handlers survive the swap.
    *   - The construction fiber is scope-owned: layer release interrupts an in-flight `acquire`. A real provider
    *     acquired but not yet swapped is torn down by scope close **when `acquire` registers it in its `Scope`** (e.g.
    *     via `ZIO.acquireRelease`); a bare `attemptBlocking(new Provider(...))` registers no finalizer, so teardown of
    *     the not-yet-swapped provider is then the caller's responsibility.
    *
    * @param acquire
    *   the real provider as a scoped effect (typically `ZIO.attemptBlocking(new Provider(...))`)
    * @param fallback
    *   a scoped effect producing a FRESH fallback instance each time it is run
    * @param compose
    *   how to combine (real, freshFallback) into the swapped-in provider
    * @param constructionRetry
    *   retry policy for `acquire`
    * @param constructionTimeout
    *   per-attempt bound on `acquire`
    * @param onConstructionError
    *   run when `acquire` (or the swap) ultimately fails; the instance stays on the fallback
    */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider = defaultAcquireCompose,
    constructionRetry: Schedule[Any, Throwable, Any] = Schedule.recurs(3) && Schedule.exponential(1.second).jittered,
    constructionTimeout: Duration = DefaultInitTimeout,
    onConstructionError: Throwable => UIO[Unit] = _ => ZIO.unit,
    evaluationTimeout: Duration = DefaultEvaluationTimeout,
    initTimeout: Duration = DefaultInitTimeout
  ): URLayer[Scope, FeatureFlags] =
    ZLayer.scoped {
      for {
        // Build immediately on a fresh fallback. An in-memory fallback whose init fails is a programming bug, not a
        // recoverable condition, so a failed build is a defect (`orDie`) — this is what makes the layer a `URLayer`.
        firstFallback <- fallback
        ff <- build(
          firstFallback,
          domain = None,
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          evaluationTimeout = Some(evaluationTimeout),
          initTimeout = initTimeout
        ).orDie
        // Background construction + swap. `acquire`'s Scope is the layer scope, so the acquired real provider is torn
        // down on layer release even if it was never swapped in. The fiber is `forkScoped`, so an in-flight acquire is
        // interrupted on release. A terminal construction/swap failure is caught, reported, and left on the fallback —
        // nothing escapes to the (already-built) layer.
        construct = for {
          real <- acquire.disconnect
            // `.disconnect` so the per-attempt timeout returns promptly even when `acquire` is an uninterruptible
            // `attemptBlocking(new Provider(...))` — the underlying constructor may keep running in the background, but
            // the retry advances instead of waiting on a hung attempt.
            .timeoutFail(new TimeoutException(s"Provider construction exceeded $constructionTimeout"))(
              constructionTimeout
            )
            .retry(constructionRetry)
          freshFallback <- fallback
          _ <- ff
            .setProvider(compose(real, freshFallback))
            // `setProvider`'s rollback now restores both routing AND status (#282): a failed swap re-registers the
            // still-live fallback with the SDK and sets Ready, so no local force-ready workaround is needed here.
            .mapError(e => new RuntimeException(s"Provider swap failed: $e"))
        } yield ()
        _ <- construct.catchAll(onConstructionError).forkScoped
      } yield ff
    }
}
