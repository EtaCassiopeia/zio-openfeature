package zio.openfeature

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  Value
}
import dev.openfeature.sdk.multiprovider.Strategy
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testsupport.PropertyBudget
import zio.test._
import zio.test.TestAspect.withLiveClock

/** `FeatureFlags.multiProvider` against the strategy table in docs/providers.md ("Ask several providers in order"):
  * `firstMatch` moves on only for FLAG_NOT_FOUND and stops at the first other answer, an error included;
  * `firstSuccessful` moves on for any error and stops at the first answer without one; when nothing has the flag,
  * `firstMatch` gives FLAG_NOT_FOUND and `firstSuccessful` a GENERAL error. A member that throws is not documented, so
  * a chain that reaches one is not asserted.
  */
object MultiProviderStrategyPropertySpec extends ZIOSpecDefault {

  sealed private trait Answer
  private case object Holds    extends Answer
  private case object NotFound extends Answer
  private case object Errors   extends Answer
  private case object Throws   extends Answer

  // What the chain must serve; None where the docs do not say.
  sealed private trait Served
  final private case class Value_(provider: Int) extends Served
  private case object Missing                    extends Served
  private case object GeneralError               extends Served

  private val answerGen: Gen[Any, Answer]      = Gen.elements[Answer](Holds, NotFound, Errors, Throws)
  private val chainGen: Gen[Any, List[Answer]] = Gen.listOfBounded(1, 3)(answerGen)

  private class Member(index: Int, answer: Answer) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = s"member-$index" }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      answer match {
        case Holds    => ProviderEvaluations.of[String](s"value-$index", s"v$index", "STATIC")
        case NotFound => ProviderEvaluations.error[String](d, OFErrorCode.FLAG_NOT_FOUND, "not found")
        case Errors   => ProviderEvaluations.error[String](d, OFErrorCode.GENERAL, "general failure")
        case Throws   => throw new RuntimeException(s"member $index throws")
      }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  // The reference model, from the documented table.
  private def firstMatchModel(chain: List[(Answer, Int)]): Option[Served] =
    chain match {
      case Nil                   => Some(Missing)
      case (Holds, i) :: _       => Some(Value_(i))
      case (Errors, _) :: _      => Some(GeneralError)
      case (NotFound, _) :: rest => firstMatchModel(rest)
      case (Throws, _) :: _      => None
    }

  private def firstSuccessfulModel(chain: List[(Answer, Int)]): Option[Served] =
    chain match {
      case Nil                            => Some(GeneralError)
      case (Holds, i) :: _                => Some(Value_(i))
      case (NotFound | Errors, _) :: rest => firstSuccessfulModel(rest)
      case (Throws, _) :: _               => None
    }

  private def run(answers: List[Answer], strategy: Strategy): UIO[FlagResolution[String]] =
    ZIO.scoped {
      val members = answers.zipWithIndex.map { case (a, i) => new Member(i, a): FeatureProvider }
      FeatureFlags
        .fromProvider(
          FeatureFlags.multiProvider(members, strategy),
          FeatureFlagsConfig(),
          statusRef = None,
          apiOverride = Some(OpenFeatureAPI.createIsolated())
        )
        .build
        .flatMap(_.get[FeatureFlags].resolveOrDefault[String]("flag", "dflt"))
    }.orDie

  private def matches(model: Option[Served], got: FlagResolution[String]): Boolean =
    model match {
      case None               => true
      case Some(Value_(i))    => got.value == s"value-$i" && got.errorCode.isEmpty
      case Some(Missing)      => got.value == "dflt" && got.errorCode.contains(ErrorCode.FlagNotFound)
      case Some(GeneralError) => got.value == "dflt" && got.errorCode.contains(ErrorCode.General)
    }

  def spec: Spec[Any, Any] = suite("MultiProviderStrategyPropertySpec")(
    test("firstMatch serves what the documented table says") {
      check(chainGen) { answers =>
        run(answers, MultiProviderStrategy.firstMatch).map { got =>
          assertTrue(matches(firstMatchModel(answers.zipWithIndex), got))
        }
      }
    } @@ PropertyBudget(60) @@ withLiveClock,
    test("firstSuccessful serves what the documented table says") {
      check(chainGen) { answers =>
        run(answers, MultiProviderStrategy.firstSuccessful).map { got =>
          assertTrue(matches(firstSuccessfulModel(answers.zipWithIndex), got))
        }
      }
    } @@ PropertyBudget(60) @@ withLiveClock
  )
}
