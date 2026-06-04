package zio.openfeature.internal

import zio._
import zio.openfeature._

final case class FeatureFlagsState(
  globalContextRef: Ref[EvaluationContext],
  clientContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext],
  transactionRef: FiberRef[Option[TransactionState]],
  zioApiHooksRef: Ref[List[FeatureHook]],
  hooksRef: Ref[List[FeatureHook]],
  eventHub: Hub[ProviderEvent],
  statusRef: Ref[ProviderStatus],
  trackRecorder: Ref[List[(String, EvaluationContext, Option[TrackingEventDetails])]]
)

object FeatureFlagsState {
  def make: URIO[Scope, FeatureFlagsState] =
    for {
      globalCtxRef   <- Ref.make(EvaluationContext.empty)
      clientCtxRef   <- Ref.make(EvaluationContext.empty)
      fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
      transactionRef <- FiberRef.make[Option[TransactionState]](None)
      zioApiHooksRef <- Ref.make(List.empty[FeatureHook])
      hooksRef       <- Ref.make(List.empty[FeatureHook])
      // Bounded; subscribers reconcile via current state on next evaluation, so dropping intermediate events is safe.
      eventHub  <- Hub.dropping[ProviderEvent](256)
      statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
      trackRec  <- Ref.make(List.empty[(String, EvaluationContext, Option[TrackingEventDetails])])
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
