package zio.openfeature

import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit, TimeoutException}

import dev.openfeature.sdk.OpenFeatureAPI
import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures.Counting
import zio.openfeature.internal.{FeatureFlagsFactories, InitDeadline, ManagedProvider}
import zio.openfeature.testkit.ScriptedInitDeadline

object InitDeadlineSpec extends ZIOSpecDefault {

  // Long enough that only a fired deadline can time a swap out.
  private val LongBound = 30.seconds

  private def build(
    api: OpenFeatureAPI,
    provider: Counting,
    deadline: InitDeadline = InitDeadline.default,
    initTimeout: Duration = LongBound
  ) =
    FeatureFlagsFactories.build(
      provider,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      initTimeout = initTimeout,
      initDeadline = deadline
    )

  // initialize() returns at once the first time, and waits for `gate` every later time: a rollback's re-registration.
  final private class StallsOnReinit extends Counting() {
    override def initialize(ctx: dev.openfeature.sdk.EvaluationContext): Unit =
      if (inits.incrementAndGet() > 1) gate.await()
  }

  private def raw(api: OpenFeatureAPI) = api.getProvider() match {
    case m: ManagedProvider => m.delegate
    case other              => other
  }

  private def awaitArmed(deadline: ScriptedInitDeadline, provider: Counting) =
    ZIO
      .succeed(deadline.armed.contains(provider))
      .repeat(Schedule.spaced(10.millis) *> Schedule.recurUntil[Boolean](identity))
      .timeoutFail("the swap's wait was never armed")(5.seconds)

  private def awaitShutdowns(provider: Counting, n: Int) =
    ZIO
      .succeed(provider.shutdowns.get())
      .repeat(Schedule.spaced(10.millis) *> Schedule.recurUntil[Int](_ >= n))
      .timeoutFail(s"fewer than $n shutdowns")(5.seconds)

  def spec = suite("InitDeadline")(
    suite("the default")(
      test("times an outcome still pending out with a TimeoutException, after the bound") {
        for {
          outcome <- ZIO.succeed(new CompletableFuture[Unit]())
          armed   <- ZIO.succeed(InitDeadline.default.arm(outcome, ManagedProvider(new Counting()), 100.millis))
          failure <- ZIO.attemptBlocking(armed.get(5, TimeUnit.SECONDS)).flip
        } yield assertTrue(failure.isInstanceOf[ExecutionException], failure.getCause.isInstanceOf[TimeoutException])
      },
      test("leaves an outcome that completes in time as it is") {
        for {
          outcome <- ZIO.succeed(new CompletableFuture[Unit]())
          armed   <- ZIO.succeed(InitDeadline.default.arm(outcome, ManagedProvider(new Counting()), 5.seconds))
          _       <- ZIO.succeed(outcome.complete(()))
          value   <- ZIO.attemptBlocking(armed.get(5, TimeUnit.SECONDS))
        } yield assertTrue(value == (()), !armed.isCompletedExceptionally)
      },
      test("still fails a swap whose provider outlives initTimeout, with the same message") {
        ZIO.scoped {
          for {
            api     <- ZIO.succeed(OpenFeatureAPI.createIsolated())
            first   <- ZIO.succeed(new Counting(open = true))
            ff      <- build(api, first, initTimeout = 300.millis)
            hanging <- ZIO.succeed(new Counting())
            failure <- ff.setProvider(hanging).flip
            _       <- ZIO.succeed(hanging.gate.countDown())
          } yield assertTrue(
            failure.isInstanceOf[FeatureFlagError.ProviderInitializationFailed],
            failure.asInstanceOf[FeatureFlagError.ProviderInitializationFailed].underlying.getMessage ==
              "Provider initialization exceeded PT0.3S"
          )
        }
      } @@ TestAspect.withLiveClock
    ),
    suite("a scripted deadline")(
      test("times a swap out when fired: no rollback, Error, and the replaced provider kept until the late init") {
        ZIO.scoped {
          for {
            api              <- ZIO.succeed(OpenFeatureAPI.createIsolated())
            deadline         <- ZIO.succeed(new ScriptedInitDeadline)
            first            <- ZIO.succeed(new Counting(open = true))
            ff               <- build(api, first, deadline)
            stalled          <- ZIO.succeed(new Counting())
            swap             <- ff.setProvider(stalled).flip.fork
            _                <- awaitArmed(deadline, stalled)
            fired            <- ZIO.succeed(deadline.fire(stalled))
            failure          <- swap.join
            status           <- ff.providerStatus
            bound            <- ZIO.succeed(raw(api))
            shutBeforeSettle <- ZIO.succeed(first.shutdowns.get())
            _                <- ZIO.succeed(stalled.gate.countDown())
            shutsAfter       <- awaitShutdowns(first, 1)
          } yield assertTrue(
            fired,
            failure.isInstanceOf[FeatureFlagError.ProviderInitializationFailed],
            failure.asInstanceOf[FeatureFlagError.ProviderInitializationFailed].underlying.getMessage ==
              "Provider initialization exceeded PT30S",
            status == ProviderStatus.Error,
            bound eq stalled,
            shutBeforeSettle == 0,
            shutsAfter == 1
          )
        }
      } @@ TestAspect.withLiveClock,
      test("times a rollback out when fired: Error, the previous provider bound, the failed one kept until it settles") {
        ZIO.scoped {
          for {
            api                     <- ZIO.succeed(OpenFeatureAPI.createIsolated())
            deadline                <- ZIO.succeed(new ScriptedInitDeadline)
            first                   <- ZIO.succeed(new StallsOnReinit)
            ff                      <- build(api, first, deadline)
            failing                 <- ZIO.succeed(new Counting(failure = Some("boom"), open = true))
            swap                    <- ff.setProvider(failing).flip.fork
            _                       <- awaitArmed(deadline, first)
            fired                   <- ZIO.succeed(deadline.fire(first))
            failure                 <- swap.join
            status                  <- ff.providerStatus
            bound                   <- ZIO.succeed(raw(api))
            failingShutBeforeSettle <- ZIO.succeed(failing.shutdowns.get())
            _                       <- ZIO.succeed(first.gate.countDown())
            failingShutsAfter       <- awaitShutdowns(failing, 1)
          } yield assertTrue(
            fired,
            failure.isInstanceOf[FeatureFlagError.ProviderInitializationFailed],
            status == ProviderStatus.Error,
            bound eq first,
            failingShutBeforeSettle == 0,
            failingShutsAfter == 1
          )
        }
      } @@ TestAspect.withLiveClock,
      test("leaves a swap whose provider initializes before it is fired alone, and is then no longer armed") {
        ZIO.scoped {
          for {
            api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
            deadline <- ZIO.succeed(new ScriptedInitDeadline)
            first    <- ZIO.succeed(new Counting(open = true))
            ff       <- build(api, first, deadline)
            next     <- ZIO.succeed(new Counting(open = true))
            _        <- ff.setProvider(next)
            fired    <- ZIO.succeed(deadline.fire(next))
            status   <- ff.providerStatus
            bound    <- ZIO.succeed(raw(api))
            armed    <- ZIO.succeed(deadline.armed)
          } yield assertTrue(!fired, armed.isEmpty, status == ProviderStatus.Ready, bound eq next)
        }
      } @@ TestAspect.withLiveClock,
      test("fires nothing for a provider it never armed") {
        val deadline = new ScriptedInitDeadline
        assertTrue(!deadline.fire(new Counting()), deadline.armed.isEmpty)
      }
    )
  ) @@ TestAspect.timeout(60.seconds)
}
