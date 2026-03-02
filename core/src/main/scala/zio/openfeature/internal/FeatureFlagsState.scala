package zio.openfeature.internal

import zio._
import zio.openfeature._

final private[openfeature] case class FeatureFlagsState(
  globalContext: Ref[EvaluationContext],
  clientContext: Ref[EvaluationContext],
  fiberContext: FiberRef[EvaluationContext],
  transaction: FiberRef[Option[TransactionState]],
  hooks: Ref[List[FeatureHook]],
  eventHub: Hub[ProviderEvent],
  status: Ref[ProviderStatus]
)

private[openfeature] object FeatureFlagsState {
  def make(
    initialHooks: List[FeatureHook] = Nil,
    statusRef: Option[Ref[ProviderStatus]] = None
  ): URIO[Scope, FeatureFlagsState] =
    for {
      globalCtx   <- Ref.make(EvaluationContext.empty)
      clientCtx   <- Ref.make(EvaluationContext.empty)
      fiberCtx    <- FiberRef.make(EvaluationContext.empty)
      transaction <- FiberRef.make[Option[TransactionState]](None)
      hooks       <- Ref.make(initialHooks)
      eventHub    <- Hub.unbounded[ProviderEvent]
      status      <- statusRef.fold(Ref.make[ProviderStatus](ProviderStatus.Ready))(ZIO.succeed(_))
    } yield FeatureFlagsState(globalCtx, clientCtx, fiberCtx, transaction, hooks, eventHub, status)
}
