package zio.openfeature.testkit

import dev.openfeature.sdk.{ErrorCode => OFErrorCode, ImmutableContext, OpenFeatureAPI, Value}
import dev.openfeature.sdk.exceptions.GeneralError
import dev.openfeature.sdk.providers.memory.{Flag, InMemoryProvider}
import zio._
import zio.openfeature._
import zio.test._
import zio.test.Assertion._
import scala.jdk.CollectionConverters._

/** #369: `TestFeatureProvider` answered an absent key with `reason = DEFAULT` and no error code, which reads to a
  * `MultiProvider` chain as "I answered" — so a chain never fell through past the test provider, and the testkit could
  * not reproduce the fall-through that #355/#370 fixed for the config providers. An absent key is `FLAG_NOT_FOUND`.
  *
  * The chain tests are the load-bearing ones: the unit assertions pin what each evaluation method reports, but only the
  * chain shows the user-visible consequence, and only the hook test shows the observable cost (an absent key now runs
  * the `error` stage rather than `after`).
  *
  * The SECOND provider in each chain is the SDK's own `InMemoryProvider`, deliberately not another
  * `TestFeatureProvider`: the Java SDK keys a chain's providers by metadata name and silently keeps only the last of
  * two same-named instances, so a chain of two test providers is a chain of one and the precedence test would pass for
  * the wrong reason (#371). Distinct names keep both providers in the chain, so the ordering assertions mean what they
  * say. Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object AbsentKeyChainSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  /** An `InMemoryProvider` holding string flags, each served from a single static variant (reason `STATIC`). */
  private def inMemory(flags: (String, String)*): InMemoryProvider = {
    val built: Map[String, Flag[_]] = flags.map { case (k, v) =>
      k -> Flag.builder[String]().variant("on", v).defaultVariant("on").build()
    }.toMap
    new InMemoryProvider(built.asJava)
  }

  private def chainOf(first: TestFeatureProvider, second: InMemoryProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(
        FeatureFlags.multiProvider(List(first, second)),
        FeatureFlagsConfig(),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(_.get[FeatureFlags])

  def spec = suite("AbsentKeyChainSpec")(
    suite("an absent key is FLAG_NOT_FOUND, not DEFAULT")(
      test("all six evaluation methods report FLAG_NOT_FOUND with the caller's default as the value") {
        for {
          provider <- TestFeatureProvider.make
          b = provider.getBooleanEvaluation("missing", true, ctx)
          s = provider.getStringEvaluation("missing", "d", ctx)
          i = provider.getIntegerEvaluation("missing", Int.box(7), ctx)
          l = provider.getLongEvaluation("missing", java.lang.Long.valueOf(7L), ctx)
          d = provider.getDoubleEvaluation("missing", Double.box(1.5), ctx)
          o = provider.getObjectEvaluation("missing", new Value("obj-default"), ctx)
        } yield assertTrue(
          b.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          s.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          i.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          l.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          d.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          o.getErrorCode == OFErrorCode.FLAG_NOT_FOUND,
          // The caller's default rides along with the code, so nothing throws and nothing returns null.
          b.getValue == true,
          s.getValue == "d",
          i.getValue == 7,
          l.getValue == 7L,
          d.getValue == 1.5,
          o.getValue.asString == "obj-default",
          // Pin the reason too: ERROR is what flips hooks from `after` to `error`, the observable half of this change.
          b.getReason == "ERROR",
          s.getReason == "ERROR",
          i.getReason == "ERROR",
          l.getReason == "ERROR",
          d.getReason == "ERROR",
          o.getReason == "ERROR",
          // And the message names the key, so an operator can tell which flag was unset.
          s.getErrorMessage.contains("missing")
        )
      },
      test("a present key still reports TARGETING_MATCH with no error code on every evaluation method") {
        // Stored types deliberately differ from the requested ones where the provider widens (an Int read as Long, an
        // Int read as Double), so the conversion paths are pinned to the same reason as the direct ones.
        for {
          provider <- TestFeatureProvider.make(
            Map("present" -> true, "name" -> "x", "count" -> 3, "big" -> 4, "rate" -> 5, "obj" -> Map("k" -> "v"))
          )
          b   = provider.getBooleanEvaluation("present", false, ctx)
          s   = provider.getStringEvaluation("name", "d", ctx)
          i   = provider.getIntegerEvaluation("count", Int.box(0), ctx)
          l   = provider.getLongEvaluation("big", java.lang.Long.valueOf(0L), ctx)
          d   = provider.getDoubleEvaluation("rate", Double.box(0.0), ctx)
          o   = provider.getObjectEvaluation("obj", new Value(), ctx)
          all = List(b, s, i, l, d, o)
        } yield assertTrue(
          all.forall(_.getReason == "TARGETING_MATCH"),
          all.forall(_.getErrorCode == null),
          b.getValue == true,
          s.getValue == "x",
          i.getValue == 3,
          l.getValue == 4L,
          d.getValue == 5.0,
          o.getValue.asStructure.getValue("k").asString == "v"
        )
      },
      test("an absent key is still recorded as an evaluation") {
        for {
          provider <- TestFeatureProvider.make
          _ = provider.getStringEvaluation("missing", "d", ctx)
          seen    <- provider.wasEvaluated("missing")
          count   <- provider.evaluationCount("missing")
          history <- provider.getEvaluations
        } yield assertTrue(seen, count == 1, history.map(_._1) == List("missing"))
      },
      test("a configured ErrorMode still takes precedence over absence") {
        // Behaviour controls simulate a failing provider for EVERY key; an absent key must not downgrade that throw
        // into a quiet FLAG_NOT_FOUND result.
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setErrorMode(TestFeatureProvider.ErrorMode.General)
          exit     <- ZIO.attempt(provider.getBooleanEvaluation("missing", true, ctx)).exit
        } yield assert(exit)(fails(isSubtype[GeneralError](anything)))
      }
    ),
    suite("MultiProvider chains fall through")(
      test("a key absent from the test provider resolves from the next provider") {
        // THE point of the issue: the test provider does not hold the key, so the chain must consult the second.
        ZIO.scoped {
          for {
            first  <- TestFeatureProvider.make(Map("unrelated" -> true))
            ff     <- chainOf(first, inMemory("only-in-second" -> "from-second"))
            result <- ff.stringDetails("only-in-second", "fallback")
          } yield assertTrue(
            result.value == "from-second",
            result.errorCode.isEmpty,
            result.reason == ResolutionReason.Static
          )
        }
      },
      test("the test provider still wins when it does hold the key") {
        // Guards the other direction: fall-through must not become "last one wins". Both providers hold `shadowed`.
        ZIO.scoped {
          for {
            first  <- TestFeatureProvider.make(Map("shadowed" -> "from-first"))
            ff     <- chainOf(first, inMemory("shadowed" -> "from-second"))
            result <- ff.stringDetails("shadowed", "fallback")
          } yield assertTrue(
            result.value == "from-first",
            result.errorCode.isEmpty,
            result.reason == ResolutionReason.TargetingMatch
          )
        }
      },
      test("a key absent from every provider yields the caller's default with FLAG_NOT_FOUND through the client") {
        // Exercised through `FeatureFlags`, not the raw chain: when every provider reports FLAG_NOT_FOUND the chain
        // has no value to give and answers null — substituting the caller's default is the SDK client's job. Asserting
        // it here is what proves the change costs users nothing: no failure, same value, and now a truthful code.
        ZIO.scoped {
          for {
            first <- TestFeatureProvider.make
            ff    <- chainOf(first, inMemory())
            s     <- ff.stringDetails("nowhere", "fallback")
            b     <- ff.boolean("nowhere", true)
          } yield assertTrue(
            s.value == "fallback",
            s.errorCode.contains(ErrorCode.FlagNotFound),
            s.reason == ResolutionReason.Error,
            b
          )
        }
      }
    ),
    suite("hooks observe the change")(
      test("an absent key runs the `error` stage and a present key runs `after`") {
        for {
          provider <- TestFeatureProvider.make(Map("present" -> true))
          stages   <- Ref.make(List.empty[String])
          hook = new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              stages.update(_ :+ s"after:${ctx.flagKey}")
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              stages.update(_ :+ s"error:${ctx.flagKey}:${err.getClass.getSimpleName}")
          }
          config = FeatureFlagsConfig().withHooks(List(hook))
          result <- ZIO.scoped {
            FeatureFlags
              .fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
              .build
              .map(_.get[FeatureFlags])
              .flatMap { ff =>
                for {
                  present <- ff.boolean("present", default = false)
                  missing <- ff.boolean("missing", default = false)
                  seen    <- stages.get
                } yield assertTrue(
                  present,
                  !missing,
                  seen == List("after:present", "error:missing:FlagNotFound")
                )
              }
          }
        } yield result
      }
    )
  )
}
