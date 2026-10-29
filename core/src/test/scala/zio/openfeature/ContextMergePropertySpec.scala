package zio.openfeature

import java.util.concurrent.atomic.AtomicReference

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, Metadata, OpenFeatureAPI}
import dev.openfeature.sdk.{ProviderState, Value}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testsupport.PropertyBudget
import zio.test._
import zio.test.TestAspect.withLiveClock

/** The context levels of docs/context.md ("Context levels", "How levels merge"), lowest to highest: global,
  * transaction, client, context source, scoped (`withContext`), invocation. A higher level wins on every attribute both
  * set and on the targeting key if it sets one; attributes only one level sets are kept; structs under the same key
  * merge field by field. Every layer tags its values with its own rank, so the winner of each key is readable.
  */
object ContextMergePropertySpec extends ZIOSpecDefault {

  private val keys   = List("k1", "k2", "k3")
  private val fields = List("f1", "f2")

  private val layerCount = 6

  // Which of the fixed names one layer sets: a targeting key, plain attributes, and fields of the struct attribute `s`.
  final private case class Shape(targetingKey: Boolean, attrs: Set[String], structFields: Set[String])

  private val shapeGen: Gen[Any, Shape] =
    for {
      tk <- Gen.boolean
      as <- Gen.listOfN(keys.size)(Gen.boolean)
      fs <- Gen.listOfN(fields.size)(Gen.boolean)
    } yield Shape(
      tk,
      keys.zip(as).collect { case (k, true) => k }.toSet,
      fields.zip(fs).collect { case (f, true) => f }.toSet
    )

  private val shapesGen: Gen[Any, List[Shape]] = Gen.listOfN(layerCount)(shapeGen)

  private def tag(rank: Int, name: String): String = s"L$rank-$name"

  private def contextOf(rank: Int, shape: Shape): EvaluationContext = {
    val withKey = if (shape.targetingKey) EvaluationContext(tag(rank, "tk")) else EvaluationContext.empty
    val plain   = shape.attrs.foldLeft(withKey)((c, k) => c.withAttribute(k, AttributeValue.StringValue(tag(rank, k))))
    if (shape.structFields.isEmpty) plain
    else
      plain.withAttribute(
        "s",
        AttributeValue.StructValue(
          shape.structFields.map(f => f -> (AttributeValue.StringValue(tag(rank, f)): AttributeValue)).toMap
        )
      )
  }

  // What the provider must end up seeing, from the documented rule: the highest layer that sets a name supplies it.
  final private case class Seen(
    targetingKey: Option[String],
    attrs: Map[String, String],
    structFields: Map[String, String]
  )

  private def expected(shapes: List[Shape]): Seen = {
    val ranked                                                    = shapes.zipWithIndex
    def top(pick: Shape => Boolean, name: String): Option[String] =
      ranked.filter { case (s, _) => pick(s) }.lastOption.map { case (_, r) => tag(r, name) }
    Seen(
      top(_.targetingKey, "tk"),
      keys.flatMap(k => top(_.attrs.contains(k), k).map(k -> _)).toMap,
      fields.flatMap(f => top(_.structFields.contains(f), f).map(f -> _)).toMap
    )
  }

  private class RecordingProvider(seen: AtomicReference[Option[OFEvaluationContext]]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Recording" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      seen.set(Some(c))
      ProviderEvaluations.of[String](d, "DEFAULT")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def read(c: OFEvaluationContext): Seen = {
    def str(v: Value): Option[String] = Option(v).flatMap(x => Option(x.asString))
    val struct                        = Option(c.getValue("s")).flatMap(v => Option(v.asStructure))
    Seen(
      Option(c.getTargetingKey),
      keys.flatMap(k => str(c.getValue(k)).map(k -> _)).toMap,
      fields.flatMap(f => struct.flatMap(st => str(st.getValue(f))).map(f -> _)).toMap
    )
  }

  def spec: Spec[Any, Any] = suite("ContextMergePropertySpec")(
    test("merging the layers by hand lets the highest layer win per name") {
      check(shapesGen) { shapes =>
        val merged = shapes.zipWithIndex
          .map { case (s, r) => contextOf(r, s) }
          .foldLeft(EvaluationContext.empty)(_.merge(_))
        val got = Seen(
          merged.targetingKey,
          keys.flatMap(k => merged.getString(k).map(k -> _)).toMap,
          fields.flatMap { f =>
            merged.get("s").flatMap(_.asStruct).flatMap(_.get(f)).flatMap(_.asString).map(f -> _)
          }.toMap
        )
        assertTrue(got == expected(shapes))
      }
    } @@ PropertyBudget(200),
    test("the provider receives the highest layer's value per name across all six levels") {
      check(shapesGen) { shapes =>
        ZIO.scoped {
          val ctxs        = shapes.zipWithIndex.map { case (s, r) => contextOf(r, s) }
          val global      = ctxs(0)
          val transaction = ctxs(1)
          val client      = ctxs(2)
          val source      = ctxs(3)
          val scoped      = ctxs(4)
          val invocation  = ctxs(5)
          val received    = new AtomicReference[Option[OFEvaluationContext]](None)
          val config      = FeatureFlagsConfig().withContextSource(ContextSource(ZIO.succeed(source)))
          for {
            ff <- FeatureFlags
              .fromProvider(
                new RecordingProvider(received),
                config,
                statusRef = None,
                apiOverride = Some(OpenFeatureAPI.createIsolated())
              )
              .build
              .map(_.get[FeatureFlags])
            _ <- ff.setGlobalContext(global)
            _ <- ff.setClientContext(client)
            _ <- ff.withContext(scoped)(
              ff.transaction(context = transaction)(ff.stringOrDefault("flag", "dflt", invocation))
            )
          } yield assertTrue(received.get.map(read) == Some(expected(shapes)))
        }
      }
    } @@ PropertyBudget(60) @@ withLiveClock
  )
}
