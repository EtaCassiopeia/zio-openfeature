package zio.openfeature

import zio._
import zio.test._

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, NoOpProvider, OpenFeatureAPI}
import zio.openfeature.internal.{
  Bound,
  LifecycleEffect,
  LifecycleInput,
  LifecycleState,
  LifecycleStep,
  Lock,
  ManagedProvider,
  ProviderName,
  ProviderRegistration,
  Refusal,
  SwapInFlight
}
import zio.openfeature.internal.ProviderRegistration.Settled
import zio.openfeature.internal.ProviderStatusMachine.Signal

/** The lifecycle's pure step, against the TLA+ model (`formal/lifecycle`): one test per library action of the README's
  * "Actions and the code" table, one per model mutant whose bug the step would now carry, and properties over random
  * input sequences.
  *
  * The mutants not here are bugs in a sequence rather than in a step, and are pinned where that sequence is tested: the
  * status following the event type rather than the SDK's state (`EventBridge.applySdkSignal`, `SdkEventOrderSpec`), a
  * failed swap reading the SDK's state once its gate drops (`reconcileRegistration`, `ProviderHotSwapSpec`), the orphan
  * watcher reading an error after `initialize()` returned (`OrphanedProviders`, `OrphanedProviderSpec`), shutdown or
  * the scope close waiting for a swap in flight (the swap lock, `ShutdownDuringSwapSpec`, `LifecycleTeardownSpec`), a
  * failed swap rolling back onto a provider still initializing (`setProvider`'s failure branch, `ProviderHotSwapSpec`
  * "a failed swap does not initialize again a provider whose initialize() has not returned"), and the async build
  * recording its provider as initializing (the wrapper's count, `ProviderHotSwapSpec` "a failed swap on an async-built
  * instance does not initialize again a provider still initializing").
  */
object LifecycleStepSpec extends ZIOSpecDefault {

  private val p0: OFFeatureProvider = new NoOpProvider()
  private val m1: ManagedProvider   = ManagedProvider(new NoOpProvider())
  private val m2: ManagedProvider   = ManagedProvider(new NoOpProvider())
  private val m3: ManagedProvider   = ManagedProvider(new NoOpProvider())

  // The record holds a holder's registration as the SDK bound it; the step only compares it.
  private val api = OpenFeatureAPI.createIsolated()

  private val registrations: Map[ManagedProvider, ProviderRegistration.Registration] =
    List(m1, m2, m3).map { m =>
      m -> Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(ProviderRegistration.register(api, Some(java.util.UUID.randomUUID().toString), Bound.Managed(m)))
          .getOrThrowFiberFailure()
      }
    }.toMap

  private def replaced(holder: ManagedProvider, previous: ManagedProvider): LifecycleInput =
    LifecycleInput.Replaced(registrations(holder), Bound.Managed(previous))

  private val p0Name: ProviderName   = ProviderName.Named("p0")
  private val incoming: ProviderName = ProviderName.Named("incoming")
  private val stack: ProviderName    = ProviderName.Named("stack")

  private val ready: LifecycleState =
    LifecycleState.initial(p0, p0Name).copy(status = ProviderStatus.Ready, everReady = true)

  /** The record after each input in turn, and every effect on the way. */
  private def run(state: LifecycleState, inputs: LifecycleInput*): (LifecycleState, Chunk[LifecycleEffect]) =
    inputs.foldLeft((state, Chunk.empty[LifecycleEffect])) { case ((s, effects), input) =>
      val outcome = LifecycleStep.step(s, input)
      (outcome.state, effects ++ outcome.effects)
    }

  private def refusal(state: LifecycleState, input: LifecycleInput): Option[Refusal] =
    LifecycleStep.step(state, input).effects.collectFirst { case LifecycleEffect.Refuse(r) => r }

  private def admit(
    m: ManagedProvider,
    initializing: ManagedProvider => Boolean = _ => false,
    released: Boolean = true
  ) =
    // As a swap asks: the user's provider, and the wrapper the library holds for it.
    LifecycleInput.Admit(m.delegate, Some(m), initializing, _.bound.managed.exists(initializing), (_, _) => released)

  private val admitUnwrapped = LifecycleInput.Admit(p0, None, _ => false, _ => false, (_, _) => true)

  private def swapStart(p: OFFeatureProvider) = LifecycleInput.SwapStart(p, incoming)

  private def event(signal: Signal) = LifecycleInput.Status(signal)

  def spec = suite("LifecycleStepSpec")(
    suite("a status override (the testkit's setStatus)")(
      test("moves the status from every status, Fatal included, and nothing else in the record") {
        val all = List(
          ProviderStatus.NotReady,
          ProviderStatus.Ready,
          ProviderStatus.Stale,
          ProviderStatus.Error,
          ProviderStatus.Fatal,
          ProviderStatus.ShuttingDown
        )
        val moves = for {
          from <- all
          to   <- all
        } yield {
          val before  = ready.copy(status = from)
          val outcome = LifecycleStep.step(before, LifecycleInput.Override(to))
          outcome.state == before.copy(status = to) && outcome.transitioned.contains(to) && outcome.effects.isEmpty
        }
        assertTrue(moves.forall(identity))
      },
      test("counts as ready when it sets Ready or Stale, and not otherwise") {
        val fresh                         = LifecycleState.initial(p0, p0Name)
        def everReady(to: ProviderStatus) = LifecycleStep.step(fresh, LifecycleInput.Override(to)).state.everReady
        assertTrue(
          !fresh.everReady,
          everReady(ProviderStatus.Ready),
          everReady(ProviderStatus.Stale),
          !everReady(ProviderStatus.Error),
          !everReady(ProviderStatus.Fatal),
          !everReady(ProviderStatus.NotReady)
        )
      },
      test("an instance made Ready by an override is not escalated to Fatal when the init watchdog fires later") {
        val async       = LifecycleState.initial(p0, p0Name)
        val (after, fx) = run(
          async,
          LifecycleInput.Override(ProviderStatus.Ready),
          LifecycleInput.Override(ProviderStatus.Error),
          LifecycleInput.WatchdogFired(p0, 1.second)
        )
        assertTrue(
          after.status == ProviderStatus.Error,
          !fx.exists(_.isInstanceOf[LifecycleEffect.EscalateInitTimeout])
        )
      },
      test("is refused once the instance is shut down or its scope is closed, and while a swap's gate is up") {
        val shutDown = run(ready, event(Signal.ShutdownStarted), event(Signal.ShutdownCompleted))._1
        val closed   = LifecycleStep.step(ready, LifecycleInput.ScopeClose).state
        val swapping = LifecycleStep.step(ready, swapStart(m1)).state
        val input    = LifecycleInput.Override(ProviderStatus.Ready)
        assertTrue(
          refusal(shutDown, input) == Some(Refusal.Closed),
          refusal(closed, input) == Some(Refusal.Closed),
          refusal(swapping, input) == Some(Refusal.SwapInFlight),
          LifecycleStep.step(swapping, input).state == swapping
        )
      }
    ),
    suite("actions")(
      test("SwapStart: the gate goes up, the status becomes NotReady, and the new provider is installed") {
        val outcome = LifecycleStep.step(ready, swapStart(m1))
        val s       = outcome.state
        assertTrue(
          s.gate,
          s.status == ProviderStatus.NotReady,
          s.installed eq m1,
          s.generation == ready.generation + 1,
          s.lock == Lock.Swap,
          s.swap == Some(SwapInFlight(m1, p0, ProviderStatus.Ready)),
          s.name == incoming
        )
      },
      test("SwapDone: the swap ends in the state the SDK reports, and the gate and lock are released") {
        val (s, _) = run(ready, swapStart(m1), event(Signal.SwapSucceeded(Settled.Stale)), LifecycleInput.GateDown)
        assertTrue(s.status == ProviderStatus.Stale, !s.gate, s.lock == Lock.Free, s.swap.isEmpty, s.everReady)
      },
      test("SwapTimeout: status Error, and the provider it replaced is held by the new provider's initialization") {
        val (s, _) = run(
          ready.copy(installed = m2),
          swapStart(m1),
          event(Signal.SwapFailed),
          replaced(m1, m2),
          LifecycleInput.OrphanRecorded(m2, "m1"),
          LifecycleInput.GateDown
        )
        assertTrue(
          s.status == ProviderStatus.Error,
          s.installed eq m1,
          s.replaced == List(registrations(m1) -> m2),
          s.orphans == List((m2: OFFeatureProvider) -> "m1"),
          !s.gate
        )
      },
      test("SwapFailNoRollback: status Error, the failed provider stays, and the previous one is watched") {
        val (s, _) = run(
          ready.copy(installed = m2),
          swapStart(m1),
          event(Signal.SwapFailed),
          LifecycleInput.OrphanRecorded(m2, "m1"),
          LifecycleInput.GateDown
        )
        assertTrue(s.status == ProviderStatus.Error, s.installed eq m1, s.orphans.map(_._1) == List(m2))
      },
      test("SwapRollback: the previous provider is installed again and settles in the state the SDK reports") {
        val (s, _) = run(
          ready,
          swapStart(m1),
          event(Signal.SwapFailed),
          LifecycleInput.Reinstall(p0, p0Name),
          event(Signal.RollbackSucceeded(Settled.Error("down", reused = false))),
          LifecycleInput.GateDown
        )
        assertTrue(s.installed eq p0, s.name == p0Name, s.status == ProviderStatus.Error, s.everReady, !s.gate)
      },
      test("SwapRollbackUnbound: nothing was bound, so the status goes back to the one before the swap") {
        val before = ready.copy(status = ProviderStatus.Error)
        val (s, _) = run(
          before,
          swapStart(m1),
          event(Signal.SwapFailed),
          LifecycleInput.Reinstall(p0, p0Name),
          event(Signal.RollbackUnbound(ProviderStatus.Error)),
          LifecycleInput.GateDown
        )
        assertTrue(s.installed eq p0, s.status == ProviderStatus.Error)
      },
      test("Deliver: an event moves the status, except while a swap's gate is up") {
        val delivered = LifecycleStep.step(LifecycleState.initial(p0, p0Name), event(Signal.EventReady)).state.status
        val gated     = run(ready, swapStart(m1), event(Signal.EventReady))._1.status
        assertTrue(delivered == ProviderStatus.Ready, gated == ProviderStatus.NotReady)
      },
      test("OrphanResolved: a settled entry no longer holds its provider") {
        val (s, _) = run(ready, LifecycleInput.OrphanRecorded(m2, "m1"), LifecycleInput.OrphanSettled(m2))
        assertTrue(s.orphans.isEmpty, refusal(s, admit(m2)).isEmpty)
      },
      test("ShutdownStart and ShutdownDone: ShuttingDown under the lock, then a final NotReady no event moves") {
        val (starting, _) = run(ready, event(Signal.ShutdownStarted))
        val (done, _)     = run(starting, event(Signal.ShutdownCompleted), event(Signal.EventReady))
        assertTrue(
          starting.status == ProviderStatus.ShuttingDown,
          starting.lock == Lock.Shutdown,
          done.status == ProviderStatus.NotReady,
          done.shutDown,
          done.lock == Lock.Free
        )
      },
      test("ScopeClose: the instance is closed, and a swap is refused") {
        val (s, _) = run(ready, LifecycleInput.ScopeClose)
        assertTrue(s.closed, refusal(s, admit(m1)) == Some(Refusal.Closed))
      },
      test("NameRead: writes the name only from Pending") {
        val pending  = ready.copy(name = ProviderName.Pending)
        val nameless = LifecycleInput.NameRead(p0, ProviderName.Nameless)
        val named    = LifecycleInput.NameRead(p0, stack)
        assertTrue(
          LifecycleStep.step(pending, named).state == pending.copy(name = stack),
          LifecycleStep.step(pending, nameless).state == pending.copy(name = ProviderName.Nameless),
          LifecycleStep.step(ready, named).state == ready,
          LifecycleStep.step(pending.copy(name = ProviderName.Nameless), named).state.name == ProviderName.Nameless,
          LifecycleStep.step(pending, named).transitioned.isEmpty,
          LifecycleStep.step(pending, named).effects.isEmpty
        )
      },
      test("NameRead: a read of a provider a swap has since replaced does not name the one it installed") {
        val swapped = LifecycleStep.step(ready, LifecycleInput.SwapStart(m1, ProviderName.Pending)).state
        val outcome = LifecycleStep.step(swapped, LifecycleInput.NameRead(p0, p0Name))
        assertTrue(swapped.name == ProviderName.Pending, outcome.state == swapped)
      },
      test("Reinstall: a failed swap restores the name it captured, Pending included") {
        val pending = ready.copy(name = ProviderName.Pending)
        val (s, _)  = run(
          pending,
          swapStart(m1),
          event(Signal.SwapFailed),
          LifecycleInput.Reinstall(p0, ProviderName.Pending)
        )
        assertTrue(s.name == ProviderName.Pending, s.installed eq p0)
      },
      test("Switch: the installed switchable provider is renamed, and nothing else moves") {
        val installed = LifecycleStep.step(ready, swapStart(m1)).state.copy(status = ProviderStatus.Ready, gate = false)
        val outcome   = LifecycleStep.step(installed, LifecycleInput.Switch(m1, stack))
        assertTrue(
          outcome.state == installed.copy(name = stack),
          outcome.transitioned.isEmpty,
          outcome.effects.isEmpty
        )
      },
      test("Switch: refused once a swap installed another provider, and on a shut-down or closed instance") {
        val (shut, _)   = run(ready, event(Signal.ShutdownStarted), event(Signal.ShutdownCompleted))
        val (closed, _) = run(ready, LifecycleInput.ScopeClose)
        val replaced    = LifecycleStep.step(ready, LifecycleInput.Switch(m1, stack))
        assertTrue(
          refusal(ready, LifecycleInput.Switch(m1, stack)) == Some(Refusal.NotInstalled(m1)),
          replaced.state == ready,
          refusal(shut, LifecycleInput.Switch(p0, stack)) == Some(Refusal.Closed),
          refusal(closed, LifecycleInput.Switch(p0, stack)) == Some(Refusal.Closed)
        )
      }
    ),
    suite("the model's mutants, as steps")(
      test("a swap after shutdown is refused") {
        val (s, _) = run(ready, event(Signal.ShutdownStarted), event(Signal.ShutdownCompleted))
        assertTrue(refusal(s, admitUnwrapped) == Some(Refusal.Closed))
      },
      test("a swap after the scope close is refused") {
        val (s, _) = run(ready, LifecycleInput.ScopeClose)
        assertTrue(refusal(s, admitUnwrapped) == Some(Refusal.Closed))
      },
      test("a provider still initializing is not installed again") {
        assertTrue(refusal(ready, admit(m1, _ eq m1)) == Some(Refusal.StillInitializing(m1)))
      },
      test("a provider a timed-out swap still holds is not installed again") {
        val (s, _) = run(ready, LifecycleInput.OrphanRecorded(m2, "m1"))
        assertTrue(refusal(s, admit(m2)) == Some(Refusal.PendingOrphan(m2, "m1")))
      },
      test("a timed-out swap remembers the provider it replaced") {
        val (s, _) = run(ready.copy(installed = m2), swapStart(m1), LifecycleInput.OrphanRecorded(m2, "m1"))
        assertTrue(s.orphans.map(_._1) == List(m2))
      },
      test("a provider a registration still initializing replaced is not installed again, until that call returns") {
        val (s, _)   = run(ready, replaced(m1, m2))
        val held     = refusal(s, admit(m2, _ eq m1))
        val returned = LifecycleStep.step(s, admit(m2, _ => false))
        val pruned   = returned.state.replaced
        val admitted = returned.effects.isEmpty
        assertTrue(held == Some(Refusal.HeldBy(m2, m1)), admitted, pruned.isEmpty)
      },
      test("a replacement whose shutdown the SDK has yet to run outlives admitting another provider") {
        val (s, _) = run(ready, replaced(m1, m2))
        val other  = LifecycleStep.step(s, admit(m3, released = false))
        val done   = LifecycleStep.step(s, admit(m3, released = true))
        assertTrue(other.effects.isEmpty, other.state.replaced.size == 1, done.state.replaced.isEmpty)
      },
      test(
        "admitting a provider whose shutdown the SDK has yet to run asks for the wait and keeps the entry until it ends"
      ) {
        val (s, _)  = run(ready, replaced(m1, m2))
        val asked   = LifecycleStep.step(s, admit(m2, released = false))
        val ended   = LifecycleStep.step(asked.state, LifecycleInput.SdkShutDownAwaited(m2))
        val retried = LifecycleStep.step(asked.state, admit(m2, released = false))
        assertTrue(
          asked.effects == Chunk(LifecycleEffect.AwaitSdkShutDown(m2)),
          asked.state.replaced.size == 1,
          ended.state.replaced.isEmpty,
          retried.effects == Chunk(LifecycleEffect.AwaitSdkShutDown(m2))
        )
      },
      test("admitting a provider its replacement has released asks for no wait") {
        val (s, _) = run(ready, replaced(m1, m2))
        val done   = LifecycleStep.step(s, admit(m2, released = true))
        assertTrue(done.effects.isEmpty, done.state.replaced.isEmpty)
      },
      test("a refusal asks for no wait, whatever the SDK has yet to do") {
        val (s, _) = run(ready, replaced(m1, m2), LifecycleInput.ScopeClose)
        val closed = LifecycleStep.step(s, admit(m2, released = false))
        assertTrue(closed.effects == Chunk(LifecycleEffect.Refuse(Refusal.Closed)), closed.state.replaced.size == 1)
      },
      test("STALE moves the status from NotReady, Ready and Error") {
        val from = List(ProviderStatus.NotReady, ProviderStatus.Ready, ProviderStatus.Error)
        val to   = from.map(s => LifecycleStep.step(ready.copy(status = s), event(Signal.EventStale)).state.status)
        assertTrue(to.forall(_ == ProviderStatus.Stale))
      },
      test("a swap into a STALE provider ends Stale, not Ready") {
        val (s, _) = run(ready, swapStart(m1), event(Signal.SwapSucceeded(Settled.Stale)))
        assertTrue(s.status == ProviderStatus.Stale)
      },
      test("an error reported once initialize() returned is the provider's status, and it counts as usable") {
        val (s, effects) = run(
          LifecycleState.initial(p0, p0Name),
          swapStart(m1),
          event(Signal.SwapSucceeded(Settled.Error("down", reused = false))),
          LifecycleInput.GateDown,
          LifecycleInput.WatchdogFired(m1, 1.second)
        )
        assertTrue(s.status == ProviderStatus.Error, s.everReady, effects.isEmpty)
      },
      test("a rollback that settles with an error is a successful rollback, in Error") {
        val (s, _) = run(
          ready,
          swapStart(m1),
          event(Signal.SwapFailed),
          event(Signal.RollbackSucceeded(Settled.Error("e", reused = false)))
        )
        assertTrue(s.status == ProviderStatus.Error, s.everReady)
      },
      test("a failed swap that never bound goes back to the status before it, not Ready") {
        val before = LifecycleState.initial(m2, p0Name)
        val (s, _) =
          run(before, swapStart(m1), event(Signal.SwapFailed), event(Signal.RollbackUnbound(ProviderStatus.NotReady)))
        assertTrue(s.status == ProviderStatus.NotReady, !s.everReady)
      },
      test("the swap gate keeps bridge events and the watchdog from moving the status mid-swap") {
        val (s, effects) =
          run(
            LifecycleState.initial(p0, p0Name),
            swapStart(m1),
            event(Signal.EventError(true)),
            LifecycleInput.WatchdogFired(p0, 1.second)
          )
        assertTrue(s.status == ProviderStatus.NotReady, effects.isEmpty, s.deferredInitTimeout == Some((p0, 1.second)))
      }
    ),
    suite("the init watchdog and the seed")(
      test("a deadline a swap deferred is replayed once the swap ends on that provider still unready") {
        val (s, effects) = run(
          LifecycleState.initial(p0, p0Name),
          swapStart(m1),
          event(Signal.SwapFailed),
          LifecycleInput.Reinstall(p0, p0Name),
          LifecycleInput.WatchdogFired(p0, 1.second),
          event(Signal.RollbackUnbound(ProviderStatus.NotReady)),
          LifecycleInput.GateDown,
          LifecycleInput.ReplayDeferred
        )
        assertTrue(
          s.status == ProviderStatus.Fatal,
          s.deferredInitTimeout.isEmpty,
          effects == Chunk(LifecycleEffect.EscalateInitTimeout(p0, 1.second))
        )
      },
      test("a deferred deadline for a provider the swap replaced is dropped, not escalated") {
        val (s, effects) = run(
          LifecycleState.initial(p0, p0Name),
          swapStart(m1),
          LifecycleInput.WatchdogFired(p0, 1.second),
          event(Signal.SwapSucceeded(Settled.Ready)),
          LifecycleInput.GateDown,
          LifecycleInput.ReplayDeferred
        )
        assertTrue(s.status == ProviderStatus.Ready, s.deferredInitTimeout.isEmpty, effects.isEmpty)
      },
      test("the seed applies only while the status is the initial NotReady, and records a usable one") {
        val seeded =
          LifecycleStep.step(LifecycleState.initial(p0, p0Name), LifecycleInput.Seed(ProviderStatus.Stale)).state
        val moved =
          LifecycleStep.step(ready.copy(status = ProviderStatus.Error), LifecycleInput.Seed(ProviderStatus.Ready))
        assertTrue(
          seeded.status == ProviderStatus.Stale,
          seeded.everReady,
          moved.state.status == ProviderStatus.Error,
          moved.transitioned.isEmpty
        )
      }
    ),
    suite("properties")(
      test("a refusal leaves the record as it was") {
        check(genState, genAdmit) { (state, input) =>
          val outcome = LifecycleStep.step(state, input)
          val refused = outcome.effects.exists(_.isInstanceOf[LifecycleEffect.Refuse])
          assertTrue(!refused || outcome.state == state)
        }
      },
      test("after a completed shutdown, no event and no watchdog moves the status") {
        check(Gen.listOfBounded(0, 20)(genEventOrWatchdog)) { inputs =>
          val (down, _) = run(ready, event(Signal.ShutdownStarted), event(Signal.ShutdownCompleted))
          val (s, eff)  = run(down, inputs: _*)
          assertTrue(s.status == ProviderStatus.NotReady, eff.isEmpty)
        }
      },
      test("while a swap's gate is up, no event and no watchdog moves the status") {
        check(Gen.listOfBounded(0, 20)(genEventOrWatchdog)) { inputs =>
          val (swapping, _) = run(ready, swapStart(m1))
          val (s, eff)      = run(swapping, inputs: _*)
          assertTrue(s.status == ProviderStatus.NotReady, eff.isEmpty)
        }
      },
      test("ever-readiness never goes back, and the watchdog shuts a provider down at most once a swap") {
        check(Gen.listOfBounded(0, 40)(genInput)) { inputs =>
          val (_, readyLost, twice) =
            inputs.foldLeft((LifecycleState.initial(p0, p0Name), false, false)) { case ((s, lost, dup), input) =>
              val next      = LifecycleStep.step(s, input)
              val escalated = next.effects.collect { case LifecycleEffect.EscalateInitTimeout(p, _) => p }
              // A second escalation needs a status out of Fatal, which only a swap gives.
              val again = escalated.nonEmpty && s.status == ProviderStatus.Fatal
              (next.state, lost || (s.everReady && !next.state.everReady), dup || again)
            }
          assertTrue(!readyLost, !twice)
        }
      }
    )
  ) @@ TestAspect.afterAll(ZIO.attemptBlocking(api.shutdown()).ignore)

  // Generators: providers from a small pool, so identities repeat.

  private val providers: List[OFFeatureProvider] = List(p0, m1, m2)
  private val managed: List[ManagedProvider]     = List(m1, m2)

  private val genStatus: Gen[Any, ProviderStatus] = Gen.elements(
    ProviderStatus.NotReady,
    ProviderStatus.Ready,
    ProviderStatus.Stale,
    ProviderStatus.Error,
    ProviderStatus.Fatal,
    ProviderStatus.ShuttingDown
  )

  private val genSettled: Gen[Any, Settled] =
    Gen.elements(Settled.Ready, Settled.Stale, Settled.Error("e", reused = false), Settled.Fatal("f", reused = false))

  private val genEvent: Gen[Any, LifecycleInput] =
    Gen.elements(Signal.EventReady, Signal.EventError(false), Signal.EventError(true), Signal.EventStale).map(event)

  private val genEventOrWatchdog: Gen[Any, LifecycleInput] =
    Gen.oneOf(genEvent, Gen.elements(providers: _*).map(p => LifecycleInput.WatchdogFired(p, 1.second)))

  private val genAdmit: Gen[Any, LifecycleInput] =
    for {
      m    <- Gen.elements(managed: _*)
      busy <- Gen.listOf(Gen.elements(managed: _*))
    } yield admit(m, (x: ManagedProvider) => busy.exists(_ eq x))

  private val genInput: Gen[Any, LifecycleInput] = Gen.oneOf(
    genEventOrWatchdog,
    genAdmit,
    Gen.elements(providers: _*).map(p => LifecycleInput.Switch(p, stack)),
    genSettled.map(s => event(Signal.SwapSucceeded(s))),
    genSettled.map(s => event(Signal.RollbackSucceeded(s))),
    genStatus.map(s => event(Signal.RollbackUnbound(s))),
    genStatus.map(s => LifecycleInput.Seed(s)),
    Gen.elements[LifecycleInput](
      event(Signal.SwapFailed),
      event(Signal.ShutdownStarted),
      event(Signal.ShutdownCompleted),
      LifecycleInput.GateDown,
      LifecycleInput.ReplayDeferred,
      LifecycleInput.ScopeClose,
      LifecycleInput.Idle
    ),
    Gen.elements(providers: _*).map(swapStart),
    Gen.elements(providers: _*).map(p => LifecycleInput.Reinstall(p, p0Name)),
    Gen.elements(providers: _*).map(p => LifecycleInput.NameRead(p, stack)),
    Gen.elements(providers: _*).map(p => LifecycleInput.OrphanRecorded(p, "holder")),
    Gen.elements(providers: _*).map(p => LifecycleInput.OrphanSettled(p)),
    Gen.elements(managed: _*).zip(Gen.elements(managed: _*)).map { case (h, r) => replaced(h, r) }
  )

  private val genState: Gen[Any, LifecycleState] =
    Gen.listOfBounded(0, 20)(genInput).map(inputs => run(LifecycleState.initial(p0, p0Name), inputs: _*)._1)
}
