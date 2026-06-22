package zio.openfeature.internal

import dev.openfeature.sdk.ProviderEvaluation

/** Builds [[ProviderEvaluation]] without fluently chaining off the Lombok-generated builder's self-type.
  *
  * Since SDK 1.21.0, `ProviderEvaluation.builder[T]()` returns `ProviderEvaluationBuilder[T, ?, ?]` (an F-bounded
  * self-type for SuperBuilder-style subclassing). Scala 2.13 cannot resolve a second fluent call's member lookup
  * against the captured existential (`scalac` reports "value reason is not a member of ?1"), even though Scala 3
  * handles it fine. Calling each setter on the same stable `builder` reference — discarding its return value, since
  * Lombok builders mutate in place and return `this` — sidesteps the existential chain entirely. Call sites with more
  * setters (e.g. `flagMetadata`, `errorCode`) that don't fit these two common shapes use the same stable-reference
  * pattern inline instead of a third helper overload.
  */
private[openfeature] object ProviderEvaluations {

  def of[T](value: T, reason: String): ProviderEvaluation[T] = {
    val builder = ProviderEvaluation.builder[T]()
    builder.value(value)
    builder.reason(reason)
    // build() returns the existentially-captured C (bounded by ProviderEvaluation[T]); widen explicitly since
    // that bound doesn't auto-widen across this method's declared return type.
    builder.build().asInstanceOf[ProviderEvaluation[T]]
  }

  def of[T](value: T, variant: String, reason: String): ProviderEvaluation[T] = {
    val builder = ProviderEvaluation.builder[T]()
    builder.value(value)
    builder.variant(variant)
    builder.reason(reason)
    // build() returns the existentially-captured C (bounded by ProviderEvaluation[T]); widen explicitly since
    // that bound doesn't auto-widen across this method's declared return type.
    builder.build().asInstanceOf[ProviderEvaluation[T]]
  }
}
