package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Reason,
  Value
}
import zio._
import zio.test._
import zio.test.TestAspect.{samples, sequential, timeout, withLiveClock}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

// The invalidation race as a property: lookups parked inside the delegate (generated count, keys and duplication) are
// in flight when the cache is invalidated, by `invalidateAll` or by a delegate configuration-change event. Evaluations
// started after the invalidation, and every read after the parked lookups finish, must see the post-invalidation value:
// nothing computed before the invalidation is served after it.
object CachingInvalidationPropertySpec extends ZIOSpecDefault {

  private val Old = "old"
  private val New = "new"

  /** Answers `current`, read on entry. A call that read `Old` parks until `gate` opens. */
  private class LatchedProvider(parkedKeys: Int) extends EventProvider {
    val current                 = new AtomicReference[String](Old)
    val entered: CountDownLatch = new CountDownLatch(parkedKeys)
    val gate: CountDownLatch    = new CountDownLatch(1)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "latched" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getStringEvaluation(
      key: String,
      default: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      val value = current.get()
      if (value == Old) { entered.countDown(); gate.await() }
      ProviderEvaluations.of[String](value, Reason.STATIC.toString)
    }
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")

    def configChanged(): Unit = {
      emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await()
      ()
    }
  }

  final private case class Schedule(parkedKeys: List[Int], postKeys: List[Int], viaEvent: Boolean)

  private val schedules: Gen[Any, Schedule] =
    for {
      parked   <- Gen.listOfBounded(1, 8)(Gen.int(0, 3)) // duplicates dedupe onto one parked lookup
      post     <- Gen.listOfBounded(0, 6)(Gen.int(0, 3))
      viaEvent <- Gen.boolean
    } yield Schedule(parked, post, viaEvent)

  private val ctx = new ImmutableContext("user-1")

  private def eval(cache: CachingProvider, key: Int): Task[String] =
    ZIO.attemptBlocking(cache.getStringEvaluation(s"flag-$key", "default", ctx).getValue)

  def spec = suite("CachingInvalidationPropertySpec")(
    test("no value computed before an invalidation is served after it completes") {
      check(schedules) { case Schedule(parkedKeys, postKeys, viaEvent) =>
        val delegate               = new LatchedProvider(parkedKeys.distinct.size)
        val cache                  = CachingProvider(delegate)
        val invalidate: Task[Unit] =
          if (viaEvent) ZIO.attemptBlocking(delegate.configChanged())
          else cache.invalidateAll
        for {
          _        <- ZIO.attemptBlocking(cache.initialize(ctx)) // attaches the delegate's event channel
          inFlight <- ZIO.foreach(parkedKeys)(k => eval(cache, k).fork)
          _        <- ZIO.attemptBlocking(delegate.entered.await())
          // The delegate changes first, then the cache is invalidated — the order a real provider follows (update its
          // state, then emit CONFIGURATION_CHANGED). The other way round races: a duplicate-key caller that has not yet
          // reached the cache could take a key after the invalidation, still read `Old`, and store it legitimately.
          _          <- ZIO.succeed(delegate.current.set(New))
          _          <- invalidate
          postFibers <- ZIO.foreach(postKeys)(k => eval(cache, k).fork) // start while the old lookups are parked
          _    <- ZIO.sleep(20.millis) // let them reach the cache while the old lookups are still parked
          _    <- ZIO.succeed(delegate.gate.countDown())
          post <- ZIO.foreach(postFibers)(_.join)
          _        <- ZIO.foreachDiscard(inFlight)(_.join) // the parked callers get their own (old) answer
          afterAll <- ZIO.foreach((0 to 3).toList)(k => eval(cache, k))
        } yield assertTrue(post.forall(_ == New), afterAll.forall(_ == New))
      }
    }
  ) @@ sequential @@ withLiveClock @@ samples(20) @@ timeout(2.minutes)
}
