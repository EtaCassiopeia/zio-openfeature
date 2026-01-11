package zio.openfeature.provider.optimizely

import zio.*
import zio.stream.*
import zio.openfeature.*
import com.optimizely.ab.Optimizely
import com.optimizely.ab.OptimizelyFactory
import com.optimizely.ab.OptimizelyUserContext
import com.optimizely.ab.config.HttpProjectConfigManager
import com.optimizely.ab.optimizelydecision.OptimizelyDecision
import scala.jdk.CollectionConverters.*
import java.util.concurrent.TimeUnit

/** OpenFeature provider for Optimizely Feature Experimentation.
  *
  * Supports:
  *   - Boolean, String, Int, Double, and Object flag evaluations
  *   - User targeting via EvaluationContext
  *   - "Flat" flags (single variable named "_" for non-boolean values)
  *   - Authenticated datafile access via access token
  *   - Configurable polling intervals
  */
final class OptimizelyProvider private (
  client: Optimizely,
  statusRef: Ref[ProviderStatus],
  eventsHub: Hub[ProviderEvent]
) extends FeatureProvider:

  val metadata: ProviderMetadata = ProviderMetadata("Optimizely", "4.1.1")

  def status: UIO[ProviderStatus] = statusRef.get

  def initialize: Task[Unit] =
    for
      _ <- statusRef.set(ProviderStatus.Ready)
      _ <- eventsHub.publish(ProviderEvent.Ready(metadata))
    yield ()

  def shutdown: UIO[Unit] =
    for
      _ <- statusRef.set(ProviderStatus.NotReady)
      _ <- ZIO.attempt(client.close()).ignore
      _ <- eventsHub.shutdown
    yield ()

  def events: UStream[ProviderEvent] =
    ZStream.fromHub(eventsHub)

  def resolveBooleanValue(
    key: String,
    defaultValue: Boolean,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    resolve(key, defaultValue, context) { decision =>
      if isValidDecision(decision) then Some(decision.getEnabled)
      else None
    }

  def resolveStringValue(
    key: String,
    defaultValue: String,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[String]] =
    resolve(key, defaultValue, context) { decision =>
      if isValidDecision(decision) then Option(decision.getVariationKey)
      else None
    }

  def resolveIntValue(
    key: String,
    defaultValue: Int,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    resolve(key, defaultValue, context) { decision =>
      if isValidDecision(decision) then
        extractFromVariables(decision) {
          case i: java.lang.Integer => Some(i.intValue())
          case l: java.lang.Long    => Some(l.intValue())
          case s: String            => scala.util.Try(s.toInt).toOption
          case _                    => None
        }
      else None
    }

  def resolveDoubleValue(
    key: String,
    defaultValue: Double,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    resolve(key, defaultValue, context) { decision =>
      if isValidDecision(decision) then
        extractFromVariables(decision) {
          case d: java.lang.Double => Some(d.doubleValue())
          case f: java.lang.Float  => Some(f.doubleValue())
          case n: Number           => Some(n.doubleValue())
          case s: String           => scala.util.Try(s.toDouble).toOption
          case _                   => None
        }
      else None
    }

  def resolveObjectValue(
    key: String,
    defaultValue: Map[String, Any],
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    resolve(key, defaultValue, context) { decision =>
      if isValidDecision(decision) then
        val json = decision.getVariables
        if json != null then
          try
            val map = json.toMap.asScala.toMap.asInstanceOf[Map[String, Any]]
            if map.nonEmpty then Some(map) else None
          catch case _: Exception => None
        else None
      else None
    }

  private def isValidDecision(decision: OptimizelyDecision): Boolean =
    decision != null &&
      decision.getVariationKey != null &&
      decision.getVariationKey.nonEmpty

  /** Extracts a value from Optimizely flag variables.
    *
    * Supports "flat" flags where a single variable named "_" holds the value. This allows non-boolean OpenFeature flags
    * to be represented in Optimizely.
    */
  private def extractFromVariables[A](decision: OptimizelyDecision)(
    extract: Any => Option[A]
  ): Option[A] =
    val json = decision.getVariables
    if json != null then
      try
        val map = json.toMap.asScala
        if map.isEmpty then None
        else if map.size == 1 then
          // Check for "flat" flag pattern (single variable named "_")
          map.get("_") match
            case Some(value) => extract(value)
            case None        => map.values.headOption.flatMap(extract)
        else
          // Multiple variables - try to extract from first value
          map.values.headOption.flatMap(extract)
      catch case _: Exception => None
    else None

  private def resolve[A](
    key: String,
    defaultValue: A,
    context: EvaluationContext
  )(extract: OptimizelyDecision => Option[A]): IO[FeatureFlagError, FlagResolution[A]] =
    ZIO
      .attempt {
        val userId = context.targetingKey.getOrElse("anonymous")
        val attributes = context.attributes.map { case (k, v) =>
          k -> attributeToJava(v)
        }.asJava

        val userContext = client.createUserContext(userId, attributes)
        if userContext == null then FlagResolution.default(key, defaultValue)
        else
          val decision = userContext.decide(key)
          if decision == null || decision.getVariationKey == null then FlagResolution.default(key, defaultValue)
          else
            extract(decision) match
              case Some(value) =>
                // Include rule key in metadata if available
                val ruleKey = Option(decision.getRuleKey)
                val meta = ruleKey match
                  case Some(rk) => FlagMetadata("ruleKey" -> rk)
                  case None     => FlagMetadata.empty
                FlagResolution(
                  value = value,
                  variant = Some(decision.getVariationKey),
                  reason = ResolutionReason.TargetingMatch,
                  metadata = meta,
                  flagKey = key,
                  errorCode = None,
                  errorMessage = None
                )
              case None => FlagResolution.default(key, defaultValue)
      }
      .mapError(e => FeatureFlagError.ProviderError(e))

  private def attributeToJava(value: AttributeValue): AnyRef = value match
    case AttributeValue.BoolValue(v)    => java.lang.Boolean.valueOf(v)
    case AttributeValue.StringValue(v)  => v
    case AttributeValue.IntValue(v)     => java.lang.Integer.valueOf(v)
    case AttributeValue.LongValue(v)    => java.lang.Long.valueOf(v)
    case AttributeValue.DoubleValue(v)  => java.lang.Double.valueOf(v)
    case AttributeValue.InstantValue(v) => v.toString
    case AttributeValue.ListValue(vs) =>
      vs.map(attributeToJava).asJava
    case AttributeValue.StructValue(m) =>
      m.map { case (k, v) => k -> attributeToJava(v) }.asJava

object OptimizelyProvider:

  // ============ Create from pre-built client ============

  /** Create provider from an existing Optimizely client */
  def make(client: Optimizely): UIO[OptimizelyProvider] =
    for
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      eventsHub <- Hub.unbounded[ProviderEvent]
    yield OptimizelyProvider(client, statusRef, eventsHub)

  /** Create a layer from an existing Optimizely client */
  def layer(client: Optimizely): ULayer[FeatureProvider] =
    ZLayer.fromZIO(make(client))

  /** Create a scoped provider from an existing Optimizely client */
  def scoped(client: Optimizely): ZIO[Scope, Nothing, OptimizelyProvider] =
    for
      provider <- make(client)
      _        <- provider.initialize.orDie
      _        <- ZIO.addFinalizer(provider.shutdown)
    yield provider

  /** Create a scoped layer from an existing Optimizely client */
  def scopedLayer(client: Optimizely): ZLayer[Scope, Nothing, FeatureProvider] =
    ZLayer.fromZIO(scoped(client))

  // ============ Create from options ============

  /** Create provider from OptimizelyOptions (builds client internally) */
  def fromOptions(options: OptimizelyOptions): Task[OptimizelyProvider] =
    ZIO.attempt(buildClient(options)).flatMap(make)

  /** Create a layer from OptimizelyOptions */
  def layerFromOptions(options: OptimizelyOptions): TaskLayer[FeatureProvider] =
    ZLayer.fromZIO(fromOptions(options))

  /** Create a scoped provider from OptimizelyOptions */
  def scopedFromOptions(options: OptimizelyOptions): ZIO[Scope, Throwable, OptimizelyProvider] =
    for
      client   <- ZIO.attempt(buildClient(options))
      provider <- make(client)
      _        <- provider.initialize
      _        <- ZIO.addFinalizer(provider.shutdown)
    yield provider

  /** Create a scoped layer from OptimizelyOptions */
  def scopedLayerFromOptions(options: OptimizelyOptions): ZLayer[Scope, Throwable, FeatureProvider] =
    ZLayer.fromZIO(scopedFromOptions(options))

  // ============ Convenience methods ============

  /** Create provider with just an SDK key (public datafile) */
  def fromSdkKey(sdkKey: String): Task[OptimizelyProvider] =
    fromOptions(OptimizelyOptions(sdkKey))

  /** Create provider with SDK key and access token (authenticated datafile) */
  def fromSdkKey(sdkKey: String, accessToken: String): Task[OptimizelyProvider] =
    fromOptions(OptimizelyOptions(sdkKey, accessToken))

  /** Create provider with SDK key, access token, and polling interval */
  def fromSdkKey(sdkKey: String, accessToken: String, pollingIntervalSeconds: Int): Task[OptimizelyProvider] =
    fromOptions(OptimizelyOptions(sdkKey, accessToken, pollingIntervalSeconds))

  /** Create a layer with just an SDK key */
  def layerFromSdkKey(sdkKey: String): TaskLayer[FeatureProvider] =
    layerFromOptions(OptimizelyOptions(sdkKey))

  /** Create a layer with SDK key and access token */
  def layerFromSdkKey(sdkKey: String, accessToken: String): TaskLayer[FeatureProvider] =
    layerFromOptions(OptimizelyOptions(sdkKey, accessToken))

  // ============ Internal helpers ============

  private def buildClient(options: OptimizelyOptions): Optimizely =
    val configManagerBuilder = HttpProjectConfigManager
      .builder()
      .withSdkKey(options.sdkKey)
      .withPollingInterval(options.pollingInterval.toSeconds, TimeUnit.SECONDS)

    // Add access token if provided (for authenticated datafile access)
    val configManager = options.accessToken match
      case Some(token) => configManagerBuilder.withDatafileAccessToken(token).build()
      case None        => configManagerBuilder.build()

    Optimizely.builder().withConfigManager(configManager).build()
