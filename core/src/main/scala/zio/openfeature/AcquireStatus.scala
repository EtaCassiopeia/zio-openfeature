package zio.openfeature

import zio._
import zio.stream.{SubscriptionRef, ZStream}

/** Whether a [[FeatureFlags.fromAcquireAsync]] instance is serving its fallback or its real provider — the question a
  * readiness probe asks and `providerStatus` cannot answer (it reads `Ready` from time zero, and dips through
  * `NotReady` during the swap itself).
  *
  * Provided alongside `FeatureFlags` by that one factory only: `fromAcquireAsync` returns `URLayer[Scope, FeatureFlags
  * with AcquireStatus]`. The concept is meaningless on instances built any other way, so it is a separate service
  * rather than a member of the core trait.
  */
trait AcquireStatus {

  /** The current [[AcquireState]]. */
  def get: UIO[AcquireState]

  /** The current state first, then every transition — so a late subscriber is never left waiting for a change that
    * already happened. There is at most one transition after `Constructing`, and it is guaranteed while the layer scope
    * is open (layer release interrupts construction and leaves `Constructing` in place). `changes.filter(_ !=
    * Constructing).runHead` waits for the outcome, either way.
    *
    * This reports the outcome of *this factory's construction*, not continuous health: `Live` never retracts if the
    * real provider later degrades (the first-successful chain then serves fallback values), and a later `setProvider`
    * is invisible to it. Post-swap health is `extras.CircuitBreakerProvider`'s job.
    */
  def changes: ZStream[Any, Nothing, AcquireState]
}

/** The `cause` of [[AcquireState.Failed]] (and the argument to `onConstructionError`) when the real provider was
  * acquired and verified but the swap itself failed and rolled back — carries the structured error rather than
  * flattening it into a message.
  */
final case class ProviderSwapFailed(error: FeatureFlagError) extends RuntimeException(s"Provider swap failed: $error")

object AcquireStatus {

  def get: URIO[AcquireStatus, AcquireState] =
    ZIO.serviceWithZIO(_.get)

  def changes: ZStream[AcquireStatus, Nothing, AcquireState] =
    ZStream.serviceWithStream(_.changes)

  private[openfeature] def fromRef(ref: SubscriptionRef[AcquireState]): AcquireStatus =
    new AcquireStatus {
      def get: UIO[AcquireState]                       = ref.get
      def changes: ZStream[Any, Nothing, AcquireState] = ref.changes
    }
}
