package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.{FallbackLogLimiter, FeatureFlagsFactories, LongAwareMultiProvider}
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}
import dev.openfeature.sdk.multiprovider.{Strategy, FirstMatchStrategy, FirstSuccessfulStrategy}

/** The ZIO face of an OpenFeature client. Evaluation comes in two tiers, and which one you call is a statement about
  * what a missing or broken flag should do to the caller:
  *
  *   - '''Typed tier''' — `boolean`/`string`/`int`/`long`/`double`/`obj`/`value`, every `*Details`, and the `FlagDef`
  *     overloads. Return `IO[FeatureFlagError, _]` and '''fail''' whenever the evaluation cannot produce a real value:
  *     a provider that throws, a decode that rejects the payload, and — since #388 — a provider that answers with an
  *     '''error code''' on the resolution (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`, `PARSE_ERROR`, …), which is how most
  *     providers report problems. The code is mapped to the matching [[FeatureFlagError]] (`FlagNotFound(key)`,
  *     `TypeMismatch(key, expected, actual)`, …). Reach for this tier when a default would be ''wrong'' — a fail-closed
  *     gate written with `value` actually fails closed.
  *   - '''Total tier''' — `*OrDefault` and `resolveOrDefault`. Return `UIO[_]` and '''never fail''': the caller's
  *     default is served and, on `resolveOrDefault`, the returned [[FlagResolution]] says why (`reason = Error`,
  *     `errorCode`, `errorMessage`). This is the OpenFeature spec's §1.4.10 "always return the default" contract, and
  *     it is the migration path for code that used to read `errorCode` off a `*Details` result.
  *
  * Hooks observe an error-coded resolution as the `error` stage on both tiers; the two tiers differ only in what the
  * ''caller'' sees. Everything else — context merging, transactions, caching, timeouts — is shared.
  */
trait FeatureFlags {

  // Typed tier: abstract detailed evaluation methods (one per type, with defaults for ctx and options). Each fails with
  // a typed `FeatureFlagError` when the evaluation cannot produce a real value — see the trait scaladoc for the contract.

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

  /** [[value]] with per-call [[EvaluationOptions]] (invocation hooks, hints, a timeout). */
  def value[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, A] =
    valueDetails(key, default, ctx, options).map(_.value)

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

  // The same, with per-call EvaluationOptions (#444). Spelled without defaults: the overloads above already carry the
  // `ctx` default, and Scala allows default arguments on only one overload of a method.

  def booleanOrDefault(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): UIO[Boolean] =
    totalResolution(booleanDetails(key, default, ctx, options), key, default).map(_.value)

  def stringOrDefault(key: String, default: String, ctx: EvaluationContext, options: EvaluationOptions): UIO[String] =
    totalResolution(stringDetails(key, default, ctx, options), key, default).map(_.value)

  def intOrDefault(key: String, default: Int, ctx: EvaluationContext, options: EvaluationOptions): UIO[Int] =
    totalResolution(intDetails(key, default, ctx, options), key, default).map(_.value)

  def longOrDefault(key: String, default: Long, ctx: EvaluationContext, options: EvaluationOptions): UIO[Long] =
    totalResolution(longDetails(key, default, ctx, options), key, default).map(_.value)

  def doubleOrDefault(key: String, default: Double, ctx: EvaluationContext, options: EvaluationOptions): UIO[Double] =
    totalResolution(doubleDetails(key, default, ctx, options), key, default).map(_.value)

  def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): UIO[Map[String, Any]] =
    totalResolution(objDetails(key, default, ctx, options), key, default).map(_.value)

  def valueOrDefault[A: FlagType](key: String, default: A, ctx: EvaluationContext, options: EvaluationOptions): UIO[A] =
    totalResolution(valueDetails(key, default, ctx, options), key, default).map(_.value)

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

  def value[A](flag: FlagDef[A], ctx: EvaluationContext, options: EvaluationOptions): IO[FeatureFlagError, A] = {
    implicit val ft: FlagType[A] = flag.flagType
    value(flag.key, flag.default, ctx, options)
  }

  def valueOrDefault[A](flag: FlagDef[A], ctx: EvaluationContext, options: EvaluationOptions): UIO[A] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueOrDefault(flag.key, flag.default, ctx, options)
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
              val served = FlagResolution.error(key, default, FeatureFlagError.toErrorCode(e), e.message)
              logFallback(key, served, defect = None).as(served)
            case None =>
              // A defect is an unexpected bug, not an expected provider error. Absorb it per spec §1.1.7, but leave a
              // breadcrumb — the value-only `*OrDefault` variants discard the resolution, so without this a swallowed
              // bug would be entirely invisible.
              val served =
                FlagResolution.error(
                  key,
                  default,
                  ErrorCode.General,
                  cause.dieOption.fold("evaluation defect")(_.toString)
                )
              logFallback(key, served, defect = Some(cause.stripFailures)).as(served)
          },
      // Since #388 a provider-reported error CODE fails the typed tier, so it arrives on the `Fail` branch above; this
      // branch is reached only by a resolution with `reason = Error` and no code — still a served-default fallback, so
      // it still gets the breadcrumb.
      resolved =>
        if (resolved.isError) logFallback(key, resolved, defect = None).as(resolved) else ZIO.succeed(resolved)
    )

  /** The total tier's breadcrumb for a served-default fallback: called by `totalResolution` with the resolution about
    * to be served and, for an absorbed defect, its cause. Logging only — it never affects the resolution. The default
    * is what every implementor got before this hook existed: absorbed defects logged at warn on every occurrence,
    * error-coded fallbacks silent. `FeatureFlagsLive` overrides it with the instance's [[FallbackLogging]] policy
    * (per-key rate limiting, error-coded fallbacks included).
    */
  protected def logFallback[A](key: String, resolution: FlagResolution[A], defect: Option[Cause[Nothing]]): UIO[Unit] =
    defect.fold[UIO[Unit]](ZIO.unit)(cause => ZIO.logWarningCause(FallbackLogLimiter.absorbedDefectMessage(key), cause))

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
    *
    * `nested` decides what happens when this call is already inside a transaction — see [[NestedPolicy]]. The default,
    * `Fail`, raises `NestedTransactionNotAllowed`; `Reuse` runs `zio` inside the enclosing transaction and '''ignores
    * `overrides`, `context` and `cacheEvaluations`''' (the enclosing transaction is the one running).
    */
  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]]

  /** Like [[transaction]], but with a uniform, cross-version typed error channel: `Either[E, FeatureFlagError]` on both
    * Scala 2.13 and 3. `Left(e)` carries the caller's own error from `zio`; `Right(ffe)` carries a
    * transaction-machinery error (e.g. `NestedTransactionNotAllowed`). Prefer this on Scala 2.13, where
    * [[transaction]]'s error channel erases to `Any` (no union types) and disables typed recovery. On both versions
    * this is the source-tagged form `transaction` is built on, so the two never disagree about which side an error came
    * from.
    *
    * `nested` decides what happens when this call is already inside a transaction — see [[NestedPolicy]]. Under `Reuse`
    * the body runs inside the enclosing transaction, `overrides`/`context`/`cacheEvaluations` are '''ignored''', and
    * the returned `TransactionResult` reflects the enclosing transaction as of the body's completion.
    */
  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]]

  def inTransaction: UIO[Boolean]

  /** The flags the current transaction has evaluated so far — `Map.empty` both when there is no transaction and when
    * there is one that has evaluated nothing yet. That ambiguity is deliberate for a convenience read but is the wrong
    * shape for an audit record ("which flags shaped this request"), where an empty answer must not be mistaken for a
    * real one: prefer [[transactionEvaluations]], which answers `None` outside a transaction (#387).
    */
  def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]

  /** The current transaction's evaluations, or `None` when this fiber is not inside a transaction at all (#387).
    *
    * `Some(Map.empty)` is a real answer — a transaction that has evaluated nothing yet — and `None` is a different one:
    * there is no transaction, so the question does not apply. [[currentEvaluatedFlags]] returns `Map.empty` for both,
    * which is exactly what lets audit code that has drifted outside its transaction boundary write empty flag sets into
    * production records silently. Use this where that distinction matters; it fails loudly at the type.
    *
    * A concrete default rather than an abstract member so an existing implementor of this trait keeps compiling: it is
    * derived from [[inTransaction]] and [[currentEvaluatedFlags]], in that order. The order is what makes it sound:
    * whether this fiber is in a transaction is fiber-local and cannot flip between the two reads, so the presence check
    * decides `None`/`Some` and the map read only fills the payload. That payload is a snapshot — a fiber forked inside
    * the same transaction may record more evaluations at any moment. The live implementation overrides it with a single
    * read.
    */
  def transactionEvaluations: UIO[Option[Map[String, FlagEvaluation[_]]]] =
    inTransaction.flatMap {
      case true  => currentEvaluatedFlags.map(Some(_))
      case false => ZIO.none
    }

  def events: ZStream[Any, Nothing, ProviderEvent]
  def providerStatus: UIO[ProviderStatus]

  /** Semantically block until the provider reaches an evaluable state (`canEvaluate` — Ready/Stale), returns early on
    * `Fatal`, or `within` elapses — returning the status at that moment in every case. Backed by the status change
    * stream (no polling), and safe for many concurrent waiters. Ideal for `/ready` probes:
    * `ff.awaitReady(5.seconds).map(_.canEvaluate)`. On an instance that is shutting down or shut down it returns at
    * once with that status (`ShuttingDown` / `NotReady`), since it will never become evaluable again.
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
    * The cancellation effect must be run, or the handler fiber lives until the instance shuts down. To tie a handler to
    * a scope, wrap the registration: `ZIO.acquireRelease(ff.onProviderReady(h))(cancel => cancel)`. The same holds for
    * every `on*` method below.
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

  /** Add an API-level hook that applies to all clients sharing this instance's OpenFeatureAPI.
    *
    * The hook lives on the API, not on this instance: every sibling instance on the same API (registry domains
    * included) runs it too. There is no per-instance removal; [[clearApiHooks]] is all-or-nothing.
    */
  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]

  /** Clear all API-level hooks on this instance's OpenFeatureAPI.
    *
    * The API is shared by every sibling instance, so this removes their API-level hooks as well as this instance's.
    * Called from a `Shared` instance (one that does not own the API) it reaches siblings the caller may not control,
    * and logs one warning saying so; an `Owned` instance logs nothing.
    */
  def clearApiHooks: UIO[Unit]

  /** Replace the underlying provider at runtime.
    *
    * The new provider is initialized — bounded by the layer's init timeout — and, once it is ready, the old provider is
    * shut down; the status transitions through `NotReady` during the swap. Hooks, context, and event handlers are
    * preserved. Evaluations that start during the swap fail with `ProviderNotReady`.
    *
    * If the new provider's `initialize()` fails, the effect fails with `ProviderInitializationFailed` and the previous
    * provider is registered again. If it does not finish within the init timeout, the effect fails with
    * `ProviderInitializationFailed` wrapping a `TimeoutException` and status becomes `Error`, but the swap is not
    * rolled back: the new provider stays registered (status recovers to `Ready` if it finishes initializing later), and
    * the previous provider is not restored, because the SDK would shut it down if the new one later became ready. Call
    * `setProvider` again with another provider to recover.
    *
    * Once the swap has started it runs to one of those outcomes even if the calling fiber is interrupted, so
    * `setProvider(p).timeout(d)` with `d` shorter than the init timeout returns only when the swap has settled (at most
    * about twice the init timeout). Waiting for an earlier swap to finish can still be interrupted.
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

  /** The most recent tracking events recorded by this instance, oldest first — only for instances built by the testkit.
    * Production instances retain nothing (the merged contexts would routinely hold PII), so this is empty for them.
    * Testkit-built instances keep the last 1000 events.
    */
  @deprecated("Use TestFeatureProvider.trackedEvents, which records what the provider received", "1.2.0")
  def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] = ZIO.succeed(Nil)
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

  def value[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx, options))

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

  def booleanOrDefault(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.booleanOrDefault(key, default, ctx, options))

  def stringOrDefault(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, String] =
    ZIO.serviceWithZIO(_.stringOrDefault(key, default, ctx, options))

  def intOrDefault(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, Int] =
    ZIO.serviceWithZIO(_.intOrDefault(key, default, ctx, options))

  def longOrDefault(
    key: String,
    default: Long,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, Long] =
    ZIO.serviceWithZIO(_.longOrDefault(key, default, ctx, options))

  def doubleOrDefault(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, Double] =
    ZIO.serviceWithZIO(_.doubleOrDefault(key, default, ctx, options))

  def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, Map[String, Any]] =
    ZIO.serviceWithZIO(_.objOrDefault(key, default, ctx, options))

  def valueOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(key, default, ctx, options))

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

  def value[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(flag, ctx, options))

  def valueOrDefault[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(flag, ctx, options))

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
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transaction(overrides, context, cacheEvaluations, nested)(zio))

  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Either[E, FeatureFlagError], TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transactionEither(overrides, context, cacheEvaluations, nested)(zio))

  def inTransaction: ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.inTransaction)

  def currentEvaluatedFlags: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[_]]] =
    ZIO.serviceWithZIO(_.currentEvaluatedFlags)

  def transactionEvaluations: ZIO[FeatureFlags, Nothing, Option[Map[String, FlagEvaluation[_]]]] =
    ZIO.serviceWithZIO(_.transactionEvaluations)

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

  @deprecated("Use TestFeatureProvider.trackedEvents, which records what the provider received", "1.2.0")
  def trackedEvents: ZIO[FeatureFlags, Nothing, List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    ZIO.serviceWithZIO(_.trackedEvents)

  def setProvider(provider: OFFeatureProvider): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(provider))

  // Factory Methods

  /** Default cap on how long initialization may take before the layer build fails or — for async layers — transitions
    * to `Fatal`. Mirrors the documented default referenced in every factory's ScalaDoc.
    */
  private[openfeature] val DefaultInitTimeout: Duration = 30.seconds

  private[openfeature] val DefaultConstructionRetries: Int      = 3
  private[openfeature] val DefaultConstructionBackoff: Duration = 1.second

  /** The default `constructionRetry` of `fromAcquireAsync`: up to three retries with jittered exponential backoff from
    * one second.
    */
  val DefaultConstructionRetry: Schedule[Any, Throwable, Any] =
    Schedule.recurs(DefaultConstructionRetries) && Schedule.exponential(DefaultConstructionBackoff).jittered

  /** Fallback provider name stamped when a provider exposes no metadata/name at registration time. The event-bridge
    * identity guard (`internal.EventBridge.fromCurrentProvider`) treats this as an indeterminate identity and fails
    * open, so a provider whose real name only appears after `initialize()` (e.g. the SDK's MultiProvider) still drives
    * its own status.
    */
  private[openfeature] val UnknownProviderName: String = "unknown"

  /** Default per-evaluation timeout: **1 second, applied to every evaluation unless overridden**. An evaluation whose
    * provider call takes longer fails with `FeatureFlagError.ProviderError` wrapping a `TimeoutException` — so a remote
    * provider with cold-start latency can fail its first evaluations out of the box. The provider call itself is
    * abandoned, not interrupted: the Java SDK offers no way to cancel it, so it runs on to completion on the blocking
    * pool. To change it for an instance: `FeatureFlagsConfig().withEvaluationTimeout(d)`, or `withoutEvaluationTimeout`
    * to disable it; per call, `EvaluationOptions.empty.withTimeout(d)` bounds a single evaluation and `.withoutTimeout`
    * disables it (the latter also skips the timeout scaffolding, which matters for microsecond-latency in-memory
    * providers).
    *
    * Each timed-out call keeps a blocking-pool thread until the provider returns, and that pool is unbounded, so
    * against a provider that never returns every timeout parks one more thread. [[DefaultMaxAbandonedCalls]] caps how
    * many such calls an instance tolerates; for a remote provider, wrapping it in the extras module's
    * `CircuitBreakerProvider` also stops new calls once it starts failing.
    */
  val DefaultEvaluationTimeout: Duration = 1.second

  /** Default cap on **abandoned** provider calls per `FeatureFlags` instance: **256**. A call is abandoned when the
    * [[DefaultEvaluationTimeout evaluation timeout]] fires (or the caller is interrupted) while the provider is still
    * running; it holds a blocking-pool thread until the provider returns. Once an instance has this many abandoned
    * calls still running, every new timed evaluation fails fast with `FeatureFlagError.ProviderError` wrapping a
    * `java.util.concurrent.RejectedExecutionException`, without calling the provider, until enough of them return. The
    * total tier (`*OrDefault`, `value`) absorbs that error and serves defaults, and the first rejection in each
    * `FallbackLogging` window logs a warning.
    *
    * Only abandoned calls count, so healthy traffic never gets near the cap however concurrent it is, and untimed
    * evaluations (`withoutEvaluationTimeout`, `EvaluationOptions.withoutTimeout`) can never be rejected. Change it with
    * `FeatureFlagsConfig().withMaxAbandonedCalls(n)` (or `fromAcquireAsync`'s `maxAbandonedCalls`); `Int.MaxValue`
    * disables the cap.
    */
  val DefaultMaxAbandonedCalls: Int = 256

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
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlagsFactories.build(
      provider,
      domain,
      version,
      initialHooks,
      statusRef,
      addShutdownFinalizer,
      apiOverride,
      evaluationTimeout,
      initTimeout,
      onReady,
      contextSource,
      fallbackLogging
    )

  /** [[build]], optionally keeping tracked events for `trackedEvents` (testkit only, #442). */
  private[openfeature] def buildWithTracking(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls,
    recordTracking: Boolean = false
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlagsFactories.buildWithTracking(
      provider,
      domain,
      version,
      initialHooks,
      statusRef,
      addShutdownFinalizer,
      apiOverride,
      evaluationTimeout,
      initTimeout,
      onReady,
      contextSource,
      fallbackLogging,
      maxAbandonedCalls,
      recordTracking
    )

  /** The config-driven factory: every retired overload forwards here. See [[FeatureFlagsConfig]] for field semantics
    * (`domain`, `version`, `initialHooks`, `evaluationTimeout`, `initTimeout`, `initMode`, `apiOwnership`).
    *
    * `FeatureFlagsConfig()` reproduces `fromProvider(provider)` exactly (sync init, no domain, `Owned` API — the `Auto`
    * truth table's `domain.isEmpty` branch).
    */
  def fromProvider(provider: OFFeatureProvider, config: FeatureFlagsConfig): ZLayer[Any, Throwable, FeatureFlags] =
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlagsFactories.fromProvider(provider, config, statusRef, apiOverride, onReady)

  /** Combine multiple providers into one via the SDK's `MultiProvider`, defaulting to a first-match strategy. Pairs
    * with [[fromProvider(OFFeatureProvider, FeatureFlagsConfig)]] to express combinations `fromMultiProvider` could
    * not, e.g. multi-provider + domain: `fromProvider(multiProvider(ps), FeatureFlagsConfig(domain =
    * Some("checkout")))`.
    *
    * Every provider stays in the chain, in order, even when two report the same metadata name (Java SDK 1.23.0; earlier
    * SDKs kept only the last of a name). The `getProviderHooks` of each provider the strategy consults run too; see
    * docs/extras.md for when they see this client's metadata.
    *
    * For the chain to advance past a provider, that provider must report `FLAG_NOT_FOUND` for a key it does not hold;
    * see [[MultiProviderStrategy.firstMatch]] for what does and does not cause fall-through.
    *
    * Long reads dispatch to each child's own `getLongEvaluation`, exact across the whole `Long` range, with the child's
    * provider hooks seeing `FlagValueType.LONG`. A bare `new MultiProvider(...)` does not: the SDK's `MultiProvider`
    * has no long getter, so a long read goes through each child's double getter, which this library's providers answer
    * with `TYPE_MISMATCH` for integer values. Build the chain here, or wrap a bare one in
    * `zio.openfeature.extras.IntegerWideningLongProvider` for Int-range values.
    */
  def multiProvider(
    providers: List[OFFeatureProvider],
    strategy: Strategy = new FirstMatchStrategy()
  ): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new LongAwareMultiProvider(providers.asJava, strategy)
  }

  /** Create a FeatureFlags layer from any OpenFeature provider.
    *
    * Initialization is bounded by the default 30s init timeout: if the provider's `initialize()` takes longer, or it
    * reports `ERROR`/`FATAL`/`NOT_READY` afterwards, the layer build fails with a `TimeoutException` or
    * `IllegalStateException` wrapped at the layer boundary. Use `fromProvider(provider, FeatureFlagsConfig())` with
    * `.withInitTimeout(...)` to override.
    */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig())

  /** Create a FeatureFlags layer with a global evaluation timeout.
    *
    * If a provider evaluation takes longer than `evaluationTimeout`, it fails with `ProviderError` containing a
    * `TimeoutException`. This prevents hung providers from blocking fibers indefinitely. Per-call timeouts set via
    * `EvaluationOptions.timeout` override this global default. Initialization uses the default 30s init timeout.
    */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withEvaluationTimeout(evalTimeout))", "0.2.0")
  def fromProvider(provider: OFFeatureProvider, evaluationTimeout: Duration): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withEvaluationTimeout(evaluationTimeout))

  /** Create a FeatureFlags layer with explicit evaluation and initialization timeouts.
    *
    * `initTimeout` bounds the sync init: if the provider's `initialize()` takes longer, the layer build fails with a
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withEvaluationTimeout(evaluationTimeout).withInitTimeout(initTimeout))

  /** Create a FeatureFlags layer with a named domain/client. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withDomain(d))", "0.2.0")
  def fromProviderWithDomain(provider: OFFeatureProvider, domain: String): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withDomain(domain))

  /** Create a FeatureFlags layer with a named domain/client and version. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withDomain(d).withVersion(v))", "0.2.0")
  def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    version: String
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withDomain(domain).withVersion(version))

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  private[openfeature] def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlagsFactories.fromProviderWithDomain(provider, domain, statusRef, api, onReady)

  /** Create a FeatureFlags layer from multiple providers using the first-match strategy. */
  @deprecated("Use fromProvider(FeatureFlags.multiProvider(ps), FeatureFlagsConfig())", "0.2.0")
  def fromMultiProvider(providers: List[OFFeatureProvider]): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers), FeatureFlagsConfig())

  /** Create a FeatureFlags layer from multiple providers with a custom strategy. */
  @deprecated("Use fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig())", "0.2.0")
  def fromMultiProvider(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers, strategy), FeatureFlagsConfig())

  /** Create a FeatureFlags layer with initial hooks. */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig().withHooks(hooks))", "0.2.0")
  def fromProviderWithHooks(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig().withHooks(initialHooks))

  // Async Factory Methods (non-blocking provider initialization)

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. The bridge is
    * attached before the provider is registered, so that event cannot be missed and arrives exactly once (#531).
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
    initTimeout: Duration = DefaultInitTimeout,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlagsFactories.buildAsync(
      provider,
      domain,
      version,
      initialHooks,
      statusRef,
      addShutdownFinalizer,
      apiOverride,
      onReady,
      evaluationTimeout,
      initTimeout,
      contextSource,
      fallbackLogging
    )

  /** [[buildAsync]], optionally keeping tracked events for `trackedEvents` (testkit only, #442). */
  private[openfeature] def buildAsyncWithTracking(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls,
    recordTracking: Boolean = false
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlagsFactories.buildAsyncWithTracking(
      provider,
      domain,
      version,
      initialHooks,
      statusRef,
      addShutdownFinalizer,
      apiOverride,
      onReady,
      evaluationTimeout,
      initTimeout,
      contextSource,
      fallbackLogging,
      maxAbandonedCalls,
      recordTracking
    )

  /** Create a FeatureFlags layer from any OpenFeature provider (non-blocking).
    *
    * The provider initializes in the background. Evaluations fail with `ProviderNotReady` until the provider is ready.
    * Use `onProviderReady` or `providerStatus` to detect when the provider becomes available. If the provider has not
    * become ready within the default 30s init timeout, status atomically transitions to `Fatal` so callers polling
    * `providerStatus` stop waiting. Use `fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async))` with
    * `.withInitTimeout(...)` to override.
    */
  def fromProviderAsync(provider: OFFeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain).withVersion(version))

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  private[openfeature] def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlagsFactories.fromProviderWithDomainAsync(provider, domain, statusRef, api, onReady)

  /** Create a FeatureFlags layer with initial hooks (non-blocking). */
  @deprecated("Use fromProvider(p, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(hooks))", "0.2.0")
  def fromProviderWithHooksAsync(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(initialHooks))

  /** Create a FeatureFlags layer from multiple providers (non-blocking, first-match strategy). */
  @deprecated(
    "Use fromProvider(FeatureFlags.multiProvider(ps), FeatureFlagsConfig(initMode = InitMode.Async))",
    "0.2.0"
  )
  def fromMultiProviderAsync(providers: List[OFFeatureProvider]): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers), FeatureFlagsConfig(initMode = InitMode.Async))

  /** Create a FeatureFlags layer from multiple providers with a custom strategy (non-blocking). */
  @deprecated(
    "Use fromProvider(FeatureFlags.multiProvider(ps, strategy), FeatureFlagsConfig(initMode = InitMode.Async))",
    "0.2.0"
  )
  def fromMultiProviderAsync(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Any, Throwable, FeatureFlags] =
    fromProvider(multiProvider(providers, strategy), FeatureFlagsConfig(initMode = InitMode.Async))

  /** Default `compose` for [[fromAcquireAsync]]: layer the real provider over the fallback with a first-successful
    * strategy, so a sick real provider transparently falls through to the fallback. Both stay in the chain even if they
    * report the same metadata name (Java SDK 1.23.0; earlier SDKs dropped the real provider in that case). Long reads
    * use each provider's own long getter, as with [[multiProvider]].
    */
  def defaultAcquireCompose(real: OFFeatureProvider, fallback: OFFeatureProvider): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new LongAwareMultiProvider(List(real, fallback).asJava, new FirstSuccessfulStrategy())
  }

  /** Fallback-first async factory: build the layer immediately on a fresh `fallback`, construct the real provider in a
    * background scoped fiber, and switch the registered provider's delegate to it once it is initialized.
    *
    * Unlike every other factory, this takes the real provider **as an effect** (`acquire`) rather than a strict,
    * already-constructed instance — so a provider whose Java constructor does network I/O never runs on the
    * application's boot path. The layer's error channel is `Nothing` (`URLayer`): a failing real provider is handled
    * internally (retried, then `onConstructionError`, then left on the fallback), so the compiler proves no
    * provider-related failure can propagate into the application's layer graph.
    *
    *   - Status is `Ready` from time zero; evaluations answer fallback values (never `ProviderNotReady`) until the
    *     swap, and the swap itself never interrupts service. The SDK sees one provider for the life of the layer, an
    *     internal switch that delegates to the fallback; the real provider is composed and **initialized off to the
    *     side**, bounded by `initTimeout`, and only then does the switch start routing to it. There is no SDK
    *     `setProvider` on this path, so neither this library nor the SDK reports `NotReady` at any point (#512).
    *     Because `providerStatus` therefore cannot tell fallback from real, the layer also provides an
    *     [[AcquireStatus]] whose [[AcquireState]] is `Constructing` until the real provider is acquired, verified
    *     **and** swapped in (`Live`), or the construction fails terminally (`Failed(cause)`, set just before
    *     `onConstructionError` runs). `onSwapped` is the success-side twin of `onConstructionError`: it runs once
    *     `Live` is observable.
    *   - Each attempt runs `acquire` and then `verify` on the acquired candidate, the pair bounded by
    *     `constructionTimeout` and retried with `constructionRetry`. A `verify` failure is an attempt failure exactly
    *     like an `acquire` failure: the candidate is released, the schedule advances, the fallback keeps serving. On
    *     terminal failure, `onConstructionError` runs (with `verify`'s error where that was the cause) and the instance
    *     stays on the fallback.
    *   - `verify` sees the **bare** candidate exactly as `acquire` returned it — before `compose`, before the swap,
    *     never the live stack — so in-flight evaluations cannot observe a half-verified provider. Two consequences: the
    *     composed stack has **not** been initialized yet (that happens next, before the switch), and no ambient context
    *     (`contextSource`, global context) applies. A provider that only answers after `initialize()` must be
    *     initialized inside `acquire` (and its `initialize` must then tolerate the second call before the switch), or
    *     be verified through its own readiness API rather than an evaluation. Construction success is a weak health
    *     signal (a provider can construct on bad credentials or an empty config and then serve wrong values that a
    *     first-successful chain would happily accept); `verify` is where a sentinel evaluation such as
    *     [[Verify.flagExists]] proves the candidate is actually answering.
    *   - The composed stack uses a **fresh** fallback instance (the switch shuts the replaced first fallback down,
    *     bounded, right after it flips, so the first fallback must not be reused). Hooks, contexts, and event handlers
    *     survive the swap; the composed stack's events reach them through the switch. An initialization that fails or
    *     exceeds `initTimeout` is a swap failure: the composed stack is shut down, the fallback keeps serving, and the
    *     state becomes `Failed`. A user `setProvider` that lands before the switch wins: the acquired provider is
    *     discarded and the state becomes `Failed`.
    *   - **Teardown.** Every attempt runs `acquire` in its own child `Scope`. A candidate that does not make it into
    *     service — `acquire` failed after registering finalizers, `verify` failed, the attempt timed out, or the swap
    *     itself failed — has that scope closed immediately, so its finalizers run then rather than at layer close. The
    *     candidate that is swapped in has its scope handed to the layer scope, so a real provider acquired but not yet
    *     swapped is still torn down on layer release, and layer release still interrupts an in-flight attempt. As
    *     before, this only tears down what `acquire` registers in its `Scope` (e.g. via `ZIO.acquireRelease`); a bare
    *     `attemptBlocking(new Provider(...))` registers no finalizer, so teardown of the not-yet-swapped provider is
    *     then the caller's responsibility.
    *
    * @param acquire
    *   the real provider as a scoped effect (typically `ZIO.attemptBlocking(new Provider(...))`)
    * @param fallback
    *   a scoped effect producing a FRESH fallback instance each time it is run
    * @param compose
    *   how to combine (real, freshFallback) into the swapped-in provider
    * @param constructionRetry
    *   retry policy for the `acquire` + `verify` attempt
    * @param constructionTimeout
    *   per-attempt bound on `acquire` + `verify`
    * @param onConstructionError
    *   run when `acquire`, `verify`, or the swap ultimately fails; the instance stays on the fallback
    * @param verify
    *   run on each acquired candidate before the swap; failure rejects the candidate and counts as a failed attempt.
    *   Defaults to accepting every candidate. See [[Verify]] for ready-made checks.
    * @param onSwapped
    *   run once after the switch routes to the real provider and [[AcquireState.Live]] is observable
    * @param maxAbandonedCalls
    *   cap on provider calls abandoned by `evaluationTimeout` and still running, see [[DefaultMaxAbandonedCalls]]; must
    *   be at least 1
    */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider = defaultAcquireCompose,
    constructionRetry: Schedule[Any, Throwable, Any] = DefaultConstructionRetry,
    constructionTimeout: Duration = DefaultInitTimeout,
    onConstructionError: Throwable => UIO[Unit] = _ => ZIO.unit,
    evaluationTimeout: Duration = DefaultEvaluationTimeout,
    initTimeout: Duration = DefaultInitTimeout,
    // Taken directly rather than via `FeatureFlagsConfig`, because this factory bypasses config entirely — a
    // config-only surface would leave the fallback-first path with no way to supply an ambient context (#353).
    contextSource: ContextSource = ContextSource.empty,
    // Last, so every existing positional call site keeps compiling; `compose` stays the one mid-list parameter a
    // caller plausibly passes positionally (#349, #352).
    verify: OFFeatureProvider => Task[Unit] = _ => ZIO.unit,
    onSwapped: UIO[Unit] = ZIO.unit,
    // Its own parameter, not a config field: this factory bypasses `FeatureFlagsConfig`, and it is exactly the
    // fallback-first path where a served-default log storm during an outage matters most (#350).
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    // Its own parameter for the same reason as `fallbackLogging` (#437).
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls
  ): URLayer[Any, FeatureFlags with AcquireStatus] =
    FeatureFlagsFactories.fromAcquireAsync(
      acquire,
      fallback,
      compose,
      constructionRetry,
      constructionTimeout,
      onConstructionError,
      evaluationTimeout,
      initTimeout,
      contextSource,
      verify,
      onSwapped,
      fallbackLogging,
      maxAbandonedCalls
    )

  // Binary-compatibility bridge for callers compiled against 1.1.2, before `maxAbandonedCalls` (#437). Package-private,
  // so source callers never see it, but public in bytecode, so an already-compiled call still links. No defaults: a
  // call needing one resolves to the full overload above.
  private[openfeature] def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider,
    constructionRetry: Schedule[Any, Throwable, Any],
    constructionTimeout: Duration,
    onConstructionError: Throwable => UIO[Unit],
    evaluationTimeout: Duration,
    initTimeout: Duration,
    contextSource: ContextSource,
    verify: OFFeatureProvider => Task[Unit],
    onSwapped: UIO[Unit],
    fallbackLogging: FallbackLogging
  ): URLayer[Any, FeatureFlags with AcquireStatus] =
    FeatureFlagsFactories.fromAcquireAsync(
      acquire,
      fallback,
      compose,
      constructionRetry,
      constructionTimeout,
      onConstructionError,
      evaluationTimeout,
      initTimeout,
      contextSource,
      verify,
      onSwapped,
      fallbackLogging,
      DefaultMaxAbandonedCalls
    )
}
