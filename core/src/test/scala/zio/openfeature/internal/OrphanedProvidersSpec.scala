package zio.openfeature.internal

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import dev.openfeature.sdk.OpenFeatureAPI

/** The orphan list on its own: a settle claims its entry once, a swap that installs the provider again waits for a
  * settle under way, and one entry settling leaves the others for `resolveAll`.
  */
object OrphanedProvidersSpec extends ZIOSpecDefault {

  private def wrapperOf(provider: Gated): ManagedProvider = ManagedProvider(provider)

  /** `previous` registered in `slot` and settled; then `incoming` registered there, its `initialize()` still running,
    * recorded as the registration that replaced `previous`.
    */
  private def orphan(api: OpenFeatureAPI, orphans: OrphanedProviders, slot: String, previous: Gated, incoming: Gated) =
    for {
      _ <- ProviderRegistration
        .register(api, Some(slot), Bound.Managed(wrapperOf(previous)))
        .flatMap(_.settle(5.seconds))
      registration <- ProviderRegistration.register(api, Some(slot), Bound.Managed(wrapperOf(incoming)))
      _            <- orphans.record(Bound.Managed(wrapperOf(previous)), "previous", "incoming", registration, ZIO.unit)
    } yield ()

  private def resolvedFor(provider: Gated): LifecycleProbe.Point => Boolean = {
    case LifecycleProbe.Point.OrphanResolved(previous, _) => previous eq wrapperOf(provider)
    case _                                                => false
  }

  def spec = suite("OrphanedProvidersSpec")(
    test("a settle under way is waited for by a swap that installs its provider again") {
      val api      = OpenFeatureAPI.createIsolated()
      val previous = new Gated(open = true)
      val incoming = new Gated()
      for {
        hold <- Hold(resolvedFor(previous))
        orphans = OrphanedProviders(api, new Holds(List(hold)))
        _       <- orphan(api, orphans, "s", previous, incoming)
        _       <- ZIO.succeed(incoming.gate.countDown())
        _       <- hold.reached.await
        waiting <- orphans.awaitSettling(wrapperOf(previous)).fork
        _       <- suspendedOrDone(waiting)
        before  <- waiting.poll
        _       <- hold.go
        _       <- waiting.join
      } yield assertTrue(before.isEmpty)
    },
    test("resolveAll during a settle already under way does not settle that entry again") {
      val api      = OpenFeatureAPI.createIsolated()
      val previous = new Gated(open = true)
      val incoming = new Gated()
      for {
        hold <- Hold(resolvedFor(previous))
        probe   = new Holds(List(hold))
        orphans = OrphanedProviders(api, probe)
        _         <- orphan(api, orphans, "s", previous, incoming)
        _         <- ZIO.succeed(incoming.gate.countDown())
        _         <- hold.reached.await
        resolving <- orphans.resolveAll.fork
        _         <- suspendedOrDone(resolving)
        _         <- hold.go
        _         <- resolving.join
        hits      <- ZIO.succeed(probe.count("OrphanResolved"))
        count     <- ZIO.succeed(previous.shutdowns.get())
      } yield assertTrue(hits == 1, count <= 1)
    },
    test("one entry settling leaves the others for resolveAll to shut down") {
      val api       = OpenFeatureAPI.createIsolated()
      val previous1 = new Gated(open = true)
      val previous2 = new Gated(open = true)
      val failing   = new Gated(failure = Some("boom"))
      val pending   = new Gated()
      for {
        dropped <- Promise.make[Nothing, Unit]
        probe = new LifecycleProbe {
          def at(p: LifecycleProbe.Point): UIO[Unit] = p match {
            case LifecycleProbe.Point.OrphanDropped(prev, _) if prev eq wrapperOf(previous1) => dropped.succeed(()).unit
            case _                                                                           => ZIO.unit
          }
        }
        orphans = OrphanedProviders(api, probe)
        _      <- orphan(api, orphans, "s1", previous1, failing)
        _      <- orphan(api, orphans, "s2", previous2, pending)
        _      <- ZIO.succeed(failing.gate.countDown())
        _      <- dropped.await
        _      <- orphans.resolveAll
        first  <- ZIO.succeed(previous1.shutdowns.get())
        second <- ZIO.succeed(previous2.shutdowns.get())
        _      <- ZIO.succeed(pending.gate.countDown())
      } yield assertTrue(first == 1, second == 1)
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}
