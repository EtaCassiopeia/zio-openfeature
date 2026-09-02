package zio.openfeature

import zio.Duration
import zio.durationInt

/** How the total tier (`*OrDefault` / `resolveOrDefault`) logs a served-default fallback — the built-in warn line that
  * names the flag, why it degraded, and the value served. Hooks and metrics see every evaluation regardless; only this
  * log line is governed here.
  *
  *   - [[FallbackLogging.Off]] — no served-default line at all.
  *   - [[FallbackLogging.Always]] — every fallback is logged (a provider outage on a hot flag is a log storm).
  *   - [[FallbackLogging.Throttled]] — at most one line per flag key per `window`; the next emitted line for that key
  *     carries `(suppressed N similar)`. `Throttled(Duration.Zero)` behaves like `Always`;
  *     `Throttled(Duration.Infinity)` logs the first fallback per key only.
  *
  * An absorbed *defect* is a bug, not outage noise: its line has its own per-key bucket under `Throttled` — and under
  * `Off`, at `Default`'s window — and is never silenced; this breadcrumb is the only place a swallowed bug surfaces
  * from the value-only `*OrDefault` variants.
  *
  * Spec §1.4.11 says client operations SHOULD NOT write log messages; the default deviates deliberately — the throttle
  * bounds the volume the spec worries about, and a silently served default is the signal most worth having. `Off` is
  * the conformant setting. Recorded in `docs/spec-compliance.md`.
  */
enum FallbackLogging:
  case Off
  case Always
  case Throttled(window: Duration)

object FallbackLogging:
  /** One line per key per minute. */
  val Default: FallbackLogging = Throttled(60.seconds)
