package zio.openfeature

import zio.Duration

/** Per-evaluation timeout selection.
  *
  *   - [[EvaluationTimeout.Default]] — use the global evaluation timeout configured on the `FeatureFlags` instance
  *     (1 second unless overridden at the factory).
  *   - [[EvaluationTimeout.Disabled]] — no timeout for this evaluation; the timeout scaffolding (a per-call fiber +
  *     timer race) is skipped entirely, which matters for in-memory/local providers whose evaluation is microseconds.
  *   - [[EvaluationTimeout.After]] — bound this evaluation at the given duration.
  */
sealed trait EvaluationTimeout extends Product with Serializable

object EvaluationTimeout {
  case object Default                         extends EvaluationTimeout
  case object Disabled                        extends EvaluationTimeout
  final case class After(duration: Duration)  extends EvaluationTimeout
}
