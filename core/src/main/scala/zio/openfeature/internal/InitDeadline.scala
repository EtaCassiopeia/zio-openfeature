package zio.openfeature.internal

import java.util.concurrent.{CompletableFuture, TimeUnit}

import zio.Duration

/** Bounds a registration's wait for its provider's `initialize()` (see [[ProviderRegistration.Registration.settle]]).
  *
  * The default is a JDK timer on the outcome future, which no `TestClock` can fire, so a test that needs a swap to time
  * out at a moment of its choosing passes its own: one that completes the outcome with a `TimeoutException` when told
  * to, as the timer would. Only this wait is bounded here: the wait for the SDK's READY write after `initialize()`
  * returns keeps the same `initTimeout` on `System.nanoTime`, so a test that fires the deadline keeps that bound long.
  * Only [[FeatureFlagsFactories.build]] takes one; the async and registry builds, and their swaps, use the default.
  */
private[openfeature] trait InitDeadline {

  /** Bound `outcome`, the wait for `provider`'s `initialize()`, by `timeout`: the returned future fails with a
    * `java.util.concurrent.TimeoutException` once the bound passes before `outcome` completes.
    */
  def arm(outcome: CompletableFuture[Unit], provider: ManagedProvider, timeout: Duration): CompletableFuture[Unit]
}

private[openfeature] object InitDeadline {

  /** A JDK timer: `outcome.orTimeout(timeout)`. */
  val default: InitDeadline = new InitDeadline {
    def arm(outcome: CompletableFuture[Unit], provider: ManagedProvider, timeout: Duration): CompletableFuture[Unit] =
      outcome.orTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
  }
}
