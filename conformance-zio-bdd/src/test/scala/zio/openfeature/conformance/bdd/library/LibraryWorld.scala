package zio.openfeature.conformance.bdd.library

import scala.jdk.CollectionConverters._

import zio._
import zio.bdd.core.Default
import zio.openfeature._
import zio.openfeature.testkit.TestFeatureProvider

/** Collects the log lines a scenario cares about.
  *
  * A plain concurrent queue rather than a `Ref`, because it is written from inside a `ZLogger`, which is a *pure*
  * callback with no effect context to run a `Ref.update` in.
  */
final class LogSink {
  private val lines = new java.util.concurrent.ConcurrentLinkedQueue[String]()

  def add(line: String): Unit = {
    lines.add(line)
    ()
  }

  def all: List[String] = lines.asScala.toList
}

object LogSink {

  /** A logger that funnels warn-and-above lines into `sink`. Installed per step with
    * `FiberRef.currentLoggers.locallyWith(_ + logger)`, so it never leaks into another scenario.
    */
  def logger(sink: LogSink): ZLogger[String, Unit] = new ZLogger[String, Unit] {
    def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message: () => String,
      cause: Cause[Any],
      context: FiberRefs,
      spans: List[LogSpan],
      annotations: Map[String, String]
    ): Unit =
      if (logLevel >= LogLevel.Warning) sink.add(message())
  }
}

/** Per-scenario state for the library suites.
  *
  * zio-bdd isolates this in a `FiberRef`, so it can hold opaque references (a live `FeatureFlags`, provider handles,
  * recorder `Ref`s) that have no `Schema` — it only needs a [[Default]] instance.
  */
final case class LibraryWorld(
  // lifecycle
  scope: Option[Scope.Closeable] = None,
  flags: Option[FeatureFlags] = None,
  testProvider: Option[TestFeatureProvider] = None,
  chainedProviders: List[TestFeatureProvider] = Nil,
  recorder: Option[LibraryProviders.DefaultRecordingProvider] = None,
  // pull-based ambient context (#353/#373)
  ambient: Option[Ref[EvaluationContext]] = None,
  // fallback logging (#350)
  logSink: Option[LogSink] = None,
  // fallback-first construction (#349/#352)
  acquireStatus: Option[AcquireStatus] = None,
  swapped: Option[Ref[Boolean]] = None,
  constructionError: Option[Ref[Option[Throwable]]] = None,
  settled: Option[AcquireState] = None,
  // hooks
  hookSeen: Option[Ref[Chunk[FlagValueType]]] = None,
  // outcomes
  resultValue: Option[Any] = None,
  resultError: Option[FeatureFlagError] = None,
  resolution: Option[FlagResolution[Any]] = None,
  txResult: Option[TransactionResult[List[Any]]] = None,
  fixtureError: Option[Throwable] = None
) {

  def ff: FeatureFlags = flags.getOrElse(throw new IllegalStateException("no FeatureFlags built in this scenario"))

  def provider: TestFeatureProvider =
    testProvider.getOrElse(throw new IllegalStateException("this scenario has no TestFeatureProvider"))

  /** The value produced by the last evaluation step, whichever tier it used. */
  def observedValue: Any =
    resultValue.orElse(resolution.map(_.value)).getOrElse(throw new IllegalStateException("no evaluation ran"))
}

object LibraryWorld {
  // The state holds non-schema references, so a Schema-derived Default is not available — supply it explicitly.
  given Default[LibraryWorld] = Default.from(LibraryWorld())
}
