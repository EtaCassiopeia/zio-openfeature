package zio.openfeature

import zio._
import zio.openfeature.internal.{ContextConverter, ErrorCodeConverter}
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, ProviderEvaluation, Value}

/** Ready-made `verify` checks for [[FeatureFlags.fromAcquireAsync]].
  *
  * A check is a plain `OFFeatureProvider => Task[Unit]`: it receives the freshly acquired candidate (pre-swap, never
  * the composed stack) and fails to reject it. Combine several with [[all]]: `verify =
  * Verify.all(Verify.flagExists[Boolean]("kill-switch"), Verify.flagExists[String]("banner"))`.
  */
object Verify {

  /** Run every check against the candidate, in order, stopping at the first failure. `Verify.all()` accepts every
    * candidate — the same as `fromAcquireAsync`'s default.
    */
  def all(checks: (OFFeatureProvider => Task[Unit])*): OFFeatureProvider => Task[Unit] =
    provider => ZIO.foreachDiscard(checks)(check => check(provider))

  /** The candidate answered `key` with an error code — it does not know the flag (`FlagNotFound`), or cannot serve it
    * (`TypeMismatch`, `General`, ...). Carries the code so `onConstructionError` can distinguish a missing sentinel
    * from a broken provider.
    */
  final case class VerificationFailed(key: String, errorCode: ErrorCode, message: Option[String])
      extends RuntimeException(
        s"Verification of flag '$key' failed with $errorCode" + message.fold("")(m => s": $m")
      )

  /** Fail unless the candidate answers `key` **without an error code**.
    *
    * Evaluates `key` directly on the bare provider through the SDK getter matching `FlagType[A].wireType` — the same
    * dispatch evaluation uses — with a fixed neutral default (`false`, `""`, `0`, `0L`, `0.0`, or an empty `Value` on
    * the object path), so the sentinel flag may be of any type. Only the returned `errorCode` is judged: a value with
    * any reason (including `DEFAULT`) means the provider knows the key; `FLAG_NOT_FOUND` — which the SDK returns on the
    * `ProviderEvaluation` rather than throwing — or any other code fails with [[VerificationFailed]]. A getter that
    * throws fails with that throwable.
    *
    * The candidate is probed exactly as `acquire` returned it: the SDK has not called `initialize()` on it, and only
    * `context` (default: empty) is sent — no ambient or global context. A provider that answers `PROVIDER_NOT_READY` or
    * `TARGETING_KEY_MISSING` in that state is rejected on every attempt, so initialize inside `acquire` or pass the
    * context the sentinel needs. Prefer a `Boolean`/`String` sentinel over `Long`: a third-party provider that does not
    * override `getLongEvaluation` answers `TYPE_MISMATCH` from the SDK's default (see `IntegerWideningLongProvider`).
    */
  def flagExists[A: FlagType](
    key: String,
    context: EvaluationContext = EvaluationContext.empty
  ): OFFeatureProvider => Task[Unit] = { provider =>
    val ctx = ContextConverter.toOpenFeature(context)
    // The whole read happens inside the blocking attempt so that a misbehaving candidate — a getter that throws, or
    // one that hands back `null` instead of a ProviderEvaluation — becomes a typed failure that the retry schedule and
    // `onConstructionError` see, never a defect that would skip both.
    ZIO
      .attemptBlocking {
        val evaluation = evaluateExistence(provider, FlagType[A].wireType, key, ctx)
        if (evaluation eq null) Some((ErrorCode.General, Some("provider returned a null ProviderEvaluation")))
        else
          Option(evaluation.getErrorCode).map(c => (ErrorCodeConverter.fromJava(c), Option(evaluation.getErrorMessage)))
      }
      .flatMap {
        case None                  => ZIO.unit
        case Some((code, message)) => ZIO.fail(VerificationFailed(key, code, message))
      }
  }

  private def evaluateExistence(
    provider: OFFeatureProvider,
    wireType: String,
    key: String,
    ctx: dev.openfeature.sdk.EvaluationContext
  ): ProviderEvaluation[_] =
    wireType match {
      case "Boolean" => provider.getBooleanEvaluation(key, java.lang.Boolean.FALSE, ctx)
      case "String"  => provider.getStringEvaluation(key, "", ctx)
      case "Int"     => provider.getIntegerEvaluation(key, Integer.valueOf(0), ctx)
      case "Long"    => provider.getLongEvaluation(key, java.lang.Long.valueOf(0L), ctx)
      // Float has no SDK getter of its own; evaluation routes it through the double resolver too.
      case "Float" | "Double" => provider.getDoubleEvaluation(key, java.lang.Double.valueOf(0.0), ctx)
      case _                  => provider.getObjectEvaluation(key, new Value(), ctx)
    }
}
