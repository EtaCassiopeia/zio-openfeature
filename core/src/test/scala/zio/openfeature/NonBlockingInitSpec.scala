package zio.openfeature

import zio._
import zio.test._
import zio.stream.SubscriptionRef
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

object NonBlockingInitSpec extends ZIOSpecDefault {

  private def uniqueDomain(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  /** Always-READY provider answering a fixed boolean for every key; records shutdown() invocations. */
  private class FixedBoolProvider(nm: String, value: Boolean, shutdowns: AtomicInteger = new AtomicInteger(0))
      extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = nm }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); () }
    def shutdownCount: Int                                  = shutdowns.get()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(java.lang.Boolean.valueOf(value), "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  /** Provider whose initialize() blocks on a gate so the Java SDK never fires PROVIDER_READY — the status ref stays
    * whatever the test drives it to. The gate is released on shutdown() (invoked by the api-shutdown finalizer at scope
    * close), so no executor thread leaks.
    */
  private class InertProvider extends FeatureProvider {
    private val gate = new CountDownLatch(1)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "inert" }
    override def getState: ProviderState                    = ProviderState.NOT_READY
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = gate.countDown()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  private def buildControlled(
    ref: SubscriptionRef[ProviderStatus],
    provider: FeatureProvider,
    onReady: Option[CountDownLatch] = None,
    initTimeout: Duration = 1.hour
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildAsync(
      provider,
      domain = Some(uniqueDomain("await")),
      version = None,
      initialHooks = Nil,
      statusRef = Some(ref),
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      onReady = onReady,
      initTimeout = initTimeout
    )

  def spec = suite("NonBlockingInitSpec")(
    // AC1
    test("fromAcquireAsync builds instantly and answers fallback while acquire never completes") {
      val acquire: RIO[Scope, FeatureProvider] =
        ZIO.sleep(10.minutes) *> ZIO.succeed(new FixedBoolProvider("real", true))
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      ZIO.scoped {
        FeatureFlags.fromAcquireAsync(acquire, fallback).build.map(_.get[FeatureFlags]).flatMap { ff =>
          for {
            status <- ff.providerStatus
            v      <- ff.boolean("x", true) // default true; fallback answers false, proving fallback is live
          } yield assertTrue(status == ProviderStatus.Ready, !v)
        }
      }
    },
    // AC2
    test("fromAcquireAsync: terminal acquire failure runs onConstructionError and stays on fallback") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        errP <- Promise.make[Nothing, Throwable]
        acquire: RIO[Scope, FeatureProvider] = ZIO.fail(new RuntimeException("boom"))
        result <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallback,
              constructionRetry = Schedule.recurs(2), // no delay, fail fast after 3 attempts
              onConstructionError = e => errP.succeed(e).unit
            )
            .build
            .map(_.get[FeatureFlags])
            .flatMap { ff =>
              for {
                err    <- errP.await
                status <- ff.providerStatus
                v      <- ff.boolean("x", true)
              } yield assertTrue(err.getMessage == "boom", status == ProviderStatus.Ready, !v)
            }
        }
      } yield result
    },
    // AC2b: swap failure (acquire succeeds, setProvider fails) must keep the instance usable on the fallback
    test("fromAcquireAsync: swap failure stays usable on the fallback with an evaluable status") {
      val fallback: URIO[Scope, FeatureProvider] = ZIO.succeed(new FixedBoolProvider("fb", false))
      val acquire: RIO[Scope, FeatureProvider]   = ZIO.succeed(new FixedBoolProvider("real", true))
      // compose returns a provider whose initialize() throws, so setProviderAndWait fails and rolls back to the fallback
      val badCompose: (FeatureProvider, FeatureProvider) => FeatureProvider = (_, _) =>
        new FixedBoolProvider("bad", true) {
          override def initialize(c: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
        }
      for {
        errP <- Promise.make[Nothing, Throwable]
        result <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback, compose = badCompose, onConstructionError = e => errP.succeed(e).unit)
            .build
            .map(_.get[FeatureFlags])
            .flatMap { ff =>
              for {
                _ <- errP.await
                // #282: setProvider's rollback now re-registers the fallback with the SDK, so evaluations route back to
                // the FALLBACK — not the failed provider. The fallback ("fb") returns false, so the evaluation must be
                // Right(false), not the failed provider's true. (Pre-#282 this could return the failed provider's
                // value; asserting the value, not just evaluability, is the original evidence case for this bug.)
                v <- ff.boolean("x", true).either
              } yield assertTrue(v == Right(false))
            }
        }
      } yield result
    },
    // AC3
    test(
      "fromAcquireAsync: successful swap preserves hooks and context, uses a fresh fallback, shuts the first fallback down once"
    ) {
      val fbShutdowns = new AtomicInteger(0)
      val created     = new AtomicInteger(0)
      val fallback = ZIO.succeed[FeatureProvider] {
        created.incrementAndGet()
        new FixedBoolProvider(s"fb-${created.get()}", false, fbShutdowns)
      }
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(new FixedBoolProvider("real", true))
      val hook: FeatureHook                    = new FeatureHook {}
      ZIO.scoped {
        FeatureFlags
          .fromAcquireAsync(acquire, fallback)
          .build
          .map(_.get[FeatureFlags])
          .flatMap { ff =>
            val marker = EvaluationContext.empty.withAttribute("marker", AttributeValue.StringValue("keep"))
            for {
              _ <- ff.addHook(hook)
              _ <- ff.setGlobalContext(marker)
              // Wait for the swap: the composed stack answers the real value (true) for the default-false call. The eval
              // transiently fails with ProviderNotReady during the swap window, so tolerate that (`.either`) and retry.
              swapped <- Live.live(
                ff.boolean("x", false).either.repeatUntil(_ == Right(true)).timeout(30.seconds)
              )
              hooks    <- ff.hooks
              ctx      <- ff.globalContext
              createdN <- ZIO.succeed(created.get())
              // The SDK's ProviderRepository.shutDownOld runs the old fallback's shutdown() asynchronously on its
              // task executor, so poll for the count rather than reading immediately (which could race it, #320).
              fbDown <- Live.live(ZIO.succeed(fbShutdowns.get()).repeatUntil(_ >= 1).timeout(5.seconds))
            } yield assertTrue(
              swapped.contains(Right(true)),
              hooks.contains(hook),                     // hooks survive the swap
              ctx.getString("marker").contains("keep"), // context survives the swap
              createdN == 2,                            // first fallback + fresh fallback
              fbDown.contains(1)                        // pre-swap fallback shut down exactly once
            )
          }
      }
    },
    // AC4
    test(
      "fromAcquireAsync: scope close tears down a real provider acquired but not yet swapped, interrupting acquire"
    ) {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        released  <- Ref.make(0)
        acquired  <- Promise.make[Nothing, Unit]
        releasedP <- Promise.make[Nothing, Unit]
        acquire: RIO[Scope, FeatureProvider] = ZIO
          .acquireRelease(acquired.succeed(()).as(new FixedBoolProvider("real", true): FeatureProvider))(_ =>
            released.update(_ + 1) *> releasedP.succeed(()).unit
          )
          .zipRight(ZIO.never)
        _ <- ZIO.scoped {
          FeatureFlags.fromAcquireAsync(acquire, fallback).build.map(_.get[FeatureFlags]).flatMap { _ =>
            acquired.await // return (and close the scope) once the real provider has been acquired
          }
        }
        // finalizer runs on the interrupted background fiber; await it (don't race scope-close). Bounded via
        // Live (the default TestClock is frozen) so a regression that skips the finalizer fails fast, not hangs.
        _ <- Live.live(
          releasedP.await.timeoutFail(new RuntimeException("AC4: release finalizer did not run"))(5.seconds)
        )
        r <- released.get
      } yield assertTrue(r == 1)
    },
    // #349 verify: runs against the bare candidate, before the swap, and gates it
    test("fromAcquireAsync: verify runs against the bare candidate before the swap and gates it") {
      val real                                 = new FixedBoolProvider("real", true)
      val fallback                             = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(real)
      for {
        seen    <- Ref.make(Option.empty[FeatureProvider])
        release <- Promise.make[Nothing, Unit]
        // The evaluation observed from INSIDE verify: the swap must not have happened yet.
        duringVerify <- Ref.make(Option.empty[Either[FeatureFlagError, Boolean]])
        ffPromise    <- Promise.make[Nothing, FeatureFlags]
        result <- ZIO.scoped {
          val verify = (p: FeatureProvider) =>
            seen.set(Some(p)) *>
              ffPromise.await.flatMap(ff => ff.boolean("x", true).either.flatMap(v => duringVerify.set(Some(v)))) *>
              release.await
          for {
            ff <- FeatureFlags.fromAcquireAsync(acquire, fallback, verify = verify).build.map(_.get[FeatureFlags])
            _  <- ffPromise.succeed(ff)
            // While verify is blocked the fallback still serves and status is Ready (edge case d).
            _       <- Live.live(seen.get.repeatUntil(_.isDefined).timeout(5.seconds))
            pre     <- ff.boolean("x", true)
            st      <- ff.providerStatus
            _       <- release.succeed(())
            swapped <- Live.live(ff.boolean("x", false).either.repeatUntil(_ == Right(true)).timeout(30.seconds))
            s       <- seen.get
            dv      <- duringVerify.get
          } yield assertTrue(
            s.exists(_ eq real),        // bare candidate, not the composed MultiProvider (edge case e)
            !pre,                       // fallback answered while verify was in flight
            st == ProviderStatus.Ready, // and status stayed Ready (no NotReady dip before the swap)
            dv == Some(Right(false)),   // evaluation issued from inside verify saw the fallback
            swapped.contains(Right(true))
          )
        }
      } yield result
    },
    // #349 verify failure = attempt failure: candidate released, retry advances, terminal error reported
    test(
      "fromAcquireAsync: verify failure releases the candidate, advances the retry, and reports the terminal error"
    ) {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        acquired <- Ref.make(0)
        released <- Ref.make(0)
        verified <- Ref.make(0)
        errP     <- Promise.make[Nothing, Throwable]
        acquire: RIO[Scope, FeatureProvider] = ZIO.acquireRelease(
          acquired.update(_ + 1).as(new FixedBoolProvider("real", true): FeatureProvider)
        )(_ => released.update(_ + 1))
        verify = (_: FeatureProvider) => verified.update(_ + 1) *> ZIO.fail(new RuntimeException("sentinel missing"))
        result <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallback,
              constructionRetry = Schedule.recurs(2), // 3 attempts, no delay
              onConstructionError = e => errP.succeed(e).unit,
              verify = verify
            )
            .build
            .map(_.get[FeatureFlags])
            .flatMap { ff =>
              for {
                err <- errP.await
                a   <- acquired.get
                r   <- released.get
                v   <- verified.get
                st  <- ff.providerStatus
                x   <- ff.boolean("x", true)
              } yield assertTrue(
                err.getMessage == "sentinel missing", // verify's error is what onConstructionError sees
                a == 3,
                v == 3, // every attempt was verified
                r == 3, // every rejected candidate was released BEFORE layer close
                st == ProviderStatus.Ready,
                !x // fallback still serving
              )
            }
        }
      } yield result
    },
    // #349: rejected candidates are released promptly; the winning one lives until layer close
    test("fromAcquireAsync: a candidate that passes verify after earlier rejections is swapped in and kept alive") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        attempts <- Ref.make(0)
        released <- Ref.make(0)
        acquire: RIO[Scope, FeatureProvider] = ZIO.acquireRelease(
          attempts.updateAndGet(_ + 1).map(n => new FixedBoolProvider(s"real-$n", true): FeatureProvider)
        )(_ => released.update(_ + 1))
        // Third candidate is the first to pass.
        verify = (p: FeatureProvider) =>
          ZIO
            .fail(new RuntimeException(s"reject ${p.getMetadata.getName}"))
            .unless(p.getMetadata.getName == "real-3")
            .unit
        liveCounts <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback, constructionRetry = Schedule.recurs(5), verify = verify)
            .build
            .map(_.get[FeatureFlags])
            .flatMap { ff =>
              for {
                swapped <- Live.live(ff.boolean("x", false).either.repeatUntil(_ == Right(true)).timeout(30.seconds))
                a       <- attempts.get
                r       <- released.get
              } yield (swapped, a, r)
            }
        }
        afterClose <- released.get
      } yield assertTrue(
        liveCounts._1.contains(Right(true)),
        liveCounts._2 == 3, // stopped retrying once verify passed
        liveCounts._3 == 2, // the two rejected candidates were released while the layer was live
        afterClose == 3     // the winner is released at layer close, not before
      )
    },
    // #349: the documented wiring — Verify.flagExists as the verify argument — end to end
    test("fromAcquireAsync: Verify.flagExists gates the swap on the sentinel flag") {
      // Answers FLAG_NOT_FOUND for every key until `known` is set, then answers `true` (like a provider whose config
      // has finally arrived).
      class LazyProvider(known: java.util.concurrent.atomic.AtomicBoolean) extends FixedBoolProvider("real", true) {
        override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
          if (known.get()) super.getBooleanEvaluation(k, d, c)
          else ProviderEvaluations.error(d, dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND, s"no $k")
      }
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        known <- ZIO.succeed(new java.util.concurrent.atomic.AtomicBoolean(false))
        errP  <- Promise.make[Nothing, Throwable]
        rejected <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(
              ZIO.succeed(new LazyProvider(known): FeatureProvider),
              fallback,
              constructionRetry = Schedule.recurs(1),
              onConstructionError = e => errP.succeed(e).unit,
              verify = Verify.flagExists[Boolean]("kill-switch")
            )
            .build
            .map(_.get[FeatureFlags])
            .flatMap(ff => errP.await.zip(ff.boolean("x", true)))
        }
        _ <- ZIO.succeed(known.set(true))
        swapped <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(
              ZIO.succeed(new LazyProvider(known): FeatureProvider),
              fallback,
              verify = Verify.flagExists[Boolean]("kill-switch")
            )
            .build
            .map(_.get[FeatureFlags])
            .flatMap(ff => Live.live(ff.boolean("x", false).either.repeatUntil(_ == Right(true)).timeout(30.seconds)))
        }
      } yield assertTrue(
        rejected._1 == Verify.VerificationFailed("kill-switch", ErrorCode.FlagNotFound, Some("no kill-switch")),
        !rejected._2,                 // stayed on the fallback
        swapped.contains(Right(true)) // sentinel resolves → swap lands
      )
    },
    // #349: layer release while verify is in flight closes the child scope exactly once
    test("fromAcquireAsync: layer release during verify releases the in-flight candidate exactly once") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        released  <- Ref.make(0)
        verifying <- Promise.make[Nothing, Unit]
        releasedP <- Promise.make[Nothing, Unit]
        acquire: RIO[Scope, FeatureProvider] =
          ZIO.acquireRelease(ZIO.succeed(new FixedBoolProvider("real", true): FeatureProvider))(_ =>
            released.update(_ + 1) *> releasedP.succeed(()).unit
          )
        verify = (_: FeatureProvider) => verifying.succeed(()) *> ZIO.never
        _ <- ZIO.scoped {
          FeatureFlags.fromAcquireAsync(acquire, fallback, verify = verify).build.map(_.get[FeatureFlags]).flatMap {
            _ => verifying.await // close the layer while verify is blocked
          }
        }
        _ <- Live.live(
          releasedP.await.timeoutFail(new RuntimeException("candidate was not released on layer close"))(5.seconds)
        )
        r <- released.get
      } yield assertTrue(r == 1)
    },
    // #349: a candidate whose swap fails is released promptly, not at layer close
    test("fromAcquireAsync: swap failure releases the accepted candidate before layer close") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val badCompose: (FeatureProvider, FeatureProvider) => FeatureProvider = (_, _) =>
        new FixedBoolProvider("bad", true) {
          override def initialize(c: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
        }
      for {
        released <- Ref.make(0)
        errP     <- Promise.make[Nothing, Throwable]
        acquire: RIO[Scope, FeatureProvider] =
          ZIO.acquireRelease(ZIO.succeed(new FixedBoolProvider("real", true): FeatureProvider))(_ =>
            released.update(_ + 1)
          )
        liveCount <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback, compose = badCompose, onConstructionError = e => errP.succeed(e).unit)
            .build
            .map(_.get[FeatureFlags])
            .flatMap(_ => errP.await *> released.get)
        }
        afterClose <- released.get
      } yield assertTrue(liveCount == 1, afterClose == 1)
    },
    // #349: acquire + verify share the per-attempt constructionTimeout
    test("fromAcquireAsync: verify is bounded by constructionTimeout") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        released <- Ref.make(0)
        errP     <- Promise.make[Nothing, Throwable]
        acquire: RIO[Scope, FeatureProvider] =
          ZIO.acquireRelease(ZIO.succeed(new FixedBoolProvider("real", true): FeatureProvider))(_ =>
            released.update(_ + 1)
          )
        verify = (_: FeatureProvider) => ZIO.never
        result <- Live.live {
          ZIO.scoped {
            FeatureFlags
              .fromAcquireAsync(
                acquire,
                fallback,
                constructionRetry = Schedule.recurs(1), // 2 attempts
                constructionTimeout = 200.millis,
                onConstructionError = e => errP.succeed(e).unit,
                verify = verify
              )
              .build
              .map(_.get[FeatureFlags])
              .flatMap { ff =>
                for {
                  err <- errP.await.timeoutFail(new RuntimeException("onConstructionError never ran"))(10.seconds)
                  r   <- released.get
                  x   <- ff.boolean("x", true)
                } yield assertTrue(
                  err.isInstanceOf[java.util.concurrent.TimeoutException],
                  err.getMessage.contains("exceeded"),
                  r == 2, // both timed-out candidates released
                  !x
                )
              }
          }
        }
      } yield result
    },
    // AC6 (a) many concurrent waiters
    test("awaitReady: many concurrent waiters all complete when status becomes Ready") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildControlled(ref, new InertProvider).flatMap { ff =>
            for {
              waiters <- ZIO.foreach(1 to 8)(_ => ff.awaitReady().fork)
              _       <- ref.set(ProviderStatus.Ready)
              results <- ZIO.foreach(waiters)(_.join)
            } yield assertTrue(results.forall(_ == ProviderStatus.Ready))
          }
        }
      } yield out
    },
    // AC6 (b) Fatal returns promptly
    test("awaitReady: returns Fatal when status transitions to Fatal") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildControlled(ref, new InertProvider).flatMap { ff =>
            ref.set(ProviderStatus.Fatal) *> ff.awaitReady().map(s => assertTrue(s == ProviderStatus.Fatal))
          }
        }
      } yield out
    },
    // AC6 (c) timeout returns the then-current status, no polling
    test("awaitReady: times out returning the then-current status") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildControlled(ref, new InertProvider).flatMap { ff =>
            for {
              fiber  <- ff.awaitReady(1.second).fork
              _      <- TestClock.adjust(1.second)
              status <- fiber.join
            } yield assertTrue(status == ProviderStatus.NotReady)
          }
        }
      } yield out
    },
    // AC7 watchdog Fatal releases onReady latch and is observable via awaitReady
    test("watchdog: Fatal transition releases the onReady latch and is observable via awaitReady") {
      val latch = new CountDownLatch(1)
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildControlled(ref, new InertProvider, onReady = Some(latch), initTimeout = 200.millis).flatMap { ff =>
            for {
              _      <- TestClock.adjust(200.millis)
              status <- ff.awaitReady(5.seconds)
              // The latch countdown happens after an attemptBlocking hop in the watchdog fiber, so poll for it rather
              // than reading immediately (which could race the still-dispatching countdown).
              count <- Live.live(ZIO.succeed(latch.getCount).repeatUntil(_ == 0L).timeout(5.seconds))
            } yield assertTrue(status == ProviderStatus.Fatal, count.contains(0L))
          }
        }
      } yield out
    }
  ) @@ TestAspect.sequential
}
