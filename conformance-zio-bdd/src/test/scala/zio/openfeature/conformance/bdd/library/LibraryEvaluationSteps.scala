package zio.openfeature.conformance.bdd.library

import zio._
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.ZIOSteps
import zio.openfeature._

/** Evaluation and the assertions every suite shares. */
trait LibraryEvaluationSteps { self: ZIOSteps[Any, LibraryWorld] =>

  /** Written as a polymorphic method so `A` is bound once from the `FlagDef`; calling `valueDetails` on a
    * `FlagDef[?]` directly would leave the compiler to capture the wildcard at every call site.
    */
  private def detailsOf[A](ff: FeatureFlags, flag: FlagDef[A]): IO[FeatureFlagError, FlagResolution[Any]] =
    ff.valueDetails(flag).map(_.asInstanceOf[FlagResolution[Any]])

  private def totalOf[A](ff: FeatureFlags, flag: FlagDef[A]): UIO[FlagResolution[Any]] =
    ff.resolveOrDefault(flag).map(_.asInstanceOf[FlagResolution[Any]])

  private def record(
    outcome: Either[FeatureFlagError, FlagResolution[Any]]
  ): ZIO[zio.bdd.core.step.State[LibraryWorld], Nothing, Unit] =
    ScenarioContext.update(
      _.copy(
        resolution = outcome.toOption,
        resultValue = outcome.toOption.map(_.value),
        resultError = outcome.swap.toOption
      )
    )

  // Typed (FlagDef) evaluation --------------------------------------------------------------------

  When("the flag " / string / " is evaluated") { (name: String) =>
    for {
      w   <- ScenarioContext.get
      out <- detailsOf(w.ff, LibraryHarness.flagDef(name)).either
      _   <- record(out)
    } yield ()
  }

  When("the flag " / string / " is resolved with its own default") { (name: String) =>
    for {
      w   <- ScenarioContext.get
      res <- totalOf(w.ff, LibraryHarness.flagDef(name))
      _   <- record(Right(res))
    } yield ()
  }

  // Key-based evaluation ---------------------------------------------------------------------------

  When("the boolean flag " / string / " is evaluated with default " / string) { (key: String, default: String) =>
    for {
      w   <- ScenarioContext.get
      out <- w.ff.booleanDetails(key, default.toBoolean).either
      _   <- record(out.map(_.asInstanceOf[FlagResolution[Any]]))
    } yield ()
  }

  When("the string flag " / string / " is evaluated with default " / string) { (key: String, default: String) =>
    for {
      w   <- ScenarioContext.get
      out <- w.ff.stringDetails(key, default).either
      _   <- record(out.map(_.asInstanceOf[FlagResolution[Any]]))
    } yield ()
  }

  When("the long flag " / string / " is evaluated with default " / long) { (key: String, default: Long) =>
    for {
      w   <- ScenarioContext.get
      out <- w.ff.longDetails(key, default).either
      _   <- record(out.map(_.asInstanceOf[FlagResolution[Any]]))
    } yield ()
  }

  When("the object flag " / string / " is evaluated with default field " / string / " set to " / string) {
    (key: String, field: String, value: String) =>
      for {
        w   <- ScenarioContext.get
        out <- w.ff.objDetails(key, Map[String, Any](field -> value)).either
        _   <- record(out.map(_.asInstanceOf[FlagResolution[Any]]))
      } yield ()
  }

  When("the boolean flag " / string / " is evaluated with invocation context " / string / " = " / string) {
    (key: String, ctxKey: String, ctxValue: String) =>
      for {
        w <- ScenarioContext.get
        out <- w.ff
          .booleanDetails(key, false, EvaluationContext.builder.attribute(ctxKey, ctxValue).build)
          .either
        _ <- record(out.map(_.asInstanceOf[FlagResolution[Any]]))
      } yield ()
  }

  // Value assertions --------------------------------------------------------------------------------

  Then("the flag value is " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = LibraryHarness.render(w.observedValue)
      assertTrue(actual == expected, s"flag value '$actual' != '$expected'")
    }
  }

  Then("the long value equals " / long) { (expected: Long) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.observedValue
      assertTrue(actual == expected, s"long value $actual != $expected")
    }
  }

  Then("the object field " / string / " is " / string) { (field: String, expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.observedValue.asInstanceOf[Map[String, Any]].get(field).map(String.valueOf)
      assertTrue(actual.contains(expected), s"object field '$field' = $actual != '$expected'")
    }
  }

  // Error assertions ---------------------------------------------------------------------------------

  Then("the evaluation fails with error code " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.resultError.map(e => LibraryHarness.errorCodeName(FeatureFlagError.toErrorCode(e)))
      assertTrue(actual.contains(expected), s"failure error code $actual != $expected")
    }
  }

  Then("the failure message mentions " / string) { (fragment: String) =>
    ScenarioContext.get.flatMap { w =>
      val message = w.resultError.map(_.message).getOrElse("")
      assertTrue(message.contains(fragment), s"failure message '$message' does not mention '$fragment'")
    }
  }

  Then("the resolved error code is " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.resolution.flatMap(_.errorCode).map(LibraryHarness.errorCodeName)
      assertTrue(actual.contains(expected), s"resolved error code $actual != $expected")
    }
  }

  Then("the resolved reason is " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val actual = w.resolution.map(r => LibraryHarness.reasonName(r.reason))
      assertTrue(actual.contains(expected), s"resolved reason $actual != $expected")
    }
  }

  Then("the evaluation succeeds") {
    ScenarioContext.get.flatMap(w => assertTrue(w.resultError.isEmpty, s"evaluation failed with ${w.resultError}"))
  }

  // Provider-interaction assertions ---------------------------------------------------------------------

  Then("the provider evaluation count for the flag " / string / " is " / int) { (key: String, expected: Int) =>
    ScenarioContext.get.flatMap { w =>
      w.provider.evaluationCount(key).flatMap { actual =>
        assertTrue(actual == expected, s"provider saw $actual evaluations of '$key', expected $expected")
      }
    }
  }

  Then("the provider was handed the object default field " / string / " with value " / string) {
    (field: String, expected: String) =>
      ScenarioContext.get.flatMap { w =>
        val recorder = w.recorder.getOrElse(throw new IllegalStateException("this scenario has no recording provider"))
        val seen     = Option(recorder.sawObjectDefault.get)
        val actual   = seen.flatMap(v => Option(v.asStructure)).flatMap(s => Option(s.getValue(field))).map(_.asString)
        assertTrue(actual.contains(expected), s"object default field '$field' = $actual != '$expected'")
      }
  }

  Then("the provider received the context attribute " / string / " = " / string) { (key: String, expected: String) =>
    ScenarioContext.get.flatMap { w =>
      w.provider.getRawEvaluations.flatMap { evaluations =>
        val actual = evaluations.lastOption
          .flatMap { case (_, ctx) => Option(ctx.getValue(key)) }
          .flatMap(v => Option(v.asString))
        assertTrue(actual.contains(expected), s"merged context '$key' = $actual != '$expected'")
      }
    }
  }

  Then("the provider metadata name is " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      w.ff.providerMetadata.flatMap { meta =>
        assertTrue(meta.name == expected, s"provider metadata name '${meta.name}' != '$expected'")
      }
    }
  }

  // Hook assertions -----------------------------------------------------------------------------------

  Then("the hook saw the flag value type " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val seen = w.hookSeen.getOrElse(throw new IllegalStateException("this scenario registered no scoped hook"))
      seen.get.flatMap { types =>
        assertTrue(types.contains(LibraryHarness.flagValueType(expected)), s"hook saw $types, expected $expected")
      }
    }
  }

  Then("the hook did not run") {
    ScenarioContext.get.flatMap { w =>
      val seen = w.hookSeen.getOrElse(throw new IllegalStateException("this scenario registered no scoped hook"))
      seen.get.flatMap(types => assertTrue(types.isEmpty, s"hook ran and saw $types"))
    }
  }
}
