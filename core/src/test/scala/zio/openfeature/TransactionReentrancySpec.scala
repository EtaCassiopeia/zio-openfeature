package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, Metadata, ProviderState, Value}
import dev.openfeature.sdk.OpenFeatureAPI
import java.util.concurrent.CopyOnWriteArrayList

/** #386: an inner `transaction`/`transactionEither` can opt into running inside the enclosing transaction
  * (`NestedPolicy.Reuse`) instead of failing with `NestedTransactionNotAllowed` (`NestedPolicy.Fail`, still the
  * default). The natural composition — a per-request transaction as middleware, a handler wrapping a sub-operation in
  * its own — must not fail the request, and must not require every wrapper to hand-roll an `inTransaction` guard.
  *
  * Under `Reuse` the inner call's `overrides`, `context` and `cacheEvaluations` are IGNORED (the enclosing transaction
  * is the one running), and its `TransactionResult` reflects the enclosing transaction as of the inner body's
  * completion. Every one of those "ignored" claims is pinned here literally, because silently dropping an argument is
  * the one surprising part of the design and the docs promise it loudly.
  *
  * Shared (cross-compiled) test source dir → braces only, no `enum`/`given`.
  */
object TransactionReentrancySpec extends ZIOSpecDefault {

  /** Answers `true` for `provider-flag` and the default for everything else; records every context it is handed so a
    * test can prove which transaction's context reached the provider.
    */
  private class RecordingProvider extends EventProvider {
    val seenContexts = new CopyOnWriteArrayList[OFEvaluationContext]()

    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Recording" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      seenContexts.add(c)
      if (k == "provider-flag") ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
      else ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      seenContexts.add(c)
      ProviderEvaluations.of[String](d, "DEFAULT")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def build(p: RecordingProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"reentrant-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def withFF[A](f: (FeatureFlags, RecordingProvider) => ZIO[Scope, Any, A]): ZIO[Any, Any, A] =
    ZIO.scoped {
      val p = new RecordingProvider
      build(p).flatMap(ff => f(ff, p))
    }

  def spec = suite("TransactionReentrancySpec")(
    // --- the default is unchanged --------------------------------------------------------------------------
    test("default policy is Fail: a nested transactionEither still fails with NestedTransactionNotAllowed") {
      withFF { (ff, _) =>
        for {
          ran <- Ref.make(false)
          out <- ff.transactionEither() {
            ff.transactionEither()(ran.set(true)).either
          }
          didRun <- ran.get
        } yield assertTrue(
          out.result == Left(Right(FeatureFlagError.NestedTransactionNotAllowed)),
          !didRun
        )
      }
    },
    test("Fail passed explicitly while nested fails before the body runs") {
      withFF { (ff, _) =>
        for {
          ran <- Ref.make(false)
          out <- ff.transactionEither() {
            ff.transactionEither(nested = NestedPolicy.Fail)(ran.set(true)).either
          }
          didRun <- ran.get
        } yield assertTrue(
          out.result == Left(Right(FeatureFlagError.NestedTransactionNotAllowed)),
          !didRun
        )
      }
    },
    // --- Reuse: the inner call runs inside the enclosing transaction ------------------------------------------
    test("Reuse while nested runs the body, and the enclosing transaction's overrides apply inside it") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither(overrides = Map("outer-only" -> true)) {
            ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("outer-only", false))
          }
        } yield assertTrue(out.result.result == true)
      }
    },
    test("Reuse ignores the inner call's own overrides — the key is evaluated as the enclosing transaction sees it") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither() {
            // Inner asks to override to true; the provider (and the enclosing transaction) say the default false.
            ff.transactionEither(overrides = Map("inner-only" -> true), nested = NestedPolicy.Reuse)(
              ff.boolean("inner-only", false)
            )
          }
        } yield assertTrue(out.result.result == false, !out.wasOverridden("inner-only"))
      }
    },
    test("Reuse ignores the inner call's context — the provider is handed the enclosing transaction's context") {
      withFF { (ff, p) =>
        val outerCtx = EvaluationContext.builder.targetingKey("outer").build
        val innerCtx = EvaluationContext.builder.targetingKey("inner").build
        for {
          _ <- ff.transactionEither(context = outerCtx) {
            ff.transactionEither(context = innerCtx, nested = NestedPolicy.Reuse)(ff.boolean("provider-flag", false))
          }
          seen = p.seenContexts.get(p.seenContexts.size - 1).getTargetingKey
        } yield assertTrue(seen == "outer")
      }
    },
    test("Reuse ignores the inner call's cacheEvaluations=false — the enclosing cache still serves a re-read") {
      withFF { (ff, p) =>
        for {
          _ <- ff.transactionEither() {
            ff.boolean("provider-flag", false) *>
              ff.transactionEither(cacheEvaluations = false, nested = NestedPolicy.Reuse)(
                ff.boolean("provider-flag", false)
              )
          }
          // One provider call, not two: the inner read was answered from the enclosing transaction's cache.
          calls = p.seenContexts.size
        } yield assertTrue(calls == 1)
      }
    },
    test("Reuse: the inner result snapshots the enclosing evaluations, including ones made before the inner call") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither() {
            for {
              _     <- ff.boolean("before-inner", false)
              inner <- ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("inside-inner", false))
            } yield inner
          }
        } yield assertTrue(out.result.allFlagKeys == Set("before-inner", "inside-inner"))
      }
    },
    test("Reuse: the enclosing transaction's final result includes evaluations made inside the inner body") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither() {
            ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("inside-inner", false)).unit
          }
        } yield assertTrue(out.wasEvaluated("inside-inner"), out.flagCount == 1)
      }
    },
    test("Reuse: the inner body's own error surfaces as Left(e) and the enclosing transaction keeps running") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither() {
            for {
              inner <- ff.transactionEither(nested = NestedPolicy.Reuse)(ZIO.fail("boom"): IO[String, Int]).either
              after <- ff.boolean("after-failure", false)
            } yield (inner, after)
          }
        } yield assertTrue(
          out.result._1 == Left(Left("boom")),
          out.result._2 == false,
          out.wasEvaluated("after-failure")
        )
      }
    },
    test("Reuse composes across the two forms: transaction outer, transactionEither inner") {
      withFF { (ff, _) =>
        for {
          out <- ff.transaction(overrides = Map("k" -> true)) {
            ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("k", false))
          }
        } yield assertTrue(out.result.result == true)
      }
    },
    test("Reuse composes across the two forms: transactionEither outer, transaction inner") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither(overrides = Map("k" -> true)) {
            ff.transaction(nested = NestedPolicy.Reuse)(ff.boolean("k", false))
          }
        } yield assertTrue(out.result.result == true)
      }
    },
    test("Reuse in a forked child fiber runs inside the parent's transaction (FiberRef inheritance)") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither(overrides = Map("k" -> true)) {
            ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("k", false)).fork.flatMap(_.join)
          }
        } yield assertTrue(out.result.result == true, out.wasOverridden("k"))
      }
    },
    test("Reuse three levels deep still runs inside the outermost transaction") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither(overrides = Map("k" -> true)) {
            ff.transactionEither(nested = NestedPolicy.Reuse) {
              ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("k", false))
            }
          }
        } yield assertTrue(out.result.result.result == true)
      }
    },
    // --- Reuse when there is nothing to reuse behaves exactly like today ---------------------------------------
    test("Reuse when NOT nested opens a real transaction: its overrides apply and inTransaction is true inside") {
      withFF { (ff, _) =>
        for {
          out <- ff.transactionEither(overrides = Map("k" -> true), nested = NestedPolicy.Reuse) {
            ff.boolean("k", false).zip(ff.inTransaction)
          }
          after <- ff.inTransaction
        } yield assertTrue(out.result == ((true, true)), out.wasOverridden("k"), !after)
      }
    },
    test(
      "Reuse when the ENCLOSING transaction has cacheEvaluations=false: the enclosing setting wins, so a re-read hits the provider"
    ) {
      withFF { (ff, p) =>
        for {
          _ <- ff.transactionEither(cacheEvaluations = false) {
            ff.boolean("provider-flag", false) *>
              // Inner asks for caching; the enclosing transaction was opened without it and is the one running.
              ff.transactionEither(cacheEvaluations = true, nested = NestedPolicy.Reuse)(
                ff.boolean("provider-flag", false)
              )
          }
        } yield assertTrue(p.seenContexts.size == 2)
      }
    },
    // --- the companion accessors thread `nested` through -------------------------------------------------------
    test(
      "companion accessors: FeatureFlags.transactionEither(nested = Reuse) inside FeatureFlags.transaction reuses it"
    ) {
      withFF { (ff, _) =>
        val program =
          FeatureFlags.transaction(overrides = Map("k" -> true)) {
            FeatureFlags.transactionEither(nested = NestedPolicy.Reuse)(FeatureFlags.boolean("k", false))
          }
        program
          .provideEnvironment(ZEnvironment(ff))
          .map(out => assertTrue(out.result.result == true, out.wasOverridden("k")))
      }
    },
    test("companion accessors: the default is still Fail — FeatureFlags.transaction nested without a policy fails") {
      withFF { (ff, _) =>
        val program =
          FeatureFlags.transactionEither() {
            FeatureFlags.transaction()(ZIO.unit).either
          }
        program
          .provideEnvironment(ZEnvironment(ff))
          .map(out => assertTrue(out.result.left.exists(_ == FeatureFlagError.NestedTransactionNotAllowed)))
      }
    }
  )
}
