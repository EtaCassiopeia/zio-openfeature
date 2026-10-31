package zio.openfeature

import zio._
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** Administering a [[FeatureFlags]] instance: what can change what a sibling instance or a later reader observes — the
  * provider, the API's hooks, the event handlers, and the instance's lifetime. Reading flags, tracking and observing
  * the status stay on [[FeatureFlags]].
  *
  * A program states what it needs: `ZIO[FeatureFlags, ...]` reads flags and cannot swap the provider or shut the
  * instance down. The factories' layers provide [[FeatureFlags]]; add the administration of that instance with
  * [[FeatureFlagsAdmin.layer]]: `FeatureFlags.fromProvider(p) >+> FeatureFlagsAdmin.layer`. A client from
  * [[FeatureFlagRegistry]] is administered through the registry instead.
  */
trait FeatureFlagsAdmin {

  // Event Handlers - return a cancellation effect
  //
  // Delivery semantics: the event subscription is established before the registration effect returns, so no event
  // published afterwards is lost. Handlers with an "associated state" (ready/error/stale) also run immediately when
  // the provider is already in that state (spec 5.3.3); an event arriving during registration may therefore invoke
  // the handler twice — delivery is at-least-once and handlers should be idempotent.

  /** Register a handler for provider ready events. Returns a cancellation effect.
    *
    * The cancellation effect must be run, or the handler fiber lives until the instance shuts down. To tie a handler to
    * a scope, wrap the registration: `ZIO.acquireRelease(admin.onProviderReady(h))(cancel => cancel)`. The same holds
    * for every `on*` method below.
    *
    * Per OpenFeature spec 5.2.1 and 5.2.7, handlers can be registered and removed. Delivery is at-least-once — see the
    * note on event handlers above.
    */
  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider error events. Returns a cancellation effect. */
  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider stale events. Returns a cancellation effect. */
  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for configuration changed events. Returns a cancellation effect. */
  def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for any provider event type. Returns a cancellation effect.
    *
    * This is a generic alternative to the specific event handler methods (onProviderReady, etc.).
    */
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a ZIO API-level hook (spec §4.4.1 level 1). Runs before client-level hooks on every evaluation. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
  def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearZioApiHooks: UIO[Unit]
  def zioApiHooks: UIO[List[FeatureHook]]

  /** Add an API-level hook that applies to all clients sharing this instance's OpenFeatureAPI.
    *
    * The hook lives on the API, not on this instance: every sibling instance on the same API (registry domains
    * included) runs it too. There is no per-instance removal; [[clearApiHooks]] is all-or-nothing.
    */
  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]

  /** Clear all API-level hooks on this instance's OpenFeatureAPI.
    *
    * The API is shared by every sibling instance, so this removes their API-level hooks as well as this instance's.
    * Called from a `Shared` instance (one that does not own the API) it reaches siblings the caller may not control,
    * and logs one warning saying so; an `Owned` instance logs nothing.
    */
  def clearApiHooks: UIO[Unit]

  /** Replace the underlying provider at runtime.
    *
    * The new provider is initialized — bounded by the layer's init timeout — and, once it is ready, the old provider is
    * shut down; the status transitions through `NotReady` during the swap and ends `Ready`, or `Stale` when the SDK
    * reports the new provider stale. Hooks, context, and event handlers are preserved. Evaluations that start during
    * the swap fail with `ProviderNotReady`.
    *
    * If the new provider's `initialize()` fails, the effect fails with `ProviderInitializationFailed` and the previous
    * provider is registered again. If it does not finish within the init timeout, the effect fails with
    * `ProviderInitializationFailed` wrapping a `TimeoutException` and status becomes `Error`, but the swap is not
    * rolled back: the new provider stays registered (status recovers to `Ready` if it finishes initializing later), and
    * the previous provider is not restored, because the SDK would shut it down if the new one later became ready. Call
    * `setProvider` again with another provider to recover. If the new provider fails to initialize later instead, or
    * has not finished when this instance shuts down or its scope closes, an instance that owns its API shuts the
    * previous provider down itself, since the SDK no longer holds it. Until the new provider has settled, installing
    * the previous provider again fails with `ProviderInitializationFailed`.
    *
    * On an instance that has been shut down, the effect fails with `ProviderNotReady(NotReady)` and the provider is not
    * touched: a shut-down instance is never revived.
    *
    * Once the swap has started it runs to one of those outcomes even if the calling fiber is interrupted, so
    * `setProvider(p).timeout(d)` with `d` shorter than the init timeout returns only when the swap has settled (at most
    * about twice the init timeout). Waiting for an earlier swap to finish can still be interrupted.
    */
  def setProvider(provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  /** Shut down this instance (spec 1.6.1, 1.6.2).
    *
    * The status transitions to `ShuttingDown` for the duration of the teardown (evaluations started in that window fail
    * with `ProviderNotReady(ShuttingDown)`) and ends at `NotReady`. Client-level and ZIO API-level hooks, the global
    * and client contexts, and the tracked-events recorder are cleared, and the event hub is shut down.
    *
    * A shutdown called while a [[setProvider]] swap is in flight, or queued, waits for those swaps to settle (each is
    * bounded by the init timeout) before it starts, so the final `NotReady` is never overwritten by a swap's outcome.
    * Waiting for an earlier swap to finish can be interrupted, which cancels the shutdown before it has changed
    * anything; once it starts, it runs to completion.
    *
    * Provider/API teardown depends on ownership. An instance created by a sole-owner factory (e.g. `fromProvider` /
    * `fromProviderAsync`) shuts down the underlying OpenFeature API, and with it its provider. An instance that shares
    * its API with siblings — a client obtained from `FeatureFlagRegistry`, or any domain-scoped client — leaves the
    * shared API and its provider untouched (both belong to whatever owns the API, which tears every provider down once
    * when its own scope closes), releasing only this instance's own state; such clients should be retired via whatever
    * owns the API's lifecycle (e.g. the registry). Note that a domain-scoped client created directly on the
    * process-global API (e.g. `fromProviderWithDomain`) has no such owner: neither `shutdown` nor scope close reclaims
    * its provider, so for a resource-heavy provider prefer a sole-owner factory (or the registry), or shut the global
    * API down yourself. Fiber-local context and any in-flight transaction state are fiber-scoped and unaffected.
    */
  def shutdown: UIO[Unit]
}

object FeatureFlagsAdmin {

  /** The administration of the [[FeatureFlags]] in the environment, the same instance:
    * `FeatureFlags.fromProvider(p) >+> FeatureFlagsAdmin.layer` provides both. Building it does nothing to the
    * instance.
    */
  val layer: URLayer[FeatureFlags, FeatureFlagsAdmin] = ZLayer.fromZIO(ZIO.serviceWith[FeatureFlags](of))

  /** The instance itself when it is one (every instance this library builds is), or an adapter over the members
    * [[FeatureFlags]] still carries for 1.x, for another implementor.
    */
  private[openfeature] def of(ff: FeatureFlags): FeatureFlagsAdmin = ff match {
    case admin: FeatureFlagsAdmin => admin
    case other                    => new Delegating(other)
  }

  // Calls the members that moved here, which `FeatureFlags` keeps, deprecated, for 1.x.
  @scala.annotation.nowarn("cat=deprecation")
  final private class Delegating(ff: FeatureFlags) extends FeatureFlagsAdmin {
    def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] = ff.onProviderReady(handler)
    def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
      ff.onProviderError(handler)
    def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = ff.onProviderStale(handler)
    def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
      ff.onConfigurationChanged(handler)
    def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] =
      ff.on(eventType, handler)
    def addZioApiHook(hook: FeatureHook): UIO[Unit]                   = ff.addZioApiHook(hook)
    def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]           = ff.addZioApiHooks(hooks)
    def clearZioApiHooks: UIO[Unit]                                   = ff.clearZioApiHooks
    def zioApiHooks: UIO[List[FeatureHook]]                           = ff.zioApiHooks
    def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]      = ff.addApiHook(hook)
    def clearApiHooks: UIO[Unit]                                      = ff.clearApiHooks
    def setProvider(provider: OFFeatureProvider): IO[FeatureFlagError, Unit] = ff.setProvider(provider)
    def shutdown: UIO[Unit]                                           = ff.shutdown
  }

  // Service accessors

  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): URIO[FeatureFlagsAdmin, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderReady(handler))

  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): URIO[FeatureFlagsAdmin, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderError(handler))

  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): URIO[FeatureFlagsAdmin, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderStale(handler))

  def onConfigurationChanged(
    handler: (Set[String], ProviderMetadata) => UIO[Unit]
  ): URIO[FeatureFlagsAdmin, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onConfigurationChanged(handler))

  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): URIO[FeatureFlagsAdmin, UIO[Unit]] =
    ZIO.serviceWithZIO(_.on(eventType, handler))

  def addZioApiHook(hook: FeatureHook): URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHook(hook))

  def addZioApiHooks(hooks: List[FeatureHook]): URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHooks(hooks))

  def clearZioApiHooks: URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.clearZioApiHooks)

  def zioApiHooks: URIO[FeatureFlagsAdmin, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.zioApiHooks)

  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.addApiHook(hook))

  def clearApiHooks: URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.clearApiHooks)

  def setProvider(provider: OFFeatureProvider): ZIO[FeatureFlagsAdmin, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(provider))

  def shutdown: URIO[FeatureFlagsAdmin, Unit] =
    ZIO.serviceWithZIO(_.shutdown)
}
