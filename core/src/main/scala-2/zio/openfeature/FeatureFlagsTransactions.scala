package zio.openfeature

// Scala 2.13 has no union types, so `transaction` does not exist here: use `transactionEither`.
trait FeatureFlagsTransactions

private[openfeature] trait FeatureFlagsTransactionAccessors
