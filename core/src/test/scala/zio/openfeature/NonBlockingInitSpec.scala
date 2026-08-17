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
    // #352: Constructing until the swap, Live after; onSwapped runs once Live is observable
    test("acquireStatus: Constructing until the swap, Live after, onSwapped runs once Live is observable") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        gate          <- Promise.make[Nothing, Unit]
        seenInSwapped <- Promise.make[Nothing, AcquireState]
        acquire: RIO[Scope, FeatureProvider] = gate.await.as(new FixedBoolProvider("real", true): FeatureProvider)
        result <- ZIO.scoped {
          for {
            statusP <- Promise.make[Nothing, AcquireStatus]
            env <- FeatureFlags
              .fromAcquireAsync(
                acquire,
                fallback,
                onSwapped = statusP.await.flatMap(_.get).flatMap(seenInSwapped.succeed(_)).unit
              )
              .build
            ff     = env.get[FeatureFlags]
            status = env.get[AcquireStatus]
            _      <- statusP.succeed(status)
            before <- status.get
            v0     <- ff.boolean("x", true)
            _      <- gate.succeed(())
            live   <- Live.live(status.changes.filter(_ == AcquireState.Live).runHead.timeout(30.seconds))
            inCb   <- Live.live(seenInSwapped.await.timeout(10.seconds))
            after  <- status.get
            v1     <- Live.live(ff.boolean("x", false).either.repeatUntil(_ == Right(true)).timeout(30.seconds))
          } yield assertTrue(
            before == AcquireState.Constructing,
            !before.isLive,
            !v0, // fallback serving while Constructing
            live.flatten == Some(AcquireState.Live),
            inCb == Some(AcquireState.Live), // onSwapped observes Live via `get`
            after == AcquireState.Live,
            after.isLive,
            v1.contains(Right(true))
          )
        }
      } yield result
    },
    // #352: verify rejections and retries do not leave Constructing; Live only once acquired AND verified AND swapped
    test("acquireStatus: stays Constructing across verify rejections and retries, then Live") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        attempts <- Ref.make(0)
        seen     <- Ref.make(List.empty[AcquireState])
        acquire: RIO[Scope, FeatureProvider] =
          attempts.updateAndGet(_ + 1).map(n => new FixedBoolProvider(s"real-$n", true): FeatureProvider)
        result <- ZIO.scoped {
          for {
            statusP <- Promise.make[Nothing, AcquireStatus]
            verify = (p: FeatureProvider) =>
              statusP.await.flatMap(_.get).flatMap(s => seen.update(s :: _)) *>
                ZIO.fail(new RuntimeException("reject")).unless(p.getMetadata.getName == "real-3").unit
            env <- FeatureFlags
              .fromAcquireAsync(acquire, fallback, constructionRetry = Schedule.recurs(5), verify = verify)
              .build
            status = env.get[AcquireStatus]
            _      <- statusP.succeed(status)
            live   <- Live.live(status.changes.filter(_ == AcquireState.Live).runHead.timeout(30.seconds))
            states <- seen.get
            n      <- attempts.get
          } yield assertTrue(
            live.flatten == Some(AcquireState.Live),
            n == 3,
            states.length == 3,
            states.forall(_ == AcquireState.Constructing) // every verify (incl. the passing one) ran while Constructing
          )
        }
      } yield result
    },
    // #352: terminal failure → Failed(cause), set before onConstructionError, and terminal
    test("acquireStatus: terminal acquire failure sets Failed(cause) before onConstructionError and stays Failed") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val boom     = new RuntimeException("boom")
      for {
        inCb <- Promise.make[Nothing, (Throwable, AcquireState)]
        acquire: RIO[Scope, FeatureProvider] = ZIO.fail(boom)
        result <- ZIO.scoped {
          for {
            statusP <- Promise.make[Nothing, AcquireStatus]
            env <- FeatureFlags
              .fromAcquireAsync(
                acquire,
                fallback,
                constructionRetry = Schedule.recurs(2),
                onConstructionError = e => statusP.await.flatMap(_.get).flatMap(s => inCb.succeed((e, s))).unit
              )
              .build
            status = env.get[AcquireStatus]
            _   <- statusP.succeed(status)
            cb  <- inCb.await
            now <- status.get
            // Give any hypothetical "retry back to Constructing" a chance to happen; Failed must be terminal.
            later <- Live.live(ZIO.sleep(200.millis) *> status.get)
            v     <- env.get[FeatureFlags].boolean("x", true)
          } yield assertTrue(
            cb._1 eq boom,
            cb._2 == AcquireState.Failed(boom), // already Failed inside onConstructionError
            now == AcquireState.Failed(boom),
            later == AcquireState.Failed(boom),
            !now.isLive,
            !v // fallback still serving
          )
        }
      } yield result
    },
    // #352: verified-but-swap-failed → Failed, and onConstructionError still fires (compat)
    test("acquireStatus: swap failure sets Failed and still fires onConstructionError") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val badCompose: (FeatureProvider, FeatureProvider) => FeatureProvider = (_, _) =>
        new FixedBoolProvider("bad", true) {
          override def initialize(c: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
        }
      for {
        errP <- Promise.make[Nothing, Throwable]
        acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(new FixedBoolProvider("real", true))
        result <- ZIO.scoped {
          for {
            env <- FeatureFlags
              .fromAcquireAsync(
                acquire,
                fallback,
                compose = badCompose,
                onConstructionError = e => errP.succeed(e).unit
              )
              .build
            err   <- errP.await
            state <- env.get[AcquireStatus].get
          } yield assertTrue(
            err.getMessage.startsWith("Provider swap failed"),
            err.isInstanceOf[ProviderSwapFailed], // structured cause survives, not just a message
            state == AcquireState.Failed(err)
          )
        }
      } yield result
    },
    // #352: verify rejecting on EVERY attempt → Failed(verify's error) once the schedule is exhausted
    test("acquireStatus: verify failing on every attempt sets Failed with verify's error") {
      val fallback                             = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val verifyErr                            = new RuntimeException("verify always rejects")
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(new FixedBoolProvider("real", true))
      for {
        errP <- Promise.make[Nothing, Throwable]
        result <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallback,
              constructionRetry = Schedule.recurs(2),
              onConstructionError = e => errP.succeed(e).unit,
              verify = (_: FeatureProvider) => ZIO.fail(verifyErr)
            )
            .build
            .flatMap { env =>
              for {
                err   <- errP.await
                state <- env.get[AcquireStatus].get
              } yield assertTrue(err eq verifyErr, state == AcquireState.Failed(verifyErr))
            }
        }
      } yield result
    },
    // #352: a DEFECT in construction (not a typed failure) still resolves to Failed and reaches onConstructionError,
    // instead of killing the fiber with the state stuck at Constructing forever
    test("acquireStatus: a defect during acquire sets Failed(cause) and fires onConstructionError") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val boom     = new IllegalStateException("ctor boom")
      // `ZIO.succeed` does not catch — this is a Die, the shape a caller gets by not using attempt/attemptBlocking.
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed[FeatureProvider](throw boom)
      for {
        errP <- Promise.make[Nothing, Throwable]
        result <- ZIO.scoped {
          FeatureFlags
            .fromAcquireAsync(acquire, fallback, onConstructionError = e => errP.succeed(e).unit)
            .build
            .flatMap { env =>
              for {
                err <- Live
                  .live(errP.await.timeoutFail(new RuntimeException("onConstructionError never ran"))(10.seconds))
                outcome <- Live.live(
                  env.get[AcquireStatus].changes.filter(_ != AcquireState.Constructing).runHead.timeout(10.seconds)
                )
                v <- env.get[FeatureFlags].boolean("x", true)
              } yield assertTrue(
                err eq boom,
                outcome.flatten == Some(
                  AcquireState.Failed(boom)
                ), // the documented "wait for the outcome" idiom returns
                !v // fallback still serving
              )
            }
        }
      } yield result
    },
    // #352: changes emits the current state first, then transitions — a late subscriber is not left waiting
    test("acquireStatus: changes emits the current state first and then transitions") {
      val fallback = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      for {
        gate <- Promise.make[Nothing, Unit]
        acquire: RIO[Scope, FeatureProvider] = gate.await.as(new FixedBoolProvider("real", true): FeatureProvider)
        result <- ZIO.scoped {
          for {
            env <- FeatureFlags.fromAcquireAsync(acquire, fallback).build
            status = env.get[AcquireStatus]
            // Early subscriber: `changes` hands over the current value on subscribe, so the first `take` proves the
            // subscription is attached (gate still closed → Constructing) before the transition is triggered.
            q      <- Queue.unbounded[AcquireState]
            _      <- status.changes.runForeach(q.offer).forkScoped
            first  <- q.take
            _      <- gate.succeed(())
            second <- Live.live(q.take.timeout(30.seconds))
            // Late subscriber (after Live): first element is the CURRENT state, not a wait for the next change.
            late <- Live.live(status.changes.runHead.timeout(10.seconds))
          } yield assertTrue(
            first == AcquireState.Constructing,
            second == Some(AcquireState.Live),
            late.flatten == Some(AcquireState.Live)
          )
        }
      } yield result
    },
    // #352: the layer provides BOTH services, and the widened output still ascribes to the old type
    test(
      "acquireStatus: layer provides FeatureFlags and AcquireStatus and still ascribes to URLayer[Scope, FeatureFlags]"
    ) {
      val fallback                             = ZIO.succeed[FeatureProvider](new FixedBoolProvider("fb", false))
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(new FixedBoolProvider("real", true))
      val widened: URLayer[Scope, FeatureFlags with AcquireStatus] = FeatureFlags.fromAcquireAsync(acquire, fallback)
      val narrowed: URLayer[Scope, FeatureFlags]                   = widened // source compatibility (covariant ROut)
      val program = for {
        _   <- ZIO.service[FeatureFlags]
        st  <- AcquireStatus.get
        st2 <- ZIO.serviceWithZIO[AcquireStatus](_.changes.runHead)
      } yield assertTrue(
        st == AcquireState.Constructing || st == AcquireState.Live,
        st2.isDefined
      )
      program.provideSome[Scope](widened) *>
        ZIO.serviceWith[FeatureFlags](_ => assertCompletes).provideSome[Scope](narrowed)
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
