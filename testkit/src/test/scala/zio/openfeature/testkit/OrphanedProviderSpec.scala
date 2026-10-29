package zio.openfeature.testkit

import zio.openfeature.testsupport.LeakCheck.{awaitNoLeaks, snapshot}

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}

// A swap that times out leaves the SDK holding the new provider and nothing holding the previous one: the SDK shuts
// it down only if the new provider's initialize() later succeeds. These pin that the instance shuts it down in every
// other case, exactly once, and never while it serves.
object OrphanedProviderSpec extends ZIOSpecDefault {

  private val initTimeout = 300.millis
  private val testBound   = 30.seconds
  private val LeakBound   = 5.seconds

  private def build(provider: FeatureProvider, owned: Boolean = true): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"orphaned-provider-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owned,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def initFailure(e: FeatureFlagError): Option[Throwable] = e match {
    case FeatureFlagError.ProviderInitializationFailed(t) => Some(t)
    case _                                                => None
  }

  private def lateFailure = Fault.Delay(800.millis, Fault.Throw(new RuntimeException("late failure")))

  private def shutdowns(p: FaultyProvider[_]): UIO[Int] = p.calls(Op.Shutdown).map(_.size)

  /** Polls `read` until `ok` holds or 5 seconds pass, then waits a little longer so a second, unwanted change shows. */
  private def settled[A](read: UIO[A])(ok: A => Boolean): UIO[A] = {
    def loop(left: Int): UIO[A] =
      read.flatMap(a => if (ok(a) || left == 0) ZIO.succeed(a) else ZIO.sleep(50.millis) *> loop(left - 1))
    loop(100) *> ZIO.sleep(300.millis) *> read
  }

  /** Runs `body` in its own scope, then reports what it returned and any fiber or thread it left behind. */
  private def leakFree[A](body: ZIO[Scope, Any, A]): ZIO[Any, Any, (A, List[String])] =
    for {
      baseline <- snapshot
      a        <- ZIO.scoped(body).timeoutFail(s"did not complete within $testBound")(testBound)
      leaked   <- awaitNoLeaks(baseline, LeakBound)
    } yield (a, leaked)

  def spec = suite("OrphanedProviderSpec")(
    test("a timed-out swap whose init later fails gets the previous provider shut down, and the status stays Error") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        _        <- incoming.script(Op.Initialize, lateFailure)
        result   <- leakFree {
          for {
            ff     <- build(previous)
            swap   <- ff.setProvider(incoming).either
            during <- settled(shutdowns(previous))(_ >= 1)
            status <- ff.providerStatus
          } yield (swap.isLeft, during, status)
        }
        ((timedOut, during, status), leaked) = result
        after <- shutdowns(previous)
      } yield assertTrue(timedOut, during == 1, status == ProviderStatus.Error, after == 1, leaked.isEmpty)
    },
    test("a timed-out swap whose init later succeeds leaves the previous provider to the SDK, shut down once") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        _        <- incoming.script(Op.Initialize, Fault.Delay(800.millis))
        result   <- leakFree {
          for {
            ff     <- build(previous)
            swap   <- ff.setProvider(incoming).either
            status <- settled(ff.providerStatus)(_ == ProviderStatus.Ready)
            value  <- ff.boolean("x", false)
          } yield (swap.isLeft, status, value)
        }
        ((timedOut, status, value), leaked) = result
        after <- shutdowns(previous)
      } yield assertTrue(timedOut, status == ProviderStatus.Ready, value, after == 1, leaked.isEmpty)
    },
    test("a later swap while the timed-out provider is still initializing shuts the previous provider down once") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        good     <- FaultyProvider.make(Map("x" -> true), name = "good")
        _        <- incoming.script(Op.Initialize, lateFailure)
        result   <- leakFree {
          for {
            ff     <- build(previous)
            _      <- ff.setProvider(incoming).either
            next   <- ff.setProvider(good).either
            during <- settled(shutdowns(previous))(_ >= 1)
            status <- ff.providerStatus
            value  <- ff.boolean("x", false)
          } yield (next.isRight, during, status, value)
        }
        ((swapped, during, status, value), leaked) = result
        after <- shutdowns(previous)
      } yield assertTrue(swapped, during == 1, status == ProviderStatus.Ready, value, after == 1, leaked.isEmpty)
    },
    test("the previous provider can be installed again once the timed-out provider has failed, and not before") {
      for {
        previous <- FaultyProvider.make(Map("x" -> true), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> false), name = "incoming")
        _        <- incoming.script(Op.Initialize, lateFailure)
        result   <- leakFree {
          for {
            ff     <- build(previous)
            _      <- ff.setProvider(incoming).either
            early  <- ff.setProvider(previous).either
            held   <- ff.providerStatus
            _      <- settled(shutdowns(previous))(_ >= 1)
            back   <- ff.setProvider(previous).either
            status <- ff.providerStatus
            value  <- ff.boolean("x", false)
            during <- shutdowns(previous)
          } yield (early, held, back.isRight, status, value, during)
        }
        ((early, held, restored, status, value, during), leaked) = result
        after <- shutdowns(previous)
      } yield assertTrue(
        early.left.toOption.flatMap(initFailure).exists(_.getMessage.contains("cannot be installed again")),
        held == ProviderStatus.Error,
        restored,
        status == ProviderStatus.Ready,
        value,
        during == 1,
        after == 2,
        leaked.isEmpty
      )
    },
    test("a shutdown while the timed-out provider still hangs shuts the previous provider down, once in all") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
        result   <- leakFree {
          for {
            ff     <- build(previous)
            _      <- ff.setProvider(incoming).either
            _      <- ff.shutdown
            status <- ff.providerStatus
            during <- shutdowns(previous)
          } yield (status, during)
        }
        ((status, during), leaked) = result
        _     <- incoming.release
        after <- shutdowns(previous)
      } yield assertTrue(status == ProviderStatus.NotReady, during == 1, after == 1, leaked.isEmpty)
    },
    test("closing the scope while the timed-out provider still hangs shuts the previous provider down") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
        result   <- leakFree(build(previous).flatMap(_.setProvider(incoming).either))
        (_, leaked) = result
        _     <- incoming.release
        after <- shutdowns(previous)
      } yield assertTrue(after == 1, leaked.isEmpty)
    },
    test("a non-owning instance leaves the previous provider to whatever owns the API") {
      for {
        previous <- FaultyProvider.make(Map("x" -> false), name = "previous")
        incoming <- FaultyProvider.make(Map("x" -> true), name = "incoming")
        _        <- incoming.script(Op.Initialize, lateFailure)
        result   <- leakFree {
          for {
            ff <- build(previous, owned = false)
            _  <- ff.setProvider(incoming).either
            _  <- ZIO.sleep(1500.millis)
            _  <- ff.shutdown
          } yield ()
        }
        (_, leaked) = result
        after <- shutdowns(previous)
      } yield assertTrue(after == 0, leaked.isEmpty)
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
}
