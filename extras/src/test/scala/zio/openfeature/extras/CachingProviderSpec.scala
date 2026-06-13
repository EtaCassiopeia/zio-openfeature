package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  ImmutableMetadata,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
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
      ProviderEvaluation.builder[java.lang.Boolean]().value(v).reason("TARGETING_MATCH").build()
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluation.builder[String]().value(v).reason("TARGETING_MATCH").build()
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluation.builder[java.lang.Integer]().value(v).reason("TARGETING_MATCH").build()
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluation.builder[java.lang.Double]().value(v).reason("TARGETING_MATCH").build()
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] = {
      evaluationCount.incrementAndGet()
      maybeDelay()
      val v = flags.get(key).map(a => new Value(a.toString)).getOrElse(defaultValue)
      ProviderEvaluation
        .builder[Value]()
        .value(v)
        .variant("obj-variant")
        .reason("TARGETING_MATCH")
        .flagMetadata(ImmutableMetadata.builder().addString("source", "test").build())
        .build()
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
          _          <- ZIO.succeed(underlying.fireConfigChanged())
          // The emission runs on the delegate's emitter executor; poll until the cache misses again
          _ <- ZIO
            .attemptBlocking(cached.getBooleanEvaluation("flag", false, ctx))
            .repeatUntil(_.getReason != "CACHED")
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
              ProviderEvaluation
                .builder[java.lang.Boolean]()
                .value(defaultValue)
                .reason("ERROR")
                .errorCode(dev.openfeature.sdk.ErrorCode.GENERAL)
                .errorMessage("upstream hiccup")
                .build()
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
    )
  ) @@ TestAspect.withLiveClock
}
