package zio.openfeature.conformance.bdd.library

import zio._
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.ZIOSteps
import zio.openfeature._
import zio.openfeature.testkit.TestFeatureProvider
import zio.openfeature.testkit.FlagOverride.Ops
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** Transactions, served-default logging, fallback-first construction, and typed-fixture rejection. */
trait LibraryLifecycleSteps { self: ZIOSteps[Any, LibraryWorld] =>

  // Transactions (#365) ----------------------------------------------------------------------------

  /** Reads the same flag twice inside one transaction. The second read must be served from the transaction's cache —
    * decoded back out of the *wire* value it stored — so the provider sees exactly one evaluation.
    */
  private def readTwice[A](
    ff: FeatureFlags,
    flag: FlagDef[A],
    overrides: Map[String, Any]
  ): ZIO[Any, Throwable, TransactionResult[List[Any]]] =
    ff.transactionEither(overrides = overrides) {
      ff.valueOrDefault(flag).zipWith(ff.valueOrDefault(flag))((a, b) => List[Any](a, b))
    }.mapError(e => new RuntimeException(s"transaction failed: $e"))

  /** The domain value a feature file spells for a flag — an actual `Tier`, `Level`, … rather than its wire form. */
  private def domainValue(flag: FlagDef[?], raw: String): Any = flag.key match {
    case "user.plan"    => Tier.valueOf(raw)
    case "max.items"    => Level(raw.toInt)
    case "kill.switch"  => raw.toBoolean
    case "budget.cents" => raw.toLong
    case _              => raw
  }

  /** The same value in the form a provider would carry it. */
  private def wireValue(flag: FlagDef[?], raw: String): Any = flag.key match {
    case "max.items"    => raw.toInt
    case "kill.switch"  => raw.toBoolean
    case "budget.cents" => raw.toLong
    case _              => raw
  }

  private def runTransaction(name: String, overrides: FlagDef[?] => Map[String, Any]) =
    for {
      w <- ScenarioContext.get
      flag = LibraryHarness.flagDef(name)
      res <- readTwice(w.ff, flag, overrides(flag))
      _   <- ScenarioContext.update(_.copy(txResult = Some(res)))
    } yield ()

  When("a transaction reads the flag " / string / " twice") { (name: String) =>
    runTransaction(name, _ => Map.empty)
  }

  When("a transaction overriding the flag " / string / " with the domain value " / string / " reads it twice") {
    (name: String, raw: String) =>
      runTransaction(name, flag => Map(flag.key -> domainValue(flag, raw)))
  }

  When("a transaction overriding the flag " / string / " with the wire value " / string / " reads it twice") {
    (name: String, raw: String) =>
      runTransaction(name, flag => Map(flag.key -> wireValue(flag, raw)))
  }

  Then("both transaction reads are " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val values = w.txResult.map(_.result.map(LibraryHarness.render)).getOrElse(Nil)
      assertTrue(values == List(expected, expected), s"transaction reads $values != two of '$expected'")
    }
  }

  Then("the flag " / string / " was overridden in the transaction") { (name: String) =>
    ScenarioContext.get.flatMap { w =>
      val key        = LibraryHarness.flagDef(name).key
      val overridden = w.txResult.exists(_.wasOverridden(key))
      assertTrue(overridden, s"flag '$key' was not overridden: ${w.txResult.map(_.overriddenFlags)}")
    }
  }

  // Served-default logging (#350) ---------------------------------------------------------------------

  private def servedDefaultLines(w: LibraryWorld): List[String] =
    w.logSink.map(_.all).getOrElse(Nil).filter(_.contains("fell back to its default"))

  When("the boolean flag " / string / " is served its default " / int / " times") { (key: String, times: Int) =>
    ScenarioContext.get.flatMap { w =>
      val sink = w.logSink.getOrElse(throw new IllegalStateException("this scenario captures no logs"))
      FiberRef.currentLoggers.locallyWith(_ + LogSink.logger(sink))(
        ZIO.foreachDiscard(1 to times)(_ => w.ff.booleanOrDefault(key, false))
      )
    }
  }

  Then("the served-default warning count is " / int) { (expected: Int) =>
    ScenarioContext.get.flatMap { w =>
      val lines = servedDefaultLines(w)
      assertTrue(lines.sizeIs == expected, s"served-default warnings ${lines.size} != $expected: $lines")
    }
  }

  Then("a served-default warning names the flag " / string) { (key: String) =>
    ScenarioContext.get.flatMap { w =>
      val lines = servedDefaultLines(w)
      assertTrue(lines.exists(_.contains(s"'$key'")), s"no served-default warning names '$key': $lines")
    }
  }

  // Fallback-first construction (#349/#352) -------------------------------------------------------------

  Given("a fallback-first instance whose real provider " / string) { (outcome: String) =>
    for {
      sc        <- Scope.make
      swapped   <- Ref.make(false)
      failure   <- Ref.make(Option.empty[Throwable])
      fallbackP <- TestFeatureProvider.makeNamed("fallback", Map[String, Any]("kill.switch" -> false))
      realP     <- TestFeatureProvider.makeNamed("real", Map[String, Any]("kill.switch" -> true))
      acquire = outcome match {
        case "is acquired and verified" => ZIO.succeed[OFFeatureProvider](realP)
        // Constructs fine and then knows nothing — exactly the "successful wrong values" case `verify` exists for.
        case "fails verification" => ZIO.succeed[OFFeatureProvider](new LibraryProviders.EmptyProvider("empty"))
        case "cannot be acquired" => ZIO.fail(new RuntimeException("acquire exploded"))
        case other                => throw new IllegalArgumentException(s"unknown acquire outcome: $other")
      }
      env <- sc.extend[Any](
        FeatureFlags
          .fromAcquireAsync(
            acquire = acquire,
            fallback = ZIO.succeed(fallbackP),
            // No retry: a scenario asserting the terminal outcome must not wait out an exponential schedule.
            constructionRetry = Schedule.stop,
            constructionTimeout = 10.seconds,
            onConstructionError = t => failure.set(Some(t)),
            verify = Verify.flagExists[Boolean]("kill.switch"),
            onSwapped = swapped.set(true)
          )
          .build
      )
      _ <- ScenarioContext.update(
        _.copy(
          scope = Some(sc),
          flags = Some(env.get[FeatureFlags]),
          acquireStatus = Some(env.get[AcquireStatus]),
          swapped = Some(swapped),
          constructionError = Some(failure)
        )
      )
    } yield ()
  }

  When("the construction outcome settles") {
    for {
      w <- ScenarioContext.get
      status = w.acquireStatus.getOrElse(throw new IllegalStateException("this scenario has no AcquireStatus"))
      settled <- status.changes
        .filter(_ != AcquireState.Constructing)
        .runHead
        .timeoutFail(new RuntimeException("construction never settled"))(30.seconds)
      _ <- ScenarioContext.update(_.copy(settled = settled))
    } yield ()
  }

  Then("the acquire state is " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.settled.map {
        case AcquireState.Constructing => "Constructing"
        case AcquireState.Live         => "Live"
        case AcquireState.Failed(_)    => "Failed"
      }
      assertTrue(actual.contains(expected), s"acquire state $actual != $expected")
    }
  }

  Then("the swap callback fired") {
    ScenarioContext.get.flatMap { w =>
      w.swapped.get.get.flatMap(fired => assertTrue(fired, "onSwapped never fired"))
    }
  }

  Then("the swap callback did not fire") {
    ScenarioContext.get.flatMap { w =>
      w.swapped.get.get.flatMap(fired => assertTrue(!fired, "onSwapped fired unexpectedly"))
    }
  }

  Then("the construction error mentions " / string) { (fragment: String) =>
    ScenarioContext.get.flatMap { w =>
      w.constructionError.get.get.flatMap { failure =>
        val text = failure.map(_.toString).getOrElse("")
        assertTrue(text.contains(fragment), s"construction error '$text' does not mention '$fragment'")
      }
    }
  }

  // Typed fixtures (#351) ---------------------------------------------------------------------------------

  When("two typed overrides for the same key are used to seed a provider") {
    ZIO
      .attempt(TestFeatureProvider.make(Flags.Plan := Tier.Premium, Flags.Plan := Tier.Free))
      .either
      .flatMap(out => ScenarioContext.update(_.copy(fixtureError = out.swap.toOption)))
  }

  Then("seeding the provider was rejected") {
    ScenarioContext.get.flatMap { w =>
      val message = w.fixtureError.map(_.getMessage).getOrElse("")
      assertTrue(
        w.fixtureError.isDefined && message.contains("Duplicate flag overrides"),
        s"expected a duplicate-override rejection, got '$message'"
      )
    }
  }
}
