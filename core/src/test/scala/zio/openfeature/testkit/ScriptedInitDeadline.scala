package zio.openfeature.testkit

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, TimeoutException}

import scala.jdk.CollectionConverters._

import dev.openfeature.sdk.FeatureProvider
import zio.Duration
import zio.openfeature.internal.{InitDeadline, ManagedProvider}

/** An [[InitDeadline]] a test fires: it arms no timer, and times a registration's wait out only when told to, so a swap
  * times out at the moment the test chooses rather than after a real `initTimeout`. Build the instance with a long
  * `initTimeout` too, since the wait for the SDK's READY write after `initialize()` returns keeps that bound on its own
  * clock.
  *
  * Each armed wait is keyed by the provider the test gave the library (the wrapper's delegate) and forgotten once it
  * completes, however it completes. A later registration of the same provider replaces the earlier one's entry.
  */
final private[openfeature] class ScriptedInitDeadline extends InitDeadline {

  private val waiting = new ConcurrentHashMap[FeatureProvider, CompletableFuture[Unit]]()

  def arm(outcome: CompletableFuture[Unit], provider: ManagedProvider, timeout: Duration): CompletableFuture[Unit] = {
    val key = provider.delegate
    waiting.put(key, outcome)
    outcome.whenComplete((_: Unit, _: Throwable) => waiting.remove(key, outcome): Unit): Unit
    // The outcome itself, not the stage whenComplete returns, which would wrap a failure in a CompletionException.
    outcome
  }

  /** The providers whose wait is armed and still open. */
  def armed: Set[FeatureProvider] = waiting.keySet.asScala.toSet

  /** Time `provider`'s open wait out, with the `TimeoutException` the default deadline would fail it with. False when
    * no wait is armed for it, or it completed first.
    */
  def fire(provider: FeatureProvider): Boolean =
    Option(waiting.get(provider)).exists(_.completeExceptionally(new TimeoutException("init deadline fired")))
}
