package zio.openfeature.extras

import java.time.Instant

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  MutableStructure,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio.openfeature.internal.ProviderEvaluations
import zio._
import zio.test._
import scala.jdk.CollectionConverters._

// A cached answer is only right for the context it was computed for, so two contexts the delegate can tell apart must
// never share a cache entry.
object CachingFingerprintSpec extends ZIOSpecDefault {

  // A delegate that answers with a type-aware rendering of the whole context, as a strict rule engine sees it.
  private object EchoProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Echo" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    def render(v: Value): String =
      if (v == null || v.isNull) "null"
      else if (v.isList) v.asList.asScala.map(render).mkString("[", ",", "]")
      else if (v.isStructure)
        v.asStructure.asMap.asScala.toList
          .sortBy(_._1)
          .map { case (k, e) => s"$k=${render(e)}" }
          .mkString("{", ",", "}")
      else s"${v.asObject.getClass.getSimpleName}(${v.asObject})"

    def render(ctx: OFEvaluationContext): String =
      s"${ctx.getTargetingKey}|" + ctx.asMap.asScala.toList
        .sortBy(_._1)
        .map { case (k, v) => s"$k=${render(v)}" }
        .mkString(",")

    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext): ProviderEvaluation[String] =
      ProviderEvaluations.of[String](render(c), "TARGETING_MATCH")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "STATIC")
  }

  private def ctx(attrs: (String, Value)*): OFEvaluationContext = new ImmutableContext("user-1", attrs.toMap.asJava)

  private def list(vs: Value*): Value = new Value(vs.toList.asJava)

  private def struct(fields: (String, Value)*): Value = {
    val s = new MutableStructure()
    fields.foreach { case (k, v) => s.add(k, v) }
    new Value(s)
  }

  // Through the cache, `first` then `second`: the second answer must be the delegate's own for `second`.
  private def servedSecond(first: OFEvaluationContext, second: OFEvaluationContext): ZIO[Scope, Nothing, Boolean] =
    CachingProvider.scoped(EchoProvider).orDieWith(e => new IllegalStateException(e.toString)).map { caching =>
      caching.getStringEvaluation("f", "", first)
      caching.getStringEvaluation("f", "", second).getValue == EchoProvider.render(second)
    }

  private val distinctPairs: List[(String, Value, Value)] = List(
    ("a string and a number with the same text", new Value("30"), new Value(30)),
    ("a string and a boolean with the same text", new Value("true"), new Value(true)),
    ("an int and a double of the same magnitude", new Value(30), new Value(30.0)),
    ("an int and a long of the same magnitude", new Value(30), new Value(30L)),
    (
      "a string and an instant with the same text",
      new Value("2026-01-01T00:00:00Z"),
      new Value(Instant.parse("2026-01-01T00:00:00Z"))
    ),
    ("a list of strings and a list of numbers", list(new Value("1"), new Value("2")), list(new Value(1), new Value(2))),
    (
      "a one-element list holding a comma and a two-element list",
      list(new Value("a,b")),
      list(new Value("a"), new Value("b"))
    ),
    ("a structure and a string that renders like it", struct("a" -> new Value(1)), new Value("{a=1}")),
    ("lists whose elements only concatenate alike", list(new Value("a"), new Value("sb")), list(new Value("assb"))),
    ("structures whose fields only concatenate alike", struct("a" -> new Value("sb")), struct("as" -> new Value("b"))),
    ("a null and the string \"null\"", new Value(), new Value("null"))
  )

  def spec = suite("CachingFingerprintSpec")(
    suite("contexts that differ only in an attribute's type or shape do not share a cache entry")(
      distinctPairs.map { case (label, a, b) =>
        test(label) {
          for {
            ab <- servedSecond(ctx("x" -> a), ctx("x" -> b))
            ba <- servedSecond(ctx("x" -> b), ctx("x" -> a))
          } yield assertTrue(ab, ba)
        }
      }: _*
    ),
    test("the same context still hits the cache") {
      ZIO
        .scoped {
          CachingProvider.scoped(EchoProvider).orDieWith(e => new IllegalStateException(e.toString)).map { caching =>
            caching.getStringEvaluation("f", "", ctx("x" -> list(new Value(1))))
            caching.getStringEvaluation("f", "", ctx("x" -> list(new Value(1)))).getReason
          }
        }
        .map(reason => assertTrue(reason == "CACHED"))
    },
    test("any two generated contexts get the delegate's own answer through the cache") {
      check(contextGen, contextGen) { (a, b) =>
        ZIO.scoped(servedSecond(a, b)).map(assertTrue(_))
      }
    }
  )

  // A small pool of values whose renderings coincide across types, so generated pairs collide often.
  private def valueGen(depth: Int): Gen[Any, Value] = {
    val leaf: Gen[Any, Value] = Gen
      .elements(
        () => new Value("30"),
        () => new Value(30),
        () => new Value(30L),
        () => new Value(30.0),
        () => new Value("true"),
        () => new Value(true),
        () => new Value("null"),
        () => new Value(),
        () => new Value("1,2"),
        () => new Value(Instant.EPOCH),
        () => new Value(Instant.EPOCH.toString)
      )
      .map(_())
    if (depth <= 0) leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfBounded(0, 2)(valueGen(depth - 1)).map(vs => list(vs: _*)),
        Gen.listOfBounded(0, 2)(Gen.elements("a", "b").zip(valueGen(depth - 1))).map(fs => struct(fs: _*))
      )
  }

  private val contextGen: Gen[Any, OFEvaluationContext] =
    Gen.listOfBounded(0, 2)(Gen.elements("x", "y").zip(valueGen(2))).map(attrs => ctx(attrs: _*))
}
