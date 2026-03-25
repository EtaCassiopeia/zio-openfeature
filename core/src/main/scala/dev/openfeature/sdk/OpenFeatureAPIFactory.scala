package dev.openfeature.sdk

/** Factory that creates isolated OpenFeatureAPI instances.
  *
  * The Java SDK's `OpenFeatureAPI` uses a singleton pattern via `getInstance()`, but its constructor is `protected`.
  * This factory lives in the `dev.openfeature.sdk` package to access that constructor, enabling per-test isolation.
  *
  * All meaningful state (provider repository, event support, hooks) is instance-scoped in the Java SDK — only a static
  * read-write lock is shared, which serializes provider registration but doesn't affect correctness.
  */
object OpenFeatureAPIFactory {

  /** Create a fresh, isolated OpenFeatureAPI instance with its own provider repository and event support. */
  def create(): OpenFeatureAPI = new OpenFeatureAPI()
}
