package zio.openfeature.internal

import zio._
import zio.stream._
import zio.openfeature._
import zio.openfeature.internal.ProviderStatusMachine.Signal
import dev.openfeature.sdk.{
  Client => OFClient,
  ProviderEvent => JavaProviderEvent,
  ProviderState => JavaProviderState,
  EventDetails
}

import scala.jdk.CollectionConverters._
import EventBridge.runHandler

/** The event bridge of a [[FeatureFlagsLive]]: the status-machine glue, the Java SDK event handlers that drive it, and
  * the ZIO-side event subscriptions (`onProviderReady`, `on`, ...).
  */
final private[openfeature] class EventBridge(
  client: OFClient,
  initialProvider: dev.openfeature.sdk.FeatureProvider,
  providerNameRef: java.util.concurrent.atomic.AtomicReference[ProviderName],
  state: FeatureFlagsState,
  onReady: Option[java.util.concurrent.CountDownLatch],
  probe: LifecycleProbe = LifecycleProbe.none
) {

  // The record's `gate`, mirrored for the evaluator, which reads it synchronously: true while `setProvider` holds the
  // swap lock and re-registers the provider, when bridge events do not drive the status (a queued event from the OLD
  // provider would clobber the swap's own outcome). Written only by the lifecycle's interpreter.
  private[openfeature] val swapInProgress = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Bumped by each swap's start. The evaluator compares it across its provider call to catch a swap that started and
  // ended inside that call: the gate is down again by the re-check, and the answer came from the provider the swap
  // replaced. A swap still in flight is the gate's to catch. So the bump only has to land while the gate is up; the
  // swap's start writes the gate, then the status, then this, so an evaluator that reads the new value finds the gate
  // up, and one that reads the old value with the status already NotReady is refused at its check. The lifecycle
  // model checks this (formal/lifecycle/README.md, "Evaluation").
  private[openfeature] val swapGeneration = new java.util.concurrent.atomic.AtomicLong(0L)

  /** The instance's lifecycle record and its interpreter: every status write goes through it. */
  private[openfeature] val lifecycle =
    new Lifecycle(state.statusRef, initialProvider, swapInProgress, swapGeneration, providerNameRef)

  // Set by startEventBridge; invoked by `shutdown` BEFORE teardown (so no emitter thread races it) and by the scope
  // finalizer (idempotent — removeHandler on an already-removed handler is a no-op in the SDK, so double-invocation
  // is safe).
  private[openfeature] val bridgeDeregister =
    new java.util.concurrent.atomic.AtomicReference[() => Unit](() => ())

  // Whether the bridge's handlers are on the client: set when they are added, cleared when they are removed.
  private val attached = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Whether the bridge's handlers are still registered with the client. */
  private[openfeature] def bridgeAttached: Boolean = attached.get()

  /** A status signal, through the lifecycle's step. Every status write goes through the lifecycle, the testkit's
    * overrides included, which serializes it against every other. Returns Some(next) iff a transition happened, so
    * callers can attach transition-gated side effects (watchdog shutdown, latch release).
    */
  private[openfeature] def applySignal(signal: Signal): UIO[Option[ProviderStatus]] =
    probe.at(LifecycleProbe.Point.ApplySignal(signal)) *>
      lifecycle.run(LifecycleInput.Status(signal)).map(_.transitioned)

  /** [[applySignal]] for an event the SDK delivered: the signal is the one the SDK's own state for this client's slot
    * implies, read inside the serialized write; `own` (the event's signal) applies only when the slot is unreadable, or
    * when the event is a fatal error. The SDK writes the slot state in the emit task, on the provider's emitter pool,
    * before it runs that event's handlers, and runs the handlers with no ordering between them; so whichever handler
    * applies last reads the slot's final state, and an ERROR handled after the READY that followed it cannot leave the
    * status on Error. Across events there is no such guarantee: two emissions from one provider write the slot in
    * either order unless the provider awaits each one (the extras wrappers do), and the status follows the slot. For
    * the same reason an event from the installed provider never finds the slot `NOT_READY`: one that does is about
    * another registration (a replaced provider with the same name, which the identity guard cannot tell apart), and
    * applies no signal. Returns the signal applied, if any, and the transition.
    */
  private def applySdkSignal(own: Signal): UIO[(Option[Signal], Option[ProviderStatus])] =
    probe.at(LifecycleProbe.Point.ApplySignal(own)) *> lifecycle
      .runWith { _ =>
        // A fatal error is final whatever the slot reports by the time its handler runs.
        val (signal, unreadable) =
          if (own == Signal.EventError(true)) (Some(own), None)
          else
            try (EventBridge.signalForSlot(client.getProviderState), None)
            catch { case scala.util.control.NonFatal(e) => (Some(own), Some(e)) }
        // No signal for the slot: a step that changes nothing.
        (signal.fold[LifecycleInput](LifecycleInput.Idle)(LifecycleInput.Status(_)), (signal, unreadable))
      }
      .map { case ((signal, unreadable), outcome) => (signal, outcome.transitioned, unreadable) }
      .flatMap { case (signal, transitioned, unreadable) =>
        ZIO
          .foreachDiscard(unreadable)(e =>
            ZIO.logWarningCause("event bridge: could not read the SDK state; applying the event as sent", Cause.die(e))
          )
          .as((signal, transitioned))
      }

  // The SDK stamps every dispatched event with the emitting provider's metadata name — or NULL when the provider has
  // no metadata/name (verified sdk-1.21.0, OpenFeatureAPI.runHandlersForProvider). An event that does not match the
  // CURRENT provider is a stale event from a replaced/failed provider still queued on the SDK's emitter executor: it
  // must not drive the status machine. An event with no name cannot be attributed to a *different* provider, so it
  // fails open; two providers with no name are indistinguishable, as same-named swaps are (a known limitation of the
  // identity guard). A `Pending` current name fails open too: a provider like the SDK's MultiProvider only gets its name
  // inside initialize(), while its own events carry it, and the fail-open ends once it has initialized.
  //
  // Phase 2 watch-point: re-verify this identity-guard behaviour once the OpenFeature Java SDK ships the
  // spec-v0.9.0 provider-event marker — it may offer a more direct way to attribute an event to its emitting provider.
  private def fromCurrentProvider(details: EventDetails): Boolean =
    (Option(details.getProviderName), providerNameRef.get()) match {
      case (None, _)                        => true
      case (_, ProviderName.Pending)        => true
      case (Some(_), ProviderName.Nameless) => false
      case (Some(n), ProviderName.Named(c)) => n == c
    }

  /** Settle a `Pending` name once the status is `Ready`: the SDK's state for the slot was READY, which a provider's own
    * emission can also make it while its `initialize()` runs, so a wrapper still initializing keeps `Pending`, and the
    * READY the SDK sends when that call returns settles it. The installed provider's own metadata decides, so an event
    * cannot write another provider's name whatever it is stamped with.
    */
  private[openfeature] def settlePendingName: UIO[Unit] =
    ZIO.suspendSucceed {
      val record = lifecycle.snapshot
      val settle = record.status == ProviderStatus.Ready && record.name == ProviderName.Pending &&
        !Bound.ofRegistered(record.installed).initializing
      ZIO.when(settle)(readName(record.installed)).unit
    }

  /** Record `provider`'s name now that it has initialized. A throw from the provider leaves the name `Pending`: it says
    * nothing about the name, and the swap or event that asked has its own outcome.
    */
  private[openfeature] def readName(provider: dev.openfeature.sdk.FeatureProvider): UIO[Unit] =
    ZIO
      .attempt(ProviderNames.afterInit(provider))
      .foldZIO(
        e => ZIO.logDebug(s"event bridge: could not read the provider's name: ${e.getMessage}"),
        name => lifecycle.run(LifecycleInput.NameRead(provider, name)).unit
      )

  // Truthful payloads: publish under the name the SDK stamped on the event (the actual emitter), falling back to the
  // current provider only when the event carries none.
  private def eventProviderMetadata(details: EventDetails): ProviderMetadata = {
    val name = details.getProviderName
    ProviderMetadata(if (name == null) providerNameRef.get().render else name)
  }

  // Event metadata is optional, so a payload that cannot be read must not drop the event: it is published with empty
  // metadata — but the failure is logged rather than vanishing.
  private def extractEventMetadata(details: EventDetails): UIO[FlagMetadata] =
    ZIO
      .attempt {
        val javaMeta = details.getEventMetadata
        if (javaMeta == null || javaMeta.isEmpty) FlagMetadata.empty
        else Evaluator.convertImmutableMetadata(javaMeta)
      }
      .catchAllCause(c =>
        ZIO
          .logWarningCause("event bridge: unreadable provider event metadata; publishing the event without it", c)
          .as(FlagMetadata.empty)
      )

  // Bridge event bodies as pure effects, so tests can drive them directly with a builder-constructed
  // EventDetails. The Java-side Consumers registered in startEventBridge are thin wrappers that run these via
  // `runHandler`. Events are ALWAYS published for observers; identity/machine gating applies only to the status
  // transition and the onReady latch. The one exception is a PROVIDER_READY the SDK itself does not back with a ready
  // state (see `onSdkEvent`): that one is not delivered at all. With `fromSdk` the status signal comes from the
  // SDK's state for the slot (`applySdkSignal`); without it, from the event type.

  /** The status signal applied for an event and its transition; None for an event from another provider, or one the
    * SDK's state for the slot does not back.
    */
  private def applyFor(current: Boolean, fromSdk: Boolean, own: Signal): UIO[Option[(Signal, Option[ProviderStatus])]] =
    if (!current) ZIO.none
    else if (fromSdk) applySdkSignal(own).map { case (signal, transitioned) => signal.map((_, transitioned)) }
    else applySignal(own).map(t => Some((own, t)))

  private[openfeature] def onReadyEvent(details: EventDetails, fromSdk: Boolean = false): UIO[Unit] =
    extractEventMetadata(details).flatMap { em =>
      applyFor(fromCurrentProvider(details), fromSdk, Signal.EventReady).flatMap { applied =>
        // Before the event and the latch, so whoever waits for this READY reads the name.
        settlePendingName *>
          state.eventHub.publish(ProviderEvent.Ready(eventProviderMetadata(details), em)) *>
          // The latch counts READY deliveries from the current provider.
          ZIO.succeed(onReady.foreach(_.countDown())).when(applied.nonEmpty)
      }.unit
    }

  /** A provider event as the SDK delivers it, with the status signal taken from the SDK's state for the slot.
    *
    * A PROVIDER_READY found while this client's slot is still `NOT_READY` is dropped. The SDK sends READY to every
    * domain bound to a provider when ANY registration of it finishes its init call — including a registration that
    * reused a manager still initializing for another domain, which returns at once while the state is NOT_READY. The
    * SDK sets the state before dispatching an event, so a real READY never finds it NOT_READY; the READY the SDK sends
    * when the shared initialization completes takes the dropped one's place. A READY found with the slot in another
    * state is delivered, and the status follows that state.
    */
  private[openfeature] def onSdkEvent(event: JavaProviderEvent, details: EventDetails): UIO[Unit] =
    probe.at(LifecycleProbe.Point.DeliverEvent(event.name)) *> deliver(event, details)

  private def deliver(event: JavaProviderEvent, details: EventDetails): UIO[Unit] = event match {
    case JavaProviderEvent.PROVIDER_READY =>
      ZIO
        .attempt(client.getProviderState)
        .map(_ != JavaProviderState.NOT_READY)
        .catchAllCause(c =>
          // The state cannot be read: fall back to the event, as the identity guard does for an unknown provider.
          ZIO.logWarningCause("event bridge: could not confirm PROVIDER_READY against the SDK state", c).as(true)
        )
        .flatMap { confirmed =>
          if (confirmed) onReadyEvent(details, fromSdk = true)
          else ZIO.logDebug("event bridge: dropped a PROVIDER_READY sent while the provider is still initializing")
        }
    case JavaProviderEvent.PROVIDER_ERROR => onErrorEvent(details, fromSdk = true)
    case JavaProviderEvent.PROVIDER_STALE => onStaleEvent(details, fromSdk = true)
    case _                                => ZIO.unit
  }

  private[openfeature] def onErrorEvent(details: EventDetails, fromSdk: Boolean = false): UIO[Unit] =
    extractEventMetadata(details).flatMap { em =>
      val error     = new RuntimeException(Option(details.getMessage).getOrElse("Provider error"))
      val errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava)
      val fatal     = errorCode.contains(ErrorCode.ProviderFatal)
      applyFor(fromCurrentProvider(details), fromSdk, Signal.EventError(fatal)).flatMap { applied =>
        // A Fatal transition must release latch waiters (registry handshake, awaitReady seeds) so they observe Fatal
        // instead of stalling the full timeout.
        ZIO.succeed(onReady.foreach(_.countDown())).when(applied.exists(_._2.contains(ProviderStatus.Fatal)))
      } *>
        state.eventHub
          .publish(
            ProviderEvent.Error(error, eventProviderMetadata(details), errorCode, Option(details.getMessage), em)
          )
          .unit
    }

  private[openfeature] def onStaleEvent(details: EventDetails, fromSdk: Boolean = false): UIO[Unit] =
    extractEventMetadata(details).flatMap { em =>
      val reason = Option(details.getMessage).getOrElse("Provider stale")
      applyFor(fromCurrentProvider(details), fromSdk, Signal.EventStale) *>
        state.eventHub.publish(ProviderEvent.Stale(reason, eventProviderMetadata(details), em)).unit
    }

  // Bridge Java SDK provider events to ZIO event system
  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] =
    ZIO.runtime[Any].flatMap { runtime =>
      // `suspendSucceed` defers the handler bodies (metadata extraction, identity check, payload construction) into the
      // effect so a synchronous throw while building them on the Java emitter thread becomes a defect caught by
      // `runHandler`'s `catchAllCause`, rather than escaping to kill the SDK's dispatch thread.
      val readyHandler: java.util.function.Consumer[EventDetails] =
        details =>
          runHandler(runtime, "PROVIDER_READY")(
            ZIO.suspendSucceed(onSdkEvent(JavaProviderEvent.PROVIDER_READY, details))
          )

      val errorHandler: java.util.function.Consumer[EventDetails] =
        details =>
          runHandler(runtime, "PROVIDER_ERROR")(
            ZIO.suspendSucceed(onSdkEvent(JavaProviderEvent.PROVIDER_ERROR, details))
          )

      val staleHandler: java.util.function.Consumer[EventDetails] =
        details =>
          runHandler(runtime, "PROVIDER_STALE")(
            ZIO.suspendSucceed(onSdkEvent(JavaProviderEvent.PROVIDER_STALE, details))
          )

      val configHandler: java.util.function.Consumer[EventDetails] = details =>
        runHandler(runtime, "PROVIDER_CONFIGURATION_CHANGED")(
          ZIO.suspendSucceed {
            val flags = Option(details.getFlagsChanged)
              .map(_.asScala.toSet)
              .getOrElse(Set.empty[String])
            extractEventMetadata(details).flatMap { em =>
              state.eventHub
                .publish(ProviderEvent.ConfigurationChanged(flags, eventProviderMetadata(details), em))
                .unit
            }
          }
        )

      // Deregistration thunk stored on the instance so `shutdown` can remove the handlers BEFORE tearing down the API
      // (no emitter thread can then race the teardown); the scope finalizer keeps calling it too (idempotent).
      val deregister: () => Unit = () => {
        client.removeHandler(JavaProviderEvent.PROVIDER_READY, readyHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_STALE, staleHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
        attached.set(false)
      }

      for {
        _ <- ZIO.succeed {
          client.on(JavaProviderEvent.PROVIDER_READY, readyHandler)
          client.on(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
          client.on(JavaProviderEvent.PROVIDER_STALE, staleHandler)
          client.on(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
          attached.set(true)
          bridgeDeregister.set(deregister)
          ()
        }
        _ <- ZIO.addFinalizer(ZIO.attempt(deregister()).ignore)
      } yield ()
    }

  // Event Handlers - return cancellation effects per OpenFeature spec 5.2.7

  /** Runs `zio` with this library's FiberRefs reset. A handler outlives the call that registered it, so it must not
    * carry that call's transaction (whose overrides it would keep applying, and whose finished state it would keep
    * recording into) or its fiber-local context. Other FiberRefs — log annotations, ones a ContextSource reads — are
    * still inherited.
    */
  private def detached[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    state.transactionRef.locally(None)(state.fiberContextRef.locally(EvaluationContext.empty)(zio))

  /** Fork a daemon fiber consuming hub events, guaranteeing the hub subscription is established before this method
    * returns. Without the handshake, events published between handler registration and the forked stream's first pull
    * would be silently lost. The returned effect cancels the subscription.
    */
  private def consumeEvents(consume: ZStream[Any, Nothing, ProviderEvent] => UIO[Unit]): UIO[UIO[Unit]] =
    for {
      subscribed <- Promise.make[Nothing, Unit]
      // Forked inside `detached`, so the fork copies the reset FiberRefs rather than the registering fiber's.
      // `interruptible`: a fork inherits the registering fiber's interruptibility, and `acquireRelease` runs its acquire
      // uninterruptibly, so without it a handler registered there could never be cancelled (the canceller hung).
      fiber <- detached(
        ZIO
          .scoped {
            state.eventHub.subscribe.flatMap { queue =>
              subscribed.succeed(()) *> consume(ZStream.fromQueue(queue))
            }
          }
          .interruptible
          .forkDaemon
      )
      _ <- subscribed.await
    } yield fiber.interrupt.unit

  /** Per OpenFeature spec 5.3.3, handlers attached after the provider reaches an associated state MUST run immediately.
    *
    * The subscription is established first, then the current status is checked: no event can fall between the two. The
    * cost is at-least-once delivery — an event arriving in that window may invoke the handler via both the immediate
    * check and the stream.
    */
  // Isolate a handler invocation so a defect does NOT kill the delivery fiber — the subscription is retained and the
  // handler keeps receiving subsequent events (spec 5.2.5). `suspendSucceed` is essential: it captures a *synchronous*
  // throw while `handler(a)` is producing its effect (e.g. a raw exception thrown in the handler body before it returns
  // a ZIO) as a defect, not just an already-suspended `ZIO.die`. Interruption is re-raised (not logged) so cancelling
  // the subscription still tears the fiber down.
  private def isolate[A](handler: A => UIO[Unit]): A => UIO[Unit] =
    a =>
      ZIO.suspendSucceed(handler(a)).catchAllCause { cause =>
        cause.dieOption match {
          case Some(_) => ZIO.logErrorCause("event handler failed; subscription retained", cause)
          case None    => ZIO.refailCause(cause)
        }
      }

  private def subscribeToEvent[A](
    immediateCondition: ProviderStatus => Boolean,
    immediatePayload: => A,
    collect: PartialFunction[ProviderEvent, A],
    handler: A => UIO[Unit]
  ): UIO[UIO[Unit]] = {
    val safe = isolate(handler)
    for {
      cancel <- consumeEvents(_.collect(collect).foreach(safe))
      status <- providerStatus
      _      <- ZIO.when(immediateCondition(status))(detached(safe(immediatePayload)))
    } yield cancel
  }

  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get().render).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        _ == ProviderStatus.Ready,
        metadata,
        { case ProviderEvent.Ready(m, _) => m },
        handler
      )
    }

  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get().render).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
        (new RuntimeException("Provider in error state"), metadata),
        { case ProviderEvent.Error(error, m, _, _, _) => (error, m) },
        handler.tupled
      )
    }

  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get().render).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        _ == ProviderStatus.Stale,
        ("Provider in stale state", metadata),
        { case ProviderEvent.Stale(reason, m, _) => (reason, m) },
        handler.tupled
      )
    }

  def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = {
    // Configuration changed doesn't have an "associated state" so no immediate execution needed.
    val safe = isolate[(Set[String], ProviderMetadata)] { case (flags, m) => handler(flags, m) }
    consumeEvents(_.collect { case ProviderEvent.ConfigurationChanged(flags, m, _) => (flags, m) }.foreach(safe))
  }

  // The generic `on` delivers the ORIGINAL event from the stream (preserving every payload field — errorCode,
  // errorMessage, eventMetadata, ...), not a narrowed reconstruction. Associated-state events (Ready/Error/Stale) still
  // fire immediately when the provider is already in that state (spec 5.3.3), using a synthetic event that reflects the
  // current state (there is no original event to replay for an already-reached state). All invocations are isolated.
  // Suspended so the provider name is read when the registration runs, not when the effect is built.
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.suspendSucceed {
      val metadata = ProviderMetadata(providerNameRef.get().render)
      eventType match {
        case ProviderEventType.Ready =>
          subscribeToEvent[ProviderEvent](
            _ == ProviderStatus.Ready,
            ProviderEvent.Ready(metadata),
            { case e: ProviderEvent.Ready => e },
            handler
          )
        case ProviderEventType.Error =>
          subscribeToEvent[ProviderEvent](
            s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
            ProviderEvent.Error(new RuntimeException("Provider in error state"), metadata),
            { case e: ProviderEvent.Error => e },
            handler
          )
        case ProviderEventType.Stale =>
          subscribeToEvent[ProviderEvent](
            _ == ProviderStatus.Stale,
            ProviderEvent.Stale("Provider in stale state", metadata),
            { case e: ProviderEvent.Stale => e },
            handler
          )
        case ProviderEventType.ConfigurationChanged =>
          consumeEvents(_.filter(_.eventType == ProviderEventType.ConfigurationChanged).foreach(isolate(handler)))
        case ProviderEventType.Reconnecting =>
          consumeEvents(_.filter(_.eventType == ProviderEventType.Reconnecting).foreach(isolate(handler)))
      }
    }

  private def providerStatus: UIO[ProviderStatus] = state.statusRef.get
}

private[openfeature] object EventBridge {

  /** The status signal the SDK's state for the slot implies; none while it is `NOT_READY` or unknown. Also the
    * lifecycle replay's bridge, so the replay checks the mapping as written.
    */
  def signalForSlot(slot: JavaProviderState): Option[Signal] = slot match {
    case JavaProviderState.READY => Some(Signal.EventReady)
    case JavaProviderState.STALE => Some(Signal.EventStale)
    case JavaProviderState.ERROR => Some(Signal.EventError(false))
    case JavaProviderState.FATAL => Some(Signal.EventError(true))
    case _                       => None
  }

  /** Run an event-publish effect from a Java SDK thread. Failures are logged via the ZIO logger rather than thrown, so
    * the Java SDK's event dispatch thread is never killed by a defect.
    *
    * A cause that is only an interruption means the SDK is stopping its event executor (a pending interrupt on this
    * thread interrupts the fiber), which is routine at teardown: it is logged at DEBUG, and the thread's interrupt flag
    * is set again for the executor's stop to see. Every other cause is an ERROR.
    */
  private[internal] def runHandler(runtime: Runtime[Any], label: String)(effect: UIO[Unit]): Unit = {
    val reported                             = new java.util.concurrent.atomic.AtomicBoolean(false)
    val interrupted                          = new java.util.concurrent.atomic.AtomicBoolean(false)
    def report(c: Cause[Nothing]): UIO[Unit] =
      ZIO.succeed(reported.set(true)) *> {
        if (c.isInterruptedOnly) ZIO.succeed(interrupted.set(true)) *> ZIO.logDebugCause(s"event bridge: $label", c)
        else ZIO.logErrorCause(s"event bridge: $label", c)
      }
    Unsafe.unsafe { implicit u =>
      // A fiber interrupted from the outside skips its own recovery, so its failure is reported here instead.
      runtime.unsafe.run(effect.catchAllCause(report)) match {
        case Exit.Failure(c) if !reported.get() => runtime.unsafe.run(report(c)).getOrElse(_ => ())
        case _                                  => ()
      }
    }
    if (interrupted.get()) Thread.currentThread().interrupt()
  }
}
