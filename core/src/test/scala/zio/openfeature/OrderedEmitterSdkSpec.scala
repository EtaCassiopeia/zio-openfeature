package zio.openfeature

import zio.openfeature.internal.{OrderedEmitter, ProviderEvaluations}

import dev.openfeature.sdk.{
  Awaitable,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

// The lane against the real SDK, which `formal/lane` models: the SDK's emitter pool and its API lock. The slot follows
// the order of `enqueue` while writers take the write lock; an emission is held, not lost, while a `setProviderAndWait`
// holds that lock; and `close()`'s bound cuts off what is still queued behind it, the limit the model's witness shows.
object OrderedEmitterSdkSpec extends ZIOSpecDefault {

  private val details = ProviderEventDetails.builder().build()

  /** A real `EventProvider`, counting the emissions the lane hands to the SDK; `initialize()` runs `onInit`. */
  final private class Owner(onInit: () => Unit = () => ()) extends EventProvider {
    val handed = new AtomicInteger(0)

    override def emit(event: ProviderEvent, details: ProviderEventDetails): Awaitable = {
      handed.incrementAndGet()
      super.emit(event, details)
    }

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "lane-owner" }
    override def initialize(ctx: OFEvaluationContext): Unit                                    = onInit()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
  }

  private val cycle   = Vector(ProviderEvent.PROVIDER_READY, ProviderEvent.PROVIDER_STALE, ProviderEvent.PROVIDER_ERROR)
  private val stateOf = Map(
    ProviderEvent.PROVIDER_READY -> ProviderState.READY,
    ProviderEvent.PROVIDER_STALE -> ProviderState.STALE,
    ProviderEvent.PROVIDER_ERROR -> ProviderState.ERROR
  )

  /** An isolated API whose "lane" slot holds `owner`, initialized. */
  private def registered(owner: Owner): OpenFeatureAPI = {
    val api = OpenFeatureAPI.createIsolated()
    api.setProviderAndWait("lane", owner)
    api
  }

  /** A `setProviderAndWait` on another slot, in a thread of its own, once it is inside `initialize()` and so holds the
    * API's write lock; `release` lets it finish.
    */
  private def holdingTheWriteLock(api: OpenFeatureAPI, release: CountDownLatch): Task[Thread] =
    ZIO.attemptBlocking {
      val entered = new CountDownLatch(1)
      val holder  =
        new Thread(() => api.setProviderAndWait("holder", new Owner(() => { entered.countDown(); release.await() })))
      holder.setDaemon(true)
      holder.start()
      if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the holder never reached initialize()")
      holder
    }

  private def eventually(cond: => Boolean): UIO[Boolean] =
    Live.live(ZIO.succeed(cond).repeatUntil(identity).timeout(10.seconds)).map(_.contains(true))

  def spec = suite("OrderedEmitterSdkSpec")(
    test("the slot follows the order of enqueue while writers keep taking the write lock") {
      val owner                   = new Owner
      val api                     = registered(owner)
      val lane                    = new OrderedEmitter(owner)
      val client                  = api.getClient("lane")
      val n                       = 200
      val seen                    = new ConcurrentHashMap[Int, ProviderState]()
      val writesAt                = new ConcurrentHashMap[Int, Int]()
      val writing                 = new AtomicBoolean(true)
      val writes                  = new AtomicInteger(0)
      val emissions               = (0 until n).map(k => cycle(k % cycle.size))
      def writer(id: Int): Thread = {
        val t = new Thread(() =>
          while (writing.get()) {
            val handler: java.util.function.Consumer[dev.openfeature.sdk.EventDetails] = _ => ()
            api.onProviderReady(handler)
            api.removeHandler(ProviderEvent.PROVIDER_READY, handler)
            api.setProvider(s"other-$id", new Owner)
            writes.incrementAndGet()
            java.util.concurrent.locks.LockSupport.parkNanos(50000L) // let queued readers in between passes
          }
        )
        t.setDaemon(true)
        t
      }
      val writers = (1 to 2).map(writer)
      (for {
        _ <- ZIO.succeed(writers.foreach(_.start()))
        _ <- ZIO.succeed(emissions.zipWithIndex.foreach { case (e, k) =>
          lane.enqueue(e, details, () => { writesAt.put(k, writes.get()); seen.put(k, client.getProviderState): Unit })
        })
        all <- eventually(seen.size == n)
        _   <- ZIO.succeed(writing.set(false))
      } yield assertTrue(
        all,
        writesAt.get(n - 1) > writesAt.get(0), // the writers took the lock while the emissions landed
        (0 until n).forall(k => seen.get(k) == stateOf(emissions(k)))
      )).ensuring(ZIO.succeed {
        writing.set(false)
        writers.foreach(_.join(5000L))
        lane.close()
        api.shutdown()
      })
    },
    test("while a setProviderAndWait holds the write lock, one emission is handed over and none lands") {
      val owner   = new Owner
      val api     = registered(owner)
      val lane    = new OrderedEmitter(owner)
      val client  = api.getClient("lane")
      val release = new CountDownLatch(1)
      val landed  = new ConcurrentHashMap[Int, ProviderState]()
      (for {
        _      <- holdingTheWriteLock(api, release)
        before <- ZIO.succeed(owner.handed.get())
        _      <- ZIO.succeed {
          lane.enqueue(ProviderEvent.PROVIDER_STALE, details, () => landed.put(1, client.getProviderState): Unit)
          lane.enqueue(ProviderEvent.PROVIDER_ERROR, details, () => landed.put(2, client.getProviderState): Unit)
        }
        first  <- eventually(owner.handed.get() - before == 1)
        _      <- Live.live(ZIO.sleep(500.millis)) // neither a second hand-over nor a landing follows
        held   <- ZIO.succeed(landed.isEmpty)
        handed <- ZIO.succeed(owner.handed.get() - before)
        _      <- ZIO.succeed(release.countDown())
        both   <- eventually(landed.size == 2)
      } yield assertTrue(
        first,
        held,
        handed == 1,
        both,
        landed.get(1) == ProviderState.STALE,
        landed.get(2) == ProviderState.ERROR
      )).ensuring(ZIO.succeed { release.countDown(); lane.close(); api.shutdown() })
    },
    test("close gives up within its bound behind a held write lock, and what was still queued is never delivered") {
      val owner     = new Owner
      val api       = registered(owner)
      val lane      = new OrderedEmitter(owner)
      val release   = new CountDownLatch(1)
      val delivered = new ConcurrentHashMap[Int, Boolean]()
      (for {
        _ <- holdingTheWriteLock(api, release)
        _ <- ZIO.succeed((1 to 3).foreach { k =>
          lane.enqueue(ProviderEvent.PROVIDER_STALE, details, () => delivered.put(k, true): Unit)
        })
        took <- ZIO
          .attemptBlockingInterrupt {
            val start = java.lang.System.nanoTime()
            lane.close()
            java.lang.System.nanoTime() - start
          }
          .timeoutFail(new IllegalStateException("close() did not return within 10 s"))(10.seconds)
          .withClock(Clock.ClockLive)
        _     <- ZIO.succeed(release.countDown())
        first <- eventually(delivered.containsKey(1))
        _     <- Live.live(ZIO.sleep(500.millis))
      } yield assertTrue(
        took >= TimeUnit.SECONDS.toNanos(3) - 100.millis.toNanos,
        took < TimeUnit.SECONDS.toNanos(6),
        first,
        !delivered.containsKey(2),
        !delivered.containsKey(3)
      )).ensuring(ZIO.succeed { release.countDown(); lane.close(); api.shutdown() })
    }
  ) @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
