package zio.openfeature.internal

import zio.openfeature.ErrorCode
import dev.openfeature.sdk.{ErrorCode => OFErrorCode}

private[openfeature] object ErrorCodeConverter {

  def fromJava(errorCode: OFErrorCode): ErrorCode =
    errorCode match {
      case OFErrorCode.PROVIDER_NOT_READY    => ErrorCode.ProviderNotReady
      case OFErrorCode.PROVIDER_FATAL        => ErrorCode.ProviderFatal
      case OFErrorCode.FLAG_NOT_FOUND        => ErrorCode.FlagNotFound
      case OFErrorCode.PARSE_ERROR           => ErrorCode.ParseError
      case OFErrorCode.TYPE_MISMATCH         => ErrorCode.TypeMismatch
      case OFErrorCode.TARGETING_KEY_MISSING => ErrorCode.TargetingKeyMissing
      case OFErrorCode.INVALID_CONTEXT       => ErrorCode.InvalidContext
      case OFErrorCode.GENERAL               => ErrorCode.General
    }

  def toJava(errorCode: ErrorCode): OFErrorCode =
    errorCode match {
      case ErrorCode.ProviderNotReady    => OFErrorCode.PROVIDER_NOT_READY
      case ErrorCode.ProviderFatal       => OFErrorCode.PROVIDER_FATAL
      case ErrorCode.FlagNotFound        => OFErrorCode.FLAG_NOT_FOUND
      case ErrorCode.ParseError          => OFErrorCode.PARSE_ERROR
      case ErrorCode.TypeMismatch        => OFErrorCode.TYPE_MISMATCH
      case ErrorCode.TargetingKeyMissing => OFErrorCode.TARGETING_KEY_MISSING
      case ErrorCode.InvalidContext      => OFErrorCode.INVALID_CONTEXT
      case ErrorCode.General             => OFErrorCode.GENERAL
    }
}
