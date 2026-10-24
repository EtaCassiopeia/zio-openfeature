package zio.openfeature

/** Marks the built-in `FlagType[Option[A]]`, so evaluation can recognise it on the scalar path (#439).
  *
  * `Option[A]` over a scalar `A` resolves through `A`'s resolver, but a `None` default has no scalar form and `decode`
  * never sees the resolution's reason. Evaluation therefore sends `underlying.encode(underlying.defaultValue)` as a
  * sentinel default and reads the result back as `None` when the provider fell back to it.
  */
private[openfeature] trait OptionalFlagType[A] {
  def underlying: FlagType[A]
}
