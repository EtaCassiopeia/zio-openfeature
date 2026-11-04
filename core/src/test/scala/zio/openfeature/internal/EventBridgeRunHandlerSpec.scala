package zio.openfeature.internal

import java.util.concurrent.atomic.AtomicReference

import zio._
import zio.test._

object EventBridgeRunHandlerSpec extends ZIOSpecDefault {

  /** Runs `runHandler` on a fresh thread, as the SDK's event executor does, and answers whether that thread's interrupt
    * flag is set once it returns.
    */
  private def handlerThreadFlag(label: String, effect: UIO[Unit], interruptFirst: Boolean): Task[Boolean] =
    ZIO.runtime[Any].flatMap { runtime =>
      ZIO.attemptBlocking {
        val flag   = new AtomicReference[Option[Boolean]](None)
        val thread = new Thread(() => {
          if (interruptFirst) Thread.currentThread().interrupt()
          EventBridge.runHandler(runtime, label)(effect)
          flag.set(Some(Thread.currentThread().isInterrupted))
        })
        thread.start()
        thread.join()
        flag.get().getOrElse(throw new IllegalStateException("the handler thread did not finish"))
      }
    }

  private def errorLines: UIO[Chunk[String]] =
    ZTestLogger.logOutput.map(_.filter(_.logLevel == LogLevel.Error).map(_.message()))

  def spec = suite("EventBridge.runHandler")(
    test("an interruption of the handler thread logs nothing at ERROR and leaves the thread's flag set") {
      for {
        flag   <- handlerThreadFlag("PROVIDER_READY", ZIO.never, interruptFirst = true)
        errors <- errorLines
        debugs <- ZTestLogger.logOutput.map(_.filter(_.logLevel == LogLevel.Debug).map(_.message()))
      } yield assertTrue(
        flag,
        errors.isEmpty,
        debugs.exists(_.contains("event bridge: PROVIDER_READY"))
      )
    },
    test("a handler that dies still logs at ERROR and does not set the thread's flag") {
      for {
        flag   <- handlerThreadFlag("PROVIDER_ERROR", ZIO.die(new RuntimeException("boom")), interruptFirst = false)
        errors <- errorLines
      } yield assertTrue(
        !flag,
        errors.exists(_.contains("event bridge: PROVIDER_ERROR"))
      )
    }
  )
}
