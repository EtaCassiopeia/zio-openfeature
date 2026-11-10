package zio.openfeature.testkit

import java.nio.file.{Files, Path}

import dev.openfeature.sdk.NoOpProvider
import zio._
import zio.openfeature.internal.LifecycleProbe.Point
import zio.openfeature.internal.ManagedProvider
import zio.test._

import scala.jdk.CollectionConverters._

object TraceProbeSpec extends ZIOSpecDefault {

  private val scratch: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("trace-probe")))(dir =>
      ZIO.attemptBlocking {
        Files.list(dir).iterator().asScala.foreach(Files.delete)
        Files.delete(dir)
      }.orDie
    )

  /** The ndjson lines a recorder writes after `run`. */
  private def recorded(run: TraceProbe => UIO[Unit]): Task[List[String]] =
    ZIO.scoped {
      for {
        dir   <- scratch
        trace <- ZIO.succeed(TraceProbe.recording)
        _     <- run(trace)
        _     <- trace.write("run", dir)
        lines <- ZIO.attemptBlocking(Files.readAllLines(dir.resolve("run.ndjson")).asScala.toList)
      } yield lines
    }

  private def points(ps: Point*): Task[List[String]] = recorded(trace => ZIO.foreachDiscard(ps)(trace.at))

  private def field(line: String, key: String): Option[String] =
    s""""$key":"([^"]*)"""".r.findFirstMatchIn(line).map(_.group(1))

  def spec = suite("TraceProbeSpec")(
    test("two providers sharing a name are told apart by instance") {
      val (first, second) = (new NoOpProvider(), new NoOpProvider())
      points(Point.SwapStart(first), Point.SwapStart(second)).map { lines =>
        assertTrue(
          lines.size == 2,
          lines.forall(l => field(l, "provider").contains("No-op Provider")),
          field(lines.head, "providerInstance").isDefined,
          field(lines.head, "providerInstance") != field(lines(1), "providerInstance")
        )
      }
    },
    test("the library's wrapper of a provider records the provider's instance") {
      val provider = new NoOpProvider()
      points(Point.SwapStart(provider), Point.SwapStart(ManagedProvider(provider))).map { lines =>
        assertTrue(
          field(lines.head, "providerInstance").isDefined,
          field(lines.head, "providerInstance") == field(lines(1), "providerInstance")
        )
      }
    },
    test("a settled orphan names the provider it held and the one it watched") {
      val (previous, watch) = (new NoOpProvider(), new NoOpProvider())
      points(Point.OrphanResolved(previous, watch), Point.SwapStart(previous), Point.SwapStart(watch)).map { lines =>
        assertTrue(
          field(lines.head, "providerInstance") == field(lines(1), "providerInstance"),
          field(lines.head, "watchInstance") == field(lines(2), "providerInstance"),
          field(lines.head, "watch").contains("No-op Provider")
        )
      }
    },
    test("the run's start names the instance's first provider") {
      val initial = new NoOpProvider()
      recorded(trace => trace.begin(initial) *> trace.at(Point.SwapStart(initial))).map { lines =>
        assertTrue(
          lines.head.contains(""""point":"Begin""""),
          field(lines.head, "providerInstance").isDefined,
          field(lines.head, "providerInstance") == field(lines(1), "providerInstance")
        )
      }
    },
    test("an event names its provider and keeps its event as the argument") {
      points(Point.DeliverEvent("PROVIDER_READY", "x"), Point.DeliverEvent("PROVIDER_STALE", "")).map { lines =>
        assertTrue(
          field(lines.head, "arg").contains("PROVIDER_READY"),
          field(lines.head, "provider").contains("x"),
          field(lines.head, "providerInstance").isEmpty,
          field(lines(1), "provider").contains("")
        )
      }
    },
    test("a shutdown names its provider, instance and caller") {
      points(Point.ProviderShutDown(7L, new NoOpProvider(), "sdk-thread")).map { lines =>
        assertTrue(
          field(lines.head, "arg").contains("7"),
          field(lines.head, "provider").contains("No-op Provider"),
          field(lines.head, "caller").contains("sdk-thread"),
          field(lines.head, "providerInstance").isDefined
        )
      }
    },
    test("a point that names no provider writes no identity") {
      points(Point.SwapDone).map(lines => assertTrue(field(lines.head, "provider").isEmpty))
    }
  )
}
