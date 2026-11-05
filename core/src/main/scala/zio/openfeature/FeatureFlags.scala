package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.FeatureFlagError.{EvaluationError, TransactionError}
import zio.openfeature.internal.{Evaluator, FallbackLogLimiter, FeatureFlagsFactories, LongAwareMultiProvider}
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}

/** The ZIO face of an OpenFeature client. Evaluation comes in two tiers, and which one you call is a statement about
  * what a missing or broken flag should do to the caller:
  *
  *   - '''Typed tier''' — `boolean`/`string`/`int`/`long`/`double`/`obj`/`value`, every `*Details`, and the `FlagDef`
  *     overloads. Return `IO[FeatureFlagError.EvaluationError, _]` and '''fail''' whenever the evaluation cannot
  *     produce a real value: a provider that throws, a decode that rejects the payload, and a provider that answers
  *     with an '''error code''' on the resolution (`FLAG_NOT_FOUND`, `TYPE_MISMATCH`, `PARSE_ERROR`, …), which is how
  *     most providers report problems. The code is mapped to the matching [[FeatureFlagError]] (`FlagNotFound(key)`,
  *     `TypeMismatch(key, expected, detail)`, …). Reach for this tier when a default would be ''wrong'' — a fail-closed
  *     gate written with `value` actually fails closed.
  *   - '''Total tier''' — `*OrDefault` and `resolveOrDefault`. Return `UIO[_]` and '''never fail''': the caller's
  *     default is served and, on `resolveOrDefault`, the returned [[FlagResolution]] says why (`reason = Error`,
  *     `errorCode`, `errorMessage`). This is the OpenFeature spec's §1.4.10 "always return the default" contract, and
  *     it is the migration path for code that used to read `errorCode` off a `*Details` result.
  *
  * Hooks observe an error-coded resolution as the `error` stage on both tiers; the two tiers differ only in what the
  * ''caller'' sees. Everything else — context merging, transactions, caching, timeouts — is shared.
  */
trait FeatureFlags extends FeatureFlagsTransactions {

  // Typed tier. `valueDetails` is the one abstract evaluation method; everything else on this tier is defined through
  // it, so an implementor writes it once. Each fails with a typed `EvaluationError` when the evaluation cannot
  // produce a real value — see the trait scaladoc for the contract. `default` is what the provider and hooks receive
  // for this evaluation; it is not served on failure: use the `*OrDefault` form for that.

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[A]]

  final def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[Boolean]] =
    valueDetails[Boolean](key, default, ctx, options)

  final def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[String]] =
    valueDetails[String](key, default, ctx, options)

  final def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[Int]] =
    valueDetails[Int](key, default, ctx, options)

  final def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[Long]] =
    valueDetails[Long](key, default, ctx, options)

  final def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[Double]] =
    valueDetails[Double](key, default, ctx, options)

  final def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, FlagResolution[Map[String, Any]]] =
    valueDetails[Map[String, Any]](key, default, ctx, options)

  /** The value alone. `options` carries per-call [[EvaluationOptions]] (invocation hooks, hints, a timeout). */
  def value[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, A] =
    valueDetails[A](key, default, ctx, options).map(_.value)

  final def boolean(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, Boolean] =
    value[Boolean](key, default, ctx, options)

  final def string(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, String] =
    value[String](key, default, ctx, options)

  final def int(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, Int] =
    value[Int](key, default, ctx, options)

  final def long(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, Long] =
    value[Long](key, default, ctx, options)

  final def double(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, Double] =
    value[Double](key, default, ctx, options)

  final def obj(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[EvaluationError, Map[String, Any]] =
    value[Map[String, Any]](key, default, ctx, options)

  // Total (never-fails) evaluation variants (spec §1.4.10 / §1.1.7: evaluation "MUST NOT throw ... always return the
  // default value"). Any evaluation error is absorbed into the supplied default: `*OrDefault` returns the value,
  // `resolveOrDefault` returns the full `FlagResolution` with `reason = Error` and `errorCode`/`errorMessage` populated
  // so the caller can still see WHY the default was served. Both typed `FeatureFlagError`s and defects (unexpected
  // exceptions) are absorbed — the opt-in "give me a value no matter what" contract — but fiber interruption is always
  // propagated, so cancellation still works. The non-total methods above remain for callers who want to handle errors.

  def valueOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[A] =
    totalResolution(
      resolutionForTotal(key, default, ctx, options)(valueDetails(key, default, ctx, options)),
      key,
      default
    ).map(_.value)

  final def booleanOrDefault(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[Boolean] =
    valueOrDefault[Boolean](key, default, ctx, options)

  final def stringOrDefault(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[String] =
    valueOrDefault[String](key, default, ctx, options)

  final def intOrDefault(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[Int] =
    valueOrDefault[Int](key, default, ctx, options)

  final def longOrDefault(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[Long] =
    valueOrDefault[Long](key, default, ctx, options)

  final def doubleOrDefault(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[Double] =
    valueOrDefault[Double](key, default, ctx, options)

  final def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[Map[String, Any]] =
    valueOrDefault[Map[String, Any]](key, default, ctx, options)

  /** Total details variant: like [[valueDetails]] but never fails — an evaluation error yields a `FlagResolution` whose
    * `reason` is `Error`, `value` is `default`, and `errorCode`/`errorMessage` describe the failure (spec §1.4.10).
    */
  def resolveOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): UIO[FlagResolution[A]] =
    totalResolution(
      resolutionForTotal(key, default, ctx, options)(valueDetails(key, default, ctx, options)),
      key,
      default
    )

  // Typed flag definition overloads
  //
  // The typed ones fail like the key-based typed tier; `flag.default` is passed to the evaluation, not served on failure.
  //
  // Each delegates to the key-based generic tier above, passing `flag.default` as the evaluation default and
  // `flag.flagType` as the decoding instance. There is no new evaluation machinery here, and no new failure mode:
  // the partial overloads keep the `FeatureFlagError` channel, the total ones keep the never-fails `UIO` contract.
  //
  // Spelled as default-free arity pairs rather than with default arguments, because the key-based `value` /
  // `valueOrDefault` / `resolveOrDefault` / `valueDetails` already carry defaults and Scala rejects two overloads of
  // one method that both have default arguments.
  //
  // The local `implicit val` is a cross-build necessity rather than a style lapse: this file compiles for both 2.13
  // and 3, and passing a context-bound instance explicitly would need `(using flag.flagType)` on 3 but
  // `(flag.flagType)` on 2.13 — which cannot be written once. An `implicit val` in scope satisfies both.

  def value[A](flag: FlagDef[A]): IO[EvaluationError, A] = {
    implicit val ft: FlagType[A] = flag.flagType
    value(flag.key, flag.default)
  }

  def value[A](flag: FlagDef[A], ctx: EvaluationContext): IO[EvaluationError, A] = {
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

  def value[A](flag: FlagDef[A], ctx: EvaluationContext, options: EvaluationOptions): IO[EvaluationError, A] = {
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

  def valueDetails[A](flag: FlagDef[A]): IO[EvaluationError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default)
  }

  def valueDetails[A](flag: FlagDef[A], ctx: EvaluationContext): IO[EvaluationError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default, ctx)
  }

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[EvaluationError, FlagResolution[A]] = {
    implicit val ft: FlagType[A] = flag.flagType
    valueDetails(flag.key, flag.default, ctx, options)
  }

  private def totalResolution[A: FlagType](
    details: IO[EvaluationError, FlagResolution[A]],
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
      // An error-coded resolution arrives here when `resolutionForTotal` returns the provider's own answer: it is
      // served with the caller's default, keeping the variant, flag metadata and message the provider gave. A
      // resolution with `reason = Error` and no code is likewise a served-default fallback and gets the breadcrumb.
      resolved =>
        resolved.errorCode match {
          case Some(code) =>
            val served = resolved.copy(
              value = default,
              reason = ResolutionReason.Error,
              errorMessage = resolved.errorMessage.orElse(
                Some(Evaluator.errorFromResolution(key, code, None, FlagType[A].typeName).message)
              )
            )
            logFallback(key, served, defect = None).as(served)
          case None =>
            if (resolved.isError) logFallback(key, resolved, defect = None).as(resolved) else ZIO.succeed(resolved)
        }
    )

  /** The resolution the total tier serves from. By default the typed tier's, whose provider-reported error codes arrive
    * as failures; an implementation that can return the provider's own error resolution instead — its variant, flag
    * metadata and message — overrides this.
    */
  private[openfeature] def resolutionForTotal[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  )(typed: => IO[EvaluationError, FlagResolution[A]]): IO[EvaluationError, FlagResolution[A]] = typed

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
    * The error channel is tagged by source: `Left(e)` carries the caller's own error from `zio`, `Right(err)` a
    * [[FeatureFlagError.TransactionError]] raised by the transaction itself (`NestedTransactionNotAllowed`). An
    * evaluation failing inside `zio` is part of `E`, not a transaction error. On Scala 3, `transaction` is the same
    * with the tag dropped, `E | TransactionError`; Scala 2.13 has no union types, so this is its only form there.
    *
    * `nested` decides what happens when this call is already inside a transaction — see [[NestedPolicy]]. The default,
    * `Fail`, raises `NestedTransactionNotAllowed`. Under `Reuse` the body runs inside the enclosing transaction,
    * `overrides`/`context`/`cacheEvaluations` are '''ignored''', and the returned `TransactionResult` reflects the
    * enclosing transaction as of the body's completion.
    */
  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R, Either[E, TransactionError], TransactionResult[A]]

  def inTransaction: UIO[Boolean]

  /** The flags the current transaction has evaluated so far — `Map.empty` both when there is no transaction and when
    * there is one that has evaluated nothing yet. That ambiguity is deliberate for a convenience read but is the wrong
    * shape for an audit record ("which flags shaped this request"), where an empty answer must not be mistaken for a
    * real one: prefer [[transactionEvaluations]], which answers `None` outside a transaction.
    */
  def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]

  /** The current transaction's evaluations, or `None` when this fiber is not inside a transaction at all.
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

  def addHook(hook: FeatureHook): UIO[Unit]
  def addHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearHooks: UIO[Unit]
  def hooks: UIO[List[FeatureHook]]

  // Tracking API
  def track(eventName: String): IO[EvaluationError, Unit]
  def track(eventName: String, context: EvaluationContext): IO[EvaluationError, Unit]
  def track(eventName: String, details: TrackingEventDetails): IO[EvaluationError, Unit]
  def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): IO[EvaluationError, Unit]
}

object FeatureFlags extends FeatureFlagsTransactionAccessors {

  // Service Accessors - typed tier. `default` is what the provider and hooks receive for this evaluation; it is not
  // served on failure: use the `*OrDefault` form for that.

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default, ctx, options))

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default, ctx, options))

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default, ctx, options))

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default, ctx, options))

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default, ctx, options))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx, options))

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default, ctx, options))

  def value[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx, options))

  def boolean(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default, ctx, options))

  def string(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, String] =
    ZIO.serviceWithZIO(_.string(key, default, ctx, options))

  def int(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, Int] =
    ZIO.serviceWithZIO(_.int(key, default, ctx, options))

  def long(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, Long] =
    ZIO.serviceWithZIO(_.long(key, default, ctx, options))

  def double(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, Double] =
    ZIO.serviceWithZIO(_.double(key, default, ctx, options))

  def obj(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, EvaluationError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default, ctx, options))

  // Service Accessors - total (never-fails) evaluation (spec §1.4.10)

  def valueOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(key, default, ctx, options))

  def resolveOrDefault[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.resolveOrDefault(key, default, ctx, options))

  def booleanOrDefault(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.booleanOrDefault(key, default, ctx, options))

  def stringOrDefault(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, String] =
    ZIO.serviceWithZIO(_.stringOrDefault(key, default, ctx, options))

  def intOrDefault(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, Int] =
    ZIO.serviceWithZIO(_.intOrDefault(key, default, ctx, options))

  def longOrDefault(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, Long] =
    ZIO.serviceWithZIO(_.longOrDefault(key, default, ctx, options))

  def doubleOrDefault(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, Double] =
    ZIO.serviceWithZIO(_.doubleOrDefault(key, default, ctx, options))

  def objOrDefault(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, Nothing, Map[String, Any]] =
    ZIO.serviceWithZIO(_.objOrDefault(key, default, ctx, options))

  // Service Accessors - typed flag definitions
  //
  // No `implicit val` is needed here (unlike the trait methods): the `FlagDef` carries its own `FlagType`, and the
  // trait overload it delegates to takes no context bound.

  def value[A](flag: FlagDef[A]): ZIO[FeatureFlags, EvaluationError, A] =
    ZIO.serviceWithZIO(_.value(flag))

  def value[A](flag: FlagDef[A], ctx: EvaluationContext): ZIO[FeatureFlags, EvaluationError, A] =
    ZIO.serviceWithZIO(_.value(flag, ctx))

  def valueOrDefault[A](flag: FlagDef[A]): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(flag))

  def valueOrDefault[A](flag: FlagDef[A], ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, A] =
    ZIO.serviceWithZIO(_.valueOrDefault(flag, ctx))

  def value[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, EvaluationError, A] =
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

  def valueDetails[A](flag: FlagDef[A]): ZIO[FeatureFlags, EvaluationError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(flag))

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(flag, ctx))

  def valueDetails[A](
    flag: FlagDef[A],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, EvaluationError, FlagResolution[A]] =
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

  def transactionEither[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Either[E, TransactionError], TransactionResult[A]] =
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

  def addHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHook(hook))

  def addHooks(hooks: List[FeatureHook]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHooks(hooks))

  def clearHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearHooks)

  def hooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.hooks)

  // API-level Hooks (per OpenFeature spec 4.4.1)

  // Tracking API

  def track(eventName: String): ZIO[FeatureFlags, EvaluationError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName))

  def track(eventName: String, context: EvaluationContext): ZIO[FeatureFlags, EvaluationError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context))

  def track(eventName: String, details: TrackingEventDetails): ZIO[FeatureFlags, EvaluationError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, details))

  def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): ZIO[FeatureFlags, EvaluationError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context, details))

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

  /** The name reported for a provider that exposes none: one with no name yet (`internal.ProviderName.Pending`, whose
    * real name may only appear in `initialize()`, as the SDK's MultiProvider's does), or one that has none
    * (`internal.ProviderName.Nameless`). The event-bridge identity guard tells the two apart, not this string.
    */
  private[openfeature] val UnknownProviderName: String = "unknown"

  /** Default per-evaluation timeout: **1 second, applied to every evaluation unless overridden**. An evaluation whose
    * provider call takes longer fails with `FeatureFlagError.ProviderError` wrapping a `TimeoutException` — so a remote
    * provider with cold-start latency can fail its first evaluations out of the box. The provider call itself is
    * abandoned, not interrupted: the Java SDK offers no way to cancel it, so it runs on to completion on the blocking
    * pool. To change it for an instance: `FeatureFlagsConfig.default.withEvaluationTimeout(d)`, or
    * `withoutEvaluationTimeout` to disable it; per call, `EvaluationOptions.empty.withTimeout(d)` bounds a single
    * evaluation and `.withoutTimeout` disables it (the latter also skips the timeout scaffolding, which matters for
    * microsecond-latency in-memory providers).
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
    * `FeatureFlagsConfig.default.withMaxAbandonedCalls(n)` (or `fromAcquireAsync`'s `maxAbandonedCalls`);
    * `Int.MaxValue` disables the cap.
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
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls
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
      fallbackLogging,
      maxAbandonedCalls
    )

  /** The config-driven factory: every retired overload forwards here. See [[FeatureFlagsConfig]] for field semantics
    * (`domain`, `version`, `initialHooks`, `evaluationTimeout`, `initTimeout`, `initMode`, `apiOwnership`).
    *
    * `FeatureFlagsConfig.default` reproduces `fromProvider(provider)` exactly (sync init, no domain, `Owned` API — the
    * `Auto` truth table's `domain.isEmpty` branch).
    */
  def fromProvider(
    provider: OFFeatureProvider,
    config: FeatureFlagsConfig
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
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
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    FeatureFlagsFactories.fromProvider(provider, config, statusRef, apiOverride, onReady)

  /** Combine multiple providers into one via the SDK's `MultiProvider`, defaulting to a first-match strategy. Pairs
    * with [[fromProvider(OFFeatureProvider, FeatureFlagsConfig)]] to express combinations `fromMultiProvider` could
    * not, e.g. multi-provider + domain: `fromProvider(multiProvider(ps),
    * FeatureFlagsConfig.default.withDomain("checkout"))`.
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
    *
    * `track` goes to every child, in order, whatever the strategy; a bare `new MultiProvider(...)` drops it. If a child
    * throws, the rest still receive the event and the call fails with the first error.
    */
  def multiProvider(
    providers: List[OFFeatureProvider],
    strategy: MultiProviderStrategy.Strategy = MultiProviderStrategy.FirstMatch
  ): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new LongAwareMultiProvider(providers.asJava, strategy)
  }

  /** Create a FeatureFlags layer from any OpenFeature provider.
    *
    * Initialization is bounded by the default 30s init timeout: if the provider's `initialize()` takes longer, or it
    * reports `ERROR`/`FATAL`/`NOT_READY` afterwards, the layer build fails with a `TimeoutException` or
    * `IllegalStateException` wrapped at the layer boundary. Use `fromProvider(provider, FeatureFlagsConfig.default)`
    * with `.withInitTimeout(...)` to override.
    */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    fromProvider(provider, FeatureFlagsConfig.default)

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  private[openfeature] def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    FeatureFlagsFactories.fromProviderWithDomain(provider, domain, statusRef, api, onReady)

  // Async Factory Methods (non-blocking provider initialization)

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. The bridge is
    * attached before the provider is registered, so that event cannot be missed and arrives exactly once.
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
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls
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
      fallbackLogging,
      maxAbandonedCalls
    )

  /** Create a FeatureFlags layer from any OpenFeature provider (non-blocking).
    *
    * The provider initializes in the background. Evaluations fail with `ProviderNotReady` until the provider is ready.
    * Use `onProviderReady` or `providerStatus` to detect when the provider becomes available. If the provider has not
    * become ready within the default 30s init timeout, status atomically transitions to `Fatal` so callers polling
    * `providerStatus` stop waiting. Use `fromProvider(provider, FeatureFlagsConfig.default.withAsyncInit)` with
    * `.withInitTimeout(...)` to override.
    */
  def fromProviderAsync(provider: OFFeatureProvider): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    fromProvider(provider, FeatureFlagsConfig.default.withAsyncInit)

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  private[openfeature] def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    FeatureFlagsFactories.fromProviderWithDomainAsync(provider, domain, statusRef, api, onReady)

  /** Default `compose` for [[fromAcquireAsync]]: layer the real provider over the fallback with a first-successful
    * strategy, so a sick real provider transparently falls through to the fallback. Both stay in the chain even if they
    * report the same metadata name (Java SDK 1.23.0; earlier SDKs dropped the real provider in that case). Long reads
    * use each provider's own long getter, as with [[multiProvider]].
    */
  def defaultAcquireCompose(real: OFFeatureProvider, fallback: OFFeatureProvider): OFFeatureProvider = {
    import scala.jdk.CollectionConverters._
    new LongAwareMultiProvider(List(real, fallback).asJava, MultiProviderStrategy.FirstSuccessful)
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
    *     `setProvider` on this path, so neither this library nor the SDK reports `NotReady` at any point. Because
    *     `providerStatus` therefore cannot tell fallback from real, the layer also provides an [[AcquireStatus]] whose
    *     [[AcquireState]] is `Constructing` until the real provider is acquired, verified **and** swapped in (`Live`),
    *     or the construction fails terminally (`Failed(cause)`, set just before `onConstructionError` runs).
    *     `onSwapped` is the success-side twin of `onConstructionError`: it runs once `Live` is observable.
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
    *   - **Teardown.** The library shuts down every provider `acquire` returns, exactly once: the one it swapped in
    *     with the API, when the instance owns the API, and any other as soon as it is out of the running. Don't call
    *     `shutdown()` from a finalizer `acquire` registers. Its `Scope` is for the provider's other resources, such as
    *     an HTTP client. Every attempt has its own child `Scope`, which closes after the provider has shut down: right
    *     away for a candidate that does not make it into service, at layer release for the one that does. A swapped-in
    *     provider on an instance that does not own the API stays bound to that API, so the API's owner shuts it down,
    *     and its `Scope` still closes at layer release.
    *   - **An abandoned attempt.** A timeout or a layer release does not wait for a blocking constructor. It keeps its
    *     `Scope` open, and when it returns the provider is shut down and the `Scope` closes; one that never returns
    *     keeps them for good. An attempt suspended in an asynchronous call is interrupted, and whatever that call would
    *     have returned is lost, so run the constructor directly in `acquire`: behind a `fork`, a `timeout` or a
    *     `zipPar` the waiting fiber is suspended, and the library cannot reach what the constructor returns. If that
    *     interrupt does not finish, the timeout or the release goes on after a short bound and leaves it running.
    *   - `compose` runs on the construction fiber and should not block, because layer release waits for it.
    *
    * @param acquire
    *   the real provider as a scoped effect (typically `ZIO.attemptBlocking(new Provider(...))`). The library owns the
    *   provider it returns; its `Scope` is for that provider's other resources, and closes after the provider's
    *   `shutdown()`
    * @param fallback
    *   a scoped effect producing a FRESH fallback instance each time it is run
    * @param config
    *   what every instance takes: domain, version, initial hooks, timeouts (`initTimeout` bounds the composed stack's
    *   initialization), API ownership, context source, fallback logging and `maxAbandonedCalls`. `initMode` does not
    *   apply: the fallback is installed at once and the real provider always arrives later.
    * @param acquireConfig
    *   how the real provider is composed, retried, bounded, verified and reported; see [[AcquireConfig]]
    */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    config: FeatureFlagsConfig,
    acquireConfig: AcquireConfig
  ): URLayer[Any, FeatureFlags with FeatureFlagsAdmin with AcquireStatus] =
    FeatureFlagsFactories.fromAcquireAsync(acquire, fallback, config, acquireConfig)

  /** [[fromAcquireAsync(RIO[Scope, OFFeatureProvider], URIO[Scope, OFFeatureProvider], FeatureFlagsConfig, AcquireConfig)]]
    * with [[AcquireConfig.default]].
    */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    config: FeatureFlagsConfig
  ): URLayer[Any, FeatureFlags with FeatureFlagsAdmin with AcquireStatus] =
    fromAcquireAsync(acquire, fallback, config, AcquireConfig.default)

  /** [[fromAcquireAsync(RIO[Scope, OFFeatureProvider], URIO[Scope, OFFeatureProvider], FeatureFlagsConfig, AcquireConfig)]]
    * with the default config and [[AcquireConfig.default]].
    */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider]
  ): URLayer[Any, FeatureFlags with FeatureFlagsAdmin with AcquireStatus] =
    fromAcquireAsync(acquire, fallback, FeatureFlagsConfig.default, AcquireConfig.default)
}
