package zio.openfeature.conformance.bdd

import zio._
import zio.stream.SubscriptionRef
import zio.bdd.core.Suite
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.{State, ZIOSteps}
import zio.openfeature._
// zio-bdd's Hooks trait defines `type FeatureHook = URIO[R, Unit]`, which shadows the OpenFeature hook trait; alias it.
import zio.openfeature.FeatureHook as OFFeatureHook
import zio.openfeature.testkit.{CachingReasonProvider, TestFeatureProvider}
import zio.schema.{DeriveSchema, Schema}
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, OpenFeatureAPI}
import dev.openfeature.sdk.providers.memory.InMemoryProvider

/** Data-table row types (zio-bdd's `table[T]` maps a gherkin table to `List[T]` via a zio-schema record). */
final case class MetaRow(key: String, metadata_type: String, value: String)
object MetaRow { given Schema[MetaRow] = DeriveSchema.gen[MetaRow] }
final case class HookRow(data_type: String, key: String, value: String)
object HookRow { given Schema[HookRow] = DeriveSchema.gen[HookRow] }

/** The OpenFeature gherkin conformance suite run via zio-bdd (experimental dog-food of zio-bdd). Mirrors the Cucumber
  * suite in `conformance/` but with native ZIO step bodies — no `Unsafe.run` bridge.
  */
@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/openfeature"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 1,
  // Canonical exclusion rationale for BOTH conformance runners (the Cucumber `RunConformance` references this).
  // The remaining excluded tags are out of scope for a ZIO SDK + in-memory testkit, not coverage gaps:
  //   - async:        tautological — every evaluation already returns a non-blocking ZIO effect; there is no separate
  //                   async API surface to assert against.
  //   - deprecated:   superseded scenarios retained in the feature file for history, not meant to run.
  //   - immutability: the @immutability scenario asserts evaluation-*context* immutability (spec 1.4.15) and has no
  //                   step definitions here; enabling it needs those steps implemented. It is unrelated to
  //                   hook-*hints* immutability (spec 4.5.3), which #247 enforces at the type level.
  // Both runners exclude exactly {deprecated, async, immutability}. @reason-codes-cached (spec 1.4.7) and
  // @evaluation-options (spec 1.5.1) are now enabled: the former via the CachingReasonProvider decorator wrapping the
  // in-memory provider, the latter via the evaluation-options step defs.
  excludeTags = Array("deprecated", "async", "immutability"),
  logLevel = "warning"
)
object ConformanceSpec extends ZIOSteps[Any, World] {

  // Provider setup -------------------------------------------------------------------------------

  // Providers are scoped resources, built into a per-scenario `Scope.Closeable` stored in the scenario state and
  // released by the `afterScenario` hook below — no global/leaked scope.
  private def buildInMemory(sc: Scope.Closeable): ZIO[Any, Throwable, FeatureFlags] =
    for {
      statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
      api    = OpenFeatureAPI.createIsolated()
      domain = s"bdd-${java.util.UUID.randomUUID()}"
      env <- sc.extend[Any](
        FeatureFlags
          .fromProviderWithDomain(
            new CachingReasonProvider(new InMemoryProvider(Fixtures.inMemoryFlags)),
            domain,
            statusRef,
            api = Some(api)
          )
          .build
      )
    } yield env.get[FeatureFlags]

  private def buildTestProvider(sc: Scope.Closeable): ZIO[Any, Throwable, (TestFeatureProvider, FeatureFlags)] =
    sc.extend[Any](TestFeatureProvider.layer(Fixtures.testProviderSeed).build)
      .map(env => (env.get[TestFeatureProvider], env.get[FeatureFlags]))

  afterScenario {
    ScenarioContext.get.flatMap(w => ZIO.foreachDiscard(w.scope)(_.close(Exit.unit)))
  }

  private def statusOf(word: String): ProviderStatus = word match {
    case "not ready" => ProviderStatus.NotReady
    case "error"     => ProviderStatus.Error
    case "fatal"     => ProviderStatus.Fatal
    case "stale"     => ProviderStatus.Stale
    case other       => throw new IllegalArgumentException(s"unknown provider status: $other")
  }

  // A "stable" provider needs real variants/reasons → InMemoryProvider; any non-ready status is simulated by the
  // testkit provider whose status we then flip.
  Given("a " / string / " provider") { (status: String) =>
    for {
      sc <- Scope.make
      _ <-
        if (status == "stable")
          buildInMemory(sc).flatMap(ff =>
            ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff), testProvider = None))
          )
        else
          for {
            tpff <- buildTestProvider(sc)
            _    <- tpff._1.setStatus(statusOf(status))
            _    <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(tpff._2), testProvider = Some(tpff._1)))
          } yield ()
    } yield ()
  }

  Given("a stable provider with retrievable context is registered") {
    for {
      sc   <- Scope.make
      tpff <- buildTestProvider(sc)
      _    <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(tpff._2), testProvider = Some(tpff._1)))
    } yield ()
  }

  // Flag + context -------------------------------------------------------------------------------

  Given("a " / string / "-flag with key " / string / " and a fallback value " / string) {
    (typ: String, key: String, default: String) =>
      ScenarioContext.update(_.copy(flagType = typ.toLowerCase, flagKey = key, defaultRaw = default))
  }

  Given("a context containing a key " / string / ", with type " / string / " and with value " / string) {
    (key: String, typ: String, value: String) =>
      val av = typ.toLowerCase match {
        case "integer" => AttributeValue.IntValue(value.toInt)
        case "boolean" => AttributeValue.BoolValue(value.toBoolean)
        case _         => AttributeValue.StringValue(value)
      }
      ScenarioContext.update(w => w.copy(ctx = w.ctx.withAttribute(key, av)))
  }

  // No null attribute in the typed context; an absent key is equivalent (→ DEFAULT).
  Given("a context containing a key " / string / " with null value") { (_: String) => ZIO.unit }

  // Evaluation -----------------------------------------------------------------------------------

  private def unescape(s: String): String = s.replace("\\\"", "\"")

  private def bridge[A](key: String, e: FeatureFlagError, default: A): FlagResolution[A] =
    FlagResolution(default, None, ResolutionReason.Error, FlagMetadata.empty, key, Some(FeatureFlagError.toErrorCode(e)), Some(e.message))

  private def evalByType(
    ff: FeatureFlags,
    w: World,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[Any, Nothing, FlagResolution[Any]] = {
    def br[A](io: IO[FeatureFlagError, FlagResolution[A]], default: A): ZIO[Any, Nothing, FlagResolution[Any]] =
      io.catchAll(e => ZIO.succeed(bridge(w.flagKey, e, default))).map(_.asInstanceOf[FlagResolution[Any]])
    w.flagType match {
      case "boolean" => br(ff.booleanDetails(w.flagKey, w.defaultRaw.toBoolean, w.ctx, options), w.defaultRaw.toBoolean)
      case "string"  => br(ff.stringDetails(w.flagKey, w.defaultRaw, w.ctx, options), w.defaultRaw)
      case "integer" => br(ff.intDetails(w.flagKey, w.defaultRaw.toInt, w.ctx, options), w.defaultRaw.toInt)
      case "float"   => br(ff.doubleDetails(w.flagKey, w.defaultRaw.toDouble, w.ctx, options), w.defaultRaw.toDouble)
      case "object" =>
        val d = JsonLite.parseObject(unescape(w.defaultRaw)); br(ff.objDetails(w.flagKey, d, w.ctx, options), d)
      case other => ZIO.die(new IllegalArgumentException(s"unknown flag type: $other"))
    }
  }

  When("the flag was evaluated with details") {
    for {
      w   <- ScenarioContext.get
      res <- evalByType(w.flags.get, w)
      _ <- ScenarioContext.update(
        _.copy(
          resultValue = res.value,
          resultReason = reasonName(res.reason),
          resultErrorCode = res.errorCode.map(errorCodeName),
          resultVariant = res.variant,
          resultMetadata = res.metadata,
          resultFlagKey = res.flagKey
        )
      )
    } yield ()
  }

  // Result assertions ----------------------------------------------------------------------------

  private def reasonName(r: ResolutionReason): String = r match {
    case ResolutionReason.Static         => "STATIC"
    case ResolutionReason.Default        => "DEFAULT"
    case ResolutionReason.TargetingMatch => "TARGETING_MATCH"
    case ResolutionReason.Split          => "SPLIT"
    case ResolutionReason.Cached         => "CACHED"
    case ResolutionReason.Disabled       => "DISABLED"
    case ResolutionReason.Unknown        => "UNKNOWN"
    case ResolutionReason.Stale          => "STALE"
    case ResolutionReason.Error          => "ERROR"
    case ResolutionReason.Other(v)       => v
  }

  private def errorCodeName(c: ErrorCode): String = c match {
    case ErrorCode.ProviderNotReady    => "PROVIDER_NOT_READY"
    case ErrorCode.ProviderFatal       => "PROVIDER_FATAL"
    case ErrorCode.FlagNotFound        => "FLAG_NOT_FOUND"
    case ErrorCode.ParseError          => "PARSE_ERROR"
    case ErrorCode.TypeMismatch        => "TYPE_MISMATCH"
    case ErrorCode.TargetingKeyMissing => "TARGETING_KEY_MISSING"
    case ErrorCode.InvalidContext      => "INVALID_CONTEXT"
    case ErrorCode.General             => "GENERAL"
  }

  private def valueMatches(w: World, expected: String): Boolean = w.flagType match {
    case "boolean" => w.resultValue == expected.toBoolean
    case "string"  => w.resultValue == expected
    case "integer" => w.resultValue == expected.toInt
    case "float"   => w.resultValue == expected.toDouble
    case "object"  => w.resultValue.asInstanceOf[Map[String, Any]] == JsonLite.parseObject(unescape(expected))
    case _         => false
  }

  Then("the resolved details value should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap(w => assertTrue(valueMatches(w, expected), s"value ${w.resultValue} != '$expected'"))
  }

  Then("the reason should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap(w => assertTrue(w.resultReason == expected, s"reason ${w.resultReason} != $expected"))
  }

  Then("the error-code should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap(w =>
      assertTrue(w.resultErrorCode.contains(expected), s"error-code ${w.resultErrorCode} != $expected")
    )
  }

  Then("the flag key should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap(w => assertTrue(w.resultFlagKey == expected, s"flagKey ${w.resultFlagKey} != $expected"))
  }

  Then("the variant should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      val ok = if (expected == "null") w.resultVariant.isEmpty else w.resultVariant.contains(expected)
      assertTrue(ok, s"variant ${w.resultVariant} != $expected")
    }
  }

  Then("the provider status should be " / string) { (expected: String) =>
    ScenarioContext.get.flatMap { w =>
      w.flags.get.providerStatus.flatMap { st =>
        val actual = st match {
          case ProviderStatus.Ready        => "READY"
          case ProviderStatus.NotReady     => "NOT_READY"
          case ProviderStatus.Error        => "ERROR"
          case ProviderStatus.Fatal        => "FATAL"
          case ProviderStatus.Stale        => "STALE"
          case ProviderStatus.ShuttingDown => "SHUTTING_DOWN"
        }
        assertTrue(actual == expected, s"provider status $actual != $expected")
      }
    }
  }

  // Metadata -------------------------------------------------------------------------------------

  private def metaOk(r: MetaRow, m: FlagMetadata): Boolean = r.metadata_type.toLowerCase match {
    case "string"  => m.getString(r.key).contains(r.value)
    case "integer" => m.getInt(r.key).contains(r.value.toInt)
    case "boolean" => m.getBoolean(r.key).contains(r.value.toBoolean)
    case "float"   => m.get(r.key).contains(MetadataValue.FloatValue(r.value.toFloat))
    case _         => false
  }

  Then("the resolved metadata should contain" / table[MetaRow]) { (rows: List[MetaRow]) =>
    ScenarioContext.get.flatMap { w =>
      assertTrue(rows.forall(r => metaOk(r, w.resultMetadata)), s"metadata mismatch: ${w.resultMetadata.values}")
    }
  }

  Then("the resolved metadata is empty") {
    ScenarioContext.get.flatMap(w => assertTrue(w.resultMetadata.isEmpty, s"metadata not empty: ${w.resultMetadata.values}"))
  }

  // Hooks ----------------------------------------------------------------------------------------

  private def recordingHook(stages: Ref[Chunk[String]], details: Ref[Option[FlagResolution[Any]]]): OFFeatureHook =
    new OFFeatureHook {
      override def before(c: HookContext, h: HookHints): UIO[Option[EvaluationContext]] =
        stages.update(_ :+ "before").as(None)
      override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] =
        stages.update(_ :+ "after") *> details.set(Some(d.asInstanceOf[FlagResolution[Any]]))
      override def error(c: HookContext, e: FeatureFlagError, h: HookHints): UIO[Unit] =
        stages.update(_ :+ "error").unit
      override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
        stages.update(_ :+ "finally") *> details.update(prev => d.map(_.asInstanceOf[FlagResolution[Any]]).orElse(prev))
    }

  Given("a client with added hook") {
    for {
      stages  <- Ref.make(Chunk.empty[String])
      details <- Ref.make(Option.empty[FlagResolution[Any]])
      w       <- ScenarioContext.get
      _       <- w.flags.get.addHook(recordingHook(stages, details))
      _       <- ScenarioContext.update(_.copy(hookStages = Some(stages), hookDetails = Some(details)))
    } yield ()
  }

  Then("the " / string / " hook should have been executed") { (stage: String) =>
    ScenarioContext.get.flatMap(w =>
      w.hookStages.get.get.flatMap(st => assertTrue(st.contains(stage), s"stage '$stage' not in $st"))
    )
  }

  private def hookDetailOk(r: HookRow, d: FlagResolution[Any]): Boolean = r.key match {
    case "flag_key" => d.flagKey == r.value
    case "value"    => if (r.data_type.toLowerCase == "boolean") d.value == r.value.toBoolean else d.value == r.value
    case "variant"  => if (r.value == "null") d.variant.isEmpty else d.variant.contains(r.value)
    case "reason"   => reasonName(d.reason) == r.value
    case "error_code" =>
      val actual = d.errorCode.map(errorCodeName)
      if (r.value == "null") actual.isEmpty else actual.contains(r.value)
    case _ => false
  }

  Then("the " / string / " hooks should be called with evaluation details" / table[HookRow]) {
    (stagesCsv: String, rows: List[HookRow]) =>
      ScenarioContext.get.flatMap { w =>
        for {
          stages  <- w.hookStages.get.get
          details <- w.hookDetails.get.get
          stagesOk = stagesCsv.split(",").map(_.trim).forall(stages.contains)
          detailOk = details.exists(d => rows.forall(r => hookDetailOk(r, d)))
          _ <- assertTrue(stagesOk && detailOk, s"hook stages=$stages details=$details rows=$rows")
        } yield ()
      }
  }

  // Evaluation options (spec 1.5.1) --------------------------------------------------------------

  // Per-invocation hook that tags each stage with its name, so we can assert both execution and ordering.
  private def orderedHook(name: String, log: Ref[Chunk[String]]): OFFeatureHook =
    new OFFeatureHook {
      override def before(c: HookContext, h: HookHints): UIO[Option[EvaluationContext]] =
        log.update(_ :+ s"$name:before").as(None)
      override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] =
        log.update(_ :+ s"$name:after").unit
      override def error(c: HookContext, e: FeatureFlagError, h: HookHints): UIO[Unit] =
        log.update(_ :+ s"$name:error").unit
      override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
        log.update(_ :+ s"$name:finally").unit
    }

  Given("evaluation options containing specific hooks") {
    for {
      log <- Ref.make(Chunk.empty[String])
      opts = EvaluationOptions(orderedHook("first", log), orderedHook("second", log))
      _ <- ScenarioContext.update(_.copy(evalOptions = Some(opts), optionHookLog = Some(log)))
    } yield ()
  }

  When("the flag was evaluated with details using the evaluation options") {
    for {
      w   <- ScenarioContext.get
      res <- evalByType(w.flags.get, w, w.evalOptions.getOrElse(EvaluationOptions.empty))
      _ <- ScenarioContext.update(
        _.copy(
          resultValue = res.value,
          resultReason = reasonName(res.reason),
          resultErrorCode = res.errorCode.map(errorCodeName),
          resultVariant = res.variant,
          resultMetadata = res.metadata,
          resultFlagKey = res.flagKey
        )
      )
    } yield ()
  }

  Then("the specified hooks should execute during evaluation") {
    ScenarioContext.get.flatMap(w =>
      w.optionHookLog.get.get.flatMap { log =>
        val allRan = Set("first", "second").forall(n =>
          log.contains(s"$n:before") && log.contains(s"$n:after") && log.contains(s"$n:finally")
        )
        assertTrue(allRan, s"option hooks did not all execute: $log")
      }
    )
  }

  Then("the hook order should be maintained") {
    ScenarioContext.get.flatMap(w =>
      w.optionHookLog.get.get.flatMap { log =>
        // Spec 4.4.2: before hooks run in registration order; after/finally in reverse.
        val beforeOk  = log.indexOf("first:before") < log.indexOf("second:before")
        val afterOk   = log.indexOf("second:after") < log.indexOf("first:after")
        val finallyOk = log.indexOf("second:finally") < log.indexOf("first:finally")
        assertTrue(beforeOk && afterOk && finallyOk, s"hook order not maintained: $log")
      }
    )
  }

  // Context merging ------------------------------------------------------------------------------

  private def entryCtx(key: String, value: String): EvaluationContext =
    EvaluationContext.builder.attribute(key, value).build

  private def addLevelEntry(w: World, key: String, value: String, level: String): World = level match {
    case "API"          => w.copy(apiCtx = w.apiCtx.merge(entryCtx(key, value)))
    case "Transaction"  => w.copy(txCtx = Some(w.txCtx.getOrElse(EvaluationContext.empty).merge(entryCtx(key, value))))
    case "Client"       => w.copy(clientCtx = w.clientCtx.merge(entryCtx(key, value)))
    case "Invocation"   => w.copy(invocationCtx = w.invocationCtx.merge(entryCtx(key, value)))
    case "Before Hooks" => w.copy(beforeHookCtx = Some(w.beforeHookCtx.getOrElse(EvaluationContext.empty).merge(entryCtx(key, value))))
    case _              => w
  }

  Given("A context entry with key " / string / " and value " / string / " is added to the " / string / " level") {
    (key: String, value: String, level: String) =>
      ScenarioContext.update(w => addLevelEntry(w, key, value, level))
  }

  // zio-bdd's table[T] requires a header row; this gherkin table is headerless (and there is no raw-DataTable access in
  // the step signature), so we ignore the table and use the canonical precedence order. The span-to-target logic below
  // is unaffected by including levels a given scenario doesn't set (the target level still wins the merge).
  Given("A table with levels of increasing precedence") {
    ScenarioContext.update(_.copy(levelOrder = List("API", "Transaction", "Client", "Invocation", "Before Hooks")))
  }

  Given("Context entries for each level from API level down to the " / string / " level, with key " / string / " and value " / string) {
    (target: String, key: String, _ : String) =>
      ScenarioContext.update { w =>
        val upTo = w.levelOrder.span(_ != target) match {
          case (before, t :: _) => before :+ t
          case (before, Nil)    => before
        }
        upTo.foldLeft(w)((acc, level) => addLevelEntry(acc, key, level, level))
      }
  }

  private def ctxHook(ctx: EvaluationContext): OFFeatureHook = new OFFeatureHook {
    override def before(c: HookContext, h: HookHints): UIO[Option[EvaluationContext]] = ZIO.some(ctx)
  }

  When("Some flag was evaluated") {
    for {
      w  <- ScenarioContext.get
      ff = w.flags.get
      _  <- ff.setGlobalContext(w.apiCtx)
      _  <- ff.setClientContext(w.clientCtx)
      _  <- ZIO.foreachDiscard(w.beforeHookCtx)(c => ff.addHook(ctxHook(c)))
      eval = ff.booleanDetails("merge-flag", false, w.invocationCtx)
      run = w.txCtx match {
        case Some(tx) => ff.transaction(context = tx)(eval).unit.orDieWith(e => new RuntimeException(String.valueOf(e)))
        case None     => eval.unit.orDieWith(e => new RuntimeException(String.valueOf(e)))
      }
      _      <- run
      merged <- w.testProvider.get.getRawEvaluations.map(_.last._2)
      _      <- ScenarioContext.update(_.copy(mergedCtx = Some(merged)))
    } yield ()
  }

  Then("The merged context contains an entry with key " / string / " and value " / string) {
    (key: String, value: String) =>
      ScenarioContext.get.flatMap { w =>
        val actual = w.mergedCtx.flatMap(c => Option(c.getValue(key)).flatMap(v => Option(v.asString())))
        assertTrue(actual.contains(value), s"merged $key = $actual != $value")
      }
  }
}
