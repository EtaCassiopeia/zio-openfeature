package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, ImmutableMetadata, Metadata}
import dev.openfeature.sdk.{OpenFeatureAPI, ProviderEvaluation, ProviderState, Value}

/** A read served from a transaction's cache is the recorded resolution re-labelled `Cached`: it keeps the provider's
  * variant and flag metadata.
  *
  * Shared (cross-compiled) test source dir: braces only.
  */
object TransactionCachedDetailsSpec extends ZIOSpecDefault {

  private class ExperimentProvider extends EventProvider {
    val calls                                               = new java.util.concurrent.atomic.AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Experiment" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      calls.incrementAndGet()
      new ProviderEvaluation[String](
        "B",
        "B",
        "TARGETING_MATCH",
        null,
        null,
        ImmutableMetadata.builder().addString("experiment", "exp-1").build()
      )
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def build(p: ExperimentProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"txcached-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  def spec = suite("TransactionCachedDetailsSpec")(
    test("a cached re-read keeps the provider's variant and flag metadata, re-labelled Cached") {
      ZIO.scoped {
        val p = new ExperimentProvider
        for {
          ff   <- build(p)
          both <- ff.transaction()(ff.stringDetails("exp", "A").zip(ff.stringDetails("exp", "A")))
          (first, second) = both.result
          calls           = p.calls.get()
        } yield assertTrue(
          first.variant == Some("B"),
          calls == 1,
          second.reason == ResolutionReason.Cached,
          second.value == "B",
          second.variant == Some("B"),
          second.metadata.getString("experiment") == Some("exp-1"),
          second.errorCode.isEmpty
        )
      }
    },
    test("an after hook sees the variant on a cached read") {
      ZIO.scoped {
        val p = new ExperimentProvider
        for {
          ff   <- build(p)
          seen <- Ref.make(List.empty[Option[String]])
          _    <- ff.addHook(new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              seen.update(_ :+ details.variant)
          })
          _        <- ff.transaction()(ff.stringDetails("exp", "A") *> ff.stringDetails("exp", "A"))
          variants <- seen.get
        } yield assertTrue(variants == List(Some("B"), Some("B")))
      }
    }
  )
}
