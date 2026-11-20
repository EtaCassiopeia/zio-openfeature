package zio.openfeature

import zio._
import zio.test._
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}

// ZIO exits the JVM on a VirtualMachineError reaching a fiber, so each test here would end the test run rather than
// fail if a provider's StackOverflowError got through.
object ProviderStackOverflowSpec extends ZIOSpecDefault {

  private def overflow: Nothing = throw new StackOverflowError("provider stack overflow")

  /** Throws a StackOverflowError from every call into it after `initialize`, and from `initialize` when asked: after a
    * moment there, so the build is already waiting on the outcome. One thrown at once is read from a completed future,
    * a path that never saw it as fatal.
    */
  final private class OverflowingProvider(onInit: Boolean) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "overflowing" }
    override def initialize(ctx: OFEvaluationContext): Unit = if (onInit) { Thread.sleep(200); overflow }
    override def shutdown(): Unit                           = overflow
    override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit = overflow
    override def getBooleanEvaluation(
      k: String,
      d: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = overflow
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext): ProviderEvaluation[String] =
      overflow
    override def getIntegerEvaluation(
      k: String,
      d: java.lang.Integer,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = overflow
    override def getLongEvaluation(
      k: String,
      d: java.lang.Long,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Long] = overflow
    override def getDoubleEvaluation(
      k: String,
      d: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double]                                                                  = overflow
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext): ProviderEvaluation[Value] =
      overflow
  }

  private def build(onInit: Boolean = false, initTimeout: Duration = 2.seconds) =
    FeatureFlags.build(
      new OverflowingProvider(onInit),
      domain = Some(s"overflow-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def isOverflow(exit: Exit[FeatureFlagError, Any]): Boolean =
    exit.causeOption.flatMap(_.failureOption).exists {
      case FeatureFlagError.ProviderError(_: StackOverflowError) => true
      case _                                                     => false
    }

  def spec = suite("ProviderStackOverflowSpec")(
    test("every typed evaluation fails with a ProviderError carrying the provider's StackOverflowError") {
      ZIO.scoped {
        for {
          ff    <- build()
          exits <- ZIO.collectAll(
            List[IO[FeatureFlagError, Any]](
              ff.boolean("b", false),
              ff.string("s", "x"),
              ff.int("i", 1),
              ff.long("l", 1L),
              ff.double("d", 1.0),
              ff.obj("o", Map("k" -> "v"))
            ).map(_.exit)
          )
        } yield assertTrue(exits.forall(isOverflow))
      }
    },
    test("the total tier serves the default when the provider overflows its stack") {
      ZIO.scoped {
        for {
          ff <- build()
          b  <- ff.booleanOrDefault("b", true)
          s  <- ff.stringOrDefault("s", "fallback")
          l  <- ff.longOrDefault("l", 7L)
        } yield assertTrue(b, s == "fallback", l == 7L)
      }
    },
    test("track fails with a ProviderError when the provider overflows its stack") {
      ZIO.scoped(build().flatMap(_.track("purchase").exit)).map(exit => assertTrue(isOverflow(exit)))
    },
    test("an initialize that overflows its stack fails the build with a GeneralError, not a defect") {
      ZIO.scoped(build(onInit = true).exit).map { exit =>
        val failure = exit.causeOption.flatMap(_.failureOption)
        assertTrue(
          exit.causeOption.exists(!_.isDie),
          failure.exists(_.isInstanceOf[dev.openfeature.sdk.exceptions.GeneralError]),
          failure.exists(_.getCause.isInstanceOf[StackOverflowError])
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(1.minute)
}
