package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.cache.{Cache, Lookup}
import java.util.concurrent.ConcurrentHashMap
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
  */
final private[extras] case class CacheKey(flagKey: String, flagType: String, contextFingerprint: String)

/** A decorator provider that wraps any existing provider and adds evaluation caching backed by `zio-cache`.
  *
  * Benefits from `zio-cache`:
  *   - Concurrent lookup deduplication — if N fibers evaluate the same flag simultaneously, the underlying provider is
  *     called exactly once
  *   - TTL-based expiration
  *   - LRU eviction when max capacity is reached
  *
  * Cached evaluations return `CACHED` as the resolution reason. Call `invalidateAll` when receiving
  * `ConfigurationChanged` events from the underlying provider.
  */
final class CachingProvider private (
  val underlying: EventProvider,
  val config: CachingConfig,
  private val cache: Cache[CacheKey, Throwable, ProviderEvaluation[Any]],
  private val evaluators: ConcurrentHashMap[CacheKey, () => ProviderEvaluation[Any]],
  private val runtime: Runtime[Any]
) extends EventProvider {

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = s"CachingProvider(${underlying.getMetadata.getName})"
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = underlying.getState

  override def initialize(context: OFEvaluationContext): Unit =
    underlying.initialize(context)

  override def shutdown(): Unit = {
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(cache.invalidateAll).getOrThrowFiberFailure()
    }
    evaluators.clear()
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

  private def withCachedReason[A](eval: ProviderEvaluation[A]): ProviderEvaluation[A] =
    ProviderEvaluation
      .builder[A]()
      .value(eval.getValue)
      .variant(eval.getVariant)
      .reason("CACHED")
      .errorCode(eval.getErrorCode)
      .errorMessage(eval.getErrorMessage)
      .flagMetadata(eval.getFlagMetadata)
      .build()

  private def cached[A](
    key: String,
    flagType: String,
    context: OFEvaluationContext,
    evaluate: => ProviderEvaluation[A]
  ): ProviderEvaluation[A] = {
    val ck = CacheKey(key, flagType, contextFingerprint(context))
    // Register the evaluation thunk before calling cache.get. On a cache miss, the Lookup reads this thunk.
    // zio-cache deduplicates concurrent Lookups for the same key, so only one Lookup runs. The try/finally
    // ensures the evaluators entry is always removed — on cache hit (Lookup never runs) and on miss alike.
    evaluators.put(ck, () => evaluate.asInstanceOf[ProviderEvaluation[Any]])
    try
      Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(
            cache.contains(ck).flatMap { hit =>
              cache
                .get(ck)
                .map(_.asInstanceOf[ProviderEvaluation[A]])
                .map(r => if (hit) withCachedReason(r) else r)
            }
          )
          .getOrThrowFiberFailure()
      }
    finally
      evaluators.remove(ck)
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
      evaluators = new ConcurrentHashMap[CacheKey, () => ProviderEvaluation[Any]]()
      cache <- Cache.make(
        config.maxEntries,
        config.ttl,
        Lookup[CacheKey, Any, Throwable, ProviderEvaluation[Any]] { ck =>
          ZIO.attempt {
            val eval = evaluators.remove(ck)
            if (eval == null) throw new IllegalStateException(s"No evaluator registered for $ck")
            eval()
          }
        }
      )
    } yield new CachingProvider(underlying, config, cache, evaluators, rt)

  /** Create a CachingProvider (convenience, requires ZIO runtime). */
  def apply(underlying: EventProvider, config: CachingConfig = CachingConfig()): CachingProvider = {
    val rt = Runtime.default
    Unsafe.unsafe { implicit u =>
      rt.unsafe.run(make(underlying, config)).getOrThrowFiberFailure()
    }
  }
}
