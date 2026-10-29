package zio.openfeature.conformance.bdd.tck

import dev.openfeature.sdk.FeatureProvider
import zio.*
import zio.bdd.core.Suite
import zio.openfeature.extras.{
  CachingProvider,
  CircuitBreakerProvider,
  DeferredProvider,
  EnvVarProvider,
  HoconProvider
}

/** The providers the provider TCK runs against in CI, each seeded with the canonical flag set.
  *
  * Capabilities each one withholds, through its suite's `excludeTags`, and why:
  *   - every one: `events`, `lifecycle`, `stale`, `configuration-change`, `unavailable`, `reinitialization` (no backend
  *     to reach, lose or change: they are local sources), `targeting` and `disabled-flags` (no rules or flag states in
  *     the source), `large-integers` (the Java SDK has no 64-bit getter), `caching` (not declarable);
  *   - `variants`: neither source names variants;
  *   - `string-typing` and `fully-typed-values`: a scalar asked for as a string comes back as its text, a question the
  *     specification leaves open (open-feature/spec#433, #430);
  *   - `object` (environment variables only): a variable holds text, not a structure.
  *
  * Everything else is declared, and what a declared or mandatory scenario still fails is a [[KnownFailure]].
  */
object TckBackends {

  // A scalar requested as a string comes back as its text, so this scenario of `standard-reasons` cannot pass.
  private val stringTyping: List[KnownFailure] =
    List(Check.Reason -> "STATIC", Check.ErrorCode -> "").map { case (check, observed) =>
      KnownFailure(
        "string",
        "boolean-flag",
        check,
        observed,
        "a scalar requested as a string resolves as its text (open-feature/spec#433), not as a type mismatch"
      )
    }

  private val hoconFailures = stringTyping

  private def hoconProvider: FeatureProvider = HoconProvider.fromConfig(CanonicalFlags.asConfig)

  val hocon: TckBackend = TckBackend("HoconProvider", ZIO.succeed(hoconProvider), hoconFailures)

  val caching: TckBackend = TckBackend(
    "CachingProvider over HoconProvider",
    CachingProvider.make(hoconProvider).orDieWith(e => new IllegalArgumentException(e.message)),
    hoconFailures
  )

  val circuitBreaker: TckBackend = TckBackend(
    "CircuitBreakerProvider over HoconProvider",
    CircuitBreakerProvider.make(hoconProvider).orDieWith(e => new IllegalArgumentException(e.message)),
    hoconFailures
  )

  val deferred: TckBackend =
    TckBackend("DeferredProvider over HoconProvider", ZIO.succeed(DeferredProvider("hocon")(() => hoconProvider)), hoconFailures)

  // Every value is text, so one that does not parse as the requested type is a parse error, not a type mismatch.
  private def envParse(flagType: String, flagKey: String) =
    KnownFailure(
      flagType,
      flagKey,
      Check.ErrorCode,
      "PARSE_ERROR",
      "environment variables are untyped text: a value that does not parse as the requested type is PARSE_ERROR"
    )

  val envVar: TckBackend = TckBackend(
    "EnvVarProvider",
    ZIO.succeed {
      val byVariable = CanonicalFlags.asStrings.map { case (k, v) => EnvVarProvider.defaultKeyTransform(k) -> v }
      EnvVarProvider.withLookup(name => byVariable.get(name.stripPrefix("FF_")))
    },
    List(
      envParse("boolean", "string-flag"),
      envParse("integer", "string-flag"),
      envParse("float", "string-flag"),
      envParse("boolean", "wrong-flag"),
      envParse("integer", "boolean-flag"),
      envParse("float", "boolean-flag"),
      envParse("boolean", "integer-flag"),
      envParse("boolean", "float-flag"),
      envParse("integer", "float-flag")
    ) ++ stringTyping
  )
}

@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/provider-tck"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  excludeTags = Array(
    "events", "lifecycle", "stale", "configuration-change", "unavailable", "reinitialization", "targeting",
    "disabled-flags", "large-integers", "caching", "variants", "string-typing", "fully-typed-values"
  ),
  logLevel = "warning"
)
object HoconProviderTckSpec extends TckSteps(TckBackends.hocon)

@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/provider-tck"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  excludeTags = Array(
    "events", "lifecycle", "stale", "configuration-change", "unavailable", "reinitialization", "targeting",
    "disabled-flags", "large-integers", "caching", "variants", "string-typing", "fully-typed-values"
  ),
  logLevel = "warning"
)
object CachingProviderTckSpec extends TckSteps(TckBackends.caching)

@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/provider-tck"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  excludeTags = Array(
    "events", "lifecycle", "stale", "configuration-change", "unavailable", "reinitialization", "targeting",
    "disabled-flags", "large-integers", "caching", "variants", "string-typing", "fully-typed-values"
  ),
  logLevel = "warning"
)
object CircuitBreakerProviderTckSpec extends TckSteps(TckBackends.circuitBreaker)

@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/provider-tck"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  excludeTags = Array(
    "events", "lifecycle", "stale", "configuration-change", "unavailable", "reinitialization", "targeting",
    "disabled-flags", "large-integers", "caching", "variants", "string-typing", "fully-typed-values"
  ),
  logLevel = "warning"
)
object DeferredProviderTckSpec extends TckSteps(TckBackends.deferred)

@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/provider-tck"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  excludeTags = Array(
    "events", "lifecycle", "stale", "configuration-change", "unavailable", "reinitialization", "targeting",
    "disabled-flags", "large-integers", "caching", "variants", "string-typing", "fully-typed-values", "object"
  ),
  logLevel = "warning"
)
object EnvVarProviderTckSpec extends TckSteps(TckBackends.envVar)
