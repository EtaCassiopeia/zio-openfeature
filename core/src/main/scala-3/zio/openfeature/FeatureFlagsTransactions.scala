package zio.openfeature

import zio.*
import zio.openfeature.FeatureFlagError.TransactionError

/** `transaction` for Scala 3, where its error channel is the union `E | TransactionError`. Scala 2.13 has no union
  * types, so there the method does not exist: use `transactionEither`.
  */
trait FeatureFlagsTransactions:
  self: FeatureFlags =>

  /** [[transactionEither]] with the source tag dropped: the caller's own `E` from `zio`, or the
    * [[FeatureFlagError.TransactionError]] raised by the transaction itself.
    */
  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R, E | TransactionError, TransactionResult[A]] =
    transactionEither(overrides, context, cacheEvaluations, nested)(zio)
      .mapError(_.fold[E | TransactionError](identity, identity))

private[openfeature] trait FeatureFlagsTransactionAccessors:

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true,
    nested: NestedPolicy = NestedPolicy.Fail
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, E | TransactionError, TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transaction(overrides, context, cacheEvaluations, nested)(zio))
