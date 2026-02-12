package zio.openfeature

object Compat:
  type OrError[+E1, +E2] = E1 | E2
