package zio.openfeature.extras

import dev.openfeature.sdk.{EventProvider, EventProviderBridge, FeatureProvider, ProviderEvent, ProviderEventDetails}

/** A wrapper's hold on its delegate's event channel, shared by `CachingProvider` and `CircuitBreakerProvider`.
  *
  * Empty for a delegate that is not an `EventProvider`, so "attached to something that cannot emit" is unrepresentable.
  * `attach` records the attachment only once it has succeeded, and `detach` releases only an attachment this channel
  * made: the SDK's `detach` is unconditional, so detaching after a failed `attach` (the delegate already belonged to
  * another wrapper) would cut that other wrapper off.
  */
final private[extras] class DelegateEventChannel(
  delegate: FeatureProvider,
  onEvent: (ProviderEvent, ProviderEventDetails) => Unit
) {

  private val eventDelegate: Option[EventProvider] = delegate match {
    case ev: EventProvider => Some(ev)
    case _                 => None
  }

  private val attached = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Take the delegate's event channel; a no-op if already taken. Throws if another owner holds it. */
  def attach(): Unit =
    eventDelegate.foreach { ev =>
      attached.synchronized {
        if (!attached.get()) {
          EventProviderBridge.attach(ev, onEvent)
          attached.set(true)
        }
      }
    }

  /** Release the delegate's event channel if this channel took it. */
  def detach(): Unit =
    eventDelegate.foreach { ev =>
      attached.synchronized {
        if (attached.getAndSet(false)) scala.util.Try(EventProviderBridge.detach(ev)): Unit
      }
      ()
    }
}
