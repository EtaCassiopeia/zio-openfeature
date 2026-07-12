package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Gate tests for #253's config-driven factory (`FeatureFlags.fromProvider(provider, config)`). Each test exercises a
  * combination the pre-#253 factory-overload surface could not express (T1-T5), plus a behavioral-equivalence pin
  * between the deprecated forwards and the config path (T6).
  *
  * Isolated `OpenFeatureAPI` instances (`OpenFeatureAPI.createIsolated()`) + the `private[openfeature]`
  * `FeatureFlags.fromProvider(provider, config, statusRef, apiOverride, onReady)` variant are used throughout so these
  * tests never touch the process-global singleton and can run in parallel with every other spec.
  */
object FactoryConfigSpec extends ZIOSpecDefault {

  private def uniqueDomain(label: String): String = s"cfg-$label-${java.util.UUID.randomUUID()}"

  /** A minimal tracking provider (mirrors `FeatureFlagsShutdownSpec`'s) so T4a/T4b can observe `shutdown()` directly,
    * independent of `TestFeatureProvider`'s own state-transition semantics.
    */
  private class TrackingProvider(name: String, shutCalled: AtomicBoolean) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = shutCalled.set(true)

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def coreSuite = suite("Factory Config — FeatureFlagsConfig-driven fromProvider")(
    test("T1: domain + hooks (impossible before) — hooks fire AND clientMetadata reflects the domain") {
      for {
        provider  <- TestFeatureProvider.make(Map("flag" -> true))
        hookCalls <- Ref.make(0)
        hook = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            hookCalls.update(_ + 1)
        }
        domain = uniqueDomain("t1")
        config = FeatureFlagsConfig().withDomain(domain).withHooks(List(hook))
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap { env =>
              val ff = env.get[FeatureFlags]
              for {
                meta  <- ff.clientMetadata
                flag  <- ff.boolean("flag", default = false)
                calls <- hookCalls.get
              } yield assertTrue(meta.domain.contains(domain), flag, calls == 1)
            }
        }
      } yield result
    },
    test("T2: domain + evaluationTimeout — a slow provider fails with ProviderError(TimeoutException)") {
      for {
        provider <- TestFeatureProvider.make(Map("flag" -> true))
        _        <- provider.setDelay(500.millis)
        domain = uniqueDomain("t2")
        config = FeatureFlagsConfig().withDomain(domain).withEvaluationTimeout(20.millis)
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap(env => env.get[FeatureFlags].boolean("flag", default = false).either)
        }
      } yield assertTrue(
        result.left.toOption.exists {
          case FeatureFlagError.ProviderError(underlying) => underlying.isInstanceOf[TimeoutException]
          case _                                          => false
        }
      )
    } @@ withLiveClock,
    test("T3: initMode = Async — layer builds immediately; first eval fails ProviderNotReady, then succeeds") {
      for {
        provider <- TestFeatureProvider.makeNotReady(Map("flag" -> true))
        domain = uniqueDomain("t3")
        config = FeatureFlagsConfig(initMode = InitMode.Async).withDomain(domain)
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(
              provider,
              config,
              statusRef = Some(provider.statusRef),
              apiOverride = Some(OpenFeatureAPI.createIsolated()),
              onReady = provider.initDone
            )
            .build
            .flatMap { env =>
              val ff = env.get[FeatureFlags]
              for {
                before <- ff.boolean("flag", default = false).either
                _      <- provider.setStatus(ProviderStatus.Ready)
                after  <- ff.boolean("flag", default = false)
              } yield assertTrue(
                before == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
                after
              )
            }
        }
      } yield result
    } @@ withLiveClock,
    test("T4a: Auto + domain — closing the config-layer scope does NOT shut the shared api (#243)") {
      val shutA = new AtomicBoolean(false)
      val shutB = new AtomicBoolean(false)
      val api   = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          ffA <- FeatureFlags
            .fromProvider(
              new TrackingProvider("A", shutA),
              FeatureFlagsConfig(initMode = InitMode.Async).withDomain(uniqueDomain("t4a-A")),
              statusRef = None,
              apiOverride = Some(api)
            )
            .build
            .map(_.get[FeatureFlags])
          ffB <- FeatureFlags
            .fromProvider(
              new TrackingProvider("B", shutB),
              FeatureFlagsConfig(initMode = InitMode.Async).withDomain(uniqueDomain("t4a-B")),
              statusRef = None,
              apiOverride = Some(api)
            )
            .build
            .map(_.get[FeatureFlags])
          _      <- ffA.awaitReady(5.seconds)
          _      <- ffB.awaitReady(5.seconds)
          _      <- ffA.shutdown
          sibRes <- ffB.boolean("flag", default = false)
        } yield assertTrue(
          !shutA.get(),   // own provider left to the (still-alive) shared api
          !shutB.get(),   // sibling untouched
          sibRes == false // sibling still evaluable after ffA's shutdown
        )
      }
    } @@ withLiveClock,
    test("T4b: Owned override + domain — scope close DOES shut that (private) api's provider") {
      val shut = new AtomicBoolean(false)
      for {
        // The inner `ZIO.scoped` fully closes (running finalizers, including the api-shutdown one) before this
        // for-comprehension continues — so `shut.get()` below observes the post-close state deterministically.
        _ <- ZIO.scoped {
          FeatureFlags
            .fromProvider(
              new TrackingProvider("owned", shut),
              FeatureFlagsConfig(initMode = InitMode.Async)
                .withDomain(uniqueDomain("t4b"))
                .withApiOwnership(ApiOwnership.Owned),
              statusRef = None,
              apiOverride = Some(OpenFeatureAPI.createIsolated())
            )
            .build
            .flatMap(env => env.get[FeatureFlags].awaitReady(5.seconds))
        }
      } yield assertTrue(shut.get())
    } @@ withLiveClock,
    test("T5: withoutEvaluationTimeout — a provider slower than DefaultEvaluationTimeout does not time out") {
      for {
        provider <- TestFeatureProvider.make(Map("flag" -> true))
        _        <- provider.setDelay(FeatureFlags.DefaultEvaluationTimeout + 500.millis)
        domain = uniqueDomain("t5")
        config = FeatureFlagsConfig().withDomain(domain).withoutEvaluationTimeout
        result <- ZIO.scoped {
          FeatureFlags
            .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
            .build
            .flatMap(env => env.get[FeatureFlags].boolean("flag", default = false))
        }
      } yield assertTrue(result)
    } @@ withLiveClock
  )

  /** T6 (equivalence gate): every deprecated forward is a one-line call into the config path, so this suite pins that
    * forwarding itself — the ONE place in the codebase still allowed to call the deprecated overloads directly,
    * suppressed here rather than migrated because exercising the forwards *is* the test.
    */
  @scala.annotation.nowarn("cat=deprecation")
  private def t6EquivalenceSuite =
    suite("T6: deprecated forwards behave identically to their config-form equivalents")(
      test("fromProviderWithDomain(p, d, v) == fromProvider(p, config.withDomain(d).withVersion(v))") {
        for {
          providerA <- TestFeatureProvider.make(Map("flag" -> true))
          providerB <- TestFeatureProvider.make(Map("flag" -> true))
          domainA = uniqueDomain("t6-legacy")
          domainB = uniqueDomain("t6-config")
          legacy <- ZIO.scoped {
            FeatureFlags
              .fromProviderWithDomain(providerA, domainA, "9.9.9")
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                for {
                  meta <- ff.clientMetadata
                  flag <- ff.boolean("flag", default = false)
                } yield (meta.domain, meta.version, flag)
              }
          }
          viaConfig <- ZIO.scoped {
            FeatureFlags
              .fromProvider(providerB, FeatureFlagsConfig().withDomain(domainB).withVersion("9.9.9"))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                for {
                  meta <- ff.clientMetadata
                  flag <- ff.boolean("flag", default = false)
                } yield (meta.domain, meta.version, flag)
              }
          }
        } yield assertTrue(
          legacy._1.contains(domainA),
          viaConfig._1.contains(domainB),
          legacy._2 == viaConfig._2,
          legacy._3 == viaConfig._3
        )
      },
      test("fromProviderWithHooksAsync(p, hooks) == fromProvider(p, asyncConfig.withHooks(hooks))") {
        for {
          providerA <- TestFeatureProvider.make(Map("flag" -> true))
          providerB <- TestFeatureProvider.make(Map("flag" -> true))
          callsA    <- Ref.make(0)
          callsB    <- Ref.make(0)
          hookA = new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              callsA.update(_ + 1)
          }
          hookB = new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              callsB.update(_ + 1)
          }
          legacy <- ZIO.scoped {
            FeatureFlags.fromProviderWithHooksAsync(providerA, List(hookA)).build.flatMap { env =>
              val ff = env.get[FeatureFlags]
              ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds) *>
                ff.boolean("flag", default = false) *> callsA.get
            }
          }
          viaConfig <- ZIO.scoped {
            FeatureFlags
              .fromProvider(providerB, FeatureFlagsConfig(initMode = InitMode.Async).withHooks(List(hookB)))
              .build
              .flatMap { env =>
                val ff = env.get[FeatureFlags]
                ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds) *>
                  ff.boolean("flag", default = false) *> callsB.get
              }
          }
        } yield assertTrue(legacy == 1, viaConfig == 1, legacy == viaConfig)
      } @@ withLiveClock
    )

  // --- T6 equivalence helpers: build a layer in an isolated scope and extract the observable it pins ---

  private def awaitReady(ff: FeatureFlags): UIO[Any] =
    ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)

  // Build fails with Throwable, evaluation with FeatureFlagError (not a Throwable). Convert the evaluation error to a
  // Throwable INSIDE the effect (cross-build safe — no Scala 3 union type in source) so the outer `.orDie` type-checks.
  private def evalDefect(e: FeatureFlagError): Throwable = new RuntimeException(String.valueOf(e))

  private def metaFlag(
    layer: ZLayer[Scope, Throwable, FeatureFlags]
  ): UIO[(Option[String], Option[String], Boolean)] =
    ZIO.scoped {
      layer.build.flatMap { env =>
        val ff = env.get[FeatureFlags]
        (awaitReady(ff) *>
          ff.clientMetadata.zip(ff.boolean("flag", default = false)).map { case (m, v) => (m.domain, m.version, v) })
          .mapError(evalDefect)
      }
    }.orDie

  private def hookCalls(layer: ZLayer[Scope, Throwable, FeatureFlags], calls: Ref[Int]): UIO[Int] =
    ZIO.scoped {
      layer.build.flatMap { env =>
        val ff = env.get[FeatureFlags]
        (awaitReady(ff) *> ff.boolean("flag", default = false) *> calls.get).mapError(evalDefect)
      }
    }.orDie

  private def countingHook(calls: Ref[Int]): FeatureHook =
    new FeatureHook {
      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        calls.update(_ + 1)
    }

  /** Build a layer whose provider evaluates slowly and report whether the evaluation timed out. Distinguishes the
    * two-`Duration` forwards from an `evaluationTimeout`/`initTimeout` argument transposition: a short eval timeout
    * with a long init timeout must time out the (fast-to-init, slow-to-evaluate) provider; if the two were swapped in
    * the forward, the eval timeout would be the long one and this would return false.
    */
  private def evalTimesOut(layer: ZLayer[Scope, Throwable, FeatureFlags]): UIO[Boolean] =
    ZIO
      .scoped {
        layer.build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          awaitReady(ff) *> ff.boolean("flag", default = false).either
        }
      }
      .map {
        case Left(FeatureFlagError.ProviderError(u)) => u.isInstanceOf[TimeoutException]
        case _                                       => false
      }
      .orDie

  /** T6, part 2 (#253 review): the first two suite entries pin two representative forwards; these pin the remaining 12
    * so every deprecated forward is covered, per the design's "each deprecated forward" gate.
    */
  @scala.annotation.nowarn("cat=deprecation")
  private def t6RemainingForwardsSuite =
    suite("T6b: the remaining deprecated forwards each behave identically to their config form")(
      test("domain/version forwards match config (WithDomain(p,d), WithDomainAsync(p,d), WithDomainAsync(p,d,v))") {
        for {
          p1 <- TestFeatureProvider.make(Map("flag" -> true))
          p2 <- TestFeatureProvider.make(Map("flag" -> true))
          p3 <- TestFeatureProvider.make(Map("flag" -> true))
          p4 <- TestFeatureProvider.make(Map("flag" -> true))
          p5 <- TestFeatureProvider.make(Map("flag" -> true))
          p6 <- TestFeatureProvider.make(Map("flag" -> true))
          dA = uniqueDomain("t6-wd"); dB   = uniqueDomain("t6-wd-cfg")
          dC = uniqueDomain("t6-wda"); dD  = uniqueDomain("t6-wda-cfg")
          dE = uniqueDomain("t6-wdav"); dF = uniqueDomain("t6-wdav-cfg")
          l1 <- metaFlag(FeatureFlags.fromProviderWithDomain(p1, dA))
          c1 <- metaFlag(FeatureFlags.fromProvider(p2, FeatureFlagsConfig().withDomain(dB)))
          l2 <- metaFlag(FeatureFlags.fromProviderWithDomainAsync(p3, dC))
          c2 <- metaFlag(FeatureFlags.fromProvider(p4, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(dD)))
          l3 <- metaFlag(FeatureFlags.fromProviderWithDomainAsync(p5, dE, "2.0.0"))
          c3 <- metaFlag(
            FeatureFlags
              .fromProvider(p6, FeatureFlagsConfig(initMode = InitMode.Async).withDomain(dF).withVersion("2.0.0"))
          )
        } yield assertTrue(
          l1._1.contains(dA) && c1._1.contains(dB) && l1._2 == c1._2 && l1._3 == c1._3,
          l2._1.contains(dC) && c2._1.contains(dD) && l2._2 == c2._2 && l2._3 == c2._3,
          l3._2.contains("2.0.0") && c3._2.contains("2.0.0") && l3._3 == c3._3
        )
      } @@ withLiveClock,
      test("hooks forward matches config (fromProviderWithHooks(p, hooks))") {
        for {
          pA     <- TestFeatureProvider.make(Map("flag" -> true))
          pB     <- TestFeatureProvider.make(Map("flag" -> true))
          cA     <- Ref.make(0)
          cB     <- Ref.make(0)
          legacy <- hookCalls(FeatureFlags.fromProviderWithHooks(pA, List(countingHook(cA))), cA)
          cfg    <- hookCalls(FeatureFlags.fromProvider(pB, FeatureFlagsConfig().withHooks(List(countingHook(cB)))), cB)
        } yield assertTrue(legacy == 1, cfg == 1)
      } @@ withLiveClock,
      test("evaluation-timeout forwards match config, incl. the two-Duration forms (transposition guard)") {
        val delay = 500.millis
        val evalT = 20.millis
        val initT = 30.seconds
        for {
          p1  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p1.setDelay(delay)
          p2  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p2.setDelay(delay)
          p3  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p3.setDelay(delay)
          p4  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p4.setDelay(delay)
          p5  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p5.setDelay(delay)
          p6  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p6.setDelay(delay)
          p7  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p7.setDelay(delay)
          p8  <- TestFeatureProvider.make(Map("flag" -> true)); _ <- p8.setDelay(delay)
          lo1 <- evalTimesOut(FeatureFlags.fromProvider(p1, evalT))
          co1 <- evalTimesOut(FeatureFlags.fromProvider(p2, FeatureFlagsConfig().withEvaluationTimeout(evalT)))
          lo2 <- evalTimesOut(FeatureFlags.fromProvider(p3, evalT, initT))
          co2 <- evalTimesOut(
            FeatureFlags.fromProvider(p4, FeatureFlagsConfig().withEvaluationTimeout(evalT).withInitTimeout(initT))
          )
          lo3 <- evalTimesOut(FeatureFlags.fromProviderAsync(p5, evalT))
          co3 <- evalTimesOut(
            FeatureFlags.fromProvider(p6, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(evalT))
          )
          lo4 <- evalTimesOut(FeatureFlags.fromProviderAsync(p7, evalT, initT))
          co4 <- evalTimesOut(
            FeatureFlags.fromProvider(
              p8,
              FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(evalT).withInitTimeout(initT)
            )
          )
        } yield assertTrue(lo1, co1, lo2, co2, lo3, co3, lo4, co4)
      } @@ withLiveClock,
      test("multi-provider forwards match config, incl. the strategy-carrying forms") {
        def pair = TestFeatureProvider.make(Map("flag" -> true)).zip(TestFeatureProvider.make(Map("flag" -> true)))
        for {
          ab1 <- pair; ab2 <- pair; ab3 <- pair; ab4 <- pair
          ab5 <- pair; ab6 <- pair; ab7 <- pair; ab8 <- pair
          l1  <- metaFlag(FeatureFlags.fromMultiProvider(List(ab1._1, ab1._2)))
          c1 <- metaFlag(
            FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(ab2._1, ab2._2)), FeatureFlagsConfig())
          )
          l2 <- metaFlag(FeatureFlags.fromMultiProvider(List(ab3._1, ab3._2), new FirstSuccessfulStrategy()))
          c2 <- metaFlag(
            FeatureFlags.fromProvider(
              FeatureFlags.multiProvider(List(ab4._1, ab4._2), new FirstSuccessfulStrategy()),
              FeatureFlagsConfig()
            )
          )
          l3 <- metaFlag(FeatureFlags.fromMultiProviderAsync(List(ab5._1, ab5._2)))
          c3 <- metaFlag(
            FeatureFlags
              .fromProvider(
                FeatureFlags.multiProvider(List(ab6._1, ab6._2)),
                FeatureFlagsConfig(initMode = InitMode.Async)
              )
          )
          l4 <- metaFlag(FeatureFlags.fromMultiProviderAsync(List(ab7._1, ab7._2), new FirstSuccessfulStrategy()))
          c4 <- metaFlag(
            FeatureFlags.fromProvider(
              FeatureFlags.multiProvider(List(ab8._1, ab8._2), new FirstSuccessfulStrategy()),
              FeatureFlagsConfig(initMode = InitMode.Async)
            )
          )
        } yield assertTrue(l1._3 == c1._3, l2._3 == c2._3, l3._3 == c3._3, l4._3 == c4._3)
      } @@ withLiveClock
    )

  def spec = (coreSuite + t6EquivalenceSuite + t6RemainingForwardsSuite) @@ TestAspect.sequential
}
