package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ProviderEvent => JavaProviderEvent,
  EventProvider,
  EventProviderBridge,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails,
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
  * with an `OpenFeatureAPI` while it is wrapped. This requires an `EventProvider` delegate — see below.
  *
  * '''Plain providers''': the delegate is a `FeatureProvider`, so a provider that does not extend `EventProvider` can
  * be wrapped too (#382). Only the event integration needs the richer type; everything else — all six resolvers, both
  * `initialize` overloads, `isDomainScoped`, `getProviderHooks`, `track`, `shutdown`, `getMetadata` and `getState` — is
  * `FeatureProvider` surface and is forwarded unchanged. The delegate is held as given, with no adapter interposed.
  *
  * '''Know what you lose''': a plain delegate cannot emit `PROVIDER_CONFIGURATION_CHANGED`, so the automatic
  * invalidation above never fires for one. TTL expiry (and `shutdown`) become the only ways an entry is dropped — a
  * flag changed at the provider can therefore keep being served from cache for up to `ttl`. Size `ttl` for how stale
  * you can afford to be, rather than relying on the provider to tell you.
  *
  * Failures are never served from cache: a delegate exception (and any evaluation that resolves with an error code)
  * invalidates its entry, so the next evaluation retries the delegate instead of replaying a transient error for the
  * remainder of the TTL. `DEFAULT`-reason results are likewise not retained — a `DEFAULT` resolution echoes the
  * caller's own default value, which the cache key does not capture, so caching it would serve one call site's default
  * to another.
  *
  * Caveat: because concurrent lookups deduplicate by cache key (which excludes the default value), N simultaneous
  * callers evaluating the same absent flag with DIFFERENT defaults all await the single in-flight evaluation and so
  * share whichever default won the race. The non-retention above eliminates the sequential leak (across the TTL); the
  * concurrent-window sharing is inherent to dedup and cannot be prevented without keying on the default.
  */
final class CachingProvider private (
  val underlying: FeatureProvider,
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

  // Forward the delegate's provider hooks and tracking so wrapping a provider in caching doesn't silently drop its
  // telemetry/validation hooks or discard `track` events (spec: a decorator must not swallow these). See #261.
  override def getProviderHooks = underlying.getProviderHooks

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    underlying.track(eventName, context, details)

  private val delegateAttached = new AtomicBoolean(false)

  // Event integration is derived from the delegate's type rather than stored as a separate flag, so
  // "attached to something that cannot emit" is unrepresentable. Empty for a plain `FeatureProvider`.
  private val eventDelegate: Option[EventProvider] = underlying match {
    case ev: EventProvider => Some(ev)
    case _                 => None
  }

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

  private def attachDelegate(): Unit =
    eventDelegate.foreach { ev =>
      if (delegateAttached.compareAndSet(false, true)) EventProviderBridge.attach(ev, onDelegateEvent)
    }

  override def initialize(context: OFEvaluationContext): Unit = {
    attachDelegate()
    underlying.initialize(context)
  }

  override def initialize(context: OFEvaluationContext, domain: String): Unit = {
    attachDelegate()
    underlying.initialize(context, domain)
  }

  override def isDomainScoped(): Boolean = underlying.isDomainScoped()

  override def shutdown(): Unit = {
    eventDelegate.foreach { ev =>
      if (delegateAttached.compareAndSet(true, false)) scala.util.Try(EventProviderBridge.detach(ev))
    }
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

  // The OpenFeature `DEFAULT` reason string, compared against `ProviderEvaluation.getReason` (a raw String).
  private val DefaultReason: String = dev.openfeature.sdk.Reason.DEFAULT.toString

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
    // `ran` is flipped inside the lookup thunk, which zio-cache invokes ONLY on a miss (a hit returns the stored value
    // without calling it). Reading it after `get` tells us hit-vs-miss atomically — no separate `cache.contains` that
    // could disagree with `get` if the entry expires between the two, and one cache operation per evaluation instead of
    // two (#259).
    val ran = new AtomicBoolean(false)
    val ck = new CacheKey(
      key,
      flagType,
      contextFingerprint(context),
      () => { ran.set(true); evaluate.asInstanceOf[ProviderEvaluation[Any]] }
    )
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(
          cache
            .get(ck)
            // zio-cache stores the completed lookup Exit, failures included. Without this invalidation a
            // single transient delegate exception would be replayed for the rest of the TTL.
            .onError(_ => cache.invalidate(ck))
            // Don't retain error results (errorCode set) OR DEFAULT-reason results: a DEFAULT resolution means the flag
            // was absent and the delegate echoed THIS caller's default value, which the cache key does not include —
            // keeping it would serve one call site's default to another (under a CACHED label) for the whole TTL. Both
            // are still returned to this caller, but re-evaluated next time (#259).
            .tap(r => ZIO.when(r.getErrorCode != null || r.getReason == DefaultReason)(cache.invalidate(ck)))
            .map(_.asInstanceOf[ProviderEvaluation[A]])
            .map(r => if (ran.get()) r else withCachedReason(r))
        ) match {
        // Rethrow the original error as an `OpenFeatureError` (an `Exception`) rather than letting
        // `getOrThrowFiberFailure` raise a `zio.FiberFailure` (a `Throwable`) that would escape the SDK's
        // `catch (Exception)` and be thrown into application code (spec: evaluation must never throw). See #258.
        case Exit.Success(value) => value
        case Exit.Failure(cause) => throw FiberFailures.toOpenFeatureError(cause)
      }
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

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    cached(key, "long", context, underlying.getLongEvaluation(key, defaultValue, context))

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
  def make(underlying: FeatureProvider, config: CachingConfig = CachingConfig()): UIO[CachingProvider] =
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
  def apply(underlying: FeatureProvider, config: CachingConfig = CachingConfig()): CachingProvider = {
    val rt = Runtime.default
    Unsafe.unsafe { implicit u =>
      rt.unsafe.run(make(underlying, config)).getOrThrowFiberFailure()
    }
  }
}
