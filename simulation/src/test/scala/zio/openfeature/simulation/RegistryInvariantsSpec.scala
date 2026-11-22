package zio.openfeature.simulation

import zio.openfeature.ProviderStatus
import zio.test._

import RegistryInvariants.{Instance, Leaf, Slot, State}

object RegistryInvariantsSpec extends ZIOSpecDefault {

  private val sdk     = "openfeature-provider-thread-3"
  private val library = "zio-default-blocking-7"

  private def leaf(name: String, threads: String*) = Leaf(name, threads.toList)
  private def held(leaves: Leaf*)                  = Slot.Held(leaves.toList, failedOn = false)

  private val owner   = Instance(0, ownsApi = true, live = true, closed = false, held(leaf("L1")), ProviderStatus.Ready)
  private val sibling =
    Instance(1, ownsApi = false, live = true, closed = false, held(leaf("L2")), ProviderStatus.Ready)
  private val open = State(apiOpen = true, apiGone = false, List(owner, sibling), Nil)

  // The API after the owner's close returned: no instance live, every one NotReady, the slots gone.
  private val gone = State(
    apiOpen = false,
    apiGone = true,
    List(owner, sibling).map(_.copy(live = false, closed = true, slot = Slot.Empty, status = ProviderStatus.NotReady)),
    Nil
  )

  private def names(vs: List[String]) = vs.map(_.takeWhile(_ != ':'))

  def spec = suite("RegistryInvariants")(
    test("an open API with two serving instances, and one the owner closed, satisfy every invariant") {
      assertTrue(RegistryInvariants.always(open).isEmpty, RegistryInvariants.always(gone).isEmpty)
    },
    test("OneOwner: two instances own the API, or its owner is not live while it is open") {
      val two    = open.copy(instances = List(owner, sibling.copy(ownsApi = true)))
      val closed = open.copy(instances = List(owner.copy(live = false), sibling))
      assertTrue(
        RegistryInvariants.always(two) == List("OneOwner: i0, i1 own the API"),
        RegistryInvariants.always(closed) == List("OneOwner: the API is open, its owner not live"),
        RegistryInvariants.always(closed.copy(apiOpen = false)).isEmpty
      )
    },
    test("ForeignNeverShutByLibrary: a stack a slot holds was shut down by the library; by the SDK is not this one") {
      val bySdk     = open.copy(instances = List(owner, sibling.copy(live = false, slot = held(leaf("L2", sdk)))))
      val byLibrary = open.copy(instances = List(owner, sibling.copy(live = false, slot = held(leaf("L2", library)))))
      assertTrue(
        RegistryInvariants.always(bySdk).isEmpty,
        RegistryInvariants.always(byLibrary) ==
          List("ForeignNeverShutByLibrary: L2 in i1's slot shut down by the library")
      )
    },
    test("UserProviderNeverShutByLibrary: a stack the user bound was shut down by the library, even once unbound") {
      assertTrue(
        RegistryInvariants.always(gone.copy(userBound = List(leaf("L3", sdk)))).isEmpty,
        RegistryInvariants.always(gone.copy(userBound = List(leaf("L3", library)))) ==
          List("UserProviderNeverShutByLibrary: L3 shut down by the library")
      )
    },
    test("LiveSiblingKeepsProvider: a live instance's provider shut down by the library, unless it failed on it") {
      val shut   = sibling.copy(slot = held(leaf("L2", library)))
      val failed = sibling.copy(slot = Slot.Held(List(leaf("L2", library)), failedOn = true))
      assertTrue(
        names(RegistryInvariants.always(open.copy(instances = List(owner, shut)))) ==
          List("ForeignNeverShutByLibrary", "LiveSiblingKeepsProvider"),
        names(RegistryInvariants.always(open.copy(instances = List(owner, failed)))) == List(
          "ForeignNeverShutByLibrary"
        )
      )
    },
    test("ClosedNonOwnerKeepsSlot: a closed non-owner's slot is empty while the API is open") {
      val closed = sibling.copy(live = false, closed = true, status = ProviderStatus.NotReady)
      assertTrue(
        RegistryInvariants.always(open.copy(instances = List(owner, closed))).isEmpty,
        RegistryInvariants.always(open.copy(instances = List(owner, closed.copy(slot = Slot.Empty)))) ==
          List("ClosedNonOwnerKeepsSlot: closed i1's slot holds no provider"),
        RegistryInvariants.always(open.copy(instances = List(owner, closed.copy(slot = Slot.Unknown)))).isEmpty
      )
    },
    test("ClosedApiServesNone: an instance not NotReady once the owner's close returned; during the close it may be") {
      val serving = gone.copy(instances = gone.instances.map {
        case i if i.k == 1 => i.copy(status = ProviderStatus.Ready)
        case i             => i
      })
      assertTrue(
        RegistryInvariants.always(serving) == List("ClosedApiServesNone: i1 Ready after the owner's close returned"),
        RegistryInvariants.always(serving.copy(apiGone = false)).isEmpty,
        RegistryInvariants
          .always(serving.copy(instances = serving.instances.map(_.copy(status = ProviderStatus.ShuttingDown))))
          .isEmpty
      )
    },
    test("SiblingsUnaffected: a quiet sibling moved off its provider or retired by a non-owner's close") {
      val p, q   = new Object
      val before = Map(0 -> (p, ProviderStatus.Ready), 2 -> (q, ProviderStatus.Ready))
      assertTrue(
        RegistryInvariants.siblingsUnaffected(1, Set(0, 2), before, before).isEmpty,
        RegistryInvariants.siblingsUnaffected(1, Set(0, 2), before, before.updated(2, (p, ProviderStatus.Ready))) ==
          List("SiblingsUnaffected: i1's close moved i2 off the provider its slot held"),
        RegistryInvariants.siblingsUnaffected(1, Set(0), before, before.updated(0, (p, ProviderStatus.NotReady))) ==
          List("SiblingsUnaffected: i1's close retired i0 (Ready to NotReady)"),
        // Its own provider moved its status: not a phase change.
        RegistryInvariants.siblingsUnaffected(1, Set(0), before, before.updated(0, (p, ProviderStatus.Error))).isEmpty,
        // Not quiet: a swap of its own moved it.
        RegistryInvariants.siblingsUnaffected(1, Set(2), before, before.updated(0, (q, ProviderStatus.Error))).isEmpty
      )
    }
  )
}
