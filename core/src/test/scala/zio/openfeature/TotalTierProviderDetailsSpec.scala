package zio.openfeature

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  ImmutableMetadata,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._

// The total tier (`*OrDefault`, `resolveOrDefault`) serves the default on a provider-reported error, but the rest of
// the provider's answer is still the caller's to see: its variant, flag metadata and message (spec 1.4).
object TotalTierProviderDetailsSpec extends ZIOSpecDefault {

  /** Answers every flag with FLAG_NOT_FOUND, a variant, flag metadata and its own message (unless `message` is null).
    */
  final private class ErrorWithDetailsProvider(message: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "ErrorWithDetails" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    private def err[T](d: T): ProviderEvaluation[T]         =
      new ProviderEvaluation[T](
        d,
        "v-err",
        "ERROR",
        OFErrorCode.FLAG_NOT_FOUND,
        message,
        ImmutableMetadata.builder().addString("source", "segment-db").build()
      )
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = err(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = err(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = err(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = err(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = err(d)
  }

  private def withMessage(m: String) = FeatureFlags.fromProvider(new ErrorWithDetailsProvider(m))

  def spec = suite("TotalTierProviderDetailsSpec")(
    test("resolveOrDefault keeps the provider's variant, flag metadata and message, serving the default") {
      for {
        res <- ZIO.serviceWithZIO[FeatureFlags](_.resolveOrDefault[Boolean]("x", false))
      } yield assertTrue(
        res.value == false,
        res.errorCode.contains(ErrorCode.FlagNotFound),
        res.reason == ResolutionReason.Error,
        res.errorMessage.contains("upstream: flag archived by alice"),
        res.variant.contains("v-err"),
        res.metadata.values.contains("source")
      )
    }.provide(withMessage("upstream: flag archived by alice")),
    test("the value-only total tier still serves the default") {
      for {
        b <- ZIO.serviceWithZIO[FeatureFlags](_.booleanOrDefault("x", true))
        s <- ZIO.serviceWithZIO[FeatureFlags](_.stringOrDefault("x", "fallback"))
        i <- ZIO.serviceWithZIO[FeatureFlags](_.intOrDefault("x", 7))
      } yield assertTrue(b, s == "fallback", i == 7)
    }.provide(withMessage("upstream: flag archived by alice")),
    test("without a provider message, the total tier keeps the typed tier's message") {
      for {
        res <- ZIO.serviceWithZIO[FeatureFlags](_.resolveOrDefault[Int]("x", 3))
      } yield assertTrue(res.value == 3, res.errorMessage.contains("Flag 'x' not found"), res.variant.contains("v-err"))
    }.provide(withMessage(null)),
    test("a custom FlagType read keeps the provider's details too") {
      implicit val plan: FlagType[String] = FlagType.from[String](
        "Plan",
        "free",
        {
          case s: String => Right(s)
          case other     => Left(s"not a plan: $other")
        }
      )
      for {
        res <- ZIO.serviceWithZIO[FeatureFlags](_.resolveOrDefault[String]("x", "free"))
      } yield assertTrue(
        res.value == "free",
        res.errorCode.contains(ErrorCode.FlagNotFound),
        res.variant.contains("v-err"),
        res.metadata.values.contains("source")
      )
    }.provide(withMessage("upstream: flag archived by alice")),
    test("the options overloads serve the same, and the error stage runs once with no after stage") {
      val errors = new java.util.concurrent.atomic.AtomicInteger(0)
      val afters = new java.util.concurrent.atomic.AtomicInteger(0)
      val hook   = new FeatureHook {
        override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
          ZIO.succeed(afters.incrementAndGet()).unit
        override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
          ZIO.succeed(errors.incrementAndGet()).unit
      }
      for {
        v <- ZIO.serviceWithZIO[FeatureFlags](
          _.booleanOrDefault("x", true, EvaluationContext.empty, EvaluationOptions(hook))
        )
      } yield assertTrue(v, errors.get == 1, afters.get == 0)
    }.provide(withMessage("upstream: flag archived by alice")),
    test("the typed tier still fails on the error code") {
      for {
        res <- ZIO.serviceWithZIO[FeatureFlags](_.booleanDetails("x", false)).either
      } yield assertTrue(res == Left(FeatureFlagError.FlagNotFound("x")))
    }.provide(withMessage("upstream: flag archived by alice"))
  ) @@ TestAspect.sequential
}
