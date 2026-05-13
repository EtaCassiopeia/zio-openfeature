package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ImmutableContext, MutableContext}
import zio.openfeature.optimizely.{OptimizelyFeatureProvider, OptimizelyProvider}
import zio.{Runtime, Unsafe}

import java.time.Duration

/** Single source of truth for the flag-key / variable / variation constants that the IT specs assert against. Any
  * change here must be matched in the committed datafiles under `src/test/resources/datafiles/` AND in
  * `docs/it-project-setup.md` so the fixtures stay reproducible from a real Optimizely free-tier project.
  */
object RealOptimizelySupport {

  // SDK keys — filenames served by nginx, also passed to the Optimizely Java SDK as "the" SDK key. They have to
  // satisfy OptimizelyProvider's shape validation: [A-Za-z0-9_-]+, length 6..128.

  val BasicSdkKey: String      = "it_basic"
  val V2SdkKey: String         = "it_basic_v2"
  val TargetingSdkKey: String  = "it_targeting"
  val VariationsSdkKey: String = "it_variations"
  val MalformedSdkKey: String  = "it_malformed"

  // Flag keys defined by the committed datafiles.

  val BoolFlagKey: String      = "it_bool_flag"
  val StringFlagKey: String    = "it_string_flag"
  val IntFlagKey: String       = "it_int_flag"
  val DoubleFlagKey: String    = "it_double_flag"
  val ObjectFlagKey: String    = "it_object_flag"
  val VariationFlagKey: String = "it_variation_flag"
  val AudienceFlagKey: String  = "it_audience_flag"
  val UnknownFlagKey: String   = "it_does_not_exist"

  // Expected values served by `it_basic.json` and `it_variations.json` when a flag is fully rolled out.

  val BoolFlagExpectedEnabled: Boolean = true
  val BoolFlagExpectedVariant: String  = "on"
  val StringFlagExpectedValue: String  = "rolled-out"
  val IntFlagExpectedValue: Int        = 42
  val DoubleFlagExpectedValue: Double  = 3.14
  val ObjectFlagExpectedName: String   = "alice"
  val ObjectFlagExpectedLevel: Int     = 7
  val VariationFlagExpectedKey: String = "treatment_a"

  val DefaultInitWait: Duration = Duration.ofSeconds(5)

  /** Build a `MutableContext` whose targeting key is set, so Optimizely's bucketing has a userId to hash. Without one
    * the provider short-circuits with TARGETING_KEY_MISSING and the decision engine is never exercised.
    */
  def userContext(targetingKey: String): MutableContext = new MutableContext(targetingKey)

  /** Build a provider, drive it to `READY` via `initialize`, run `body`, and shutdown on every path. */
  def withReadyProvider[A](
    sdkKey: String,
    initWait: Duration = DefaultInitWait
  )(body: OptimizelyFeatureProvider => A): A =
    withProvider(sdkKey, initWait) { provider =>
      provider.initialize(new ImmutableContext())
      body(provider)
    }

  /** Synchronously build a provider against the live stack and run `body` against it, ensuring shutdown.
    *
    * Mirrors the synchronous pattern from `OptimizelyProviderIntegrationSpec.withProvider` — assertions on the provider
    * state must observe the provider while it is alive, so we don't defer into a ZIO effect that completes after the
    * `finally` shutdown.
    */
  def withProvider[A](
    sdkKey: String,
    initWait: Duration = DefaultInitWait
  )(body: OptimizelyFeatureProvider => A): A = {
    val provider = buildProvider(sdkKey, initWait)
    try body(provider)
    finally provider.shutdown()
  }

  /** Try to initialize the provider, returning the exception (if any). */
  def tryInit(provider: OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(new ImmutableContext()); Right(()) }
    catch { case t: Throwable => Left(t) }

  private def buildProvider(sdkKey: String, initWait: Duration): OptimizelyFeatureProvider = {
    val url    = OptimizelyItStack.datafileUrl(sdkKey)
    val effect = OptimizelyProvider.make(sdkKey, Some(url), initWait)
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())
  }
}
