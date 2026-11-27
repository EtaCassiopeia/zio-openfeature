package zio.openfeature.internal

import zio._
import zio.openfeature.FeatureFlagsLive
import dev.openfeature.sdk.{EventProviderAccess, FeatureProvider, OpenFeatureAPI, ProviderStateAccess}
import scala.jdk.CollectionConverters._

/** Every instance built on each `OpenFeatureAPI`, so the one that tears an API down can retire the others: their
  * provider goes with the API, and the SDK's fresh registry would otherwise answer them with its No-op provider while
  * they still report `Ready`.
  *
  * Weak both ways, compared by identity (neither type overrides `equals`). An instance holds its API, so a strong value
  * would keep every API, and every instance built on one, for the life of the JVM; a weak one keeps neither. An
  * instance that is gone cannot be retired, and retiring one whose scope already closed only repeats its teardown.
  */
private[openfeature] object ApiInstances {

  // The slots of every instance built on each API, kept past the instance: a closed instance's slot keeps its registration
  // however long ago the instance was collected. Weak in the API; the domains hold nothing.
  private val slots = new java.util.WeakHashMap[OpenFeatureAPI, Set[Option[String]]]()

  private val instances =
    new java.util.WeakHashMap[OpenFeatureAPI, java.util.WeakHashMap[FeatureFlagsLive, java.lang.Boolean]]()

  def register(api: OpenFeatureAPI, ff: FeatureFlagsLive, domain: Option[String]): Unit = synchronized {
    slots.put(api, Option(slots.get(api)).getOrElse(Set.empty) + domain)
    val onApi = Option(instances.get(api)).getOrElse {
      val fresh = new java.util.WeakHashMap[FeatureFlagsLive, java.lang.Boolean]()
      instances.put(api, fresh)
      fresh
    }
    val _ = onApi.put(ff, java.lang.Boolean.TRUE)
  }

  private def on(api: OpenFeatureAPI): List[FeatureFlagsLive] = synchronized {
    Option(instances.get(api)).fold(List.empty[FeatureFlagsLive])(_.keySet.asScala.toList)
  }

  /** Whether a live instance on `api` other than `self` is bound to `provider`'s registration: the SDK shares one state
    * manager between slots, so shutting it down would take it from that instance too. Asked of the instances because
    * the SDK does not list an API's slots. One that closed, that failed on this provider, or that was collected no
    * longer counts: its slot keeps the registration, which the API's shutdown reaches.
    */
  def heldByAnother(api: OpenFeatureAPI, self: FeatureFlagsLive, provider: FeatureProvider): Boolean =
    on(api).exists(instance => (instance ne self) && instance.serves(provider))

  /** Whether another live instance's slot on `api` holds `provider`. A collected instance is not seen, though its slot
    * keeps the provider: a decision about what the SDK leaves asks `boundInAnySlot`.
    */
  def boundElsewhere(api: OpenFeatureAPI, self: FeatureFlagsLive, provider: FeatureProvider): Boolean =
    on(api).exists(instance => (instance ne self) && instance.slotHolds(provider))

  /** Whether the SDK has a shutdown of `provider` queued on `api`: a live instance's swap or rollback replaced it with
    * a registration still initializing.
    */
  def shutdownQueued(api: OpenFeatureAPI, provider: FeatureProvider): Boolean =
    on(api).exists(_.queuesShutdownOf(provider))

  // The providers a swap in flight on each API is moving off, each with the instance swapping and its slot: the SDK
  // queued their shutdown at that swap's binding, keyed by the state manager they had there. Compared by identity.
  private val swappingOff =
    new java.util.WeakHashMap[OpenFeatureAPI, List[(FeatureProvider, AnyRef, Option[String])]]()

  /** Claim `provider` for the swap of `claimant` moving `domain`'s slot off it, unless another instance's swap claimed
    * it from another slot first. Checks and claims in one step, so of two swaps off one provider in two slots at most
    * one gets it. A swap on the same slot binds over the first one's provider, not over `provider`, so it claims too.
    */
  def claimSwapOff(api: OpenFeatureAPI, claimant: AnyRef, domain: Option[String], provider: FeatureProvider): Boolean =
    synchronized {
      val claims = Option(swappingOff.get(api)).getOrElse(Nil)
      if (claims.exists { case (p, by, d) => (p eq provider) && (by ne claimant) && d != domain }) false
      else {
        val _ = swappingOff.put(api, (provider, claimant, domain) :: claims)
        true
      }
    }

  /** Drop the claim of `claimant` on `provider`. */
  def releaseSwapOff(api: OpenFeatureAPI, claimant: AnyRef, provider: FeatureProvider): Unit = synchronized {
    val kept = Option(swappingOff.get(api)).getOrElse(Nil).filterNot { case (p, by, _) =>
      (p eq provider) && (by eq claimant)
    }
    val _ = if (kept.isEmpty) swappingOff.remove(api) else swappingOff.put(api, kept)
  }

  /** Whether a swap of an instance other than `except` (which may be `null`) is moving off `provider` on `api`. */
  def swappedOffByAnother(api: OpenFeatureAPI, provider: FeatureProvider, except: AnyRef): Boolean = synchronized {
    Option(swappingOff.get(api)).exists(_.exists { case (p, by, _) => (p eq provider) && (by ne except) })
  }

  /** Whether an instance was ever built on `domain` of `api`. */
  def knowsSlot(api: OpenFeatureAPI, domain: String): Boolean =
    synchronized(Option(slots.get(api)).exists(_.contains(Some(domain))))

  /** Whether the slot of any instance ever built on `api` holds `provider`. The SDK does not list an API's slots, so
    * these and the default slot are the ones the library can see.
    */
  def boundInAnySlot(api: OpenFeatureAPI, provider: FeatureProvider): Boolean = {
    val known = synchronized(Option(slots.get(api)).getOrElse(Set.empty[Option[String]]))
    known.exists(domain => ProviderStateAccess.boundIn(api, domain.orNull, provider))
  }

  // The library's wrappers that a timed-out swap of an instance not owning `api` replaced, each with the count of
  // `initialize()` calls it had started then. Strong values: nothing else may hold such a wrapper. A wrapper still
  // attached to its API keeps that key reachable until the owner's close drains the entry; an API nothing in the
  // library owns is never drained, and stays with its user, as everything registered with it does.
  private val leftBehind = new java.util.WeakHashMap[OpenFeatureAPI, List[(ManagedProvider, Long)]]()

  /** Keep `wrapper` for the close of `api`'s owner: a timed-out swap of an instance that does not own `api` replaced
    * it, and only that swap's pending `initialize()` holds it. The SDK shuts it down if that call returns; if it
    * throws, nothing else reaches it, as no slot holds it and the instance leaves every provider of `api` to its owner.
    */
  def leaveBehind(api: OpenFeatureAPI, wrapper: ManagedProvider): Unit = synchronized {
    val kept =
      Option(leftBehind.get(api)).getOrElse(Nil).filterNot { case (w, _) => (w eq wrapper) || w.shutDownInCycle }
    val _ = leftBehind.put(api, (wrapper, wrapper.initsStarted) :: kept)
  }

  /** The wrappers left behind on `api` that its owner's close would release now (still running in the cycle they were
    * left in), for a model replay.
    */
  def leftBehindRunning(api: OpenFeatureAPI): List[ManagedProvider] = synchronized {
    Option(leftBehind.get(api)).getOrElse(Nil).collect {
      case (wrapper, started) if !wrapper.shutDownInCycle && wrapper.initsStarted == started => wrapper
    }
  }

  /** At the close of `api`'s owner, after the API's shutdown: shut down each wrapper a non-owner's timed-out swap left
    * behind that is still running in the cycle it was left in. One the SDK or the API's shutdown reached is shut down
    * in its cycle; one initialized again since, on this API or another, serves there and is left alone.
    */
  def releaseLeftBehind(api: OpenFeatureAPI): UIO[Unit] =
    ZIO
      .succeed(synchronized(Option(leftBehind.remove(api)).getOrElse(Nil)))
      .flatMap(ZIO.foreachDiscard(_) {
        case (wrapper, started) if !wrapper.shutDownInCycle && wrapper.initsStarted == started =>
          // As the SDK's own `shutDownOld`: released from the API too, so another API can register it.
          ProviderRegistration.boundedProviderShutdown(wrapper, "API close (provider a timed-out swap left behind)") *>
            ZIO
              .attemptBlocking {
                EventProviderAccess.deregisterGlobalProvider(api, wrapper)
                EventProviderAccess.detach(wrapper)
              }
              .catchAllCause(c =>
                ZIO.logWarningCause(
                  "API close: releasing a provider a timed-out swap left behind from the OpenFeature API failed; " +
                    "registering it again may fail with 'already attached'",
                  c
                )
              )
        case _ => ZIO.unit
      })

  /** Retire every instance on `api` but `except` (the one tearing it down, or `null` before it exists). */
  def retireSiblings(api: OpenFeatureAPI, except: FeatureFlagsLive): UIO[Unit] =
    ZIO.succeed(on(api).filterNot(_ eq except)).flatMap(ZIO.foreachParDiscard(_)(_.retire))

  /** Retire every instance on `api`: its owner (a registry) is tearing it down. */
  def retireAll(api: OpenFeatureAPI): UIO[Unit] =
    retireSiblings(api, except = null)
}
