package zio.openfeature

@deprecated("Moved to zio.openfeature.internal.Compat", "1.3.0")
object Compat {
  type OrError[+E1, +E2] = internal.Compat.OrError[E1, E2]

  def merge[E1, E2](e: Either[E1, E2]): OrError[E1, E2] = internal.Compat.merge(e)
}
