package zio.openfeature

import dev.openfeature.sdk.OpenFeatureAPI
import zio._
import zio.test._

import java.lang.reflect.{InvocationHandler, Method, Proxy => JProxy}
import java.util.concurrent.atomic.AtomicInteger

/** Pins an interrupt in the gap of `FeatureFlagRegistryLive.memoizedClient` between the lock section that inserts the
  * domain's promise and the `forkIn` that starts the build which settles it.
  *
  * The seam wraps the registry's real semaphore (see `gatedLock`); no production code changes. The registry is built
  * with its package-private constructor so the seam and the `clients` map can be passed in.
  */
object RegistryInsertForkGapSpec extends ZIOSpecDefault {

  /** The registry's real semaphore behind a JDK proxy (zio.Semaphore is sealed, so it cannot be subclassed). Only
    * `withPermit` is intercepted: on its first call (the `getClient` insert) the caller pauses on `gate` right after
    * the permit section returns, provided it is interruptible there. Everything else goes straight to the real
    * semaphore.
    */
  private def gatedLock(real: Semaphore, inserted: Promise[Nothing, Unit], gate: Promise[Nothing, Unit]): Semaphore = {
    val calls                                   = new AtomicInteger(0)
    def pause(implicit trace: Trace): UIO[Unit] =
      ZIO.suspendSucceed {
        if (calls.getAndIncrement() != 0) ZIO.unit
        else
          ZIO.checkInterruptible {
            case InterruptStatus.Interruptible   => inserted.succeed(()) *> gate.await
            case InterruptStatus.Uninterruptible => inserted.succeed(()).unit
          }
      }
    val handler = new InvocationHandler {
      def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        if (method.getName == "withPermit") {
          val zio            = args(0).asInstanceOf[ZIO[Any, Any, Any]]
          implicit val trace = args(1).asInstanceOf[Trace]
          real.withPermit(zio) <* pause
        } else method.invoke(real, (if (args == null) Array.empty[AnyRef] else args): _*)
    }
    JProxy
      .newProxyInstance(getClass.getClassLoader, Array[Class[_]](classOf[Semaphore]), handler)
      .asInstanceOf[Semaphore]
  }

  final private case class Rig(
    registry: FeatureFlagRegistry,
    clients: Ref[Map[String, Promise[FeatureFlagError, FeatureFlagsLive]]],
    inserted: Promise[Nothing, Unit],
    gate: Promise[Nothing, Unit]
  )

  private val rig: ZIO[Scope, Nothing, Rig] =
    for {
      scope      <- ZIO.service[Scope]
      api        <- ZIO.succeed(OpenFeatureAPI.createIsolated())
      clients    <- Ref.make(Map.empty[String, Promise[FeatureFlagError, FeatureFlagsLive]])
      providers  <- Ref.make(Map.empty[String, dev.openfeature.sdk.FeatureProvider])
      defaultRef <- Ref.make(Option.empty[Promise[FeatureFlagError, FeatureFlagsLive]])
      real       <- Semaphore.make(1)
      inserted   <- Promise.make[Nothing, Unit]
      gate       <- Promise.make[Nothing, Unit]
      hooks      <- Ref.make(List.empty[FeatureHook])
      provider   <- ZIO.succeed(new dev.openfeature.sdk.NoOpProvider())
      registry = new FeatureFlagRegistryLive(
        provider,
        api,
        scope,
        clients,
        providers,
        defaultRef,
        gatedLock(real, inserted, gate),
        hooks,
        FeatureFlagsConfig.default.withInitTimeout(500.millis),
        _ => zio.openfeature.internal.LifecycleProbe.none
      )
    } yield Rig(registry, clients, inserted, gate)

  def spec = suite("RegistryInsertForkGapSpec")(
    test("an interrupt between the promise insert and the build fork leaves the domain usable") {
      ZIO.scoped {
        for {
          r     <- rig
          first <- r.registry.getClient("d").fork
          _     <- r.inserted.await
          _     <- first.interrupt
          // 10x the registry's init bound: a live build settles (or fails) its promise well within it.
          second  <- r.registry.getClient("d").either.timeout(5.seconds)
          settled <- r.clients.get.flatMap(_.get("d") match {
            case Some(p) => p.isDone.map(Some(_))
            case None    => ZIO.none
          })
        } yield assertTrue(second.exists(_.isRight)) && assertTrue(!settled.contains(false))
      }
    } @@ TestAspect.nonFlaky(50),
    test("control: the same seam with no interrupt builds the client") {
      ZIO.scoped {
        for {
          r      <- rig
          first  <- r.registry.getClient("d").fork
          _      <- r.inserted.await
          _      <- r.gate.succeed(())
          a      <- first.join.either
          second <- r.registry.getClient("d").either.timeout(5.seconds)
        } yield assertTrue(a.isRight, second.exists(_.isRight))
      }
    } @@ TestAspect.nonFlaky(50)
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
}
