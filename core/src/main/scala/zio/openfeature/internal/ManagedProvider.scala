package zio.openfeature.internal

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderAccess,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}
import zio._

/** The provider the library registers with the SDK in place of the user's: it observes the user's provider directly
  * instead of inferring its lifecycle from the slot.
  *
  *   - '''Initialization''': each `initialize()` is counted per domain while it runs ([[initsInFlight]]) and completes
  *     an [[ManagedProvider.Attempt]] with how it ended. A registration arms the attempt before it binds ([[arm]]) and
  *     waits on that one, so it awaits the call its binding leads to instead of polling the slot for it. Every
  *     registration armed before an `initialize()` enters shares its attempt, whichever domain the SDK runs it for: the
  *     SDK keeps one registration per wrapper and API, and initializes it once.
  *   - '''Shutdown''': `shutdown()` reaches the provider once per cycle; a second call before the next `initialize()`,
  *     such as the SDK's `shutDownOld` followed by `api.shutdown()`, returns without calling it. A cycle starts at the
  *     first `initialize()` after construction or after a shutdown, so a provider the SDK registers again after
  *     shutting it down initializes and shuts down afresh. A shutdown acts only on the cycle it ended, so one that
  *     overlaps the next `initialize()` leaves the new cycle's lane and event attachment alone.
  *   - '''Events''': a provider that is an `EventProvider` is attached here, not by the SDK, and each event it emits is
  *     re-emitted through an [[OrderedEmitter]], so the slot's state follows the order in which the wrapper received
  *     the events. Not covered: two emissions the provider issues back to back without awaiting them, which its own
  *     emitter pool can swap before they arrive here.
  *
  * Locks: the attachment to the provider carries a lock of its own, so the provider's emitter tasks never take the
  * API's lock; only this wrapper's emissions do, from the lane. The lane holds no lock while it waits; the API's
  * writers (`setProvider`'s registration, handler registration, `api.shutdown()`'s prepare step) never wait on a lane,
  * and `setProviderAndWait`, which the library never calls, only delays it.
  *
  * Equality is identity: one wrapper per provider instance, which every instance and registry binding it reuses.
  *
  * A wrapper made by [[ManagedProvider.switchable]] can also be pointed at another, already-initialized provider
  * ([[switchTo]]) without leaving the slot: `fromAcquireAsync` registers one on its fallback and switches it to the
  * composed stack, so the slot never goes `NOT_READY`.
  */
final private[openfeature] class ManagedProvider private (
  initial: OFFeatureProvider,
  probe: LifecycleProbe,
  val switchable: Boolean
) extends EventProvider {
  import ManagedProvider.{Armed, Attempt}

  private val inFlight  = new ConcurrentHashMap[String, AtomicInteger]()
  private val started   = new java.util.concurrent.atomic.AtomicLong(0L)
  private val lastError = new AtomicReference[String](null)
  private val attached  = new AtomicBoolean(false)
  private val cycleLock = new Object
  // Replaced only by switchTo, under cycleLock.
  private val current = new AtomicReference[OFFeatureProvider](initial)
  // The attempt the next initialize() enters, or the running one's.
  private val attempt = new AtomicReference[Attempt](new Attempt)
  // Replaced under cycleLock, read without it: the provider's emitter threads read it and never take cycleLock.
  private val lane = new AtomicReference[OrderedEmitter](new OrderedEmitter(this))
  // Guarded by cycleLock. `shutDowns` is the signal of the cycle in progress, or of the next one once a shutdown ended
  // the last: a shutdown captures its own before replacing it.
  private var shutDown   = false
  private var generation = 0L
  private var shutDowns  = new CompletableFuture[Unit]()

  // The cache entry's key: held here so the entry lives exactly as long as this wrapper. None for a switchable wrapper,
  // which is never cached, so it does not keep the provider it switched away from reachable.
  private[ManagedProvider] val cacheKey: Option[ManagedProvider.IdentityKey] =
    if (switchable) None else Some(new ManagedProvider.IdentityKey(initial))

  attachDelegate()

  /** The provider answering now: the user's, or for a switchable wrapper the one it was last switched to. */
  def delegate: OFFeatureProvider = current.get()

  /** Route everything to `next`, which must already be initialized, and return the replaced delegate, which the caller
    * owns and shuts down. `next`'s events are forwarded from now on and the replaced delegate's are dropped and
    * detached. Throws `IllegalStateException`, changing nothing, when `next` is an `EventProvider` already attached
    * elsewhere, when this cycle has been shut down (the shutdown reached the delegate it held), or when the wrapper is
    * not switchable.
    */
  def switchTo(next: OFFeatureProvider): OFFeatureProvider =
    cycleLock.synchronized {
      if (!switchable) throw new IllegalStateException("this provider's wrapper is not switchable")
      if (shutDown) throw new IllegalStateException("the provider was shut down; it no longer switches")
      attachEvents(next)
      val old = current.getAndSet(next)
      EventProviderAccess.detach(old)
      old
    }

  /** How many `initialize()` calls are running for `domain` (the default slot for `None`). */
  def initsInFlight(domain: Option[String]): Int =
    Option(inFlight.get(key(domain))).fold(0)(_.get())

  /** How many `initialize()` calls have started, over every domain, since construction. */
  def initsStarted: Long = started.get()

  /** How many `initialize()` calls are running over every domain. */
  def initsInFlightAnywhere: Int = inFlight.values().stream().mapToInt(_.get()).sum()

  /** What a registration about to bind waits on: the attempt the next `initialize()` enters (the running one's, when
    * one runs), and the signal of the cycle that call belongs to. Taken before the binding, so nothing a later
    * registration arms can replace them.
    */
  private[internal] def arm(): Armed =
    cycleLock.synchronized {
      if (attempt.get().outcome.isDone) attempt.set(new Attempt)
      new Armed(attempt.get(), shutDowns)
    }

  /** Completes when the current attempt's `initialize()` returns, failing with what it threw; waits for that call when
    * it has not started. Each wait gets its own stage, so interrupting one cancels nothing shared.
    */
  def initOutcome: Task[Unit] = awaitStage(attempt.get().outcome)

  /** The provider's message in the last `PROVIDER_ERROR` it emitted, if any. */
  def lastErrorMessage: Option[String] = Option(lastError.get())

  /** Completes when the provider is shut down in the current cycle, or in the next one once a shutdown has ended the
    * last: what the SDK does to a registration a later one replaced, once that one has initialized.
    */
  def shutDownSignal: Task[Unit] = awaitStage(shutDownStage)

  /** The stage behind [[shutDownSignal]], for a wait that composes it without ZIO interruption. */
  private[internal] def shutDownStage: CompletableFuture[Unit] = cycleLock.synchronized(shutDowns)

  /** Whether the provider's `shutdown()` has run since the last `initialize()`. */
  def shutDownInCycle: Boolean = cycleLock.synchronized(shutDown)

  override def initialize(context: OFEvaluationContext): Unit =
    observeInit(None)(current.get().initialize(context))

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    observeInit(Option(domain))(current.get().initialize(context, domain))

  /** Reaches the provider once per cycle, then stops that cycle's lane and releases the event attachment, unless a new
    * cycle took it over meanwhile.
    */
  override def shutdown(): Unit = {
    val ended = cycleLock.synchronized {
      if (shutDown) None
      else {
        shutDown = true
        // The delegate too: switchTo refuses from here until the next cycle, so it is the one this shutdown reaches.
        val cycle = (current.get(), lane.get(), shutDowns, generation)
        shutDowns = new CompletableFuture[Unit]()
        Some(cycle)
      }
    }
    ended.foreach { case (target, cycleLane, signal, cycle) =>
      at(LifecycleProbe.Point.ProviderShutDown)
      try target.shutdown()
      finally {
        cycleLane.close()
        cycleLock.synchronized {
          if (generation == cycle && attached.compareAndSet(true, false)) EventProviderAccess.detach(target)
        }
        signal.complete(()): Unit
      }
    }
  }

  private def observeInit(domain: Option[String])(body: => Unit): Unit = {
    val k       = key(domain)
    val current = cycleLock.synchronized {
      startCycle()
      val armed = attempt.get()
      // A second initialize() alongside a running one, or one after the last completed, gets an attempt of its own.
      if (!armed.outcome.isDone && armed.enter()) armed
      else {
        val fresh = new Attempt
        fresh.enter(): Unit
        attempt.set(fresh)
        fresh
      }
    }
    inFlight.computeIfAbsent(k, _ => new AtomicInteger(0)).incrementAndGet()
    started.incrementAndGet()
    at(LifecycleProbe.Point.InitEntered)
    try {
      body
      inFlight.get(k).decrementAndGet()
      current.outcome.complete(())
    } catch {
      case e: Throwable =>
        inFlight.get(k).decrementAndGet()
        current.outcome.completeExceptionally(e)
        throw e
    } finally at(LifecycleProbe.Point.InitReturned)
    ()
  }

  // Under cycleLock. A new cycle after a shutdown: a fresh lane, and the provider's events attached again.
  private def startCycle(): Unit = {
    if (shutDown) {
      shutDown = false
      generation += 1
      lane.set(new OrderedEmitter(this))
    }
    attachDelegate()
  }

  private def currentLane: OrderedEmitter = lane.get()

  private def attachDelegate(): Unit =
    cycleLock.synchronized {
      if (!attached.get()) {
        attachEvents(current.get())
        attached.set(true)
      }
    }

  // Under cycleLock. Events from a provider this wrapper has switched away from are dropped.
  private def attachEvents(provider: OFFeatureProvider): Unit =
    EventProviderAccess.attach(
      provider,
      (event, details) =>
        if (current.get() eq provider) {
          // Recorded before the event reaches the slot, so a wait that reads the slot's ERROR finds its message.
          if (event == dev.openfeature.sdk.ProviderEvent.PROVIDER_ERROR)
            lastError.set(Option(details).flatMap(d => Option(d.getMessage)).getOrElse(""))
          val running = attempt.get()
          if (event == dev.openfeature.sdk.ProviderEvent.PROVIDER_STALE && running.entered && !running.outcome.isDone)
            currentLane.enqueue(event, details, () => running.stale.complete(()): Unit)
          else currentLane.enqueue(event, details)
        }
    )

  private def at(point: LifecycleProbe.Point): Unit =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(probe.at(point)).getOrThrowFiberFailure())

  private def key(domain: Option[String]): String = domain.getOrElse("")

  private def awaitStage(f: CompletableFuture[Unit]): Task[Unit] =
    ZIO.fromCompletableFuture(f.thenApply[Unit](_ => ()))

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = current.get().getMetadata

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = current.get().getState

  override def getProviderHooks = current.get().getProviderHooks

  override def isDomainScoped(): Boolean = current.get().isDomainScoped()

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    current.get().track(eventName, context, details)

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = current.get().getBooleanEvaluation(key, defaultValue, ctx)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] = current.get().getStringEvaluation(key, defaultValue, ctx)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = current.get().getIntegerEvaluation(key, defaultValue, ctx)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = current.get().getLongEvaluation(key, defaultValue, ctx)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = current.get().getDoubleEvaluation(key, defaultValue, ctx)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] = current.get().getObjectEvaluation(key, defaultValue, ctx)
}

private[openfeature] object ManagedProvider {

  /** One `initialize()` of the wrapper and the registrations waiting on it. */
  final private[internal] class Attempt {

    /** Completes when the call returns, failing with what it threw. */
    val outcome = new CompletableFuture[Unit]()

    /** Completes once a `PROVIDER_STALE` the provider reported during the call has reached the slot: the SDK reports
      * the slot STALE from then, before the call returns.
      */
    val stale = new CompletableFuture[Unit]()

    private val started = new AtomicBoolean(false)

    /** Whether an `initialize()` has entered this attempt. */
    def entered: Boolean = started.get()

    private[ManagedProvider] def enter(): Boolean = started.compareAndSet(false, true)
  }

  /** What [[ManagedProvider.arm]] hands a registration: its attempt and its cycle's shutdown signal. */
  final private[internal] class Armed(val attempt: Attempt, val shutDown: CompletableFuture[Unit])

  /** The live wrapper of each provider instance, by identity, held weakly: one wrapper per provider in the JVM, so two
    * instances that share an API and register the same provider share its registration as the SDK always did, and a
    * provider registered again after a shutdown keeps its wrapper.
    */
  private val wrappers = new java.util.WeakHashMap[IdentityKey, java.lang.ref.WeakReference[ManagedProvider]]()

  /** A key equal only to a key for the same instance: `WeakHashMap` compares keys with `equals`. The wrapper holds the
    * provider strongly, so the key lives as long as the wrapper does.
    */
  final private[internal] class IdentityKey(val provider: OFFeatureProvider) {
    override def hashCode: Int               = java.lang.System.identityHashCode(provider)
    override def equals(other: Any): Boolean = other match {
      case k: IdentityKey => k.provider eq provider
      case _              => false
    }
  }

  /** The wrapper around `provider`, made on first use; `provider` itself when it already is one. Throws
    * `IllegalStateException` when `provider` is an `EventProvider` already attached elsewhere, as the SDK's
    * registration would. `probe` applies to a wrapper made by this call.
    */
  def apply(provider: OFFeatureProvider, probe: LifecycleProbe = LifecycleProbe.none): ManagedProvider =
    provider match {
      case managed: ManagedProvider => managed
      case other                    =>
        wrappers.synchronized {
          val key = new IdentityKey(other)
          Option(wrappers.get(key)).flatMap(ref => Option(ref.get())).getOrElse {
            val made = new ManagedProvider(other, probe, switchable = false)
            made.cacheKey.foreach(k => wrappers.put(k, new java.lang.ref.WeakReference(made)))
            made
          }
        }
    }

  /** A wrapper starting on `initial` that [[ManagedProvider.switchTo]] can point elsewhere. Never cached: what it
    * delegates to changes, so it is nobody's wrapper but its creator's.
    */
  def switchable(initial: OFFeatureProvider, probe: LifecycleProbe = LifecycleProbe.none): ManagedProvider =
    new ManagedProvider(initial, probe, switchable = true)

  /** The wrapper around `provider` if one exists, without making one. */
  def existing(provider: OFFeatureProvider): Option[ManagedProvider] = provider match {
    case managed: ManagedProvider => Some(managed)
    case other                    =>
      wrappers.synchronized(Option(wrappers.get(new IdentityKey(other))).flatMap(ref => Option(ref.get())))
  }

  /** The provider behind a wrapper (for a switchable one, its current delegate), or `provider` itself. Peels one
    * wrapper only.
    */
  def unwrap(provider: OFFeatureProvider): OFFeatureProvider = provider match {
    case managed: ManagedProvider => managed.delegate
    case other                    => other
  }
}
