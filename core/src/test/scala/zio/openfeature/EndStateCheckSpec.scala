package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.FeatureFlagsLive.{endStateViolations, wrappersOf, WrapperEnd}
import zio.openfeature.internal.{LifecycleState, Lock, ManagedProvider, ProviderName, Quiescence => Registry}
import zio.openfeature.testsupport.Quiescence
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, NoOpProvider, OpenFeatureAPI, ProviderState}

/** The end-state check that `Quiescence.aspect` runs after each lifecycle test, fed known-good and known-bad states.
  * The aspect only reports what this check finds, so a check that stopped finding a violation would pass every spec it
  * guards.
  */
object EndStateCheckSpec extends ZIOSpecDefault {

  private val provider: OFFeatureProvider = new NoOpProvider()
  private val orphan: OFFeatureProvider   = new NoOpProvider()

  private val live: LifecycleState =
    LifecycleState.initial(provider, ProviderName.Named("p")).copy(status = ProviderStatus.Ready, everReady = true)
  private val shutDown: LifecycleState = live.copy(status = ProviderStatus.NotReady, shutDown = true)
  private val closed: LifecycleState   = live.copy(closed = true)

  private val stopped = WrapperEnd("w", shutDownInCycle = true, laneRunning = false)
  private val running = WrapperEnd("w", shutDownInCycle = true, laneRunning = true)
  private val active  = WrapperEnd("w", shutDownInCycle = false, laneRunning = true)

  private def observed(found: String*): Registry.Observed =
    new Registry.Observed {
      def label: String                  = "planted"
      def violations: UIO[Chunk[String]] = ZIO.succeed(Chunk.fromIterable(found))
    }
  private val planted = observed("planted violation")
  private val clean   = observed()

  // An instance on its own API, over a provider that is READY once initialized; the caller's scope closes it.
  private val built: ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      new NoOpProvider {
        @scala.annotation.nowarn("msg=deprecated")
        override def getState: ProviderState = ProviderState.READY
      },
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  private def check(
    record: LifecycleState,
    status: ProviderStatus = ProviderStatus.NotReady,
    abandoned: Int = 0,
    bridgeAttached: Boolean = false,
    wrappers: List[WrapperEnd] = Nil
  ): List[String] = endStateViolations(record, status, abandoned, bridgeAttached, wrappers).toList

  def spec = suite("EndStateCheckSpec")(
    suite("known-good states report nothing")(
      test("a live instance with a lock free and nothing left over") {
        assertTrue(check(live, ProviderStatus.Ready, bridgeAttached = true, wrappers = List(active)).isEmpty)
      },
      test("a shut-down instance, NotReady, detached, its wrapper's lane stopped") {
        assertTrue(check(shutDown, wrappers = List(stopped)).isEmpty)
      },
      test("a closed instance, whatever its status, detached, its wrapper's lane stopped") {
        assertTrue(check(closed, ProviderStatus.Ready, wrappers = List(stopped)).isEmpty)
      },
      test("a live instance mid-swap with an orphan pending") {
        val swapping = live.copy(lock = Lock.Swap, orphans = List(orphan -> "incoming"))
        assertTrue(check(swapping, ProviderStatus.Ready).isEmpty)
      },
      test("an ended instance whose wrapper still runs a lane it never shut down") {
        assertTrue(check(shutDown, wrappers = List(active)).isEmpty, check(closed, wrappers = List(active)).isEmpty)
      }
    ),
    suite("known-bad states report each violation")(
      test("an abandoned call still counted, live or ended") {
        val expected = List("2 abandoned provider calls still counted")
        assertTrue(
          check(live, ProviderStatus.Ready, abandoned = 2) == expected,
          check(shutDown, abandoned = 2) == expected
        )
      },
      test("the bridge still attached once shut down or closed, but not on a live instance") {
        val expected = List("the event bridge is still attached to the client")
        assertTrue(
          check(shutDown, bridgeAttached = true) == expected,
          check(closed, bridgeAttached = true) == expected,
          check(live, ProviderStatus.Ready, bridgeAttached = true).isEmpty
        )
      },
      test("an orphan left on an ended instance, whoever holds the lock, or on a live one with the lock free") {
        val left     = List(orphan -> "a", orphan -> "b")
        val expected = List("orphans left: a, b")
        assertTrue(
          check(live.copy(orphans = left), ProviderStatus.Ready) == expected,
          check(shutDown.copy(orphans = left, lock = Lock.Shutdown)) == expected,
          check(closed.copy(orphans = left, lock = Lock.Swap)) == expected,
          check(live.copy(orphans = left, lock = Lock.Shutdown), ProviderStatus.Ready).isEmpty
        )
      },
      test("a shut-down instance with a status other than NotReady, but not a closed one") {
        assertTrue(
          check(shutDown, ProviderStatus.Ready) == List("shut down with status Ready"),
          check(shutDown, ProviderStatus.Error) == List("shut down with status Error"),
          check(closed, ProviderStatus.Error).isEmpty
        )
      },
      test("a wrapper shut down in its cycle whose lane still runs, once ended") {
        val expected = List("the event lane of 'w' still runs after its shutdown")
        assertTrue(
          check(shutDown, wrappers = List(running, stopped)) == expected,
          check(closed, wrappers = List(running)) == expected,
          check(live, ProviderStatus.Ready, wrappers = List(running)).isEmpty
        )
      },
      test("every violation at once, each reported") {
        val found = check(
          shutDown.copy(orphans = List(orphan -> "a")),
          ProviderStatus.Ready,
          abandoned = 1,
          bridgeAttached = true,
          wrappers = List(running)
        )
        assertTrue(found.size == 5)
      }
    ),
    suite("the wrappers it reads")(
      test("the installed provider's wrapper, and no plain provider") {
        val wrapper = ManagedProvider(new NoOpProvider())
        assertTrue(
          wrappersOf(live.copy(installed = wrapper)) == List(wrapper),
          wrappersOf(live).isEmpty
        )
      }
    ),
    suite("on a built instance")(
      test("building an instance registers its end state for the aspect to read") {
        Registry.collecting(ZIO.scoped(built.map(_.endState.label))) { (label, observed) =>
          ZIO.succeed(assertTrue(observed.exists(_.label == label)))
        }
      },
      test("a registration on a daemon fiber that is never joined is still collected") {
        // ZIO Test runs a test's body on such a fiber.
        val onDaemon = Registry.register(planted).forkDaemon.flatMap(_.await).unit
        Registry.collecting(onDaemon)((_, observed) => ZIO.succeed(assertTrue(observed == Chunk(planted))))
      },
      test("a live instance with nothing left over reports nothing") {
        ZIO.scoped(built.flatMap(_.endState.violations)).map(found => assertTrue(found.isEmpty))
      }
    ),
    suite("the aspect")(
      test("fails a test that leaves a registered end state with a violation") {
        Registry.register(planted).as(assertCompletes)
      } @@ Quiescence.aspect @@ TestAspect.failing,
      test("passes a test whose registered end state has none") {
        Registry.register(clean).as(assertCompletes)
      } @@ Quiescence.aspect
    )
  )
}
