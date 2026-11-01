package zio.openfeature.internal

import dev.openfeature.sdk.{EventProvider, ProviderEvent, ProviderEventDetails}

import java.util.concurrent.{ExecutorService, Executors, RejectedExecutionException, ThreadFactory, TimeUnit}

/** One ordered lane for an `EventProvider`'s emissions: they reach the SDK slot in the order `enqueue` was called.
  *
  * `EventProvider.emit` hands each emission to a cached thread pool, so two emissions made back to back are two tasks
  * that write the slot state in either order. This lane runs `owner.emit(event, details).await()` on a single thread,
  * one at a time, so emission N+1 is handed to the SDK only once the SDK has written the slot for emission N. Callers
  * never block.
  *
  * Locks: the lane thread holds no lock while it waits; the emission it waits for takes the API's read lock. The write
  * lock is held by `setProvider` while it registers (not during `initialize()`, which the SDK runs on its task
  * executor), by handler registration and by `OpenFeatureAPI.shutdown`'s prepare step, and none of those waits on a
  * lane. A caller of `setProviderAndWait` holds the write lock across a whole `initialize()`; the lane stalls and
  * queues until it is released, then drains. There is no cycle. `Awaitable.await()` ignores interrupts, so `close()`
  * cannot cut a stalled wait short, which is why the thread is a daemon.
  *
  * Not covered: two emissions the owner's delegate issues back to back without awaiting them. Its own pool can swap
  * them before any lane sees them.
  */
final private[openfeature] class OrderedEmitter(owner: EventProvider) {
  import OrderedEmitter._

  private var state: State = State.Idle // guarded by `this`

  /** Whether the lane has started its thread and that thread has not finished. */
  private[openfeature] def isRunning: Boolean = synchronized(state) match {
    case State.Running(ex)      => !ex.isTerminated
    case State.Closed(Some(ex)) => !ex.isTerminated
    case _                      => false
  }

  /** Hand `event` to the owner's emitter after every emission enqueued before it. A no-op once closed. */
  def enqueue(event: ProviderEvent, details: ProviderEventDetails): Unit = enqueue(event, details, () => ())

  /** [[enqueue]], then `delivered` on the lane once the SDK has applied the emission (or dropped it). */
  def enqueue(event: ProviderEvent, details: ProviderEventDetails, delivered: () => Unit): Unit =
    executor().foreach { ex =>
      try ex.execute { () => emitAndAwait(event, details); delivered() }
      catch { case _: RejectedExecutionException => () } // closed between the read and the submit
    }

  /** Run the emissions already enqueued, waiting up to the SDK's own emitter bound, then stop the lane. Idempotent. */
  def close(): Unit = {
    val toStop = synchronized {
      state match {
        case State.Running(ex) => state = State.Closed(Some(ex)); Some(ex)
        case State.Idle        => state = State.Closed(None); None
        case _: State.Closed   => None
      }
    }
    toStop.foreach { ex =>
      ex.shutdown()
      try
        if (!ex.awaitTermination(ShutdownTimeoutSeconds, TimeUnit.SECONDS)) ex.shutdownNow(): Unit
      catch {
        case _: InterruptedException =>
          ex.shutdownNow(): Unit
          Thread.currentThread().interrupt()
      }
    }
  }

  private def executor(): Option[ExecutorService] = synchronized {
    state match {
      case State.Running(ex) => Some(ex)
      case _: State.Closed   => None
      case State.Idle        =>
        val ex = Executors.newSingleThreadExecutor(daemonFactory)
        state = State.Running(ex)
        Some(ex)
    }
  }

  // Dropped, as the wrappers dropped a failed emission before the lane: the owner's emitter rejects once the owner has
  // shut down, and an emission it can no longer deliver has no one to reach. Catching here also keeps the lane's thread.
  private def emitAndAwait(event: ProviderEvent, details: ProviderEventDetails): Unit =
    try owner.emit(event, details).await(): Unit
    catch { case _: Exception => () }
}

private[openfeature] object OrderedEmitter {

  /** `EventSupport.SHUTDOWN_TIMEOUT_SECONDS`, the bound the SDK gives its own emitters. */
  private val ShutdownTimeoutSeconds = 3L

  sealed private trait State
  private object State {
    case object Idle                                     extends State
    final case class Running(ex: ExecutorService)        extends State
    final case class Closed(ex: Option[ExecutorService]) extends State
  }

  private val daemonFactory: ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, "zio-openfeature-emitter")
    t.setDaemon(true)
    t
  }
}
