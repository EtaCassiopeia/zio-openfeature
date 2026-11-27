package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import zio.openfeature.internal.{ApiInstances, ManagedProvider}
import zio.openfeature.internal.LifecycleProbe.Point
import dev.openfeature.sdk.OpenFeatureAPI

/** A swap and a sibling's build share one `initialize()` of a wrapper, and it throws. The sibling decides first, while
  * the swapping instance still serves the wrapper, and defers to it. Whether the swap's own decision then shuts the
  * wrapper down must not depend on whether the sibling, a failed build nothing references, has been collected.
  */
object FailedSiblingCollectedSpec extends ZIOSpecDefault {

  // The deferring sibling's instance, failed and unreferenced, is collected before the swap decides.
  private def collected(api: OpenFeatureAPI, self: FeatureFlagsLive, failing: Gated): UIO[Boolean] =
    Live.live(
      ZIO
        .succeed {
          java.lang.System.gc()
          !ManagedProvider.existing(failing).exists(w => ApiInstances.boundElsewhere(api, self, w))
        }
        .repeat(Schedule.spaced(50.millis) *> Schedule.recurUntil[Boolean](identity))
        .timeout(10.seconds)
        .map(_.isDefined)
    )

  def spec = suite("FailedSiblingCollected")(
    test("the swap shuts the failed wrapper down once even when the sibling that deferred to it has been collected") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Gated(open = true)
      val failing = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          // Held once its binding returns, before it waits on the call: the sibling, deciding first, sees it serving.
          mark <- Hold(_ == Point.SdkBind)
          a    <- probedBuild(api, old, "a", new Holds(List(mark)), owns = true)
          swap <- a.setProvider(failing).exit.fork
          _    <- mark.reached.await
          // The sibling's build joins the same registration and call, then fails with it and defers.
          built   <- ZIO.scoped(syncBuild(api, failing, Some("b"), 10.seconds).unit).exit.fork
          _       <- wrapperIn(api, failing, "b")
          _       <- ZIO.succeed(failing.gate.countDown())
          b       <- built.join
          gone    <- collected(api, a, failing)
          _       <- mark.go
          swapped <- swap.join
          count   <- shutdownsAfterSettling(failing)
        } yield assertTrue(b.isFailure, gone, swapped.isFailure, count == 1)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
