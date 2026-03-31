package zio.openfeature.testkit

import zio._
import zio.stream._
import zio.openfeature._
import zio.openfeature.internal.ErrorCodeConverter
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableMetadata,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value,
  Structure
}
import dev.openfeature.sdk.exceptions._
import zio.test.TestAspect
import scala.jdk.CollectionConverters._
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, CountDownLatch}
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
  private[openfeature] val statusRef: Ref[ProviderStatus],
  private val initLatch: Option[CountDownLatch],
  private[testkit] val initDone: Option[CountDownLatch]
) extends EventProvider {

  import TestFeatureProvider.{BehaviorConfig, ErrorMode}

  private[testkit] val behaviorRef: AtomicReference[BehaviorConfig] = new AtomicReference(BehaviorConfig())

  private def applyBehavior(): Unit = {
    val config = behaviorRef.get()
    config.delay.foreach(d => Thread.sleep(d.toMillis))
    config.errorMode.foreach {
      case ErrorMode.FlagNotFound     => throw new FlagNotFoundError("Simulated: flag not found")
      case ErrorMode.ParseError       => throw new ParseError("Simulated: parse error")
      case ErrorMode.TypeMismatch     => throw new TypeMismatchError("Simulated: type mismatch")
      case ErrorMode.ProviderNotReady => throw new ProviderNotReadyError("Simulated: provider not ready")
      case ErrorMode.General          => throw new GeneralError("Simulated: general error")
    }
    if (
      config.failureProbability > 0.0 &&
      java.util.concurrent.ThreadLocalRandom.current().nextDouble() < config.failureProbability
    )
      throw new GeneralError("Simulated: random failure")
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = "TestFeatureProvider"
  }

  override def getState: ProviderState = state.get()

  override def initialize(context: OFEvaluationContext): Unit = {
    initLatch match {
      case Some(latch) => latch.await() // Block until released
      case None        => ()
    }
    state.set(ProviderState.READY)
    // Note: initDone is NOT counted down here. It is counted down by the event bridge's
    // readyHandler when the Java SDK fires PROVIDER_READY, ensuring the SDK has fully
    // processed the initialization before setStatus(Ready) returns.
  }

  override def shutdown(): Unit = {
    initLatch.foreach(_.countDown()) // Release blocked initialize() if still waiting
    initDone.foreach(_.countDown())  // Unblock any waiter if initialize() never ran
    state.set(ProviderState.NOT_READY)
  }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = {
    applyBehavior()
    evaluations.add((key, context))
    val value = Option(flags.get(key)).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
    ProviderEvaluation
      .builder[java.lang.Boolean]()
      .value(value)
      .reason(if (flags.containsKey(key)) "TARGETING_MATCH" else "DEFAULT")
      .build()
  }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] = {
    applyBehavior()
    evaluations.add((key, context))
    val value = Option(flags.get(key)).map(_.toString).getOrElse(defaultValue)
    ProviderEvaluation
      .builder[String]()
      .value(value)
      .reason(if (flags.containsKey(key)) "TARGETING_MATCH" else "DEFAULT")
      .build()
  }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = {
    applyBehavior()
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
      .reason(if (flags.containsKey(key)) "TARGETING_MATCH" else "DEFAULT")
      .build()
  }

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = {
    applyBehavior()
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
      .reason(if (flags.containsKey(key)) "TARGETING_MATCH" else "DEFAULT")
      .build()
  }

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] = {
    applyBehavior()
    evaluations.add((key, context))
    val value = Option(flags.get(key))
      .map(anyToValue)
      .getOrElse(defaultValue)
    ProviderEvaluation
      .builder[Value]()
      .value(value)
      .reason(if (flags.containsKey(key)) "TARGETING_MATCH" else "DEFAULT")
      .build()
  }

  private def anyToValue(any: Any): Value = any match {
    case b: Boolean    => new Value(b)
    case s: String     => new Value(s)
    case i: Int        => new Value(i)
    case l: Long       => new Value(l.toDouble)
    case d: Double     => new Value(d)
    case list: List[_] => new Value(list.map(anyToValue).asJava)
    case map: Map[_, _] =>
      val javaMap: java.util.Map[String, Object] = map
        .asInstanceOf[Map[String, Any]]
        .map { case (k, v) =>
          k -> anyToValue(v).asObject()
        }
        .asJava
      new Value(Structure.mapToStructure(javaMap))
    case null  => new Value()
    case other => new Value(other.toString)
  }

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

  /** Set the provider status. For async test layers, setting Ready releases the init latch and waits for the Java SDK's
    * background `initialize()` thread to complete, ensuring the provider is fully ready before returning.
    */
  def setStatus(status: ProviderStatus): UIO[Unit] =
    statusRef.set(status) *> ZIO.succeed {
      status match {
        case ProviderStatus.Ready =>
          state.set(ProviderState.READY)
          initLatch.foreach(_.countDown())
          // Wait for the event bridge to receive PROVIDER_READY from the Java SDK.
          // This confirms the SDK has fully processed the initialization and the
          // provider is registered as ready — no sleep needed.
          initDone.foreach(_.await())
        case ProviderStatus.NotReady     => state.set(ProviderState.NOT_READY)
        case ProviderStatus.Error        => state.set(ProviderState.ERROR)
        case ProviderStatus.Stale        => state.set(ProviderState.STALE)
        case ProviderStatus.Fatal        => state.set(ProviderState.FATAL)
        case ProviderStatus.ShuttingDown => state.set(ProviderState.NOT_READY)
      }
    }

  /** Get the current status. */
  def getStatus: UIO[ProviderStatus] =
    statusRef.get

  private def toJavaMetadata(meta: FlagMetadata): ImmutableMetadata =
    if (meta.isEmpty) ImmutableMetadata.builder().build()
    else {
      val builder = ImmutableMetadata.builder()
      meta.values.foreach {
        case (k, MetadataValue.BooleanValue(v)) => builder.addBoolean(k, v)
        case (k, MetadataValue.StringValue(v))  => builder.addString(k, v)
        case (k, MetadataValue.IntValue(v))     => builder.addInteger(k, v)
        case (k, MetadataValue.LongValue(v))    => builder.addLong(k, v)
        case (k, MetadataValue.FloatValue(v))   => builder.addFloat(k, v)
        case (k, MetadataValue.DoubleValue(v))  => builder.addDouble(k, v)
      }
      builder.build()
    }

  /** Emit a provider event through the Java SDK event system so it reaches FeatureFlagsLive handlers. */
  def emitEvent(event: ProviderEvent): UIO[Unit] =
    eventsHubRef.get.flatMap(_.publish(event)).unit *>
      ZIO.succeed {
        event match {
          case ProviderEvent.Ready(_, em) =>
            emitProviderReady(ProviderEventDetails.builder().eventMetadata(toJavaMetadata(em)).build())
          case ProviderEvent.Error(_, _, errorCode, errorMessage, em) =>
            val builder = ProviderEventDetails.builder()
            errorMessage.foreach(builder.message(_))
            errorCode.foreach(ec => builder.errorCode(ErrorCodeConverter.toJava(ec)))
            builder.eventMetadata(toJavaMetadata(em))
            emitProviderError(builder.build())
          case ProviderEvent.Stale(reason, _, em) =>
            emitProviderStale(
              ProviderEventDetails.builder().message(reason).eventMetadata(toJavaMetadata(em)).build()
            )
          case ProviderEvent.ConfigurationChanged(changedFlags, _, em) =>
            emitProviderConfigurationChanged(
              ProviderEventDetails
                .builder()
                .flagsChanged(changedFlags.toList.asJava)
                .eventMetadata(toJavaMetadata(em))
                .build()
            )
          case ProviderEvent.Reconnecting(_, _) =>
            () // No Java SDK equivalent for reconnecting
        }
      }

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

  // Behavior Controls

  /** Add a delay before each evaluation (simulates network latency). */
  def setDelay(d: Duration): UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(delay = Some(d)))).unit

  /** Remove the evaluation delay. */
  def clearDelay: UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(delay = None))).unit

  /** Make all evaluations fail with the given error mode. */
  def setErrorMode(mode: ErrorMode): UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(errorMode = Some(mode)))).unit

  /** Clear the error mode (evaluations succeed normally). */
  def clearErrorMode: UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(errorMode = None))).unit

  /** Convenience: make all evaluations fail with a general error. */
  def setFailing(failing: Boolean): UIO[Unit] =
    if (failing) setErrorMode(ErrorMode.General) else clearErrorMode

  /** Set the probability (0.0 to 1.0) that each evaluation fails randomly. */
  def setFailureProbability(p: Double): UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(failureProbability = p))).unit

  /** Reset all behavior controls to defaults. */
  def clearBehavior: UIO[Unit] =
    ZIO.succeed(behaviorRef.set(BehaviorConfig())).unit
}

object TestFeatureProvider {

  // Behavior Controls — types

  private[testkit] case class BehaviorConfig(
    delay: Option[Duration] = None,
    errorMode: Option[ErrorMode] = None,
    failureProbability: Double = 0.0
  )

  sealed trait ErrorMode extends Product with Serializable
  object ErrorMode {
    case object FlagNotFound     extends ErrorMode
    case object ParseError       extends ErrorMode
    case object TypeMismatch     extends ErrorMode
    case object ProviderNotReady extends ErrorMode
    case object General          extends ErrorMode
  }

  // Behavior Controls — TestAspects

  /** Aspect that adds a delay to all evaluations for the duration of the test. */
  def withDelay(d: Duration): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    behaviorAspect(_.copy(delay = Some(d)))

  /** Aspect that makes all evaluations fail with a general error. */
  val withFailures: TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    behaviorAspect(_.copy(errorMode = Some(ErrorMode.General)))

  /** Aspect that makes all evaluations fail with a specific error mode. */
  def withErrorMode(mode: ErrorMode): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    behaviorAspect(_.copy(errorMode = Some(mode)))

  /** Aspect that makes evaluations fail randomly with the given probability (0.0 to 1.0). */
  def withFailureProbability(p: Double): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    behaviorAspect(_.copy(failureProbability = p))

  private def behaviorAspect(
    modify: BehaviorConfig => BehaviorConfig
  ): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] = {
    val setup   = ZIO.serviceWith[TestFeatureProvider](tp => tp.behaviorRef.updateAndGet(modify(_)))
    val cleanup = ZIO.serviceWith[TestFeatureProvider](_.behaviorRef.set(BehaviorConfig()))
    TestAspect.before(setup) >>> TestAspect.after(cleanup)
  }

  /** Create a new TestFeatureProvider with no initial flags. */
  def make: UIO[TestFeatureProvider] =
    make(Map.empty)

  /** Create a new TestFeatureProvider with initial flags. */
  def make(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    for {
      eventsHub <- Hub.unbounded[ProviderEvent]
      hubRef    <- Ref.make(eventsHub)
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.Ready)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(flags, state, evaluations, hubRef, statusRef, initLatch = None, initDone = None)
      }
    } yield provider

  /** Create a FeatureFlags layer from TestFeatureProvider. */
  def layer: ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(Map.empty)

  /** Create a FeatureFlags layer with initial flags.
    *
    * Each invocation creates an isolated OpenFeatureAPI instance and a unique domain, ensuring full test isolation.
    * Tests using this layer can safely run in parallel without cross-test event contamination.
    */
  def layer(flags: Map[String, Any]): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    ZLayer
      .scoped {
        for {
          testProvider <- make(flags)
          api    = OpenFeatureAPIFactory.create()
          domain = s"test-${java.util.UUID.randomUUID()}"
          featureFlags <- FeatureFlags
            .fromProviderWithDomain(testProvider, domain, testProvider.statusRef, api = Some(api))
            .build
            .map(_.get)
          // The Java SDK dispatches an initial PROVIDER_READY event asynchronously when
          // handlers are registered on an already-ready provider. Wait briefly for this
          // event to settle so that subsequent setStatus() calls in tests are not overwritten.
          // Use the live clock to avoid blocking on ZIO's TestClock.
          _ <- ZIO.attemptBlocking(Thread.sleep(50)).ignore
        } yield (testProvider, featureFlags)
      }
      .flatMap { env =>
        val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
      }

  /** Create just the TestFeatureProvider layer (without FeatureFlags). */
  def providerLayer: ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make)

  /** Create just the TestFeatureProvider layer with initial flags. */
  def providerLayer(flags: Map[String, Any]): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(flags))

  /** Create a FeatureFlags layer from an existing TestFeatureProvider. */
  def layerFrom(provider: TestFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"test-${java.util.UUID.randomUUID()}"
    FeatureFlags.fromProviderWithDomain(provider, domain, provider.statusRef, api = Some(api))
  }

  /** Self-contained test layer that provides its own Scope. */
  val scopedLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer

  /** Self-contained test layer with initial flags that provides its own Scope. */
  def scopedLayer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(flags)

  /** Create a FeatureFlags layer with async (non-blocking) initialization.
    *
    * The provider starts in NotReady state. Use `setStatus(ProviderStatus.Ready)` or emit a `ProviderEvent.Ready` event
    * to simulate the provider becoming ready. Evaluations will fail with `ProviderNotReady` until then.
    */
  def asyncLayer: ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncLayer(Map.empty)

  /** Create a FeatureFlags layer with async initialization and initial flags.
    *
    * The provider starts in NotReady state and does not auto-initialize. Call `setStatus(ProviderStatus.Ready)` to
    * simulate the provider becoming ready. Evaluations will fail with `ProviderNotReady` until then.
    */
  def asyncLayer(flags: Map[String, Any]): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    ZLayer
      .scoped {
        for {
          testProvider <- makeNotReady(flags)
          api    = OpenFeatureAPIFactory.create()
          domain = s"test-async-${java.util.UUID.randomUUID()}"
          featureFlags <- FeatureFlags
            .fromProviderWithDomainAsync(
              testProvider,
              domain,
              testProvider.statusRef,
              api = Some(api),
              onReady = testProvider.initDone
            )
            .build
            .map(_.get)
        } yield (testProvider, featureFlags)
      }
      .flatMap { env =>
        val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
      }

  /** Create a TestFeatureProvider that starts in NotReady state.
    *
    * The provider's `initialize()` blocks until `setStatus(ProviderStatus.Ready)` is called, simulating a
    * slow-connecting provider (e.g., one that needs to establish a network connection).
    */
  private[testkit] def makeNotReady(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    for {
      eventsHub <- Hub.unbounded[ProviderEvent]
      hubRef    <- Ref.make(eventsHub)
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.NOT_READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(
          flags,
          state,
          evaluations,
          hubRef,
          statusRef,
          initLatch = Some(new CountDownLatch(1)),
          initDone = Some(new CountDownLatch(1))
        )
      }
    } yield provider

  /** Create a FeatureFlags layer with async initialization that auto-transitions to Ready.
    *
    * Like `asyncLayer`, the provider starts in NotReady state. After `initDelay`, it automatically transitions to Ready
    * without manual `setStatus` calls. Useful for simulating a real async provider (e.g., connecting to a remote
    * service) without needing to manage the state transition in tests.
    */
  def asyncReadyLayer(
    flags: Map[String, Any] = Map.empty,
    initDelay: Duration = 100.millis
  ): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    ZLayer
      .scoped {
        for {
          testProvider <- makeNotReady(flags)
          api    = OpenFeatureAPIFactory.create()
          domain = s"test-async-ready-${java.util.UUID.randomUUID()}"
          featureFlags <- FeatureFlags
            .fromProviderWithDomainAsync(
              testProvider,
              domain,
              testProvider.statusRef,
              api = Some(api),
              onReady = testProvider.initDone
            )
            .build
            .map(_.get)
          _ <- (ZIO.sleep(initDelay) *> testProvider.setStatus(ProviderStatus.Ready)).forkScoped
        } yield (testProvider, featureFlags)
      }
      .flatMap { env =>
        val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
      }

  /** Self-contained async test layer that provides its own Scope. */
  val scopedAsyncLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.asyncLayer

  /** Self-contained async test layer with initial flags. */
  def scopedAsyncLayer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.asyncLayer(flags)

}
