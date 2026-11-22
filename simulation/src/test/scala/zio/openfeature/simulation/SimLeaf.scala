package zio.openfeature.simulation

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  MutableStructure,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails,
  Value
}
import dev.openfeature.sdk.exceptions.{FlagNotFoundError, GeneralError}

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters._

/** What a leaf holds under a key. `Echo` answers a string read with a rendering of the context the leaf received. */
sealed trait Stored extends Product with Serializable
object Stored {
  final case class SBool(v: Boolean)            extends Stored
  final case class SStr(v: String)              extends Stored
  final case class SInt(v: Int)                 extends Stored
  final case class SLong(v: Long)               extends Stored
  final case class SDbl(v: Double)              extends Stored
  final case class SObj(v: Map[String, String]) extends Stored
  case object Echo                              extends Stored
}

/** How a leaf misbehaves on every evaluation while the fault is set. */
sealed trait LeafFault extends Product with Serializable
object LeafFault {
  case object ThrowGeneral  extends LeafFault
  case object ThrowNotFound extends LeafFault
  case object ReturnGeneral extends LeafFault

  /** One-shot: the next call that reaches the leaf blocks on its [[StallGate]] until the runner releases it, and the
    * fault then clears. The runner moves the clock to just past (`pastBound`) or just short of the innermost evaluation
    * timeout above the leaf before it releases the call.
    */
  final case class Stall(pastBound: Boolean) extends LeafFault
}

/** How a leaf's `initialize()` misbehaves. Given only to the leaves of a stack a swap installs. */
sealed trait InitFault extends Product with Serializable
object InitFault {

  /** Every `initialize()` throws at once. */
  case object Fails extends InitFault

  /** The first `initialize()` blocks until the runner releases it, then succeeds; later ones succeed at once. */
  case object StallThenOk extends InitFault

  /** The first `initialize()` blocks until the runner releases it, then throws; later ones throw at once. */
  case object StallThenFail extends InitFault

  /** The first `n` calls to `initialize()` throw at once; later ones succeed. */
  final case class FailsFirst(n: Int) extends InitFault
}

/** How a leaf's `shutdown()` misbehaves. */
sealed trait ShutdownFault extends Product with Serializable
object ShutdownFault {

  /** `shutdown()` counts itself and shuts the leaf down, then throws. */
  case object Throws extends ShutdownFault
}

/** A latch a stalled call waits on: `entered` once a call is blocked on it, `exited` once that call has returned or
  * thrown. `release(abandon = true)` makes the call throw, as a call whose caller timed out and walked away.
  */
final class StallGate {
  private val enteredLatch            = new CountDownLatch(1)
  private val exitedLatch             = new CountDownLatch(1)
  private val released                = new CountDownLatch(1)
  @volatile private var abandonCall   = false
  def entered: Boolean                = enteredLatch.getCount == 0
  def exited: Boolean                 = exitedLatch.getCount == 0
  def release(abandon: Boolean): Unit = { abandonCall = abandon; released.countDown() }

  /** Blocks until released, honouring interrupts (a circuit breaker's timeout interrupts the call); `true` when the
    * call should go on to answer.
    */
  def await(): Boolean =
    try {
      enteredLatch.countDown()
      released.await()
      !abandonCall
    } catch { case _: InterruptedException => false }
    finally exitedLatch.countDown()
}

/** The wire getter a read goes through. */
sealed trait Wire extends Product with Serializable
object Wire {
  case object Bool extends Wire
  case object Str  extends Wire
  case object Int  extends Wire
  case object Long extends Wire
  case object Dbl  extends Wire
  case object Obj  extends Wire
}

/** A deterministic leaf provider: its answer is a pure function of what it holds, its fault and the context it gets, so
  * a reference model can predict it exactly. Every answer carries the variant `"<name>:<key>"`, so the model can tell
  * which leaf answered. It counts what it is asked, so the model can check that wrappers call it when they should.
  */
final class SimLeaf(
  val name: String,
  initFault: Option[InitFault] = None,
  shutdownFault: Option[ShutdownFault] = None
) extends EventProvider {

  val flags: ConcurrentHashMap[String, Stored]  = new ConcurrentHashMap()
  val fault: AtomicReference[Option[LeafFault]] = new AtomicReference(None)
  val calls: AtomicInteger                      = new AtomicInteger(0)
  val inits: AtomicInteger                      = new AtomicInteger(0)
  // The `initialize()` calls running now, and how many times one entered while another was still running.
  val initsInFlight: AtomicInteger   = new AtomicInteger(0)
  val concurrentInits: AtomicInteger = new AtomicInteger(0)
  val shutdowns: AtomicInteger       = new AtomicInteger(0)
  // The thread each shutdown() ran on: the SDK's executor ("openfeature-provider-thread-") or the library's.
  val shutdownThreads: java.util.concurrent.ConcurrentLinkedQueue[String] =
    new java.util.concurrent.ConcurrentLinkedQueue()
  val tracked: AtomicInteger = new AtomicInteger(0)
  // Calls that arrived after this leaf was shut down: a replaced or closed provider answering.
  val callsAfterShutdown: AtomicInteger             = new AtomicInteger(0)
  private val state: AtomicReference[ProviderState] = new AtomicReference(ProviderState.NOT_READY)

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata { override def getName: String = name }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = state.get()

  /** The gate a stalling `initialize()` waits on. */
  val initGate: StallGate = new StallGate

  /** The gate the next stalled evaluation waits on; replaced each time a stall fault is set. */
  val stallGate: AtomicReference[StallGate] = new AtomicReference(new StallGate)

  override def initialize(context: OFEvaluationContext): Unit = {
    val n = inits.incrementAndGet()
    if (initsInFlight.incrementAndGet() > 1) concurrentInits.incrementAndGet(): Unit
    try initializeOnce(n)
    finally initsInFlight.decrementAndGet(): Unit
  }

  private def initializeOnce(n: Int): Unit = {
    initFault match {
      case Some(InitFault.Fails)                   => throw new GeneralError(s"$name: initialize fails")
      case Some(InitFault.StallThenOk) if n == 1   => initGate.await(): Unit
      case Some(InitFault.StallThenFail) if n == 1 =>
        initGate.await(): Unit
        throw new GeneralError(s"$name: initialize fails after a stall")
      case Some(InitFault.StallThenFail)           => throw new GeneralError(s"$name: initialize fails")
      case Some(InitFault.FailsFirst(k)) if n <= k => throw new GeneralError(s"$name: initialize fails ($n of $k)")
      case _                                       => ()
    }
    state.set(ProviderState.READY)
  }

  override def shutdown(): Unit = {
    shutdownThreads.add(Thread.currentThread().getName): Unit
    shutdowns.incrementAndGet(): Unit
    state.set(ProviderState.NOT_READY)
    super.shutdown()
    if (shutdownFault.contains(ShutdownFault.Throws)) throw new IllegalStateException(s"$name: shutdown throws")
  }

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    tracked.incrementAndGet(): Unit

  def configurationChanged(): Unit =
    emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await()

  private def error[T](default: T, code: OFErrorCode): ProviderEvaluation[T] =
    ProviderEvaluation.builder[T]().value(default).errorCode(code).errorMessage(s"$name: $code").reason("ERROR").build()

  /** Set once a swap away from this leaf's stack has raised its gate: an answer given from then on carries `@late` in
    * its variant, and must never reach a caller as a success.
    */
  val retiring: AtomicBoolean = new AtomicBoolean(false)

  private def ok[T](value: T, key: String, reason: String): ProviderEvaluation[T] =
    ProviderEvaluation
      .builder[T]()
      .value(value)
      .variant(s"$name:$key" + (if (retiring.get()) SimLeaf.LateSuffix else ""))
      .reason(reason)
      .build()

  private def answer[T](wire: Wire, key: String, default: T, ctx: OFEvaluationContext): ProviderEvaluation[T] = {
    calls.incrementAndGet(): Unit
    if (shutdowns.get() > 0) callsAfterShutdown.incrementAndGet(): Unit
    def serve(): ProviderEvaluation[T] =
      Option(flags.get(key)) match {
        case None         => error(default, OFErrorCode.FLAG_NOT_FOUND)
        case Some(stored) =>
          SimLeaf.serve(wire, stored, ctx) match {
            case Some((v, reason)) => ok(v.asInstanceOf[T], key, reason)
            case None              => error(default, OFErrorCode.TYPE_MISMATCH)
          }
      }
    fault.get() match {
      case Some(LeafFault.ThrowGeneral)  => throw new GeneralError(s"$name: general")
      case Some(LeafFault.ThrowNotFound) => throw new FlagNotFoundError(s"$name: not found")
      case Some(LeafFault.ReturnGeneral) => error(default, OFErrorCode.GENERAL)
      case f @ Some(_: LeafFault.Stall) if fault.compareAndSet(f, None) =>
        if (stallGate.get().await()) serve() else throw new GeneralError(s"$name: stalled call abandoned")
      case _ => serve()
    }
  }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = answer(Wire.Bool, key, defaultValue, ctx)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    answer(Wire.Str, key, defaultValue, ctx)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = answer(Wire.Int, key, defaultValue, ctx)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = answer(Wire.Long, key, defaultValue, ctx)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = answer(Wire.Dbl, key, defaultValue, ctx)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    answer(Wire.Obj, key, defaultValue, ctx)
}

object SimLeaf {

  /** The variant suffix of an answer a retiring leaf gave. */
  val LateSuffix: String = "@late"

  /** The rendering an `Echo` answers with: the targeting key, then the string attributes, sorted. */
  def render(tk: Option[String], attrs: Map[String, String]): String =
    tk.getOrElse("-") + "|" + attrs.toList.sorted.map { case (k, v) => s"$k=$v" }.mkString(",")

  def renderJava(ctx: OFEvaluationContext): String =
    if (ctx == null) render(None, Map.empty)
    else {
      val tk    = Option(ctx.getTargetingKey).filter(_.nonEmpty)
      val attrs = ctx.asObjectMap.asScala.collect {
        case (k, v: String) if k != "targetingKey" => k -> v
        case (k, v) if k != "targetingKey"         => k -> s"<${String.valueOf(v)}>"
      }.toMap
      render(tk, attrs)
    }

  /** The value (in the SDK's boxed form) and reason a read through `wire` serves for `stored`, if the types match. */
  def serve(wire: Wire, stored: Stored, ctx: OFEvaluationContext): Option[(Any, String)] = (wire, stored) match {
    case (Wire.Bool, Stored.SBool(v)) => Some((java.lang.Boolean.valueOf(v), "STATIC"))
    case (Wire.Str, Stored.SStr(v))   => Some((v, "STATIC"))
    case (Wire.Str, Stored.Echo)      => Some((renderJava(ctx), "TARGETING_MATCH"))
    case (Wire.Int, Stored.SInt(v))   => Some((java.lang.Integer.valueOf(v), "STATIC"))
    case (Wire.Long, Stored.SLong(v)) => Some((java.lang.Long.valueOf(v), "STATIC"))
    case (Wire.Dbl, Stored.SDbl(v))   => Some((java.lang.Double.valueOf(v), "STATIC"))
    case (Wire.Obj, Stored.SObj(m))   =>
      val s = new MutableStructure()
      m.foreach { case (k, v) => s.add(k, v): Unit }
      Some((new Value(s), "STATIC"))
    case _ => None
  }
}
