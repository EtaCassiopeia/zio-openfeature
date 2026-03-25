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

/** Configuration for the caching provider. */
final case class CachingConfig(
  maxEntries: Int = 1000,
  ttl: Duration = 5.minutes
)

/** A cache key combining the flag key, evaluation type, and a hash of the evaluation context. */
final private[extras] case class CacheKey(flagKey: String, flagType: String, contextHash: Int)

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

  private def contextHash(ctx: OFEvaluationContext): Int =
    if (ctx == null) 0
    else {
      val tk    = Option(ctx.getTargetingKey).getOrElse("")
      val attrs = Option(ctx.asUnmodifiableMap()).map(_.hashCode()).getOrElse(0)
      (tk, attrs).hashCode()
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
    val ck = CacheKey(key, flagType, contextHash(context))
    // Register the evaluation thunk before calling cache.get. On a cache miss,
    // the Lookup reads this thunk to compute the value. For the same CacheKey,
    // zio-cache deduplicates — only one Lookup runs regardless of how many
    // fibers request the same key concurrently.
    evaluators.put(ck, () => evaluate.asInstanceOf[ProviderEvaluation[Any]])
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
            val eval = evaluators.get(ck)
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
