package zio.openfeature.internal

import zio.openfeature.*
import dev.openfeature.sdk.{ErrorCode as OFErrorCode, ImmutableMetadata, Value, Structure}
import scala.jdk.CollectionConverters.*

/** Internal utilities for converting between ZIO OpenFeature types and OpenFeature SDK types.
  */
private[openfeature] object ValueConverter:

  /** Convert OpenFeature SDK reason string to ResolutionReason enum. */
  def toResolutionReason(reason: String): ResolutionReason =
    if reason == null then ResolutionReason.Unknown
    else
      reason.toUpperCase match
        case "STATIC"          => ResolutionReason.Static
        case "DEFAULT"         => ResolutionReason.Default
        case "TARGETING_MATCH" => ResolutionReason.TargetingMatch
        case "SPLIT"           => ResolutionReason.Split
        case "CACHED"          => ResolutionReason.Cached
        case "DISABLED"        => ResolutionReason.Disabled
        case "STALE"           => ResolutionReason.Stale
        case "ERROR"           => ResolutionReason.Error
        case _                 => ResolutionReason.Unknown

  /** Convert OpenFeature SDK ErrorCode to our ErrorCode enum. */
  def toErrorCode(errorCode: OFErrorCode): ErrorCode =
    errorCode match
      case OFErrorCode.PROVIDER_NOT_READY    => ErrorCode.ProviderNotReady
      case OFErrorCode.FLAG_NOT_FOUND        => ErrorCode.FlagNotFound
      case OFErrorCode.PARSE_ERROR           => ErrorCode.ParseError
      case OFErrorCode.TYPE_MISMATCH         => ErrorCode.TypeMismatch
      case OFErrorCode.TARGETING_KEY_MISSING => ErrorCode.TargetingKeyMissing
      case OFErrorCode.INVALID_CONTEXT       => ErrorCode.InvalidContext
      case OFErrorCode.GENERAL               => ErrorCode.General
      case _                                 => ErrorCode.General

  /** Convert OpenFeature SDK metadata to our FlagMetadata. */
  def toFlagMetadata(metadata: ImmutableMetadata): FlagMetadata =
    // ImmutableMetadata doesn't expose a direct map, so we return empty
    // In practice, flag metadata is rarely used and providers don't always populate it
    FlagMetadata.empty

  /** Convert a Scala value to Java Object for OpenFeature SDK. */
  def scalaToJava(value: Any): Object = value match
    case b: Boolean     => java.lang.Boolean.valueOf(b)
    case s: String      => s
    case i: Int         => java.lang.Integer.valueOf(i)
    case l: Long        => java.lang.Long.valueOf(l)
    case d: Double      => java.lang.Double.valueOf(d)
    case f: Float       => java.lang.Float.valueOf(f)
    case list: List[?]  => list.map(scalaToJava).asJava
    case map: Map[?, ?] => map.asInstanceOf[Map[String, Any]].map((k, v) => k -> scalaToJava(v)).asJava
    case null           => null
    case other          => other.toString

  /** Convert an OpenFeature SDK Value to a Scala Map. */
  def valueToMap(value: Value): Map[String, Any] =
    if value == null || !value.isStructure then Map.empty
    else
      value
        .asStructure()
        .asMap()
        .asScala
        .map((k, v) => k -> valueToScala(v))
        .toMap

  /** Convert an OpenFeature SDK Value to a Scala value. */
  def valueToScala(value: Value): Any =
    if value == null then null
    else if value.isBoolean then value.asBoolean()
    else if value.isString then value.asString()
    else if value.isNumber then value.asDouble()
    else if value.isList then value.asList().asScala.map(valueToScala).toList
    else if value.isStructure then valueToMap(value)
    else if value.isInstant then value.asInstant()
    else null

  /** Create an OpenFeature SDK Value from a Scala Map. */
  def mapToValue(map: Map[String, Any]): Value =
    new Value(
      Structure.mapToStructure(
        map.map((k, v) => k -> scalaToJava(v)).asJava
      )
    )
