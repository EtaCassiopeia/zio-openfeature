package zio.openfeature

import zio._
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** The settings of [[FeatureFlags.fromAcquireAsync]] that mean nothing on any other path: how the real provider is
  * acquired, checked and swapped in. Everything an instance has on every path (timeouts, context source, hooks,
  * fallback logging) comes from its [[FeatureFlagsConfig]].
  *
  * Built from [[AcquireConfig.default]] with the `with*` methods, each returning a copy. It holds functions, so it has
  * no structural equality.
  *
  * @param compose
  *   how to combine (real, freshFallback) into the swapped-in provider; [[FeatureFlags.defaultAcquireCompose]] by
  *   default
  * @param constructionRetry
  *   retry policy for the `acquire` + `verify` attempt
  * @param constructionTimeout
  *   per-attempt bound on `acquire` + `verify`
  * @param onConstructionError
  *   run when `acquire`, `verify`, or the swap ultimately fails; the instance stays on the fallback
  * @param verify
  *   run on each acquired candidate before the swap; failure rejects the candidate and counts as a failed attempt.
  *   Accepts every candidate by default. See [[Verify]] for ready-made checks.
  * @param onSwapped
  *   run once after the switch routes to the real provider and [[AcquireState.Live]] is observable
  */
final class AcquireConfig private (
  val compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider,
  val constructionRetry: Schedule[Any, Throwable, Any],
  val constructionTimeout: Duration,
  val onConstructionError: Throwable => UIO[Unit],
  val verify: OFFeatureProvider => Task[Unit],
  val onSwapped: UIO[Unit]
) {

  private def copy(
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider = compose,
    constructionRetry: Schedule[Any, Throwable, Any] = constructionRetry,
    constructionTimeout: Duration = constructionTimeout,
    onConstructionError: Throwable => UIO[Unit] = onConstructionError,
    verify: OFFeatureProvider => Task[Unit] = verify,
    onSwapped: UIO[Unit] = onSwapped
  ): AcquireConfig =
    new AcquireConfig(compose, constructionRetry, constructionTimeout, onConstructionError, verify, onSwapped)

  def withCompose(f: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider): AcquireConfig = copy(compose = f)
  def withConstructionRetry(s: Schedule[Any, Throwable, Any]): AcquireConfig = copy(constructionRetry = s)
  def withConstructionTimeout(d: Duration): AcquireConfig                    = copy(constructionTimeout = d)
  def withOnConstructionError(f: Throwable => UIO[Unit]): AcquireConfig      = copy(onConstructionError = f)
  def withVerify(f: OFFeatureProvider => Task[Unit]): AcquireConfig          = copy(verify = f)
  def withOnSwapped(effect: UIO[Unit]): AcquireConfig                        = copy(onSwapped = effect)
}

object AcquireConfig {

  /** The defaults of [[FeatureFlags.fromAcquireAsync]]: the default compose and retry, a 30 second construction bound,
    * no callbacks and no verification.
    */
  val default: AcquireConfig = new AcquireConfig(
    FeatureFlags.defaultAcquireCompose,
    FeatureFlags.DefaultConstructionRetry,
    FeatureFlags.DefaultInitTimeout,
    _ => ZIO.unit,
    _ => ZIO.unit,
    ZIO.unit
  )
}
