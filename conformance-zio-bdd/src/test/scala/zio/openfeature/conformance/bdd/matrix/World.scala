package zio.openfeature.conformance.bdd.matrix

import zio.bdd.core.Default
import zio.openfeature.optimizely.matrix.RecommendationResult

/** Per-scenario state for the flag-matrix BDD suite. */
final case class World(
  lastResult: Option[RecommendationResult] = None
)

object World {
  given Default[World] = Default.from(World())
}
