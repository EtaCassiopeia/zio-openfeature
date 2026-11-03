package zio.openfeature

import zio._
import zio.test._
import zio.stream.ZStream
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, Metadata, ProviderState, Value}
import dev.openfeature.sdk.OpenFeatureAPI

/** `currentEvaluatedFlags` answers `Map.empty` both outside any transaction and inside one that has evaluated nothing
  * yet — so audit code that moved outside the transaction boundary would silently record empty flag sets.
  * `transactionEvaluations` makes the distinction visible in the type: `None` iff not in a transaction.
  *
  * The method is a concrete default on the `FeatureFlags` trait (so an external implementor is not broken and no MiMa
  * filter is needed) overridden in `FeatureFlagsLive`; both the override and the trait default are exercised here.
  *
  * Shared (cross-compiled) test source dir → braces only, no `enum`/`given`.
  */
object TransactionEvaluationsSpec extends ZIOSpecDefault {

  private class NoopProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Noop" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      new NoopProvider,
      domain = Some(s"txeval-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def withFF[A](f: FeatureFlags => ZIO[Scope, Any, A]): ZIO[Any, Any, A] =
    ZIO.scoped(buildFF.flatMap(f))

  /** A minimal external-style implementor: overrides ONLY the two members the trait default is built on, and leaves
    * `transactionEvaluations` to the trait. Every other member is `???` and is never reached.
    */
  final private class MinimalFlags(inTx: Boolean, evals: Map[String, FlagEvaluation[_]]) extends FeatureFlags {
    override def inTransaction: UIO[Boolean]                                = ZIO.succeed(inTx)
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]] = ZIO.succeed(evals)

    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def globalContext: UIO[EvaluationContext]                                                    = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def clientContext: UIO[EvaluationContext]                                                    = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]            = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError.TransactionError], TransactionResult[A]]                                 = ???
    override def events: ZStream[Any, Nothing, ProviderEvent]                                                     = ???
    override def providerStatus: UIO[ProviderStatus]                                                              = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                                = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                          = ???
    override def clientMetadata: UIO[ClientMetadata]                                                              = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                            = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                                    = ???
    override def clearHooks: UIO[Unit]                                                                            = ???
    override def hooks: UIO[List[FeatureHook]]                                                                    = ???
    override def track(eventName: String): IO[FeatureFlagError.EvaluationError, Unit]                             = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError.EvaluationError, Unit] = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError.EvaluationError, Unit] = ???
  }

  def spec = suite("TransactionEvaluationsSpec")(
    // --- the distinction the issue asks for ---------------------------------------------------------------------
    test("outside any transaction, transactionEvaluations is None") {
      withFF(ff => ff.transactionEvaluations.map(r => assertTrue(r.isEmpty)))
    },
    test("inside a transaction that has evaluated nothing, transactionEvaluations is Some(Map.empty) — not None") {
      withFF { ff =>
        ff.transactionEither()(ff.transactionEvaluations).map { out =>
          assertTrue(out.result == Some(Map.empty[String, FlagEvaluation[_]]))
        }
      }
    },
    test("inside a transaction after evaluations, it is Some(the evaluated flags), including an overridden one") {
      withFF { ff =>
        ff.transactionEither(overrides = Map("o" -> true)) {
          ff.boolean("o", false) *> ff.boolean("p", false) *> ff.transactionEvaluations
        }.map { out =>
          val evals = out.result.getOrElse(Map.empty[String, FlagEvaluation[_]])
          // Read the booleans out first: inside the `assertTrue` macro, 2.13 cannot unify the existential
          // `FlagEvaluation[_]` element type across the two `apply` calls (found `FlagEvaluation[_$2]`).
          val oOverridden: Boolean = evals("o").wasOverridden
          val pOverridden: Boolean = evals("p").wasOverridden
          assertTrue(evals.keySet == Set("o", "p"), oOverridden, !pOverridden)
        }
      }
    },
    test("after the transaction ends, on the same fiber, it is None again") {
      withFF { ff =>
        for {
          _     <- ff.transactionEither()(ff.boolean("k", false))
          after <- ff.transactionEvaluations
        } yield assertTrue(after.isEmpty)
      }
    },
    test("in a fiber forked inside a transaction, it is Some (the transaction is fiber-local and inherited)") {
      withFF { ff =>
        ff.transactionEither() {
          ff.boolean("k", false) *> ff.transactionEvaluations.fork.flatMap(_.join)
        }.map(out => assertTrue(out.result.exists(_.keySet == Set("k"))))
      }
    },
    test("inside a NestedPolicy.Reuse inner call, it reports the enclosing transaction's evaluations") {
      withFF { ff =>
        ff.transactionEither() {
          ff.boolean("outer-k", false) *>
            ff.transactionEither(nested = NestedPolicy.Reuse)(ff.transactionEvaluations)
        }.map(out => assertTrue(out.result.result.exists(_.keySet == Set("outer-k"))))
      }
    },
    test("companion accessor: FeatureFlags.transactionEvaluations threads through") {
      withFF { ff =>
        val program = FeatureFlags.transactionEvaluations.zip(
          FeatureFlags.transactionEither()(FeatureFlags.boolean("k", false) *> FeatureFlags.transactionEvaluations)
        )
        program.provideEnvironment(ZEnvironment(ff)).map { case (outside, inside) =>
          assertTrue(outside.isEmpty, inside.result.exists(_.contains("k")))
        }
      }
    },
    // --- the old method is unchanged: source-compatible, and still ambiguous ----------------------------------------
    test("currentEvaluatedFlags still answers Map.empty outside a transaction and the flags inside one") {
      withFF { ff =>
        for {
          outside <- ff.currentEvaluatedFlags
          inside  <- ff.transactionEither()(ff.boolean("k", false) *> ff.currentEvaluatedFlags)
        } yield assertTrue(outside.isEmpty, inside.result.keySet == Set("k"))
      }
    },
    // --- the trait default, as an external implementor would inherit it ------------------------------------------
    test("trait default: an implementor overriding only inTransaction/currentEvaluatedFlags gets None outside") {
      new MinimalFlags(inTx = false, evals = Map.empty).transactionEvaluations.map(r => assertTrue(r.isEmpty))
    },
    test("trait default: ... and Some(Map.empty) inside an empty transaction, not None") {
      new MinimalFlags(inTx = true, evals = Map.empty).transactionEvaluations.map { r =>
        assertTrue(r == Some(Map.empty[String, FlagEvaluation[_]]))
      }
    },
    test("trait default: ... and Some(the flags) inside a populated one") {
      for {
        e <- FlagEvaluation.overridden("k", true)
        r <- new MinimalFlags(inTx = true, evals = Map("k" -> e)).transactionEvaluations
      } yield assertTrue(r.exists(_.keySet == Set("k")))
    }
  )
}
