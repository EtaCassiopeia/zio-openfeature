package zio.openfeature.testkit

import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
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
    * provider whose `initialize()` ignores shutdown, the shape that wedged the layer. A release that comes after the
    * call entered but before it reached the hang (say, during a preceding [[Delay]]) still lets it through.
    */
  case object HangUntilReleased extends Fault

  /** Block until `shutdown()` is called (or [[FaultyProvider.release]]), then call the delegate: the common "wait for
    * the first config, shutdown aborts the wait" shape. As with [[HangUntilReleased]], a shutdown or release after the
    * call entered counts even if it came before the call reached the hang.
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

  /** Replace an evaluation's answer with a malformed one, for the six `Evaluate*` ops. On any other op the call throws
    * `IllegalStateException`: the shape has no meaning there.
    */
  final case class Answer(shape: AnswerShape) extends Fault

  /** Replace `getMetadata`'s answer, for [[Op.GetMetadata]]. On any other op the call throws `IllegalStateException`.
    */
  final case class MetadataAnswer(shape: MetadataShape) extends Fault
}

/** A malformed evaluation answer for [[Fault.Answer]]. */
sealed trait AnswerShape extends Product with Serializable

object AnswerShape {

  /** A resolution with no value and no error code. */
  case object NullValue extends AnswerShape

  /** A value of another box than the getter's type: an `Integer` from the boolean, string, long and double getters, a
    * `Boolean` from the integer getter, a `String` from the object getter. (Not a `String` from the boolean getter:
    * `FlagType[Boolean]` parses `"true"` and `"false"` on purpose.)
    */
  case object WrongBox extends AnswerShape

  /** The delegate's value next to `ErrorCode.GENERAL`. */
  case object ErrorCodeWithValue extends AnswerShape

  /** The delegate's value with a `null` reason. */
  case object NullReason extends AnswerShape

  /** The getter returns `null` instead of a `ProviderEvaluation`. */
  case object NullEvaluation extends AnswerShape

  val values: Chunk[AnswerShape] = Chunk(NullValue, WrongBox, ErrorCodeWithValue, NullReason, NullEvaluation)
}

/** A malformed `getMetadata` answer for [[Fault.MetadataAnswer]]. */
sealed trait MetadataShape extends Product with Serializable

object MetadataShape {

  /** `getMetadata` returns `null`. */
  case object Null extends MetadataShape

  /** A `Metadata` whose `getName` is `null`. */
  case object Nameless extends MetadataShape

  val values: Chunk[MetadataShape] = Chunk(Null, Nameless)
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

  import FaultyProvider.{Call, Entry, Outcome}

  private val scripts  = new ConcurrentHashMap[Op, AtomicReference[Chunk[Fault]]]()
  private val recorded = new ConcurrentLinkedQueue[Call]()
  // A call takes its tickets when it enters, so a release or shutdown() between the entry (what awaitEntered sees) and
  // the call reaching its hang still lets it through. `release` opens both gates; `shutdown()` only the second.
  private val released           = new Gate
  private val releasedOrShutdown = new Gate

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

  /** Wait until at least `n` calls of `op` have started. A [[release]] (or `shutdown()`) after this returns reaches
    * those calls even if they have not parked on their hang yet.
    */
  def awaitEntered(op: Op, n: Int = 1): UIO[Unit] =
    ZIO.attemptBlockingInterrupt {
      entryLock.synchronized {
        while (entries.getOrElse(op, 0) < n) entryLock.wait()
      }
    }.orDie

  /** Unpark every call that entered before this release, including one still on its way to its hang. A call that enters
    * later parks again.
    */
  def release: UIO[Unit] = ZIO.succeed { released.open(); releasedOrShutdown.open() }

  /** Release everything and make every later hang return at once. The finalizer of [[FaultyProvider.scoped]]. */
  private[testkit] def close: UIO[Unit] = ZIO.succeed { released.close(); releasedOrShutdown.close() }

  private def nextFault(op: Op): Fault =
    Option(scripts.get(op)) match {
      case None      => Fault.Succeed
      case Some(ref) =>
        ref.getAndUpdate(s => if (s.size > 1) s.tail else s).headOption.getOrElse(Fault.Succeed)
    }

  private def hang(onShutdown: Boolean, entry: Entry): Unit =
    if (onShutdown) releasedOrShutdown.await(entry.releasedOrShutdown) else released.await(entry.released)

  private def applyFault[A](op: Op, fault: Fault, entry: Entry, shaped: PartialFunction[Fault, A], body: => A): A =
    fault match {
      case Fault.Succeed                  => body
      case Fault.Throw(error)             => throw error
      case Fault.Delay(duration, andThen) =>
        Thread.sleep(duration.toMillis); applyFault(op, andThen, entry, shaped, body)
      case Fault.HangUntilReleased            => hang(onShutdown = false, entry); body
      case Fault.HangUntilShutdown            => hang(onShutdown = true, entry); body
      case Fault.Emit(event, details, after)  => emit(event, details); applyFault(op, after, entry, shaped, body)
      case shape if shaped.isDefinedAt(shape) => shaped(shape)
      case other                              => throw new IllegalStateException(s"$other does not apply to $op")
    }

  private def run[A](op: Op, flagKey: Option[String], arguments: Any*)(body: => A): A =
    runShaped(op, flagKey, PartialFunction.empty[Fault, A], arguments: _*)(body)

  /** An evaluation, whose [[Fault.Answer]] replaces the delegate's answer with `shape`'s. */
  private def evaluate[A](op: Op, key: String, defaultValue: A, ctx: OFEvaluationContext, wrong: Any)(
    body: => ProviderEvaluation[A]
  ): ProviderEvaluation[A] =
    runShaped(op, Some(key), FaultyProvider.answers(body, wrong), key, defaultValue, ctx)(body)

  private def runShaped[A](op: Op, flagKey: Option[String], shaped: PartialFunction[Fault, A], arguments: Any*)(
    body: => A
  ): A = {
    val args      = Chunk.fromIterable(arguments)
    val startedAt = Instant.now()
    val thread    = Thread.currentThread().getName
    // Read before the entry is announced, so anything that waited on awaitEntered and then released is seen.
    val entry = entryLock.synchronized {
      val seen = Entry(released.ticket(), releasedOrShutdown.ticket())
      entries.update(op, entries.getOrElse(op, 0) + 1)
      entryLock.notifyAll()
      seen
    }
    try {
      val result = applyFault(op, nextFault(op), entry, shaped, body)
      recorded.add(Call(op, flagKey, thread, startedAt, Outcome.Returned, args))
      result
    } catch {
      case error: Throwable =>
        recorded.add(Call(op, flagKey, thread, startedAt, Outcome.Threw(error), args))
        throw error
    }
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = {
    val named = new Metadata { override def getName: String = name }
    runShaped[Metadata](
      Op.GetMetadata,
      None,
      {
        case Fault.MetadataAnswer(MetadataShape.Null)     => null
        case Fault.MetadataAnswer(MetadataShape.Nameless) => new Metadata { override def getName: String = null }
      }
    )(named)
  }

  override def getProviderHooks = run(Op.GetProviderHooks, None)(delegate.getProviderHooks)

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = run(Op.GetState, None)(delegate.getState)

  override def initialize(context: OFEvaluationContext): Unit =
    run(Op.Initialize, None, context)(delegate.initialize(context))

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    run(Op.Initialize, None, context, domain)(delegate.initialize(context, domain))

  /** Unparks every [[Fault.HangUntilShutdown]] first, then applies the [[Op.Shutdown]] script. */
  override def shutdown(): Unit = {
    releasedOrShutdown.open()
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
    evaluate(Op.EvaluateBoolean, key, defaultValue, ctx, java.lang.Integer.valueOf(1))(
      delegate.getBooleanEvaluation(key, defaultValue, ctx)
    )

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    evaluate(Op.EvaluateString, key, defaultValue, ctx, java.lang.Integer.valueOf(1))(
      delegate.getStringEvaluation(key, defaultValue, ctx)
    )

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    evaluate(Op.EvaluateInteger, key, defaultValue, ctx, java.lang.Boolean.TRUE)(
      delegate.getIntegerEvaluation(key, defaultValue, ctx)
    )

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    evaluate(Op.EvaluateLong, key, defaultValue, ctx, java.lang.Integer.valueOf(1))(
      delegate.getLongEvaluation(key, defaultValue, ctx)
    )

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    evaluate(Op.EvaluateDouble, key, defaultValue, ctx, java.lang.Integer.valueOf(1))(
      delegate.getDoubleEvaluation(key, defaultValue, ctx)
    )

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    evaluate(Op.EvaluateObject, key, defaultValue, ctx, "not a Value")(
      delegate.getObjectEvaluation(key, defaultValue, ctx)
    )
}

object FaultyProvider {

  /** The answers [[Fault.Answer]] shapes: built from the delegate's own answer where the shape keeps its value. */
  private def answers[A](
    delegateAnswer: => ProviderEvaluation[A],
    wrong: Any
  ): PartialFunction[Fault, ProviderEvaluation[A]] = {
    case Fault.Answer(AnswerShape.NullValue) =>
      answer[A](None, None, Some("STATIC"), None)
    case Fault.Answer(AnswerShape.WrongBox) =>
      // Deliberately unsound: a provider written in Java can return any box, and the library must survive it.
      answer[Any](Some(wrong), None, Some("STATIC"), None).asInstanceOf[ProviderEvaluation[A]]
    case Fault.Answer(AnswerShape.ErrorCodeWithValue) =>
      answer[A](Some(delegateAnswer.getValue), None, Some("ERROR"), Some(dev.openfeature.sdk.ErrorCode.GENERAL))
    case Fault.Answer(AnswerShape.NullReason) =>
      val d = delegateAnswer
      answer[A](Some(d.getValue), Option(d.getVariant), None, None)
    case Fault.Answer(AnswerShape.NullEvaluation) =>
      null
  }

  // Statement by statement and cast, as core's ProviderEvaluations does: Scala 2.13 infers the chained builder's
  // self type as an existential.
  private def answer[A](
    value: Option[A],
    variant: Option[String],
    reason: Option[String],
    errorCode: Option[dev.openfeature.sdk.ErrorCode]
  ): ProviderEvaluation[A] = {
    val builder = ProviderEvaluation.builder[A]()
    value.foreach(builder.value(_))
    variant.foreach(builder.variant(_))
    reason.foreach(builder.reason(_))
    errorCode.foreach { code =>
      builder.errorCode(code)
      builder.errorMessage("error code next to a value")
    }
    builder.build().asInstanceOf[ProviderEvaluation[A]]
  }

  /** The tickets a call took when it entered. */
  final private case class Entry(released: Int, releasedOrShutdown: Int)

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
