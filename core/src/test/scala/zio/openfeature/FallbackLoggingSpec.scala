package zio.openfeature

import zio._
import zio.test._
import zio.stream.ZStream
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

/** #350: served-default fallbacks in the total tier are logged at warn, rate-limited per key, according to
  * `FallbackLogging` — plumbed through `FeatureFlagsConfig` and, separately, `fromAcquireAsync`.
  */
object FallbackLoggingSpec extends ZIOSpecDefault {

  /** Answers FLAG_NOT_FOUND for every key except `known` (a real `true`), so every other `*OrDefault` call is a
    * served-default fallback.
    */
  private class MissingProvider(nm: String) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = nm }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    private def missing[T](k: String, d: T) =
      ProviderEvaluations.error(d, OFErrorCode.FLAG_NOT_FOUND, s"flag $k does not exist")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      if (k == "known") ProviderEvaluations.of(java.lang.Boolean.TRUE, "STATIC") else missing(k, d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = missing(k, d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = missing(k, d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = missing(k, d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = missing(k, d)
  }

  private def layer(config: FeatureFlagsConfig): ZLayer[Scope, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(
      new MissingProvider("missing"),
      config.withDomain(s"fl-${java.util.UUID.randomUUID()}")
    )

  // The test logger accumulates across tests in the same runtime, so every test measures from its own baseline.
  private def baseline: UIO[Int] = ZTestLogger.logOutput.map(_.length)
  private def warnings(since: Int): UIO[Chunk[String]] =
    ZTestLogger.logOutput.map(_.drop(since).filter(_.logLevel == LogLevel.Warning).map(_.message()))

  /** Minimal external implementor: the trait's default hook must keep today's behaviour for it. */
  abstract private class StubFlags extends FeatureFlags {
    protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]]
    override def booleanDetails(k: String, d: Boolean, ctx: EvaluationContext, o: EvaluationOptions)      = boom
    override def stringDetails(k: String, d: String, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def intDetails(k: String, d: Int, ctx: EvaluationContext, o: EvaluationOptions)              = ???
    override def longDetails(k: String, d: Long, ctx: EvaluationContext, o: EvaluationOptions)            = ???
    override def doubleDetails(k: String, d: Double, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def objDetails(k: String, d: Map[String, Any], ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = boom
    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def globalContext: UIO[EvaluationContext]                                                    = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def clientContext: UIO[EvaluationContext]                                                    = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]            = ???
    override def transaction[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]] = ???
    override def inTransaction: UIO[Boolean]                                                             = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                              = ???
    override def events: ZStream[Any, Nothing, ProviderEvent]                                            = ???
    override def providerStatus: UIO[ProviderStatus]                                                     = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                       = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                 = ???
    override def clientMetadata: UIO[ClientMetadata]                                                     = ???
    override def onProviderReady(h: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]                       = ???
    override def onProviderError(h: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]          = ???
    override def onProviderStale(h: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]             = ???
    override def onConfigurationChanged(h: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = ???
    override def on(t: ProviderEventType, h: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]                 = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                   = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                           = ???
    override def clearHooks: UIO[Unit]                                                                   = ???
    override def hooks: UIO[List[FeatureHook]]                                                           = ???
    override def addZioApiHook(hook: FeatureHook): UIO[Unit]                                             = ???
    override def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]                                     = ???
    override def clearZioApiHooks: UIO[Unit]                                                             = ???
    override def zioApiHooks: UIO[List[FeatureHook]]                                                     = ???
    override def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]                                = ???
    override def clearApiHooks: UIO[Unit]                                                                = ???
    override def setProvider(p: dev.openfeature.sdk.FeatureProvider): IO[FeatureFlagError, Unit]         = ???
    override def shutdown: UIO[Unit]                                                                     = ???
    override def track(eventName: String): IO[FeatureFlagError, Unit]                                    = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]        = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]     = ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError, Unit] = ???
    override def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] = ???
  }

  def spec = suite("FallbackLoggingSpec")(
    test(
      "default policy: a served-default fallback is logged at warn once per key per 60s, then with the suppressed count"
    ) {
      ZIO.scoped {
        for {
          base  <- baseline
          ff    <- layer(FeatureFlagsConfig()).build.map(_.get[FeatureFlags])
          v1    <- ff.booleanOrDefault("hot", default = true)
          _     <- ff.booleanOrDefault("hot", default = true)
          _     <- ff.stringOrDefault("hot", default = "s") // same key, different type — same bucket
          _     <- ff.intOrDefault("other", default = 7)
          first <- warnings(base)
          _     <- TestClock.adjust(60.seconds)
          _     <- ff.booleanOrDefault("hot", default = true)
          all   <- warnings(base)
        } yield assertTrue(
          v1,                // resolution unchanged: default served
          first.length == 2, // one line per key
          first.exists(m => m.contains("'hot'") && m.contains("FlagNotFound") && m.contains("not found")),
          first.exists(m => m.contains("'other'") && m.contains("7")),
          first.forall(!_.contains("suppressed")),
          all.length == 3,
          all(2).contains("'hot'"),
          all(2).contains("(suppressed 2 similar)")
        )
      }
    },
    test("resolveOrDefault is logged too and its resolution is unchanged") {
      ZIO.scoped {
        for {
          base <- baseline
          ff   <- layer(FeatureFlagsConfig()).build.map(_.get[FeatureFlags])
          res  <- ff.resolveOrDefault[Boolean]("k", default = false)
          logs <- warnings(base)
        } yield assertTrue(
          !res.value,
          res.reason == ResolutionReason.Error,
          res.errorCode == Some(ErrorCode.FlagNotFound),
          logs.length == 1,
          logs.head.contains("'k'")
        )
      }
    },
    test("FallbackLogging.Always logs every fallback") {
      ZIO.scoped {
        for {
          base <- baseline
          ff   <- layer(FeatureFlagsConfig().withFallbackLogging(FallbackLogging.Always)).build.map(_.get[FeatureFlags])
          _    <- ZIO.foreachDiscard(1 to 4)(_ => ff.booleanOrDefault("k", default = true))
          logs <- warnings(base)
        } yield assertTrue(logs.length == 4, logs.forall(!_.contains("suppressed")))
      }
    },
    test("FallbackLogging.Off logs nothing") {
      ZIO.scoped {
        for {
          base <- baseline
          ff   <- layer(FeatureFlagsConfig().withFallbackLogging(FallbackLogging.Off)).build.map(_.get[FeatureFlags])
          _    <- ZIO.foreachDiscard(1 to 4)(_ => ff.booleanOrDefault("k", default = true))
          logs <- warnings(base)
        } yield assertTrue(logs.isEmpty)
      }
    },
    test("non-total evaluations do not go through the fallback log") {
      ZIO.scoped {
        for {
          base <- baseline
          ff   <- layer(FeatureFlagsConfig()).build.map(_.get[FeatureFlags])
          d    <- ff.booleanDetails("k", true).either
          logs <- warnings(base)
        } yield assertTrue(d == Left(FeatureFlagError.FlagNotFound("k")), logs.isEmpty)
      }
    },
    test("a successful (non-error) resolution logs nothing") {
      ZIO.scoped {
        for {
          base <- baseline
          ff   <- layer(FeatureFlagsConfig().withFallbackLogging(FallbackLogging.Always)).build.map(_.get[FeatureFlags])
          v    <- ff.booleanOrDefault("known", default = false)
          r    <- ff.resolveOrDefault[Boolean]("known", default = false)
          logs <- warnings(base)
        } yield assertTrue(v, r.value, !r.isError, logs.isEmpty)
      }
    },
    test("InitMode.Async plumbs the policy through buildAsync too") {
      ZIO.scoped {
        for {
          base <- baseline
          ff <- layer(FeatureFlagsConfig().withAsyncInit.withFallbackLogging(FallbackLogging.Always)).build
            .map(_.get[FeatureFlags])
          _    <- Live.live(ff.awaitReady(10.seconds))
          _    <- ZIO.foreachDiscard(1 to 3)(_ => ff.booleanOrDefault("k", default = true))
          logs <- warnings(base)
        } yield assertTrue(logs.length == 3)
      }
    },
    test("hooks still see every evaluation while the log line is throttled") {
      ZIO.scoped {
        for {
          base <- baseline
          seen <- Ref.make(0)
          hook = new FeatureHook {
            override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
              seen.update(_ + 1)
          }
          ff   <- layer(FeatureFlagsConfig().withHook(hook)).build.map(_.get[FeatureFlags])
          _    <- ZIO.foreachDiscard(1 to 5)(_ => ff.booleanOrDefault("k", default = true))
          n    <- seen.get
          logs <- warnings(base)
        } yield assertTrue(n == 5, logs.length == 1)
      }
    },
    test("fromAcquireAsync honours its own fallbackLogging parameter") {
      val fallback                             = ZIO.succeed[FeatureProvider](new MissingProvider("fb"))
      val acquire: RIO[Scope, FeatureProvider] = ZIO.never
      for {
        base <- baseline
        // Off: nothing logged even though every evaluation is a fallback
        offLogs <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback, fallbackLogging = FallbackLogging.Off)
            .build
            .map(_.get[FeatureFlags])
            .flatMap(ff => ZIO.foreachDiscard(1 to 3)(_ => ff.booleanOrDefault("k", default = true)) *> warnings(base))
        }
        base2 <- baseline
        // Default (Throttled 60s): exactly one line for the key
        defLogs <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback)
            .build
            .map(_.get[FeatureFlags])
            .flatMap(ff => ZIO.foreachDiscard(1 to 3)(_ => ff.booleanOrDefault("k", default = true)) *> warnings(base2))
        }
      } yield assertTrue(offLogs.isEmpty, defLogs.length == 1)
    },
    test("trait default keeps today's behaviour for external implementors: defect logged, error-coded silent") {
      val dying = new StubFlags {
        protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]] = ZIO.die(new RuntimeException("boom"))
      }
      val failing = new StubFlags {
        protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]] = ZIO.fail(FeatureFlagError.FlagNotFound("k"))
      }
      for {
        base  <- baseline
        _     <- failing.booleanOrDefault("k", default = true)
        _     <- failing.booleanOrDefault("k", default = true)
        quiet <- warnings(base)
        _     <- dying.booleanOrDefault("k", default = true)
        _     <- dying.booleanOrDefault("k", default = true)
        loud  <- warnings(base)
      } yield assertTrue(
        quiet.isEmpty,
        loud.length == 2, // every defect, unthrottled — exactly what shipped before
        loud.forall(_.contains("absorbed a defect"))
      )
    }
  ) @@ TestAspect.sequential
}
