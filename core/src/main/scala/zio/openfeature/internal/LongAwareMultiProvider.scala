package zio.openfeature.internal

import java.util.{ArrayList, Collections, HashMap}
import java.util.function.{BiFunction, Function => JFunction}

import java.util.concurrent.atomic.AtomicBoolean

import dev.openfeature.sdk.{
  ClientMetadata,
  ErrorCode => OFErrorCode,
  EventProviderAccess,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  FlagEvaluationDetails,
  FlagValueType,
  Hook => OFHook,
  HookContext => OFHookContext,
  MultiProviderHookExecutor,
  ProviderEvaluation,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails
}
import dev.openfeature.sdk.multiprovider.{MultiProvider, Strategy}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** The SDK's `MultiProvider` with a `getLongEvaluation` that runs the strategy over each child's own long getter.
  *
  * The SDK's `MultiProvider` (1.23.0) overrides the boolean, string, integer, double and object getters but not the
  * long one, so a long read falls to the interface default, which goes through `getDoubleEvaluation`: each child's
  * double getter, inexact past 2^53 and a `TYPE_MISMATCH` for integer values in every provider this library ships.
  *
  * This subclass adds the missing getter with public SDK API only, mirroring what the SDK does for the other five
  * types: a provider hook records the caller's client metadata and hints in a thread-local (`before`) and clears it
  * (`finallyAfter`), and each child is evaluated through `MultiProviderHookExecutor`, so the child's own provider hooks
  * run and see `FlagValueType.LONG` and the caller's client. It also forwards `track` to every child, which the SDK's
  * `MultiProvider` drops.
  *
  * It also does what the specification's Multi-Provider appendix asks of a multi-provider's status, which the SDK's
  * `MultiProvider` (1.23.0) does not: it never attaches to its children, so their events went nowhere. Each child that
  * is an `EventProvider` is attached while the chain is initialized; its status is tracked the way the SDK tracks a
  * provider's (`NOT_READY`, then `READY` once initialization succeeds, then whatever its events say), and the chain's
  * own status is the worst of them, by the appendix's precedence `FATAL` > `NOT_READY` > `ERROR` > `STALE` > `READY`. A
  * child's status event is re-emitted, with that child's details, only when it changes the chain's status; a
  * configuration change is always re-emitted. `getState` reports the chain's status.
  */
final private[openfeature] class LongAwareMultiProvider(
  providers: java.util.List[OFFeatureProvider],
  strategy: Strategy
) extends MultiProvider(providers, strategy) {

  import LongAwareMultiProvider._

  // The same defensive copy the super constructor makes; its own copy is private.
  private val children: java.util.List[OFFeatureProvider] =
    Collections.unmodifiableList(new ArrayList[OFFeatureProvider](providers))

  private val chainStrategy: Strategy = strategy

  private val hookExecutor = new MultiProviderHookExecutor()

  // Child statuses, indexed by position (two children may be equal but distinct), the chain's, and whether it is
  // initialized and not shut down: guarded by `lock`. Children's events are re-emitted only while it is running.
  private val lock        = new Object
  private val childStates = Array.fill[ProviderState](children.size)(ProviderState.NOT_READY)
  private var chainState  = ProviderState.NOT_READY: ProviderState
  private var running     = false
  private val attachedTo  = Array.fill(children.size)(new AtomicBoolean(false))

  override def initialize(ctx: OFEvaluationContext): Unit = initialize(ctx, null)

  // The SDK may initialize the chain again without shutting it down first (a swap's rollback re-registers it), so a
  // child this chain is already attached to keeps that attachment.
  override def initialize(ctx: OFEvaluationContext, domain: String): Unit = {
    lock.synchronized {
      running = false
      setAll(ProviderState.NOT_READY)
    }
    val attachedNow = scala.collection.mutable.ListBuffer.empty[Int]
    try
      children.asScala.zipWithIndex.foreach { case (child, i) =>
        if (!attachedTo(i).get()) {
          // A member whose event channel something else holds — the fallback `fromAcquireAsync` composes while its
          // switch still serves it, a provider also registered with an API, or a member listed twice — keeps it, and
          // counts here as a member that sends no events.
          val attached =
            try {
              EventProviderAccess.attach(child, (event, details) => onChildEvent(i, event, details))
              true
            } catch { case _: IllegalStateException => false }
          if (attached) {
            attachedTo(i).set(true)
            attachedNow += i
          }
        }
      }
    catch {
      case e: Throwable =>
        attachedNow.foreach(detach)
        throw e
    }
    // On a failure the SDK's initialize calls `shutdown()`, which detaches.
    super.initialize(ctx, domain)
    // Initialization succeeding makes every child READY, as the SDK does for a provider whatever it emitted meanwhile,
    // so a child's status event during it is not re-emitted. Nor is the chain's READY: the SDK reports it once this
    // returns.
    lock.synchronized {
      setAll(ProviderState.READY)
      running = true
    }
  }

  override def shutdown(): Unit = {
    lock.synchronized {
      running = false
      chainState = ProviderState.NOT_READY
    }
    childStates.indices.foreach(detach)
    super.shutdown()
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = lock.synchronized(chainState)

  // Called holding `lock`.
  private def setAll(state: ProviderState): Unit = {
    childStates.indices.foreach(childStates(_) = state)
    chainState = state
  }

  private def detach(index: Int): Unit =
    if (attachedTo(index).compareAndSet(true, false)) EventProviderAccess.detach(children.get(index))

  // Re-emitted outside `lock`, on the chain's own emitter, which the SDK runs unordered anyway: the order between two
  // children's changes is best-effort.
  private def onChildEvent(index: Int, event: OFProviderEvent, details: ProviderEventDetails): Unit =
    event match {
      case OFProviderEvent.PROVIDER_CONFIGURATION_CHANGED =>
        if (lock.synchronized(running)) {
          emit(event, details)
          ()
        }
      case status =>
        val reported = status match {
          case OFProviderEvent.PROVIDER_READY                                             => ProviderState.READY
          case OFProviderEvent.PROVIDER_STALE                                             => ProviderState.STALE
          case _ if details != null && details.getErrorCode == OFErrorCode.PROVIDER_FATAL => ProviderState.FATAL
          case _                                                                          => ProviderState.ERROR
        }
        val changedTo = lock.synchronized {
          if (!running) None
          else {
            childStates(index) = reported
            val next = aggregateOf(childStates.toSeq)
            if (next == chainState) None
            else {
              chainState = next
              Some(next)
            }
          }
        }
        changedTo.flatMap(eventFor).foreach { e =>
          emit(e, details)
          ()
        }
    }

  private val captured = new ThreadLocal[Captured]()

  private val captureHook: OFHook[Object] = new OFHook[Object] {
    override def before(
      ctx: OFHookContext[Object],
      hints: java.util.Map[String, Object]
    ): java.util.Optional[OFEvaluationContext] = {
      captured.set(Captured(ctx.getClientMetadata, snapshot(hints)))
      java.util.Optional.empty[OFEvaluationContext]()
    }
    override def finallyAfter(
      ctx: OFHookContext[Object],
      details: FlagEvaluationDetails[Object],
      hints: java.util.Map[String, Object]
    ): Unit = captured.remove()
  }

  private val hooks: java.util.List[OFHook[_]] = {
    val all = new ArrayList[OFHook[_]](super.getProviderHooks)
    all.add(captureHook)
    Collections.unmodifiableList(all)
  }

  override def getProviderHooks: java.util.List[OFHook[_]] = hooks

  // The SDK's MultiProvider inherits FeatureProvider's no-op `track`, so the event would reach no child. Tracking has no
  // result for a strategy to pick, so every child is offered it, and one throwing child must not starve the rest: the
  // first failure is rethrown once all have run, carrying the others as suppressed.
  override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit = {
    val failures = children.asScala.toList.flatMap(child => Try(child.track(eventName, ctx, details)).failed.toOption)
    failures.headOption.foreach { first =>
      // Children may rethrow shared or cached instances: a Throwable cannot suppress itself, and one already suppressed
      // by an earlier call must not be added again, or a cached exception's list grows with every event.
      failures.tail
        .filterNot(f => (f eq first) || first.getSuppressed.exists(_ eq f))
        .distinct
        .foreach(first.addSuppressed)
      throw first
    }
  }

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    // Read on the calling thread, before the strategy runs, as the SDK's own getters do. Nothing captured means a
    // direct provider call with no client: fall back to the metadata and hints the SDK uses on that path.
    val current  = Option(captured.get())
    val metadata = current.fold(fallbackMetadata)(_.metadata)
    val hints    = current.fold(emptyHints)(_.hints)
    val callLong = new BiFunction[OFFeatureProvider, OFEvaluationContext, ProviderEvaluation[java.lang.Long]] {
      def apply(p: OFFeatureProvider, c: OFEvaluationContext): ProviderEvaluation[java.lang.Long] =
        p.getLongEvaluation(key, defaultValue, c)
    }
    chainStrategy.evaluate[java.lang.Long](
      children,
      key,
      defaultValue,
      ctx,
      new JFunction[OFFeatureProvider, ProviderEvaluation[java.lang.Long]] {
        def apply(child: OFFeatureProvider): ProviderEvaluation[java.lang.Long] =
          hookExecutor.execute[java.lang.Long](
            child,
            key,
            defaultValue,
            FlagValueType.LONG,
            ctx,
            metadata,
            hints,
            callLong
          )
      }
    )
  }
}

private[openfeature] object LongAwareMultiProvider {

  final private case class Captured(metadata: ClientMetadata, hints: java.util.Map[String, Object])

  private val emptyHints: java.util.Map[String, Object] = Collections.emptyMap[String, Object]()

  /** The chain's status for its children's: the worst, by the appendix's precedence. No children: READY. */
  private[internal] def aggregateOf(states: Seq[ProviderState]): ProviderState =
    Seq(ProviderState.FATAL, ProviderState.NOT_READY, ProviderState.ERROR, ProviderState.STALE)
      .find(states.contains)
      .getOrElse(ProviderState.READY)

  // A FATAL is reported as an ERROR event; the triggering child's details carry the PROVIDER_FATAL code.
  private def eventFor(state: ProviderState): Option[OFProviderEvent] = state match {
    case ProviderState.READY                       => Some(OFProviderEvent.PROVIDER_READY)
    case ProviderState.STALE                       => Some(OFProviderEvent.PROVIDER_STALE)
    case ProviderState.ERROR | ProviderState.FATAL => Some(OFProviderEvent.PROVIDER_ERROR)
    case _                                         => None
  }

  // The SDK's MultiProvider hands its children a client whose domain is its own name when nothing was captured.
  private val fallbackMetadata: ClientMetadata = new ClientMetadata {
    def getDomain: String = MultiProvider.getNAME
  }

  // An immutable copy that tolerates null keys and values, which `Map.copyOf` rejects.
  private def snapshot(hints: java.util.Map[String, Object]): java.util.Map[String, Object] =
    if (hints == null || hints.isEmpty) emptyHints
    else Collections.unmodifiableMap(new HashMap[String, Object](hints))
}
