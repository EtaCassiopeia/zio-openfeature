package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.{ManagedProvider, ProviderEvaluations}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import java.util.concurrent.{CountDownLatch, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.ClassTag

/** Who shuts a provider down when a build, an init deadline or a timed-out swap fails on it: the library only for a
  * registration of its own that nothing else on the API is serving from. A provider the user registered with the API
  * stays where it is, and the SDK shuts it down once, when its slot lets go of it.
  */
object ProviderOwnershipShutdownSpec extends ZIOSpecDefault {

  private class Gated(failure: Option[String] = None, open: Boolean = false) extends FeatureProvider {
    val shutdowns = new AtomicInteger(0)
    val gate      = new CountDownLatch(if (open) 0 else 1)

    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "gated" }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      gate.await()
      failure.foreach(msg => throw new GeneralError(msg))
    }
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); gate.countDown() }

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

  private def syncBuild(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    domain: Option[String],
    initTimeout: Duration,
    owns: Boolean = false
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      provider,
      domain = domain,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owns,
      apiOverride = Some(api),
      initTimeout = initTimeout
    )

  private def asyncBuild(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    domain: String,
    initTimeout: Duration,
    onReady: Option[CountDownLatch] = None
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildAsync(
      provider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = false,
      apiOverride = Some(api),
      onReady = onReady,
      initTimeout = initTimeout
    )

  private def failedWith[E <: Throwable](exit: Exit[Throwable, Any])(implicit tag: ClassTag[E]): Boolean =
    exit.causeOption.flatMap(_.failureOption).exists(tag.runtimeClass.isInstance)

  private def awaitFatal(ff: FeatureFlagsLive): UIO[Option[ProviderStatus]] =
    Live.live(ff.providerStatus.repeatUntil(_ == ProviderStatus.Fatal).timeout(5.seconds))

  private def orphansLeft(ff: FeatureFlagsLive): UIO[Boolean] =
    ff.endState.violations.map(_.exists(_.startsWith("orphans left")))

  private def orphansSettled(ff: FeatureFlagsLive): UIO[Boolean] =
    Live.live(orphansLeft(ff).repeatUntil(!_).timeout(10.seconds)).map(_.isDefined)

  // Waits long enough for a shutdown on its way to land: a watchdog runs its effects after the status moved.
  private def shutdownsAfterSettling(provider: Gated): UIO[Int] =
    Live.live(
      ZIO.succeed(provider.shutdowns.get()).repeatUntil(_ > 0).timeout(500.millis).map(_.getOrElse(0))
    )

  // A sibling's build in flight on `domain`, holding `provider`'s wrapper in its slot while the provider initializes.
  private def siblingBuilding(
    api: OpenFeatureAPI,
    provider: Gated,
    domain: String
  ): UIO[Fiber[Throwable, ProviderStatus]] =
    for {
      fiber <- ZIO.scoped(syncBuild(api, provider, Some(domain), 30.seconds).flatMap(_.providerStatus)).fork
      _     <- Live.live(
        ZIO
          .succeed(ManagedProvider.existing(provider).exists(w => api.getProvider(domain) eq w))
          .repeatUntil(identity)
          .timeout(5.seconds)
      )
    } yield fiber

  private val userRegistered = suite("a provider the user registered with the API")(
    test("a build whose wait times out fails and leaves it in its slot") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _     <- ZIO.succeed(api.setProvider(provider))
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
        held  <- ZIO.succeed(api.getProvider() eq provider)
        _     <- ZIO.succeed(provider.gate.countDown())
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, held)
    },
    test("the same when the user registered it in the build's own slot") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _     <- ZIO.succeed(api.setProvider("d", provider))
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
        held  <- ZIO.succeed(api.getProvider("d") eq provider)
        _     <- ZIO.succeed(provider.gate.countDown())
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, held)
    },
    test("a build fails with the provider's error when its initialize() throws during the wait, and leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"))
      for {
        _     <- ZIO.succeed(api.setProvider(provider))
        fiber <- ZIO.scoped(syncBuild(api, provider, Some("d"), 10.seconds)).exit.fork
        // Nothing shows the build has reached its wait: an unbound domain reads the default slot, which holds it.
        _     <- Live.live(ZIO.sleep(300.millis))
        _     <- ZIO.succeed(provider.gate.countDown())
        exit  <- fiber.join
        count <- ZIO.succeed(provider.shutdowns.get())
        state <- ZIO.succeed(api.getClient("d").getProviderState)
        held  <- ZIO.succeed(api.getProvider() eq provider)
      } yield assertTrue(failedWith[GeneralError](exit), count == 0, state == ProviderState.ERROR, held)
    },
    test("a build on one that had already failed fails without shutting it down") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"), open = true)
      for {
        // Fails by design: this is the state the build then finds.
        _     <- ZIO.attemptBlocking(api.setProviderAndWait(provider)).ignore
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 5.seconds)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
      } yield assertTrue(failedWith[GeneralError](exit), count == 0)
    },
    test("an init deadline makes the instance Fatal, publishes the error, releases the latch, and leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      val latch    = new CountDownLatch(1)
      for {
        _   <- ZIO.succeed(api.setProvider(provider))
        out <- ZIO.scoped {
          asyncBuild(api, provider, "d", 1.second, Some(latch)).flatMap { ff =>
            for {
              errors <- Ref.make(0)
              _      <- ff.on(ProviderEventType.Error, _ => errors.update(_ + 1))
              _      <- TestClock.adjust(2.seconds)
              status <- awaitFatal(ff)
              seen   <- Live.live(errors.get.repeatUntil(_ > 0).timeout(5.seconds))
              count  <- shutdownsAfterSettling(provider)
            } yield assertTrue(
              status.contains(ProviderStatus.Fatal),
              seen.isDefined,
              latch.getCount == 0L,
              count == 0,
              api.getProvider() eq provider
            )
          }
        }
        _ <- ZIO.succeed(provider.gate.countDown())
      } yield out
    },
    test("after a failed build the SDK shuts it down exactly once, with the API") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _    <- ZIO.succeed(api.setProvider(provider))
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        _    <- ZIO.succeed(provider.gate.countDown())
        _    <- ZIO.attemptBlocking(api.shutdown())
      } yield assertTrue(exit.isFailure, provider.shutdowns.get() == 1)
    },
    test("a swap off it that times out and then fails leaves it while the default slot holds it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(swap.isFailure, pending, settled, count == 0)
      }
    },
    test("the same when no slot holds it any more still shuts it down: nothing else would") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- ZIO.attemptBlocking(api.setProviderAndWait("d", provider))
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
          // At least once, not exactly: the SDK's task for the build's own binding may run after the swap rebinds the slot,
          // and then shuts it down too, which a provider with no wrapper cannot absorb.
        } yield assertTrue(swap.isFailure, pending, settled, count >= 1)
      }
    }
  )

  private val sharedWrapper = suite("the library's wrapper of a provider a sibling domain is initializing")(
    test("a build whose wait times out fails and leaves the sibling to finish") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        sibling <- siblingBuilding(api, provider, "a")
        exit    <- ZIO.scoped(syncBuild(api, provider, Some("b"), 300.millis)).exit
        count   <- ZIO.succeed(provider.shutdowns.get())
        _       <- ZIO.succeed(provider.gate.countDown())
        status  <- sibling.join
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, status == ProviderStatus.Ready)
    },
    test("an init deadline leaves the sibling to finish") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        sibling <- siblingBuilding(api, provider, "a")
        out     <- ZIO.scoped {
          asyncBuild(api, provider, "b", 1.second).flatMap { ff =>
            for {
              _      <- TestClock.adjust(2.seconds)
              status <- awaitFatal(ff)
              count  <- shutdownsAfterSettling(provider)
            } yield assertTrue(status.contains(ProviderStatus.Fatal), count == 0)
          }
        }
        _      <- ZIO.succeed(provider.gate.countDown())
        status <- sibling.join
      } yield out && assertTrue(status == ProviderStatus.Ready)
    }
  )

  private val ownRegistration = suite("the library's own registration, which nothing else serves from")(
    test("a build whose wait times out shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
      } yield assertTrue(failedWith[TimeoutException](exit), provider.shutdowns.get() == 1)
    },
    test("a build whose provider threw shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"), open = true)
      for {
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 5.seconds)).exit
      } yield assertTrue(failedWith[GeneralError](exit), provider.shutdowns.get() == 1)
    },
    test("an init deadline shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      ZIO.scoped {
        asyncBuild(api, provider, "d", 1.second).flatMap { ff =>
          for {
            _      <- TestClock.adjust(2.seconds)
            status <- awaitFatal(ff)
            count  <- shutdownsAfterSettling(provider)
          } yield assertTrue(status.contains(ProviderStatus.Fatal), count == 1)
        }
      }
    },
    test("a sibling whose scope has closed no longer counts: a build that times out shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _      <- ZIO.scoped(asyncBuild(api, provider, "a", 1.hour))
        before <- ZIO.succeed(provider.shutdowns.get())
        exit   <- ZIO.scoped(syncBuild(api, provider, Some("b"), 300.millis)).exit
      } yield assertTrue(before == 0, failedWith[TimeoutException](exit), provider.shutdowns.get() == 1)
    },
    test("two siblings that fail together on an API with no owner shut it down once between them") {
      val api                   = OpenFeatureAPI.createIsolated()
      val provider              = new Gated(failure = Some("boom"))
      def build(domain: String) = ZIO.scoped(syncBuild(api, provider, Some(domain), 10.seconds)).exit.fork
      for {
        a <- build("a")
        b <- build("b")
        _ <- Live.live(
          ZIO
            .succeed(
              ManagedProvider.existing(provider).exists(w => (api.getProvider("a") eq w) && (api.getProvider("b") eq w))
            )
            .repeatUntil(identity)
            .timeout(5.seconds)
        )
        _     <- ZIO.succeed(provider.gate.countDown())
        exitA <- a.join
        exitB <- b.join
      } yield assertTrue(exitA.isFailure, exitB.isFailure, provider.shutdowns.get() == 1)
    },
    test("two siblings that both reach their deadline shut it down once, and the API's shutdown adds nothing") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        count <- ZIO.scoped {
          for {
            a     <- asyncBuild(api, provider, "a", 1.second)
            b     <- asyncBuild(api, provider, "b", 1.second)
            _     <- TestClock.adjust(2.seconds)
            _     <- awaitFatal(a)
            _     <- awaitFatal(b)
            count <- shutdownsAfterSettling(provider)
          } yield count
        }
        _ <- ZIO.succeed(provider.gate.countDown())
        _ <- ZIO.attemptBlocking(api.shutdown())
      } yield assertTrue(count == 1, provider.shutdowns.get() == 1)
    }
  )

  def spec = suite("ProviderOwnershipShutdownSpec")(userRegistered, sharedWrapper, ownRegistration) @@
    TestAspect.sequential @@ TestAspect.timeout(120.seconds)
}
