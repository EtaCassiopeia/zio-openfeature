package zio.openfeature.optimizely.matrix

import zio._
import zio.openfeature._

/** Toy system-under-test: reads feature flags and produces a recommendation response.
  *
  * `recommend` (original, single-user context): reads two flags and returns a kind string.
  *
  * `recommendWithContext` (extended): reads three flags against a caller-supplied user context, returning a
  * [[RecommendationResult]] that also carries a rate-limit integer variable. The third flag,
  * `recommendation_rate_limit`, is audience-gated — only users with `plan = "premium"` receive the elevated limit;
  * everyone else gets the default.
  *
  * Decision logic (shared across both methods):
  *   - `recommendation_kill_switch` boolean — FlagNotFound → no opinion; any other error → degraded.
  *   - `recommendation_variant` string — FlagNotFound or error → "default".
  *   - Kill-switch explicitly off (enabled=false) → degraded, rateLimit = 0.
  *   - Kill-switch absent or on → serve variant (or "default" if also absent).
  *   - `recommendation_rate_limit` integer variable — audience-gated; FlagNotFound → 10 (conservative default).
  *
  * NOTE: The OpenFeature SDK + Optimizely provider surface FLAG_NOT_FOUND as a *successful* `FlagResolution` carrying
  * `errorCode = Some(ErrorCode.FlagNotFound)` with the default value, not as a ZIO failure. We therefore use `*Details`
  * methods and inspect `resolution.errorCode`.
  */
final class RecommendationService(flags: FeatureFlags) {

  private val defaultUserCtx = EvaluationContext("test-user")

  def recommend: UIO[String] =
    recommendWithContext(defaultUserCtx).map(_.kind)

  def recommendWithContext(userCtx: EvaluationContext): UIO[RecommendationResult] =
    for {
      killResolution <- flags
        .booleanDetails("recommendation_kill_switch", default = false, userCtx)
        .catchAll(_ =>
          ZIO.succeed(
            FlagResolution.error("recommendation_kill_switch", false, ErrorCode.General, "provider error")
          )
        )
      killEnabled = killResolution.errorCode match {
        case Some(ErrorCode.FlagNotFound) => None        // absent → no opinion
        case Some(_)                      => Some(false) // other error → degrade
        case None                         => Some(killResolution.value)
      }
      result <- killEnabled match {
        case Some(false) =>
          ZIO.succeed(RecommendationResult(kind = "degraded", rateLimit = 0))
        case _ =>
          for {
            variantResult <- flags
              .stringDetails("recommendation_variant", default = "__absent__", userCtx)
              .map { r =>
                if (r.errorCode.contains(ErrorCode.FlagNotFound)) "default"
                else if (r.value == "__absent__") "default"
                else r.value
              }
              .catchAll(_ => ZIO.succeed("default"))
            rateLimitResult <- flags
              .intDetails("recommendation_rate_limit", default = 10, userCtx)
              .map { r =>
                if (r.errorCode.contains(ErrorCode.FlagNotFound)) 10
                else r.value
              }
              .catchAll(_ => ZIO.succeed(10))
          } yield RecommendationResult(kind = variantResult, rateLimit = rateLimitResult)
      }
    } yield result
}

final case class RecommendationResult(kind: String, rateLimit: Int)
