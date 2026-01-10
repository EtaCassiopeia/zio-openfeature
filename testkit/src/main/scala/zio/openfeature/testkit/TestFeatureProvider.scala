package zio.openfeature.testkit

import zio.*
import zio.stream.*
import zio.openfeature.*

final class TestFeatureProvider private (
  flagsRef: Ref[Map[String, Any]],
  statusRef: Ref[ProviderStatus],
  eventsHub: Hub[ProviderEvent],
  evaluationsRef: Ref[List[(String, EvaluationContext)]]
) extends FeatureProvider:

  val metadata: ProviderMetadata = ProviderMetadata("TestProvider", "1.0.0")

  def status: UIO[ProviderStatus] = statusRef.get

  def initialize: Task[Unit] =
    statusRef.set(ProviderStatus.Ready) *>
      eventsHub.publish(ProviderEvent.Ready(metadata)).unit

  def shutdown: UIO[Unit] =
    statusRef.set(ProviderStatus.NotReady) *>
      eventsHub.shutdown

  def events: UStream[ProviderEvent] =
    ZStream.fromHub(eventsHub)

  def resolveBooleanValue(
    key: String,
    defaultValue: Boolean,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    resolveTypedValue(key, defaultValue, context)

  def resolveStringValue(
    key: String,
    defaultValue: String,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[String]] =
    resolveTypedValue(key, defaultValue, context)

  def resolveIntValue(
    key: String,
    defaultValue: Int,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    resolveTypedValue(key, defaultValue, context)

  def resolveDoubleValue(
    key: String,
    defaultValue: Double,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    resolveTypedValue(key, defaultValue, context)

  def resolveObjectValue(
    key: String,
    defaultValue: Map[String, Any],
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    resolveTypedValue(key, defaultValue, context)

  private def resolveTypedValue[A](
    key: String,
    defaultValue: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      _     <- evaluationsRef.update((key, context) :: _)
      flags <- flagsRef.get
      result <- flags.get(key) match
        case Some(value) =>
          value match
            case v: A @unchecked => ZIO.succeed(FlagResolution.targetingMatch(key, v))
            case _ =>
              ZIO.fail(
                FeatureFlagError.TypeMismatch(
                  key,
                  defaultValue.getClass.getSimpleName,
                  value.getClass.getSimpleName
                )
              )
        case None =>
          ZIO.succeed(FlagResolution.default(key, defaultValue))
    yield result

  // Test helper methods
  def setFlag[A](key: String, value: A): UIO[Unit] =
    flagsRef.update(_ + (key -> value))

  def setFlags(flags: Map[String, Any]): UIO[Unit] =
    flagsRef.set(flags)

  def removeFlag(key: String): UIO[Unit] =
    flagsRef.update(_ - key)

  def clearFlags: UIO[Unit] =
    flagsRef.set(Map.empty)

  def setStatus(status: ProviderStatus): UIO[Unit] =
    statusRef.set(status)

  def emitEvent(event: ProviderEvent): UIO[Unit] =
    eventsHub.publish(event).unit

  def getEvaluations: UIO[List[(String, EvaluationContext)]] =
    evaluationsRef.get

  def clearEvaluations: UIO[Unit] =
    evaluationsRef.set(Nil)

  def wasEvaluated(flagKey: String): UIO[Boolean] =
    evaluationsRef.get.map(_.exists(_._1 == flagKey))

  def evaluationCount(flagKey: String): UIO[Int] =
    evaluationsRef.get.map(_.count(_._1 == flagKey))

object TestFeatureProvider:
  def make: UIO[TestFeatureProvider] =
    make(Map.empty)

  def make(flags: Map[String, Any]): UIO[TestFeatureProvider] =
    for
      flagsRef       <- Ref.make(flags)
      statusRef      <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      eventsHub      <- Hub.unbounded[ProviderEvent]
      evaluationsRef <- Ref.make(List.empty[(String, EvaluationContext)])
    yield TestFeatureProvider(flagsRef, statusRef, eventsHub, evaluationsRef)

  def layer: ULayer[TestFeatureProvider & FeatureProvider] =
    ZLayer.fromZIO(make.map(p => p))

  def layer(flags: Map[String, Any]): ULayer[TestFeatureProvider & FeatureProvider] =
    ZLayer.fromZIO(make(flags).map(p => p))
