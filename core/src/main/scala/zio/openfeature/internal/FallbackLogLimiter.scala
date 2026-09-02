package zio.openfeature.internal

import zio._
import zio.openfeature.{FallbackLogging, FlagResolution}
import scala.util.Try

/** Emits the total tier's served-default warning under a [[FallbackLogging]] policy, one bucket per flag key.
  *
  * Absorbed defects get their own bucket per key (`key + DefectSuffix`), so a routine `FLAG_NOT_FOUND` line can never
  * consume the slot a bug's breadcrumb needed — and they are never silenced by `Off`, which governs served-default
  * lines only: a defect is a bug, not outage noise, and this line is the only place it surfaces. `Off` still bounds
  * that breadcrumb, at [[FallbackLogging.Default]]'s window, so a defect on a hot flag cannot storm the log either.
  *
  * The per-key map is bounded at [[FallbackLogLimiter.MaxTrackedKeys]] so an unbounded key space (per-user keys) cannot
  * leak: when full and a new key arrives, entries whose window has elapsed are pruned; if it is still full the key is
  * throttled through one shared overflow bucket — one line per window for all untracked keys together — so an outage on
  * a high-cardinality key space degrades to a trickle rather than to no limiting at all.
  */
final private[openfeature] class FallbackLogLimiter private (
  policy: FallbackLogging,
  entries: Ref[Map[String, FallbackLogLimiter.Entry]],
  maxKeys: Int
) {
  import FallbackLogLimiter.{DefectSuffix, Entry, OverflowKey}

  /** `Some(n)` — emit now, `n` events for this bucket were suppressed since the previous emit; `None` — suppress. */
  def admit(key: String): UIO[Option[Int]] = admit(policy, key)

  private def admit(under: FallbackLogging, key: String): UIO[Option[Int]] =
    under match {
      case FallbackLogging.Off               => ZIO.none
      case FallbackLogging.Always            => ZIO.some(0)
      case FallbackLogging.Throttled(window) => throttle(key, window)
    }

  private def throttle(key: String, window: Duration): UIO[Option[Int]] = {
    // `toNanos` overflows past ~292 years; `Duration.Infinity` itself is exactly Long.MaxValue nanos.
    val windowNanos = if (window.compareTo(Duration.Infinity) >= 0) Long.MaxValue else window.toNanos
    def decide(now: Long, m: Map[String, Entry], k: String): (Option[Int], Map[String, Entry]) =
      m.get(k) match {
        case Some(e) if now - e.lastEmitNanos < windowNanos =>
          (None, m.updated(k, e.copy(suppressed = e.suppressed + 1)))
        case Some(e) => (Some(e.suppressed), m.updated(k, Entry(now, 0)))
        case None    => (Some(0), m.updated(k, Entry(now, 0)))
      }
    Clock.nanoTime.flatMap { now =>
      entries.modify { m =>
        if (m.contains(key) || m.size < maxKeys) decide(now, m, key)
        else {
          val pruned = m.filter { case (k, e) => k == OverflowKey || now - e.lastEmitNanos < windowNanos }
          if (pruned.size < maxKeys) decide(now, pruned, key) else decide(now, pruned, OverflowKey)
        }
      }
    }
  }

  /** The total tier's fallback line for `key`, subject to `admit`. `defect` selects the absorbed-defect wording (with
    * the cause attached, its own bucket, and `Off` downgraded to `Default` rather than silence); otherwise the
    * resolution's error code and message are reported. Either way the served value is named, and a non-zero suppressed
    * count is appended.
    */
  def log[A](key: String, resolution: FlagResolution[A], defect: Option[Cause[Nothing]]): UIO[Unit] = {
    // A custom FlagType's value may have a throwing `toString`; a breadcrumb must never fail the never-fails tier.
    def served                  = Try(String.valueOf(resolution.value)).getOrElse("<unrenderable value>")
    def suffix(suppressed: Int) = if (suppressed > 0) s" (suppressed $suppressed similar)" else ""
    defect match {
      case Some(cause) =>
        // `Default` is `Throttled`, so a defect under `Off` is bounded, never silenced.
        val defectPolicy = policy match {
          case FallbackLogging.Off => FallbackLogging.Default
          case p                   => p
        }
        admit(defectPolicy, key + DefectSuffix).flatMap {
          case None => ZIO.unit
          case Some(n) =>
            ZIO.logWarningCause(s"${FallbackLogLimiter.absorbedDefectMessage(key)} $served${suffix(n)}", cause)
        }
      case None =>
        admit(key).flatMap {
          case None => ZIO.unit
          case Some(n) =>
            val why = resolution.errorCode.fold("error")(_.toString) + resolution.errorMessage.fold("")(m => s": $m")
            ZIO.logWarning(s"Flag '$key' fell back to its default $served ($why)${suffix(n)}")
        }
    }
  }

  /** Distinct flag keys currently tracked (the shared overflow bucket excluded). */
  def trackedKeys: UIO[Int] = entries.get.map(m => m.size - (if (m.contains(OverflowKey)) 1 else 0))
}

private[openfeature] object FallbackLogLimiter {

  final case class Entry(lastEmitNanos: Long, suppressed: Int)

  /** Upper bound on distinct keys tracked per instance (plus one shared overflow bucket). */
  val MaxTrackedKeys: Int = 1024

  /** The absorbed-defect line's prefix — shared with the `FeatureFlags` trait default so the two cannot drift. */
  def absorbedDefectMessage(key: String): String =
    s"Total evaluation of '$key' absorbed a defect; serving the default"

  // NUL cannot appear in a flag key, so neither reserved name can collide with a real one.
  private val DefectSuffix = "\u0000defect"
  private val OverflowKey  = "\u0000overflow"

  def make(policy: FallbackLogging, maxKeys: Int = MaxTrackedKeys): UIO[FallbackLogLimiter] =
    Ref.make(Map.empty[String, Entry]).map(new FallbackLogLimiter(policy, _, maxKeys))
}
