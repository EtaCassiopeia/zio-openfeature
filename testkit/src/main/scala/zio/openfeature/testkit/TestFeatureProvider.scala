package zio.openfeature.testkit

import zio._
import zio.stream._
import zio.openfeature._
import zio.openfeature.internal.{ContextConverter, ErrorCodeConverter, ProviderEvaluations}
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

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue)(raw => java.lang.Boolean.valueOf(raw.asInstanceOf[Boolean]))
  }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue)(_.toString)
  }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue) {
      case i: Int    => java.lang.Integer.valueOf(i)
      case l: Long   => java.lang.Integer.valueOf(l.toInt)
      case d: Double => java.lang.Integer.valueOf(d.toInt)
      case other     => java.lang.Integer.valueOf(other.toString.toInt)
    }
  }

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue) {
      case l: Long   => java.lang.Long.valueOf(l)
      case i: Int    => java.lang.Long.valueOf(i.toLong)
      case d: Double => java.lang.Long.valueOf(d.toLong)
      case other     => java.lang.Long.valueOf(other.toString.toLong)
    }
  }

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue) {
      case d: Double => java.lang.Double.valueOf(d)
      case f: Float  => java.lang.Double.valueOf(f.toDouble)
      case i: Int    => java.lang.Double.valueOf(i.toDouble)
      case l: Long   => java.lang.Double.valueOf(l.toDouble)
      case other     => java.lang.Double.valueOf(other.toString.toDouble)
    }
  }

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] = {
    applyBehavior()
    evaluations.add((key, context))
    evaluate(key, defaultValue)(anyToValue)
  }

  private def anyToValue(any: Any): Value = any match {
    // Option is unwrapped before the `other.toString` fallback below, and a `Value` passes straight through. Without
    // these, seeding a flag with an encoder's output — `setFlag(key, FlagType[Rollout].encode(r))`, the natural way
    // to test a derived product — sends an Option-valued field as the literal string "Some(x)". Kept in step with
    // the equivalent helper in `FeatureFlagsLive`.
    case v: Value      => v
    case Some(inner)   => anyToValue(inner)
    case None          => new Value()
    case b: Boolean    => new Value(b)
    case s: String     => new Value(s)
    case i: Int        => new Value(i)
    case l: Long       => new Value(l)
    case d: Double     => new Value(d)
    case f: Float      => new Value(f.toDouble)
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
  def setFailureProbability(p: Double): UIO[Unit] = {
    val clamped = if (p.isNaN) 0.0 else p.max(0.0).min(1.0)
    ZIO.succeed(behaviorRef.updateAndGet(_.copy(failureProbability = clamped))).unit
  }

  /** Reset all behavior controls to defaults. */
  def clearBehavior: UIO[Unit] =
    ZIO.succeed(behaviorRef.set(BehaviorConfig())).unit
}

object TestFeatureProvider {

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
    * Two things key providers by that name, so two identically-named instances cannot be told apart:
    *   - a `MultiProvider` chain keeps only the '''last''' provider of a given name (the SDK logs the collision at INFO
    *     and moves on), so a chain of two default-named test providers is a chain of one;
    *   - the event-identity guard behind `FeatureFlags.setProvider` compares the old and new provider's names.
    *
    * Give each one a distinct name when a test depends on either:
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
    for {
      eventsHub <- Hub.unbounded[ProviderEvent]
      statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(
          flags,
          state,
          evaluations,
          eventsHub,
          statusRef,
          initLatch = None,
          initDone = None,
          name = name
        )
      }
    } yield provider

  // Typed fixture factories (#351)
  //
  // Every untyped factory gets a `FlagOverride*` twin, so reaching for `scopedLayer` after learning `layer` does not
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
  def layer(overrides: FlagOverride*): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(toFlagMap(overrides))

  /** Create a self-contained (scope-providing) layer from typed overrides. */
  def scopedLayer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    scopedLayer(toFlagMap(overrides))

  /** Create an async-init layer from typed overrides. */
  def asyncLayer(overrides: FlagOverride*): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncLayer(toFlagMap(overrides))

  /** Create just the TestFeatureProvider layer from typed overrides. */
  def providerLayer(overrides: FlagOverride*): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(toFlagMap(overrides)))

  /** Create a self-contained async layer from typed overrides. */
  def scopedAsyncLayer(overrides: FlagOverride*): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> asyncLayer(toFlagMap(overrides))

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
  ): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    asyncReadyLayer(toFlagMap(overrides), delay)

  /** Create a FeatureFlags layer from TestFeatureProvider. */
  def layer: ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    layer(Map.empty[String, Any])

  /** Create a FeatureFlags layer with initial flags.
    *
    * Each invocation creates an isolated OpenFeatureAPI instance and a unique domain, ensuring full test isolation.
    * Tests using this layer can safely run in parallel without cross-test event contamination.
    */
  def layer(flags: Map[String, Any]): ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
    ZLayer
      .scoped {
        for {
          testProvider <- makeReadyWithInitDone(flags)
          api    = OpenFeatureAPI.createIsolated()
          domain = s"test-${java.util.UUID.randomUUID()}"
          featureFlags <- FeatureFlags
            .fromProviderWithDomain(
              testProvider,
              domain,
              testProvider.statusRef,
              api = Some(api),
              onReady = testProvider.initDone
            )
            .build
            .map(_.get)
          // The Java SDK dispatches an initial PROVIDER_READY event asynchronously when handlers are
          // registered on an already-ready provider. The event bridge counts down `initDone` when that
          // replay arrives — a deterministic handshake (formerly a fixed 50ms sleep) ensuring subsequent
          // setStatus() calls in tests are not overwritten by the late replay.
          _ <- awaitLatch(testProvider.initDone, "initial PROVIDER_READY replay during layer construction")
        } yield (testProvider, featureFlags)
      }
      .flatMap { env =>
        val (testProvider, featureFlags) = env.get[(TestFeatureProvider, FeatureFlags)]
        ZLayer.succeed(testProvider) ++ ZLayer.succeed(featureFlags)
      }

  /** Like [[make]], but in Ready state with an `initDone` latch wired for the sync layer's PROVIDER_READY handshake. */
  private def makeReadyWithInitDone(initialFlags: Map[String, Any]): UIO[TestFeatureProvider] =
    for {
      eventsHub <- Hub.unbounded[ProviderEvent]
      statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(
          flags,
          state,
          evaluations,
          eventsHub,
          statusRef,
          initLatch = None,
          initDone = Some(new CountDownLatch(1)),
          name = DefaultName
        )
      }
    } yield provider

  /** Create just the TestFeatureProvider layer (without FeatureFlags). */
  def providerLayer: ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make)

  /** Create just the TestFeatureProvider layer with initial flags. */
  def providerLayer(flags: Map[String, Any]): ULayer[TestFeatureProvider] =
    ZLayer.fromZIO(make(flags))

  /** Create a FeatureFlags layer from an existing TestFeatureProvider. */
  def layerFrom(provider: TestFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPI.createIsolated()
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
    asyncLayer(Map.empty[String, Any])

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
          api    = OpenFeatureAPI.createIsolated()
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
      statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
      provider <- ZIO.succeed {
        val flags = new ConcurrentHashMap[String, Any]()
        initialFlags.foreach { case (k, v) => flags.put(k, v) }
        val state       = new AtomicReference[ProviderState](ProviderState.NOT_READY)
        val evaluations = new CopyOnWriteArrayList[(String, OFEvaluationContext)]()
        new TestFeatureProvider(
          flags,
          state,
          evaluations,
          eventsHub,
          statusRef,
          initLatch = Some(new CountDownLatch(1)),
          initDone = Some(new CountDownLatch(1)),
          name = DefaultName
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
          api    = OpenFeatureAPI.createIsolated()
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
