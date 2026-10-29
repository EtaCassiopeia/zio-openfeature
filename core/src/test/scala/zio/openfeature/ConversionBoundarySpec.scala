package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableContext,
  Metadata,
  MutableStructure,
  ProviderState,
  Value
}
import zio._
import zio.openfeature.internal.{ContextConverter, ProviderEvaluations, ValueBridge}
import zio.test._

import scala.jdk.CollectionConverters._

// The value conversions at the boundary with the SDK, pinned at the edges of each numeric range. Each case states its
// expected value literally, so an off-by-one bound or a dropped branch fails here.
object ConversionBoundarySpec extends ZIOSpecDefault {

  private def attributeOf(v: Value): Option[AttributeValue] =
    ContextConverter.fromOpenFeature(new ImmutableContext(Map("a" -> v).asJava)).attributes.get("a")

  private def number(d: Double): Option[AttributeValue] = attributeOf(new Value(d))

  /** Answers double reads from a fixed table. */
  final private class DoubleProvider(values: Map[String, Double]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Doubles" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](java.lang.Double.valueOf(values.getOrElse(k, d.doubleValue)), "STATIC")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "STATIC")
  }

  // Accepts only a Float, so a value that reaches `decode` as a Double is visible as a Left.
  private val floatOnly: FlagType[Float] = new FlagType[Float] {
    def typeName: String                          = "FloatOnly"
    override def wire: WireType                   = WireType.Float
    def defaultValue: Float                       = 0f
    def decode(value: Any): Either[String, Float] = value match {
      case f: Float => Right(f)
      case other    => Left(s"not a Float: $other")
    }
  }

  private val doubles = Map(
    "max"     -> Float.MaxValue.toDouble,
    "huge"    -> 1e300,
    "negHuge" -> -1e300,
    "nan"     -> Double.NaN,
    "inf"     -> Double.PositiveInfinity
  )

  def spec = suite("ConversionBoundarySpec")(
    suite("a whole double read back from the SDK")(
      test("inside Int range is an IntValue, at both edges") {
        assertTrue(
          number(3.0).contains(AttributeValue.IntValue(3)),
          number(0.0).contains(AttributeValue.IntValue(0)),
          number(Int.MaxValue.toDouble).contains(AttributeValue.IntValue(Int.MaxValue)),
          number(Int.MinValue.toDouble).contains(AttributeValue.IntValue(Int.MinValue))
        )
      },
      test("just outside Int range is a LongValue") {
        assertTrue(
          number(Int.MaxValue.toDouble + 1).contains(AttributeValue.LongValue(Int.MaxValue.toLong + 1)),
          number(Int.MinValue.toDouble - 1).contains(AttributeValue.LongValue(Int.MinValue.toLong - 1))
        )
      },
      test("Long.MinValue is a LongValue; Long.MaxValue, which rounds up to 2^63, stays a DoubleValue") {
        assertTrue(
          number(Long.MinValue.toDouble).contains(AttributeValue.LongValue(Long.MinValue)),
          number(Long.MaxValue.toDouble).contains(AttributeValue.DoubleValue(Long.MaxValue.toDouble))
        )
      },
      test("a fraction, and negative zero, stay DoubleValues") {
        val negZero = number(1.0 / Double.NegativeInfinity)
        assertTrue(
          number(2.5).contains(AttributeValue.DoubleValue(2.5)),
          negZero.exists {
            case AttributeValue.DoubleValue(d) => d == 0.0 && (1.0 / d).isNegInfinity
            case _                             => false
          }
        )
      },
      test("booleans and strings keep their kind") {
        assertTrue(
          attributeOf(new Value(true)).contains(AttributeValue.BoolValue(true)),
          attributeOf(new Value("x")).contains(AttributeValue.StringValue("x"))
        )
      },
      test("an empty value is never read as an instant") {
        assertTrue(!attributeOf(new Value()).exists(_.isInstanceOf[AttributeValue.InstantValue]))
      }
    ),
    suite("ValueBridge")(
      test("valueToMap is empty for null and for a value that is not a structure") {
        assertTrue(
          ValueBridge.valueToMap(null) == Map.empty[String, Any],
          ValueBridge.valueToMap(new Value("x")).isEmpty
        )
      },
      test("valueToAny keeps a list as a list and a structure as a map, and is None for null") {
        val s = new MutableStructure()
        s.add("k", new Value("v"))
        assertTrue(
          ValueBridge.valueToAny(null).isEmpty,
          ValueBridge.valueToAny(new Value(List(new Value("a"), new Value(1)).asJava)).contains(List("a", 1.0)),
          ValueBridge.valueToAny(new Value(s)).contains(Map("k" -> "v")),
          ValueBridge.valueToAny(new Value(java.time.Instant.EPOCH)).contains(java.time.Instant.EPOCH),
          ValueBridge.valueToAny(new Value()).isEmpty
        )
      }
    ),
    suite("built-in decoders at the range edges")(
      test("Long: Long.MinValue as a double decodes; Long.MaxValue as a double, which saturates, does not") {
        assertTrue(
          FlagType[Long].decode(Long.MinValue.toDouble) == Right(Long.MinValue),
          FlagType[Long].decode(Long.MaxValue.toDouble).isLeft,
          FlagType[Long].decode(1.5).isLeft
        )
      },
      test("Int and Long accept any whole Number and reject a fraction") {
        assertTrue(
          FlagType[Int].decode(java.math.BigDecimal.valueOf(5)) == Right(5),
          FlagType[Int].decode(java.math.BigDecimal.valueOf(5.5)).isLeft,
          FlagType[Long].decode(java.math.BigDecimal.valueOf(7)) == Right(7L),
          FlagType[Long].decode(java.math.BigDecimal.valueOf(7.5)).isLeft
        )
      },
      test("Float: NaN and infinity pass, Float.MaxValue passes, a finite double beyond it does not") {
        assertTrue(
          FlagType[Float].decode(Double.NaN).exists(_.isNaN),
          FlagType[Float].decode(Double.PositiveInfinity) == Right(Float.PositiveInfinity),
          FlagType[Float].decode(Float.MaxValue.toDouble) == Right(Float.MaxValue),
          FlagType[Float].decode(-Float.MaxValue.toDouble) == Right(-Float.MaxValue),
          FlagType[Float].decode(1e300).isLeft,
          FlagType[Float].decode(-1e300).isLeft
        )
      }
    ),
    test("a Float flag gets a Float for every in-range value and a mismatch for a finite overflow") {
      implicit val ft: FlagType[Float] = floatOnly
      for {
        ff      <- ZIO.service[FeatureFlags]
        max     <- ff.value[Float]("max", 0f).either
        nan     <- ff.value[Float]("nan", 0f).either
        inf     <- ff.value[Float]("inf", 0f).either
        huge    <- ff.value[Float]("huge", 0f).either
        negHuge <- ff.value[Float]("negHuge", 0f).either
      } yield assertTrue(
        max == Right(Float.MaxValue),
        nan.exists(_.isNaN),
        inf == Right(Float.PositiveInfinity),
        huge.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]),
        negHuge.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch])
      )
    }.provide(FeatureFlags.fromProvider(new DoubleProvider(doubles)))
  ) @@ TestAspect.sequential
}
