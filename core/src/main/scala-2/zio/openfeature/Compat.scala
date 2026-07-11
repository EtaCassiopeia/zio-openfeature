package zio.openfeature

object Compat {
  type OrError[+E1, +E2] = Any

  /** Collapse a source-tagged `Either[E1, E2]` back into the [[OrError]] channel. Scala 2.13 has no union types, so
    * [[OrError]] erases to `Any` and the tag is dropped, matching the legacy `transaction` error channel. The typed
    * `Either` remains available through `transactionEither`.
    */
  def merge[E1, E2](e: Either[E1, E2]): OrError[E1, E2] = e.fold(l => l, r => r)
}
