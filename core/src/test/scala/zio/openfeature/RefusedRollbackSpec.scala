package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import zio.openfeature.internal.{ApiInstances, ManagedProvider}
import zio.openfeature.internal.LifecycleProbe.Point
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderStateAccess
}
import dev.openfeature.sdk.exceptions.GeneralError
import java.util.concurrent.atomic.AtomicBoolean

/** A failed swap's rollback whose re-registration of the previous provider the SDK refuses before it binds: the slot
  * keeps the failed provider. The instance then holds that provider as installed, so the next swap or the close shuts
  * it down, and the previous provider, bound nowhere, is shut down by an owner of the API or left to it. The refusal is
  * forced by binding the domain-scoped previous provider under another domain while the rollback waits, which the SDK's
  * `validateDomainScopedBinding` refuses for a second domain.
  */
object RefusedRollbackSpec extends ZIOSpecDefault {

  private def named(name: String, failure: Option[String] = None, scoped: Boolean = false): Counting =
    new Counting(failure, open = true) {
      override def getMetadata: Metadata     = new Metadata { override def getName: String = name }
      override def isDomainScoped(): Boolean = scoped
    }

  // Fails its first initialize() only: a rollback onto it then restores it.
  private def failsOnce(name: String): Counting =
    new Counting(open = true) {
      override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
      override def initialize(ctx: OFEvaluationContext): Unit = {
        super.initialize(ctx)
        if (inits.get() == 1) throw new GeneralError(s"$name boom")
      }
    }

  private def holds(api: OpenFeatureAPI, slot: String, provider: FeatureProvider): Boolean =
    api.getProvider(slot) eq ManagedProvider.existing(provider).getOrElse(provider)

  // Shutdowns once the SDK's executor has had time to run what it queued.
  private def settled(provider: Gated): UIO[Int] =
    Live.live(ZIO.sleep(300.millis) *> ZIO.succeed(provider.shutdowns.get()))

  /** Holds the swap at its rollback, binds `previous`'s wrapper under `elsewhere`, then lets the rollback run: its
    * re-registration of `previous` in the instance's own slot is refused before it binds.
    */
  private def refusedRollback(
    api: OpenFeatureAPI,
    instance: FeatureFlagsLive,
    hold: Hold,
    previous: Counting,
    failing: FeatureProvider,
    elsewhere: String
  ): Task[Exit[FeatureFlagError, Unit]] =
    for {
      swap <- instance.setProvider(failing).exit.fork
      _    <- hold.reached.await
      // The wrapper is still attached to the registration the swap replaced: bound the way the library binds it,
      // resetting that attachment. Its initialize() is left to the SDK's executor. Released whatever happens, so a
      // failing step does not leave the swap, and its lock, held.
      _ <- ZIO
        .attemptBlocking(
          ProviderStateAccess.bindReclaiming(api, elsewhere, ManagedProvider.existing(previous).get, () => ())
        )
        .ensuring(hold.go)
      exit <- swap.join
    } yield exit

  def spec = suite("RefusedRollbackSpec")(
    test(
      "a rollback refused before it binds leaves the failed provider installed, and the next failing swap and the close shut every provider down once"
    ) {
      val api      = OpenFeatureAPI.createIsolated()
      val previous = named("previous", scoped = true)
      val failed   = failsOnce("failed")
      val next     = named("next", Some("next boom"))
      val armed    = new AtomicBoolean(true)
      for {
        run <- ZIO.scoped {
          for {
            hold    <- Hold(_ == Point.SwapRollback, armed)
            a       <- probedBuild(api, previous, "a", new Holds(List(hold)), owns = true)
            refused <- refusedRollback(api, a, hold, previous, failed, "x")
            // The instance installs what the slot routes to. The previous provider is bound under the other domain,
            // which refused it, so the instance leaves it to that slot: the API's shutdown reaches it, once.
            routed       <- ZIO.succeed(holds(api, "a", failed))
            named        <- a.providerMetadata.map(_.name)
            status       <- a.providerStatus
            previousShut <- settled(previous)
            second       <- a.setProvider(next).exit
            // The second rollback restores the failed provider, the instance's installed one: the SDK shuts `next` down.
            restored <- ZIO.succeed(holds(api, "a", failed))
            nextShut <- settled(next)
          } yield (
            refused.isFailure,
            routed && named == "failed",
            status,
            previousShut,
            second.isFailure,
            restored,
            nextShut
          )
        }
        counts <- ZIO.foreach(List(previous, failed, next))(settled)
      } yield {
        val (refused, routed, status, previousShut, second, restored, nextShut) = run
        assertTrue(
          refused,
          routed,
          status == ProviderStatus.Error,
          previousShut == 0,
          second,
          restored,
          nextShut == 1,
          counts == List(1, 1, 1)
        )
      }
    },
    test(
      "a successful swap after a refused rollback shuts the failed provider down, and the close the previous one, once each"
    ) {
      val api      = OpenFeatureAPI.createIsolated()
      val previous = named("previous", scoped = true)
      val failed   = named("failed", Some("failed boom"))
      val next     = named("next")
      val armed    = new AtomicBoolean(true)
      for {
        run <- ZIO.scoped {
          for {
            hold    <- Hold(_ == Point.SwapRollback, armed)
            a       <- probedBuild(api, previous, "a", new Holds(List(hold)), owns = true)
            refused <- refusedRollback(api, a, hold, previous, failed, "x")
            swapped <- a.setProvider(next).exit
            status  <- a.providerStatus
            early   <- ZIO.foreach(List(previous, failed, next))(settled)
          } yield (refused.isFailure, swapped.isSuccess, status, early)
        }
        counts <- ZIO.foreach(List(previous, failed, next))(settled)
      } yield {
        val (refused, swapped, status, early) = run
        assertTrue(
          refused,
          swapped,
          status == ProviderStatus.Ready,
          early == List(0, 1, 0),
          counts == List(1, 1, 1)
        )
      }
    },
    test(
      "an instance that does not own its API leaves the previous provider of a refused rollback to the API's owner"
    ) {
      val api      = OpenFeatureAPI.createIsolated()
      val owned    = named("owned")
      val previous = named("previous", scoped = true)
      val failed   = named("failed", Some("failed boom"))
      val armed    = new AtomicBoolean(true)
      for {
        run <- ZIO.scoped {
          for {
            _       <- syncBuild(api, owned, None, 10.seconds, owns = true)
            hold    <- Hold(_ == Point.SwapRollback, armed)
            a       <- probedBuild(api, previous, "a", new Holds(List(hold)))
            refused <- refusedRollback(api, a, hold, previous, failed, "x")
            routed  <- ZIO.succeed(holds(api, "a", failed))
            named   <- a.providerMetadata.map(_.name)
            early   <- settled(previous)
            left    <- ZIO.succeed(
              ApiInstances.leftBehindRunning(api).exists(w => ManagedProvider.existing(previous).contains(w))
            )
          } yield (refused.isFailure, routed, named, early, left)
        }
        counts <- ZIO.foreach(List(previous, failed))(settled)
      } yield {
        val (refused, routed, named, early, left) = run
        assertTrue(refused, routed, named == "failed", early == 0, left, counts == List(1, 1))
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
