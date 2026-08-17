package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

/** #353: `ContextSource` — pull-based ambient evaluation context.
  *
  * The load-bearing property is the PRECEDENCE SLOT: `global -> transaction -> client -> contextSource -> fiberLocal ->
  * invocation`. Ambient identity must override static client/global context while still losing to `withContext` and to
  * a per-call context. That slot is why this belongs in the library — a `before` hook is merged on top of the finished
  * effective context, so it could only ever take the highest-precedence slot, and `HookContext` exposes one flattened
  * context with no provenance, so a hook cannot rebuild the ordering either.
  *
  * Shared test dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object ContextSourceSpec extends ZIOSpecDefault {

  /** Echoes back the targeting key and a chosen attribute, so a test can see exactly which context won. */
  private class ContextEchoProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "ContextEcho" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      val seen =
        if (c == null) "no-context"
        else
          k match {
            case "targeting" => Option(c.getTargetingKey).getOrElse("none")
            case attr        => Option(c.getValue(attr)).map(_.asString()).getOrElse("none")
          }
      ProviderEvaluations.of[String](seen, "TARGETING_MATCH")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def build(source: ContextSource, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      new ContextEchoProvider,
      domain = Some(s"ctxsrc-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds),
      contextSource = source
    )

  private def attr(k: String, v: String): EvaluationContext =
    EvaluationContext.withAttributes(k -> AttributeValue.string(v))

  def spec = suite("ContextSourceSpec")(
    suite("the type itself")(
      test("empty contributes nothing") {
        ContextSource.empty.current.map(c => assertTrue(c.isEmpty))
      },
      test("apply re-evaluates its effect per call, rather than capturing one value") {
        // A pull-based source that captured its value once would not be pull-based at all.
        for {
          counter <- Ref.make(0)
          source = ContextSource(counter.updateAndGet(_ + 1).map(n => EvaluationContext(s"user-$n")))
          first  <- source.current
          second <- source.current
        } yield assertTrue(first.targetingKey.contains("user-1"), second.targetingKey.contains("user-2"))
      },
      test("++ merges, and the right-hand source wins on a collision") {
        val left  = ContextSource(ZIO.succeed(attr("a", "left").withTargetingKey("left-key")))
        val right = ContextSource(ZIO.succeed(attr("a", "right")))
        (left ++ right).current.map { c =>
          assertTrue(
            c.getString("a").contains("right"),
            // The left-hand contribution survives where there is no collision.
            c.targetingKey.contains("left-key")
          )
        }
      },
      test("++ with empty is a no-op in both directions") {
        val source = ContextSource(ZIO.succeed(EvaluationContext("only")))
        for {
          l <- (source ++ ContextSource.empty).current
          r <- (ContextSource.empty ++ source).current
        } yield assertTrue(l.targetingKey.contains("only"), r.targetingKey.contains("only"))
      }
    ),
    suite("precedence — the reason this is in the library")(
      test("the source reaches the provider when nothing else supplies context") {
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "basic").flatMap { ff =>
            ff.string("targeting", "unset").map(seen => assertTrue(seen == "from-source"))
          }
        }
      },
      test("the source OVERRIDES global context") {
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "vs-global").flatMap { ff =>
            for {
              _    <- ff.setGlobalContext(EvaluationContext("from-global"))
              seen <- ff.string("targeting", "unset")
            } yield assertTrue(seen == "from-source")
          }
        }
      },
      test("the source OVERRIDES client context") {
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "vs-client").flatMap { ff =>
            for {
              _    <- ff.setClientContext(EvaluationContext("from-client"))
              seen <- ff.string("targeting", "unset")
            } yield assertTrue(seen == "from-source")
          }
        }
      },
      test("the source OVERRIDES transaction context") {
        // `transactionEither` rather than `transaction`: on 2.13 `transaction`'s error channel erases to `Any`.
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "vs-transaction").flatMap { ff =>
            ff.transactionEither(context = EvaluationContext("from-tx"), cacheEvaluations = false) {
              ff.string("targeting", "unset")
            }.map(txResult => assertTrue(txResult.result == "from-source"))
          }
        }
      },
      test("the source LOSES to a fiber-local withContext") {
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "vs-fiber").flatMap { ff =>
            ff.withContext(EvaluationContext("from-fiber")) {
              ff.string("targeting", "unset")
            }.map(seen => assertTrue(seen == "from-fiber"))
          }
        }
      },
      test("the source LOSES to a per-call invocation context") {
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(EvaluationContext("from-source"))), "vs-invocation").flatMap { ff =>
            ff.string("targeting", "unset", EvaluationContext("from-call"))
              .map(seen => assertTrue(seen == "from-call"))
          }
        }
      },
      test("non-colliding attributes from every layer survive together") {
        // Precedence applies per key: the source must not wipe out attributes it does not set.
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(attr("ambient", "yes"))), "merge").flatMap { ff =>
            for {
              _           <- ff.setGlobalContext(attr("global", "yes"))
              fromGlobal  <- ff.string("global", "none")
              fromAmbient <- ff.string("ambient", "none")
            } yield assertTrue(fromGlobal == "yes", fromAmbient == "yes")
          }
        }
      }
    ),
    suite("configuration and robustness")(
      test("withContextSource on FeatureFlagsConfig carries the source") {
        val source = ContextSource(ZIO.succeed(EvaluationContext("configured")))
        val config = FeatureFlagsConfig().withContextSource(source)
        assertTrue(config.contextSource eq source)
      },
      test("a source is consulted per evaluation, not once per client") {
        ZIO.scoped {
          for {
            counter <- Ref.make(0)
            source = ContextSource(counter.updateAndGet(_ + 1).map(n => EvaluationContext(s"call-$n")))
            ff    <- build(source, "per-eval")
            first <- ff.string("targeting", "unset")
            next  <- ff.string("targeting", "unset")
          } yield assertTrue(first == "call-1", next == "call-2")
        }
      },
      test("track consults the source too, not just evaluation") {
        // `trackImpl` shares `effectiveContext` with evaluation, which is what makes the "on every evaluation and on
        // track" claim in the scaladoc/docs true. Pin it, so a future refactor cannot quietly drop the source here.
        ZIO.scoped {
          build(ContextSource(ZIO.succeed(attr("ambient", "yes"))), "track").flatMap { ff =>
            for {
              _      <- ff.track("checkout", EvaluationContext("from-call"))
              events <- ff.trackedEvents
            } yield assertTrue(
              events.size == 1,
              events.head._1 == "checkout",
              events.head._2.getString("ambient").contains("yes"),
              // The per-call context still wins, exactly as on the evaluation path.
              events.head._2.targetingKey.contains("from-call")
            )
          }
        }
      },
      test("the default config supplies an empty source, leaving behaviour unchanged") {
        ZIO.scoped {
          build(ContextSource.empty, "default").flatMap { ff =>
            for {
              _    <- ff.setGlobalContext(EvaluationContext("from-global"))
              seen <- ff.string("targeting", "unset")
            } yield assertTrue(seen == "from-global")
          }
        }
      }
    )
  ) @@ sequential @@ withLiveClock
}
