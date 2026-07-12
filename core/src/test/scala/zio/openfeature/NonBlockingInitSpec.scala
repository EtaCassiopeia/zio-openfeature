package zio.openfeature

import zio._
import zio.test._
import zio.stream.SubscriptionRef
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPIFactory,
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
      apiOverride = Some(OpenFeatureAPIFactory.create()),
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
              fbDown   <- ZIO.succeed(fbShutdowns.get())
            } yield assertTrue(
              swapped.contains(Right(true)),
              hooks.contains(hook),                     // hooks survive the swap
              ctx.getString("marker").contains("keep"), // context survives the swap
              createdN == 2,                            // first fallback + fresh fallback
              fbDown == 1                               // pre-swap fallback shut down exactly once
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
