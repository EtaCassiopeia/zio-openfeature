package zio.openfeature

import zio.test._
import zio.openfeature.internal.ClientEvaluator
import dev.openfeature.sdk.FlagEvaluationDetails

object ClientEvaluatorSpec extends ZIOSpecDefault {

  private def detailsWithValue[A](value: A): FlagEvaluationDetails[A] = {
    val details = new FlagEvaluationDetails[A]()
    details.setValue(value)
    details
  }

  def spec = suite("ClientEvaluatorSpec")(
    suite("forType lookup")(
      test("resolves Boolean evaluator") {
        val result = ClientEvaluator.forType(FlagType[Boolean])
        assertTrue(result.isDefined)
      },
      test("resolves String evaluator") {
        val result = ClientEvaluator.forType(FlagType[String])
        assertTrue(result.isDefined)
      },
      test("resolves Int evaluator") {
        val result = ClientEvaluator.forType(FlagType[Int])
        assertTrue(result.isDefined)
      },
      test("resolves Long evaluator") {
        val result = ClientEvaluator.forType(FlagType[Long])
        assertTrue(result.isDefined)
      },
      test("resolves Float evaluator") {
        val result = ClientEvaluator.forType(FlagType[Float])
        assertTrue(result.isDefined)
      },
      test("resolves Double evaluator") {
        val result = ClientEvaluator.forType(FlagType[Double])
        assertTrue(result.isDefined)
      },
      test("returns None for Object type") {
        val result = ClientEvaluator.forType(FlagType[Map[String, Any]])
        assertTrue(result.isEmpty)
      },
      test("returns None for custom types") {
        implicit val customFlagType: FlagType[List[Int]] = FlagType.from(
          name = "CustomList",
          default = List.empty,
          decoder = _ => Right(List.empty)
        )
        val result = ClientEvaluator.forType(customFlagType)
        assertTrue(result.isEmpty)
      }
    ),
    suite("extractValue")(
      test("Boolean extracts value from details") {
        val details = detailsWithValue[java.lang.Boolean](java.lang.Boolean.TRUE)
        val value   = ClientEvaluator.boolean.extractValue(details)
        assertTrue(value == true)
      },
      test("String extracts value from details") {
        val details = detailsWithValue[String]("hello")
        val value   = ClientEvaluator.string.extractValue(details)
        assertTrue(value == "hello")
      },
      test("Int extracts value from details") {
        val details = detailsWithValue[java.lang.Integer](java.lang.Integer.valueOf(42))
        val value   = ClientEvaluator.int.extractValue(details)
        assertTrue(value == 42)
      },
      test("Long extracts value from details") {
        val details = detailsWithValue[java.lang.Integer](java.lang.Integer.valueOf(100))
        val value   = ClientEvaluator.long.extractValue(details)
        assertTrue(value == 100L)
      },
      test("Float extracts value from details") {
        val details = detailsWithValue[java.lang.Double](java.lang.Double.valueOf(3.14))
        val value   = ClientEvaluator.float.extractValue(details)
        assertTrue(value == 3.14.toFloat)
      },
      test("Double extracts value from details") {
        val details = detailsWithValue[java.lang.Double](java.lang.Double.valueOf(2.718))
        val value   = ClientEvaluator.double.extractValue(details)
        assertTrue(value == 2.718)
      }
    )
  )
}
