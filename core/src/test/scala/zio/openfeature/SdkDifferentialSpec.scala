package zio.openfeature

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FlagEvaluationDetails,
  Hook => OFHook,
  HookContext => OFHookContext,
  ImmutableMetadata,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}
import dev.openfeature.sdk.exceptions.FlagNotFoundError
import zio._
import zio.openfeature.internal.{ContextConverter, ErrorCodeConverter, Evaluator}
import zio.test._

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

/** Differential testing: the same provider behind the raw OpenFeature Java SDK client and behind `FeatureFlags`. Any
  * difference is either documented or a bug.
  */
object SdkDifferentialSpec extends ZIOSpecDefault {

  /** One scripted answer per key. `Left` throws, `Right` returns the evaluation. */
  final class ScriptedProvider(name: String) extends EventProvider {
    val lastContext = new AtomicReference[OFEvaluationContext](null)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def eval[A](key: String, default: A, ctx: OFEvaluationContext): ProviderEvaluation[A] = {
      lastContext.set(ctx)
      val b = ProviderEvaluation.builder[A]()
      key match {
        case "ok" =>
          b.value(default); b.variant("on"); b.reason("TARGETING_MATCH")
          b.flagMetadata(ImmutableMetadata.builder().addInteger("m", 1).addString("s", "x").build())
        case "custom-reason" => b.value(default); b.reason("my_custom")
        case "null-reason"   => b.value(default)
        case "err-code"      =>
          b.value(default); b.errorCode(OFErrorCode.FLAG_NOT_FOUND); b.errorMessage("nf")
        case "err-code-static" =>
          b.value(default); b.errorCode(OFErrorCode.PARSE_ERROR); b.errorMessage("bad"); b.reason("STATIC")
        case "general" =>
          b.value(default); b.errorCode(OFErrorCode.GENERAL); b.errorMessage("boom"); b.reason("ERROR")
        case "err-with-meta" =>
          b.value(default); b.errorCode(OFErrorCode.GENERAL); b.errorMessage("boom")
          b.flagMetadata(ImmutableMetadata.builder().addString("why", "x").build())
        case "throws-nf"      => throw new FlagNotFoundError("gone")
        case "throws-runtime" => throw new RuntimeException("kaput")
        case _                => b.value(default); b.reason("STATIC")
      }
      b.build().asInstanceOf[ProviderEvaluation[A]]
    }

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = eval(k, d, c)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = eval(k, d, c)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = eval(k, d, c)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = eval(k, d, c)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = eval(k, d, c)
  }

  final case class Outcome(
    value: Any,
    variant: Option[String],
    reason: ResolutionReason,
    errorCode: Option[ErrorCode],
    errorMessage: Option[String],
    metadata: Map[String, Any]
  )

  private def fromSdk[A](d: FlagEvaluationDetails[A]): Outcome =
    Outcome(
      d.getValue,
      Option(d.getVariant),
      Evaluator.toResolutionReason(d.getReason),
      Option(d.getErrorCode).map(ErrorCodeConverter.fromJava),
      Option(d.getErrorMessage),
      Option(d.getFlagMetadata).map(_.asUnmodifiableMap().asScala.toMap).getOrElse(Map.empty)
    )

  private def metaValue(v: MetadataValue): Any = v match {
    case MetadataValue.BooleanValue(b) => b
    case MetadataValue.StringValue(s)  => s
    case MetadataValue.IntValue(i)     => i
    case MetadataValue.LongValue(l)    => l
    case MetadataValue.DoubleValue(d)  => d
    case MetadataValue.FloatValue(f)   => f
  }

  private def fromZio[A](r: FlagResolution[A]): Outcome =
    Outcome(
      r.value,
      r.variant,
      r.reason,
      r.errorCode,
      r.errorMessage,
      r.metadata.values.map { case (k, v) => k -> metaValue(v) }
    )

  private def sdkClient(p: ScriptedProvider): UIO[dev.openfeature.sdk.Client] = ZIO.succeed {
    val api = OpenFeatureAPI.createIsolated()
    api.setProviderAndWait(p)
    api.getClient()
  }

  private def zioFlags(p: ScriptedProvider): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      p,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      fallbackLogging = FallbackLogging.Off
    )

  private val keys =
    List(
      "ok",
      "custom-reason",
      "null-reason",
      "err-code",
      "err-code-static",
      "general",
      "err-with-meta",
      "throws-nf",
      "throws-runtime"
    )

  private def compareKey(key: String) =
    test(s"boolean details agree for `$key`") {
      ZIO.scoped {
        for {
          c  <- sdkClient(new ScriptedProvider("sdk"))
          ff <- zioFlags(new ScriptedProvider("zio"))
          s  <- ZIO.attemptBlocking(fromSdk(c.getBooleanDetails(key, java.lang.Boolean.TRUE)))
          z  <- ff.resolveOrDefault[Boolean](key, true).map(fromZio)
        } yield assertTrue(z == s)
      }
    }

  private def compareObjectKey(key: String) =
    test(s"string details agree for `$key`") {
      ZIO.scoped {
        for {
          c  <- sdkClient(new ScriptedProvider("sdk"))
          ff <- zioFlags(new ScriptedProvider("zio"))
          s  <- ZIO.attemptBlocking(fromSdk(c.getStringDetails(key, "dflt")))
          z  <- ff.resolveOrDefault[String](key, "dflt").map(fromZio)
        } yield assertTrue(z == s)
      }
    }

  private def ctxToMap(c: OFEvaluationContext): Map[String, Any] =
    c.asObjectMap().asScala.toMap + ("@tk" -> c.getTargetingKey)

  def spec = suite("SdkDifferentialSpec")(
    suite("details")(keys.map(compareKey): _*),
    suite("string details")(keys.map(compareObjectKey): _*),
    test("API < client < invocation context merge agrees") {
      val g = EvaluationContext("g").withAttributes(
        "a" -> AttributeValue.StringValue("g"),
        "b" -> AttributeValue.StringValue("g"),
        "c" -> AttributeValue.StringValue("g")
      )
      val cl = EvaluationContext("c").withAttributes(
        "b" -> AttributeValue.StringValue("c"),
        "c" -> AttributeValue.StringValue("c")
      )
      val inv = EvaluationContext.empty.withAttributes("c" -> AttributeValue.StringValue("i"))
      ZIO.scoped {
        for {
          ps <- ZIO.succeed(new ScriptedProvider("sdk"))
          pz <- ZIO.succeed(new ScriptedProvider("zio"))
          _  <- ZIO.attemptBlocking {
            val api = OpenFeatureAPI.createIsolated()
            api.setProviderAndWait(ps)
            api.setEvaluationContext(ContextConverter.toOpenFeature(g))
            val client = api.getClient()
            client.setEvaluationContext(ContextConverter.toOpenFeature(cl))
            client.getBooleanDetails("x", false, ContextConverter.toOpenFeature(inv))
          }
          ff <- zioFlags(pz)
          _  <- ff.setGlobalContext(g) *> ff.setClientContext(cl)
          _  <- ff.booleanDetails("x", false, inv)
        } yield assertTrue(ctxToMap(pz.lastContext.get) == ctxToMap(ps.lastContext.get))
      }
    },
    // Documented deviation (docs/context.md "How levels merge"): structs merge field by field; the SDK replaces.
    test("nested structures: FeatureFlags merges field by field, the SDK replaces the struct") {
      val g = EvaluationContext("k").withAttributes(
        "s" -> AttributeValue.StructValue(Map("x" -> AttributeValue.IntValue(1), "y" -> AttributeValue.IntValue(1)))
      )
      val inv = EvaluationContext.empty.withAttributes(
        "s" -> AttributeValue.StructValue(Map("y" -> AttributeValue.IntValue(2)))
      )
      ZIO.scoped {
        for {
          ps <- ZIO.succeed(new ScriptedProvider("sdk"))
          pz <- ZIO.succeed(new ScriptedProvider("zio"))
          _  <- ZIO.attemptBlocking {
            val api = OpenFeatureAPI.createIsolated()
            api.setProviderAndWait(ps)
            api.setEvaluationContext(ContextConverter.toOpenFeature(g))
            api.getClient().getBooleanDetails("x", false, ContextConverter.toOpenFeature(inv))
          }
          ff <- zioFlags(pz)
          _  <- ff.setGlobalContext(g)
          _  <- ff.booleanDetails("x", false, inv)
        } yield assertTrue(
          ctxToMap(pz.lastContext.get)("s").toString == "{x=1, y=2}",
          ctxToMap(ps.lastContext.get)("s").toString == "{y=2}"
        )
      }
    },
    test("an error-coded resolution runs the error hook in both (spec 4.4.x via SDK behaviour)") {
      ZIO.scoped {
        for {
          sdkErr <- Ref.make(0)
          sdkAft <- Ref.make(0)
          zErr   <- Ref.make(0)
          zAft   <- Ref.make(0)
          rt     <- ZIO.runtime[Any]
          _      <- ZIO.attemptBlocking {
            val api = OpenFeatureAPI.createIsolated()
            api.setProviderAndWait(new ScriptedProvider("sdk"))
            val client = api.getClient()
            client.addHooks(new OFHook[Any] {
              override def after(
                ctx: OFHookContext[Any],
                d: FlagEvaluationDetails[Any],
                h: java.util.Map[String, Object]
              ): Unit =
                Unsafe.unsafe(implicit u => rt.unsafe.run(sdkAft.update(_ + 1)).getOrThrow())
              override def error(ctx: OFHookContext[Any], e: Exception, h: java.util.Map[String, Object]): Unit =
                Unsafe.unsafe(implicit u => rt.unsafe.run(sdkErr.update(_ + 1)).getOrThrow())
            })
            client.getBooleanDetails("err-code", false)
          }
          ff <- zioFlags(new ScriptedProvider("zio"))
          _  <- ff.addHook(new FeatureHook {
            override def after[A](c: HookContext, d: FlagResolution[A], h: HookHints): UIO[Unit] = zAft.update(_ + 1)
            override def error(c: HookContext, e: FeatureFlagError, h: HookHints): UIO[Unit]     = zErr.update(_ + 1)
          })
          _  <- ff.resolveOrDefault[Boolean]("err-code", false)
          se <- sdkErr.get
          sa <- sdkAft.get
          ze <- zErr.get
          za <- zAft.get
        } yield assertTrue((ze, za) == ((se, sa)))
      }
    },
    test("a READY handler registered after ready runs at once in both (spec 5.3.3)") {
      ZIO.scoped {
        for {
          sdkRan <- Promise.make[Nothing, Unit]
          zRan   <- Promise.make[Nothing, Unit]
          rt     <- ZIO.runtime[Any]
          _      <- ZIO.attemptBlocking {
            val api = OpenFeatureAPI.createIsolated()
            api.setProviderAndWait(new ScriptedProvider("sdk"))
            api.getClient().onProviderReady { _ =>
              Unsafe.unsafe(implicit u => rt.unsafe.run(sdkRan.succeed(())).getOrThrow()): Unit
            }
          }
          ff <- zioFlags(new ScriptedProvider("zio"))
          _  <- ff.onProviderReady(_ => zRan.succeed(()).unit)
          s  <- sdkRan.await.timeout(5.seconds)
          z  <- zRan.await.timeout(5.seconds)
        } yield assertTrue(s.isDefined, z.isDefined)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
}
