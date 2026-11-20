package zio.openfeature.simulation

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import zio._
import zio.openfeature.testkit.TraceProbe

/** Writes a concurrent simulation run's lifecycle trace for the model's trace check, and the plan that produced it
  * beside it under the same name (`concurrent-<schedule seed>-<id>.ndjson` and `.plan.json`), so a trace the check
  * rejects can be replayed: drop the plan into the regression corpus (`regressions/concurrent/`), which replays it with
  * its own schedule seed. A failure to write is logged, never fails the run.
  */
private[simulation] object SimulationTrace {

  def name(plan: ConcurrentSimulationSpec.Plan): String =
    s"concurrent-${java.lang.Long.toHexString(plan.scheduleSeed)}-${java.util.UUID.randomUUID().toString.take(8)}"

  def write(trace: TraceProbe, plan: ConcurrentSimulationSpec.Plan): UIO[Unit] = {
    val run = name(plan)
    trace.write(run) *>
      ZIO
        .attemptBlocking {
          val dir = TraceProbe.directory
          Files.createDirectories(dir)
          Files.write(dir.resolve(s"$run.plan.json"), PlanCodec.write(plan).getBytes(StandardCharsets.UTF_8))
          ()
        }
        .catchAllCause(c => ZIO.logWarningCause(s"could not write the plan of the simulation trace '$run'", c))
  }
}
