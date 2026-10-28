package zio.openfeature.testkit

import zio._
import zio.test._
import dev.openfeature.sdk.{
  EvaluationContext,
  FeatureProvider,
  Hook,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}

object WrapperLawsSpec extends ZIOSpecDefault {

  /** Forwards every `FeatureProvider` method except `track`, which falls through to the interface's no-op default. */
  class ForgetsTrack(d: FeatureProvider) extends FeatureProvider {
    override def getMetadata: Metadata                     = d.getMetadata
    override def getProviderHooks: java.util.List[Hook[_]] = d.getProviderHooks
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState                                = d.getState
    override def initialize(c: EvaluationContext): Unit                 = d.initialize(c)
    override def initialize(c: EvaluationContext, domain: String): Unit = d.initialize(c, domain)
    override def isDomainScoped(): Boolean                              = d.isDomainScoped()
    override def shutdown(): Unit                                       = d.shutdown()
    override def getBooleanEvaluation(
      k: String,
      v: java.lang.Boolean,
      c: EvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] =
      d.getBooleanEvaluation(k, v, c)
    override def getStringEvaluation(k: String, v: String, c: EvaluationContext): ProviderEvaluation[String] =
      d.getStringEvaluation(k, v, c)
    override def getIntegerEvaluation(
      k: String,
      v: java.lang.Integer,
      c: EvaluationContext
    ): ProviderEvaluation[java.lang.Integer] =
      d.getIntegerEvaluation(k, v, c)
    override def getLongEvaluation(
      k: String,
      v: java.lang.Long,
      c: EvaluationContext
    ): ProviderEvaluation[java.lang.Long] =
      d.getLongEvaluation(k, v, c)
    override def getDoubleEvaluation(
      k: String,
      v: java.lang.Double,
      c: EvaluationContext
    ): ProviderEvaluation[java.lang.Double] =
      d.getDoubleEvaluation(k, v, c)
    override def getObjectEvaluation(k: String, v: Value, c: EvaluationContext): ProviderEvaluation[Value] =
      d.getObjectEvaluation(k, v, c)
  }

  /** Forwards everything. */
  class Complete(d: FeatureProvider) extends ForgetsTrack(d) {
    override def track(eventName: String, c: EvaluationContext, details: TrackingEventDetails): Unit =
      d.track(eventName, c, details)
  }

  /** Answers `isDomainScoped` itself. */
  final class OwnDomainScoping(d: FeatureProvider) extends Complete(d) {
    override def isDomainScoped(): Boolean = true
  }

  /** Drops the domain: `initialize(ctx, domain)` forwards to `initialize(ctx)`, a domain-ignoring wrapper. */
  final class DropsDomain(d: FeatureProvider) extends Complete(d) {
    override def initialize(c: EvaluationContext, domain: String): Unit = d.initialize(c)
  }

  /** Swaps in its own default, so the wrapped provider never sees the caller's. */
  final class OwnDefault(d: FeatureProvider) extends Complete(d) {
    override def getStringEvaluation(k: String, v: String, c: EvaluationContext): ProviderEvaluation[String] =
      d.getStringEvaluation(k, "", c)
  }

  private def faultyAround(inner: FeatureProvider): FeatureProvider =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(FaultyProvider.wrap(inner)).getOrThrowFiberFailure())

  def spec = suite("WrapperLaws")(
    suite("the law itself")(
      test("a wrapper that forgets track fails on Track") {
        for {
          found <- WrapperLaws.violations("ForgetsTrack", new ForgetsTrack(_), Op.Track, handled = false)
        } yield assertTrue(
          found == Chunk("ForgetsTrack does not delegate track: the wrapped provider received no call")
        )
      },
      test("a wrapper that forwards everything breaks no op") {
        for {
          found <- ZIO.foreach(Op.values)(WrapperLaws.violations("Complete", new Complete(_), _, handled = false))
        } yield assertTrue(found.flatten.isEmpty)
      },
      test("a wrapper that answers isDomainScoped itself passes only with the allow-list entry") {
        for {
          listed   <- WrapperLaws.violations("Own", new OwnDomainScoping(_), Op.IsDomainScoped, handled = true)
          unlisted <- WrapperLaws.violations("Own", new OwnDomainScoping(_), Op.IsDomainScoped, handled = false)
        } yield assertTrue(
          listed.isEmpty,
          unlisted == Chunk("Own does not delegate isDomainScoped: the wrapped provider received no call")
        )
      },
      test("a stale allow-list entry fails") {
        for {
          found <- WrapperLaws.violations("Complete", new Complete(_), Op.IsDomainScoped, handled = true)
        } yield assertTrue(found == Chunk("allow-list entry no longer needed: Complete delegates isDomainScoped"))
      },
      test("a wrapper that drops the domain from initialize(ctx, domain) is reported") {
        for {
          found <- WrapperLaws.violations("DropsDomain", new DropsDomain(_), Op.Initialize, handled = false)
        } yield assertTrue(
          found.size == 1,
          found.head.startsWith("DropsDomain delegates initialize(ctx, domain) with different arguments")
        )
      },
      test("a wrapper that substitutes the default is reported") {
        for {
          found <- WrapperLaws.violations("OwnDefault", new OwnDefault(_), Op.EvaluateString, handled = false)
        } yield assertTrue(
          found.size == 1,
          found.head.startsWith("OwnDefault delegates getStringEvaluation with different arguments"),
          found.head.contains("wrapper-law-default")
        )
      },
      test("a wrapper that throws instead of delegating is reported with the error") {
        val throwing: FeatureProvider => FeatureProvider = d =>
          new Complete(d) {
            override def isDomainScoped(): Boolean = throw new IllegalStateException("boom")
          }
        for {
          found <- WrapperLaws.violations("Throwing", throwing, Op.IsDomainScoped, handled = false)
        } yield assertTrue(
          found == Chunk(
            "Throwing does not delegate isDomainScoped: the wrapped provider received no call " +
              "(the wrapper threw java.lang.IllegalStateException: boom)"
          )
        )
      },
      WrapperLaws.delegation("Complete")(new Complete(_)),
      WrapperLaws.delegation("OwnDomainScoping")(
        new OwnDomainScoping(_),
        handledByWrapper = Map(Op.IsDomainScoped -> "always domain-scoped")
      )
    ),
    suite("the testkit's own wrappers")(
      WrapperLaws.delegation("CachingReasonProvider")(new CachingReasonProvider(_)),
      WrapperLaws.delegation("FaultyProvider")(
        faultyAround,
        handledByWrapper = Map(Op.GetMetadata -> "reports the name it was built with")
      )
    )
  )
}
