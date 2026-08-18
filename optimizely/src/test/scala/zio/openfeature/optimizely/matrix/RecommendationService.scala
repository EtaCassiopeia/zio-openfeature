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
  * NOTE: since #388 the typed tier (`boolean`/`string`/`int`) FAILS with a typed `FeatureFlagError` when the provider
  * reports an error code — `FlagNotFound` for an absent flag — so the decision that cares WHY (the kill switch: absent
  * vs. broken) reads the typed error, and the two that only want a value-or-default use the total tier.
  */
final class RecommendationService(flags: FeatureFlags) {

  private val defaultUserCtx = EvaluationContext("test-user")

  def recommend: UIO[String] =
    recommendWithContext(defaultUserCtx).map(_.kind)

  def recommendWithContext(userCtx: EvaluationContext): UIO[RecommendationResult] =
    for {
      killEnabled <- flags.boolean("recommendation_kill_switch", default = false, userCtx).either.map {
        case Right(enabled)                         => Some(enabled)
        case Left(FeatureFlagError.FlagNotFound(_)) => None        // absent → no opinion
        case Left(_)                                => Some(false) // other error → degrade
      }
      result <- killEnabled match {
        case Some(false) =>
          ZIO.succeed(RecommendationResult(kind = "degraded", rateLimit = 0))
        case _ =>
          for {
            // Absent or errored → "default"; the total tier serves exactly that.
            variant <- flags.stringOrDefault("recommendation_variant", default = "default", userCtx)
            // Audience-gated; absent (audience miss) or errored → the conservative 10.
            rateLimit <- flags.intOrDefault("recommendation_rate_limit", default = 10, userCtx)
          } yield RecommendationResult(kind = variant, rateLimit = rateLimit)
      }
    } yield result
}

final case class RecommendationResult(kind: String, rateLimit: Int)
