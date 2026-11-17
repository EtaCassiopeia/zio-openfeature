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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
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
final class SimLeaf(val name: String) extends EventProvider {

  val flags: ConcurrentHashMap[String, Stored]  = new ConcurrentHashMap()
  val fault: AtomicReference[Option[LeafFault]] = new AtomicReference(None)
  val calls: AtomicInteger                      = new AtomicInteger(0)
  val inits: AtomicInteger                      = new AtomicInteger(0)
  val shutdowns: AtomicInteger                  = new AtomicInteger(0)
  val tracked: AtomicInteger                    = new AtomicInteger(0)
  // Calls that arrived after this leaf was shut down: a replaced or closed provider answering.
  val callsAfterShutdown: AtomicInteger             = new AtomicInteger(0)
  private val state: AtomicReference[ProviderState] = new AtomicReference(ProviderState.NOT_READY)

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata { override def getName: String = name }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = state.get()

  override def initialize(context: OFEvaluationContext): Unit = {
    inits.incrementAndGet(): Unit
    state.set(ProviderState.READY)
  }

  override def shutdown(): Unit = {
    shutdowns.incrementAndGet(): Unit
    state.set(ProviderState.NOT_READY)
    super.shutdown()
  }

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    tracked.incrementAndGet(): Unit

  def configurationChanged(): Unit =
    emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await()

  private def error[T](default: T, code: OFErrorCode): ProviderEvaluation[T] =
    ProviderEvaluation.builder[T]().value(default).errorCode(code).errorMessage(s"$name: $code").reason("ERROR").build()

  private def ok[T](value: T, key: String, reason: String): ProviderEvaluation[T] =
    ProviderEvaluation.builder[T]().value(value).variant(s"$name:$key").reason(reason).build()

  private def answer[T](wire: Wire, key: String, default: T, ctx: OFEvaluationContext): ProviderEvaluation[T] = {
    calls.incrementAndGet(): Unit
    if (shutdowns.get() > 0) callsAfterShutdown.incrementAndGet(): Unit
    fault.get() match {
      case Some(LeafFault.ThrowGeneral)  => throw new GeneralError(s"$name: general")
      case Some(LeafFault.ThrowNotFound) => throw new FlagNotFoundError(s"$name: not found")
      case Some(LeafFault.ReturnGeneral) => error(default, OFErrorCode.GENERAL)
      case None                          =>
        Option(flags.get(key)) match {
          case None         => error(default, OFErrorCode.FLAG_NOT_FOUND)
          case Some(stored) =>
            SimLeaf.serve(wire, stored, ctx) match {
              case Some((v, reason)) => ok(v.asInstanceOf[T], key, reason)
              case None              => error(default, OFErrorCode.TYPE_MISMATCH)
            }
        }
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
