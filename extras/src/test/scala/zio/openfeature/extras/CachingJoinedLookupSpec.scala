package zio.openfeature.extras

import dev.openfeature.sdk.ImmutableContext
import zio.openfeature.testkit.{Fault, FaultyProvider, Op}
import zio._
import zio.test._

// Concurrent callers for one key share a single in-flight lookup. A caller that joined someone else's lookup must not
// be handed that lookup's failure: it was not its own evaluation.
object CachingJoinedLookupSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  def spec = suite("CachingProvider joined lookups")(
    test("a caller that joined a lookup whose thread was interrupted is served by its own evaluation") {
      for {
        delegate <- FaultyProvider.scoped(Map("x" -> true), name = "delegate")
        _        <- delegate.script(Op.EvaluateBoolean, Fault.HangUntilReleased, Fault.Succeed)
        cache = CachingProvider(delegate)
        owner  <- ZIO.attemptBlockingInterrupt(cache.getBooleanEvaluation("x", false, ctx)).fork
        _      <- delegate.awaitEntered(Op.EvaluateBoolean)
        joiner <- ZIO
          .attemptBlocking((Thread.currentThread().getName, cache.getBooleanEvaluation("x", false, ctx)))
          .fork
        _      <- ZIO.sleep(300.millis)
        _      <- owner.interrupt
        joined <- joiner.join.either
        calls  <- delegate.calls(Op.EvaluateBoolean)
        later  <- ZIO.attemptBlocking(cache.getBooleanEvaluation("x", false, ctx).getValue.booleanValue())
      } yield assertTrue(
        joined.exists(_._2.getValue.booleanValue()),
        calls.size == 2,
        calls.headOption.exists(_.outcome.isInstanceOf[FaultyProvider.Outcome.Threw]),
        // The joiner's own evaluation runs on the joiner's thread, as every miss does.
        joined.toOption.map(_._1) == calls.lift(1).map(_.thread),
        later
      )
    },
    test("a caller that joined a lookup that threw is served, while the caller that ran it gets its failure") {
      for {
        delegate <- FaultyProvider.scoped(Map("x" -> true), name = "delegate")
        _        <- delegate.script(
          Op.EvaluateBoolean,
          Fault.Delay(400.millis, Fault.Throw(new RuntimeException("transient"))),
          Fault.Succeed
        )
        cache = CachingProvider(delegate)
        owner  <- ZIO.attemptBlocking(cache.getBooleanEvaluation("x", false, ctx)).fork
        _      <- delegate.awaitEntered(Op.EvaluateBoolean)
        joiner <- ZIO.attemptBlocking(cache.getBooleanEvaluation("x", false, ctx)).fork
        own    <- owner.join.either
        joined <- joiner.join.either
        calls  <- delegate.calls(Op.EvaluateBoolean)
      } yield assertTrue(
        own.left.exists(_.getMessage.contains("transient")),
        joined.exists(_.getValue.booleanValue()),
        calls.size == 2,
        calls.headOption.exists(_.outcome.isInstanceOf[FaultyProvider.Outcome.Threw])
      )
    },
    test("concurrent callers of a lookup that succeeds still share one evaluation") {
      for {
        delegate <- FaultyProvider.scoped(Map("x" -> true), name = "delegate")
        _        <- delegate.script(Op.EvaluateBoolean, Fault.Delay(300.millis))
        cache = CachingProvider(delegate)
        results <- ZIO.foreachPar(1 to 8)(_ =>
          ZIO.attemptBlocking(cache.getBooleanEvaluation("x", false, ctx).getValue.booleanValue())
        )
        calls <- delegate.calls(Op.EvaluateBoolean).map(_.size)
      } yield assertTrue(results.forall(identity), calls == 1)
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(1.minute)
}
