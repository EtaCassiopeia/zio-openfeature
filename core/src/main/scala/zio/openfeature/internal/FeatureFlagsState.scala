package zio.openfeature.internal

import zio._
import zio.stream.SubscriptionRef
import zio.openfeature._

final case class FeatureFlagsState(
  globalContextRef: Ref[EvaluationContext],
  clientContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext],
  transactionRef: FiberRef[Option[TransactionState]],
  zioApiHooksRef: Ref[List[FeatureHook]],
  hooksRef: Ref[List[FeatureHook]],
  eventHub: Hub[ProviderEvent],
  // SubscriptionRef (not plain Ref) so status transitions are observable as a stream: every writer (event bridge,
  // setProvider, watchdog, shutdown) already goes through this ref, so `.changes` sees all of them with no extra
  // wiring. `awaitReady` consumes `.changes`; the ref still behaves as a Ref for all existing set/get/modify callers.
  statusRef: SubscriptionRef[ProviderStatus],
  trackRecorder: Ref[Chunk[(String, EvaluationContext, Option[TrackingEventDetails])]]
)

object FeatureFlagsState {

  /** Maximum number of tracking events retained for `trackedEvents`. The recorder exists for test/debug introspection;
    * bounding it keeps long-running production apps that call `track` per request from accumulating events (and their
    * merged contexts) without limit. When full, the oldest events are dropped.
    */
  val MaxTrackedEvents: Int = 1000

  def make: URIO[Scope, FeatureFlagsState] =
    for {
      globalCtxRef   <- Ref.make(EvaluationContext.empty)
      clientCtxRef   <- Ref.make(EvaluationContext.empty)
      fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
      transactionRef <- FiberRef.make[Option[TransactionState]](None)
      zioApiHooksRef <- Ref.make(List.empty[FeatureHook])
      hooksRef       <- Ref.make(List.empty[FeatureHook])
      // Sliding (not dropping): on overflow the OLDEST event is discarded, so the newest is always delivered.
      // `ConfigurationChanged.changedFlags` isn't reconstructible from status, and a re-reading consumer only needs
      // the latest change; dropping the *newest* on a burst (as `Hub.dropping` does) could hide the final change.
      eventHub  <- Hub.sliding[ProviderEvent](256)
      statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
      trackRec  <- Ref.make(Chunk.empty[(String, EvaluationContext, Option[TrackingEventDetails])])
    } yield FeatureFlagsState(
      globalCtxRef,
      clientCtxRef,
      fiberCtxRef,
      transactionRef,
      zioApiHooksRef,
      hooksRef,
      eventHub,
      statusRef,
      trackRec
    )
}
