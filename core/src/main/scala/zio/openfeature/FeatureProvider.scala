package zio.openfeature

import zio.*
import zio.stream.*

trait FeatureProvider:
  def metadata: ProviderMetadata
  def status: UIO[ProviderStatus]
  def events: ZStream[Any, Nothing, ProviderEvent]
  def initialize: Task[Unit]
  def shutdown: UIO[Unit]

  def resolveBooleanValue(
    key: String,
    defaultValue: Boolean,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]]

  def resolveStringValue(
    key: String,
    defaultValue: String,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[String]]

  def resolveIntValue(
    key: String,
    defaultValue: Int,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Int]]

  def resolveDoubleValue(
    key: String,
    defaultValue: Double,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Double]]

  def resolveObjectValue(
    key: String,
    defaultValue: Map[String, Any],
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]

  def resolveValue[A: FlagType](
    key: String,
    defaultValue: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType = FlagType[A]
    flagType.typeName match
      case "Boolean" =>
        resolveBooleanValue(key, defaultValue.asInstanceOf[Boolean], context)
          .map(_.asInstanceOf[FlagResolution[A]])
      case "String" =>
        resolveStringValue(key, defaultValue.asInstanceOf[String], context)
          .map(_.asInstanceOf[FlagResolution[A]])
      case "Int" =>
        resolveIntValue(key, defaultValue.asInstanceOf[Int], context)
          .map(_.asInstanceOf[FlagResolution[A]])
      case "Long" =>
        resolveIntValue(key, defaultValue.asInstanceOf[Long].toInt, context).map { resolution =>
          resolution.copy(value = resolution.value.toLong).asInstanceOf[FlagResolution[A]]
        }
      case "Float" =>
        resolveDoubleValue(key, defaultValue.asInstanceOf[Float].toDouble, context).map { resolution =>
          resolution.copy(value = resolution.value.toFloat).asInstanceOf[FlagResolution[A]]
        }
      case "Double" =>
        resolveDoubleValue(key, defaultValue.asInstanceOf[Double], context)
          .map(_.asInstanceOf[FlagResolution[A]])
      case "Object" =>
        resolveObjectValue(key, defaultValue.asInstanceOf[Map[String, Any]], context)
          .map(_.asInstanceOf[FlagResolution[A]])
      case _ =>
        resolveObjectValue(key, Map.empty, context).flatMap { resolution =>
          flagType.decode(resolution.value) match
            case Right(decoded) =>
              ZIO.succeed(resolution.copy(value = decoded).asInstanceOf[FlagResolution[A]])
            case Left(error) =>
              ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "Object"))
        }

object FeatureProvider:
  def get: URIO[FeatureProvider, FeatureProvider]              = ZIO.service[FeatureProvider]
  def metadata: URIO[FeatureProvider, ProviderMetadata]        = ZIO.serviceWith(_.metadata)
  def status: URIO[FeatureProvider, ProviderStatus]            = ZIO.serviceWithZIO(_.status)
  def events: ZStream[FeatureProvider, Nothing, ProviderEvent] = ZStream.serviceWithStream(_.events)
