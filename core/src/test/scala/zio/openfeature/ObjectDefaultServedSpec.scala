package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  MutableStructure,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}

/** An object flag served from the caller's default returns that default as passed. The default crosses the SDK's
  * `Value` bridge, which drops a `null` member, so decoding it back would turn a `None` field with a declared default
  * into that default.
  */
object ObjectDefaultServedSpec extends ZIOSpecDefault {

  final case class Timeouts(name: String, retryMs: Option[Int])

  // As a derived decoder does: an absent `retryMs` takes the declared default Some(30); a present null is None.
  implicit val timeouts: FlagType[Timeouts] = FlagType.from[Timeouts](
    "Timeouts",
    Timeouts("", Some(30)),
    {
      case m: Map[_, _] =>
        val fields = m.asInstanceOf[Map[String, Any]]
        fields.get("name") match {
          case Some(name: String) =>
            val retry = fields.get("retryMs") match {
              case None            => Right(Some(30))
              case Some(null)      => Right(None)
              case Some(n: Number) => Right(Some(n.intValue()))
              case Some(other)     => Left(s"retryMs: $other")
            }
            retry.map(Timeouts(name, _))
          case other => Left(s"name: $other")
        }
      case other => Left(s"not a map: $other")
    },
    t => Map("name" -> t.name, "retryMs" -> t.retryMs.fold[Any](null)(identity))
  )

  /** Answers every object evaluation with `answer(default)`. */
  final private class ObjectProvider(answer: Value => ProviderEvaluation[Value]) extends FeatureProvider {
    override def getMetadata: Metadata = new Metadata { override def getName: String = "objects" }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext): ProviderEvaluation[Value] = answer(d)
  }

  private def resolve(answer: Value => ProviderEvaluation[Value]) =
    ZIO.scoped {
      FeatureFlags
        .build(
          new ObjectProvider(answer),
          domain = None,
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated())
        )
        .flatMap(_.resolveOrDefault("t", Timeouts("a", None)))
    }

  def spec = suite("ObjectDefaultServedSpec")(
    test("control: a provider without the flag serves the caller's default as passed, with its error code") {
      resolve(d => ProviderEvaluations.error(d, ErrorCode.FLAG_NOT_FOUND, "no such flag")).map { r =>
        assertTrue(r.value == Timeouts("a", None), r.errorCode.contains(zio.openfeature.ErrorCode.FlagNotFound))
      }
    },
    test("a provider that hands back the default it was given serves it as passed") {
      resolve(d => ProviderEvaluations.of(d, "DEFAULT")).map(r => assertTrue(r.value == Timeouts("a", None)))
    },
    test("control: a structure the provider serves without the Option member takes the declared default") {
      resolve { _ =>
        val s = new MutableStructure()
        s.add("name", "b")
        ProviderEvaluations.of(new Value(s), "STATIC")
      }.map(r => assertTrue(r.value == Timeouts("b", Some(30))))
    }
  )
}
