package zio.openfeature.internal

import zio._
import zio.openfeature.FeatureFlagsLive
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}
import scala.jdk.CollectionConverters._

/** Every instance built on each `OpenFeatureAPI`, so the one that tears an API down can retire the others: their
  * provider goes with the API, and the SDK's fresh registry would otherwise answer them with its No-op provider while
  * they still report `Ready`.
  *
  * Weak both ways, compared by identity (neither type overrides `equals`). An instance holds its API, so a strong value
  * would keep every API, and every instance built on one, for the life of the JVM; a weak one keeps neither. An
  * instance that is gone cannot be retired, and retiring one whose scope already closed only repeats its teardown.
  */
private[openfeature] object ApiInstances {

  private val instances =
    new java.util.WeakHashMap[OpenFeatureAPI, java.util.WeakHashMap[FeatureFlagsLive, java.lang.Boolean]]()

  def register(api: OpenFeatureAPI, ff: FeatureFlagsLive): Unit = synchronized {
    val onApi = Option(instances.get(api)).getOrElse {
      val fresh = new java.util.WeakHashMap[FeatureFlagsLive, java.lang.Boolean]()
      instances.put(api, fresh)
      fresh
    }
    val _ = onApi.put(ff, java.lang.Boolean.TRUE)
  }

  private def on(api: OpenFeatureAPI): List[FeatureFlagsLive] = synchronized {
    Option(instances.get(api)).fold(List.empty[FeatureFlagsLive])(_.keySet.asScala.toList)
  }

  /** Whether a live instance on `api` other than `self` is bound to `provider`'s registration: the SDK shares one state
    * manager between slots, so shutting it down would take it from that instance too. Asked of the instances because
    * the SDK does not list an API's slots. One that closed, that failed on this provider, or that was collected no
    * longer counts: its slot keeps the registration, which the API's shutdown reaches.
    */
  def heldByAnother(api: OpenFeatureAPI, self: FeatureFlagsLive, provider: FeatureProvider): Boolean =
    on(api).exists(instance => (instance ne self) && instance.serves(provider))

  /** Retire every instance on `api` but `except` (the one tearing it down, or `null` before it exists). */
  def retireSiblings(api: OpenFeatureAPI, except: FeatureFlagsLive): UIO[Unit] =
    ZIO.succeed(on(api).filterNot(_ eq except)).flatMap(ZIO.foreachParDiscard(_)(_.retire))

  /** Retire every instance on `api`: its owner (a registry) is tearing it down. */
  def retireAll(api: OpenFeatureAPI): UIO[Unit] =
    retireSiblings(api, except = null)
}
