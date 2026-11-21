package zio.openfeature.simulation

import dev.openfeature.sdk.NoOpProvider
import zio.test._
import zio.openfeature.ProviderStatus
import zio.openfeature.internal.{
  LifecycleProbe,
  LifecycleState,
  Lock,
  ManagedProvider,
  ProviderName,
  ProviderRegistration,
  ProviderStatusMachine,
  SwapInFlight
}

object ModelInvariantsSpec extends ZIOSpecDefault {

  private val provider = new NoOpProvider()
  private val idle     = LifecycleState.initial(provider, ProviderName.Pending).copy(status = ProviderStatus.Ready)
  private val inFlight = Some(SwapInFlight(provider, provider, ProviderStatus.Ready))
  private val swapping = idle.copy(lock = Lock.Swap, gate = true, swap = inFlight, status = ProviderStatus.NotReady)

  // A wrapper whose provider has been shut down in its current registration cycle.
  private def shutDownWrapper: ManagedProvider = {
    val m = ManagedProvider(new NoOpProvider())
    m.shutdown()
    m
  }

  def spec = suite("ModelInvariants")(
    suite("always")(
      test("an idle instance and one mid-swap satisfy every invariant") {
        assertTrue(ModelInvariants.always(idle).isEmpty, ModelInvariants.always(swapping).isEmpty)
      },
      test("GateDownWhenIdle: the gate up with no swap holding the lock") {
        assertTrue(
          ModelInvariants.always(idle.copy(gate = true)) == List("GateDownWhenIdle: gate up with lock Free")
        )
      },
      test("OneLockHolder: the swap lock without a swap, and a swap without the lock") {
        assertTrue(
          ModelInvariants.always(idle.copy(lock = Lock.Swap)) == List("OneLockHolder: lock Swap with swap none"),
          ModelInvariants.always(idle.copy(swap = inFlight)) == List("OneLockHolder: lock Free with swap in flight")
        )
      },
      test("FinalNotReady: shut down but serving; ShuttingDown only while the shutdown holds the lock") {
        assertTrue(
          ModelInvariants.always(idle.copy(shutDown = true)) ==
            List("FinalNotReady: shut down with status Ready and lock Free"),
          ModelInvariants.always(idle.copy(shutDown = true, status = ProviderStatus.NotReady)).isEmpty,
          ModelInvariants
            .always(idle.copy(shutDown = true, lock = Lock.Shutdown, status = ProviderStatus.ShuttingDown))
            .isEmpty
        )
      },
      test("BoundNotShutDown: the installed wrapper shut down while the instance serves") {
        assertTrue(
          ModelInvariants.always(idle.copy(installed = shutDownWrapper)) ==
            List("BoundNotShutDown: the installed provider was shut down in its cycle, status Ready")
        )
      },
      test(
        "BoundNotShutDown is not checked while a swap or the shutdown holds the lock, nor once closed or shut down"
      ) {
        val shut = shutDownWrapper
        assertTrue(
          ModelInvariants.always(idle.copy(installed = shut, lock = Lock.Shutdown)).isEmpty,
          ModelInvariants.always(swapping.copy(installed = shut)).isEmpty,
          ModelInvariants.always(idle.copy(installed = shut, closed = true)).isEmpty,
          ModelInvariants.always(idle.copy(installed = shut, shutDown = true, status = ProviderStatus.NotReady)).isEmpty
        )
      }
    ),
    suite("atPoint")(
      test("NotReadyDuringSwap holds at the swap's points before its outcome") {
        assertTrue(
          List(LifecycleProbe.Point.SwapBind, LifecycleProbe.Point.SdkBind, LifecycleProbe.Point.SwapTimeout)
            .forall(p => ModelInvariants.atPoint(p, swapping).isEmpty)
        )
      },
      test("NotReadyDuringSwap: a status other than NotReady there, or no swap at all") {
        assertTrue(
          ModelInvariants.atPoint(LifecycleProbe.Point.SdkBind, swapping.copy(status = ProviderStatus.Ready)) ==
            List("NotReadyDuringSwap at SdkBind: status Ready, swap true"),
          ModelInvariants.atPoint(LifecycleProbe.Point.SwapBind, idle) ==
            List("NotReadyDuringSwap at SwapBind: status Ready, swap false")
        )
      },
      test("NotReadyDuringSwap holds just before the swap applies its outcome, success or failure") {
        val succeeded = ProviderStatusMachine.Signal.SwapSucceeded(ProviderRegistration.Settled.Ready)
        assertTrue(
          ModelInvariants.atPoint(LifecycleProbe.Point.ApplySignal(succeeded), swapping).isEmpty,
          ModelInvariants
            .atPoint(LifecycleProbe.Point.ApplySignal(ProviderStatusMachine.Signal.SwapFailed), swapping)
            .isEmpty,
          ModelInvariants.atPoint(
            LifecycleProbe.Point.ApplySignal(succeeded),
            swapping.copy(status = ProviderStatus.Ready)
          ) == List("NotReadyDuringSwap at the swap's outcome: status Ready, swap true")
        )
      },
      test("is not checked on a signal that follows the outcome, such as the rollback's") {
        val rolledBack = ProviderStatusMachine.Signal.RollbackSucceeded(ProviderRegistration.Settled.Ready)
        assertTrue(
          ModelInvariants
            .atPoint(LifecycleProbe.Point.ApplySignal(rolledBack), swapping.copy(status = ProviderStatus.Ready))
            .isEmpty
        )
      },
      test("is not checked at a point after the swap's outcome, such as its rollback") {
        assertTrue(
          ModelInvariants
            .atPoint(LifecycleProbe.Point.SwapRollback, swapping.copy(status = ProviderStatus.Error))
            .isEmpty
        )
      }
    )
  )
}
