package zio.openfeature.internal

import zio._
import zio.openfeature._
import dev.openfeature.sdk.{Client => OFClient, FlagEvaluationDetails}

import scala.jdk.CollectionConverters._

/** The evaluation path of a [[FeatureFlagsLive]]: context merging, the readiness gate, transactions, the provider call
  * with its timeout and abandoned-call cap, and decoding the result.
  */
final private[openfeature] class Evaluator(
  client: OFClient,
  state: FeatureFlagsState,
  contextSource: ContextSource,
  evaluationTimeout: Option[Duration],
  maxAbandonedCalls: Int,
  fallbackLogLimiter: FallbackLogLimiter,
  hookPipeline: HookPipeline
) {
  import Evaluator.{convertImmutableMetadata, errorFromResolution}

  // Provider calls abandoned by the evaluation timeout that have not returned yet. Each one parks a blocking-pool
  // thread, and that pool is unbounded, so `withTimeout` refuses new timed calls once this reaches `maxAbandonedCalls`.
  // AtomicInteger (not Ref): it is read on every timed evaluation, and decremented from the abandoned call's finalizer.
  private val abandonedCalls = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Abandoned provider calls still running — for tests. */
  private[openfeature] def abandonedCallCount: Int = abandonedCalls.get()

  // Cache the Scala->Java context conversion by object identity. In the common case every per-call context layer
  // (transaction/client/fiber-local/invocation) is empty, so the merged context IS the (unchanged) global context
  // object — converting it once and reusing it avoids a fresh MutableContext plus one Value per attribute on every
  // call. Keyed on identity: a different context (after `setGlobalContext`, or a non-empty invocation layer) misses and
  // is re-converted, so no explicit invalidation is needed. Reuse is safe because the OpenFeature contract treats the
  // context passed to a provider as read-only.
  private val javaContextCache =
    new java.util.concurrent.atomic.AtomicReference[(EvaluationContext, dev.openfeature.sdk.EvaluationContext)](null)

  private[openfeature] def toJavaContext(context: EvaluationContext): dev.openfeature.sdk.EvaluationContext = {
    val cached = javaContextCache.get()
    if (cached != null && (cached._1 eq context)) cached._2
    else {
      val converted = ContextConverter.toOpenFeature(context)
      javaContextCache.set((context, converted))
      converted
    }
  }

  // Context merges per OpenFeature spec: API (global) -> Transaction -> Client -> FiberLocal -> Invocation,
  // with the pull-based ContextSource slotted between Client and FiberLocal: ambient request identity
  // overrides static client/global context, but an explicit `withContext` or a per-call context still wins over it.
  //
  // This is also the reason a ContextSource cannot be a `before` hook. A hook's contribution is merged on TOP of the
  // finished context below, so it could only ever take the highest-precedence slot — and `HookContext` exposes one
  // flattened context with no provenance, so a hook cannot rebuild this ordering either.
  //
  // Both evaluation and `track` route through here, so the source applies to both.
  private[openfeature] def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for {
      global      <- state.globalContextRef.get
      clientCtx   <- state.clientContextRef.get
      ambient     <- contextSource.current
      fiberLocal  <- state.fiberContextRef.get
      transaction <- state.transactionRef.get
      txContext = transaction.map(_.context).getOrElse(EvaluationContext.empty)
    } yield global
      .merge(txContext)
      .merge(clientCtx)
      .merge(ambient)
      .merge(fiberLocal)
      .merge(invocation)

  private def checkProviderStatus: IO[FeatureFlagError, Unit] =
    providerStatus.flatMap {
      case ProviderStatus.Fatal        => ZIO.fail(FeatureFlagError.ProviderFatal)
      case ProviderStatus.NotReady     => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      case ProviderStatus.ShuttingDown =>
        ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.ShuttingDown))
      // Error/Ready/Stale proceed: fast-failing only NOT_READY and FATAL is this library's deliberate policy — spec
      // v0.9.0 removed the requirements that used to mandate this and renumbered the equivalent scenarios to
      // @spec-2.2.7 (+ @spec-1.4.10) as *permitted*, not required, behaviour. A provider in ERROR is typically a
      // transient, recoverable state and commonly still serves cached values — let the evaluation reach it (the
      // provider serves or errors on its own) rather than turning one PROVIDER_ERROR into a total outage.
      case _ => Exit.unit
    }

  // Context is already merged by evaluateWithDetails before entering the hook pipeline
  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    // The provider-readiness gate (`checkProviderStatus`) applies only where the value must actually come from the
    // provider. A transaction override or a cached evaluation resolves purely locally and MUST still succeed while the
    // provider is NotReady / Fatal / shutting down — that fallback role is the whole point of overrides (spec:
    // deterministic tests without a live provider, and forcing known-safe values while a provider is down). So the gate
    // is pushed down onto the provider-hitting paths (`evaluateFromClient` here, `evaluateAndCache` in
    // `evaluateWithTransaction`) rather than run up front.
    state.transactionRef.get.flatMap {
      case Some(ts) => evaluateWithTransaction(key, default, context, ts, timeout)
      case None     => checkProviderStatus *> evaluateFromClient(key, default, context, timeout)
    }

  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    txState: TransactionState,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val flagType = FlagType[A]
    // First check for explicit overrides
    txState.getOverride(key) match {
      case Some(overrideValue) =>
        decodeOverride(flagType, overrideValue) match {
          case Right(decoded) =>
            val resolution = FlagResolution.cached(key, decoded)
            FlagEvaluation.overridden(key, decoded).flatMap { eval =>
              txState.record(eval).as(resolution)
            }
          case Left(reason) =>
            val actual = if (overrideValue == null) "null" else overrideValue.getClass.getSimpleName
            ZIO.fail(FeatureFlagError.OverrideTypeMismatch(key, flagType.typeName, s"$actual ($reason)"))
        }

      case None =>
        // Check for cached evaluation from previous call in this transaction. A usable cache hit resolves locally; only
        // the paths that must reach the provider (`evaluateAndCache`) run behind the readiness gate.
        txState.getCachedEvaluation(key).flatMap {
          case Some(cached) =>
            // The cache holds the WIRE value, so this is the same decode the provider path runs — a custom type's
            // domain value would not decode (decode is wire → domain), and that is what used to make the cache miss on
            // every read for such types. A `Left` now means a genuine cross-type read of the same key (cached as
            // Boolean, re-read as String): not an error, just not servable from the cache.
            flagType.decode(cached.wire) match {
              case Right(decoded) =>
                ZIO.succeed(FlagResolution.cached(key, decoded))
              case Left(reason) =>
                ZIO.logDebug(
                  s"Transaction cache for '$key' does not decode as ${flagType.typeName} ($reason); evaluating from the provider"
                ) *> checkProviderStatus *> evaluateAndCache(key, default, context, txState, timeout)
            }
          case None =>
            checkProviderStatus *> evaluateAndCache(key, default, context, txState, timeout)
        }
    }
  }

  /** An override may be given as the WIRE value (`"dual_write"`) or the DOMAIN value (`Phase.DualWrite`). Wire is tried
    * first — for every built-in the two coincide, so that is the whole story there. Otherwise the value is treated as a
    * domain value and round-tripped through `encode`; `decode` stays the sole arbiter, so whatever is accepted is a
    * real `A` that `decode` produced. A value of the wrong type typically throws inside `encode` (the erased cast is
    * caught here) or encodes to something `decode` rejects; a total `encode` that maps a foreign value to a decodable
    * wire form would accept it. Both reasons are reported — the wire one first, since it names the type the caller most
    * likely meant, then why the value did not pass as a domain value either.
    */
  private def decodeOverride[A](flagType: FlagType[A], raw: Any): Either[String, A] =
    flagType.decode(raw).left.flatMap { wireReason =>
      scala.util
        .Try(flagType.encode(raw.asInstanceOf[A]))
        .toEither
        .left
        .map(t => s"encode threw ${t.getClass.getSimpleName}: ${t.getMessage}")
        .flatMap(flagType.decode)
        .left
        .map { domainReason =>
          if (domainReason == wireReason) wireReason else s"$wireReason; not a domain value either: $domainReason"
        }
    }

  private def evaluateAndCache[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    txState: TransactionState,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      resolution <- evaluateFromClient(key, default, context, timeout)
      eval       <- zio.openfeature.FlagEvaluation.evaluated(key, resolution)
      _          <- txState.record(eval)
    } yield resolution

  private def evaluateFromClient[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val flagType  = FlagType[A]
    val ofContext = toJavaContext(context)

    // The only place a provider call can be abandoned, so the abandoned-call cap lives here and nowhere else:
    // the untimed path cannot abandon a call and never touches the counter.
    def withTimeout[B](effect: Task[B]): Task[B] =
      timeout match {
        case Some(d) =>
          ZIO.suspendSucceed {
            val abandoned = abandonedCalls.get()
            // Check, then start: under a race a few calls may slip past the cap, which is fine — it bounds the thread
            // leak, it is not an exact quota.
            if (abandoned >= maxAbandonedCalls) rejectAbandoned(abandoned)
            else {
              // Exactly one of the finalizer (Running -> Done) and the abandoning side (Running -> Abandoned) wins the
              // CAS, so the counter is incremented and decremented once per abandoned call and never drifts.
              val call    = new java.util.concurrent.atomic.AtomicInteger(FeatureFlagsLive.CallRunning)
              val abandon = ZIO.succeed {
                if (call.compareAndSet(FeatureFlagsLive.CallRunning, FeatureFlagsLive.CallAbandoned))
                  abandonedCalls.incrementAndGet()
              }.unit
              effect
                // Before `.disconnect`, so the finalizer runs on the disconnected fiber when the provider finally
                // returns, however long after the caller moved on.
                .ensuring(ZIO.succeed {
                  if (!call.compareAndSet(FeatureFlagsLive.CallRunning, FeatureFlagsLive.CallDone))
                    abandonedCalls.decrementAndGet()
                })
                .disconnect
                .timeoutFail(new java.util.concurrent.TimeoutException(s"Evaluation of '$key' timed out after $d"))(d)
                .tapError {
                  case _: java.util.concurrent.TimeoutException => abandon
                  case _                                        => ZIO.unit
                }
                // An interrupted caller walks away from the disconnected call just as a timeout does.
                .onInterrupt(abandon)
            }
          }
        case None => effect
      }

    def rejectAbandoned[B](abandoned: Int): Task[B] = {
      val error = new java.util.concurrent.RejectedExecutionException(
        s"Evaluation of '$key' rejected: $abandoned abandoned provider calls are still running " +
          s"(cap $maxAbandonedCalls); the provider appears hung"
      )
      fallbackLogLimiter.logRejection(error.getMessage) *> ZIO.fail(error)
    }

    val evaluation: IO[FeatureFlagError, FlagResolution[A]] =
      ClientEvaluator.evaluateStandard[A](flagType, client, key, default, ofContext) match {
        // The instance declares a scalar wire type its `encode` does not produce — a contract violation, surfaced as
        // a typed error rather than an opaque ClassCastException from inside the SDK bridge.
        case Some(Left(message)) =>
          ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, message))

        case Some(Right(erased)) =>
          withTimeout(erased.task)
            .mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              erased.extract(details) match {
                case Right(value) =>
                  toFlagResolution(key, details).map(r => r.copy(value = value))
                case Left(message) =>
                  // The provider answered, but its value is not a valid `A` — e.g. an unknown enum variant on a
                  // string-backed type. A typed failure, not a silent fall back to the default: the total tier
                  // (`*OrDefault`) still absorbs it upstream for callers who opted into that.
                  ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, message))
              }
            }

        // Reference identity to the built-in instance, not a name test: this branch casts the default to
        // `Map[String, Any]` and serves the raw map as `A` past `decode`, which is only sound for that instance. Any
        // other object-backed type, even one named "Object" (`FlagType.from("Object", ...)`), falls through to the
        // decode branch below so its own `decode` runs.
        case None if flagType eq FlagType.objectFlagType =>
          withTimeout(
            ZIO.attemptBlocking {
              // Through `anyToValue` so a `Map` flag encodes its members exactly as a derived product does. Building
              // the structure with `anyToObject` instead left this path stringifying `Option` members (`"Some(1)"`)
              // while the custom-type path sent the unwrapped value.
              client.getObjectDetails(key, anyToValue(default.asInstanceOf[Map[String, Any]]), ofContext)
            }
          ).mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              val value = valueToMap(details.getValue)
              toFlagMetadata(details.getFlagMetadata).map { metadata =>
                FlagResolution(
                  value = value.asInstanceOf[A],
                  variant = Option(details.getVariant),
                  reason = Evaluator.toResolutionReason(details.getReason),
                  metadata = metadata,
                  flagKey = key,
                  errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava),
                  errorMessage = Option(details.getErrorMessage)
                )
              }
            }

        case None =>
          // Custom type: resolve through the object surface and decode. The caller's default is ENCODED and sent
          // down — it used to be an empty `Value()`, which meant a provider could never serve the caller's default
          // for a custom type the way it does for the scalar ones.
          withTimeout(
            ZIO.attemptBlocking {
              client.getObjectDetails(key, anyToValue(flagType.encode(default)), ofContext)
            }
          ).mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              val providerError = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava)
              val decoded       = valueToAny(details.getValue).map(flagType.decode)

              decoded match {
                case Some(Right(value)) =>
                  toFlagMetadata(details.getFlagMetadata).map { metadata =>
                    FlagResolution(
                      value = value,
                      variant = Option(details.getVariant),
                      reason = Evaluator.toResolutionReason(details.getReason),
                      metadata = metadata,
                      flagKey = key,
                      errorCode = providerError,
                      errorMessage = Option(details.getErrorMessage)
                    )
                  }

                // The provider itself reported a problem — FLAG_NOT_FOUND, PARSE_ERROR, ... Serve the caller's
                // default and surface the provider's own code, which is exactly what the scalar path does.
                // Relabelling this a `TypeMismatch` (the previous behaviour) hid the real cause and made every
                // miss on a custom type look like a codec bug.
                case _ if providerError.isDefined =>
                  toFlagMetadata(details.getFlagMetadata).map { metadata =>
                    FlagResolution(
                      value = default,
                      variant = Option(details.getVariant),
                      reason = ResolutionReason.Error,
                      metadata = metadata,
                      flagKey = key,
                      errorCode = providerError,
                      errorMessage = Option(details.getErrorMessage)
                    )
                  }

                // The provider answered successfully but the payload is not a valid `A` — a genuine codec failure,
                // which must stay loud rather than being absorbed into the default.
                case Some(Left(message)) =>
                  ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, message))

                // Nothing extractable, and the provider reported no error. Let the instance say what an absent
                // value means before failing: `FlagType[Option[A]]` decodes null to `None`, and an `Option` over an
                // object-backed type (`Option[Rollout]`) routes down this very path, so failing outright here would make
                // a legitimately empty optional flag unresolvable.
                case None =>
                  flagType.decode(null) match {
                    case Right(empty) =>
                      toFlagMetadata(details.getFlagMetadata).map { metadata =>
                        FlagResolution(
                          value = empty,
                          variant = Option(details.getVariant),
                          reason = Evaluator.toResolutionReason(details.getReason),
                          metadata = metadata,
                          flagKey = key,
                          errorCode = providerError,
                          errorMessage = Option(details.getErrorMessage)
                        )
                      }
                    case Left(message) =>
                      ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, message))
                  }
              }
            }
      }

    // Check resolution error codes for provider-level failures (handles TOCTOU race
    // where checkProviderStatus passes but the Java SDK's internal state is stale)
    evaluation.flatMap { resolution =>
      resolution.errorCode match {
        case Some(ErrorCode.ProviderNotReady) =>
          ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
        case Some(ErrorCode.ProviderFatal) =>
          ZIO.fail(FeatureFlagError.ProviderFatal)
        case _ =>
          ZIO.succeed(resolution)
      }
    }
  }

  private def toFlagResolution[A](key: String, details: FlagEvaluationDetails[A]): UIO[FlagResolution[A]] =
    toFlagMetadata(details.getFlagMetadata).map { metadata =>
      FlagResolution(
        value = details.getValue,
        variant = Option(details.getVariant),
        reason = Evaluator.toResolutionReason(details.getReason),
        metadata = metadata,
        flagKey = key,
        errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava),
        errorMessage = Option(details.getErrorMessage)
      )
    }

  private def toFlagMetadata(metadata: dev.openfeature.sdk.ImmutableMetadata): UIO[FlagMetadata] =
    if (metadata == null || metadata.isEmpty) ZIO.succeed(FlagMetadata.empty)
    else
      ZIO
        .attempt(convertImmutableMetadata(metadata))
        .catchAll(e => ZIO.logWarning(s"Failed to parse flag metadata: ${e.getMessage}").as(FlagMetadata.empty))

  // The Scala <-> SDK `Value` conversions live in `internal.ValueBridge` so that `testkit.FlagTypeLaws` can check a
  // `FlagType` against the bridge this code actually crosses, rather than against a copy that could drift from it.
  // Kept as local aliases so call sites read unchanged.
  private def anyToObject(value: Any): Object                                = ValueBridge.anyToObject(value)
  private def anyToValue(value: Any): dev.openfeature.sdk.Value              = ValueBridge.anyToValue(value)
  private def valueToMap(value: dev.openfeature.sdk.Value): Map[String, Any] = ValueBridge.valueToMap(value)
  private def valueToAny(value: dev.openfeature.sdk.Value): Option[Any]      = ValueBridge.valueToAny(value)

  private[openfeature] def evaluateWithDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    evaluateResolution(key, default, ctx, options).flatMap { resolution =>
      // The typed tier fails on a provider-reported error CODE, not only on a thrown one. Most providers report
      // FLAG_NOT_FOUND / TYPE_MISMATCH / ... as a code on the resolution and never throw, so without this the tier a
      // caller reaches for precisely because a default would be wrong handed the default back with no signal — a
      // fail-closed gate written with `value` was silently fail-open. Promoted HERE, after the hook pipeline, so hooks
      // keep observing exactly what they did (the `error` stage via the pipeline's own errorCode branch, and a
      // `finallyAfter` with the details — which is also what the Java SDK hands `finally` on error), and so a
      // transaction still records the evaluation for audit.
      resolution.errorCode match {
        case Some(code) =>
          ZIO.fail(errorFromResolution(key, code, resolution.errorMessage, expected = FlagType[A].typeName))
        case None => ZIO.succeed(resolution)
      }
    }

  /** The evaluation before an error code is promoted to a failure: a provider's error-coded answer comes back as a
    * resolution, with the variant, flag metadata and message the provider gave. The total tier serves from this.
    */
  private[openfeature] def evaluateResolution[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    // Resolve the per-call selection against the instance's global timeout. `After` and `Disabled` override the global;
    // `Default` defers to it. The result is `Option[Duration]` (None => skip the timeout scaffolding entirely).
    val timeout = options.timeout match {
      case EvaluationTimeout.After(d) => Some(d)
      case EvaluationTimeout.Disabled => None
      case EvaluationTimeout.Default  => evaluationTimeout
    }
    effectiveContext(ctx)
      .flatMap { effectCtx =>
        hookPipeline.runWithHooks(
          key,
          default,
          effectCtx,
          c => evaluateFlag(key, default, c, timeout),
          options.hooks,
          options.hookHints
        )
      }
  }

  private def providerStatus: UIO[ProviderStatus] = state.statusRef.get
}

private[openfeature] object Evaluator {

  private[openfeature] def toResolutionReason(reason: String): ResolutionReason =
    Option(reason) match {
      // `Unknown` is reserved for a genuinely absent reason. A non-null but unrecognized reason is a provider-specific
      // one and must be passed through verbatim (spec 1.4.7), not collapsed — otherwise Optimizely/flagd custom reasons
      // are lost to hooks, analytics, and debugging.
      case None    => ResolutionReason.Unknown
      case Some(r) =>
        r.toUpperCase(java.util.Locale.ROOT) match {
          case "STATIC"          => ResolutionReason.Static
          case "DEFAULT"         => ResolutionReason.Default
          case "TARGETING_MATCH" => ResolutionReason.TargetingMatch
          case "SPLIT"           => ResolutionReason.Split
          case "CACHED"          => ResolutionReason.Cached
          case "DISABLED"        => ResolutionReason.Disabled
          case "STALE"           => ResolutionReason.Stale
          case "ERROR"           => ResolutionReason.Error
          case _                 => ResolutionReason.Other(r)
        }
    }

  /** Reconstruct a typed error from a resolution that carries an error code — a provider-reported failure
    * (FLAG_NOT_FOUND, TYPE_MISMATCH, ...) returned as a default value rather than thrown. Used both to hand the `error`
    * hook stage something typed and to fail the typed tier itself. `expected` names the flag's type for `TypeMismatch`;
    * the provider's message becomes `actual`, since the provider is the one that knows what it found.
    * `ProviderNotReady`/`ProviderFatal` codes are normally caught upstream in `evaluateFromClient`; the arms are kept
    * so the mapping is total.
    */
  private[openfeature] def errorFromResolution(
    key: String,
    code: ErrorCode,
    message: Option[String],
    expected: String
  ): FeatureFlagError =
    code match {
      case ErrorCode.FlagNotFound => FeatureFlagError.FlagNotFound(key)
      case ErrorCode.TypeMismatch => FeatureFlagError.TypeMismatch(key, expected, message.getOrElse("unknown"))
      case ErrorCode.ParseError   =>
        FeatureFlagError.ParseError(key, new ProviderReportedError(message.getOrElse("parse error")))
      case ErrorCode.TargetingKeyMissing => FeatureFlagError.TargetingKeyMissing(key)
      case ErrorCode.InvalidContext      => FeatureFlagError.InvalidContext(message.getOrElse("invalid context"))
      case ErrorCode.ProviderNotReady    => FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)
      case ErrorCode.ProviderFatal       => FeatureFlagError.ProviderFatal
      // The spec has no auth error code, so a provider that reports 401/403 as an evaluation result (the OFREP
      // contrib) can only say so in the message.
      case ErrorCode.General =>
        message match {
          case Some(m) if FeatureFlagError.looksLikeAuthFailure(m) =>
            FeatureFlagError.unauthorized(m, new ProviderReportedError(m))
          case _ =>
            FeatureFlagError.ProviderError(new ProviderReportedError(message.getOrElse("general error")))
        }
    }

  private[openfeature] def convertImmutableMetadata(javaMeta: dev.openfeature.sdk.ImmutableMetadata): FlagMetadata = {
    val javaMap = javaMeta.asUnmodifiableMap()
    if (javaMap == null || javaMap.isEmpty) FlagMetadata.empty
    else {
      val entries = javaMap.asScala.collect {
        case (k, v: java.lang.Boolean) => k -> MetadataValue.BooleanValue(v.booleanValue())
        case (k, v: java.lang.Integer) => k -> MetadataValue.IntValue(v.intValue())
        case (k, v: java.lang.Long)    => k -> MetadataValue.LongValue(v.longValue())
        case (k, v: java.lang.Float)   => k -> MetadataValue.FloatValue(v.floatValue())
        case (k, v: java.lang.Double)  => k -> MetadataValue.DoubleValue(v.doubleValue())
        case (k, v: String)            => k -> MetadataValue.StringValue(v)
        case (k, v) if v != null       => k -> MetadataValue.StringValue(v.toString)
      }.toMap
      FlagMetadata(entries)
    }
  }
}
