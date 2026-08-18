package zio.openfeature.conformance.bdd.library

import zio._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}

/** Builds the `FeatureFlags` instances the library suites evaluate against.
  *
  * Every instance gets its own `OpenFeatureAPI` and its own domain, so scenarios never see each other's provider
  * registrations even when the runner widens `scenarioParallelism`.
  */
object LibraryHarness {

  /** Pins the instance to a private API + unique domain. */
  private def isolated(config: FeatureFlagsConfig): FeatureFlagsConfig =
    config.withDomain(s"lib-${java.util.UUID.randomUUID()}")

  def build(
    provider: OFFeatureProvider,
    config: FeatureFlagsConfig = FeatureFlagsConfig()
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(provider, isolated(config), statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
      .build
      .map(_.get[FeatureFlags])

  /** Parses the `FallbackLogging` policy a feature file names.
    *
    * `Throttled(1.hour)` rather than the 60 s default: a scenario asserting "one line per key" must not depend on how
    * long the suite takes to get there, and there is no `TestClock` under the zio-bdd runner.
    */
  def fallbackPolicy(name: String): FallbackLogging = name match {
    case "off"          => FallbackLogging.Off
    case "always"       => FallbackLogging.Always
    case "throttled"    => FallbackLogging.Throttled(1.hour)
    case "unthrottled"  => FallbackLogging.Throttled(Duration.Zero)
    case other          => throw new IllegalArgumentException(s"unknown fallback logging policy: $other")
  }

  def flagValueType(name: String): FlagValueType = name match {
    case "Boolean" => FlagValueType.Boolean
    case "String"  => FlagValueType.String
    case "Int"     => FlagValueType.Int
    case "Long"    => FlagValueType.Long
    case "Double"  => FlagValueType.Double
    case "Object"  => FlagValueType.Object
    case other     => throw new IllegalArgumentException(s"unknown flag value type: $other")
  }

  def errorCodeName(c: ErrorCode): String = c match {
    case ErrorCode.ProviderNotReady    => "PROVIDER_NOT_READY"
    case ErrorCode.ProviderFatal       => "PROVIDER_FATAL"
    case ErrorCode.FlagNotFound        => "FLAG_NOT_FOUND"
    case ErrorCode.ParseError          => "PARSE_ERROR"
    case ErrorCode.TypeMismatch        => "TYPE_MISMATCH"
    case ErrorCode.TargetingKeyMissing => "TARGETING_KEY_MISSING"
    case ErrorCode.InvalidContext      => "INVALID_CONTEXT"
    case ErrorCode.General             => "GENERAL"
  }

  def reasonName(r: ResolutionReason): String = r match {
    case ResolutionReason.Static         => "STATIC"
    case ResolutionReason.Default        => "DEFAULT"
    case ResolutionReason.TargetingMatch => "TARGETING_MATCH"
    case ResolutionReason.Split          => "SPLIT"
    case ResolutionReason.Cached         => "CACHED"
    case ResolutionReason.Disabled       => "DISABLED"
    case ResolutionReason.Unknown        => "UNKNOWN"
    case ResolutionReason.Stale          => "STALE"
    case ResolutionReason.Error          => "ERROR"
    case ResolutionReason.Other(v)       => v
  }

  /** The flag definition a feature file names, e.g. `Plan`. */
  def flagDef(name: String): FlagDef[?] =
    Flags.byName.getOrElse(name, throw new IllegalArgumentException(s"unknown flag definition: $name"))

  /** Renders an evaluated value the way the feature files spell it. `String.valueOf` on everything else keeps a derived
    * product (`Release(beta,25,None)`) readable without a bespoke assertion per type.
    */
  def render(value: Any): String = value match {
    case s: String => s
    case other     => String.valueOf(other)
  }
}
