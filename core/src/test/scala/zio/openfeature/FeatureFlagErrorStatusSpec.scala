package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}

// Classify by the HTTP status an exception carries, not by its class name; keep the original throwable on
// Unauthorized; name the flag-scoped predicate for what it is.
@scala.annotation.nowarn("cat=deprecation")
object FeatureFlagErrorStatusSpec extends ZIOSpecDefault {

  // Built outside the assertTrue macros, which report deprecations at their own position (past @nowarn).
  private val emptyFlagVariables: FeatureFlagError = FeatureFlagError.EmptyFlagVariables("f")

  /** Shaped like Apache httpclient5's HttpResponseException: any status, exposed through getStatusCode. */
  final class FakeHttpResponseException(status: Int)
      extends RuntimeException(s"status code: $status, reason phrase: test") {
    def getStatusCode: Int = status
  }

  /** A client whose accessor is called statusCode(). */
  final class StatusCodeException(status: Int) extends RuntimeException("request failed") {
    def statusCode(): Int = status
  }

  /** Named like an HTTP response exception but carrying no status accessor and a neutral message. */
  final class OpaqueHttpResponseException extends RuntimeException("request failed")

  /** A provider whose track() fails with an HTTP 503 carried by an HttpResponseException-style exception. */
  private class FailingTrackProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "failing-track" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit =
      throw new FakeHttpResponseException(503)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  def spec = suite("FeatureFlagErrorStatusSpec")(
    test("a provider's track failing with an HTTP 503 surfaces as ProviderError, not Unauthorized") {
      ZIO.scoped {
        for {
          ff <- FeatureFlags.build(
            new FailingTrackProvider,
            domain = Some(s"status-${java.util.UUID.randomUUID()}"),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(OpenFeatureAPI.createIsolated())
          )
          result <- ff.track("purchase").either
        } yield assertTrue(result.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderError]))
      }
    },
    test("the cause does not take part in equality, and copy does not carry it") {
      val withCause = FeatureFlagError.unauthorized("x", new RuntimeException("boom"))
      val plain     = FeatureFlagError.Unauthorized("x")
      assertTrue(withCause == plain, withCause.hashCode == plain.hashCode, withCause.copy().cause.isEmpty)
    },
    test("an HttpResponseException carrying 503 is a provider error, not Unauthorized") {
      val classified = FeatureFlagError.classify(new FakeHttpResponseException(503))
      assertTrue(classified.isInstanceOf[FeatureFlagError.ProviderError])
    },
    test("an HttpResponseException carrying 401 or 403 is Unauthorized") {
      assertTrue(
        FeatureFlagError.classify(new FakeHttpResponseException(401)).isInstanceOf[FeatureFlagError.Unauthorized],
        FeatureFlagError.classify(new FakeHttpResponseException(403)).isInstanceOf[FeatureFlagError.Unauthorized]
      )
    },
    test("a statusCode() accessor is read too") {
      assertTrue(
        FeatureFlagError.classify(new StatusCodeException(403)).isInstanceOf[FeatureFlagError.Unauthorized],
        FeatureFlagError.classify(new StatusCodeException(500)).isInstanceOf[FeatureFlagError.ProviderError]
      )
    },
    test("an HttpResponseException-named class with no status and a neutral message is a provider error") {
      val classified = FeatureFlagError.classify(new OpaqueHttpResponseException)
      assertTrue(classified.isInstanceOf[FeatureFlagError.ProviderError])
    },
    test("Unauthorized from classify keeps the original throwable as its cause") {
      val t = new FakeHttpResponseException(401)
      FeatureFlagError.classify(t) match {
        case u @ FeatureFlagError.Unauthorized(_) => assertTrue(u.cause.exists(_ eq t))
        case other                                => assertTrue(other.isInstanceOf[FeatureFlagError.Unauthorized])
      }
    },
    test("Unauthorized built from a reason alone has no cause, and still matches by reason") {
      val u       = FeatureFlagError.Unauthorized("bad key")
      val matched = u match {
        case FeatureFlagError.Unauthorized(r) => r == "bad key"
      }
      assertTrue(u.cause.isEmpty, matched)
    },
    test("a message-token auth failure keeps its cause too") {
      val t = new RuntimeException("HTTP 401 Unauthorized")
      assertTrue(FeatureFlagError.classify(t).cause.exists(_ eq t))
    },
    test("isFlagError is true exactly for flag-scoped evaluation errors") {
      import FeatureFlagError._
      val boom = new RuntimeException("boom")
      assertTrue(
        isFlagError(FlagNotFound("f")),
        isFlagError(TypeMismatch("f", "a", "b")),
        isFlagError(ParseError("f", boom)),
        isFlagError(emptyFlagVariables),
        isFlagError(TargetingKeyMissing("f")),
        isFlagError(InvalidContext("c")),
        !isFlagError(NestedTransactionNotAllowed),
        !isFlagError(OverrideTypeMismatch("f", "a", "b")),
        !isFlagError(ProviderError(boom)),
        !isFlagError(Unauthorized("x")),
        !isFlagError(ProviderNotReady(ProviderStatus.NotReady))
      )
    },
    test("the deprecated isRecoverable still answers the same") {
      import FeatureFlagError._
      @scala.annotation.nowarn("cat=deprecation")
      def legacy(e: FeatureFlagError): Boolean = isRecoverable(e)
      assertTrue(legacy(FlagNotFound("f")), !legacy(ProviderFatal))
    }
  )
}
