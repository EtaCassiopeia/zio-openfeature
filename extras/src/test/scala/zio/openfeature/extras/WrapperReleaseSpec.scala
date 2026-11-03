package zio.openfeature.extras

import zio._
import zio.openfeature.testkit.{Fault, FaultyProvider, Op}
import zio.test._

/** The wrappers' scope finalizer bounds a delegate's `shutdown()`: one that never returns is abandoned after the bound,
  * with a warning, instead of hanging the scope close.
  */
object WrapperReleaseSpec extends ZIOSpecDefault {

  private val Bound = 100.millis

  private def warnings: UIO[List[String]] =
    ZTestLogger.logOutput.map(_.filter(_.logLevel == LogLevel.Warning).map(_.message()).toList)

  // A `CachingProvider` over `delegate`, released by the wrappers' finalizer with a short bound.
  private def cachingWithShortBound(delegate: FaultyProvider[_]): ZIO[Scope, Any, CachingProvider] =
    CachingProvider
      .make(delegate)
      .flatMap(p =>
        ZIO.acquireRelease(ZIO.succeed(p))(p => WrapperRelease.release("CachingProvider", Bound)(p.shutdown()))
      )

  def spec = suite("WrapperReleaseSpec")(
    test("a delegate whose shutdown hangs does not hang the scope close past the bound") {
      for {
        delegate <- FaultyProvider.scoped(Map("flag" -> true), name = "hung")
        _        <- delegate.script(Op.Shutdown, Fault.HangUntilReleased)
        start    <- Clock.nanoTime
        _        <- ZIO.scoped(cachingWithShortBound(delegate))
        elapsed  <- Clock.nanoTime.map(end => Duration.fromNanos(end - start))
        _        <- delegate.release
        warned   <- warnings
      } yield assertTrue(
        elapsed < 2.seconds,
        warned.exists(_.contains(s"CachingProvider shutdown did not finish within ${Bound.toMillis}ms"))
      )
    },
    test("a delegate whose shutdown throws is logged, and the scope closes") {
      for {
        delegate <- FaultyProvider.scoped(Map("flag" -> true), name = "throwing")
        _        <- delegate.script(Op.Shutdown, Fault.Throw(new RuntimeException("boom")))
        _        <- ZIO.scoped(cachingWithShortBound(delegate))
        warned   <- warnings
      } yield assertTrue(warned.exists(_.contains("CachingProvider shutdown failed")))
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@ TestAspect.sequential
}
