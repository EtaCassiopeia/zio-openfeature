package zio.openfeature.conformance

import zio.*
import zio.test.*
import zio.openfeature.*
import zio.openfeature.testkit.TestFeatureProvider
import dev.openfeature.sdk.EvaluationContext as OFEvaluationContext

/** Conformance port of the spec's `contextMerging.feature` (upstream main @ 203c25f93495).
  *
  * Verifies merge precedence API → Transaction → Client → Invocation → Before-Hooks (spec 3.2.3). The merged context
  * the wrapper hands to the provider is captured via [[TestFeatureProvider.getEvaluations]] and inspected directly.
  *
  * Each level maps to a wrapper capability: API → `setGlobalContext`, Transaction → `transaction(context = …)`, Client
  * → `setClientContext`, Invocation → the per-call `ctx`, Before-Hooks → a `FeatureHook` whose `before` returns a
  * context.
  */
object ContextMergingConformanceSpec extends ZIOSpecDefault:

  private def entryCtx(key: String, value: String): EvaluationContext =
    EvaluationContext.builder.attribute(key, value).build

  private def hookAdding(ctx: EvaluationContext): FeatureHook = new FeatureHook:
    override def before(c: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
      ZIO.some((ctx, hints))

  private def entry(ctx: OFEvaluationContext, key: String): Option[String] =
    Option(ctx.getValue(key)).flatMap(v => Option(v.asString()))

  /** Apply each level's context, evaluate `merge-flag`, and return the merged context seen by the provider. */
  private def evaluateMerged(
    api: EvaluationContext = EvaluationContext.empty,
    transaction: Option[EvaluationContext] = None,
    client: EvaluationContext = EvaluationContext.empty,
    invocation: EvaluationContext = EvaluationContext.empty,
    beforeHooks: Option[EvaluationContext] = None
  ): ZIO[FeatureFlags & TestFeatureProvider, Nothing, OFEvaluationContext] =
    for
      ff <- ZIO.service[FeatureFlags]
      tp <- ZIO.service[TestFeatureProvider]
      _  <- tp.clearEvaluations
      _  <- ff.setGlobalContext(api)
      _  <- ff.setClientContext(client)
      _  <- ff.clearHooks
      _  <- ZIO.foreachDiscard(beforeHooks)(c => ff.addHook(hookAdding(c)))
      eval = ff.booleanDetails("merge-flag", false, invocation)
      runEval = transaction match
        case Some(tx) => ff.transaction(context = tx)(eval)
        case None     => eval
      _   <- runEval.orDieWith(e => new RuntimeException(String.valueOf(e)))
      ctx <- tp.getEvaluations.map(_.last._2)
    yield ctx

  private def testLayer: ZLayer[Any, Throwable, TestFeatureProvider & FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(Map("merge-flag" -> true))

  def spec = suite("ContextMergingConformanceSpec")(
    suite("A context entry is added to a single level (spec 3.2.3)")(
      test("API level") {
        evaluateMerged(api = entryCtx("key", "value")).map(ctx => assertTrue(entry(ctx, "key").contains("value")))
      },
      test("Transaction level") {
        evaluateMerged(transaction = Some(entryCtx("key", "value")))
          .map(ctx => assertTrue(entry(ctx, "key").contains("value")))
      },
      test("Client level") {
        evaluateMerged(client = entryCtx("key", "value")).map(ctx => assertTrue(entry(ctx, "key").contains("value")))
      },
      test("Invocation level") {
        evaluateMerged(invocation = entryCtx("key", "value"))
          .map(ctx => assertTrue(entry(ctx, "key").contains("value")))
      },
      test("Before Hooks level") {
        evaluateMerged(beforeHooks = Some(entryCtx("key", "value")))
          .map(ctx => assertTrue(entry(ctx, "key").contains("value")))
      }
    ),
    test("Each level contributes a distinct key (spec 3.2.3)") {
      evaluateMerged(
        api = entryCtx("API", "API value"),
        transaction = Some(entryCtx("Transaction", "Transaction value")),
        client = entryCtx("Client", "Client value"),
        invocation = entryCtx("Invocation", "Invocation value"),
        beforeHooks = Some(entryCtx("Before Hooks", "Before Hooks value"))
      ).map(ctx =>
        assertTrue(
          entry(ctx, "API").contains("API value"),
          entry(ctx, "Transaction").contains("Transaction value"),
          entry(ctx, "Client").contains("Client value"),
          entry(ctx, "Invocation").contains("Invocation value"),
          entry(ctx, "Before Hooks").contains("Before Hooks value")
        )
      )
    },
    suite("Higher-precedence level overwrites the same key (spec 3.2.3)")(
      test("only API set → API") {
        evaluateMerged(api = entryCtx("key", "API")).map(ctx => assertTrue(entry(ctx, "key").contains("API")))
      },
      test("API + Transaction → Transaction") {
        evaluateMerged(api = entryCtx("key", "API"), transaction = Some(entryCtx("key", "Transaction")))
          .map(ctx => assertTrue(entry(ctx, "key").contains("Transaction")))
      },
      test("API + Transaction + Client → Client") {
        evaluateMerged(
          api = entryCtx("key", "API"),
          transaction = Some(entryCtx("key", "Transaction")),
          client = entryCtx("key", "Client")
        ).map(ctx => assertTrue(entry(ctx, "key").contains("Client")))
      },
      test("through Invocation → Invocation") {
        evaluateMerged(
          api = entryCtx("key", "API"),
          transaction = Some(entryCtx("key", "Transaction")),
          client = entryCtx("key", "Client"),
          invocation = entryCtx("key", "Invocation")
        ).map(ctx => assertTrue(entry(ctx, "key").contains("Invocation")))
      },
      test("through Before Hooks → Before Hooks") {
        evaluateMerged(
          api = entryCtx("key", "API"),
          transaction = Some(entryCtx("key", "Transaction")),
          client = entryCtx("key", "Client"),
          invocation = entryCtx("key", "Invocation"),
          beforeHooks = Some(entryCtx("key", "Before Hooks"))
        ).map(ctx => assertTrue(entry(ctx, "key").contains("Before Hooks")))
      }
    )
  ).provide(testLayer) @@ TestAspect.withLiveClock @@ TestAspect.sequential
