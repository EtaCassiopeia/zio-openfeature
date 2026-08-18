package zio.openfeature

/** What `transaction` / `transactionEither` do when called from inside an enclosing transaction on the same fiber
  * (or a fiber forked from it, since the transaction is fiber-local and inherited).
  *
  *   - [[NestedPolicy.Fail]] — the default, and the only behaviour before this type existed: the inner call fails
  *     with `NestedTransactionNotAllowed` before its body runs. Right for code that wants to *know* it is nested.
  *   - [[NestedPolicy.Reuse]] — the outermost transaction wins: the inner call runs its body inside the enclosing
  *     transaction and returns a `TransactionResult` reflecting that transaction as of the body's completion. This is
  *     what makes a per-request transaction safe as middleware: a handler that wraps a sub-operation in its own
  *     transaction no longer fails the request, and no wrapper needs to hand-roll an `inTransaction` guard.
  *
  * '''Under `Reuse`, the inner call's `overrides`, `context` and `cacheEvaluations` are ignored''' — the enclosing
  * transaction is the one running, and it was configured by whoever opened it. That is the one surprising part of the
  * design, so it is stated here, in the method scaladoc, and in the docs; a caller who needs its own overrides while
  * nested wants `Fail` and a redesign, not `Reuse`.
  *
  * When there is no enclosing transaction the policy is irrelevant: either value opens a fresh transaction exactly
  * as before.
  */
sealed trait NestedPolicy extends Product with Serializable

object NestedPolicy {
  case object Fail  extends NestedPolicy
  case object Reuse extends NestedPolicy
}
