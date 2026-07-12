package zio.openfeature

import zio.test._
import zio.openfeature.internal.ProviderStatusMachine
import zio.openfeature.internal.ProviderStatusMachine.{Context, Signal}

/** Pure, exhaustive coverage of the centralized provider-status transition table (#244). */
object ProviderStatusMachineSpec extends ZIOSpecDefault {

  import ProviderStatus._

  private val allStatuses: List[ProviderStatus] =
    List(NotReady, Ready, Error, Stale, Fatal, ShuttingDown)

  // everReady x swapInProgress x shutdownCompleted
  private val allContexts: List[Context] =
    for {
      ever <- List(false, true)
      swap <- List(false, true)
      shut <- List(false, true)
    } yield Context(ever, swap, shut)

  private def t(current: ProviderStatus, signal: Signal, ctx: Context): Option[ProviderStatus] =
    ProviderStatusMachine.transition(current, signal, ctx)

  private val lifecycle: List[(Signal, ProviderStatus)] = List(
    Signal.ShutdownStarted   -> ShuttingDown,
    Signal.ShutdownCompleted -> NotReady,
    Signal.SwapStarted       -> NotReady,
    Signal.SwapSucceeded     -> Ready,
    Signal.SwapFailed        -> Error,
    Signal.RollbackSucceeded -> Ready
  )

  def spec = suite("ProviderStatusMachineSpec")(
    test("lifecycle signals are authoritative from every status and every context") {
      val checks = for {
        (signal, target) <- lifecycle
        status           <- allStatuses
        ctx              <- allContexts
      } yield assertTrue(t(status, signal, ctx) == Some(target))
      TestResult.allSuccesses(checks)
    },
    test("Fatal is sticky under every Event* and InitTimeout, from every context") {
      val eventSignals =
        List(
          Signal.EventReady,
          Signal.EventError(fatal = false),
          Signal.EventError(fatal = true),
          Signal.EventStale,
          Signal.InitTimeout
        )
      val checks = for {
        signal <- eventSignals
        ctx    <- allContexts
      } yield assertTrue(t(Fatal, signal, ctx).isEmpty)
      TestResult.allSuccesses(checks)
    },
    test("Fatal exits only via lifecycle signals") {
      val checks = for {
        (signal, target) <- lifecycle
        ctx              <- allContexts
      } yield assertTrue(t(Fatal, signal, ctx) == Some(target))
      TestResult.allSuccesses(checks)
    },
    suite("InitTimeout (watchdog)")(
      test("escalates to Fatal from NotReady/Error only when never Ready and not swapping/shutting down") {
        assertTrue(
          t(NotReady, Signal.InitTimeout, Context(false, false, false)) == Some(Fatal),
          t(Error, Signal.InitTimeout, Context(false, false, false)) == Some(Fatal)
        )
      },
      test("is a no-op during a swap or after shutdown completed (must not resurrect a torn-down provider)") {
        val checks = for {
          status <- List(NotReady, Error)
          ctx    <- allContexts if !ctx.everReady && (ctx.swapInProgress || ctx.shutdownCompleted)
        } yield assertTrue(t(status, Signal.InitTimeout, ctx).isEmpty)
        TestResult.allSuccesses(checks)
      },
      test("is a no-op when everReady, from every status") {
        val checks = for {
          status <- allStatuses
          ctx    <- allContexts.filter(_.everReady)
        } yield assertTrue(t(status, Signal.InitTimeout, ctx).isEmpty)
        TestResult.allSuccesses(checks)
      },
      test("is a no-op from Ready/Stale/ShuttingDown even when never Ready") {
        val checks = for {
          status <- List(Ready, Stale, ShuttingDown)
          ctx    <- allContexts.filterNot(_.everReady)
        } yield assertTrue(t(status, Signal.InitTimeout, ctx).isEmpty)
        TestResult.allSuccesses(checks)
      }
    ),
    suite("bridge events gated by swap/shutdown context")(
      test("every Event* is a no-op when swapInProgress") {
        val eventSignals = List(Signal.EventReady, Signal.EventError(false), Signal.EventError(true), Signal.EventStale)
        val checks = for {
          signal <- eventSignals
          status <- allStatuses
        } yield assertTrue(t(status, signal, Context(everReady = false, swapInProgress = true, false)).isEmpty)
        TestResult.allSuccesses(checks)
      },
      test("every Event* is a no-op when shutdownCompleted") {
        val eventSignals = List(Signal.EventReady, Signal.EventError(false), Signal.EventError(true), Signal.EventStale)
        val checks = for {
          signal <- eventSignals
          status <- allStatuses
        } yield assertTrue(t(status, signal, Context(everReady = false, false, shutdownCompleted = true)).isEmpty)
        TestResult.allSuccesses(checks)
      }
    ),
    suite("EventReady")(
      test("NotReady/Stale/Error => Ready (ungated)") {
        val checks = List(NotReady, Stale, Error).map(s =>
          assertTrue(t(s, Signal.EventReady, Context(false, false, false)) == Some(Ready))
        )
        TestResult.allSuccesses(checks)
      },
      test("Ready/Fatal/ShuttingDown => no-op (ungated)") {
        val checks = List(Ready, Fatal, ShuttingDown).map(s =>
          assertTrue(t(s, Signal.EventReady, Context(false, false, false)).isEmpty)
        )
        TestResult.allSuccesses(checks)
      }
    ),
    suite("EventError")(
      test("fatal=true => Fatal from NotReady/Ready/Stale/Error (ungated)") {
        val checks = List(NotReady, Ready, Stale, Error).map(s =>
          assertTrue(t(s, Signal.EventError(fatal = true), Context(false, false, false)) == Some(Fatal))
        )
        TestResult.allSuccesses(checks)
      },
      test("fatal=false => Error from NotReady/Ready/Stale; no-op from Error") {
        assertTrue(
          t(NotReady, Signal.EventError(false), Context(false, false, false)) == Some(Error),
          t(Ready, Signal.EventError(false), Context(false, false, false)) == Some(Error),
          t(Stale, Signal.EventError(false), Context(false, false, false)) == Some(Error),
          t(Error, Signal.EventError(false), Context(false, false, false)).isEmpty
        )
      },
      test("no-op from Fatal/ShuttingDown regardless of fatal flag") {
        assertTrue(
          t(Fatal, Signal.EventError(true), Context(false, false, false)).isEmpty,
          t(Fatal, Signal.EventError(false), Context(false, false, false)).isEmpty,
          t(ShuttingDown, Signal.EventError(true), Context(false, false, false)).isEmpty,
          t(ShuttingDown, Signal.EventError(false), Context(false, false, false)).isEmpty
        )
      }
    ),
    test("EventStale only Ready => Stale; no-op everywhere else (ungated)") {
      val checks = allStatuses.map { s =>
        val expected = if (s == Ready) Some(Stale) else None
        assertTrue(t(s, Signal.EventStale, Context(false, false, false)) == expected)
      }
      TestResult.allSuccesses(checks)
    }
  )
}
