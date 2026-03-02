package zio.openfeature

import zio.test._
import dev.openfeature.sdk.FlagEvaluationDetails
import zio.openfeature.internal.ClientEvaluator

object ClientEvaluatorSpec extends ZIOSpecDefault {

  private def makeDetails[A](value: A): FlagEvaluationDetails[A] = {
    val details = new FlagEvaluationDetails[A]()
    details.setValue(value)
    details.setFlagKey("test-flag")
    details
  }

  def spec = suite("ClientEvaluator")(
    suite("Boolean instance")(
      test("extractValue converts Java Boolean to Scala Boolean") {
        val details = makeDetails[java.lang.Boolean](java.lang.Boolean.TRUE)
        val result  = ClientEvaluator.booleanEvaluator.extractValue(details)
        assertTrue(result == true)
      },
      test("extractValue handles false") {
        val details = makeDetails[java.lang.Boolean](java.lang.Boolean.FALSE)
        val result  = ClientEvaluator.booleanEvaluator.extractValue(details)
        assertTrue(result == false)
      }
    ),
    suite("String instance")(
      test("extractValue returns string value") {
        val details = makeDetails[String]("hello")
        val result  = ClientEvaluator.stringEvaluator.extractValue(details)
        assertTrue(result == "hello")
      },
      test("extractValue handles empty string") {
        val details = makeDetails[String]("")
        val result  = ClientEvaluator.stringEvaluator.extractValue(details)
        assertTrue(result == "")
      }
    ),
    suite("Int instance")(
      test("extractValue converts Java Integer to Scala Int") {
        val details = makeDetails[java.lang.Integer](java.lang.Integer.valueOf(42))
        val result  = ClientEvaluator.intEvaluator.extractValue(details)
        assertTrue(result == 42)
      },
      test("extractValue handles zero") {
        val details = makeDetails[java.lang.Integer](java.lang.Integer.valueOf(0))
        val result  = ClientEvaluator.intEvaluator.extractValue(details)
        assertTrue(result == 0)
      },
      test("extractValue handles negative values") {
        val details = makeDetails[java.lang.Integer](java.lang.Integer.valueOf(-1))
        val result  = ClientEvaluator.intEvaluator.extractValue(details)
        assertTrue(result == -1)
      }
    ),
    suite("Long instance")(
      test("extractValue converts Java Double to Scala Long") {
        val details = makeDetails[java.lang.Double](java.lang.Double.valueOf(42.0))
        val result  = ClientEvaluator.longEvaluator.extractValue(details)
        assertTrue(result == 42L)
      },
      test("extractValue handles values larger than Int.MaxValue") {
        val largeValue = Int.MaxValue.toLong + 1000L
        val details    = makeDetails[java.lang.Double](java.lang.Double.valueOf(largeValue.toDouble))
        val result     = ClientEvaluator.longEvaluator.extractValue(details)
        assertTrue(result == largeValue)
      }
    ),
    suite("Float instance")(
      test("extractValue converts Java Double to Scala Float") {
        val details = makeDetails[java.lang.Double](java.lang.Double.valueOf(3.14))
        val result  = ClientEvaluator.floatEvaluator.extractValue(details)
        assertTrue(result == 3.14.toFloat)
      },
      test("extractValue handles zero") {
        val details = makeDetails[java.lang.Double](java.lang.Double.valueOf(0.0))
        val result  = ClientEvaluator.floatEvaluator.extractValue(details)
        assertTrue(result == 0.0f)
      }
    ),
    suite("Double instance")(
      test("extractValue converts Java Double to Scala Double") {
        val details = makeDetails[java.lang.Double](java.lang.Double.valueOf(3.14))
        val result  = ClientEvaluator.doubleEvaluator.extractValue(details)
        assertTrue(result == 3.14)
      },
      test("extractValue handles negative values") {
        val details = makeDetails[java.lang.Double](java.lang.Double.valueOf(-1.5))
        val result  = ClientEvaluator.doubleEvaluator.extractValue(details)
        assertTrue(result == -1.5)
      }
    ),
    suite("implicit resolution")(
      test("Boolean instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[Boolean]]
        assertTrue(ev != null)
      },
      test("String instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[String]]
        assertTrue(ev != null)
      },
      test("Int instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[Int]]
        assertTrue(ev != null)
      },
      test("Long instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[Long]]
        assertTrue(ev != null)
      },
      test("Float instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[Float]]
        assertTrue(ev != null)
      },
      test("Double instance is resolved implicitly") {
        val ev = implicitly[ClientEvaluator[Double]]
        assertTrue(ev != null)
      }
    )
  )
}
