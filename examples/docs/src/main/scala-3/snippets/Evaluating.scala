package snippets

import zio.*
import zio.openfeature.*

/** The code on the Evaluating flags page. */
object Evaluating:

  // --8<-- [start:typed]
  val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.boolean("dark-mode", default = false)
  val color: ZIO[FeatureFlags, FeatureFlagError, String]    = FeatureFlags.string("button-color", default = "blue")
  val limit: ZIO[FeatureFlags, FeatureFlagError, Int]       = FeatureFlags.int("max-items", default = 100)
  val quota: ZIO[FeatureFlags, FeatureFlagError, Long]      = FeatureFlags.long("max-bytes", default = 1_000_000L)
  val rate: ZIO[FeatureFlags, FeatureFlagError, Double]     = FeatureFlags.double("sample-rate", default = 0.1)
  val config: ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    FeatureFlags.obj("checkout-config", default = Map("timeout" -> 30))

  // Any type with a FlagType instance, including your own
  val ratio: ZIO[FeatureFlags, FeatureFlagError, Float] = FeatureFlags.value[Float]("ratio", 0.5f)
  // --8<-- [end:typed]

  // --8<-- [start:with-context]
  val user = EvaluationContext("user-42").withAttribute("plan", "pro")

  val forUser: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("dark-mode", default = false, user)
  // --8<-- [end:with-context]

  // --8<-- [start:details]
  val details: ZIO[FeatureFlags, FeatureFlagError, String] =
    FeatureFlags.booleanDetails("dark-mode", default = false).map { resolution =>
      s"value=${resolution.value} reason=${resolution.reason} variant=${resolution.variant.getOrElse("-")}"
    }
  // --8<-- [end:details]

  // --8<-- [start:metadata]
  val owner: ZIO[FeatureFlags, FeatureFlagError, Option[String]] =
    FeatureFlags.booleanDetails("dark-mode", default = false).map(_.metadata.getString("owner"))
  // --8<-- [end:metadata]

  final case class Misconfigured(message: String)
  final case class FlagsUnavailable(error: FeatureFlagError)

  // --8<-- [start:fail-closed]
  // Refuse to take payments unless the flag can actually be read and says yes
  val paymentsGate: ZIO[FeatureFlags, Misconfigured | FlagsUnavailable, Boolean] =
    FeatureFlags.boolean("payments-enabled", default = false).mapError {
      case FeatureFlagError.FlagNotFound(key) => Misconfigured(s"$key is not defined")
      case other                              => FlagsUnavailable(other)
    }
  // --8<-- [end:fail-closed]

  // --8<-- [start:total]
  val lenient: ZIO[FeatureFlags, Nothing, Boolean] =
    FeatureFlags.booleanOrDefault("dark-mode", default = false)

  // The full resolution, still never failing: it says why a default was served
  val explained: ZIO[FeatureFlags, Nothing, String] =
    FeatureFlags.resolveOrDefault[Boolean]("dark-mode", default = false).map { resolution =>
      resolution.errorCode match
        case Some(code) => s"served the default because of $code: ${resolution.errorMessage.getOrElse("")}"
        case None       => s"resolved to ${resolution.value}"
    }
  // --8<-- [end:total]

  // --8<-- [start:recover]
  val recovered: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("dark-mode", default = false).catchSome {
      case FeatureFlagError.FlagNotFound(_) => ZIO.succeed(false) // not defined yet: treat as off
      case _: FeatureFlagError.Unreachable  => ZIO.succeed(false) // network blip: serve the default
      case _: FeatureFlagError.Unauthorized => // wrong SDK key: be loud about it
        ZIO.logError("flag provider rejected our credentials").as(false)
    }
  // --8<-- [end:recover]

  // --8<-- [start:options]
  val options: EvaluationOptions =
    EvaluationOptions.empty
      .withHook(FeatureHook.logging()) // a hook for this call only (withHooks takes a list)
      .withHint("request-id", "r-123") // read-only data for the hooks
      .withTimeout(200.millis)         // or .withoutTimeout

  val bounded: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    FeatureFlags.booleanDetails("dark-mode", default = false, EvaluationContext.empty, options)

  val boundedValue: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("dark-mode", false, EvaluationContext.empty, options)
  // --8<-- [end:options]

  // --8<-- [start:instance-config]
  val instanceConfig: FeatureFlagsConfig =
    FeatureFlagsConfig()
      .withEvaluationTimeout(500.millis) // every evaluation; the default is 1 second
      // .withoutEvaluationTimeout                               // or no timeout at all
      .withMaxAbandonedCalls(128)                                // cap on timed-out calls still running; default 256
      .withFallbackLogging(FallbackLogging.Throttled(5.minutes)) // or FallbackLogging.Off / Always
  // --8<-- [end:instance-config]
