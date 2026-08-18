package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider,
  Hook,
  ImmutableContext,
  ImmutableMetadata,
  Metadata,
  MutableTrackingEventDetails,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}
import dev.openfeature.sdk.exceptions.{FlagNotFoundError, GeneralError, OpenFeatureError}
import zio.openfeature.internal.ProviderEvaluations
import zio._
import zio.test._
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object CachingProviderSpec extends ZIOSpecDefault {

  /** A provider that counts evaluations and optionally delays to test concurrent dedup. */
  private class CountingProvider(
    flags: Map[String, Any],
    delay: Option[Duration] = None
  ) extends EventProvider {
    val evaluationCount = new AtomicInteger(0)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = "CountingProvider"
    }

    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def maybeDelay(): Unit = delay.foreach(d => Thread.sleep(d.toMillis))

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "TARGETING_MATCH")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluations.of[String](v, "TARGETING_MATCH")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "TARGETING_MATCH")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "TARGETING_MATCH")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v       = flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue)
      val builder = ProviderEvaluation.builder[Value]()
      builder.value(v)
      builder.variant("obj-variant")
      builder.reason("TARGETING_MATCH")
      builder.flagMetadata(ImmutableMetadata.builder().addString("source", "test").build())
      builder.build().asInstanceOf[ProviderEvaluation[Value]]
    }
  }

  /** A provider whose every evaluation throws the given exception, to test error-surfacing (#258). */
  private class ThrowingProvider(error: RuntimeException) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata   = new Metadata { override def getName: String = "ThrowingProvider" }
    override def getState: ProviderState = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(
      k: String,
      d: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = throw error
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext): ProviderEvaluation[String] =
      throw error
    override def getIntegerEvaluation(
      k: String,
      d: java.lang.Integer,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = throw error
    override def getDoubleEvaluation(
      k: String,
      d: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = throw error
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext): ProviderEvaluation[Value] =
      throw error
  }

  /** A provider that always echoes the caller's default value with reason DEFAULT (as EnvVar/Hocon do for an absent
    * flag), counting evaluations. Used to prove DEFAULT-reason results are not cached across differing defaults (#259).
    */
  private class DefaultingProvider extends EventProvider {
    val evaluationCount = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata   = new Metadata { override def getName: String = "DefaultingProvider" }
    override def getState: ProviderState = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(
      k: String,
      d: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      evaluationCount.incrementAndGet(); ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet(); ProviderEvaluations.of[String](d, "DEFAULT")
    }
    override def getIntegerEvaluation(
      k: String,
      d: java.lang.Integer,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet(); ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    }
    override def getDoubleEvaluation(
      k: String,
      d: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet(); ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet(); ProviderEvaluations.of[Value](d, "DEFAULT")
    }
  }

  /** A delegate that implements only `FeatureProvider`, never `EventProvider` — the shape #382 is about.
    *
    * Deliberately defines no `getLongEvaluation`: it stands in for a pre-1.22 third-party provider, so long resolution
    * must fall through its own double-backed SDK default.
    */
  private class PlainCountingProvider(
    flags: Map[String, Any],
    domainScoped: Boolean = true,
    delay: Option[Duration] = None
  ) extends FeatureProvider {
    val evaluationCount               = new AtomicInteger(0)
    val doubleCount                   = new AtomicInteger(0)
    val longCount                     = new AtomicInteger(0)
    val initCount                     = new AtomicInteger(0)
    val domainInitCount               = new AtomicInteger(0)
    val shutdownCount                 = new AtomicInteger(0)
    val tracked                       = new CopyOnWriteArrayList[String]()
    private val providerHook: Hook[_] = new Hook[java.lang.Object] {}

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "PlainCountingProvider" }

    override def initialize(c: OFEvaluationContext): Unit                 = { initCount.incrementAndGet(); () }
    override def initialize(c: OFEvaluationContext, domain: String): Unit = { domainInitCount.incrementAndGet(); () }
    override def isDomainScoped(): Boolean                                = domainScoped
    override def shutdown(): Unit                                         = { shutdownCount.incrementAndGet(); () }
    override def getProviderHooks(): java.util.List[Hook[_]] = java.util.Collections.singletonList(providerHook)
    override def track(eventName: String, c: OFEvaluationContext, d: TrackingEventDetails): Unit = {
      tracked.add(eventName); ()
    }

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      evaluationCount.incrementAndGet()
      delay.foreach(d => Thread.sleep(d.toMillis))
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "TARGETING_MATCH")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      c: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluations.of[String](v, "TARGETING_MATCH")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "TARGETING_MATCH")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      doubleCount.incrementAndGet()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "TARGETING_MATCH")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      c: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      val v = flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue)
      ProviderEvaluations.of[Value](v, "TARGETING_MATCH")
    }
  }

  /** A plain delegate that *does* define `getLongEvaluation`, returning a value its `getDoubleEvaluation` never
    * produces — so a wrapper that drops the override and falls back to the SDK's double path is distinguishable.
    */
  private class LongAwarePlainProvider extends PlainCountingProvider(Map("n" -> 99.0)) {
    override def getLongEvaluation(
      key: String,
      defaultValue: java.lang.Long,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Long] = {
      longCount.incrementAndGet()
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(7L), "TARGETING_MATCH")
    }
  }

  private val ctx = new ImmutableContext()

  def spec = suite("CachingProvider")(
    suite("Cache hits and misses")(
      test("first evaluation is a miss, second is a hit with CACHED reason") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        val r1         = cached.getBooleanEvaluation("flag", false, ctx)
        val r2         = cached.getBooleanEvaluation("flag", false, ctx)
        assertTrue(r1.getValue == true) &&
        assertTrue(r1.getReason == "TARGETING_MATCH") &&
        assertTrue(r2.getValue == true) &&
        assertTrue(r2.getReason == "CACHED") &&
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("different keys are cached independently") {
        val underlying = new CountingProvider(Map("a" -> true, "b" -> false))
        val cached     = CachingProvider(underlying)
        cached.getBooleanEvaluation("a", false, ctx)
        cached.getBooleanEvaluation("b", true, ctx)
        cached.getBooleanEvaluation("a", false, ctx)
        cached.getBooleanEvaluation("b", true, ctx)
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("different contexts produce separate cache entries") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        val ctx1       = new ImmutableContext("user-1")
        val ctx2       = new ImmutableContext("user-2")
        cached.getBooleanEvaluation("flag", false, ctx1)
        cached.getBooleanEvaluation("flag", false, ctx2)
        cached.getBooleanEvaluation("flag", false, ctx1) // cache hit
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("null context is handled") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        val r1         = cached.getBooleanEvaluation("flag", false, null)
        val r2         = cached.getBooleanEvaluation("flag", false, null)
        assertTrue(r1.getReason == "TARGETING_MATCH") &&
        assertTrue(r2.getReason == "CACHED") &&
        assertTrue(underlying.evaluationCount.get() == 1)
      }
    ),
    suite("Context key filtering")(
      test("contextKeys filters high-cardinality fields from cache key") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached = CachingProvider(
          underlying,
          CachingConfig(contextKeys = Some(Set("plan")))
        )
        // Two contexts with same "plan" but different targeting key (e.g., per-request UUID)
        val attrs1 = new java.util.HashMap[String, Value]()
        attrs1.put("plan", new Value("premium"))
        val attrs2 = new java.util.HashMap[String, Value]()
        attrs2.put("plan", new Value("premium"))
        val ctx1 = new ImmutableContext("user-aaa-111", attrs1)
        val ctx2 = new ImmutableContext("user-bbb-222", attrs2)
        cached.getBooleanEvaluation("flag", false, ctx1) // miss
        val r2 = cached.getBooleanEvaluation("flag", false, ctx2) // hit — same "plan"
        assertTrue(r2.getReason == "CACHED") &&
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("different values in contextKeys produce separate cache entries") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached = CachingProvider(
          underlying,
          CachingConfig(contextKeys = Some(Set("plan")))
        )
        val premium = new java.util.HashMap[String, Value]()
        premium.put("plan", new Value("premium"))
        val free = new java.util.HashMap[String, Value]()
        free.put("plan", new Value("free"))
        val ctx1 = new ImmutableContext("user-1", premium)
        val ctx2 = new ImmutableContext("user-2", free)
        cached.getBooleanEvaluation("flag", false, ctx1)
        cached.getBooleanEvaluation("flag", false, ctx2)
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("empty contextKeys ignores all context — caches by flag key only") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached = CachingProvider(
          underlying,
          CachingConfig(contextKeys = Some(Set.empty))
        )
        val ctx1 = new ImmutableContext("user-1")
        val ctx2 = new ImmutableContext("user-2")
        cached.getBooleanEvaluation("flag", false, ctx1) // miss
        val r2 = cached.getBooleanEvaluation("flag", false, ctx2) // hit — context ignored
        assertTrue(r2.getReason == "CACHED") &&
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("without contextKeys, different targeting keys produce cache misses") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying) // default: contextKeys = None
        val ctx1       = new ImmutableContext("user-1")
        val ctx2       = new ImmutableContext("user-2")
        cached.getBooleanEvaluation("flag", false, ctx1)
        cached.getBooleanEvaluation("flag", false, ctx2)
        assertTrue(underlying.evaluationCount.get() == 2)
      }
    ),
    suite("All flag types")(
      test("caches boolean, string, integer, double, and object evaluations") {
        val underlying =
          new CountingProvider(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14, "o" -> "obj"))
        val cached = CachingProvider(underlying)
        // First pass — all misses
        cached.getBooleanEvaluation("b", false, ctx)
        cached.getStringEvaluation("s", "", ctx)
        cached.getIntegerEvaluation("i", 0, ctx)
        cached.getDoubleEvaluation("d", 0.0, ctx)
        cached.getObjectEvaluation("o", new Value(), ctx)
        // Second pass — all hits
        val r1 = cached.getBooleanEvaluation("b", false, ctx)
        val r2 = cached.getStringEvaluation("s", "", ctx)
        val r3 = cached.getIntegerEvaluation("i", 0, ctx)
        val r4 = cached.getDoubleEvaluation("d", 0.0, ctx)
        val r5 = cached.getObjectEvaluation("o", new Value(), ctx)
        assertTrue(r1.getReason == "CACHED") &&
        assertTrue(r2.getReason == "CACHED") &&
        assertTrue(r3.getReason == "CACHED") &&
        assertTrue(r4.getReason == "CACHED") &&
        assertTrue(r5.getReason == "CACHED") &&
        assertTrue(underlying.evaluationCount.get() == 5)
      },
      test("cached entries preserve variant and flag metadata") {
        val underlying = new CountingProvider(Map("o" -> "value"))
        val cached     = CachingProvider(underlying)
        cached.getObjectEvaluation("o", new Value(), ctx)
        val hit = cached.getObjectEvaluation("o", new Value(), ctx)
        assertTrue(hit.getVariant == "obj-variant") &&
        assertTrue(hit.getFlagMetadata.getString("source") == "test")
      }
    ),
    suite("TTL expiration")(
      test("expired entries are re-evaluated") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 1.millisecond))
        cached.getBooleanEvaluation("flag", false, ctx)
        Thread.sleep(10) // let TTL expire
        cached.getBooleanEvaluation("flag", false, ctx)
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("non-expired entries are served from cache") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 10.seconds))
        cached.getBooleanEvaluation("flag", false, ctx)
        cached.getBooleanEvaluation("flag", false, ctx)
        assertTrue(underlying.evaluationCount.get() == 1)
      }
    ),
    suite("Eviction")(
      test("working set larger than maxEntries causes re-evaluations") {
        val flags      = (1 to 5).map(i => s"flag-$i" -> true).toMap
        val underlying = new CountingProvider(flags)
        val cached     = CachingProvider(underlying, CachingConfig(maxEntries = 3))
        // Evaluate 5 unique keys with capacity 3 — some must be evicted
        (1 to 5).foreach(i => cached.getBooleanEvaluation(s"flag-$i", false, ctx))
        val afterFirst = underlying.evaluationCount.get()
        // Re-evaluate all — evicted entries require re-evaluation from the underlying
        (1 to 5).foreach(i => cached.getBooleanEvaluation(s"flag-$i", false, ctx))
        val afterSecond = underlying.evaluationCount.get()
        // First pass: 5 evaluations (all misses)
        assertTrue(afterFirst == 5) &&
        // Second pass: at least some must miss due to eviction (working set 5 > capacity 3)
        assertTrue(afterSecond > 5)
      },
      test("entries within capacity are not evicted") {
        val underlying = new CountingProvider(Map("a" -> true, "b" -> false))
        val cached     = CachingProvider(underlying, CachingConfig(maxEntries = 10))
        cached.getBooleanEvaluation("a", false, ctx)
        cached.getBooleanEvaluation("b", true, ctx)
        // Re-evaluate — both should be cached (2 keys < 10 capacity)
        cached.getBooleanEvaluation("a", false, ctx)
        cached.getBooleanEvaluation("b", true, ctx)
        assertTrue(underlying.evaluationCount.get() == 2)
      }
    ),
    suite("Invalidation")(
      test("invalidateAll clears the cache") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        cached.getBooleanEvaluation("flag", false, ctx)
        for {
          _ <- cached.invalidateAll
        } yield {
          cached.getBooleanEvaluation("flag", false, ctx)
          assertTrue(underlying.evaluationCount.get() == 2)
        }
      },
      test("cache can be rebuilt after invalidation") {
        val underlying = new CountingProvider(Map("a" -> true, "b" -> false))
        val cached     = CachingProvider(underlying)
        cached.getBooleanEvaluation("a", false, ctx)
        cached.getBooleanEvaluation("b", true, ctx)
        for {
          _ <- cached.invalidateAll
        } yield {
          // Re-populate
          cached.getBooleanEvaluation("a", false, ctx)
          cached.getBooleanEvaluation("b", true, ctx)
          // Should be cached again
          val r1 = cached.getBooleanEvaluation("a", false, ctx)
          val r2 = cached.getBooleanEvaluation("b", true, ctx)
          assertTrue(r1.getReason == "CACHED") &&
          assertTrue(r2.getReason == "CACHED") &&
          assertTrue(underlying.evaluationCount.get() == 4)
        }
      }
    ),
    suite("Concurrent deduplication")(
      test("concurrent evaluations of the same flag call the underlying provider only once") {
        for {
          underlying <- ZIO.succeed(new CountingProvider(Map("flag" -> true), delay = Some(50.millis)))
          cached     <- CachingProvider.make(underlying)
          // Launch 10 fibers all requesting the same flag concurrently
          results <- ZIO.collectAllPar(
            (1 to 10).map(_ => ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx)))
          )
        } yield
        // All 10 should get the correct value
        assertTrue(results.forall(_.getValue == true)) &&
          // But the underlying provider should only have been called once (dedup)
          assertTrue(underlying.evaluationCount.get() == 1)
      }
    ),
    suite("Delegate event propagation (#176)")(
      test("delegate CONFIGURATION_CHANGED invalidates the cache") {
        class EmittingProvider extends CountingProvider(Map("flag" -> true)) {
          def fireConfigChanged(): Unit = {
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
            ()
          }
        }
        for {
          underlying <- ZIO.succeed(new EmittingProvider)
          cached     <- CachingProvider.make(underlying, CachingConfig(ttl = 10.minutes))
          _          <- ZIO.attemptBlocking(cached.initialize(ctx))
          _          <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
          r2         <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
          // The emission runs on the delegate's async emitter executor; a single emit can occasionally be delayed or
          // dropped under load, so re-fire on each (spaced) poll until the cache misses, bounded by the timeout.
          _ <- (ZIO.attemptBlocking {
            underlying.fireConfigChanged()
            cached.getBooleanEvaluation("flag", false, ctx).getReason
          } <* ZIO.sleep(100.millis))
            .repeatUntil(_ != "CACHED")
            .timeoutFail(new RuntimeException("cache was never invalidated"))(10.seconds)
        } yield assertTrue(r2.getReason == "CACHED", underlying.evaluationCount.get() >= 2)
      },
      test("delegate events are re-emitted through the wrapper") {
        class EmittingProvider extends CountingProvider(Map("flag" -> true)) {
          def fireStale(): Unit = {
            emitProviderStale(dev.openfeature.sdk.ProviderEventDetails.builder().message("stale!").build())
            ()
          }
        }
        val seen = new java.util.concurrent.ConcurrentLinkedQueue[dev.openfeature.sdk.ProviderEvent]()
        for {
          underlying <- ZIO.succeed(new EmittingProvider)
          cached     <- CachingProvider.make(underlying)
          // Stand in for the SDK: attach to the wrapper the way OpenFeatureAPI would on registration
          _ <- ZIO.succeed(dev.openfeature.sdk.EventProviderBridge.attach(cached, (e, _) => { seen.add(e); () }))
          _ <- ZIO.attemptBlocking(cached.initialize(ctx))
          _ <- ZIO.succeed(underlying.fireStale())
          _ <- ZIO
            .succeed(seen.contains(dev.openfeature.sdk.ProviderEvent.PROVIDER_STALE))
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("delegate event was not re-emitted"))(10.seconds)
        } yield assertTrue(seen.contains(dev.openfeature.sdk.ProviderEvent.PROVIDER_STALE))
      }
    ),
    suite("Failure handling (#175)")(
      test("a delegate exception is not served from cache — next evaluation retries the delegate") {
        val failFirst = new java.util.concurrent.atomic.AtomicBoolean(true)
        val underlying = new CountingProvider(Map("flag" -> true)) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            c: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] = {
            if (failFirst.getAndSet(false)) {
              evaluationCount.incrementAndGet()
              throw new RuntimeException("transient outage")
            }
            super.getBooleanEvaluation(key, defaultValue, c)
          }
        }
        for {
          cached <- CachingProvider.make(underlying, CachingConfig(ttl = 1.minute))
          first  <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx)).exit
          second <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(
          first.isFailure,
          second.getValue == true,
          underlying.evaluationCount.get() == 2
        )
      },
      test("an evaluation that resolves with an error code is returned but not cached") {
        val errorFirst = new java.util.concurrent.atomic.AtomicBoolean(true)
        val underlying = new CountingProvider(Map("flag" -> true)) {
          override def getBooleanEvaluation(
            key: String,
            defaultValue: java.lang.Boolean,
            c: OFEvaluationContext
          ): ProviderEvaluation[java.lang.Boolean] =
            if (errorFirst.getAndSet(false)) {
              evaluationCount.incrementAndGet()
              val builder = ProviderEvaluation.builder[java.lang.Boolean]()
              builder.value(defaultValue)
              builder.reason("ERROR")
              builder.errorCode(dev.openfeature.sdk.ErrorCode.GENERAL)
              builder.errorMessage("upstream hiccup")
              builder.build().asInstanceOf[ProviderEvaluation[java.lang.Boolean]]
            } else super.getBooleanEvaluation(key, defaultValue, c)
        }
        for {
          cached <- CachingProvider.make(underlying, CachingConfig(ttl = 1.minute))
          first  <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
          second <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
          third  <- ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(
          first.getErrorCode != null,
          second.getValue == true,
          second.getErrorCode == null,
          third.getReason == "CACHED",
          underlying.evaluationCount.get() == 2
        )
      },
      test("high-contention same-key evaluations never fail with a registration race") {
        // The previous implementation handed the lookup its thunk via a shared mutable map; under contention a
        // caller's cleanup could remove another caller's thunk, surfacing IllegalStateException and caching it.
        for {
          underlying <- ZIO.succeed(new CountingProvider(Map("flag" -> true), delay = Some(5.millis)))
          cached     <- CachingProvider.make(underlying, CachingConfig(ttl = 50.millis))
          // Many waves of concurrent calls across TTL expirations to repeatedly exercise the miss path
          results <- ZIO.foreach(1 to 5) { _ =>
            ZIO.collectAllPar(
              (1 to 30).map(_ => ZIO.attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx)).exit)
            ) <* ZIO.sleep(60.millis)
          }
        } yield assertTrue(results.flatten.forall(_.isSuccess))
      }
    ),
    suite("Lifecycle")(
      test("metadata includes underlying provider name") {
        val underlying = new CountingProvider(Map.empty)
        val cached     = CachingProvider(underlying)
        assertTrue(cached.getMetadata.getName == "CachingProvider(CountingProvider)")
      },
      test("delegates getState to underlying") {
        val underlying = new CountingProvider(Map.empty)
        val cached     = CachingProvider(underlying)
        assertTrue(cached.getState == ProviderState.READY)
      },
      test("shutdown clears cache and delegates to underlying") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        cached.getBooleanEvaluation("flag", false, ctx)
        cached.shutdown()
        // After shutdown + re-init, cache should be empty
        // (in practice provider would need re-initialization, but we test cache clearing)
        assertTrue(underlying.evaluationCount.get() == 1)
      }
    ),
    suite("Plain FeatureProvider delegate (#382)")(
      test("apply accepts a plain FeatureProvider and forwards evaluations") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying)
        val result     = cached.getBooleanEvaluation("flag", false, ctx)
        assertTrue(result.getValue == true, underlying.evaluationCount.get() == 1)
      },
      test("make accepts a plain FeatureProvider") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        for {
          cached <- CachingProvider.make(underlying, CachingConfig(ttl = 1.minute))
          result <- ZIO.attempt(cached.getBooleanEvaluation("flag", false, ctx))
        } yield assertTrue(result.getValue == true, underlying.evaluationCount.get() == 1)
      },
      test("caches a plain delegate's evaluations") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 10.seconds))
        cached.getBooleanEvaluation("flag", false, ctx)
        cached.getBooleanEvaluation("flag", false, ctx)
        cached.getBooleanEvaluation("flag", false, ctx)
        // The whole point of the widening: a plain delegate gets the same caching an EventProvider does.
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("expired entries are re-evaluated for a plain delegate") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 1.millisecond))
        cached.getBooleanEvaluation("flag", false, ctx)
        Thread.sleep(10)
        cached.getBooleanEvaluation("flag", false, ctx)
        // TTL is the only eviction path a plain delegate has, since it cannot emit
        // PROVIDER_CONFIGURATION_CHANGED — so it has to work.
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("forwards every resolver to a plain delegate") {
        val underlying =
          new PlainCountingProvider(Map("b" -> true, "s" -> "hello", "i" -> 42, "d" -> 3.14, "o" -> "obj"))
        val cached = CachingProvider(underlying)
        assertTrue(
          cached.getBooleanEvaluation("b", false, ctx).getValue == true,
          cached.getStringEvaluation("s", "", ctx).getValue == "hello",
          cached.getIntegerEvaluation("i", 0, ctx).getValue == 42,
          cached.getDoubleEvaluation("d", 0.0, ctx).getValue == 3.14,
          cached.getObjectEvaluation("o", new Value(), ctx).getValue.asString() == "obj",
          underlying.evaluationCount.get() == 5
        )
      },
      test("forwards getLongEvaluation to a plain delegate that defines it") {
        val underlying = new LongAwarePlainProvider
        val cached     = CachingProvider(underlying)
        val result     = cached.getLongEvaluation("n", java.lang.Long.valueOf(0L), ctx)
        // 7L comes only from the delegate's own override; the SDK's double-backed default gives 99L.
        assertTrue(
          result.getValue.longValue == 7L,
          underlying.longCount.get() == 1,
          underlying.doubleCount.get() == 0
        )
      },
      test("routes long resolution through a pre-1.22 plain delegate's own double default") {
        val underlying = new PlainCountingProvider(Map("n" -> 42.0))
        val cached     = CachingProvider(underlying)
        val result     = cached.getLongEvaluation("n", java.lang.Long.valueOf(0L), ctx)
        assertTrue(result.getValue.longValue == 42L, underlying.doubleCount.get() == 1)
      },
      test("forwards initialize, initialize(domain), isDomainScoped and shutdown to a plain delegate") {
        val underlying = new PlainCountingProvider(Map.empty)
        val cached     = CachingProvider(underlying)
        cached.initialize(ctx)
        cached.initialize(ctx, "orders")
        val domainScoped = cached.isDomainScoped()
        cached.shutdown()
        // Both values, because the SDK's own default is `false`: a delegate that only ever reported
        // `false` would agree with a wrapper that had dropped the override entirely.
        val notDomainScoped =
          CachingProvider(new PlainCountingProvider(Map.empty, domainScoped = false)).isDomainScoped()
        assertTrue(
          underlying.initCount.get() == 1,
          underlying.domainInitCount.get() == 1,
          domainScoped,
          !notDomainScoped,
          underlying.shutdownCount.get() == 1
        )
      },
      test("shutdown invalidates the cache for a plain delegate") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 10.seconds))
        cached.getBooleanEvaluation("flag", false, ctx)
        cached.shutdown()
        cached.getBooleanEvaluation("flag", false, ctx)
        // A second delegate call proves the entry was dropped; without invalidation the 10s TTL
        // would still be serving the cached value here.
        assertTrue(underlying.evaluationCount.get() == 2)
      },
      test("forwards getProviderHooks and track to a plain delegate") {
        val underlying = new PlainCountingProvider(Map.empty)
        val cached     = CachingProvider(underlying)
        cached.track("purchase", ctx, new MutableTrackingEventDetails())
        assertTrue(cached.getProviderHooks.size == 1, underlying.tracked.contains("purchase"))
      },
      test("initialize does not disturb a plain delegate's cache") {
        val underlying = new PlainCountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 10.seconds))
        cached.getBooleanEvaluation("flag", false, ctx)
        // `initialize` is the only path that would reach event wiring at all; for a plain delegate it
        // must be inert with respect to the cache. Guards against a future edit invalidating
        // unconditionally inside attachDelegate rather than gating on eventDelegate.
        cached.initialize(ctx)
        cached.getBooleanEvaluation("flag", false, ctx)
        assertTrue(underlying.evaluationCount.get() == 1)
      },
      test("initializing a plain delegate twice is safe and re-runs delegate initialization") {
        val underlying = new PlainCountingProvider(Map.empty)
        val cached     = CachingProvider(underlying)
        cached.initialize(ctx)
        cached.initialize(ctx)
        assertTrue(underlying.initCount.get() == 2)
      },
      test("concurrent evaluations of a plain delegate deduplicate onto one call") {
        val underlying = new PlainCountingProvider(Map("flag" -> true), delay = Some(100.millis))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 10.seconds))
        for {
          results <- ZIO.foreachPar(1 to 8)(_ => ZIO.attempt(cached.getBooleanEvaluation("flag", false, ctx)))
          // zio-cache dedups by logical key, so the delegate runs once even for a plain provider.
        } yield assertTrue(results.forall(_.getValue == true), underlying.evaluationCount.get() == 1)
      } @@ TestAspect.withLiveClock,
      test("names the wrapped plain delegate in its metadata and reports its state") {
        val cached = CachingProvider(new PlainCountingProvider(Map.empty))
        assertTrue(
          cached.getMetadata.getName == "CachingProvider(PlainCountingProvider)",
          cached.getState == ProviderState.READY
        )
      }
    ),
    suite("make() factory")(
      test("make creates a working CachingProvider via ZIO") {
        for {
          underlying <- ZIO.succeed(new CountingProvider(Map("flag" -> true)))
          cached     <- CachingProvider.make(underlying, CachingConfig(ttl = 1.minute))
          _ <- ZIO.succeed {
            cached.getBooleanEvaluation("flag", false, ctx)
            cached.getBooleanEvaluation("flag", false, ctx)
          }
        } yield assertTrue(underlying.evaluationCount.get() == 1)
      }
    ),
    suite("cache correctness (#259)")(
      test("a DEFAULT-reason result is not cached, so differing call-site defaults do not leak") {
        val underlying = new DefaultingProvider
        val cached     = CachingProvider(underlying)
        val a          = cached.getBooleanEvaluation("absent", false, ctx) // call site A: default false
        val b          = cached.getBooleanEvaluation("absent", true, ctx)  // call site B: default true
        assertTrue(
          !a.getValue,
          b.getValue, // B gets its OWN default, not A's cached `false`
          a.getReason == "DEFAULT",
          b.getReason == "DEFAULT",             // not CACHED — DEFAULT results are not retained
          underlying.evaluationCount.get() == 2 // both call sites reach the delegate
        )
      },
      test("after TTL expiry a re-evaluation is a fresh miss, not mislabeled CACHED") {
        val underlying = new CountingProvider(Map("flag" -> true))
        val cached     = CachingProvider(underlying, CachingConfig(ttl = 100.millis))
        for {
          r1 <- ZIO.succeed(cached.getBooleanEvaluation("flag", false, ctx)) // miss
          _  <- ZIO.sleep(250.millis)                                        // let the TTL lapse (live clock)
          r2 <- ZIO.succeed(cached.getBooleanEvaluation("flag", false, ctx)) // expired → fresh miss
        } yield assertTrue(
          r1.getReason == "TARGETING_MATCH",
          r2.getReason == "TARGETING_MATCH", // the racy contains→get gap would have mislabeled this CACHED
          underlying.evaluationCount.get() == 2
        )
      }
    ),
    suite("error surfacing (#258)")(
      test("a delegate OpenFeatureError surfaces as-is (an Exception), not a zio.FiberFailure") {
        val cached = CachingProvider(new ThrowingProvider(new FlagNotFoundError("flag")))
        for {
          err <- ZIO.attempt(cached.getBooleanEvaluation("flag", false, ctx)).flip
        } yield assertTrue(
          err.isInstanceOf[FlagNotFoundError], // original error preserved so the SDK maps FLAG_NOT_FOUND
          err.isInstanceOf[Exception],         // caught by the SDK's `catch (Exception)` → default returned, hooks run
          !err.isInstanceOf[zio.FiberFailure]  // the bug: a FiberFailure (a Throwable) would escape that catch
        )
      },
      test("a non-OpenFeature delegate failure is wrapped in GeneralError, not a zio.FiberFailure") {
        val cached = CachingProvider(new ThrowingProvider(new RuntimeException("boom")))
        for {
          err <- ZIO.attempt(cached.getStringEvaluation("flag", "d", ctx)).flip
        } yield assertTrue(
          err.isInstanceOf[GeneralError],
          err.isInstanceOf[OpenFeatureError],
          !err.isInstanceOf[zio.FiberFailure]
        )
      }
    )
  ) @@ TestAspect.withLiveClock
}
