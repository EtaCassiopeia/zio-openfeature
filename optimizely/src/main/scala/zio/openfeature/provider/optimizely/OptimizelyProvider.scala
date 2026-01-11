package zio.openfeature.provider.optimizely

import zio.*
import zio.stream.*
import zio.openfeature.*
import com.optimizely.ab.Optimizely
import com.optimizely.ab.OptimizelyUserContext
import com.optimizely.ab.optimizelydecision.OptimizelyDecision
import scala.jdk.CollectionConverters.*

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

  private def extractFromVariables[A](decision: OptimizelyDecision)(
    extract: Any => Option[A]
  ): Option[A] =
    val json = decision.getVariables
    if json != null then
      try
        val map = json.toMap.asScala
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
          extract(decision) match
            case Some(value) => FlagResolution.targetingMatch(key, value)
            case None        => FlagResolution.default(key, defaultValue)
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
  def make(client: Optimizely): UIO[OptimizelyProvider] =
    for
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      eventsHub <- Hub.unbounded[ProviderEvent]
    yield OptimizelyProvider(client, statusRef, eventsHub)

  def layer(client: Optimizely): ULayer[FeatureProvider] =
    ZLayer.fromZIO(make(client))

  def scoped(client: Optimizely): ZIO[Scope, Nothing, OptimizelyProvider] =
    for
      provider <- make(client)
      _        <- provider.initialize.orDie
      _        <- ZIO.addFinalizer(provider.shutdown)
    yield provider

  def scopedLayer(client: Optimizely): ZLayer[Scope, Nothing, FeatureProvider] =
    ZLayer.fromZIO(scoped(client))
