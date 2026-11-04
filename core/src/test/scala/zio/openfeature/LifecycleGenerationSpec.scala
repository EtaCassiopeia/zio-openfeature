package zio.openfeature

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import dev.openfeature.sdk.NoOpProvider
import zio.openfeature.internal.{Lifecycle, LifecycleInput, ManagedProvider, ProviderName}
import zio.openfeature.internal.ProviderRegistration.Settled
import zio.openfeature.internal.ProviderStatusMachine.Signal
import zio.stream.SubscriptionRef
import zio.test._

/** The generation `Evaluator.gated` compares is written after a lifecycle step has left its critical section, so a step
  * that wrote it without moving it could land after a later swap's write and move it back. An evaluation in flight
  * would then fail `ProviderNotReady` with no swap running, or miss a swap that started and ended during it.
  */
object LifecycleGenerationSpec extends ZIOSpecDefault {

  private val name = ProviderName.Named("provider")

  def spec = suite("LifecycleGenerationSpec")(
    test("only a swap's start writes the generation, so a late step cannot move it back") {
      val generation = new AtomicLong(0L)
      for {
        status <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        lifecycle = new Lifecycle(
          status,
          new NoOpProvider(),
          new AtomicBoolean(false),
          generation,
          new AtomicReference[ProviderName](name)
        )
        _ <- lifecycle.run(LifecycleInput.SwapStart(ManagedProvider(new NoOpProvider()), name))
        first = generation.get()
        _ <- lifecycle.run(
          LifecycleInput.Status(Signal.SwapSucceeded(Settled.Ready))
        )
        _ <- lifecycle.run(LifecycleInput.GateDown)
        // A value no step holds: a step that writes the generation at all replaces it.
        _ = generation.set(41L)
        _ <- lifecycle.run(LifecycleInput.Status(Signal.EventReady))
        _ <- lifecycle.run(LifecycleInput.Idle)
        _ <- lifecycle.read
        kept = generation.get()
        _ <- lifecycle.run(LifecycleInput.SwapStart(ManagedProvider(new NoOpProvider()), name))
      } yield assertTrue(first == 1L, kept == 41L, generation.get() == 2L)
    }
  )
}
