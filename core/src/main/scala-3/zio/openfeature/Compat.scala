package zio.openfeature

object Compat:
  type OrError[+E1, +E2] = E1 | E2

  /** Collapse a source-tagged `Either[E1, E2]` back into the [[OrError]] channel. On Scala 3 that is the union `E1 |
    * E2`, so the tag is dropped and the raw error value flows through unchanged.
    */
  def merge[E1, E2](e: Either[E1, E2]): OrError[E1, E2] = e.fold(l => l, r => r)
