package zio.openfeature.conformance.bdd

import zio._
import zio.openfeature._
import zio.openfeature.testkit.TestFeatureProvider
import zio.bdd.core.Default
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext}

/** Per-scenario state for the zio-bdd conformance suite. zio-bdd isolates this via a `FiberRef`, so it can hold opaque
  * references (the live `FeatureFlags`, recorder `Ref`s) that have no schema — it only needs a [[Default]] instance.
  */
final case class World(
  flags: Option[FeatureFlags] = None,
  testProvider: Option[TestFeatureProvider] = None,
  // evaluation inputs
  flagKey: String = "",
  flagType: String = "",
  defaultRaw: String = "",
  ctx: EvaluationContext = EvaluationContext.empty,
  // captured result
  resultValue: Any = (),
  resultReason: String = "",
  resultErrorCode: Option[String] = None,
  resultVariant: Option[String] = None,
  resultMetadata: FlagMetadata = FlagMetadata.empty,
  resultFlagKey: String = "",
  // hooks
  hookStages: Option[Ref[Chunk[String]]] = None,
  hookDetails: Option[Ref[Option[FlagResolution[Any]]]] = None,
  // context merging
  apiCtx: EvaluationContext = EvaluationContext.empty,
  clientCtx: EvaluationContext = EvaluationContext.empty,
  invocationCtx: EvaluationContext = EvaluationContext.empty,
  txCtx: Option[EvaluationContext] = None,
  beforeHookCtx: Option[EvaluationContext] = None,
  levelOrder: List[String] = Nil,
  mergedCtx: Option[OFEvaluationContext] = None
)

object World {
  given Default[World] = new Default[World] { def default: World = World() }
}
