package zio.openfeature.testkit

import java.util.concurrent.CountDownLatch

import zio._
import zio.test._
import dev.openfeature.sdk.{
  EventDetails,
  FeatureProvider,
  ImmutableContext,
  MutableTrackingEventDetails,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  Value
}

object FaultyProviderSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  /** Call the provider method `op` targets, directly, as the SDK would. */
  @scala.annotation.nowarn("msg=deprecated")
  private def invoke(fp: FaultyProvider[FeatureProvider], op: Op): Any = op match {
    case Op.GetMetadata      => fp.getMetadata
    case Op.GetProviderHooks => fp.getProviderHooks
    case Op.GetState         => fp.getState
    case Op.Initialize       => fp.initialize(ctx)
    case Op.Shutdown         => fp.shutdown()
    case Op.IsDomainScoped   => fp.isDomainScoped()
    case Op.Track            => fp.track("event", ctx, new MutableTrackingEventDetails())
    case Op.EvaluateBoolean  => fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx)
    case Op.EvaluateString   => fp.getStringEvaluation("s", "default", ctx)
    case Op.EvaluateInteger  => fp.getIntegerEvaluation("i", Integer.valueOf(0), ctx)
    case Op.EvaluateLong     => fp.getLongEvaluation("l", java.lang.Long.valueOf(0L), ctx)
    case Op.EvaluateDouble   => fp.getDoubleEvaluation("d", java.lang.Double.valueOf(0.0), ctx)
    case Op.EvaluateObject   => fp.getObjectEvaluation("o", new Value("default"), ctx)
  }

  private def elapsedMillis[E, A](effect: IO[E, A]): IO[E, (A, Long)] =
    for {
      start <- Clock.nanoTime
      a     <- effect
      end   <- Clock.nanoTime
    } yield (a, (end - start) / 1000000L)

  def spec = suite("FaultyProviderSpec")(
    test("an op with no script delegates to the wrapped provider") {
      for {
        fp      <- FaultyProvider.scoped(Map("b" -> true, "s" -> "hello"), name = "faulty")
        boolean <- ZIO.attempt(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx).getValue)
        string  <- ZIO.attempt(fp.getStringEvaluation("s", "default", ctx).getValue)
        name    <- ZIO.attempt(fp.getMetadata.getName)
      } yield assertTrue(boolean.booleanValue(), string == "hello", name == "faulty")
    },
    test("Throw surfaces the scripted exception itself, on every op") {
      ZIO
        .foreach(Op.values) { op =>
          for {
            fp <- FaultyProvider.scoped()
            error = new IllegalStateException(s"boom-$op")
            _      <- fp.script(op, Fault.Throw(error))
            thrown <- ZIO.attempt(invoke(fp, op)).flip
            calls  <- fp.calls(op)
          } yield assertTrue(
            thrown eq error,
            calls.map(_.outcome) == Chunk(FaultyProvider.Outcome.Threw(error))
          )
        }
        .map(_.reduce(_ && _))
    },
    test("Delay delays the call by at least its duration, then applies andThen") {
      val error = new RuntimeException("after the delay")
      for {
        fp     <- FaultyProvider.scoped(Map("b" -> true))
        _      <- fp.script(Op.EvaluateBoolean, Fault.Delay(200.millis), Fault.Delay(100.millis, Fault.Throw(error)))
        first  <- elapsedMillis(ZIO.attemptBlocking(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx)))
        second <- elapsedMillis(ZIO.attemptBlocking(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx)).flip)
        boolean = first._1.getValue.booleanValue()
      } yield assertTrue(boolean, first._2 >= 200L, second._1 eq error, second._2 >= 100L)
    },
    test("HangUntilReleased is not opened by shutdown(), only by release") {
      for {
        fp     <- FaultyProvider.scoped()
        _      <- fp.script(Op.Initialize, Fault.HangUntilReleased)
        init   <- ZIO.attemptBlocking(fp.initialize(ctx)).fork
        _      <- fp.awaitEntered(Op.Initialize)
        _      <- ZIO.attemptBlocking(fp.shutdown())
        _      <- ZIO.sleep(200.millis)
        parked <- init.poll
        _      <- fp.release
        joined <- init.join.timeout(5.seconds)
        calls  <- fp.calls(Op.Initialize)
      } yield assertTrue(
        parked.isEmpty,
        joined.isDefined,
        calls.map(_.outcome) == Chunk(FaultyProvider.Outcome.Returned)
      )
    },
    test("HangUntilShutdown is opened by shutdown()") {
      for {
        fp     <- FaultyProvider.scoped()
        _      <- fp.script(Op.Initialize, Fault.HangUntilShutdown)
        init   <- ZIO.attemptBlocking(fp.initialize(ctx)).fork
        _      <- fp.awaitEntered(Op.Initialize)
        _      <- ZIO.sleep(100.millis)
        parked <- init.poll
        _      <- ZIO.attemptBlocking(fp.shutdown())
        joined <- init.join.timeout(5.seconds)
      } yield assertTrue(parked.isEmpty, joined.isDefined)
    },
    test("HangUntilShutdown is also opened by release") {
      for {
        fp     <- FaultyProvider.scoped(Map("b" -> true))
        _      <- fp.script(Op.EvaluateBoolean, Fault.HangUntilShutdown)
        eval   <- ZIO.attemptBlocking(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx)).fork
        _      <- fp.awaitEntered(Op.EvaluateBoolean)
        _      <- fp.release
        joined <- eval.join.timeout(5.seconds)
        value = joined.exists(_.getValue.booleanValue())
      } yield assertTrue(value)
    },
    test("Emit fires the event through the SDK, then applies andThen") {
      val api       = OpenFeatureAPI.createIsolated()
      val domain    = s"emit-${java.util.UUID.randomUUID()}"
      val delivered = new CountDownLatch(1)
      val error     = new RuntimeException("after the event")
      ZIO
        .scoped {
          for {
            fp <- FaultyProvider.scoped()
            _  <- ZIO.attemptBlocking(api.setProviderAndWait(domain, fp))
            _ <- ZIO.succeed(
              api.onProviderConfigurationChanged((_: EventDetails) => delivered.countDown())
            )
            _ <- fp.script(
              Op.EvaluateBoolean,
              Fault.Emit(
                OFProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
                ProviderEventDetails.builder().message("changed").build(),
                Fault.Throw(error)
              )
            )
            thrown <- ZIO.attempt(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx)).flip
            seen   <- ZIO.attemptBlocking(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
          } yield assertTrue(thrown eq error, seen)
        }
        .ensuring(ZIO.attemptBlocking(api.shutdown()).ignore)
    },
    test("a script is consumed call by call and its last fault repeats") {
      val error = new RuntimeException("first init fails")
      for {
        fp     <- FaultyProvider.scoped()
        _      <- fp.script(Op.Initialize, Fault.Throw(error), Fault.Succeed)
        first  <- ZIO.attempt(fp.initialize(ctx)).either
        second <- ZIO.attempt(fp.initialize(ctx)).either
        third  <- ZIO.attempt(fp.initialize(ctx)).either
      } yield assertTrue(first == Left(error), second.isRight, third.isRight)
    },
    test("an empty script resets the op to delegation") {
      for {
        fp     <- FaultyProvider.scoped()
        _      <- fp.script(Op.Initialize, Fault.Throw(new RuntimeException("never")))
        _      <- fp.script(Op.Initialize)
        result <- ZIO.attempt(fp.initialize(ctx)).either
      } yield assertTrue(result.isRight)
    },
    test("calls record a hung call, with its thread and flag key, once it is released after a timeout") {
      for {
        fp       <- FaultyProvider.scoped(Map("b" -> true))
        _        <- fp.script(Op.EvaluateString, Fault.HangUntilReleased)
        timedOut <- ZIO.attemptBlocking(fp.getStringEvaluation("s", "default", ctx)).disconnect.timeout(100.millis)
        before   <- fp.calls
        _        <- ZIO.attempt(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx))
        _        <- fp.release
        after    <- fp.calls.repeatUntil(_.size == 2).timeout(5.seconds)
        ops  = after.map(_.map(_.op))
        hung = after.flatMap(_.lastOption)
      } yield assertTrue(
        timedOut.isEmpty,
        before.isEmpty,
        ops.contains(Chunk[Op](Op.EvaluateBoolean, Op.EvaluateString)), // completion order: the hung call ended last
        hung.exists(_.flagKey.contains("s")),
        hung.exists(_.thread.nonEmpty),
        hung.exists(_.outcome == FaultyProvider.Outcome.Returned)
      )
    },
    test("scoped releases a hang still parked when the scope closes, and later hangs return at once") {
      for {
        parkedFiber <- Promise.make[Nothing, Fiber[Throwable, Unit]]
        fp <- ZIO.scoped {
          for {
            fp    <- FaultyProvider.scoped()
            _     <- fp.script(Op.Initialize, Fault.HangUntilReleased)
            fiber <- ZIO.attemptBlocking(fp.initialize(ctx)).fork
            _     <- parkedFiber.succeed(fiber)
            _     <- fp.awaitEntered(Op.Initialize)
          } yield fp
        }
        fiber  <- parkedFiber.await
        joined <- fiber.join.timeout(5.seconds)
        later  <- ZIO.attemptBlocking(fp.initialize(ctx)).timeout(5.seconds)
      } yield assertTrue(joined.isDefined, later.isDefined)
    },
    test("wrap keeps the delegate reachable and its name") {
      for {
        tp   <- TestFeatureProvider.make(Map("b" -> false))
        fp   <- FaultyProvider.wrapScoped(tp, name = "wrapped")
        _    <- fp.delegate.setFlag("b", true)
        b    <- ZIO.attempt(fp.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx).getValue)
        name <- ZIO.attempt(fp.getMetadata.getName)
      } yield assertTrue(b.booleanValue(), name == "wrapped", fp.delegate eq tp)
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
