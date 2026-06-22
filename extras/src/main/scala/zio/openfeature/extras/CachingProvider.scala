package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ProviderEvent => JavaProviderEvent,
  EventProvider,
  EventProviderBridge,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.cache.{Cache, Lookup}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

/** Configuration for the caching provider.
  *
  * @param maxEntries
  *   Maximum number of cached entries before LRU eviction
  * @param ttl
  *   Time-to-live for cached entries
  * @param contextKeys
  *   If set, only these context attribute keys (plus the targeting key) are included in the cache key hash. Use this
  *   when contexts contain high-cardinality fields (e.g., per-request UUIDs) that would defeat caching. For example,
  *   `contextKeys = Some(Set("plan", "region"))` caches by plan and region, ignoring `userId`. If `None` (default), the
  *   full context is hashed.
  */
final case class CachingConfig(
  maxEntries: Int = 1000,
  ttl: Duration = 5.minutes,
  contextKeys: Option[Set[String]] = None
)

/** A cache key combining the flag key, evaluation type, and a fingerprint of the evaluation context. Uses a string
  * fingerprint instead of a hash to avoid collisions that could serve wrong flag values to different users.
  *
  * The key also carries the evaluation thunk for cache misses. zio-cache's `Lookup` is fixed at construction, so the
  * only race-free way to hand it the delegate call for *this* evaluation is through the key itself. The thunk is
  * deliberately excluded from `equals`/`hashCode` — logical identity is (flagKey, flagType, contextFingerprint), and
  * concurrent callers with the same logical key deduplicate onto whichever thunk triggers the lookup.
  */
final private[extras] class CacheKey(
  val flagKey: String,
  val flagType: String,
  val contextFingerprint: String,
  val evaluate: () => ProviderEvaluation[Any]
) {
  override def equals(other: Any): Boolean = other match {
    case that: CacheKey =>
      flagKey == that.flagKey && flagType == that.flagType && contextFingerprint == that.contextFingerprint
    case _ => false
  }
  override def hashCode: Int    = (flagKey, flagType, contextFingerprint).hashCode()
  override def toString: String = s"CacheKey($flagKey, $flagType, $contextFingerprint)"
}

/** A decorator provider that wraps any existing provider and adds evaluation caching backed by `zio-cache`.
  *
  * Benefits from `zio-cache`:
  *   - Concurrent lookup deduplication — if N fibers evaluate the same flag simultaneously, the underlying provider is
  *     called exactly once
  *   - TTL-based expiration
  *   - LRU eviction when max capacity is reached
  *
  * Cached evaluations return `CACHED` as the resolution reason.
  *
  * Events emitted by the wrapped provider are forwarded through this wrapper (so `FeatureFlags.events` subscribers
  * still see them), and a `PROVIDER_CONFIGURATION_CHANGED` emission automatically invalidates the cache. The wrapper
  * takes ownership of the delegate's event channel on `initialize`; do not register the same delegate instance directly
  * with an `OpenFeatureAPI` while it is wrapped.
  *
  * Failures are never served from cache: a delegate exception (and any evaluation that resolves with an error code)
  * invalidates its entry, so the next evaluation retries the delegate instead of replaying a transient error for the
  * remainder of the TTL.
  */
final class CachingProvider private (
  val underlying: EventProvider,
  val config: CachingConfig,
  private val cache: Cache[CacheKey, Throwable, ProviderEvaluation[Any]],
  private val runtime: Runtime[Any]
) extends EventProvider {

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = s"CachingProvider(${underlying.getMetadata.getName})"
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = underlying.getState

  private val delegateAttached = new AtomicBoolean(false)

  // Forward delegate emissions upward (the delegate is never registered with an API, so without this its
  // events go nowhere) and invalidate the cache on configuration changes so stale values aren't served
  // for the remainder of the TTL.
  private def onDelegateEvent(event: JavaProviderEvent, details: ProviderEventDetails): Unit = {
    if (event == JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED)
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(cache.invalidateAll).getOrThrowFiberFailure()
      }
    emit(event, details)
    ()
  }

  override def initialize(context: OFEvaluationContext): Unit = {
    if (delegateAttached.compareAndSet(false, true))
      EventProviderBridge.attach(underlying, onDelegateEvent)
    underlying.initialize(context)
  }

  override def shutdown(): Unit = {
    if (delegateAttached.compareAndSet(true, false))
      scala.util.Try(EventProviderBridge.detach(underlying))
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(cache.invalidateAll).getOrThrowFiberFailure()
    }
    underlying.shutdown()
  }

  // Length-prefix each field so user-supplied strings cannot synthesize separator collisions
  // (e.g., a value containing "|" or "," produces a different fingerprint than the corresponding split).
  private def lp(s: String): String = s"${s.length}:$s"

  private def contextFingerprint(ctx: OFEvaluationContext): String =
    if (ctx == null) "null"
    else {
      val tk = config.contextKeys match {
        case Some(_) => "" // targeting key is often high-cardinality; only include if in contextKeys
        case None    => Option(ctx.getTargetingKey).getOrElse("")
      }
      val entries: List[(String, AnyRef)] = (config.contextKeys, Option(ctx.asUnmodifiableMap())) match {
        case (Some(keys), Some(m)) => m.asScala.toList.filter { case (k, _) => keys.contains(k) }.sortBy(_._1)
        case (None, Some(m))       => m.asScala.toList.sortBy(_._1)
        case (_, None)             => Nil
      }
      val attrs = entries.map { case (k, v) => s"${lp(k)}=${lp(String.valueOf(v))}" }.mkString(",")
      s"${lp(tk)}|$attrs"
    }

  private def withCachedReason[A](eval: ProviderEvaluation[A]): ProviderEvaluation[A] = {
    // Builder calls are split across statements (not chained) because the SDK's SuperBuilder-style
    // self-type confuses Scala 2.13's existential resolution past the second fluent call.
    val builder = ProviderEvaluation.builder[A]()
    builder.value(eval.getValue)
    builder.variant(eval.getVariant)
    builder.reason("CACHED")
    builder.errorCode(eval.getErrorCode)
    builder.errorMessage(eval.getErrorMessage)
    builder.flagMetadata(eval.getFlagMetadata)
    builder.build().asInstanceOf[ProviderEvaluation[A]]
  }

  private def cached[A](
    key: String,
    flagType: String,
    context: OFEvaluationContext,
    evaluate: => ProviderEvaluation[A]
  ): ProviderEvaluation[A] = {
    val ck =
      new CacheKey(key, flagType, contextFingerprint(context), () => evaluate.asInstanceOf[ProviderEvaluation[Any]])
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(
          cache.contains(ck).flatMap { hit =>
            cache
              .get(ck)
              // zio-cache stores the completed lookup Exit, failures included. Without this invalidation a
              // single transient delegate exception would be replayed for the rest of the TTL.
              .onError(_ => cache.invalidate(ck))
              // Error *results* (errorCode set, no exception) are returned to this caller but not kept either:
              // serving an error from cache would delay recovery by up to the TTL.
              .tap(r => ZIO.when(r.getErrorCode != null)(cache.invalidate(ck)))
              .map(_.asInstanceOf[ProviderEvaluation[A]])
              .map(r => if (hit) withCachedReason(r) else r)
          }
        )
        .getOrThrowFiberFailure()
    }
  }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    cached(key, "boolean", context, underlying.getBooleanEvaluation(key, defaultValue, context))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    cached(key, "string", context, underlying.getStringEvaluation(key, defaultValue, context))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    cached(key, "integer", context, underlying.getIntegerEvaluation(key, defaultValue, context))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    cached(key, "double", context, underlying.getDoubleEvaluation(key, defaultValue, context))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    cached(key, "object", context, underlying.getObjectEvaluation(key, defaultValue, context))

  /** Invalidate all cached entries. Call this when the underlying provider's configuration changes. */
  def invalidateAll: UIO[Unit] = cache.invalidateAll
}

object CachingProvider {

  /** Create a CachingProvider wrapping the given provider.
    *
    * Uses `zio-cache` for concurrent lookup deduplication, TTL expiration, and LRU eviction.
    */
  def make(underlying: EventProvider, config: CachingConfig = CachingConfig()): UIO[CachingProvider] =
    for {
      rt <- ZIO.runtime[Any]
      cache <- Cache.make(
        config.maxEntries,
        config.ttl,
        // The key carries the delegate call for this evaluation; zio-cache deduplicates concurrent
        // lookups for the same logical key, so the delegate runs once per miss.
        Lookup[CacheKey, Any, Throwable, ProviderEvaluation[Any]](ck => ZIO.attemptBlocking(ck.evaluate()))
      )
    } yield new CachingProvider(underlying, config, cache, rt)

  /** Create a CachingProvider (convenience, requires ZIO runtime). */
  def apply(underlying: EventProvider, config: CachingConfig = CachingConfig()): CachingProvider = {
    val rt = Runtime.default
    Unsafe.unsafe { implicit u =>
      rt.unsafe.run(make(underlying, config)).getOrThrowFiberFailure()
    }
  }
}
