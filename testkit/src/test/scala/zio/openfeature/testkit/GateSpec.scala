package zio.openfeature.testkit

import zio._
import zio.test._

object GateSpec extends ZIOSpecDefault {

  private def awaiting(gate: Gate, ticket: Int): URIO[Any, Fiber[Throwable, Unit]] =
    ZIO.attemptBlockingInterrupt(gate.await(ticket)).fork

  def spec = suite("Gate")(
    test("an open after the ticket was taken lets the waiter through, even before it waits") {
      val gate   = new Gate
      val ticket = gate.ticket()
      gate.open()
      for {
        passed <- ZIO.attemptBlocking(gate.await(ticket)).timeout(2.seconds)
      } yield assertTrue(passed.isDefined)
    },
    test("a waiter blocks until the next open") {
      val gate = new Gate
      for {
        waiter  <- awaiting(gate, gate.ticket())
        _       <- ZIO.sleep(200.millis)
        blocked <- waiter.poll
        _       <- ZIO.succeed(gate.open())
        passed  <- waiter.join.timeout(2.seconds)
      } yield assertTrue(blocked.isEmpty, passed.isDefined)
    },
    test("an open before the ticket was taken does not let the waiter through") {
      val gate = new Gate
      gate.open()
      for {
        waiter  <- awaiting(gate, gate.ticket())
        _       <- ZIO.sleep(200.millis)
        blocked <- waiter.poll
        _       <- ZIO.succeed(gate.open())
        passed  <- waiter.join.timeout(2.seconds)
      } yield assertTrue(blocked.isEmpty, passed.isDefined)
    },
    test("one open lets every waiter holding an earlier ticket through") {
      val gate   = new Gate
      val ticket = gate.ticket()
      for {
        waiters <- ZIO.foreach(1 to 8)(_ => awaiting(gate, ticket))
        _       <- ZIO.sleep(100.millis)
        _       <- ZIO.succeed(gate.open())
        passed  <- ZIO.foreach(waiters)(_.join.timeout(2.seconds))
      } yield assertTrue(passed.forall(_.isDefined))
    },
    test("close lets current and later waiters through for good") {
      val gate = new Gate
      for {
        current <- awaiting(gate, gate.ticket())
        _       <- ZIO.sleep(100.millis)
        _       <- ZIO.succeed(gate.close())
        passed  <- current.join.timeout(2.seconds)
        later   <- ZIO.attemptBlocking(gate.await(gate.ticket())).timeout(2.seconds)
      } yield assertTrue(passed.isDefined, later.isDefined)
    },
    test("a waiting thread can be interrupted") {
      val gate = new Gate
      for {
        waiter      <- awaiting(gate, gate.ticket())
        _           <- ZIO.sleep(100.millis)
        interrupted <- waiter.interrupt.timeout(2.seconds)
      } yield assertTrue(interrupted.exists(_.isInterrupted))
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
