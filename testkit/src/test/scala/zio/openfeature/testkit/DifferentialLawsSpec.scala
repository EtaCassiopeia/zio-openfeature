package zio.openfeature.testkit

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  Value
}
import zio._
import zio.openfeature.ErrorCode
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testkit.DifferentialLaws._
import zio.test._

/** The harness itself (#467): a deliberately wrong oracle is caught, a deviation explains it, and a deviation that
  * explains nothing fails as stale. Shared test source dir, so braces only.
  */
object DifferentialLawsSpec extends ZIOSpecDefault {

  /** Answers like `TestFeatureProvider`, except that every integer read is `TYPE_MISMATCH`. */
  final private class IntegerBlindProvider(underlying: TestFeatureProvider) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = "IntegerBlindProvider"
    }
    override def getBooleanEvaluation(
      key: String,
      default: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = underlying.getBooleanEvaluation(key, default, ctx)
    override def getStringEvaluation(
      key: String,
      default: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      underlying.getStringEvaluation(key, default, ctx)
    override def getIntegerEvaluation(
      key: String,
      default: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] =
      ProviderEvaluations.error(default, OFErrorCode.TYPE_MISMATCH, s"'$key': integers are not supported")
    override def getLongEvaluation(
      key: String,
      default: java.lang.Long,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Long] = underlying.getLongEvaluation(key, default, ctx)
    override def getDoubleEvaluation(
      key: String,
      default: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = underlying.getDoubleEvaluation(key, default, ctx)
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext): ProviderEvaluation[Value] =
      underlying.getObjectEvaluation(key, default, ctx)
  }

  private val integerBlind: FlagSet => URIO[Scope, FeatureProvider] =
    fs => TestFeatureProvider.make(fs.flags).map(new IntegerBlindProvider(_))

  private val sameAsTestkit: FlagSet => URIO[Scope, FeatureProvider] =
    fs => TestFeatureProvider.make(fs.flags)

  private val integerReadsMismatch = Deviation(
    "integer-blind",
    (_, request, _, oracle) =>
      request.accessor.isInstanceOf[Accessor.IntRead] && oracle.errorCode.contains(ErrorCode.TypeMismatch),
    "this oracle cannot read integers"
  )

  private val samples = 10

  def spec = suite("DifferentialLawsSpec")(
    test("an oracle that is TYPE_MISMATCH for every integer read fails without a deviation") {
      for {
        report <- compare("integer-blind")(integerBlind, Nil, samples)
      } yield {
        val passed = report.passed
        // Every sample reads the absent key as an int: FLAG_NOT_FOUND from the testkit, TYPE_MISMATCH from the oracle.
        val enough      = report.unexplained.size >= samples
        val allIntReads = report.unexplained.forall(_.request.accessor.isInstanceOf[Accessor.IntRead])
        assertTrue(!passed, enough, allIntReads)
      }
    },
    test("and passes once the difference is listed as a deviation") {
      for {
        report <- compare("integer-blind")(integerBlind, List(integerReadsMismatch), samples)
      } yield {
        val passed  = report.passed
        val matched = report.explained.map(_._2).sum
        assertTrue(passed, matched >= samples)
      }
    },
    test("a deviation that matches nothing fails as stale") {
      for {
        report <- compare("integer-blind")(sameAsTestkit, List(integerReadsMismatch), samples)
      } yield {
        val stale    = report.stale
        val passed   = report.passed
        val problems = report.problems
        assertTrue(!passed, stale == List(integerReadsMismatch), problems.exists(_.startsWith("stale")))
      }
    },
    test("a deviation filed under another oracle fails as misfiled") {
      for {
        report <- compare("other")(sameAsTestkit, List(integerReadsMismatch), samples)
      } yield {
        val misfiled = report.misfiled
        val passed   = report.passed
        assertTrue(!passed, misfiled == List(integerReadsMismatch))
      }
    },
    test("every generated sample reads every key and the absent key through all six accessors") {
      for {
        sampled <- DifferentialLaws.flagSets.flatMap(fs => requestsFor(fs).map(fs -> _)).runCollectN(20)
      } yield {
        val complete = sampled.forall { case (fs, requests) =>
          requests.size == (fs.flags.size + 1) * 6 && requests.exists(_.key == AbsentKey)
        }
        assertTrue(complete)
      }
    },
    // The testkit compared against itself: the full-size run with no deviations must agree everywhere.
    DifferentialLaws.agreesWithTestkit("testkit")(sameAsTestkit, Nil)
  )
}
