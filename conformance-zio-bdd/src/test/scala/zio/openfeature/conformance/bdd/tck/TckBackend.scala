package zio.openfeature.conformance.bdd.tck

import scala.jdk.CollectionConverters.*

import com.typesafe.config.{Config, ConfigFactory, ConfigValue, ConfigValueType}
import dev.openfeature.sdk.FeatureProvider
import zio.*

/** One provider under the provider TCK: how to build it seeded with the canonical flag set, and the reviewed scenarios
  * it is known to fail.
  *
  * Capabilities it does not support are withheld through its suite's `excludeTags`, which the TCK reports as skipped,
  * not passed. A known failure is for a scenario it cannot skip: a mandatory one, or one under a capability it does
  * declare.
  */
final case class TckBackend(name: String, stable: UIO[FeatureProvider], knownFailures: List[KnownFailure]) {

  def knownFailure(flagType: String, flagKey: String, check: Check): Option[KnownFailure] =
    knownFailures.find(k => k.flagType == flagType && k.flagKey == flagKey && k.check == check)
}

/** The check of a scenario an assertion step makes. */
enum Check {
  case Value, ErrorCode, ErrorMessage, Reason, Variant
}

/** One assertion a provider is known to fail on one flag, read as `flagType` (lower case) for `flagKey`, with the outcome
  * it `observed` instead (an error code, a reason, a value) and a `note` on why. While the defect stands the assertion
  * must fail with exactly that outcome; passing, or failing another way, fails the suite, so the list cannot go stale
  * or hide a different defect unnoticed.
  */
final case class KnownFailure(flagType: String, flagKey: String, check: Check, observed: String, note: String)

/** The TCK's canonical flag set, `canonical-flags.json`, read with Typesafe Config: it keeps `10` an integer and `10.0`
  * a float, which the suite's coercion scenarios depend on.
  */
object CanonicalFlags {

  private val path = "conformance-zio-bdd/src/test/resources/features/provider-tck/canonical-flags.json"

  private lazy val definitions: Config =
    ConfigFactory.parseFile(new java.io.File(path)).getConfig("flags")

  /** Every flag a provider without targeting or disabled flags can serve: enabled, untargeted, at its default variant.
    * A disabled flag or the targeted one is left out, so it reads as unknown rather than as a wrong value.
    */
  lazy val served: List[(String, ConfigValue)] =
    definitions.root.keySet.asScala.toList.sorted.flatMap { key =>
      val flag     = definitions.getConfig(s""""$key"""")
      val enabled  = flag.getString("state") == "ENABLED"
      val targeted = flag.hasPath("targeting")
      if (!enabled || targeted) None
      else Some(key -> flag.getConfig("variants").root.get(flag.getString("defaultVariant")))
    }

  /** The served flags as a config whose top-level keys are the flag keys. */
  def asConfig: Config =
    served.foldLeft(ConfigFactory.empty()) { case (config, (key, value)) => config.withValue(s""""$key"""", value) }

  /** The served scalar flags rendered as the strings an environment variable would hold. */
  def asStrings: Map[String, String] =
    served.collect {
      case (key, value) if value.valueType != ConfigValueType.OBJECT && value.valueType != ConfigValueType.LIST =>
        key -> String.valueOf(value.unwrapped)
    }.toMap
}
