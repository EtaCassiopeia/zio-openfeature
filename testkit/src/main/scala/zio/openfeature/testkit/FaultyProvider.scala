package zio.openfeature.testkit

import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._

import zio._
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails => OFTracking,
  Value
}

/** What a [[FaultyProvider]] does around one call of its delegate. */
sealed trait Fault

object Fault {

  /** Call the delegate and return its answer. */
  case object Succeed extends Fault

  /** Throw `error` without calling the delegate. */
  final case class Throw(error: Throwable) extends Fault

  /** Block the calling thread for `duration`, then apply `andThen`. */
  final case class Delay(duration: Duration, andThen: Fault = Succeed) extends Fault

  /** Block until [[FaultyProvider.release]], then call the delegate. `shutdown()` does NOT unblock it: this is a
    * provider whose `initialize()` ignores shutdown, the shape that wedged the layer.
    */
  case object HangUntilReleased extends Fault

  /** Block until `shutdown()` is called (or [[FaultyProvider.release]]), then call the delegate: the common "wait for
    * the first config, shutdown aborts the wait" shape.
    */
  case object HangUntilShutdown extends Fault

  /** Emit `event` through the provider's event channel (without waiting for delivery), then apply `andThen`. From
    * inside `initialize()` this is how a provider reports `PROVIDER_ERROR` while starting up.
    */
  final case class Emit(
    event: OFProviderEvent,
    details: ProviderEventDetails = ProviderEventDetails.builder().build(),
    andThen: Fault = Succeed
  ) extends Fault
}

/** A [[FaultyProvider]] method a script can target: every method of OpenFeature's `FeatureProvider` and `EventProvider`
  * except the `emit*` family. Each flag getter is its own op.
  */
sealed trait Op

object Op {
  case object GetMetadata      extends Op
  case object GetProviderHooks extends Op
  case object GetState         extends Op

  /** Both `initialize(ctx)` and `initialize(ctx, domain)`; the SDK calls the second. */
  case object Initialize      extends Op
  case object Shutdown        extends Op
  case object IsDomainScoped  extends Op
  case object Track           extends Op
  case object EvaluateBoolean extends Op
  case object EvaluateString  extends Op
  case object EvaluateInteger extends Op
  case object EvaluateLong    extends Op
  case object EvaluateDouble  extends Op
  case object EvaluateObject  extends Op

  val values: Chunk[Op] = Chunk(
    GetMetadata,
    GetProviderHooks,
    GetState,
    Initialize,
    Shutdown,
    IsDomainScoped,
    Track,
    EvaluateBoolean,
    EvaluateString,
    EvaluateInteger,
    EvaluateLong,
    EvaluateDouble,
    EvaluateObject
  )
}

/** A fault-injecting wrapper around any OpenFeature provider, for lifecycle and failure tests.
  *
  * Each [[Op]] has a script of [[Fault]]s, consumed one per call; the last entry repeats forever, so a script never
  * runs out mid-test. An op with no script delegates. Every call is recorded in [[calls]].
  *
  * {{{
  * for {
  *   faulty <- FaultyProvider.scoped(Map("flag" -> true))
  *   _      <- faulty.script(Op.Initialize, Fault.Throw(new RuntimeException("boom")), Fault.Succeed)
  *   // the first initialize() fails, the second and later ones succeed
  * } yield ()
  * }}}
  *
  * A hang ([[Fault.HangUntilReleased]], [[Fault.HangUntilShutdown]]) parks the calling thread; [[release]] unparks them
  * all. Use [[FaultyProvider.scoped]] so the scope's finalizer releases whatever is still parked: a test then never
  * leaks a blocked thread, whatever it asserts.
  *
  * The wrapper is an `EventProvider` in its own right: events reach the SDK through it (`emit`, or a [[Fault.Emit]]
  * script), not through the delegate, whose own events stay unobserved.
  *
  * @tparam P
  *   the delegate's type, so `make`'s [[TestFeatureProvider]] stays reachable for `setFlag`
  */
final class FaultyProvider[+P <: FeatureProvider] private (val delegate: P, name: String) extends EventProvider {

  import FaultyProvider.{Call, Outcome}

  private val scripts            = new ConcurrentHashMap[Op, AtomicReference[Chunk[Fault]]]()
  private val recorded           = new ConcurrentLinkedQueue[Call]()
  private val parked             = ConcurrentHashMap.newKeySet[CountDownLatch]()
  private val parkedTillShutdown = ConcurrentHashMap.newKeySet[CountDownLatch]()
  @volatile private var closed   = false

  // Guarded by `entryLock`: how many calls of each op have started, for `awaitEntered`.
  private val entryLock = new Object
  private val entries   = scala.collection.mutable.Map.empty[Op, Int]

  /** Replace `op`'s script. Calls consume the faults in order and the last one repeats; no faults means delegate. */
  def script(op: Op, faults: Fault*): UIO[Unit] =
    ZIO.succeed {
      if (faults.isEmpty) scripts.remove(op)
      else scripts.put(op, new AtomicReference(Chunk.fromIterable(faults)))
      ()
    }

  /** Every completed call, in completion order. A hung call appears once it is released. */
  def calls: UIO[Chunk[Call]] = ZIO.succeed(Chunk.fromIterable(recorded.asScala))

  /** The completed calls of one op, in completion order. */
  def calls(op: Op): UIO[Chunk[Call]] = calls.map(_.filter(_.op == op))

  /** How many calls of `op` have started, including ones still running or parked. */
  def entered(op: Op): UIO[Int] = ZIO.succeed(entryLock.synchronized(entries.getOrElse(op, 0)))

  /** Wait until at least `n` calls of `op` have started, e.g. until `initialize()` is parked on a hang. */
  def awaitEntered(op: Op, n: Int = 1): UIO[Unit] =
    ZIO.attemptBlockingInterrupt {
      entryLock.synchronized {
        while (entries.getOrElse(op, 0) < n) entryLock.wait()
      }
    }.orDie

  /** Unpark every call currently parked on a hang. Later hangs park again. */
  def release: UIO[Unit] = ZIO.succeed(openAll(parked))

  /** Release everything and make every later hang return at once. The finalizer of [[FaultyProvider.scoped]]. */
  private[testkit] def close: UIO[Unit] = ZIO.succeed { closed = true; openAll(parked) }

  private def openAll(latches: java.util.Set[CountDownLatch]): Unit =
    latches.asScala.foreach(_.countDown())

  private def nextFault(op: Op): Fault =
    Option(scripts.get(op)) match {
      case None => Fault.Succeed
      case Some(ref) =>
        ref.getAndUpdate(s => if (s.size > 1) s.tail else s).headOption.getOrElse(Fault.Succeed)
    }

  private def hang(onShutdown: Boolean): Unit = {
    val latch = new CountDownLatch(1)
    parked.add(latch)
    if (onShutdown) parkedTillShutdown.add(latch)
    if (closed) latch.countDown() // checked after registering, so a concurrent close cannot be missed
    try latch.await()
    finally {
      parked.remove(latch)
      parkedTillShutdown.remove(latch)
      ()
    }
  }

  private def applyFault[A](fault: Fault, body: => A): A = fault match {
    case Fault.Succeed                     => body
    case Fault.Throw(error)                => throw error
    case Fault.Delay(duration, andThen)    => Thread.sleep(duration.toMillis); applyFault(andThen, body)
    case Fault.HangUntilReleased           => hang(onShutdown = false); body
    case Fault.HangUntilShutdown           => hang(onShutdown = true); body
    case Fault.Emit(event, details, after) => emit(event, details); applyFault(after, body)
  }

  private def run[A](op: Op, flagKey: Option[String], arguments: Any*)(body: => A): A = {
    val args      = Chunk.fromIterable(arguments)
    val startedAt = Instant.now()
    val thread    = Thread.currentThread().getName
    entryLock.synchronized {
      entries.update(op, entries.getOrElse(op, 0) + 1)
      entryLock.notifyAll()
    }
    try {
      val result = applyFault(nextFault(op), body)
      recorded.add(Call(op, flagKey, thread, startedAt, Outcome.Returned, args))
      result
    } catch {
      case error: Throwable =>
        recorded.add(Call(op, flagKey, thread, startedAt, Outcome.Threw(error), args))
        throw error
    }
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = run(Op.GetMetadata, None)(new Metadata { override def getName: String = name })

  override def getProviderHooks = run(Op.GetProviderHooks, None)(delegate.getProviderHooks)

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = run(Op.GetState, None)(delegate.getState)

  override def initialize(context: OFEvaluationContext): Unit =
    run(Op.Initialize, None, context)(delegate.initialize(context))

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    run(Op.Initialize, None, context, domain)(delegate.initialize(context, domain))

  /** Unparks every [[Fault.HangUntilShutdown]] first, then applies the [[Op.Shutdown]] script. */
  override def shutdown(): Unit = {
    openAll(parkedTillShutdown)
    run(Op.Shutdown, None)(delegate.shutdown())
  }

  override def isDomainScoped(): Boolean = run(Op.IsDomainScoped, None)(delegate.isDomainScoped())

  override def track(eventName: String, context: OFEvaluationContext, details: OFTracking): Unit =
    run(Op.Track, Some(eventName), eventName, context, details)(delegate.track(eventName, context, details))

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    run(Op.EvaluateBoolean, Some(key), key, defaultValue, ctx)(delegate.getBooleanEvaluation(key, defaultValue, ctx))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    run(Op.EvaluateString, Some(key), key, defaultValue, ctx)(delegate.getStringEvaluation(key, defaultValue, ctx))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    run(Op.EvaluateInteger, Some(key), key, defaultValue, ctx)(delegate.getIntegerEvaluation(key, defaultValue, ctx))

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    run(Op.EvaluateLong, Some(key), key, defaultValue, ctx)(delegate.getLongEvaluation(key, defaultValue, ctx))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    run(Op.EvaluateDouble, Some(key), key, defaultValue, ctx)(delegate.getDoubleEvaluation(key, defaultValue, ctx))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    run(Op.EvaluateObject, Some(key), key, defaultValue, ctx)(delegate.getObjectEvaluation(key, defaultValue, ctx))
}

object FaultyProvider {

  /** The metadata name of a provider built without an explicit one. */
  final val DefaultName: String = "FaultyProvider"

  /** How a recorded call ended. */
  sealed trait Outcome

  object Outcome {
    case object Returned                     extends Outcome
    final case class Threw(error: Throwable) extends Outcome
  }

  /** One completed call: the op, the flag key (evaluations) or event name (`track`), the calling thread's name, when
    * the call started, how it ended, and the arguments it received, in parameter order (`initialize(ctx)` records one,
    * `initialize(ctx, domain)` two; no-argument methods record none). [[WrapperLaws]] compares these.
    */
  final case class Call(
    op: Op,
    flagKey: Option[String],
    thread: String,
    startedAt: Instant,
    outcome: Outcome,
    arguments: Chunk[Any]
  )

  /** Wrap `delegate`. The wrapper reports `name` as its metadata name. */
  def wrap[P <: FeatureProvider](delegate: P, name: String = DefaultName): UIO[FaultyProvider[P]] =
    ZIO.succeed(new FaultyProvider(delegate, name))

  /** Wrap a fresh [[TestFeatureProvider]] holding `flags`; reach it through `delegate` to change flags. */
  def make(
    flags: Map[String, Any] = Map.empty,
    name: String = DefaultName
  ): UIO[FaultyProvider[TestFeatureProvider]] =
    TestFeatureProvider.make(flags).flatMap(wrap(_, name))

  /** [[make]], with a finalizer that releases every hang still parked when the scope closes and makes later hangs
    * return at once, so no test leaks a blocked thread.
    */
  def scoped(
    flags: Map[String, Any] = Map.empty,
    name: String = DefaultName
  ): URIO[Scope, FaultyProvider[TestFeatureProvider]] =
    ZIO.acquireRelease(make(flags, name))(_.close)

  /** [[wrap]], with the same finalizer as [[scoped]]. */
  def wrapScoped[P <: FeatureProvider](delegate: P, name: String = DefaultName): URIO[Scope, FaultyProvider[P]] =
    ZIO.acquireRelease(wrap(delegate, name))(_.close)
}
