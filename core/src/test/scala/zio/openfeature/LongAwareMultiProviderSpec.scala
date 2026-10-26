package zio.openfeature

import java.util.concurrent.ConcurrentLinkedQueue

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.{LongAwareMultiProvider, ProviderEvaluations}
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  FlagEvaluationDetails,
  FlagEvaluationOptions,
  FlagValueType,
  Hook,
  HookContext => OFHookContext,
  ImmutableContext,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}
import dev.openfeature.sdk.multiprovider.Strategy

import scala.jdk.CollectionConverters._

/** #533: the SDK's `MultiProvider` has no `getLongEvaluation`, so a long read went through each child's double getter.
  * `FeatureFlags.multiProvider` and `defaultAcquireCompose` build a [[LongAwareMultiProvider]], whose long reads run
  * the strategy over each child's own long getter, through the child's provider hooks.
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object LongAwareMultiProviderSpec extends ZIOSpecDefault {

  sealed private trait LongAnswer
  final private case class Holds(value: Long)       extends LongAnswer
  final private case class Fails(code: OFErrorCode) extends LongAnswer

  /** A child whose long getter answers from `longs` (a missing key is `FLAG_NOT_FOUND`), tagging each answer with its
    * own name as the variant. Its double getter answers `TYPE_MISMATCH`, as this library's providers do for integer
    * values, so a long read that went through it would show.
    */
  private class LongChild(name: String, longs: Map[String, LongAnswer], hooks: List[Hook[_]] = Nil)
      extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                     = new Metadata { def getName: String = name }
    override def getProviderHooks: java.util.List[Hook[_]] = hooks.asJava
    override def getLongEvaluation(
      k: String,
      d: java.lang.Long,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Long] =
      longs.get(k) match {
        case Some(Holds(v)) => ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(v), name, "STATIC")
        case Some(Fails(e)) => ProviderEvaluations.error[java.lang.Long](d, e, s"$name: $e")
        case None           => ProviderEvaluations.error[java.lang.Long](d, OFErrorCode.FLAG_NOT_FOUND, s"$name: no $k")
      }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](java.lang.Integer.valueOf(3), name, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.error[java.lang.Double](d, OFErrorCode.TYPE_MISMATCH, s"$name: integer value")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** A provider hook recording `(flag type, client domain, hints)` for every evaluation it sees. */
  private def recordingHook(seen: ConcurrentLinkedQueue[(FlagValueType, String, Map[String, AnyRef])]): Hook[Object] =
    new Hook[Object] {
      override def before(
        ctx: OFHookContext[Object],
        hints: java.util.Map[String, Object]
      ): java.util.Optional[OFEvaluationContext] = {
        seen.add((ctx.getType, String.valueOf(ctx.getClientMetadata.getDomain), hints.asScala.toMap))
        java.util.Optional.empty[OFEvaluationContext]()
      }
    }

  /** Records the children it is handed and evaluates only the first one. */
  private class RecordingStrategy(seen: ConcurrentLinkedQueue[List[String]]) extends Strategy {
    override def evaluate[T](
      providers: java.util.List[FeatureProvider],
      key: String,
      defaultValue: T,
      ctx: OFEvaluationContext,
      providerFunction: java.util.function.Function[FeatureProvider, ProviderEvaluation[T]]
    ): ProviderEvaluation[T] = {
      seen.add(providers.asScala.toList.map(_.getMetadata.getName))
      providerFunction.apply(providers.get(0))
    }
  }

  private def ffOver(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(
        provider,
        FeatureFlagsConfig(),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(_.get[FeatureFlags])

  private def sdkWith(provider: FeatureProvider): ZIO[Scope, Throwable, OpenFeatureAPI] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val api = OpenFeatureAPI.createIsolated()
      api.setProviderAndWait(provider)
      api
    })(api => ZIO.attemptBlocking(api.shutdown()).orDie)

  private val big = 5000000000L

  def spec = suite("LongAwareMultiProviderSpec (#533)")(
    suite("through FeatureFlags.multiProvider")(
      test("firstMatch serves the first child's exact long") {
        val a = new LongChild("a", Map("n" -> Holds(big)))
        val b = new LongChild("b", Map("n" -> Holds(1L)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a, b)))
            res <- ff.longDetails("n", 0L)
          } yield assertTrue(
            res.value == 5000000000L,
            res.variant.contains("a"),
            res.errorCode.isEmpty
          )
        }
      },
      test("firstMatch falls through a FLAG_NOT_FOUND child") {
        val a = new LongChild("a", Map.empty)
        val b = new LongChild("b", Map("n" -> Holds(42L)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a, b)))
            res <- ff.longDetails("n", 0L)
          } yield assertTrue(res.value == 42L, res.variant.contains("b"))
        }
      },
      test("firstSuccessful falls through a TYPE_MISMATCH child") {
        val a = new LongChild("a", Map("n" -> Fails(OFErrorCode.TYPE_MISMATCH)))
        val b = new LongChild("b", Map("n" -> Holds(42L)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a, b), MultiProviderStrategy.firstSuccessful))
            res <- ff.longDetails("n", 0L)
          } yield assertTrue(res.value == 42L, res.variant.contains("b"))
        }
      },
      test("firstMatch stops at a TYPE_MISMATCH child, as for the other types") {
        val a = new LongChild("a", Map("n" -> Fails(OFErrorCode.TYPE_MISMATCH)))
        val b = new LongChild("b", Map("n" -> Holds(42L)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a, b)))
            res <- ff.longDetails("n", 0L).either
          } yield assertTrue(res.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]))
        }
      },
      test("Long.MinValue and Long.MaxValue round-trip exactly") {
        val a = new LongChild("a", Map("min" -> Holds(Long.MinValue), "max" -> Holds(Long.MaxValue)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a)))
            min <- ff.long("min", 0L)
            max <- ff.long("max", 0L)
          } yield assertTrue(min == -9223372036854775808L, max == 9223372036854775807L)
        }
      },
      test("a custom strategy is handed every child and a function calling the child's long getter") {
        val seen = new ConcurrentLinkedQueue[List[String]]()
        val a    = new LongChild("a", Map("n" -> Holds(7L)))
        val b    = new LongChild("b", Map("n" -> Holds(1L)))
        ZIO.scoped {
          for {
            ff  <- ffOver(FeatureFlags.multiProvider(List(a, b), new RecordingStrategy(seen)))
            res <- ff.longDetails("n", 0L)
          } yield assertTrue(
            res.value == 7L,
            res.variant.contains("a"),
            seen.asScala.toList == List(List("a", "b"))
          )
        }
      }
    ),
    suite("child provider hooks")(
      test("see LONG, the caller's domain and the invocation hints, as an integer read does") {
        val seen  = new ConcurrentLinkedQueue[(FlagValueType, String, Map[String, AnyRef])]()
        val child = new LongChild("a", Map("n" -> Holds(big)), List(recordingHook(seen)))
        val opts  = FlagEvaluationOptions.builder().hookHints(Map[String, Object]("k" -> "v").asJava).build()
        val ctx   = new ImmutableContext()
        ZIO.scoped {
          for {
            api <- sdkWith(FeatureFlags.multiProvider(List(child)))
            client = api.getClient("checkout")
            long <- ZIO.attemptBlocking(client.getLongDetails("n", java.lang.Long.valueOf(0L), ctx, opts))
            int  <- ZIO.attemptBlocking(client.getIntegerDetails("n", Integer.valueOf(0), ctx, opts))
          } yield assertTrue(
            long.getValue.longValue == 5000000000L,
            int.getValue.intValue == 3,
            seen.asScala.toList == List(
              (FlagValueType.LONG, "checkout", Map[String, AnyRef]("k" -> "v")),
              (FlagValueType.INTEGER, "checkout", Map[String, AnyRef]("k" -> "v"))
            )
          )
        }
      },
      test("each evaluation sees its own client, and nothing is left behind after it") {
        val seen     = new ConcurrentLinkedQueue[(FlagValueType, String, Map[String, AnyRef])]()
        val child    = new LongChild("a", Map("n" -> Holds(1L)), List(recordingHook(seen)))
        val provider = FeatureFlags.multiProvider(List(child))
        ZIO.scoped {
          for {
            api <- sdkWith(provider)
            _   <- ZIO.attemptBlocking(api.getClient("checkout").getLongValue("n", java.lang.Long.valueOf(0L)))
            _   <- ZIO.attemptBlocking(api.getClient("billing").getLongValue("n", java.lang.Long.valueOf(0L)))
            // A direct call, with no client, must not see the last client's metadata: the SDK's own fallback domain.
            _ <- ZIO.attemptBlocking(
              provider.getLongEvaluation("n", java.lang.Long.valueOf(0L), new ImmutableContext())
            )
          } yield assertTrue(
            seen.asScala.toList.map(_._2) == List("checkout", "billing", "multiprovider"),
            seen.asScala.toList
              .map(_._3) == List(Map.empty[String, AnyRef], Map.empty[String, AnyRef], Map.empty[String, AnyRef])
          )
        }
      },
      test("the SDK's own MultiProvider hook still runs for the other types") {
        val provider = FeatureFlags.multiProvider(List(new LongChild("a", Map.empty)))
        val hooks    = provider.getProviderHooks.size
        assertTrue(provider.isInstanceOf[LongAwareMultiProvider], hooks == 2)
      }
    ),
    suite("fromAcquireAsync with the default compose")(
      test("serves the fallback's long before the switch and the real provider's exact long after") {
        val real     = new LongChild("real", Map("n" -> Holds(big)))
        val fallback = new LongChild("fb", Map("n" -> Holds(1L)))
        ZIO.scoped {
          for {
            gate <- Promise.make[Nothing, Unit]
            env  <- FeatureFlags.fromAcquireAsync(gate.await.as(real: FeatureProvider), ZIO.succeed(fallback)).build
            ff = env.get[FeatureFlags]
            before <- ff.long("n", 0L)
            _      <- gate.succeed(())
            state  <- env
              .get[AcquireStatus]
              .changes
              .filter(_ != AcquireState.Constructing)
              .runHead
              .timeoutFail(new RuntimeException("no acquire outcome"))(10.seconds)
            after <- ff.longDetails("n", 0L)
          } yield assertTrue(
            before == 1L,
            state.contains(AcquireState.Live),
            after.value == 5000000000L,
            after.variant.contains("real")
          )
        }
      }
    )
  ) @@ withLiveClock
}
