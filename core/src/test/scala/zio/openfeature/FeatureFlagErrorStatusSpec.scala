package zio.openfeature

import zio.test._

// #440: classify by the HTTP status an exception carries, not by its class name; keep the original throwable on
// Unauthorized; name the flag-scoped predicate for what it is.
object FeatureFlagErrorStatusSpec extends ZIOSpecDefault {

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

  def spec = suite("FeatureFlagErrorStatusSpec (#440)")(
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
      val u = FeatureFlagError.Unauthorized("bad key")
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
        isFlagError(EmptyFlagVariables("f")),
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
