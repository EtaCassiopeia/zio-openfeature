package zio.openfeature

import zio.test._
import dev.openfeature.sdk.{ErrorCode => OFErrorCode}
import zio.openfeature.internal.ErrorCodeConverter

object ErrorCodeConverterSpec extends ZIOSpecDefault {
  def spec = suite("ErrorCodeConverter")(
    suite("fromJava")(
      test("converts all Java SDK error codes") {
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.PROVIDER_NOT_READY) == ErrorCode.ProviderNotReady) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.PROVIDER_FATAL) == ErrorCode.ProviderFatal) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.FLAG_NOT_FOUND) == ErrorCode.FlagNotFound) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.PARSE_ERROR) == ErrorCode.ParseError) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.TYPE_MISMATCH) == ErrorCode.TypeMismatch) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.TARGETING_KEY_MISSING) == ErrorCode.TargetingKeyMissing) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.INVALID_CONTEXT) == ErrorCode.InvalidContext) &&
        assertTrue(ErrorCodeConverter.fromJava(OFErrorCode.GENERAL) == ErrorCode.General)
      }
    ),
    suite("toJava")(
      test("converts all ZIO error codes") {
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.ProviderNotReady) == OFErrorCode.PROVIDER_NOT_READY) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.ProviderFatal) == OFErrorCode.PROVIDER_FATAL) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.FlagNotFound) == OFErrorCode.FLAG_NOT_FOUND) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.ParseError) == OFErrorCode.PARSE_ERROR) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.TypeMismatch) == OFErrorCode.TYPE_MISMATCH) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.TargetingKeyMissing) == OFErrorCode.TARGETING_KEY_MISSING) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.InvalidContext) == OFErrorCode.INVALID_CONTEXT) &&
        assertTrue(ErrorCodeConverter.toJava(ErrorCode.General) == OFErrorCode.GENERAL)
      }
    ),
    suite("round-trip")(
      test("fromJava and toJava are inverses") {
        val javaCodes = List(
          OFErrorCode.PROVIDER_NOT_READY,
          OFErrorCode.PROVIDER_FATAL,
          OFErrorCode.FLAG_NOT_FOUND,
          OFErrorCode.PARSE_ERROR,
          OFErrorCode.TYPE_MISMATCH,
          OFErrorCode.TARGETING_KEY_MISSING,
          OFErrorCode.INVALID_CONTEXT,
          OFErrorCode.GENERAL
        )
        assertTrue(javaCodes.forall(code => ErrorCodeConverter.toJava(ErrorCodeConverter.fromJava(code)) == code))
      }
    )
  )
}
