package zio.openfeature

import java.time.Instant
import zio._

final case class FlagEvaluation[+A](
  key: String,
  value: A,
  resolution: FlagResolution[A],
  wasOverridden: Boolean,
  timestamp: Instant
) {
  def wasEvaluated: Boolean = !wasOverridden
}

object FlagEvaluation {
  def evaluated[A](key: String, resolution: FlagResolution[A]): UIO[FlagEvaluation[A]] =
    Clock.instant.map { now =>
      FlagEvaluation(key, resolution.value, resolution, wasOverridden = false, now)
    }

  def overridden[A](key: String, value: A): UIO[FlagEvaluation[A]] =
    Clock.instant.map { now =>
      FlagEvaluation(
        key = key,
        value = value,
        resolution = FlagResolution.cached(key, value),
        wasOverridden = true,
        timestamp = now
      )
    }
}

final case class TransactionResult[+A](
  result: A,
  evaluatedFlags: Map[String, FlagEvaluation[_]],
  overriddenFlags: Set[String]
) {
  def allFlagKeys: Set[String] = evaluatedFlags.keySet

  def providerEvaluatedKeys: Set[String] = evaluatedFlags.keySet -- overriddenFlags

  def flagCount: Int = evaluatedFlags.size

  def overrideCount: Int = overriddenFlags.size

  def getEvaluation(key: String): Option[FlagEvaluation[_]] = evaluatedFlags.get(key)

  def wasEvaluated(key: String): Boolean = evaluatedFlags.contains(key)

  def wasOverridden(key: String): Boolean = overriddenFlags.contains(key)

  def map[B](f: A => B): TransactionResult[B] =
    copy(result = f(result))

  def toValueMap: Map[String, Any] =
    evaluatedFlags.view.mapValues(_.value).toMap
}

object TransactionResult {
  def empty[A](result: A): TransactionResult[A] =
    TransactionResult(result, Map.empty, Set.empty)
}

final private[openfeature] case class TransactionState(
  overrides: Map[String, Any],
  evaluated: Ref[Map[String, TransactionState.Cached]],
  context: EvaluationContext,
  cacheEvaluations: Boolean
) {

  /** Stores the evaluation together with `encode(evaluation.value)` — the value the provider would carry — so that a
    * re-read can run it back through `decode` exactly as it would a provider answer. Computed here, not by the caller,
    * so the cache cannot be handed a domain value by mistake.
    */
  def record[A](evaluation: FlagEvaluation[A])(implicit flagType: FlagType[A]): UIO[Unit] = {
    // Encoded outside the `update` closure: `Ref.update` may re-run its function on a lost CAS, and `encode` is user code.
    val wire = flagType.encode(evaluation.value)
    evaluated.update(_ + (evaluation.key -> TransactionState.Cached(evaluation, wire)))
  }

  def getOverride(key: String): Option[Any] =
    overrides.get(key)

  def getCachedEvaluation(key: String): UIO[Option[TransactionState.Cached]] =
    if (cacheEvaluations) evaluated.get.map(_.get(key))
    else ZIO.none

  def getEvaluations: UIO[Map[String, FlagEvaluation[_]]] =
    evaluated.get.map(_.map { case (k, c) => k -> c.evaluation })

  def toResult[A](result: A): UIO[TransactionResult[A]] =
    getEvaluations.map { evals =>
      TransactionResult(
        result = result,
        evaluatedFlags = evals,
        overriddenFlags = evals.filter(_._2.wasOverridden).keySet
      )
    }
}

private[openfeature] object TransactionState {

  /** A recorded evaluation plus the WIRE form of its value. The evaluation carries the domain value (what callers see
    * in `TransactionResult`); the wire form is what a same-key re-read decodes, because `FlagType.decode` is wire →
    * domain and feeding it the domain value only works for the built-ins, where the two coincide (#359).
    */
  final case class Cached(evaluation: FlagEvaluation[_], wire: Any)

  def make(
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean = true
  ): UIO[TransactionState] =
    Ref.make(Map.empty[String, Cached]).map { evaluated =>
      TransactionState(overrides, evaluated, context, cacheEvaluations)
    }
}
