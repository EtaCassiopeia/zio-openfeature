package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._

object FeatureFlagRegistrySpec extends ZIOSpecDefault {

  private class SimpleProvider(name: String, flags: Map[String, Any]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = name
    }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "STATIC")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluations.of[String](v, "STATIC")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "STATIC")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "STATIC")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] =
      ProviderEvaluations.of[Value](defaultValue, "STATIC")
  }

  // Equal to every other instance, as a provider comparing configuration might be.
  final private class EqualProvider(inits: java.util.concurrent.atomic.AtomicInteger)
      extends SimpleProvider("Equal", Map("flag" -> true)) {
    override def initialize(ctx: OFEvaluationContext): Unit = { inits.incrementAndGet(); () }
    override def equals(other: Any): Boolean                = other.isInstanceOf[EqualProvider]
    override def hashCode: Int                              = 1
  }

  private def makeRegistry(defaultProvider: SimpleProvider): ZIO[Scope, Throwable, FeatureFlagRegistry] =
    RegistryRecording.registryProbe.flatMap { probe =>
      FeatureFlagRegistry
        .fromProvider(defaultProvider, FeatureFlagsConfig.default, probe)
        .build
        .map(_.get[FeatureFlagRegistry])
    }

  // Poll an AtomicInteger until it reaches `target` (or a live-time bound), so assertions on the SDK's async
  // provider shutdown (dispatched to its own executor at registry release) don't race a fixed sleep.
  private def awaitCount(counter: java.util.concurrent.atomic.AtomicInteger, target: Int): UIO[Int] =
    Live
      .live(
        ZIO
          .succeed(counter.get())
          .repeat(Schedule.recurUntil((_: Int) >= target) && Schedule.spaced(10.millis))
          .timeout(5.seconds)
      )
      .as(counter.get())

  /** A domain's client build fails, then the user registers a second provider there, which fails too or starts. Returns
    * how often each provider was shut down once the registry has closed.
    */
  private def shutdownsAfterAFailedBuild(failsToo: Boolean): UIO[(Int, Int)] = {
    val firstShut  = new java.util.concurrent.atomic.AtomicInteger(0)
    val secondShut = new java.util.concurrent.atomic.AtomicInteger(0)
    def counted(name: String, shut: java.util.concurrent.atomic.AtomicInteger, fails: Boolean) =
      new SimpleProvider(name, Map.empty) {
        override def initialize(ctx: OFEvaluationContext): Unit =
          if (fails) throw new RuntimeException(s"$name failed to start")
        override def shutdown(): Unit = { shut.incrementAndGet(); () }
      }
    ZIO.scoped {
      for {
        registry <- makeRegistry(new SimpleProvider("Default", Map.empty))
        _        <- registry.setProvider("d", counted("First", firstShut, fails = true))
        _        <- registry.getClient("d").either
        _        <- registry.setProvider("d", counted("Second", secondShut, fails = failsToo))
        _        <- registry.getClient("d").either
      } yield ()
    }.orDie *> awaitCount(firstShut, 1) *> awaitCount(secondShut, 1) *>
      // Room for a second shutdown of the first provider, were one coming.
      Live.live(ZIO.sleep(200.millis)) *> ZIO.succeed((firstShut.get(), secondShut.get()))
  }

  def spec = suite("FeatureFlagRegistry")(
    test("getClient returns a working client using default provider") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map("flag" -> true))
        for {
          registry <- makeRegistry(provider)
          client   <- registry.getClient("billing")
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("setProvider then getClient uses the registered provider") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> false))
        val billingProvider = new SimpleProvider("Billing", Map("flag" -> true))
        for {
          registry <- makeRegistry(defaultProvider)
          _        <- registry.setProvider("billing", billingProvider)
          client   <- registry.getClient("billing")
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("setProvider hot-swaps an existing client") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
        val providerB = new SimpleProvider("B", Map("flag" -> false))
        for {
          registry <- makeRegistry(providerA)
          client   <- registry.getClient("domain1")
          v1       <- client.boolean("flag", default = false)
          _        <- registry.setProvider("domain1", providerB)
          v2       <- client.boolean("flag", default = true)
        } yield assertTrue(v1 == true, v2 == false)
      }
    },
    test("getClient caches — returns same instance") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map.empty)
        for {
          registry <- makeRegistry(provider)
          c1       <- registry.getClient("domain1")
          c2       <- registry.getClient("domain1")
        } yield assertTrue(c1 eq c2)
      }
    },
    test("defaultClient returns a working no-domain client") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map("flag" -> true))
        for {
          registry <- makeRegistry(provider)
          client   <- registry.defaultClient
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("multiple domains with different providers") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val billingProvider = new SimpleProvider("Billing", Map("flag" -> "billing"))
        val authProvider    = new SimpleProvider("Auth", Map("flag" -> "auth"))
        for {
          registry <- makeRegistry(defaultProvider)
          _        <- registry.setProvider("billing", billingProvider)
          _        <- registry.setProvider("auth", authProvider)
          billing  <- registry.getClient("billing")
          auth     <- registry.getClient("auth")
          other    <- registry.getClient("other")
          vBilling <- billing.string("flag", default = "none")
          vAuth    <- auth.string("flag", default = "none")
          vOther   <- other.string("flag", default = "none")
        } yield assertTrue(
          vBilling == "billing",
          vAuth == "auth",
          vOther == "default"
        )
      }
    },
    test("defaultClient is cached") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map.empty)
        for {
          registry <- makeRegistry(provider)
          c1       <- registry.defaultClient
          c2       <- registry.defaultClient
        } yield assertTrue(c1 eq c2)
      }
    },
    test("setProvider on domain without existing client registers for later use") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val customProvider  = new SimpleProvider("Custom", Map("flag" -> "custom"))
        for {
          registry <- makeRegistry(defaultProvider)
          _        <- registry.setProvider("late", customProvider)
          client   <- registry.getClient("late")
          value    <- client.string("flag", default = "none")
          meta     <- client.providerMetadata
        } yield assertTrue(value == "custom", meta.name == "Custom")
      }
    },
    test("failed setProvider does not update providers map") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("init failed")
        }
        val recoveryProvider = new SimpleProvider("Recovery", Map("flag" -> "recovered"))
        for {
          registry <- makeRegistry(defaultProvider)
          client   <- registry.getClient("domain1")
          v1       <- client.string("flag", default = "none")
          result   <- registry.setProvider("domain1", failingProvider).either
          // After a failed swap the rollback re-registers the previous provider with the SDK, so the client
          // routes back to it (status Ready, evaluations return the old provider's values) rather than being stranded
          // on the failed provider.
          status <- client.providerStatus
          v2     <- client.string("flag", default = "none")
          // Recover with a working provider
          _  <- registry.setProvider("domain1", recoveryProvider)
          v3 <- client.string("flag", default = "none")
        } yield assertTrue(
          v1 == "default",
          result.is(_.left).isInstanceOf[FeatureFlagError],
          status == ProviderStatus.Ready,
          v2 == "default",
          v3 == "recovered"
        )
      }
    },
    test("getClient for one domain is not blocked by a slow provider init in another") {
      ZIO.scoped {
        val slowStarted     = new java.util.concurrent.CountDownLatch(1)
        val slowGate        = new java.util.concurrent.CountDownLatch(1)
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "fast"))
        val slowProvider    = new SimpleProvider("Slow", Map("flag" -> "slow")) {
          override def initialize(ctx: OFEvaluationContext): Unit = {
            slowStarted.countDown()
            slowGate.await() // hold the build until the fast domain has been served
          }
        }
        for {
          registry  <- makeRegistry(defaultProvider)
          _         <- registry.setProvider("slow", slowProvider)
          slowFiber <- registry.getClient("slow").fork
          // Ensure the slow build is actually in flight before asking for the fast domain
          _    <- ZIO.attemptBlocking(slowStarted.await()).orDie
          fast <- registry.getClient("fast") // must complete while "slow" is still initializing
          v    <- fast.string("flag", default = "none")
          _    <- ZIO.succeed(slowGate.countDown())
          slow <- slowFiber.join
          v2   <- slow.string("flag", default = "none")
        } yield assertTrue(v == "fast", v2 == "slow")
      }
    },
    test("one provider bound to three domains is one registration: one wrapper, one init, one shutdown") {
      val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
      val shuts    = new java.util.concurrent.atomic.AtomicInteger(0)
      val provider = new SimpleProvider("Shared", Map("flag" -> true)) {
        override def initialize(ctx: OFEvaluationContext): Unit = { inits.incrementAndGet(); () }
        override def shutdown(): Unit                           = { shuts.incrementAndGet(); () }
      }
      for {
        during <- ZIO.scoped {
          for {
            registry <- makeRegistry(provider)
            clients  <- ZIO.foreach(List("shared-a", "shared-b", "shared-c"))(registry.getClient)
            values   <- ZIO.foreach(clients)(_.boolean("flag", default = false))
            wrapper  <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.existing(provider))
          } yield (values, wrapper.map(_.initsStarted), inits.get(), shuts.get())
        }
        after <- awaitCount(shuts, 1)
        // A second shutdown would land on the SDK's executor too: give it the same window before reading again.
        _ <- Live.live(ZIO.sleep(300.millis))
      } yield assertTrue(
        during._1 == List(true, true, true),
        during._2.contains(1L),
        during._3 == 1,
        during._4 == 0,
        after == 1,
        shuts.get() == 1
      )
    },
    test("two equal but distinct providers on two domains are two registrations, each initialized once") {
      // The SDK matches a non-domain-scoped provider by equals; the library's wrappers are equal only to themselves.
      val initsA = new java.util.concurrent.atomic.AtomicInteger(0)
      val initsB = new java.util.concurrent.atomic.AtomicInteger(0)
      val a      = new EqualProvider(initsA)
      val b      = new EqualProvider(initsB)
      ZIO.scoped {
        for {
          registry <- makeRegistry(new SimpleProvider("Default", Map.empty))
          _        <- registry.setProvider("equal-a", a)
          _        <- registry.setProvider("equal-b", b)
          ca       <- registry.getClient("equal-a")
          cb       <- registry.getClient("equal-b")
          va       <- ca.boolean("flag", default = false)
          vb       <- cb.boolean("flag", default = false)
          wa       <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.existing(a))
          wb       <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.existing(b))
        } yield assertTrue(
          a == b,
          va,
          vb,
          initsA.get() == 1,
          initsB.get() == 1,
          wa.isDefined,
          wb.isDefined,
          !wa.exists(x => wb.exists(_ eq x))
        )
      }
    },
    test("concurrent getClient calls for the same domain build the client exactly once") {
      ZIO.scoped {
        val initCount = new java.util.concurrent.atomic.AtomicInteger(0)
        val provider  = new SimpleProvider("Counting", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit = {
            initCount.incrementAndGet()
            Thread.sleep(50) // widen the race window
          }
        }
        for {
          registry <- makeRegistry(provider)
          clientsR <- ZIO.collectAllPar((1 to 8).map(_ => registry.getClient("same")))
        } yield assertTrue(
          initCount.get() == 1,
          clientsR.distinct.size == 1
        )
      }
    },
    test("failed init surfaces as a typed error and a later getClient can retry") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("boom")
        }
        val goodProvider = new SimpleProvider("Good", Map("flag" -> "good"))
        for {
          registry <- makeRegistry(defaultProvider)
          _        <- registry.setProvider("retryable", failingProvider)
          first    <- registry.getClient("retryable").either
          // The failed build is not memoized: register a working provider and retry
          _      <- registry.setProvider("retryable", goodProvider)
          client <- registry.getClient("retryable")
          v      <- client.string("flag", default = "none")
        } yield assertTrue(
          first.isLeft,
          first.left.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          v == "good"
        )
      }
    },
    suite("a failed client build releases its provider")(
      test("even when the provider registered next in its domain fails too") {
        shutdownsAfterAFailedBuild(failsToo = true).map { case (first, second) =>
          assertTrue(first == 1, second == 1)
        }
      },
      test("once, when the provider registered next in its domain starts") {
        shutdownsAfterAFailedBuild(failsToo = false).map { case (first, second) =>
          assertTrue(first == 1, second == 1)
        }
      }
    ) @@ TestAspect.withLiveClock,
    test("provider metadata reflects the domain provider") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map.empty)
        val billingProvider = new SimpleProvider("Billing", Map.empty)
        for {
          registry <- makeRegistry(defaultProvider)
          _        <- registry.setProvider("billing", billingProvider)
          client   <- registry.getClient("billing")
          meta     <- client.providerMetadata
        } yield assertTrue(meta.name == "Billing")
      }
    },
    test("getClient does not hang when registration throws synchronously; failure is typed and retryable") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        // Throws synchronously when the registry reads its metadata / registers it with the SDK — the
        // buildAsync defect path (getMetadata / api.setProvider), NOT an async initialize() failure.
        val throwingProvider = new SimpleProvider("Throwing", Map.empty) {
          @scala.annotation.nowarn("msg=deprecated")
          override def getMetadata: Metadata =
            throw new RuntimeException("metadata boom")
        }
        val goodProvider = new SimpleProvider("Good", Map("flag" -> "good"))
        for {
          registry <- makeRegistry(defaultProvider)
          // Live clock so the regression guard (a hang) is bounded in real time; the registry's own
          // readiness poll uses blocking sleeps, so the default frozen TestClock would never fire timeout.
          result <- Live.live(
            (for {
              _      <- registry.setProvider("bad", throwingProvider)
              first  <- registry.getClient("bad").exit
              _      <- registry.setProvider("bad", goodProvider)
              client <- registry.getClient("bad")
              v      <- client.string("flag", default = "none")
            } yield (first, v)).timeout(15.seconds)
          )
        } yield result match {
          case Some((first, v)) =>
            assertTrue(
              first.isFailure,
              first match {
                case Exit.Failure(cause) =>
                  cause.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed])
                case _ => false
              },
              v == "good"
            )
          case None =>
            assertTrue(false) // timed out → getClient hung (regression)
        }
      }
    },
    test("domain provider stays live during use and is shut down only at registry release") {
      // An earlier report claimed the provider is torn down "during setup, while still in use". In fact the registry
      // shuts registered providers only when its scope closes (release). This test snapshots the shutdown count INSIDE
      // the scope (must be 0 — provider live throughout use) and again AFTER the scope closes (must be 1 — shut
      // exactly once at release). Reading the count via an effect (not a lazily-rendered `assertTrue` expression)
      // is essential: a smart-assertion `shutCount.get()` is evaluated at result-render time, after the finalizer
      // has already run, which is what made the original reproduction misread teardown as a setup-time shutdown.
      val shutCount       = new java.util.concurrent.atomic.AtomicInteger(0)
      val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
      val p               = new SimpleProvider("A", Map("flag" -> "a")) {
        override def shutdown(): Unit = { shutCount.incrementAndGet(); () }
      }
      for {
        duringUse <- ZIO.scoped {
          for {
            registry <- makeRegistry(defaultProvider)
            _        <- registry.setProvider("a", p)
            client   <- registry.getClient("a")
            v        <- client.string("flag", default = "none")
            _        <- Live.live(ZIO.sleep(300.millis)) // window for any (erroneous) async teardown to land
            snap     <- ZIO.succeed(shutCount.get())
          } yield (v, snap)
        }
        afterRelease <- awaitCount(shutCount, 1) // release-time shutdown lands async on the SDK executor
      } yield assertTrue(duringUse._1 == "a", duringUse._2 == 0, afterRelease == 1)
    },
    test("domain providers stay live during use — two domains, shut only at release") {
      val shutA           = new java.util.concurrent.atomic.AtomicInteger(0)
      val shutB           = new java.util.concurrent.atomic.AtomicInteger(0)
      val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
      val pa              = new SimpleProvider("A", Map("flag" -> "a")) {
        override def shutdown(): Unit = { shutA.incrementAndGet(); () }
      }
      val pb = new SimpleProvider("B", Map("flag" -> "b")) {
        override def shutdown(): Unit = { shutB.incrementAndGet(); () }
      }
      for {
        duringUse <- ZIO.scoped {
          for {
            registry <- makeRegistry(defaultProvider)
            _        <- registry.setProvider("a", pa)
            _        <- registry.setProvider("b", pb)
            ca       <- registry.getClient("a")
            cb       <- registry.getClient("b")
            va       <- ca.string("flag", default = "none")
            vb       <- cb.string("flag", default = "none")
            _        <- Live.live(ZIO.sleep(300.millis))
            snap     <- ZIO.succeed((shutA.get(), shutB.get()))
          } yield (va, vb, snap)
        }
        afterA <- awaitCount(shutA, 1)
        afterB <- awaitCount(shutB, 1)
      } yield assertTrue(
        duringUse._1 == "a",
        duringUse._2 == "b",
        duringUse._3 == (0, 0),
        afterA == 1,
        afterB == 1
      )
    },
    test("getClient fails with a timeout when the provider is not ready within initTimeout") {
      val latch = new java.util.concurrent.CountDownLatch(1)
      val slow  = new SimpleProvider("Slow", Map.empty) {
        override def initialize(ctx: OFEvaluationContext): Unit = latch.await()
      }
      ZIO.scoped {
        for {
          probe    <- RegistryRecording.registryProbe
          registry <- FeatureFlagRegistry
            .fromProvider(
              new SimpleProvider("Default", Map.empty),
              FeatureFlagsConfig.default.withInitTimeout(300.millis),
              probe
            )
            .build
            .map(_.get[FeatureFlagRegistry])
          _ <- registry.setProvider("slow", slow)
          // The frozen TestClock keeps the build's init watchdog still: only the readiness poll can end the wait.
          client <- registry.getClient("slow").exit.fork
          exit   <- Live.live(client.join.timeout(10.seconds))
          _      <- ZIO.succeed(latch.countDown())
          cause = exit.flatMap(_.causeOption).flatMap(_.failureOption).collect {
            case FeatureFlagError.ProviderInitializationFailed(t) => t
          }
        } yield assertTrue(
          cause.exists(_.isInstanceOf[java.util.concurrent.TimeoutException]),
          cause.map(_.getMessage).contains("Provider initialization exceeded PT0.3S")
        )
      }
    },
    test("a domain whose build fails leaves the registry's API and its other domains serving") {
      val shutdowns       = new java.util.concurrent.atomic.AtomicInteger(0)
      val defaultProvider = new SimpleProvider("Default", Map("flag" -> "served")) {
        override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
      }
      val failing = new SimpleProvider("Bad", Map.empty) {
        override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
      }
      ZIO.scoped {
        for {
          registry <- makeRegistry(defaultProvider)
          healthy  <- registry.getClient("healthy")
          _        <- registry.setProvider("bad", failing)
          bad      <- registry.getClient("bad").either
          status   <- healthy.providerStatus
          value    <- healthy.string("flag", "fallback")
          other    <- registry.getClient("other")
          fresh    <- other.string("flag", "fallback")
          count    <- ZIO.succeed(shutdowns.get())
        } yield assertTrue(
          bad.left.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          status == ProviderStatus.Ready,
          value == "served",
          fresh == "served",
          count == 0
        )
      }
    }
  ) @@ RegistryRecording.eachTest("registry")
}
