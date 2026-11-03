package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  ProviderEvaluation
}
import dev.openfeature.sdk.multiprovider.{FirstMatchStrategy, FirstSuccessfulStrategy, Strategy => SdkStrategy}

import java.util.function.{Function => JFunction}
import scala.jdk.CollectionConverters._

/** How [[FeatureFlags.multiProvider]] combines its providers: one of the built-in strategies, or a [[Custom]] one.
  *
  * The strategies are this library's own types, mapped onto the Java SDK's inside the library, so a change to the SDK's
  * strategy interface does not reach callers.
  */
object MultiProviderStrategy {

  /** What a chain does with its providers for one evaluation. */
  sealed trait Strategy

  /** See [[firstMatch]]. */
  case object FirstMatch extends Strategy

  /** See [[firstSuccessful]]. */
  case object FirstSuccessful extends Strategy

  /** A strategy you write: [[CustomStrategy.evaluate]] decides which of the providers to ask and what to answer. */
  final case class Custom(strategy: CustomStrategy) extends Strategy

  /** What a custom strategy decides, in this library's terms, so a change to the SDK's interface stays inside it. */
  trait CustomStrategy {

    /** Answer one evaluation.
      *
      * @param providers
      *   the chain's providers, in order
      * @param call
      *   evaluates the flag on one provider, running that provider's own hooks; the answer is that provider's, and a
      *   provider that throws makes `call` throw
      */
    def evaluate[T](
      providers: List[OFFeatureProvider],
      key: String,
      defaultValue: T,
      ctx: OFEvaluationContext,
      call: OFFeatureProvider => ProviderEvaluation[T]
    ): ProviderEvaluation[T]
  }

  /** The SDK strategy that does what `strategy` says. A new instance on every call: the SDK's own classes carry no
    * state today, and sharing none keeps that safe if one ever gains some.
    */
  private[openfeature] def toSdk(strategy: Strategy): SdkStrategy = strategy match {
    case FirstMatch      => new FirstMatchStrategy
    case FirstSuccessful => new FirstSuccessfulStrategy
    case Custom(custom)  =>
      new SdkStrategy {
        def evaluate[T](
          providers: java.util.List[OFFeatureProvider],
          key: String,
          defaultValue: T,
          ctx: OFEvaluationContext,
          call: JFunction[OFFeatureProvider, ProviderEvaluation[T]]
        ): ProviderEvaluation[T] =
          custom.evaluate(providers.asScala.toList, key, defaultValue, ctx, provider => call.apply(provider))
      }
  }

  /** Returns the result of the first provider that reports the flag as found.
    *
    * A provider signals "I do not have this flag" either by returning the `FLAG_NOT_FOUND` error code or by throwing
    * `FlagNotFoundError`, and only those make the chain move on. A result carrying `reason = DEFAULT` and no error code
    * does '''not''' — it is taken as an answer and ends the chain, which is why a provider must report `FLAG_NOT_FOUND`
    * for an absent key rather than a default-reason value. Any other evaluation error aborts the chain; use
    * [[firstSuccessful]] if you want errors to fall through as well. When every provider reports not-found, the chain
    * answers `FLAG_NOT_FOUND` and the SDK client substitutes the caller's default.
    */
  def firstMatch: Strategy = FirstMatch

  /** Returns the result of the first provider whose evaluation completes without an error. Errors fall through to the
    * next provider; default-reason results do not, since they count as success.
    *
    * Note `FLAG_NOT_FOUND` is an error here, so a provider that does not hold the key falls through as well — and if no
    * provider holds it, the chain surfaces `GENERAL` with the aggregated per-provider errors rather than a clean
    * default. That is the shape to expect from the common "primary provider plus a small override provider" pattern
    * when the primary is failing and the key is not in the override set.
    */
  def firstSuccessful: Strategy = FirstSuccessful
}
