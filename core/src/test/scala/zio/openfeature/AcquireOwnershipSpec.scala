package zio.openfeature

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.test._
import zio.openfeature.AcquireRecording.recorded
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, FeatureProvider, Metadata, Value}

import scala.jdk.CollectionConverters._

// The library shuts down every provider `acquire` returns, exactly once, and `acquire`'s scope closes after that. These
// count `shutdown()` calls, not finalizer runs: a finalizer that ran says nothing about the provider.
object AcquireOwnershipSpec extends ZIOSpecDefault {

  private type Trail = ConcurrentLinkedQueue[String]

  /** Counts `shutdown()` and records it on `trail`. `initialize()` throws `initError` or blocks on `initGate`, which
    * `shutdown()` releases so a discarded provider never strands an init thread.
    */
  private class Counting(
    name: String,
    trail: Trail = new ConcurrentLinkedQueue[String](),
    initGate: Option[CountDownLatch] = None,
    initError: Option[Throwable] = None
  ) extends EventProvider {
    val shutdowns   = new AtomicInteger(0)
    val initStarted = new CountDownLatch(1)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      initStarted.countDown()
      initError.foreach(e => throw e)
      initGate.foreach(_.await())
    }
    override def shutdown(): Unit = {
      shutdowns.incrementAndGet()
      trail.add(s"$name.shutdown")
      initGate.foreach(_.countDown())
      super.shutdown()
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(name, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  /** A stack that shuts down what it was composed from, as the default chain does. */
  private class Stack(real: FeatureProvider, fresh: FeatureProvider) extends Counting("stack") {
    override def shutdown(): Unit = {
      super.shutdown()
      real.shutdown()
      fresh.shutdown()
    }
  }

  private class Fallbacks {
    val created                              = new ConcurrentLinkedQueue[Counting]()
    val effect: URIO[Scope, FeatureProvider] = ZIO.succeed {
      val fb = new Counting("fb")
      created.add(fb)
      fb
    }
    def fresh: Counting = created.asScala.drop(1).head
  }

  private val bound = 10.seconds

  /** `real`, with a resource that records its close on `trail`. */
  private def withResource(trail: Trail, real: FeatureProvider): RIO[Scope, FeatureProvider] =
    ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed(trail.add("resource.closed"))).as(real)

  private val isolated = FeatureFlagsConfig.default.withIsolatedApi

  private val noRetry = AcquireConfig.default.withConstructionRetry(Schedule.stop)

  private def awaitLatch(latch: CountDownLatch, what: String): Task[Unit] =
    ZIO.attemptBlocking(latch.await(bound.toMillis, TimeUnit.MILLISECONDS)).flatMap { ok =>
      ZIO.unless(ok)(ZIO.fail(new RuntimeException(s"timed out waiting for $what"))).unit
    }

  private def awaitOutcome(env: ZEnvironment[FeatureFlags with AcquireStatus]): Task[AcquireState] =
    env
      .get[AcquireStatus]
      .changes
      .filter(_ != AcquireState.Constructing)
      .runHead
      .someOrFail(new RuntimeException("acquire state stream ended"))
      .timeoutFail(new RuntimeException("no acquire outcome"))(bound)

  /** `read`, once it reads `expected` or the bound passes. Then a short settle, so a second shutdown that would arrive
    * just after the first is seen.
    */
  private def settled(read: => Int, expected: Int): UIO[Int] =
    ZIO.succeed(read).repeatUntil(_ == expected).timeout(bound) *> ZIO.sleep(100.millis) *> ZIO.succeed(read)

  /** Builds the layer in its own scope and closes that scope after `during` has run. */
  private def buildAndRelease[A](
    layer: ULayer[FeatureFlags with FeatureFlagsAdmin with AcquireStatus]
  )(during: ZEnvironment[FeatureFlags with FeatureFlagsAdmin with AcquireStatus] => Task[A]): Task[A] =
    ZIO.scoped(layer.build.flatMap(during))

  /** The fiber running a close that has reached this scope's last-registered finalizer; join it to wait for the release
    * to finish. The sleep gives the close time to reach what it is waiting on.
    */
  private def beginRelease(scope: Scope.Closeable): Task[Fiber[Nothing, Unit]] = {
    val started = new CountDownLatch(1)
    for {
      _       <- scope.addFinalizer(ZIO.succeed(started.countDown()))
      closing <- scope.close(Exit.unit).fork
      _       <- awaitLatch(started, "the release")
      _       <- ZIO.sleep(100.millis)
    } yield closing
  }

  def spec = suite("AcquireOwnershipSpec")(
    suite("a normal release")(
      test("an owning instance shuts a bare provider down once") {
        val real = new Counting("real")
        recorded("owning-bare") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated)) { env =>
              awaitOutcome(env)
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("an owning instance shuts the provider down before a resource acquire registered closes") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val real    = new Counting("real", trail)
        val acquire = withResource(trail, real)
        recorded("owning-resource") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated)) { env =>
              awaitOutcome(env)
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count), trail = Some(trail.asScala.toList))
          } yield assertTrue(count == 1, trail.asScala.toList == List("real.shutdown", "resource.closed"))
        }
      },
      test("a stack still bound to a shared API is left to that API's owner, and the resource closes") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val real    = new Counting("real", trail)
        val acquire = withResource(trail, real)
        val config  = FeatureFlagsConfig.default.withIsolatedApi.withApiOwnership(ApiOwnership.Shared)
        recorded("shared-resource") { run =>
          for {
            _     <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, config))(awaitOutcome(_))
            count <- settled(real.shutdowns.get(), 0)
            _     <- run.end(shuts = List(real -> count), trail = Some(trail.asScala.toList))
          } yield assertTrue(count == 0, trail.asScala.toList == List("resource.closed"))
        }
      },
      test("a defect in onSwapped leaves the served provider and its resource alone until release") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val real    = new Counting("real", trail)
        val acquire = withResource(trail, real)
        val config  = noRetry.withOnSwapped(ZIO.die(new RuntimeException("onSwapped boom")))
        recorded("onswapped-defect") { run =>
          for {
            during <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
              awaitOutcome(env) *> ZIO.sleep(100.millis) *> ZIO.succeed(trail.asScala.toList)
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count), trail = Some(trail.asScala.toList))
          } yield assertTrue(
            during == Nil,
            count == 1,
            trail.asScala.toList == List("real.shutdown", "resource.closed")
          )
        }
      }
    ),
    suite("a release while the provider is on its way")(
      test("during compose: the stack, the real provider and the fresh fallback each once") {
        val real      = new Counting("real")
        val fallbacks = new Fallbacks
        val entered   = new CountDownLatch(1)
        val gate      = new CountDownLatch(1)
        val stack     = new java.util.concurrent.atomic.AtomicReference[Stack]()
        val config    = noRetry.withCompose { (r, f) =>
          entered.countDown()
          gate.await()
          val s = new Stack(r, f)
          stack.set(s)
          s
        }
        recorded("release-during-compose") { run =>
          for {
            scope <- Scope.make
            _ <- scope.extend[Any](run.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect, isolated, config).build)
            _ <- awaitLatch(entered, "compose")
            closing <- beginRelease(scope)
            _       <- ZIO.succeed(gate.countDown())
            _       <- closing.join
            count   <- settled(real.shutdowns.get(), 1)
            _       <- run.end(shuts = List(real -> count), stack = Some(stack.get.shutdowns.get()))
          } yield assertTrue(count == 1, stack.get.shutdowns.get() == 1, fallbacks.fresh.shutdowns.get() == 1)
        }
      },
      test("during initialize: the real provider once") {
        val gate      = new CountDownLatch(1)
        val real      = new Counting("real", initGate = Some(gate))
        val fallbacks = new Fallbacks
        recorded("release-during-initialize") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect, isolated)) { _ =>
              awaitLatch(real.initStarted, "initialize")
            }
            count <- settled(real.shutdowns.get(), 1)
            // The default compose's chain shuts the fresh fallback down with the stack.
            _ <- run.end(shuts = List(real -> count), stack = Some(fallbacks.fresh.shutdowns.get()))
          } yield assertTrue(count == 1)
        }
      },
      test("while the fresh fallback is being built: the real provider once") {
        val real                                   = new Counting("real")
        val first                                  = new Counting("fb")
        val entered                                = new CountDownLatch(2)
        val runs                                   = new AtomicInteger(0)
        val fallback: URIO[Scope, FeatureProvider] =
          ZIO.suspendSucceed {
            entered.countDown()
            if (runs.getAndIncrement() == 0) ZIO.succeed(first) else ZIO.never
          }
        recorded("release-during-fresh-fallback") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), fallback, isolated, noRetry))(_ =>
              awaitLatch(entered, "the fresh fallback")
            )
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("while a blocking constructor runs: the provider it returns later, once") {
        val entered = new CountDownLatch(1)
        val gate    = new CountDownLatch(1)
        val real    = new Counting("real")
        val acquire = ZIO.attemptBlocking {
          entered.countDown()
          gate.await()
          real: FeatureProvider
        }
        recorded("release-during-constructor") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, noRetry)) { _ =>
              awaitLatch(entered, "the constructor")
            }
            _     <- ZIO.succeed(gate.countDown())
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("racing the end of initialize, repeatedly: never zero and never two") {
        ZIO
          .foreach((1 to 300).toList) { round =>
            val gate = new CountDownLatch(1)
            val real = new Counting("real", initGate = Some(gate))
            recorded("race-end-of-initialize") { run =>
              buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated)) { _ =>
                awaitLatch(real.initStarted, "initialize") *>
                  ZIO.succeed(gate.countDown()) *>
                  ZIO.sleep((round % 4).millis)
              } *> ZIO.succeed(real.shutdowns.get()).repeatUntil(_ >= 1).timeout(bound).flatMap { _ =>
                run.end(shuts = List(real -> real.shutdowns.get()))
              }
            }.as(real)
          }
          .flatMap { reals =>
            ZIO
              .succeed(reals.forall(_.shutdowns.get() >= 1))
              .repeatUntil(identity)
              .timeout(bound) *> ZIO.sleep(200.millis).as(assertTrue(reals.map(_.shutdowns.get()).forall(_ == 1)))
          }
      }
    ),
    suite("a provider that does not make it into service")(
      test("initialize fails under the default compose, bare: once") {
        val real = new Counting("real", initError = Some(new RuntimeException("init boom")))
        recorded("init-fails-bare") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated))(env =>
              awaitOutcome(env).flatMap(state => settled(real.shutdowns.get(), 1).as(state))
            )
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1, state.isInstanceOf[AcquireState.Failed])
        }
      },
      test("initialize fails under the default compose: the fresh fallback once") {
        val real      = new Counting("real", initError = Some(new RuntimeException("init boom")))
        val fallbacks = new Fallbacks
        recorded("init-fails-fresh-fallback") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect, isolated))(awaitOutcome(_))
            count <- settled(fallbacks.fresh.shutdowns.get(), 1)
            _     <- run.end(stack = Some(count))
          } yield assertTrue(count == 1)
        }
      },
      test("initialize fails with compose returning the real provider alone: once") {
        val real   = new Counting("real", initError = Some(new RuntimeException("init boom")))
        val config = noRetry.withCompose((r, _) => r)
        recorded("init-fails-real-alone") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("initialize fails: the provider shuts down before a resource acquire registered closes") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val real    = new Counting("real", trail, initError = Some(new RuntimeException("init boom")))
        val acquire = withResource(trail, real)
        recorded("init-fails-resource") { run =>
          for {
            during <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, noRetry)) { env =>
              awaitOutcome(env) *> ZIO.sleep(100.millis) *> ZIO.succeed(trail.asScala.toList)
            }
            _ <- run.end(trail = Some(trail.asScala.toList))
          } yield assertTrue(during == List("real.shutdown", "resource.closed"))
        }
      },
      test("verify rejects, bare: once, before the outcome is observable") {
        val real   = new Counting("real")
        val config = noRetry.withVerify(_ => ZIO.fail(new RuntimeException("verify boom")))
        recorded("verify-rejects-bare") { run =>
          for {
            during <- buildAndRelease(
              run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated, config)
            ) { env =>
              awaitOutcome(env).as(real.shutdowns.get())
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(during == 1, count == 1)
        }
      },
      test("verify rejects a provider whose finalizer also calls shutdown: twice, the documented cost of that idiom") {
        val real    = new Counting("real")
        val acquire = ZIO.acquireRelease(ZIO.succeed(real: FeatureProvider))(p => ZIO.succeed(p.shutdown()))
        val config  = noRetry.withVerify(_ => ZIO.fail(new RuntimeException("verify boom")))
        recorded("verify-rejects-user-shuts", userShuts = true) { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            count <- settled(real.shutdowns.get(), 2)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 2)
        }
      },
      test("a retried attempt: the rejected provider once while the layer lives, the accepted one once at release") {
        val rejected = new Counting("rejected")
        val accepted = new Counting("accepted")
        val runs     = new AtomicInteger(0)
        val acquire  = ZIO.succeed((if (runs.getAndIncrement() == 0) rejected else accepted): FeatureProvider)
        val config   = AcquireConfig.default
          .withConstructionRetry(Schedule.recurs(1))
          .withVerify(p => ZIO.when(p eq rejected)(ZIO.fail(new RuntimeException("verify boom"))).unit)
        recorded("retry-after-rejection") { run =>
          for {
            during <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
              awaitOutcome(env).as((rejected.shutdowns.get(), accepted.shutdowns.get()))
            }
            count <- settled(accepted.shutdowns.get(), 1)
            _     <- run.end(shuts = List(rejected -> rejected.shutdowns.get(), accepted -> count))
          } yield assertTrue(during == ((1, 0)), count == 1, rejected.shutdowns.get() == 1)
        }
      },
      test("the attempt times out and the constructor returns later: once") {
        val gate    = new CountDownLatch(1)
        val real    = new Counting("real")
        val acquire = ZIO.attemptBlocking {
          gate.await()
          real: FeatureProvider
        }
        val config = noRetry.withConstructionTimeout(50.millis)
        recorded("timeout-then-late-arrival") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
              awaitOutcome(env) *> ZIO.succeed(gate.countDown()) *> settled(real.shutdowns.get(), 1)
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("a constructor returning long after its attempt timed out: once") {
        val gate    = new CountDownLatch(1)
        val real    = new Counting("real")
        val acquire = ZIO.attemptBlocking {
          gate.await()
          real: FeatureProvider
        }
        val config = noRetry.withConstructionTimeout(50.millis)
        recorded("late-arrival-after-release") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            _     <- ZIO.sleep(6.seconds)
            _     <- ZIO.succeed(gate.countDown())
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("a blocking constructor keeps its resource until it returns, then shuts down before the resource closes") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val gate    = new CountDownLatch(1)
        val real    = new Counting("real", trail)
        val acquire = ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed(trail.add("resource.closed"))) *>
          ZIO.attemptBlocking {
            gate.await()
            real: FeatureProvider
          }
        val config = noRetry.withConstructionTimeout(50.millis)
        recorded("late-arrival-resource") { run =>
          for {
            during <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
              awaitOutcome(env).as(trail.asScala.toList)
            }
            _ <- ZIO.succeed(gate.countDown())
            _ <- settled(trail.size(), 2)
            _ <- run.end(shuts = List(real -> real.shutdowns.get()), trail = Some(trail.asScala.toList))
          } yield assertTrue(during == Nil, trail.asScala.toList == List("real.shutdown", "resource.closed"))
        }
      },
      test("a timeout during a blocking verify keeps the provider and its resource until verify returns") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val gate    = new CountDownLatch(1)
        val real    = new Counting("real", trail)
        val acquire = withResource(trail, real)
        val config  = noRetry.withConstructionTimeout(50.millis).withVerify(_ => ZIO.attemptBlocking(gate.await()).unit)
        recorded("timeout-during-blocking-verify") { run =>
          (for {
            during <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
              awaitOutcome(env).as(trail.asScala.toList)
            }
            released <- ZIO.succeed(trail.asScala.toList)
            _        <- ZIO.succeed(gate.countDown())
            _        <- settled(trail.size(), 2)
            _        <- run.end(shuts = List(real -> real.shutdowns.get()), trail = Some(trail.asScala.toList))
          } yield assertTrue(
            during == Nil,
            released == Nil,
            trail.asScala.toList == List("real.shutdown", "resource.closed")
          )).ensuring(ZIO.succeed(gate.countDown()))
        }
      },
      test("a layer release during a blocking verify leaves the provider and its resource until verify returns") {
        val trail    = new ConcurrentLinkedQueue[String]()
        val gate     = new CountDownLatch(1)
        val verified = new CountDownLatch(1)
        val real     = new Counting("real", trail)
        val acquire  = withResource(trail, real)
        val config   = noRetry.withVerify { _ =>
          ZIO.attemptBlocking { verified.countDown(); gate.await() }.unit
        }
        recorded("release-during-blocking-verify") { run =>
          (for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { _ =>
              awaitLatch(verified, "verify")
            }
            released <- ZIO.succeed(trail.asScala.toList)
            _        <- ZIO.succeed(gate.countDown())
            _        <- settled(trail.size(), 2)
            _        <- run.end(shuts = List(real -> real.shutdowns.get()), trail = Some(trail.asScala.toList))
          } yield assertTrue(released == Nil, trail.asScala.toList == List("real.shutdown", "resource.closed")))
            .ensuring(ZIO.succeed(gate.countDown()))
        }
      },
      test("verify rejects: the provider shuts down once, then its resource closes") {
        val trail   = new ConcurrentLinkedQueue[String]()
        val real    = new Counting("real", trail)
        val acquire = withResource(trail, real)
        val config  = noRetry.withVerify(_ => ZIO.fail(new RuntimeException("verify boom")))
        recorded("verify-rejects-resource") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            _ <- settled(trail.size(), 2)
            _ <- run.end(shuts = List(real -> real.shutdowns.get()), trail = Some(trail.asScala.toList))
          } yield assertTrue(
            state.isInstanceOf[AcquireState.Failed],
            real.shutdowns.get() == 1,
            trail.asScala.toList == List("real.shutdown", "resource.closed")
          )
        }
      },
      test("verify does not run on a provider that arrived after its attempt was abandoned") {
        val gate     = new CountDownLatch(1)
        val real     = new Counting("real")
        val verified = new AtomicInteger(0)
        val acquire  = ZIO.attemptBlocking {
          gate.await()
          real: FeatureProvider
        }
        val config =
          noRetry.withConstructionTimeout(50.millis).withVerify(_ => ZIO.succeed(verified.incrementAndGet()).unit)
        recorded("late-arrival-not-verified") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            _     <- ZIO.succeed(gate.countDown())
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1, verified.get() == 0)
        }
      },
      // An abandoned attempt is interrupted once it is suspended, so its resource closes then, possibly after the
      // timeout is reported: an attempt not yet scheduled reads as running, and is interrupted by a later poll.
      test("an attempt suspended in an asynchronous call: its resource closes once it is interrupted") {
        val closed  = new AtomicInteger(0)
        val acquire = ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed(closed.incrementAndGet())) *>
          ZIO.never.as(new Counting("never"): FeatureProvider)
        val config = noRetry.withConstructionTimeout(50.millis)
        recorded("timeout-suspended") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            count <- settled(closed.get(), 1)
            _     <- run.end(closed = Some(count == 1))
          } yield assertTrue(state.isInstanceOf[AcquireState.Failed], count == 1)
        }
      },
      test("an attempt still running when the timeout fires: the timeout is reported, then its resource closes") {
        // Spins without suspending past the timeout, so the interrupt finds the attempt running, not suspended.
        def spin(d: Duration): Unit = {
          val end = java.lang.System.nanoTime() + d.toNanos
          while (java.lang.System.nanoTime() < end) ()
        }
        val closed  = new AtomicInteger(0)
        val acquire = ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed(closed.incrementAndGet())) *>
          ZIO.succeed(spin(150.millis)) *> ZIO.never.as(new Counting("never"): FeatureProvider)
        val config = noRetry.withConstructionTimeout(50.millis)
        recorded("timeout-running") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            count <- settled(closed.get(), 1)
            _     <- run.end(closed = Some(count == 1))
          } yield assertTrue(state.isInstanceOf[AcquireState.Failed], count == 1)
        }
      },
      suite("a hung attempt holds up neither the timeout nor the release")(
        {
          def holdsUpNothing(name: String)(acquire: CountDownLatch => RIO[Scope, FeatureProvider]) =
            test(name) {
              val gate   = new CountDownLatch(1)
              val config = noRetry.withConstructionTimeout(50.millis)
              recorded("hung-attempt") { run =>
                (for {
                  state <- buildAndRelease(
                    run.fromAcquireAsync(acquire(gate), new Fallbacks().effect, isolated, config)
                  )(awaitOutcome(_))
                  _ <- run.end()
                } yield assertTrue(state.isInstanceOf[AcquireState.Failed])).ensuring(ZIO.succeed(gate.countDown()))
              }
            }
          def stuck(gate: CountDownLatch): FeatureProvider = {
            var waited = false
            while (!waited)
              try {
                gate.await()
                waited = true
              } catch { case _: InterruptedException => () }
            new Counting("late")
          }
          Chunk(
            holdsUpNothing("a blocking constructor behind a timeout")(gate =>
              ZIO.attemptBlocking(stuck(gate)).timeoutFail(new RuntimeException("inner timeout"))(1.minute)
            ),
            holdsUpNothing("a thread-interrupting constructor that ignores the interrupt")(gate =>
              ZIO.attemptBlockingInterrupt(stuck(gate))
            ),
            holdsUpNothing("an uninterruptible acquire waiting on an asynchronous call")(_ =>
              ZIO.acquireRelease(ZIO.never.as(new Counting("never"): FeatureProvider))(_ => ZIO.unit)
            )
          )
        }: _*
      ),
      test("a finalizer that dies does not keep a failure from being recorded") {
        val real    = new Counting("real", initError = Some(new RuntimeException("init boom")))
        val acquire = ZIO
          .acquireRelease(ZIO.unit)(_ => ZIO.die(new RuntimeException("finalizer boom")))
          .as(real: FeatureProvider)
        recorded("dying-finalizer-init-fails") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, noRetry))(
              awaitOutcome(_)
            )
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(state.isInstanceOf[AcquireState.Failed], count == 1)
        }
      },
      test("a failed acquire whose finalizer dies is still retried") {
        val real    = new Counting("real")
        val runs    = new AtomicInteger(0)
        val acquire = ZIO.suspendSucceed {
          if (runs.getAndIncrement() == 0)
            ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new RuntimeException("finalizer boom"))) *>
              ZIO.fail(new RuntimeException("acquire boom"))
          else ZIO.succeed(real: FeatureProvider)
        }
        val config = AcquireConfig.default.withConstructionRetry(Schedule.recurs(1))
        recorded("dying-finalizer-retried") { run =>
          buildAndRelease(run.fromAcquireAsync(acquire, new Fallbacks().effect, isolated, config)) { env =>
            awaitOutcome(env).map(state => assertTrue(state == AcquireState.Live, runs.get() == 2))
          } <* run.end(shuts = List(real -> real.shutdowns.get()))
        }
      },
      test("the attempt times out during verify: once") {
        val real   = new Counting("real")
        val config = noRetry.withConstructionTimeout(50.millis).withVerify(_ => ZIO.never)
        recorded("timeout-during-verify") { run =>
          for {
            _ <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated, config))(
              awaitOutcome(_)
            )
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1)
        }
      },
      test("a setProvider wins the swap, bare: once") {
        val gate = new CountDownLatch(1)
        val real = new Counting("real", initGate = Some(gate))
        recorded("setprovider-wins") { run =>
          for {
            state <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), new Fallbacks().effect, isolated)) { env =>
              for {
                _     <- awaitLatch(real.initStarted, "initialize")
                _     <- env.get[FeatureFlagsAdmin].setProvider(new Counting("other"))
                _     <- ZIO.succeed(gate.countDown())
                state <- awaitOutcome(env)
                _     <- settled(real.shutdowns.get(), 1)
              } yield state
            }
            count <- settled(real.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(count == 1, state.isInstanceOf[AcquireState.Failed])
        }
      },
      test("compose throws: the real provider and the fresh fallback once each, state Failed") {
        val real      = new Counting("real")
        val fallbacks = new Fallbacks
        val config    = noRetry.withCompose((_, _) => throw new RuntimeException("compose boom"))
        recorded("compose-throws") { run =>
          for {
            outcome <- buildAndRelease(run.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect, isolated, config)) {
              env =>
                for {
                  state <- awaitOutcome(env)
                  count <- settled(real.shutdowns.get(), 1)
                } yield (state, count)
            }
            count <- settled(real.shutdowns.get(), 1)
            fresh <- settled(fallbacks.fresh.shutdowns.get(), 1)
            _     <- run.end(shuts = List(real -> count))
          } yield assertTrue(
            outcome._1.isInstanceOf[AcquireState.Failed],
            outcome._2 == 1,
            count == 1,
            fresh == 1
          )
        }
      }
    ),
    suite("the chain")(
      test("multiProvider whose member fails initialize: each member once") {
        val good   = new Counting("good")
        val failed = new Counting("bad", initError = Some(new RuntimeException("init boom")))
        val config = FeatureFlagsConfig.default.withIsolatedApi
        for {
          exit <- ZIO
            .scoped(FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(good, failed)), config).build)
            .exit
          g <- settled(good.shutdowns.get(), 1)
          b <- settled(failed.shutdowns.get(), 1)
        } yield assertTrue(exit.isFailure, g == 1, b == 1)
      },
      test("two shutdowns reach each member once") {
        val member = new Counting("member")
        val chain  = FeatureFlags.multiProvider(List(member))
        ZIO.attemptBlocking {
          chain.shutdown()
          chain.shutdown()
          assertTrue(member.shutdowns.get() == 1)
        }
      },
      test("a chain initialized again after a failed initialize is shut down again") {
        val flaky = new Counting("flaky") {
          private val calls                                       = new AtomicInteger(0)
          override def initialize(ctx: OFEvaluationContext): Unit =
            if (calls.getAndIncrement() == 0) throw new RuntimeException("init boom")
        }
        val chain = FeatureFlags.multiProvider(List(flaky))
        ZIO.attemptBlocking {
          val ctx = new dev.openfeature.sdk.ImmutableContext()
          scala.util.Try(chain.initialize(ctx))
          val afterFailure = flaky.shutdowns.get()
          chain.initialize(ctx)
          chain.shutdown()
          assertTrue(afterFailure == 1, flaky.shutdowns.get() == 2)
        }
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
}
