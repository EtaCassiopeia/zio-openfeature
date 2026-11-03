package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}
import dev.openfeature.sdk.multiprovider.{FirstMatchStrategy, FirstSuccessfulStrategy}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.test._

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

object MultiProviderStrategySpec extends ZIOSpecDefault {

  private class Member(name: String, answer: String) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = name }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](answer, s"v-$name", "STATIC")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Records what it was asked and answers from the second member, so the answer cannot be the default chain's. */
  private class SecondMember(asked: ConcurrentLinkedQueue[(List[String], String)])
      extends MultiProviderStrategy.CustomStrategy {
    override def evaluate[T](
      providers: List[FeatureProvider],
      key: String,
      defaultValue: T,
      ctx: OFEvaluationContext,
      call: FeatureProvider => ProviderEvaluation[T]
    ): ProviderEvaluation[T] = {
      asked.add((providers.map(_.getMetadata.getName), key))
      call(providers(1))
    }
  }

  def spec = suite("MultiProviderStrategy")(
    test("firstMatch is the FirstMatch strategy, mapped to the SDK's FirstMatchStrategy") {
      assertTrue(
        MultiProviderStrategy.firstMatch == MultiProviderStrategy.FirstMatch,
        MultiProviderStrategy.toSdk(MultiProviderStrategy.firstMatch).isInstanceOf[FirstMatchStrategy]
      )
    },
    test("firstSuccessful is the FirstSuccessful strategy, mapped to the SDK's FirstSuccessfulStrategy") {
      assertTrue(
        MultiProviderStrategy.firstSuccessful == MultiProviderStrategy.FirstSuccessful,
        MultiProviderStrategy.toSdk(MultiProviderStrategy.firstSuccessful).isInstanceOf[FirstSuccessfulStrategy]
      )
    },
    test("a custom strategy is consulted by a multiProvider and its answer is the one served") {
      val asked = new ConcurrentLinkedQueue[(List[String], String)]()
      val chain = FeatureFlags.multiProvider(
        List(new Member("first", "from-first"), new Member("second", "from-second")),
        MultiProviderStrategy.Custom(new SecondMember(asked))
      )
      ZIO
        .scoped {
          FeatureFlags
            .fromProvider(
              chain,
              FeatureFlagsConfig.default,
              statusRef = None,
              apiOverride = Some(OpenFeatureAPI.createIsolated())
            )
            .build
            .flatMap(_.get[FeatureFlags].string("greeting", "dflt"))
        }
        .map { served =>
          assertTrue(
            served == "from-second",
            asked.asScala.toList == List((List("first", "second"), "greeting"))
          )
        }
    },
    test("each conversion to the SDK's strategy returns a fresh instance") {
      assertTrue(
        MultiProviderStrategy.toSdk(MultiProviderStrategy.FirstMatch) ne
          MultiProviderStrategy.toSdk(MultiProviderStrategy.FirstMatch),
        MultiProviderStrategy.toSdk(MultiProviderStrategy.FirstSuccessful) ne
          MultiProviderStrategy.toSdk(MultiProviderStrategy.FirstSuccessful)
      )
    }
  )
}
