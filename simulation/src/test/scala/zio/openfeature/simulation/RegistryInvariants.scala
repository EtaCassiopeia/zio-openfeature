package zio.openfeature.simulation

import zio.openfeature.ProviderStatus

/** The registry model's safety invariants (`formal/registry/Registry.tla`), restated over a snapshot of the shared-API
  * simulation's live state. Each predicate is named after the TLA+ property it restates, with its definition quoted.
  *
  * "Who shut it down" (the model's `by[p]`) is the thread each `shutdown()` ran on: the SDK's executor for its queued
  * shutdown of a replaced provider and for the API's teardown, the library's otherwise ([[bySdk]]). The trace labelers
  * use the same rule.
  */
object RegistryInvariants {

  /** A leaf of a stack, and the thread each of its `shutdown()` calls ran on. */
  final case class Leaf(name: String, shutdownThreads: List[String])

  /** What a slot holds, as the snapshot read it. */
  sealed trait Slot extends Product with Serializable
  object Slot {

    /** No provider of the run: the SDK's default answer for an unbound domain. */
    case object Empty extends Slot

    /** A stack the snapshot cannot judge: one whose `initialize()` fails (a multi-provider shuts its members down
      * itself), or one a swap moved the slot off while the snapshot read it.
      */
    case object Unknown extends Slot

    /** A stack of the run; `failedOn`: the instance's last failure marked this stack (`failedOn[i]`). */
    final case class Held(leaves: List[Leaf], failedOn: Boolean) extends Slot
  }

  /** One instance: `live` until its close (or the owner's) starts, `closed` once it returned. */
  final case class Instance(
    k: Int,
    ownsApi: Boolean,
    live: Boolean,
    closed: Boolean,
    slot: Slot,
    status: ProviderStatus
  )

  /** `apiOpen` until the owner's close (or the run's scope close) starts; `apiGone` once the owner's close returned.
    * `userBound`: the stacks the user bound.
    */
  final case class State(apiOpen: Boolean, apiGone: Boolean, instances: List[Instance], userBound: List[Leaf])

  def bySdk(thread: String): Boolean = thread.startsWith("openfeature-provider-thread-")

  private def notLive(status: ProviderStatus): Boolean =
    status == ProviderStatus.NotReady || status == ProviderStatus.ShuttingDown

  private def shutByLibrary(leaves: List[Leaf]): List[String] =
    leaves.filter(_.shutdownThreads.exists(t => !bySdk(t))).map(_.name)

  /** Holds in every state. */
  def always(s: State): List[String] = {
    val held = s.instances.collect { case i @ Instance(_, _, _, _, Slot.Held(ls, f), _) => (i, ls, f) }
    List(
      // OneOwner == Cardinality({i : owner = i}) <= 1 /\ (owner # None => apiOpen /\ Live(owner))
      Option.when(s.instances.count(_.ownsApi) > 1)(
        s"OneOwner: ${s.instances.filter(_.ownsApi).map(i => s"i${i.k}").mkString(", ")} own the API"
      ),
      Option.when(s.apiOpen && s.instances.exists(i => i.ownsApi && !i.live))(
        "OneOwner: the API is open, its owner not live"
      ),
      // ForeignNeverShutByLibrary == \A p : by[p] = "library" => p \notin registered
      Option.when(s.apiOpen && held.exists(h => shutByLibrary(h._2).nonEmpty))(
        "ForeignNeverShutByLibrary: " + held
          .flatMap(h => shutByLibrary(h._2).map(n => s"$n in i${h._1.k}'s slot"))
          .mkString(", ") + " shut down by the library"
      ),
      // UserProviderNeverShutByLibrary == \A p \in userOwned : by[p] # "library"
      Option.when(shutByLibrary(s.userBound).nonEmpty)(
        s"UserProviderNeverShutByLibrary: ${shutByLibrary(s.userBound).mkString(", ")} shut down by the library"
      ),
      // LiveSiblingKeepsProvider == \A i : Live(i) /\ bound[d] # None /\ failedOn[i] # bound[d] => by[bound[d]] # "library"
      held.collectFirst {
        case (i, ls, failed) if s.apiOpen && i.live && !failed && shutByLibrary(ls).nonEmpty =>
          s"LiveSiblingKeepsProvider: i${i.k}'s ${shutByLibrary(ls).mkString(", ")} shut down by the library"
      },
      // ClosedNonOwnerKeepsSlot == \A i : phase[i] = "closed" /\ apiOpen => bound[domainOf[i]] # None
      s.instances.collectFirst {
        case i if s.apiOpen && i.closed && !i.ownsApi && i.slot == Slot.Empty =>
          s"ClosedNonOwnerKeepsSlot: closed i${i.k}'s slot holds no provider"
      },
      // ClosedApiServesNone == ~apiOpen => \A i : ~Live(i), read as the status once the owner's close returned: NotReady,
      // or ShuttingDown while a retired instance's own scope closes
      Option.when(s.apiGone && s.instances.exists(i => !notLive(i.status)))(
        "ClosedApiServesNone: " + s.instances
          .filterNot(i => notLive(i.status))
          .map(i => s"i${i.k} ${i.status}")
          .mkString(", ") + " after the owner's close returned"
      )
    ).flatten
  }

  /** `SiblingsUnaffected == [][\A i : CloseInstance(i) => bound' = bound /\ \A j # i : phase'[j] = phase[j]]_vars`,
    * across instance `closing`'s close: each sibling in `quiet` (no swap in flight or started across the close) keeps
    * the provider its slot held, by identity, and is not retired: its status does not become NotReady. Any other status
    * change is the sibling's own provider at work (an open breaker, a deferred initialization), not a phase change.
    * `before` and `after` map an instance to its slot's provider and its status.
    */
  def siblingsUnaffected(
    closing: Int,
    quiet: Set[Int],
    before: Map[Int, (AnyRef, ProviderStatus)],
    after: Map[Int, (AnyRef, ProviderStatus)]
  ): List[String] =
    quiet.toList.sorted.filter(_ != closing).flatMap { j =>
      (before.get(j), after.get(j)) match {
        case (Some((p0, _)), Some((p1, _))) if !(p0 eq p1) =>
          List(s"SiblingsUnaffected: i$closing's close moved i$j off the provider its slot held")
        case (Some((_, s0)), Some((_, s1))) if s0 != ProviderStatus.NotReady && s1 == ProviderStatus.NotReady =>
          List(s"SiblingsUnaffected: i$closing's close retired i$j ($s0 to NotReady)")
        case _ => Nil
      }
    }
}
