package zio.openfeature.extras

import zio.openfeature.testkit.{Op, WrapperLaws}
import zio.test._

/** Every extras wrapper forwards the whole `FeatureProvider` surface to the provider it wraps, checked by calling each
  * method rather than by reflection, so a method the SDK adds as an interface default is caught on the first build
  * against that SDK. Caching a second call is a separate behaviour, covered in `CachingProviderSpec`; the circuit is
  * closed here, so `CircuitBreakerProvider` must pass every call through.
  */
object WrapperDelegationSpec extends ZIOSpecDefault {

  def spec = suite("extras wrapper delegation")(
    WrapperLaws.delegation("CachingProvider")(CachingProvider(_)),
    WrapperLaws.delegation("CircuitBreakerProvider")(CircuitBreakerProvider(_)),
    WrapperLaws.delegation("DeferredProvider")(
      inner => DeferredProvider("deferred")(() => inner),
      handledByWrapper = Map(Op.GetMetadata -> "reports the same name before and after construction")
    ),
    WrapperLaws.delegation("IntegerWideningLongProvider")(
      new IntegerWideningLongProvider(_),
      handledByWrapper = Map(
        Op.GetState     -> "deprecated since SDK 1.22, so the interface default applies",
        Op.EvaluateLong -> "resolves a Long whose default fits an Int through getIntegerEvaluation"
      )
    )
  )
}
