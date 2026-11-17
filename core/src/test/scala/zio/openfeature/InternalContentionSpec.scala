package zio.openfeature

import zio.openfeature.internal.{FallbackLogLimiter, ManagedProvider, OrderedEmitter, ProviderEvaluations}

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

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** High-contention checks for the internal lane and the fallback log limiter: outcomes, not steps.
  *
  *   - OrderedEmitter: many Java threads enqueue at once; every thread's emissions arrive in its own order, none is
  *     lost, and once `close()` returns nothing more is emitted.
  *   - FallbackLogLimiter: many fibers admit the same key in one window; exactly one is admitted, and the next window's
  *     single admission reports every suppressed event.
  */
object InternalContentionSpec extends ZIOSpecDefault {

  final private class Owner extends EventProvider {
    val emitted = new ConcurrentLinkedQueue[String]()
    override def emit(event: ProviderEvent, details: ProviderEventDetails): Awaitable = {
      emitted.add(details.getMessage)
      Awaitable.FINISHED
    }
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

  private def detail(msg: String) = ProviderEventDetails.builder().message(msg).build()

  /** Starts `threads` Java threads together, each enqueuing `perThread` emissions tagged "t:i"; optionally closes the
    * lane concurrently once `closeAfter` emissions were enqueued in total. Returns (emitted, emitted after close).
    */
  private def laneRun(threads: Int, perThread: Int, closeConcurrently: Boolean): (List[String], Int) = {
    val owner = new Owner
    val lane  = new OrderedEmitter(owner)
    val start = new CountDownLatch(1)
    val ts    = (0 until threads).map { t =>
      val th = new Thread(() => {
        start.await()
        (0 until perThread).foreach(i => lane.enqueue(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, detail(s"$t:$i")))
      })
      th.setDaemon(true)
      th.start()
      th
    }
    start.countDown()
    val afterClose =
      if (closeConcurrently) {
        while (owner.emitted.size < threads * perThread / 4) Thread.onSpinWait()
        lane.close()
        val atClose = owner.emitted.size
        ts.foreach(_.join(10000))
        Thread.sleep(50)
        owner.emitted.size - atClose
      } else {
        ts.foreach(_.join(10000))
        lane.close()
        0
      }
    (owner.emitted.asScala.toList, afterClose)
  }

  final private class Counted(val id: Int) extends dev.openfeature.sdk.FeatureProvider {
    val shutdowns                 = new AtomicInteger(0)
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = s"p$id" }
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

  /** A switcher thread points a switchable wrapper at fresh providers, shutting down each one it gets back (and each
    * one the wrapper refused), while `shutters` threads shut the wrapper down at the same moment. Ownership is exact
    * when every provider is shut down exactly once: the wrapper's shutdown reaches the delegate it held, the switcher
    * the rest. Returns each provider's shutdown count.
    */
  private def switchVsShutdown(switches: Int, shutters: Int): List[Int] = {
    val initial = new Counted(0)
    val wrapper = ManagedProvider.switchable(initial)
    wrapper.initialize(new dev.openfeature.sdk.ImmutableContext())
    val providers = initial :: (1 to switches).map(new Counted(_)).toList
    val start     = new CountDownLatch(1)
    val switcher  = new Thread(() => {
      start.await()
      providers.tail.foreach { p =>
        try wrapper.switchTo(p).shutdown()
        catch { case _: IllegalStateException => p.shutdown() }
      }
    })
    val ts = switcher :: List.fill(shutters)(new Thread(() => {
      start.await()
      (0 until switches / 4).foreach(_ => Thread.onSpinWait())
      wrapper.shutdown()
    }))
    ts.foreach { t => t.setDaemon(true); t.start() }
    start.countDown()
    ts.foreach(_.join(10000))
    providers.map(_.shutdowns.get())
  }

  /** Per-thread sequences, in emission order. */
  private def perThread(emitted: List[String]): Map[Int, List[Int]] =
    emitted.map(_.split(':')).groupMap(_(0).toInt)(_(1).toInt)

  def spec = suite("InternalContentionSpec")(
    suite("OrderedEmitter")(
      test("concurrent enqueuers: per-thread FIFO, nothing lost") {
        ZIO.attemptBlocking(laneRun(8, 500, closeConcurrently = false)).map { case (emitted, _) =>
          val seqs = perThread(emitted)
          assertTrue(emitted.size == 8 * 500, seqs.values.forall(s => s == s.sorted && s == s.distinct))
        }
      } @@ TestAspect.nonFlaky(50),
      test("close during concurrent enqueue: per-thread prefix, nothing emitted after close returns") {
        ZIO.attemptBlocking(laneRun(8, 2000, closeConcurrently = true)).map { case (emitted, after) =>
          val seqs = perThread(emitted)
          // What was emitted for each thread is a prefix of what it enqueued: in order, without holes.
          assertTrue(after == 0, seqs.values.forall(s => s == s.indices.toList))
        }
      } @@ TestAspect.nonFlaky(50)
    ),
    suite("ManagedProvider")(
      test("concurrent shutdown() calls reach the provider once and complete the cycle's signal") {
        ZIO
          .attemptBlocking {
            val p = new Counted(0)
            val w = ManagedProvider(p)
            w.initialize(new dev.openfeature.sdk.ImmutableContext())
            val start = new CountDownLatch(1)
            val ts    = List.fill(16)(new Thread(() => { start.await(); w.shutdown() }))
            ts.foreach(_.start()); start.countDown(); ts.foreach(_.join(10000))
            (p.shutdowns.get(), w.shutDownInCycle)
          }
          .flatMap { case (n, inCycle) =>
            // A fresh wrapper's first cycle signal is the one the shutdown completed.
            assertTrue(n == 1, inCycle) && assertCompletes
          }
      } @@ TestAspect.nonFlaky(50),
      test("switchTo racing shutdown(): every provider is shut down exactly once") {
        ZIO.attemptBlocking(switchVsShutdown(200, 4)).map(counts => assertTrue(counts.forall(_ == 1)))
      } @@ TestAspect.nonFlaky(50)
    ),
    suite("FallbackLogLimiter")(
      test("one admission per window per key under contention, and the next one counts every suppressed event") {
        for {
          limiter <- FallbackLogLimiter.make(FallbackLogging.Throttled(1.minute))
          first   <- ZIO.foreachPar(1 to 6400)(_ => limiter.admit("k")).withParallelism(64)
          _       <- TestClock.adjust(2.minutes)
          second  <- ZIO.foreachPar(1 to 640)(_ => limiter.admit("k")).withParallelism(64)
        } yield assertTrue(
          first.flatten == Vector(0),
          second.flatten == Vector(6399)
        )
      } @@ TestAspect.nonFlaky(20),
      test("distinct keys under contention stay bounded and each tracked key is admitted once") {
        for {
          limiter <- FallbackLogLimiter.make(FallbackLogging.Throttled(1.minute), maxKeys = 32)
          results <- ZIO
            .foreachPar((1 to 4000).toVector)(i => limiter.admit(s"k${i % 100}").map(i % 100 -> _))
            .withParallelism(64)
          tracked <- limiter.trackedKeys
        } yield {
          val admitted = results.collect { case (k, Some(_)) => k }
          // 32 tracked keys admitted once each, plus one line for the shared overflow bucket.
          assertTrue(tracked == 32, admitted.size == 33)
        }
      } @@ TestAspect.nonFlaky(20)
    )
  ) @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
}
