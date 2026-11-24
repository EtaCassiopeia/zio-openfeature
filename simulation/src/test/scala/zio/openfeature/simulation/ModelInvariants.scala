package zio.openfeature.simulation

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._

import zio._
import zio.openfeature.{FeatureFlagsLive, ProviderStatus}
import zio.openfeature.internal.{LifecycleProbe, LifecycleState, Lock, ManagedProvider, ProviderStatusMachine}

/** The lifecycle model's safety invariants (`formal/lifecycle/Lifecycle.tla`), checked against the live record of the
  * instance under test. Each predicate is named after the TLA+ property it restates.
  *
  * The record is written only by whole lifecycle steps, and `snapshot` returns the record after one of them, so every
  * read is a state between steps, whichever thread reads it. The predicates in [[always]] hold after every step; those
  * in [[atPoint]] hold only while the swap's own fiber stands at the named point.
  */
object ModelInvariants {

  /** Holds after every lifecycle step. */
  def always(s: LifecycleState): List[String] =
    List(
      // GateDownWhenIdle == lock # "swap" => ~gate
      Option.when(s.lock != Lock.Swap && s.gate)(s"GateDownWhenIdle: gate up with lock ${s.lock}"),
      // OneLockHolder == (lock = "swap") <=> (swap # None)
      Option.when((s.lock == Lock.Swap) != s.swap.isDefined)(
        s"OneLockHolder: lock ${s.lock} with swap ${s.swap.map(_ => "in flight").getOrElse("none")}"
      ),
      // FinalNotReady == shutDown => status = IF lock = "shutdown" THEN "ShuttingDown" ELSE "NotReady"
      Option.when(
        s.shutDown && s.status != (if (s.lock == Lock.Shutdown) ProviderStatus.ShuttingDown
                                   else ProviderStatus.NotReady)
      )(
        s"FinalNotReady: shut down with status ${s.status} and lock ${s.lock}"
      ),
      // BoundNotShutDown == (~shutDown /\ ~closed) => shuts[bound] = 0, read on the installed provider's wrapper, and
      // only while no sequence holds the lock. Under the shutdown lock the code tears down before it sets shutDown,
      // which the model's ShutdownDone does in one action. Under the swap lock `installed` is already the incoming
      // provider, before the SDK binds it: a swap back to a wrapper shut down earlier shows it shut down until its next
      // initialize() starts a new cycle, while the model reads the slot, which still holds the old one.
      Option.when(!s.shutDown && !s.closed && s.lock == Lock.Free && (s.installed match {
        case m: ManagedProvider => m.shutDownInCycle
        case _                  => false
      }))(s"BoundNotShutDown: the installed provider was shut down in its cycle, status ${s.status}")
    ).flatten

  /** Holds while the swap's own fiber stands at `point`. */
  def atPoint(point: LifecycleProbe.Point, s: LifecycleState): List[String] =
    point match {
      // NotReadyDuringSwap: until the swap applies its outcome, the status is NotReady. Its binding points come before
      // the incoming provider's initialize() returns; the outcome's ApplySignal comes after it, just before the
      // outcome is applied, when an event the gate should have dropped has had the time to land.
      case LifecycleProbe.Point.SwapBind | LifecycleProbe.Point.SdkBind | LifecycleProbe.Point.SwapTimeout =>
        notReadyDuringSwap(point.name, s)
      case LifecycleProbe.Point.ApplySignal(ProviderStatusMachine.Signal.SwapSucceeded(_)) |
          LifecycleProbe.Point.ApplySignal(ProviderStatusMachine.Signal.SwapFailed) =>
        notReadyDuringSwap("the swap's outcome", s)
      case _ => Nil
    }

  // InstalledIsBound == (swap = None /\ ~shutDown /\ ~closed) => installed = bound: the provider the instance reports
  // is the one its slot binds. The slot is read apart from the record, so only while the record is the same object
  // before and after that read: no step ran in between.
  private def installedIsBound(ff: FeatureFlagsLive, s: LifecycleState): List[String] =
    if (s.lock != Lock.Free || s.shutDown || s.closed) Nil
    else {
      val bound = ff.sdkBound
      Option
        .when((ff.lifecycleSnapshot eq s) && !(bound eq s.installed))(
          s"InstalledIsBound: the instance reports ${s.name.render}, its slot binds another provider"
        )
        .toList
    }

  private def notReadyDuringSwap(where: String, s: LifecycleState): List[String] =
    Option
      .when(s.swap.isEmpty || s.status != ProviderStatus.NotReady)(
        s"NotReadyDuringSwap at $where: status ${s.status}, swap ${s.swap.isDefined}"
      )
      .toList

  /** Collects violations seen at probe points, once the instance is built. */
  final class Watch {
    private val instance = new AtomicReference[Option[FeatureFlagsLive]](None)
    private val found    = new ConcurrentLinkedQueue[String]()

    def attach(ff: FeatureFlagsLive): UIO[Unit] = ZIO.succeed(instance.set(Some(ff)))

    def check(where: String, point: Option[LifecycleProbe.Point]): UIO[Unit] =
      ZIO.succeed {
        instance.get().foreach { ff =>
          val s = ff.lifecycleSnapshot
          (always(s) ++ point.toList.flatMap(atPoint(_, s)) ++ installedIsBound(ff, s)).foreach { v =>
            Stats.hit("invariant.violated")
            found.add(s"$v (at $where)")
          }
          Stats.hit("invariant.checked")
        }
      }

    def violations: List[String] = found.asScala.toList.distinct
  }
}
