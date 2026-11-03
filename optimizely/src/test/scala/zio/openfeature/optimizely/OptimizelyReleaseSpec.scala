package zio.openfeature.optimizely

import zio._
import zio.openfeature.testkit.{Fault, FaultyProvider, Op}
import zio.test._

/** The scope finalizer of `OptimizelyProvider.scoped`: a provider whose `shutdown()` throws is logged and the scope
  * closes.
  */
object OptimizelyReleaseSpec extends ZIOSpecDefault {

  def spec = suite("OptimizelyReleaseSpec")(
    test("a shutdown that throws is swallowed and logged, and the scope closes") {
      for {
        provider <- FaultyProvider.scoped(name = "throwing")
        _        <- provider.script(Op.Shutdown, Fault.Throw(new RuntimeException("boom")))
        _        <- ZIO.scoped(ZIO.acquireRelease(ZIO.succeed(provider))(OptimizelyProvider.releaseProvider))
        shutdown <- provider.calls(Op.Shutdown)
        logs     <- ZTestLogger.logOutput
      } yield assertTrue(
        shutdown.nonEmpty,
        logs.exists(e => e.logLevel == LogLevel.Warning && e.message().contains("Optimizely provider shutdown failed"))
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
}
