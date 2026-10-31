package zio.openfeature

import zio._
import zio.test._

@scala.annotation.nowarn("cat=deprecation")
object FeatureFlagErrorSpec extends ZIOSpecDefault {

  // Built outside the assertTrue macros, which report deprecations at their own position (past @nowarn).
  private val emptyFlagVariables: FeatureFlagError = FeatureFlagError.EmptyFlagVariables("key")

  def spec = suite("FeatureFlagErrorSpec")(
    suite("Evaluation errors")(
      test("FlagNotFound has correct message") {
        val error = FeatureFlagError.FlagNotFound("my-flag")
        assertTrue(error.message == "Flag 'my-flag' not found") &&
        assertTrue(error.cause == None)
      },
      test("TypeMismatch has correct message") {
        val error = FeatureFlagError.TypeMismatch("my-flag", "Boolean", "String")
        assertTrue(error.message == "Flag 'my-flag' type mismatch: expected Boolean, got String") &&
        assertTrue(error.cause == None)
      },
      test("ParseError has correct message and cause") {
        val underlying = new RuntimeException("Parse failed")
        val error      = FeatureFlagError.ParseError("my-flag", underlying)
        assertTrue(error.message == "Failed to parse flag 'my-flag': Parse failed") &&
        assertTrue(error.cause == Some(underlying))
      },
      test("EmptyFlagVariables has correct message") {
        val error: FeatureFlagError = FeatureFlagError.EmptyFlagVariables("my-flag")
        assertTrue(error.message == "Flag 'my-flag' has no variables but non-boolean type requested")
      },
      test("TargetingKeyMissing has correct message") {
        val error = FeatureFlagError.TargetingKeyMissing("my-flag")
        assertTrue(error.message == "Targeting key required for flag 'my-flag' but not provided")
      },
      test("InvalidContext has correct message") {
        val error = FeatureFlagError.InvalidContext("missing required attribute")
        assertTrue(error.message == "Invalid evaluation context: missing required attribute")
      }
    ),
    suite("Provider errors")(
      test("ProviderNotReady has correct message") {
        val error = FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)
        assertTrue(error.message == "Provider not ready: NotReady")
      },
      test("ProviderFatal has correct message") {
        assertTrue(FeatureFlagError.ProviderFatal.message == "Provider is in an irrecoverable error state")
      },
      test("ProviderInitializationFailed has correct message and cause") {
        val underlying = new RuntimeException("Connection failed")
        val error      = FeatureFlagError.ProviderInitializationFailed(underlying)
        assertTrue(error.message == "Provider initialization failed: Connection failed") &&
        assertTrue(error.cause == Some(underlying))
      },
      test("ProviderError has correct message and cause") {
        val underlying = new RuntimeException("API error")
        val error      = FeatureFlagError.ProviderError(underlying)
        assertTrue(error.message == "Provider error: API error") &&
        assertTrue(error.cause == Some(underlying))
      },
      test("InvalidConfiguration has correct message") {
        val error = FeatureFlagError.InvalidConfiguration("missing API key")
        assertTrue(error.message == "Invalid provider configuration: missing API key")
      },
      test("Unauthorized has correct message") {
        val error = FeatureFlagError.Unauthorized("invalid SDK key")
        assertTrue(error.message == "Provider rejected request: invalid SDK key")
      },
      test("Unreachable preserves the underlying cause") {
        val cause = new java.net.UnknownHostException("flags.example.com")
        val error = FeatureFlagError.Unreachable(cause)
        assertTrue(error.cause.contains(cause), error.message.contains("flags.example.com"))
      }
    ),
    suite("Transaction errors")(
      test("NestedTransactionNotAllowed has correct message") {
        assertTrue(FeatureFlagError.NestedTransactionNotAllowed.message == "Nested transactions are not allowed")
      },
      test("OverrideTypeMismatch has correct message") {
        val error = FeatureFlagError.OverrideTypeMismatch("my-flag", "Int", "String")
        assertTrue(error.message == "Override for flag 'my-flag' type mismatch: expected Int, got String")
      }
    ),
    suite("isFlagError")(
      test("evaluation errors are recoverable") {
        assertTrue(FeatureFlagError.isFlagError(FeatureFlagError.FlagNotFound("key"))) &&
        assertTrue(FeatureFlagError.isFlagError(FeatureFlagError.TypeMismatch("key", "A", "B"))) &&
        assertTrue(FeatureFlagError.isFlagError(FeatureFlagError.ParseError("key", new Exception))) &&
        assertTrue(FeatureFlagError.isFlagError(emptyFlagVariables)) &&
        assertTrue(FeatureFlagError.isFlagError(FeatureFlagError.TargetingKeyMissing("key"))) &&
        assertTrue(FeatureFlagError.isFlagError(FeatureFlagError.InvalidContext("reason")))
      },
      test("provider errors are not recoverable") {
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.ProviderInitializationFailed(new Exception))) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.ProviderError(new Exception))) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.Unauthorized("reason"))) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.Unreachable(new Exception))) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.InvalidConfiguration("reason")))
      },
      test("transaction errors are not recoverable") {
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.NestedTransactionNotAllowed)) &&
        assertTrue(!FeatureFlagError.isFlagError(FeatureFlagError.OverrideTypeMismatch("key", "A", "B")))
      }
    ),
    suite("isProviderError")(
      test("provider errors return true") {
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderFatal)) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderInitializationFailed(new Exception))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderError(new Exception))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.Unauthorized("reason"))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.Unreachable(new Exception))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.InvalidConfiguration("reason")))
      },
      test("non-provider errors return false") {
        assertTrue(!FeatureFlagError.isProviderError(FeatureFlagError.FlagNotFound("key"))) &&
        assertTrue(!FeatureFlagError.isProviderError(FeatureFlagError.NestedTransactionNotAllowed))
      }
    ),
    suite("looksLikeAuthFailure")(
      test("matches standalone 401/403 tokens, unauthorized, forbidden and the OFREP contrib literal") {
        val matched = List(
          "HTTP 401",
          "status 403 returned",
          "Unauthorized",
          "forbidden",
          "authentication/authorization error for flag: my-flag"
        ).map(FeatureFlagError.looksLikeAuthFailure)
        assertTrue(matched == List(true, true, true, true, true))
      },
      test("does not match digits inside other tokens or unrelated failures") {
        val matched = List(
          "port 4013",
          "flag 403abc",
          "Rate limit exceeded for flag: my-flag",
          "General error: upstream is sick",
          ""
        ).map(FeatureFlagError.looksLikeAuthFailure)
        assertTrue(matched == List(false, false, false, false, false))
      }
    ),
    suite("toErrorCode")(
      test("maps evaluation errors correctly") {
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.FlagNotFound("key")) == ErrorCode.FlagNotFound) &&
        assertTrue(
          FeatureFlagError.toErrorCode(FeatureFlagError.TypeMismatch("k", "A", "B")) == ErrorCode.TypeMismatch
        ) &&
        assertTrue(
          FeatureFlagError.toErrorCode(FeatureFlagError.ParseError("key", new Exception)) == ErrorCode.ParseError
        ) &&
        assertTrue(
          FeatureFlagError.toErrorCode(emptyFlagVariables) == ErrorCode.TypeMismatch
        ) &&
        assertTrue(
          FeatureFlagError.toErrorCode(FeatureFlagError.TargetingKeyMissing("key")) == ErrorCode.TargetingKeyMissing
        ) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.InvalidContext("reason")) == ErrorCode.InvalidContext)
      },
      test("maps provider errors correctly") {
        assertTrue(
          FeatureFlagError.toErrorCode(
            FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)
          ) == ErrorCode.ProviderNotReady
        ) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.ProviderFatal) == ErrorCode.ProviderFatal) &&
        assertTrue(
          FeatureFlagError.toErrorCode(
            FeatureFlagError.ProviderInitializationFailed(new Exception)
          ) == ErrorCode.ProviderNotReady
        ) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.ProviderError(new Exception)) == ErrorCode.General) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.Unauthorized("k")) == ErrorCode.General) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.Unreachable(new Exception)) == ErrorCode.General) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.InvalidConfiguration("reason")) == ErrorCode.General)
      },
      test("maps transaction errors correctly") {
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.NestedTransactionNotAllowed) == ErrorCode.General) &&
        assertTrue(
          FeatureFlagError.toErrorCode(FeatureFlagError.OverrideTypeMismatch("k", "A", "B")) == ErrorCode.TypeMismatch
        )
      }
    ),
    suite("classify")(
      test("401/403 only classify as Unauthorized as standalone tokens") {
        val auth401 = FeatureFlagError.classify(new RuntimeException("HTTP 401 from provider"))
        val auth403 = FeatureFlagError.classify(new RuntimeException("server replied 403"))
        // Digits embedded in larger numbers must NOT classify as auth failures
        val port    = FeatureFlagError.classify(new RuntimeException("connect to host on port 4013 failed"))
        val payload = FeatureFlagError.classify(new RuntimeException("response size 14034 exceeded limit"))
        assertTrue(
          auth401.isInstanceOf[FeatureFlagError.Unauthorized],
          auth403.isInstanceOf[FeatureFlagError.Unauthorized],
          port.isInstanceOf[FeatureFlagError.ProviderError],
          payload.isInstanceOf[FeatureFlagError.ProviderError]
        )
      },
      test("UnknownHostException is classified as Unreachable") {
        val cause = new java.net.UnknownHostException("flags.example.com")
        val ok    = FeatureFlagError.classify(cause) match {
          case FeatureFlagError.Unreachable(t) => t eq cause
          case _                               => false
        }
        assertTrue(ok)
      },
      test("ConnectException is classified as Unreachable") {
        val cause = new java.net.ConnectException("connection refused")
        val ok    = FeatureFlagError.classify(cause) match {
          case FeatureFlagError.Unreachable(t) => t eq cause
          case _                               => false
        }
        assertTrue(ok)
      },
      test("NoRouteToHostException is classified as Unreachable") {
        val cause = new java.net.NoRouteToHostException("no route")
        val ok    = FeatureFlagError.classify(cause) match {
          case _: FeatureFlagError.Unreachable => true
          case _                               => false
        }
        assertTrue(ok)
      },
      test("RuntimeException with 401 in message is classified as Unauthorized") {
        val cause = new RuntimeException("HTTP 401: Unauthorized")
        val ok    = FeatureFlagError.classify(cause) match {
          case FeatureFlagError.Unauthorized(reason) => reason.contains("401")
          case _                                     => false
        }
        assertTrue(ok)
      },
      test("RuntimeException with forbidden in message is classified as Unauthorized") {
        val cause = new RuntimeException("Forbidden")
        val ok    = FeatureFlagError.classify(cause) match {
          case _: FeatureFlagError.Unauthorized => true
          case _                                => false
        }
        assertTrue(ok)
      },
      test("SocketTimeoutException falls through to ProviderError (timeout != unreachable)") {
        val cause = new java.net.SocketTimeoutException("read timed out")
        val ok    = FeatureFlagError.classify(cause) match {
          case FeatureFlagError.ProviderError(t) => t eq cause
          case _                                 => false
        }
        assertTrue(ok)
      },
      test("generic RuntimeException falls through to ProviderError") {
        val cause = new RuntimeException("something broke")
        val ok    = FeatureFlagError.classify(cause) match {
          case FeatureFlagError.ProviderError(t) => t eq cause
          case _                                 => false
        }
        assertTrue(ok)
      }
    ),
    suite("Operation sub-traits")(
      test("every case is in the sub-traits of the operations that raise it") {
        import FeatureFlagError._
        val boom = new RuntimeException("boom")
        // (case, evaluation, lifecycle, transaction)
        val table: List[(FeatureFlagError, Boolean, Boolean, Boolean)] = List(
          (FlagNotFound("k"), true, false, false),
          (TypeMismatch("k", "Boolean", "String"), true, false, false),
          (ParseError("k", boom), true, false, false),
          (emptyFlagVariables, true, false, false),
          (TargetingKeyMissing("k"), true, false, false),
          (InvalidContext("r"), true, false, false),
          (ProviderNotReady(ProviderStatus.NotReady), true, true, false),
          (ProviderFatal, true, true, false),
          (ProviderError(boom), true, true, false),
          (Unauthorized("r"), true, true, false),
          (Unreachable(boom), true, true, false),
          (ProviderInitializationFailed(boom), false, true, false),
          (InvalidConfiguration("r"), false, true, false),
          (NestedTransactionNotAllowed, false, false, true),
          (OverrideTypeMismatch("k", "Boolean", "String"), true, false, true)
        )
        val wrong = table.filterNot { case (e, ev, lc, tx) =>
          e.isInstanceOf[EvaluationError] == ev && e.isInstanceOf[LifecycleError] == lc &&
          e.isInstanceOf[TransactionError] == tx
        }
        assertTrue(wrong.isEmpty)
      },
      test("each sub-trait is sealed: a match over its cases needs no default") {
        // Compiled with fatal warnings on Scala 3: a missing case here is a non-exhaustive match.
        def describe(e: FeatureFlagError.TransactionError): String = e match {
          case FeatureFlagError.NestedTransactionNotAllowed   => "nested"
          case FeatureFlagError.OverrideTypeMismatch(k, _, _) => s"override $k"
        }
        assertTrue(describe(FeatureFlagError.NestedTransactionNotAllowed) == "nested")
      }
    ),
    suite("Throwable bridge")(
      test("toThrowable carries the message, the cause and the error, with no stack trace") {
        val boom = new RuntimeException("boom")
        val err  = FeatureFlagError.ProviderError(boom)
        val t    = err.toThrowable
        assertTrue(
          t.getMessage == err.message,
          t.getCause eq boom,
          t.getStackTrace.isEmpty,
          t match {
            case e: FeatureFlagException => e.error == err
            case _                       => false
          }
        )
      },
      test("asThrowable composes with orDie") {
        for {
          exit <- ZIO.fail(FeatureFlagError.FlagNotFound("k")).mapError(FeatureFlagError.asThrowable).orDie.exit
        } yield assertTrue(exit.causeOption.flatMap(_.dieOption).exists {
          case e: FeatureFlagException => e.error == FeatureFlagError.FlagNotFound("k")
          case _                       => false
        })
      }
    ),
    suite("Compat")(
      test("the deprecated zio.openfeature.Compat merges as the internal one does") {
        val e: Either[String, FeatureFlagError] = Right(FeatureFlagError.ProviderFatal)
        // Outside the assertTrue macro, which reports a deprecation at its own position, past this object's @nowarn.
        val viaAlias: Any = Compat.merge(e)
        assertTrue(viaAlias == zio.openfeature.internal.Compat.merge(e))
      }
    )
  )
}
