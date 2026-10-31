package zio.openfeature

import zio.openfeature.internal.{OrderedEmitter, ProviderEvaluations}

import dev.openfeature.sdk.{
  Awaitable,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvent,
  ProviderEventDetails,
  Value
}
import zio._
import zio.test._

import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, RejectedExecutionException, TimeUnit}
import scala.jdk.CollectionConverters._

// The lane on its own: emissions in enqueue order without blocking the caller, nothing after close, a draining and
// bounded close, and an owner whose emission fails does not stop the ones after it.
object OrderedEmitterSpec extends ZIOSpecDefault {

  private val details = ProviderEventDetails.builder().build()

  /** An owner whose `emit` records the event, sleeping first for `slow` and failing for `reject`. */
  final private class Owner(
    slow: Set[ProviderEvent] = Set.empty,
    reject: Set[ProviderEvent] = Set.empty,
    gate: Option[CountDownLatch] = None
  ) extends EventProvider {
    val emitted = new CopyOnWriteArrayList[ProviderEvent]()

    override def emit(event: ProviderEvent, details: ProviderEventDetails): Awaitable = {
      gate.foreach(awaitIgnoringInterrupts)
      if (slow(event)) Thread.sleep(100)
      if (reject(event)) throw new RejectedExecutionException("emitter shut down")
      emitted.add(event)
      Awaitable.FINISHED
    }

    def seen: List[ProviderEvent] = emitted.asScala.toList

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "owner" }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
  }

  // Like the SDK's `Awaitable.await()`, which a forced stop of the lane cannot cut short.
  private def awaitIgnoringInterrupts(latch: CountDownLatch): Unit = {
    var interrupted = false
    while (latch.getCount > 0)
      try latch.await()
      catch { case _: InterruptedException => interrupted = true }
    if (interrupted) Thread.currentThread().interrupt()
  }

  private val Ready  = ProviderEvent.PROVIDER_READY
  private val Stale  = ProviderEvent.PROVIDER_STALE
  private val Error  = ProviderEvent.PROVIDER_ERROR
  private val Change = ProviderEvent.PROVIDER_CONFIGURATION_CHANGED

  private def eventually(cond: => Boolean): UIO[Boolean] =
    Live.live(ZIO.succeed(cond).repeatUntil(identity).timeout(5.seconds)).map(_.contains(true))

  def spec = suite("OrderedEmitterSpec")(
    test("emissions reach the owner in enqueue order, and enqueue does not wait for a slow one") {
      val owner = new Owner(slow = Set(Error))
      val lane  = new OrderedEmitter(owner)
      for {
        took <- ZIO.succeed {
          val start = java.lang.System.nanoTime()
          lane.enqueue(Error, details)
          lane.enqueue(Stale, details)
          lane.enqueue(Ready, details)
          java.lang.System.nanoTime() - start
        }
        all <- eventually(owner.seen.size == 3)
        _   <- ZIO.succeed(lane.close())
      } yield assertTrue(all, owner.seen == List(Error, Stale, Ready), took < 50.millis.toNanos)
    },
    test("a lane closed before any emission starts no thread and drops later emissions") {
      val owner = new Owner()
      val lane  = new OrderedEmitter(owner)
      for {
        _       <- ZIO.succeed(lane.close())
        running <- ZIO.succeed(lane.isRunning)
        _       <- ZIO.succeed(lane.enqueue(Ready, details))
        _       <- Live.live(ZIO.sleep(100.millis))
        _       <- ZIO.succeed(lane.close())
      } yield assertTrue(!running, owner.seen.isEmpty)
    },
    test("close runs the emissions already enqueued, stops the thread, and drops what comes after") {
      val owner = new Owner(slow = Set(Error, Stale))
      val lane  = new OrderedEmitter(owner)
      for {
        _       <- ZIO.succeed { lane.enqueue(Error, details); lane.enqueue(Stale, details) }
        started <- ZIO.succeed(lane.isRunning)
        _       <- ZIO.attemptBlocking(lane.close())
        drained <- ZIO.succeed(owner.seen)
        stopped <- eventually(!lane.isRunning)
        _       <- ZIO.succeed(lane.enqueue(Ready, details))
        _       <- Live.live(ZIO.sleep(100.millis))
      } yield assertTrue(started, drained == List(Error, Stale), stopped, owner.seen == List(Error, Stale))
    },
    test("close gives up on a stalled emission within the SDK's bound") {
      val gate  = new CountDownLatch(1)
      val owner = new Owner(gate = Some(gate))
      val lane  = new OrderedEmitter(owner)
      for {
        _    <- ZIO.succeed { lane.enqueue(Ready, details); lane.enqueue(Stale, details) }
        took <- ZIO.attemptBlocking {
          val start = java.lang.System.nanoTime()
          lane.close()
          java.lang.System.nanoTime() - start
        }
        stillRunning <- ZIO.succeed(lane.isRunning) // the stalled wait outlives the forced stop
        _            <- ZIO.succeed(gate.countDown())
        stopped      <- eventually(!lane.isRunning)
        _            <- Live.live(ZIO.sleep(100.millis))
      } yield assertTrue(
        took >= TimeUnit.SECONDS.toNanos(3) - 100.millis.toNanos,
        took < TimeUnit.SECONDS.toNanos(5),
        stillRunning,
        stopped,
        owner.seen == List(Ready) // the queued STALE was discarded by the forced stop
      )
    },
    test("an emission the owner rejects is dropped and the ones after it still arrive, in order") {
      val owner = new Owner(reject = Set(Error))
      val lane  = new OrderedEmitter(owner)
      for {
        _   <- ZIO.succeed { lane.enqueue(Change, details); lane.enqueue(Error, details); lane.enqueue(Stale, details) }
        all <- eventually(owner.seen.size == 2)
        _   <- ZIO.succeed(lane.close())
      } yield assertTrue(all, owner.seen == List(Change, Stale))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(1.minute)
}
