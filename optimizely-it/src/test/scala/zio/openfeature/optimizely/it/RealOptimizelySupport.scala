package zio.openfeature.optimizely.it

import zio.{Runtime, Unsafe}
import zio.openfeature.optimizely.{OptimizelyFeatureProvider, OptimizelyProvider}

import java.time.Duration

/** Single source of truth for the flag-key / variable / variation constants that the IT specs assert against. Any
  * change here must be matched in the committed datafiles under `src/test/resources/datafiles/` AND in
  * `docs/it-project-setup.md` so a contributor can regenerate the fixtures from a fresh Optimizely project.
  */
object RealOptimizelySupport {

  // SDK keys — synthetic filenames served by nginx, not real Optimizely keys.

  val BasicSdkKey: String      = "it_basic"
  val V2SdkKey: String         = "it_basic_v2"
  val TargetingSdkKey: String  = "it_targeting"
  val VariationsSdkKey: String = "it_variations"
  val MalformedSdkKey: String  = "it_malformed"

  // Flag keys, variable names, and expected variation values for `it_basic.json`. Filled in by the real-datafile-spec
  // PR; declared here so the foundation can compile.

  val BoolFlagKey: String      = "it_bool_flag"
  val StringFlagKey: String    = "it_string_flag"
  val IntFlagKey: String       = "it_int_flag"
  val DoubleFlagKey: String    = "it_double_flag"
  val ObjectFlagKey: String    = "it_object_flag"
  val VariationFlagKey: String = "it_variation_flag"
  val AudienceFlagKey: String  = "it_audience_flag"
  val UnknownFlagKey: String   = "it_does_not_exist"

  val DefaultInitWait: Duration = Duration.ofSeconds(5)

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
    try { provider.initialize(new dev.openfeature.sdk.ImmutableContext()); Right(()) }
    catch { case t: Throwable => Left(t) }

  private def buildProvider(sdkKey: String, initWait: Duration): OptimizelyFeatureProvider = {
    val url    = OptimizelyItStack.datafileUrl(sdkKey)
    val effect = OptimizelyProvider.make(sdkKey, Some(url), initWait)
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())
  }
}
