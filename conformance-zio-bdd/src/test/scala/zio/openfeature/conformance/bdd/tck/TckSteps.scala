package zio.openfeature.conformance.bdd.tck

import dev.openfeature.sdk.{
  Client,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  FlagEvaluationDetails,
  ImmutableContext,
  MutableStructure,
  OpenFeatureAPI,
  Value
}
import zio.*
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.Default
import zio.bdd.core.step.ZIOSteps
import zio.schema.{DeriveSchema, Schema}

/** Per-scenario state of a provider TCK run. */
final case class TckWorld(
  scope: Option[Scope.Closeable] = None,
  provider: Option[FeatureProvider] = None,
  client: Option[Client] = None,
  flagType: String = "",
  flagKey: String = "",
  defaultRaw: String = "",
  ctx: OFEvaluationContext = new ImmutableContext(),
  details: Option[FlagEvaluationDetails[?]] = None,
  thrown: Option[Throwable] = None
)
object TckWorld {
  given Default[TckWorld] = Default.from(TckWorld())
}

/** A row of "the resolved object value should contain". */
final case class ObjectRow(key: String, `type`: String, value: String)
object ObjectRow { given Schema[ObjectRow] = DeriveSchema.gen[ObjectRow] }

/** Step definitions for the provider TCK, against one [[TckBackend]].
  *
  * The suite validates a provider, not this library, so it drives the provider through a plain OpenFeature client on an
  * isolated API: a failure here is the provider's, never `FeatureFlags`'s.
  */
abstract class TckSteps(backend: TckBackend) extends ZIOSteps[Any, TckWorld] {

  afterScenario {
    ScenarioContext.get.flatMap(w => ZIO.foreachDiscard(w.scope)(_.close(Exit.unit)))
  }

  /** Assert `ok` for `check`, unless it is a reviewed known failure: then the check must fail with exactly the known
    * `observed` outcome. Passing, or failing some other way, is the error, so the entry cannot hide a different defect.
    */
  private def verify(w: TckWorld, check: Check, ok: Boolean, observed: String, message: => String) =
    backend.knownFailure(w.flagType, w.flagKey, check) match {
      case None        => assertTrue(ok, message)
      case Some(known) =>
        val what = s"the $check check of ${w.flagType} '${w.flagKey}', a known failure (${known.note})"
        if (ok) assertTrue(false, s"${backend.name} now passes $what; remove the entry")
        else
          assertTrue(
            observed == known.observed,
            s"${backend.name} fails $what with '$observed', not the known '${known.observed}': ${rendered(w)}"
          )
    }

  /** What the evaluation produced for one check, or `<threw>` when it produced nothing. */
  private def observed(w: TckWorld)(read: FlagEvaluationDetails[?] => Any): String =
    w.details.fold("<threw>")(d => Option(read(d)).fold("")(String.valueOf))

  // Provider -------------------------------------------------------------------------------------

  Given("a stable provider") {
    for {
      // Stored first, so `afterScenario` closes it even when setting the provider up fails.
      scope <- Scope.make
      _     <- ScenarioContext.update(_.copy(scope = Some(scope)))
      provider <- backend.stable
      api    = OpenFeatureAPI.createIsolated()
      domain = s"tck-${java.util.UUID.randomUUID()}"
      _ <- scope.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignoreLogged)
      _ <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
      _ <- ScenarioContext.update(_.copy(provider = Some(provider), client = Some(api.getClient(domain))))
    } yield ()
  }

  Then("the provider metadata name should not be empty") {
    ScenarioContext.get.flatMap { w =>
      val name = w.provider.flatMap(p => Option(p.getMetadata)).flatMap(m => Option(m.getName)).getOrElse("")
      assertTrue(name.nonEmpty, s"${backend.name} reports an empty metadata name")
    }
  }

  // Flag and context -----------------------------------------------------------------------------

  Given("a " / string / "-flag with key " / string / " and a default value " / string) {
    (flagType: String, key: String, default: String) =>
      ScenarioContext.update(_.copy(flagType = flagType.toLowerCase, flagKey = key, defaultRaw = default))
  }

  Given("a context containing a targeting key with value " / string) { (key: String) =>
    ScenarioContext.update(_.copy(ctx = new ImmutableContext(key)))
  }

  // Evaluation -----------------------------------------------------------------------------------

  private def evaluate(w: TckWorld): FlagEvaluationDetails[?] = {
    val c = w.client.getOrElse(throw new IllegalStateException("no provider was set up"))
    w.flagType match {
      case "boolean" => c.getBooleanDetails(w.flagKey, java.lang.Boolean.valueOf(w.defaultRaw.toBoolean), w.ctx)
      case "string"  => c.getStringDetails(w.flagKey, w.defaultRaw, w.ctx)
      case "integer" => c.getIntegerDetails(w.flagKey, Integer.valueOf(w.defaultRaw.toInt), w.ctx)
      case "float"   => c.getDoubleDetails(w.flagKey, java.lang.Double.valueOf(w.defaultRaw.toDouble), w.ctx)
      // The suite's only structured default is "{}".
      case "object" => c.getObjectDetails(w.flagKey, new Value(new MutableStructure()), w.ctx)
      case other    => throw new IllegalArgumentException(s"unknown flag type: $other")
    }
  }

  When("the flag was evaluated with details") {
    ScenarioContext.get.flatMap { w =>
      ZIO
        .attemptBlocking(evaluate(w))
        .foldZIO(
          thrown => ScenarioContext.update(_.copy(details = None, thrown = Some(thrown))),
          details => ScenarioContext.update(_.copy(details = Some(details), thrown = None))
        )
    }
  }

  // Assertions -----------------------------------------------------------------------------------

  private def rendered(w: TckWorld): String = w.details.fold(s"no result (threw ${w.thrown})")(d => s"$d")

  /** Whether the resolved value reads as `expected`. Numbers compare by value, so a widened `10.0` reads as "10". */
  private def valueIs(w: TckWorld, expected: String): Boolean =
    w.details.map(_.getValue).exists {
      case d: java.lang.Double =>
        scala.util.Try(BigDecimal(d.toString).compare(BigDecimal(expected)) == 0).getOrElse(false)
      case other => String.valueOf(other) == expected
    }

  Then("the resolved details value should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      verify(w, Check.Value, valueIs(w, expected), observed(w)(_.getValue), s"value is not '$expected': ${rendered(w)}")
    }
  }

  Then("the error-code should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val code = w.details.flatMap(d => Option(d.getErrorCode)).map(_.name).getOrElse("")
      verify(
        w,
        Check.ErrorCode,
        w.details.isDefined && code == expected,
        observed(w)(d => Option(d.getErrorCode).map(_.name).orNull),
        s"error code is not '$expected': ${rendered(w)}"
      )
    }
  }

  Then("the error message should be empty") {
    ScenarioContext.get.flatMap { w =>
      val message = w.details.flatMap(d => Option(d.getErrorMessage)).getOrElse("")
      verify(
        w,
        Check.ErrorMessage,
        w.details.isDefined && message.isEmpty,
        observed(w)(_.getErrorMessage),
        s"error message is set: ${rendered(w)}"
      )
    }
  }

  Then("the reason should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val reason = w.details.flatMap(d => Option(d.getReason)).getOrElse("")
      verify(w, Check.Reason, reason == expected, observed(w)(_.getReason), s"reason is not '$expected': ${rendered(w)}")
    }
  }

  Then("the variant should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val variant = w.details.flatMap(d => Option(d.getVariant)).getOrElse("")
      verify(w, Check.Variant, variant == expected, observed(w)(_.getVariant), s"variant is not '$expected': ${rendered(w)}")
    }
  }

  Then("no exception should have been thrown") {
    ScenarioContext.get.flatMap(w => assertTrue(w.thrown.isEmpty, s"the evaluation threw ${w.thrown}"))
  }

  Then("the resolved object value should contain" / table[ObjectRow]) { (rows: List[ObjectRow]) =>
    ScenarioContext.get.flatMap { w =>
      val structure = w.details.map(_.getValue).collect { case v: Value if v.isStructure => v.asStructure }
      def matches(row: ObjectRow): Boolean =
        structure.flatMap(s => Option(s.getValue(row.key))).exists { v =>
          row.`type` match {
            case "Boolean" => v.isBoolean && v.asBoolean.toString == row.value
            case "String"  => v.isString && v.asString == row.value
            case "Integer" => v.isNumber && v.asDouble == row.value.toDouble && v.asDouble % 1 == 0
            case "Float"   => v.isNumber && v.asDouble == row.value.toDouble
            case _         => false
          }
        }
      verify(w, Check.Value, rows.forall(matches), observed(w)(_.getValue), s"structure does not contain $rows: ${rendered(w)}")
    }
  }
}
