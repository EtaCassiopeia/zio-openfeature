package zio.openfeature.testkit

import zio.*
import zio.stream.*
import zio.openfeature.*
import dev.openfeature.sdk.{
  EvaluationContext as OFEvaluationContext,
  FeatureProvider as OFFeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value,
  Structure
}
import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** A test provider that implements OpenFeature's FeatureProvider interface.
  *
  * This provider allows you to:
  *   - Set flag values programmatically
  *   - Track which flags were evaluated and with what context
  *   - Simulate different provider states
  *   - Emit provider events
  */
final class TestFeatureProvider private (
  private val flags: ConcurrentHashMap[String, Any],
  private val state: AtomicReference[ProviderState],
  private val evaluations: CopyOnWriteArrayList[(String, OFEvaluationContext)],
  private val eventsHubRef: Ref[Hub[ProviderEvent]],
  private val statusRef: Ref[ProviderStatus]
) extends OFFeatureProvider:

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata:
    override def getName: String = "TestFeatureProvider"

  override def getState: ProviderState = state.get()

  override def initialize(context: OFEvaluationContext): Unit =
    state.set(ProviderState.READY)

  override def shutdown(): Unit =
    state.set(ProviderState.NOT_READY)

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    evaluations.add((key, context))
    val value = Option(flags.get(key)).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
    ProviderEvaluation
      .builder[java.lang.Boolean]()
      .value(value)
      .reason(if flags.containsKey(key) then "TARGETING_MATCH" else "DEFAULT")
      .build()

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    evaluations.add((key, context))
    val value = Option(flags.get(key)).map(_.toString).getOrElse(defaultValue)
    ProviderEvaluation
      .builder[String]()
      .value(value)
      .reason(if flags.containsKey(key) then "TARGETING_MATCH" else "DEFAULT")
      .build()

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    evaluations.add((key, context))
    val value = Option(flags.get(key))
      .map {
        case i: Int    => i
        case l: Long   => l.toInt
        case d: Double => d.toInt
        case other     => other.toString.toInt
      }
      .getOrElse(defaultValue.intValue())
    ProviderEvaluation
      .builder[java.lang.Integer]()
      .value(value)
      .reason(if flags.containsKey(key) then "TARGETING_MATCH" else "DEFAULT")
      .build()

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    evaluations.add((key, context))
    val value = Option(flags.get(key))
      .map {
        case d: Double => d
        case f: Float  => f.toDouble
        case i: Int    => i.toDouble
        case l: Long   => l.toDouble
        case other     => other.toString.toDouble
      }
      .getOrElse(defaultValue.doubleValue())
    ProviderEvaluation
      .builder[java.lang.Double]()
      .value(value)
      .reason(if flags.containsKey(key) then "TARGETING_MATCH" else "DEFAULT")
      .build()

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    evaluations.add((key, context))
    val value = Option(flags.get(key))
      .map(anyToValue)
      .getOrElse(defaultValue)
    ProviderEvaluation
      .builder[Value]()
      .value(value)
      .reason(if flags.containsKey(key) then "TARGETING_MATCH" else "DEFAULT")
      .build()

  private def anyToValue(any: Any): Value = any match
    case b: Boolean              => new Value(b)
    case s: String               => new Value(s)
    case i: Int                  => new Value(i)
    case l: Long                 => new Value(l.toDouble)
    case d: Double               => new Value(d)
    case list: List[?]           => new Value(list.map(anyToValue).asJava)
    case map: Map[?, ?] =>
      val javaMap: java.util.Map[String, Object] = map.asInstanceOf[Map[String, Any]].map { case (k, v) =>
        k -> anyToValue(v).asObject()
      }.asJava
      new Value(Structure.mapToStructure(javaMap))
    case null  => new Value()
    case other => new Value(other.toString)

  // Test Helper Methods

  /** Set a flag value for testing. */
  def setFlag[A](key: String, value: A): UIO[Unit] =
    ZIO.succeed(flags.put(key, value)).unit

  /** Set multiple flag values. */
  def setFlags(newFlags: Map[String, Any]): UIO[Unit] =
    ZIO.succeed {
      flags.clear()
      newFlags.foreach { case (k, v) => flags.put(k, v) }
    }

  /** Remove a flag. */
  def removeFlag(key: String): UIO[Unit] =
    ZIO.succeed(flags.remove(key)).unit

  /** Clear all flags. */
  def clearFlags: UIO[Unit] =
    ZIO.succeed(flags.clear())

  /** Set the provider status. */
  def setStatus(status: ProviderStatus): UIO[Unit] =
    statusRef.set(status) *> ZIO.succeed {
      status match
        case ProviderStatus.Ready        => state.set(ProviderState.READY)
        case ProviderStatus.NotReady     => state.set(ProviderState.NOT_READY)
        case ProviderStatus.Error        => state.set(ProviderState.ERROR)
        case ProviderStatus.Stale        => state.set(ProviderState.STALE)
        case ProviderStatus.ShuttingDown => state.set(ProviderState.NOT_READY)
    }

  /** Get the current status. */
  def getStatus: UIO[ProviderStatus] =
    statusRef.get

  /** Emit a provider event. */
  def emitEvent(event: ProviderEvent): UIO[Unit] =
    eventsHubRef.get.flatMap(_.publish(event)).unit

  /** Get all evaluations that have been made. */
  def getEvaluations: UIO[List[(String, OFEvaluationContext)]] =
    ZIO.succeed(evaluations.asScala.toList)

  /** Clear the evaluation history. */
  def clearEvaluations: UIO[Unit] =
    ZIO.succeed(evaluations.clear())

  /** Check if a flag was evaluated. */
  def wasEvaluated(flagKey: String): UIO[Boolean] =
    ZIO.succeed(evaluations.asScala.exists(_._1 == flagKey))

  /** Count how many times a flag was evaluated. */
  def evaluationCount(flagKey: String): UIO[Int] =
    ZIO.succeed(evaluations.asScala.count(_._1 == flagKey))

  /** Get the events hub for streaming. */
  def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.unwrap(eventsHubRef.get.map(ZStream.fromHub(_)))

  /** Provider metadata (ZIO-style). */
  val metadata: ProviderMetadata = ProviderMetadata("TestFeatureProvider", "1.0.0")

  /** Get status as ZIO effect. */
  def status: UIO[ProviderStatus] = statusRef.get

object TestFeatureProvider:

  /** Create a new TestFeatureProvider with no initial flags. */
  def make: UIO[TestFeatureProvider] =
    make(Map.empty)

  /** Create a new TestFeatureProvider with initial flags. */
  def make(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    for
      eventsHub <- Hub.unbounded[ProviderEvent]
      hubRef    <- Ref.make(eventsHub)
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.Ready)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(flags, state, evaluations, hubRef, statusRef)
      }
    yield provider

  /** Create a FeatureFlags layer from TestFeatureProvider.
    *
    * This provides both TestFeatureProvider (for test helpers) and FeatureFlags (for flag evaluation).
    * Each layer gets a unique domain for test isolation.
    */
  def layer: ZLayer[Scope, Throwable, TestFeatureProvider & FeatureFlags] =
    layer(Map.empty)

  /** Create a FeatureFlags layer with initial flags.
    * Uses a unique domain per invocation to ensure test isolation.
    */
  def layer(flags: Map[String, Any]): ZLayer[Scope, Throwable, TestFeatureProvider & FeatureFlags] =
    ZLayer.scoped {
      for
        testProvider <- make(flags)
        domain = s"test-${java.util.UUID.randomUUID()}"
        featureFlags <- FeatureFlags.fromProviderWithDomain(testProvider, domain).build.map(_.get)
      yield (testProvider, featureFlags)
    }.flatMap { env =>
      val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
      ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
    }

  /** Create just the TestFeatureProvider layer (without FeatureFlags). */
  def providerLayer: ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make)

  /** Create just the TestFeatureProvider layer with initial flags. */
  def providerLayer(flags: Map[String, Any]): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(flags))

  /** Create a FeatureFlags layer from an existing TestFeatureProvider.
    *
    * This is useful when you need to manipulate the provider before creating the layer,
    * or when you want to share a provider across multiple tests.
    */
  def layerFrom(provider: TestFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    val domain = s"test-${java.util.UUID.randomUUID()}"
    FeatureFlags.fromProviderWithDomain(provider, domain)
