package zio.openfeature

import java.time.Instant
import zio.*

final case class FlagEvaluation[+A](
  key: String,
  value: A,
  resolution: FlagResolution[A],
  wasOverridden: Boolean,
  timestamp: Instant
):
  def wasEvaluated: Boolean = !wasOverridden

object FlagEvaluation:
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

final case class TransactionResult[+A](
  result: A,
  evaluatedFlags: Map[String, FlagEvaluation[?]],
  overriddenFlags: Set[String]
):
  def allFlagKeys: Set[String] = evaluatedFlags.keySet

  def providerEvaluatedKeys: Set[String] = evaluatedFlags.keySet -- overriddenFlags

  def flagCount: Int = evaluatedFlags.size

  def overrideCount: Int = overriddenFlags.size

  def getEvaluation(key: String): Option[FlagEvaluation[?]] = evaluatedFlags.get(key)

  def wasEvaluated(key: String): Boolean = evaluatedFlags.contains(key)

  def wasOverridden(key: String): Boolean = overriddenFlags.contains(key)

  def map[B](f: A => B): TransactionResult[B] =
    copy(result = f(result))

  def toValueMap: Map[String, Any] =
    evaluatedFlags.view.mapValues(_.value).toMap

object TransactionResult:
  def empty[A](result: A): TransactionResult[A] =
    TransactionResult(result, Map.empty, Set.empty)

final private[openfeature] case class TransactionState(
  overrides: Map[String, Any],
  evaluated: Ref[Map[String, FlagEvaluation[?]]],
  context: EvaluationContext,
  cacheEvaluations: Boolean
):
  def record[A](evaluation: FlagEvaluation[A]): UIO[Unit] =
    evaluated.update(_ + (evaluation.key -> evaluation))

  def getOverride(key: String): Option[Any] =
    overrides.get(key)

  def getCachedEvaluation(key: String): UIO[Option[FlagEvaluation[?]]] =
    if cacheEvaluations then evaluated.get.map(_.get(key))
    else ZIO.none

  def getEvaluations: UIO[Map[String, FlagEvaluation[?]]] =
    evaluated.get

  def toResult[A](result: A): UIO[TransactionResult[A]] =
    evaluated.get.map { evals =>
      TransactionResult(
        result = result,
        evaluatedFlags = evals,
        overriddenFlags = evals.filter(_._2.wasOverridden).keySet
      )
    }

private[openfeature] object TransactionState:
  def make(
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean = true
  ): UIO[TransactionState] =
    Ref.make(Map.empty[String, FlagEvaluation[?]]).map { evaluated =>
      TransactionState(overrides, evaluated, context, cacheEvaluations)
    }
