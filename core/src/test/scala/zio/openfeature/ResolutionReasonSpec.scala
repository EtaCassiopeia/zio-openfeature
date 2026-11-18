package zio.openfeature

import zio.test._
import zio.openfeature.internal.Evaluator

object ResolutionReasonSpec extends ZIOSpecDefault {

  private val table: List[(String, ResolutionReason)] = List(
    "STATIC"          -> ResolutionReason.Static,
    "DEFAULT"         -> ResolutionReason.Default,
    "TARGETING_MATCH" -> ResolutionReason.TargetingMatch,
    "SPLIT"           -> ResolutionReason.Split,
    "CACHED"          -> ResolutionReason.Cached,
    "DISABLED"        -> ResolutionReason.Disabled,
    "STALE"           -> ResolutionReason.Stale,
    "ERROR"           -> ResolutionReason.Error
  )

  def spec = suite("ResolutionReasonSpec")(
    test("every spec reason maps to its own ResolutionReason, in any case") {
      val upper = table.map { case (name, _) => Evaluator.toResolutionReason(name) }
      val lower = table.map { case (name, _) => Evaluator.toResolutionReason(name.toLowerCase(java.util.Locale.ROOT)) }
      val want  = table.map(_._2)
      assertTrue(upper == want, lower == want)
    },
    test("an unrecognised reason is passed through verbatim and an absent one is Unknown") {
      assertTrue(
        Evaluator.toResolutionReason("VENDOR_X") == ResolutionReason.Other("VENDOR_X"),
        Evaluator.toResolutionReason(null) == ResolutionReason.Unknown
      )
    }
  )
}
