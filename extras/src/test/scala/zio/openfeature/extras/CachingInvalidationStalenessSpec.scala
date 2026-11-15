package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  Value
}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.test._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** One `CachingProvider` over a versioned delegate: a writer thread changes the delegate and invalidates the cache, in
  * a loop, while reader threads evaluate. An evaluation that starts after an invalidation has returned is never
  * answered with a value older than the delegate's version at that invalidation.
  *
  * An outcome check, not Lincheck: whether a miss in flight across an invalidation is kept is documented as not
  * guaranteed ("still answers its own caller, but its result is not kept"), so no deterministic sequential
  * specification exists for Lincheck to compare against, and a delegate change followed by an invalidation is not one
  * atomic step.
  */
object CachingInvalidationStalenessSpec extends ZIOSpecDefault {

  final private class Versioned extends FeatureProvider {
    val version = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = "versioned" }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(s"$k@${version.get()}", "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
  }

  /** Runs `readers` threads against one writer doing `changes` change-then-invalidate rounds. Returns the stale reads,
    * as "key: got version v, floor f".
    */
  private def run(readers: Int, changes: Int): List[String] = {
    val delegate = new Versioned
    val caching  = CachingProvider(delegate)
    val ctx      = new ImmutableContext("user-1")
    // The delegate's version at the last invalidation that has returned.
    val floor = new AtomicInteger(0)
    val done  = new java.util.concurrent.atomic.AtomicBoolean(false)
    val stale = new AtomicReference(List.empty[String])
    val start = new CountDownLatch(1)

    val writer = new Thread(() => {
      start.await()
      (1 to changes).foreach { _ =>
        val v = delegate.version.incrementAndGet()
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(caching.invalidateAll).getOrThrowFiberFailure())
        floor.set(v)
      }
      done.set(true)
    })
    val rs = (0 until readers).map { t =>
      new Thread(() => {
        start.await()
        val key = if (t % 2 == 0) "a" else "b"
        while (!done.get()) {
          val f   = floor.get()
          val got = caching.getStringEvaluation(key, "default", ctx).getValue.stripPrefix(s"$key@").toInt
          if (got < f) stale.updateAndGet(s"$key: got version $got, floor $f" :: _)
        }
      })
    }
    (writer +: rs).foreach(_.start())
    start.countDown()
    (writer +: rs).foreach(_.join())
    stale.get()
  }

  def spec = suite("CachingInvalidationStalenessSpec")(
    test("no evaluation started after an invalidation returned is answered from before it") {
      ZIO.attemptBlockingInterrupt(run(readers = 4, changes = 2000)).map(stale => assertTrue(stale.take(5).isEmpty))
    } @@ TestAspect.nonFlaky(20)
  ) @@ TestAspect.timeout(5.minutes)
}
