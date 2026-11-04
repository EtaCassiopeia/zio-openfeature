package zio.openfeature.internal

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, ProviderStateAccess}

/** What an instance registers with the SDK for a provider. */
sealed abstract private[openfeature] class Bound {

  /** The object the SDK binds to the slot. */
  def sdk: OFFeatureProvider

  /** The wrapper, when the library registers one. */
  def managed: Option[ManagedProvider]

  /** Whether an `initialize()` of this registration is still running. Unknown for a foreign provider, whose lifecycle
    * the SDK owns: `false`, as before the library registered wrappers.
    */
  def initializing: Boolean = managed.exists(_.initsInFlightAnywhere > 0)
}

private[openfeature] object Bound {

  /** The library's wrapper: its `initialize()` outcome, its once-per-cycle shutdown and its ordered events. */
  final case class Managed(provider: ManagedProvider) extends Bound {
    def sdk: OFFeatureProvider           = provider
    def managed: Option[ManagedProvider] = Some(provider)
  }

  /** A provider registered with this API outside the library, in a slot the library can see: the target slot, the
    * default one, or that of an instance built on this API. The SDK owns its lifecycle and reuses its registration;
    * none of the [[ManagedProvider]] guarantees (init outcome, once-per-cycle shutdown, ordered events) apply to it.
    */
  final case class Foreign(provider: OFFeatureProvider) extends Bound {
    def sdk: OFFeatureProvider           = provider
    def managed: Option[ManagedProvider] = None
  }

  /** How `provider` is registered for `domain`: as the SDK already holds it, or through its wrapper. Fails as the
    * wrapper's construction does, for an `EventProvider` attached to something else.
    */
  def of(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    probe: LifecycleProbe = LifecycleProbe.none
  ): Bound =
    provider match {
      case m: ManagedProvider => Managed(m)
      case _                  =>
        val wrapper = ManagedProvider.existing(provider)
        // The slot's own wrapper stays: binding the provider itself there would make the SDK shut the wrapper down, and
        // the provider with it, under whichever slot serves it.
        // A domain no instance was built on only reads the default slot's, which is not its own.
        wrapper.filter(w =>
          domain.forall(ApiInstances.knowsSlot(api, _)) && ProviderStateAccess.boundIn(api, domain.orNull, w)
        ) match {
          case Some(inSlot) => Managed(inSlot)
          // What the API holds wins over a wrapper cached from an earlier registration.
          case None if heldByApi(api, domain, provider) => Foreign(provider)
          case None                                     => Managed(wrapper.getOrElse(ManagedProvider(provider, probe)))
        }
    }

  /** The object the SDK binds for `provider` as an instance registered it: its wrapper, or itself. */
  def ofRegistered(provider: OFFeatureProvider): Bound =
    provider match {
      case m: ManagedProvider => Managed(m)
      case other              => Foreign(other)
    }

  /** Whether `slot`, the provider the SDK binds, is `incoming` by the SDK's own rule for reusing a registration:
    * identity for a domain-scoped provider, `equals` otherwise. A wrapper is equal only to itself, so for one both
    * rules are identity.
    */
  def alreadyBound(slot: OFFeatureProvider, incoming: Bound): Boolean =
    (slot eq incoming.sdk) || (slot != null && !incoming.sdk.isDomainScoped && incoming.sdk.equals(slot))

  private def heldByApi(api: OpenFeatureAPI, domain: Option[String], provider: OFFeatureProvider): Boolean =
    domain.exists(d => ProviderStateAccess.boundIn(api, d, provider)) ||
      ProviderStateAccess.boundIn(api, null, provider) || ApiInstances.boundInAnySlot(api, provider)
}
