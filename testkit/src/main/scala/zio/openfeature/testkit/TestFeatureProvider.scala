package zio.openfeature.testkit

import zio._
import zio.stream._
import zio.openfeature._
import zio.openfeature.internal.{
  ContextConverter,
  ErrorCodeConverter,
  FeatureFlagsState,
  ProviderEvaluations,
  ValueBridge
}
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableMetadata,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value,
  TrackingEventDetails => OFTracking
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
  *
  * A key that has not been set resolves as `FLAG_NOT_FOUND` with the caller's default as the value, exactly as the real
  * providers do — see `notFound` for what that changes for observers.
  */
final class TestFeatureProvider private (
  private val flags: ConcurrentHashMap[String, Any],
  private val state: AtomicReference[ProviderState],
  private val evaluations: CopyOnWriteArrayList[(String, OFEvaluationContext)],
  private val eventsHub: Hub[ProviderEvent],
  private[openfeature] val statusRef: SubscriptionRef[ProviderStatus],
  private val initLatch: Option[CountDownLatch],
  private[testkit] val initDone: Option[CountDownLatch],
  private val name: String
) extends EventProvider {

  import TestFeatureProvider.{BehaviorConfig, ErrorMode}

  private[testkit] val behaviorRef: AtomicReference[BehaviorConfig] = new AtomicReference(BehaviorConfig())

  // SDK 1.22.0 hands the bound domain to `initialize(ctx, domain)` (null for the default provider). Recorded so
  // tests can assert what a domain-registered client actually delivered — there is no other observation point.
  private val boundDomainRef: AtomicReference[Option[String]] = new AtomicReference(None)

  // What `track` delivered, after the SDK merged the context (#442). Bounded, so a long test run that tracks per request
  // cannot grow it without limit.
  private val tracked =
    new java.util.concurrent.ConcurrentLinkedDeque[(String, OFEvaluationContext, Option[OFTracking])]()

  override def track(eventName: String, context: OFEvaluationContext, details: OFTracking): Unit = {
    tracked.addLast((eventName, context, Option(details)))
    while (tracked.size > FeatureFlagsState.MaxTrackedEvents) tracked.pollFirst()
    ()
  }

  /** The tracking events this provider received, oldest first (the last 1000), each with the context the SDK merged and
    * the details converted back to [[zio.openfeature.TrackingEventDetails]]. This is what a real provider would have
    * seen, so it is the place to assert on tracking.
    */
  def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    ZIO.succeed(tracked.asScala.toList.map { case (name, ctx, details) =>
      (name, ContextConverter.fromOpenFeature(ctx), details.map(TestFeatureProvider.fromOpenFeatureTracking))
    })

  /** Clear the tracking history. */
  def clearTrackedEvents: UIO[Unit] = ZIO.succeed(tracked.clear())

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
    override def getName: String = name
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

  /** SDK 1.22.0 calls this overload with the bound domain (null for the default provider) and its interface default
    * forwards to the one-argument form. Recording the domain before delegating keeps all existing init behaviour while
    * giving [[boundDomain]] something to report.
    */
  override def initialize(context: OFEvaluationContext, domain: String): Unit = {
    boundDomainRef.set(Option(domain))
    initialize(context)
  }

  override def shutdown(): Unit = {
    initLatch.foreach(_.countDown()) // Release blocked initialize() if still waiting
    initDone.foreach(_.countDown())  // Unblock any waiter if initialize() never ran
    state.set(ProviderState.NOT_READY)
  }

  /** A key this provider does not hold is `FLAG_NOT_FOUND`, not a `DEFAULT`-reason answer. Only `FLAG_NOT_FOUND` makes
    * a `MultiProvider` chain move on to the next provider — a `DEFAULT` result ends the chain here — so this is what
    * lets the test provider sit in a chain. The returned '''value''' is still the caller's default and no evaluation
    * fails; what changes for observers is `reason = Error` / `errorCode = FlagNotFound` and that hooks see the `error`
    * stage rather than `after`. A test that means "this flag is off" should set it to `false` rather than leave it
    * unset.
    */
  private def notFound[T](key: String, defaultValue: T): ProviderEvaluation[T] =
    ProviderEvaluations.error(
      defaultValue,
      OFErrorCode.FLAG_NOT_FOUND,
      s"Flag '$key' is not set on this TestFeatureProvider"
    )

  private def evaluate[T](key: String, defaultValue: T)(convert: Any => T): ProviderEvaluation[T] =
    Option(flags.get(key)) match {
      case Some(raw) => ProviderEvaluations.of(convert(raw), "TARGETING_MATCH")
      case None      => notFound(key, defaultValue)
    }

  /** A scalar getter serves a stored value only when its runtime type is exactly the getter's boxed type; anything else
    * is `TYPE_MISMATCH` with the caller's default, as a real provider answers (#457). No coercion, no parsing, no
    * truncation and no widening — `Int` is not served as a `Long`, nor `Long` as an `Int`.
    *
    * The one exception is a `Float` read through the double getter: `FlagType[Float]` encodes to a `Float` and the SDK
    * has no float surface, so that is the only way a typed `FlagDef[Float]` fixture can be served at all.
    */
  private def typed[T](key: String, defaultValue: T, expected: Class[T])(
    accept: PartialFunction[Any, T] = PartialFunction.empty
  ): ProviderEvaluation[T] =
    Option(flags.get(key)) match {
      case Some(raw) if expected.isInstance(raw) => ProviderEvaluations.of(expected.cast(raw), "TARGETING_MATCH")
      case Some(raw) if accept.isDefinedAt(raw)  => ProviderEvaluations.of(accept(raw), "TARGETING_MATCH")
      case Some(raw) =>
        ProviderEvaluations.error(
          defaultValue,
          OFErrorCode.TYPE_MISMATCH,
          s"Flag '$key' holds a ${raw.getClass.getSimpleName}, read as ${expected.getSimpleName}"
        )
      case None => notFound(key, defaultValue)
    }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = {
    evaluations.add((key, context))
    applyBehavior()
    typed(key, defaultValue, classOf[java.lang.Boolean])()
  }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] = {
    evaluations.add((key, context))
    applyBehavior()
    typed(key, defaultValue, classOf[String])()
  }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = {
    evaluations.add((key, context))
    applyBehavior()
    typed(key, defaultValue, classOf[java.lang.Integer])()
  }

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    evaluations.add((key, context))
    applyBehavior()
    typed(key, defaultValue, classOf[java.lang.Long])()
  }

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = {
    evaluations.add((key, context))
    applyBehavior()
    typed(key, defaultValue, classOf[java.lang.Double]) { case f: java.lang.Float =>
      java.lang.Double.valueOf(f.doubleValue)
    }
  }

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] = {
    evaluations.add((key, context))
    applyBehavior()
    evaluate(key, defaultValue)(ValueBridge.anyToValue)
  }

  // Test Helper Methods

  /** Set a flag value for testing. */
  def setFlag[A](key: String, value: A): UIO[Unit] =
    ZIO.succeed(flags.put(key, value)).unit

  /** Merge these flag values into the existing flags. Flags seeded via `layer(Map(...))` / `make(Map(...))` or set by
    * earlier `setFlag`/`setFlags` calls are kept; a key present in `newFlags` overwrites its previous value. Use
    * [[replaceFlags]] to discard the existing flags first.
    */
  def setFlags(newFlags: Map[String, Any]): UIO[Unit] =
    ZIO.succeed(newFlags.foreach { case (k, v) => flags.put(k, v) })

  /** Replace ALL flags with these — every existing flag is discarded first, and a discarded key then resolves as
    * `FLAG_NOT_FOUND`.
    */
  def replaceFlags(newFlags: Map[String, Any]): UIO[Unit] =
    ZIO.succeed {
      flags.clear()
      newFlags.foreach { case (k, v) => flags.put(k, v) }
    }

  /** Remove a flag. The key then resolves as `FLAG_NOT_FOUND` (caller's default, `reason = Error`), not `DEFAULT`. */
  def removeFlag(key: String): UIO[Unit] =
    ZIO.succeed(flags.remove(key)).unit

  // Typed fixture helpers (#351)
  //
  // These take a `FlagDef[A]` instead of a bare key, so the value is checked against the flag's declared type and is
  // stored through `flagType.encode` — the test then reads it back through the same decode path production uses. The
  // key-based methods above stay: they are still how you test an undeclared key, a foreign key, or a negative case
  // like FLAG_NOT_FOUND.
  //
  // Each takes a type parameter rather than a `FlagDef[?]`/`FlagDef[_]` wildcard, because this file is cross-compiled
  // and the two versions spell that wildcard differently. `A` is inferred at every call site, so it costs callers
  // nothing.

  /** Set a flag from its definition, storing `flag.flagType.encode(value)`.
    *
    * This coexisting safely with `setFlag[A](key: String, value: A)` rests on `FlagDef` being '''invariant''' in `A`:
    * that is what forces `A = Tier` from a `FlagDef[Tier]` argument, so `setFlag(TierFlag, "paid")` is rejected. Were
    * `FlagDef` declared `+A`, `A` would widen to a common supertype and the type guarantee here would evaporate — worth
    * knowing, since that declaration lives in another module.
    */
  def setFlag[A](flag: FlagDef[A], value: A): UIO[Unit] =
    setFlag(flag.key, flag.flagType.encode(value))

  /** Apply typed overrides, keeping any flags already set. */
  def setFlags(overrides: FlagOverride*): UIO[Unit] =
    ZIO.succeed(overrides.foreach(o => flags.put(o.key, o.encoded)))

  /** Replace ALL flags with these typed overrides. */
  def replaceFlags(overrides: FlagOverride*): UIO[Unit] =
    ZIO.succeed {
      flags.clear()
      overrides.foreach(o => flags.put(o.key, o.encoded))
    }

  /** Remove a flag by its definition. */
  def removeFlag[A](flag: FlagDef[A]): UIO[Unit] =
    removeFlag(flag.key)

  /** Clear all flags. Every key then resolves as `FLAG_NOT_FOUND` (caller's default, `reason = Error`). */
  def clearFlags: UIO[Unit] =
    ZIO.succeed(flags.clear())

  /** Set the provider status. For async test layers, setting Ready releases the init latch and waits for the Java SDK's
    * background `initialize()` thread to complete, ensuring the provider is fully ready before returning.
    *
    * The wait runs on the blocking pool with a bounded timeout: an unbounded `await` on a runtime thread could starve
    * the ZIO runtime (and hang the whole suite) if the SDK never delivers PROVIDER_READY. On timeout this dies with a
    * descriptive error instead — a fast, diagnosable failure.
    */
  def setStatus(status: ProviderStatus): UIO[Unit] =
    statusRef.set(status) *> (status match {
      case ProviderStatus.Ready =>
        ZIO.succeed {
          state.set(ProviderState.READY)
          initLatch.foreach(_.countDown())
        } *> TestFeatureProvider.awaitLatch(initDone, "PROVIDER_READY after setStatus(Ready)")
      case ProviderStatus.NotReady     => ZIO.succeed(state.set(ProviderState.NOT_READY))
      case ProviderStatus.Error        => ZIO.succeed(state.set(ProviderState.ERROR))
      case ProviderStatus.Stale        => ZIO.succeed(state.set(ProviderState.STALE))
      case ProviderStatus.Fatal        => ZIO.succeed(state.set(ProviderState.FATAL))
      case ProviderStatus.ShuttingDown => ZIO.succeed(state.set(ProviderState.NOT_READY))
    })

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
    eventsHub.publish(event).unit *>
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

  /** The domain this provider was registered under, as the SDK delivered it to `initialize(ctx, domain)`.
    *
    * `None` before initialization, and `None` for the default (domain-less) provider — the SDK passes null there.
    */
  def boundDomain: UIO[Option[String]] = ZIO.succeed(boundDomainRef.get())

  /** All evaluations made so far, each captured context converted to the library's [[EvaluationContext]] so you can
    * assert on `.targetingKey` / `.getString(...)` etc. directly instead of the Java SDK type. Use
    * [[getRawEvaluations]] if you need the raw `dev.openfeature.sdk.EvaluationContext`.
    */
  def getEvaluations: UIO[List[(String, EvaluationContext)]] =
    ZIO.succeed(evaluations.asScala.toList.map { case (key, ctx) => (key, ContextConverter.fromOpenFeature(ctx)) })

  /** All evaluations made so far, with each captured context as the raw Java SDK `EvaluationContext`. */
  def getRawEvaluations: UIO[List[(String, OFEvaluationContext)]] =
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

  /** Check if a flag was evaluated, by its definition (#351). */
  def wasEvaluated[A](flag: FlagDef[A]): UIO[Boolean] =
    wasEvaluated(flag.key)

  /** Count how many times a flag was evaluated, by its definition (#351). */
  def evaluationCount[A](flag: FlagDef[A]): UIO[Int] =
    evaluationCount(flag.key)

  /** Get the events hub for streaming. */
  def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(eventsHub)

  /** Provider metadata (ZIO-style). */
  val metadata: ProviderMetadata = ProviderMetadata(name, "1.0.0")

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

  /** Set the probability (0.0 to 1.0) that each evaluation fails randomly. Values outside the range — including NaN —
    * are clamped to [0.0, 1.0]; NaN is treated as 0.0 to avoid silently disabling failure injection.
    */
  def setFailureProbability(p: Double): UIO[Unit] =
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(failureProbability = TestFeatureProvider.clampProbability(p)))).unit

  /** Reset all behavior controls to defaults. */
  def clearBehavior: UIO[Unit] =
    ZIO.succeed(behaviorRef.set(BehaviorConfig())).unit
}

object TestFeatureProvider {

  /** The default `initDelay` of `asyncReadyLayer`. */
  val DefaultInitDelay: Duration = 100.millis

  private def fromOpenFeatureTracking(details: OFTracking): TrackingEventDetails =
    TrackingEventDetails(
      value = if (details.getValue.isPresent) Some(details.getValue.get.doubleValue) else None,
      attributes = details.asMap.asScala.toMap.flatMap { case (k, v) => ValueBridge.valueToAny(v).map(k -> _) }
    )

  /** Upper bound on waits for Java SDK event delivery. Generous — the SDK dispatch is normally milliseconds — but
    * bounded so a missing event fails the test quickly and descriptively instead of hanging the suite.
    */
  private val SdkEventTimeout: Duration = 30.seconds

  /** Await a CountDownLatch on the blocking pool with a bounded timeout; dies descriptively on expiry. */
  private def awaitLatch(latch: Option[CountDownLatch], what: String): UIO[Unit] =
    ZIO.foreachDiscard(latch) { l =>
      ZIO.attemptBlocking {
        if (!l.await(SdkEventTimeout.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
          throw new IllegalStateException(
            s"TestFeatureProvider timed out after $SdkEventTimeout waiting for $what; " +
              "the Java SDK never delivered the event"
          )
      }.orDie
    }

  /** Clamp a failure probability to [0.0, 1.0], mapping NaN to 0.0. */
  private def clampProbability(p: Double): Double =
    if (p.isNaN) 0.0 else p.max(0.0).min(1.0)

  // Behavior Controls — types

  private[testkit] case class BehaviorConfig(
    delay: Option[Duration] = None,
    errorMode: Option[ErrorMode] = None,
    failureProbability: Double = 0.0
  )

  /** Simulated provider failures, thrown for every evaluation while set; to model a single absent key, leave it unset.
    */
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

  /** Aspect that makes evaluations fail randomly with the given probability, clamped to [0.0, 1.0] exactly as
    * `setFailureProbability` clamps it (NaN is 0.0).
    */
  def withFailureProbability(p: Double): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] = {
    val clamped = clampProbability(p)
    behaviorAspect(_.copy(failureProbability = clamped))
  }

  /** Applies `modify` for the test and afterwards restores the config it found — not the defaults — so a test-level
    * aspect nested in a suite-level one leaves the suite's settings in place (#457).
    */
  private def behaviorAspect(
    modify: BehaviorConfig => BehaviorConfig
  ): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    TestAspect.aroundWith(
      ZIO.serviceWith[TestFeatureProvider](tp => tp.behaviorRef.getAndUpdate(c => modify(c)))
    )(previous => ZIO.serviceWith[TestFeatureProvider](_.behaviorRef.set(previous)))

  // Flag overrides — TestAspects (#458)

  /** Aspect that pins these typed flag values for the duration of the test.
    *
    * Restores '''per key''': before the test each overridden key's current value (or its absence) is saved, and after
    * the test that value is put back, or the key removed if it was absent. Keys the aspect does not name are never
    * touched, so a test's own `setFlag` on another key survives, and a test-level aspect nested in a suite-level one
    * returns the suite's value afterwards. A `setFlag` on an overridden key inside the test is undone by the restore.
    *
    * '''Sharing caveat''': with `provideShared`, every test sees the same provider, so flag aspects on tests that run
    * concurrently race each other. Use `provide` per test, or `TestAspect.sequential` on the suite.
    *
    * Like the typed `layer`, two overrides for the same key are rejected when the aspect is built.
    */
  def withFlags(overrides: FlagOverride*): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    flagAspect(toFlagMap(overrides))

  /** Untyped twin of [[withFlags(overrides*]]: pins these key/value pairs for the test and restores each key after. */
  def withFlags(flags: Map[String, Any]): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    flagAspect(flags)

  /** Aspect that pins one typed flag for the test; see [[withFlags(overrides*]] for the restore and sharing rules. */
  def withFlag[A](flag: FlagDef[A], value: A): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    withFlags(FlagOverride.Ops(flag) := value)

  /** Aspect that pins one flag key for the test; see [[withFlags(overrides*]] for the restore and sharing rules. */
  def withFlag(key: String, value: Any): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    withFlags(Map(key -> value))

  private def flagAspect(overrides: Map[String, Any]): TestAspect[Nothing, TestFeatureProvider, Nothing, Any] =
    TestAspect.aroundWith(
      // `put` returns the previous value atomically, so the save and the set cannot interleave with another writer.
      ZIO.serviceWith[TestFeatureProvider](tp => overrides.map { case (k, v) => k -> Option(tp.flags.put(k, v)) })
    )(previous =>
      ZIO.serviceWith[TestFeatureProvider] { tp =>
        previous.foreach {
          case (k, Some(old)) => tp.flags.put(k, old)
          case (k, None)      => tp.flags.remove(k)
        }
      }
    )

  /** The metadata name every factory except [[makeNamed]] reports. */
  final val DefaultName: String = "TestFeatureProvider"

  /** Create a new TestFeatureProvider with no initial flags. */
  def make: UIO[TestFeatureProvider] =
    make(Map.empty[String, Any])

  /** Create a new TestFeatureProvider with initial flags. */
  def make(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    makeReady(DefaultName, initialFlags)

  /** Like [[make]], but the provider reports `name` as its metadata name instead of [[DefaultName]].
    *
    * The event-identity guard behind `FeatureFlags.setProvider` compares the old and new provider's names, so a swap
    * between two identically-named instances is invisible to it. A `MultiProvider` keeps same-named providers since
    * Java SDK 1.23.0 (earlier SDKs kept only the last of a name). Give each one a distinct name when swapping:
    *
    * {{{
    * for {
    *   primary  <- TestFeatureProvider.makeNamed("primary")
    *   fallback <- TestFeatureProvider.makeNamed("fallback", Map("flag" -> true))
    *   ff       <- FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(primary, fallback))).build
    * } yield ff.get[FeatureFlags]
    * }}}
    */
  def makeNamed(name: String, initialFlags: Map[String, Any] = Map.empty): UIO[TestFeatureProvider] =
    makeReady(name, initialFlags)

  private def makeReady(name: String, initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    build(name, initialFlags, Start.Ready)

  /** How a provider starts: which status it reports and which latches it carries. */
  sealed private trait Start extends Product with Serializable
  private object Start {

    /** Ready, no latches — the plain [[make]] provider. */
    case object Ready extends Start

    /** Ready, with an `initDone` latch for the sync layer's `PROVIDER_READY` replay handshake. */
    case object ReadyWithHandshake extends Start

    /** NotReady; `initialize()` blocks on `initLatch` until `setStatus(Ready)`, which then waits on `initDone`. */
    case object NotReady extends Start
  }

  private def build(name: String, initialFlags: Map[String, Any], start: Start): UIO[TestFeatureProvider] = {
    val ready = start != Start.NotReady
    for {
      eventsHub <- Hub.unbounded[ProviderEvent]
      statusRef <- SubscriptionRef.make[ProviderStatus](if (ready) ProviderStatus.Ready else ProviderStatus.NotReady)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        new TestFeatureProvider(
          flags,
          new AtomicReference[ProviderState](if (ready) ProviderState.READY else ProviderState.NOT_READY),
          new CopyOnWriteArrayList[(String, OFEvaluationContext)](),
          eventsHub,
          statusRef,
          initLatch = if (start == Start.NotReady) Some(new CountDownLatch(1)) else None,
          initDone = if (start == Start.Ready) None else Some(new CountDownLatch(1)),
          name = name
        )
      }
    } yield provider
  }

  // Typed fixture factories (#351)
  //
  // Every untyped factory gets a `FlagOverride*` twin, so reaching for another factory after learning `layer` does not
  // suddenly drop back to `Map[String, Any]`. Each delegates through `toFlagMap`, so typed and untyped fixtures build
  // exactly the same provider — the only difference is that the typed route checked the value against the flag's
  // declared type and encoded it on the way in.

  private def toFlagMap(overrides: Seq[FlagOverride]): Map[String, Any] = {
    // Rejected rather than silently last-wins: two overrides for one key means two `FlagDef`s share it, most likely at
    // different types, which is a fixture bug the test would otherwise run straight past. `:=` already throws for a
    // codec problem, so failing here is consistent.
    val duplicates = overrides.groupBy(_.key).collect { case (k, os) if os.sizeIs > 1 => k }
    require(
      duplicates.isEmpty,
      s"Duplicate flag overrides for ${duplicates.mkString("'", "', '", "'")} — each flag may be pinned once per fixture."
    )
    overrides.map(o => o.key -> o.encoded).toMap
  }

  /** Create a new TestFeatureProvider from typed overrides. */
  def make(overrides: FlagOverride*): UIO[TestFeatureProvider] =
    make(toFlagMap(overrides))

  /** Create a FeatureFlags layer from typed overrides. */
  def layer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(toFlagMap(overrides))

  /** Same as [[layer(overrides*]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use layer(...): it no longer needs a Scope, so this twin is identical", "1.2.0")
  def scopedLayer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(toFlagMap(overrides))

  /** Create an async-init layer from typed overrides. */
  def asyncLayer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncLayer(toFlagMap(overrides))

  /** Create just the TestFeatureProvider layer from typed overrides. */
  def providerLayer(overrides: FlagOverride*): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(toFlagMap(overrides)))

  /** Same as [[asyncLayer(overrides*]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use asyncLayer(...): it no longer needs a Scope, so this twin is identical", "1.2.0")
  def scopedAsyncLayer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncLayer(toFlagMap(overrides))

  /** Create an auto-transitioning async layer from typed overrides.
    *
    * The delay is explicit here, unlike its defaulted counterpart: the untyped `asyncReadyLayer` defaults both of its
    * parameters, so a bare `asyncReadyLayer()` is legal today and a plain varargs overload would make that call
    * ambiguous. Requiring the delay keeps every existing call compiling.
    *
    * The parameter is named `delay` rather than `initDelay` deliberately — named-argument resolution filters
    * alternatives by parameter name, so reusing `initDelay` here would make `asyncReadyLayer(initDelay = 200.millis)`
    * ambiguous between this and the defaulted `(Map, Duration)` form.
    */
  def asyncReadyLayer(
    delay: Duration,
    overrides: FlagOverride*
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncReadyLayer(toFlagMap(overrides), delay)

  /** Create a FeatureFlags layer from TestFeatureProvider. */
  def layer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(Map.empty[String, Any])

  /** Create a FeatureFlags layer with initial flags.
    *
    * Each invocation creates an isolated OpenFeatureAPI instance and a unique domain, ensuring full test isolation.
    * Tests using this layer can safely run in parallel without cross-test event contamination.
    */
  def layer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    isolatedLayer(flags, LayerMode.Sync("initial PROVIDER_READY replay during layer construction"))

  /** How an isolated layer connects its provider to the SDK. */
  sealed private trait LayerMode extends Product with Serializable {
    def domainPrefix: String
  }
  private object LayerMode {

    /** Blocking `fromProviderWithDomain`, then wait for the SDK's initial `PROVIDER_READY` replay. The Java SDK
      * dispatches that event asynchronously when handlers are registered on an already-ready provider; waiting for it
      * (a deterministic handshake, formerly a fixed 50ms sleep) keeps a `setStatus()` made straight after the build
      * from being overwritten by the late replay. `what` names the wait in the timeout message.
      */
    final case class Sync(what: String) extends LayerMode { val domainPrefix = "test-" }

    /** Non-blocking `fromProviderWithDomainAsync`; the test drives the transition to Ready. */
    case object Async extends LayerMode { val domainPrefix = "test-async-" }

    /** Like [[Async]], but a scoped fiber sets the provider Ready after `delay` on the ZIO clock. */
    final case class AsyncAutoReady(delay: Duration) extends LayerMode { val domainPrefix = "test-async-ready-" }
  }

  /** Register `provider` with a fresh isolated `OpenFeatureAPI` under a unique domain, so tests never share SDK state
    * or see each other's events. `onReady` is the latch the event bridge counts down on `PROVIDER_READY`.
    */
  private def connect(
    provider: TestFeatureProvider,
    mode: LayerMode,
    onReady: Option[CountDownLatch]
  ): ZIO[Scope, Throwable, FeatureFlags] =
    for {
      api    <- ZIO.succeed(OpenFeatureAPI.createIsolated())
      domain <- ZIO.succeed(s"${mode.domainPrefix}${java.util.UUID.randomUUID()}")
      layer = mode match {
        case LayerMode.Sync(_) =>
          FeatureFlags.fromProviderWithDomain(provider, domain, provider.statusRef, api = Some(api), onReady = onReady)
        case _ =>
          FeatureFlags
            .fromProviderWithDomainAsync(provider, domain, provider.statusRef, api = Some(api), onReady = onReady)
      }
      featureFlags <- layer.build.map(_.get[FeatureFlags])
      _ <- mode match {
        case LayerMode.Sync(what) => awaitLatch(onReady, what)
        case LayerMode.Async      => ZIO.unit
        case LayerMode.AsyncAutoReady(delay) =>
          (ZIO.sleep(delay) *> provider.setStatus(ProviderStatus.Ready)).forkScoped.unit
      }
    } yield featureFlags

  /** A fresh provider holding `flags`, connected through [[connect]], exposed with its `FeatureFlags`. */
  private def isolatedLayer(
    flags: Map[String, Any],
    mode: LayerMode
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] = {
    val start = mode match {
      case LayerMode.Sync(_) => Start.ReadyWithHandshake
      case _                 => Start.NotReady
    }
    ZLayer
      .scoped {
        for {
          testProvider <- build(DefaultName, flags, start)
          featureFlags <- connect(testProvider, mode, testProvider.initDone)
        } yield (testProvider, featureFlags)
      }
      .flatMap { env =>
        val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
      }
  }

  /** Create just the TestFeatureProvider layer (without FeatureFlags). */
  def providerLayer: ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make)

  /** Create just the TestFeatureProvider layer with initial flags. */
  def providerLayer(flags: Map[String, Any]): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(flags))

  /** Create a FeatureFlags layer from an existing TestFeatureProvider.
    *
    * Like [[layer]], it returns only after the SDK's initial `PROVIDER_READY` replay has arrived, so a `setStatus` made
    * straight after the build is not overwritten by that late event. The latch is the layer's own: a provider from
    * [[make]] carries none, and giving it one would make its `setStatus(Ready)` wait for an event no layer delivers.
    */
  def layerFrom(provider: TestFeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      ZIO
        .succeed(new CountDownLatch(1))
        .flatMap(replayed =>
          connect(provider, LayerMode.Sync("initial PROVIDER_READY replay during layerFrom"), Some(replayed))
        )
    }

  /** Same as [[layer]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use layer: it no longer needs a Scope, so this twin is identical", "1.2.0")
  val scopedLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    TestFeatureProvider.layer

  /** Same as [[layer(flags:Map*]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use layer: it no longer needs a Scope, so this twin is identical", "1.2.0")
  def scopedLayer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    TestFeatureProvider.layer(flags)

  /** Create a FeatureFlags layer with async (non-blocking) initialization.
    *
    * The provider starts in NotReady state. Use `setStatus(ProviderStatus.Ready)` or emit a `ProviderEvent.Ready` event
    * to simulate the provider becoming ready. Evaluations will fail with `ProviderNotReady` until then.
    */
  def asyncLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncLayer(Map.empty[String, Any])

  /** Create a FeatureFlags layer with async initialization and initial flags.
    *
    * The provider starts in NotReady state and does not auto-initialize. Call `setStatus(ProviderStatus.Ready)` to
    * simulate the provider becoming ready. Evaluations will fail with `ProviderNotReady` until then.
    */
  def asyncLayer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    isolatedLayer(flags, LayerMode.Async)

  /** Create a TestFeatureProvider that starts in NotReady state.
    *
    * The provider's `initialize()` blocks until `setStatus(ProviderStatus.Ready)` is called, simulating a
    * slow-connecting provider (e.g., one that needs to establish a network connection).
    */
  private[testkit] def makeNotReady(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    build(DefaultName, initialFlags, Start.NotReady)

  /** Create a FeatureFlags layer with async initialization that auto-transitions to Ready.
    *
    * Like `asyncLayer`, the provider starts in NotReady state. After `initDelay`, it automatically transitions to Ready
    * without manual `setStatus` calls. Useful for simulating a real async provider (e.g., connecting to a remote
    * service) without needing to manage the state transition in tests.
    *
    * The delay runs on the ZIO clock, so under zio-test's `TestClock` it never elapses by itself: advance it with
    * `TestClock.adjust(initDelay)` rather than `ZIO.sleep`, which would hang.
    */
  def asyncReadyLayer(
    flags: Map[String, Any] = Map.empty,
    initDelay: Duration = TestFeatureProvider.DefaultInitDelay
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    isolatedLayer(flags, LayerMode.AsyncAutoReady(initDelay))

  /** Same as [[asyncLayer]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use asyncLayer: it no longer needs a Scope, so this twin is identical", "1.2.0")
  val scopedAsyncLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    TestFeatureProvider.asyncLayer

  /** Same as [[asyncLayer(flags:Map*]]; kept for source compatibility from before layers stopped needing a `Scope`. */
  @deprecated("Use asyncLayer: it no longer needs a Scope, so this twin is identical", "1.2.0")
  def scopedAsyncLayer(flags: Map[String, Any]): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    TestFeatureProvider.asyncLayer(flags)

}
