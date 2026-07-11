package zio.openfeature.conformance

import io.cucumber.datatable.DataTable
import io.cucumber.scala.{EN, ScalaDsl}
import zio._
import zio.stream.SubscriptionRef
import zio.openfeature._
import zio.openfeature.testkit.TestFeatureProvider
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, OpenFeatureAPIFactory}
import dev.openfeature.sdk.providers.memory.InMemoryProvider

import scala.jdk.CollectionConverters._

/** Cucumber glue driving the ZIO `FeatureFlags` API for the OpenFeature gherkin conformance suite.
  *
  * Cucumber instantiates this class fresh per scenario, so the mutable `var`s below are per-scenario state. Effects are
  * run synchronously via `Unsafe` since Cucumber steps are blocking. Provider choice depends on the Background step:
  * `InMemoryProvider` for value/variant/reason/metadata scenarios; the testkit `TestFeatureProvider` for
  * context-merging (it records the merged context the wrapper passes down) and provider-status scenarios.
  */
class ConformanceSteps extends ScalaDsl with EN {

  private val runtime = Runtime.default
  private def run[A](z: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

  private def check(cond: Boolean, msg: => String): Unit = if (!cond) throw new AssertionError(msg)

  // Lifecycle
  private var scope: Scope.Closeable            = null
  private var flags: FeatureFlags               = null
  private var testProvider: TestFeatureProvider = null

  // Evaluation state
  private var flagKey: String             = null
  private var flagType: String            = null // boolean | string | integer | float | object
  private var defaultRaw: String          = null
  private var evalCtx: EvaluationContext  = EvaluationContext.empty
  private var result: FlagResolution[Any] = null

  // Hook state
  private val hookStages                       = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  private var hookDetails: FlagResolution[Any] = null

  // Context-merging state
  private var apiCtx: EvaluationContext                = EvaluationContext.empty
  private var clientCtx: EvaluationContext             = EvaluationContext.empty
  private var invocationCtx: EvaluationContext         = EvaluationContext.empty
  private var txCtx: Option[EvaluationContext]         = None
  private var beforeHookCtx: Option[EvaluationContext] = None
  private var levelOrder: List[String]                 = Nil
  private var mergedCtx: OFEvaluationContext           = null

  After { (_: io.cucumber.scala.Scenario) =>
    if (scope != null) run(scope.close(Exit.unit))
  }

  // Provider setup

  private def openScope(): Scope.Closeable = {
    if (scope != null) run(scope.close(Exit.unit))
    run(Scope.make)
  }

  private def setupInMemory(): Unit = {
    val sc        = openScope()
    val statusRef = run(SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready))
    val api       = OpenFeatureAPIFactory.create()
    val domain    = s"conf-${java.util.UUID.randomUUID()}"
    val ff = run(
      sc.extend[Any](
        FeatureFlags
          .fromProviderWithDomain(new InMemoryProvider(Fixtures.inMemoryFlags), domain, statusRef, api = Some(api))
          .build
          .map(_.get)
      )
    )
    scope = sc; flags = ff; testProvider = null
  }

  private def setupTestProvider(): Unit = {
    val sc  = openScope()
    val env = run(sc.extend[Any](TestFeatureProvider.layer(Fixtures.testProviderSeed).build))
    testProvider = env.get[TestFeatureProvider]
    flags = env.get[FeatureFlags]
    scope = sc
  }

  private def providerStatusOf(word: String): ProviderStatus = word match {
    case "not ready" => ProviderStatus.NotReady
    case "error"     => ProviderStatus.Error
    case "fatal"     => ProviderStatus.Fatal
    case "stale"     => ProviderStatus.Stale
    case other       => throw new IllegalArgumentException(s"unknown provider status: $other")
  }

  // A "stable" provider needs real variants/reasons → InMemoryProvider; any non-ready status is simulated by the
  // testkit provider whose status we then flip.
  Given("""^a (stable|not ready|error|fatal|stale) provider$""") { (status: String) =>
    if (status == "stable") setupInMemory()
    else {
      setupTestProvider()
      run(testProvider.setStatus(providerStatusOf(status)))
    }
  }

  Given("""^a stable provider with retrievable context is registered$""") { () =>
    setupTestProvider()
  }

  // Flag definition + context

  Given("""^a (\w+)-flag with key "([^"]*)" and a fallback value "(.*)"$""") {
    (typ: String, key: String, default: String) =>
      flagType = typ.toLowerCase
      flagKey = key
      defaultRaw = default
  }

  Given("""^a context containing a key "([^"]*)", with type "([^"]*)" and with value "([^"]*)"$""") {
    (key: String, typ: String, value: String) =>
      val av = typ.toLowerCase match {
        case "integer" => AttributeValue.IntValue(value.toInt)
        case "boolean" => AttributeValue.BoolValue(value.toBoolean)
        case _         => AttributeValue.StringValue(value)
      }
      evalCtx = evalCtx.withAttribute(key, av)
  }

  // The typed EvaluationContext has no null attribute; an absent key is equivalent (→ DEFAULT), so this is a no-op.
  Given("""^a context containing a key "([^"]*)" with null value$""")((_: String) => ())

  // Evaluation

  private def defaultBoolean = defaultRaw.toBoolean
  private def defaultInt     = defaultRaw.toInt
  private def defaultDouble  = defaultRaw.toDouble
  private def defaultObject  = JsonLite.parseObject(unescape(defaultRaw))

  private def unescape(s: String): String = s.replace("\\\"", "\"")

  private def bridge[A](e: FeatureFlagError, default: A): FlagResolution[A] =
    FlagResolution(
      default,
      None,
      ResolutionReason.Error,
      FlagMetadata.empty,
      flagKey,
      Some(FeatureFlagError.toErrorCode(e)),
      Some(e.message)
    )

  private def evalDetails[A](io: IO[FeatureFlagError, FlagResolution[A]], default: A): FlagResolution[A] =
    run(io.catchAll(e => ZIO.succeed(bridge(e, default))))

  When("""^the flag was evaluated with details$""") { () =>
    result = (flagType match {
      case "boolean" => evalDetails(flags.booleanDetails(flagKey, defaultBoolean, evalCtx), defaultBoolean)
      case "string"  => evalDetails(flags.stringDetails(flagKey, defaultRaw, evalCtx), defaultRaw)
      case "integer" => evalDetails(flags.intDetails(flagKey, defaultInt, evalCtx), defaultInt)
      case "float"   => evalDetails(flags.doubleDetails(flagKey, defaultDouble, evalCtx), defaultDouble)
      case "object"  => evalDetails(flags.objDetails(flagKey, defaultObject, evalCtx), defaultObject)
      case other     => throw new IllegalArgumentException(s"unknown flag type: $other")
    }).asInstanceOf[FlagResolution[Any]]
  }

  // Assertions on the resolved details

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

  private def valueMatches(r: FlagResolution[Any], expected: String): Boolean = flagType match {
    case "boolean" => r.value == expected.toBoolean
    case "string"  => r.value == expected
    case "integer" => r.value == expected.toInt
    case "float"   => r.value == expected.toDouble
    case "object"  => r.value.asInstanceOf[Map[String, Any]] == JsonLite.parseObject(unescape(expected))
    case _         => false
  }

  Then("""^the resolved details value should be "(.*)"$""") { (expected: String) =>
    check(valueMatches(result, expected), s"value ${result.value} != expected '$expected'")
  }

  Then("""^the reason should be "([^"]*)"$""") { (expected: String) =>
    check(reasonName(result.reason) == expected, s"reason ${reasonName(result.reason)} != $expected")
  }

  Then("""^the error-code should be "([^"]*)"$""") { (expected: String) =>
    val actual = result.errorCode.map(errorCodeName)
    check(actual.contains(expected), s"error-code $actual != $expected")
  }

  Then("""^the flag key should be "([^"]*)"$""") { (expected: String) =>
    check(result.flagKey == expected, s"flagKey ${result.flagKey} != $expected")
  }

  Then("""^the variant should be "([^"]*)"$""") { (expected: String) =>
    if (expected == "null") check(result.variant.isEmpty, s"variant ${result.variant} should be empty")
    else check(result.variant.contains(expected), s"variant ${result.variant} != $expected")
  }

  Then("""^the provider status should be "([^"]*)"$""") { (expected: String) =>
    val actual = run(flags.providerStatus) match {
      case ProviderStatus.Ready        => "READY"
      case ProviderStatus.NotReady     => "NOT_READY"
      case ProviderStatus.Error        => "ERROR"
      case ProviderStatus.Fatal        => "FATAL"
      case ProviderStatus.Stale        => "STALE"
      case ProviderStatus.ShuttingDown => "SHUTTING_DOWN"
    }
    check(actual == expected, s"provider status $actual != $expected")
  }

  // Metadata

  private def assertMetadataRow(metaKey: String, metaType: String, value: String, m: FlagMetadata): Unit =
    metaType.toLowerCase match {
      case "string"  => check(m.getString(metaKey).contains(value), s"metadata $metaKey string != $value")
      case "integer" => check(m.getInt(metaKey).contains(value.toInt), s"metadata $metaKey int != $value")
      case "boolean" => check(m.getBoolean(metaKey).contains(value.toBoolean), s"metadata $metaKey bool != $value")
      case "float" =>
        check(m.get(metaKey).contains(MetadataValue.FloatValue(value.toFloat)), s"metadata $metaKey float != $value")
      case other => throw new IllegalArgumentException(s"unknown metadata type: $other")
    }

  Then("""^the resolved metadata should contain$""") { (table: DataTable) =>
    rows(table).foreach(row => assertMetadataRow(row("key"), row("metadata_type"), row("value"), result.metadata))
  }

  Then("""^the resolved metadata is empty$""") { () =>
    check(result.metadata.isEmpty, s"metadata should be empty but was ${result.metadata.values}")
  }

  // Hooks

  private def recordingHook: FeatureHook = new FeatureHook {
    override def before(c: HookContext, h: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO.succeed(hookStages.add("before")).as(None)
    override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] =
      ZIO.succeed { hookStages.add("after"); hookDetails = d.asInstanceOf[FlagResolution[Any]] }
    override def error(c: HookContext, e: FeatureFlagError, h: HookHints): UIO[Unit] =
      ZIO.succeed(hookStages.add("error")).unit
    override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
      ZIO.succeed { hookStages.add("finally"); d.foreach(r => hookDetails = r.asInstanceOf[FlagResolution[Any]]) }
  }

  Given("""^a client with added hook$""") { () =>
    run(flags.addHook(recordingHook))
  }

  Then("""^the "([^"]*)" hook should have been executed$""") { (stage: String) =>
    check(hookStages.contains(stage), s"hook stage '$stage' not in ${hookStages.asScala.toList}")
  }

  Then("""^the "([^"]*)" hooks should be called with evaluation details$""") { (stagesCsv: String, table: DataTable) =>
    stagesCsv.split(",").map(_.trim).foreach(stage => check(hookStages.contains(stage), s"hook stage '$stage' not run"))
    rows(table).foreach(row => assertHookDetailRow(row("data_type"), row("key"), row("value")))
  }

  private def assertHookDetailRow(dataType: String, key: String, value: String): Unit = key match {
    case "flag_key" => check(hookDetails.flagKey == value, s"flag_key ${hookDetails.flagKey} != $value")
    case "value" =>
      val ok = dataType.toLowerCase match {
        case "boolean" => hookDetails.value == value.toBoolean
        case _         => hookDetails.value == value
      }
      check(ok, s"hook value ${hookDetails.value} != $value")
    case "variant" =>
      if (value == "null") check(hookDetails.variant.isEmpty, s"variant ${hookDetails.variant} should be null")
      else check(hookDetails.variant.contains(value), s"variant ${hookDetails.variant} != $value")
    case "reason" =>
      check(reasonName(hookDetails.reason) == value, s"reason ${reasonName(hookDetails.reason)} != $value")
    case "error_code" =>
      val actual = hookDetails.errorCode.map(errorCodeName)
      if (value == "null") check(actual.isEmpty, s"error_code $actual should be null")
      else check(actual.contains(value), s"error_code $actual != $value")
    case other => throw new IllegalArgumentException(s"unknown hook detail key: $other")
  }

  // Context merging

  private def entryCtx(key: String, value: String): EvaluationContext =
    EvaluationContext.builder.attribute(key, value).build

  private def addLevelEntry(key: String, value: String, level: String): Unit = level match {
    case "API"         => apiCtx = apiCtx.merge(entryCtx(key, value))
    case "Transaction" => txCtx = Some(txCtx.getOrElse(EvaluationContext.empty).merge(entryCtx(key, value)))
    case "Client"      => clientCtx = clientCtx.merge(entryCtx(key, value))
    case "Invocation"  => invocationCtx = invocationCtx.merge(entryCtx(key, value))
    case "Before Hooks" =>
      beforeHookCtx = Some(beforeHookCtx.getOrElse(EvaluationContext.empty).merge(entryCtx(key, value)))
    case other => throw new IllegalArgumentException(s"unknown level: $other")
  }

  Given("""^A context entry with key "([^"]*)" and value "([^"]*)" is added to the "([^"]*)" level$""") {
    (key: String, value: String, level: String) => addLevelEntry(key, value, level)
  }

  Given("""^A table with levels of increasing precedence$""") { (table: DataTable) =>
    levelOrder = table.asLists().asScala.toList.map(_.get(0))
  }

  // Each level from API down to the target gets key with its own level name as the value, so the merged result equals
  // the highest-precedence (target) level's name.
  Given(
    """^Context entries for each level from API level down to the "([^"]*)" level, with key "([^"]*)" and value "(.*)"$"""
  ) { (target: String, key: String, _: String) =>
    val upTo = levelOrder.span(_ != target) match { case (before, t :: _) => before :+ t; case (before, Nil) => before }
    upTo.foreach(level => addLevelEntry(key, level, level))
  }

  private def ctxHook(ctx: EvaluationContext): FeatureHook = new FeatureHook {
    override def before(c: HookContext, h: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO.some((ctx, h))
  }

  When("""^Some flag was evaluated$""") { () =>
    run(flags.setGlobalContext(apiCtx))
    run(flags.setClientContext(clientCtx))
    beforeHookCtx.foreach(c => run(flags.addHook(ctxHook(c))))
    val eval = flags.booleanDetails("merge-flag", false, invocationCtx)
    val full = txCtx match {
      case Some(tx) =>
        flags.transaction(context = tx)(eval).unit.orDieWith(e => new RuntimeException(String.valueOf(e)))
      case None => eval.unit.orDieWith(e => new RuntimeException(String.valueOf(e)))
    }
    run(full)
    mergedCtx = run(testProvider.getEvaluations).last._2
  }

  Then("""^The merged context contains an entry with key "([^"]*)" and value "([^"]*)"$""") {
    (key: String, value: String) =>
      val actual = Option(mergedCtx.getValue(key)).flatMap(v => Option(v.asString()))
      check(actual.contains(value), s"merged context $key = $actual != $value")
  }

  // DataTable helper: turn a header+rows table into a list of column-keyed maps.
  private def rows(table: DataTable): List[Map[String, String]] = {
    val all    = table.asLists().asScala.toList.map(_.asScala.toList)
    val header = all.head
    all.tail.map(r => header.zip(r).toMap)
  }
}
