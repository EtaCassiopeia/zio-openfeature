package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.FallbackLogLimiter

object FallbackLogLimiterSpec extends ZIOSpecDefault {

  private def resolution(key: String): FlagResolution[Boolean] =
    FlagResolution.error(key, true, ErrorCode.FlagNotFound, "no such flag")

  // The test logger accumulates across tests in the same runtime, so every test measures from its own baseline.
  private def baseline: UIO[Int] = ZTestLogger.logOutput.map(_.length)
  private def warnings(since: Int): UIO[Chunk[String]] =
    ZTestLogger.logOutput.map(_.drop(since).filter(_.logLevel == LogLevel.Warning).map(_.message()))

  def spec = suite("FallbackLogLimiterSpec")(
    suite("admit")(
      test("Throttled admits the first event per key and suppresses within the window") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          a1 <- l.admit("k")
          a2 <- l.admit("k")
          _  <- TestClock.adjust(59.seconds)
          a3 <- l.admit("k")
        } yield assertTrue(a1 == Some(0), a2 == None, a3 == None)
      },
      test("Throttled re-admits after the window with the suppressed count, then resets") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          _  <- l.admit("k")
          _  <- l.admit("k")
          _  <- l.admit("k")
          _  <- l.admit("k")
          _  <- TestClock.adjust(60.seconds)
          a5 <- l.admit("k")
          a6 <- l.admit("k")
          _  <- TestClock.adjust(60.seconds)
          a7 <- l.admit("k")
        } yield assertTrue(a5 == Some(3), a6 == None, a7 == Some(1))
      },
      test("keys are independent") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          a1 <- l.admit("a")
          b1 <- l.admit("b")
          a2 <- l.admit("a")
          _  <- TestClock.adjust(60.seconds)
          a3 <- l.admit("a")
          b2 <- l.admit("b")
        } yield assertTrue(a1 == Some(0), b1 == Some(0), a2 == None, a3 == Some(1), b2 == Some(0))
      },
      test("Always admits every event with 0 suppressed") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Always)
          rs <- ZIO.foreach(1 to 5)(_ => l.admit("k"))
        } yield assertTrue(rs.forall(_ == Some(0)))
      },
      test("Off admits nothing") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Off)
          rs <- ZIO.foreach(1 to 5)(_ => l.admit("k"))
        } yield assertTrue(rs.forall(_ == None))
      },
      test("Throttled(Duration.Zero) behaves like Always") {
        for {
          l  <- FallbackLogLimiter.make(FallbackLogging.Throttled(Duration.Zero))
          rs <- ZIO.foreach(1 to 3)(_ => l.admit("k"))
        } yield assertTrue(rs.forall(_ == Some(0)))
      },
      test("tracked keys are bounded; overflow keys share one throttled bucket instead of going unlimited") {
        val max = FallbackLogLimiter.MaxTrackedKeys
        for {
          l    <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          rs   <- ZIO.foreach(1 to max + 50)(i => l.admit(s"k-$i"))
          size <- l.trackedKeys
          more <- l.admit("k-more") // still full, still within the window → the overflow bucket suppresses it
          _    <- TestClock.adjust(60.seconds)
          _  <- ZIO.foreach(1 to max)(i => l.admit(s"k-$i")) // re-fill: everything re-tracked, overflow expired
          o1 <- l.admit("k-late-1")                          // full again → overflow bucket, window elapsed → emit
          o2 <- l.admit("k-late-2")
        } yield assertTrue(
          rs.take(max).forall(_ == Some(0)),  // tracked keys: first event each
          rs(max) == Some(0),                 // first overflow key: the shared bucket emits once
          rs.drop(max + 1).forall(_ == None), // the other 49 overflow keys are throttled by that bucket
          size == max,
          more == None,
          o1 == Some(50), // 49 overflow keys + `k-more` were suppressed since the bucket last emitted
          o2 == None
        )
      },
      test("expired entries are pruned when full so new keys become tracked again") {
        val max = FallbackLogLimiter.MaxTrackedKeys
        for {
          l    <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          _    <- ZIO.foreach(1 to max)(i => l.admit(s"k-$i"))
          _    <- TestClock.adjust(60.seconds)
          a1   <- l.admit("fresh")
          size <- l.trackedKeys
          a2   <- l.admit("fresh") // tracked now → suppressed
        } yield assertTrue(a1 == Some(0), size == 1, a2 == None)
      }
    ),
    suite("log")(
      test("served-default line names the key, error code, message and served value, with the suppressed count") {
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          _    <- l.log("k", resolution("k"), None)
          _    <- l.log("k", resolution("k"), None)
          _    <- l.log("k", resolution("k"), None)
          _    <- TestClock.adjust(60.seconds)
          _    <- l.log("k", resolution("k"), None)
          logs <- warnings(base)
        } yield assertTrue(
          logs.length == 2,
          logs(0).contains("'k'"),
          logs(0).contains("FlagNotFound"),
          logs(0).contains("no such flag"),
          logs(0).contains("true"),
          !logs(0).contains("suppressed"),
          logs(1).contains("(suppressed 2 similar)")
        )
      },
      test("absorbed defect keeps its message and cause, and has its own bucket per key") {
        val cause = Cause.die(new RuntimeException("boom"))
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Throttled(60.seconds))
          _    <- l.log("k", resolution("k"), None)        // served-default bucket for k
          _    <- l.log("k", resolution("k"), Some(cause)) // defect bucket for k — not starved by the line above
          _    <- l.log("k", resolution("k"), Some(cause)) // suppressed
          _    <- TestClock.adjust(60.seconds)
          _    <- l.log("k", resolution("k"), Some(cause)) // emitted, with count 1
          all  <- ZTestLogger.logOutput
          warns = all.drop(base).filter(_.logLevel == LogLevel.Warning)
        } yield assertTrue(
          warns.length == 3,
          warns(1).message().startsWith("Total evaluation of 'k' absorbed a defect; serving the default"),
          !warns(1).message().contains("suppressed"),
          warns(1).cause.dieOption.exists(_.getMessage == "boom"),
          warns(2).message().contains("(suppressed 1 similar)"),
          warns(2).cause.dieOption.exists(_.getMessage == "boom")
        )
      },
      test("Off throttles absorbed defects at the default window and never logs a served-default line") {
        val boom = Cause.die(new RuntimeException("boom"))
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Off)
          _    <- l.log("k", resolution("k"), None)       // served-default line: silent under Off
          _    <- l.log("k", resolution("k"), Some(boom)) // first defect for k: emitted
          _    <- l.log("k", resolution("k"), Some(boom)) // within the default window: suppressed, not silenced
          _    <- l.log("j", resolution("j"), Some(boom)) // another key gets its own first line
          _    <- TestClock.adjust(60.seconds)
          _    <- l.log("k", resolution("k"), None)       // still silent after the window
          _    <- l.log("k", resolution("k"), Some(boom)) // re-admitted, carrying the suppressed count
          logs <- warnings(base)
        } yield assertTrue(
          logs.length == 3,
          logs.forall(_.contains("absorbed a defect")),
          logs(0).startsWith("Total evaluation of 'k' absorbed a defect"),
          !logs(0).contains("suppressed"),
          logs(1).startsWith("Total evaluation of 'j' absorbed a defect"),
          logs(2).contains("(suppressed 1 similar)")
        )
      },
      test("Throttled(Duration.Infinity) logs the first event per key only") {
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Throttled(Duration.Infinity))
          _    <- ZIO.foreach(1 to 3)(_ => l.log("k", resolution("k"), None))
          _    <- TestClock.adjust(36500.days)
          _    <- l.log("k", resolution("k"), None)
          logs <- warnings(base)
        } yield assertTrue(logs.length == 1)
      },
      test("a value whose toString throws still gets a line, and never fails the effect") {
        val hostile = new AnyRef { override def toString: String = throw new IllegalStateException("no render") }
        val res     = FlagResolution.error[Any]("k", hostile, ErrorCode.General, "x")
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Always)
          exit <- l.log("k", res, None).exit
          logs <- warnings(base)
        } yield assertTrue(exit.isSuccess, logs.length == 1, logs.head.contains("<unrenderable value>"))
      },
      test("Always logs every event without a suppressed suffix") {
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Always)
          _    <- ZIO.foreach(1 to 3)(_ => l.log("k", resolution("k"), None))
          logs <- warnings(base)
        } yield assertTrue(logs.length == 3, logs.forall(!_.contains("suppressed")))
      },
      test("Always logs every absorbed defect without a suppressed suffix") {
        val boom = Cause.die(new RuntimeException("boom"))
        for {
          base <- baseline
          l    <- FallbackLogLimiter.make(FallbackLogging.Always)
          _    <- ZIO.foreach(1 to 3)(_ => l.log("k", resolution("k"), Some(boom)))
          logs <- warnings(base)
        } yield assertTrue(
          logs.length == 3,
          logs.forall(_.startsWith("Total evaluation of 'k' absorbed a defect")),
          logs.forall(!_.contains("suppressed"))
        )
      }
    ) @@ TestAspect.sequential
  )
}
