package zio.openfeature

import dev.openfeature.sdk.{ErrorCode => OFErrorCode}
import zio.openfeature.internal.ErrorCodeConverter
import zio.openfeature.testsupport.PropertyBudget
import zio.test._

object ErrorCodeConverterRoundTripSpec extends ZIOSpecDefault {

  private val allJava: List[OFErrorCode] = OFErrorCode.values().toList

  def spec: Spec[Any, Any] = suite("ErrorCodeConverterRoundTripSpec")(
    test("every Java SDK error code survives toJava(fromJava(c))") {
      val broken = allJava.filter(c => ErrorCodeConverter.toJava(ErrorCodeConverter.fromJava(c)) != c)
      assertTrue(allJava.nonEmpty, broken.isEmpty)
    },
    test("fromJava is total: never throws and never returns null, for any Java code") {
      check(Gen.elements(allJava: _*)) { c =>
        val converted = scala.util.Try(ErrorCodeConverter.fromJava(c))
        assertTrue(converted.isSuccess, converted.toOption.exists(_ != null))
      }
    } @@ PropertyBudget(50)
  )
}
