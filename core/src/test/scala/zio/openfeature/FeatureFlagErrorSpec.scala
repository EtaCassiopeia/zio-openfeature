package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*

object FeatureFlagErrorSpec extends ZIOSpecDefault:

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
        val error = FeatureFlagError.EmptyFlagVariables("my-flag")
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
    suite("isRecoverable")(
      test("evaluation errors are recoverable") {
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.FlagNotFound("key"))) &&
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.TypeMismatch("key", "A", "B"))) &&
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.ParseError("key", new Exception))) &&
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.EmptyFlagVariables("key"))) &&
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.TargetingKeyMissing("key"))) &&
        assertTrue(FeatureFlagError.isRecoverable(FeatureFlagError.InvalidContext("reason")))
      },
      test("provider errors are not recoverable") {
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))) &&
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.ProviderInitializationFailed(new Exception))) &&
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.ProviderError(new Exception))) &&
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.InvalidConfiguration("reason")))
      },
      test("transaction errors are not recoverable") {
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.NestedTransactionNotAllowed)) &&
        assertTrue(!FeatureFlagError.isRecoverable(FeatureFlagError.OverrideTypeMismatch("key", "A", "B")))
      }
    ),
    suite("isProviderError")(
      test("provider errors return true") {
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderInitializationFailed(new Exception))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.ProviderError(new Exception))) &&
        assertTrue(FeatureFlagError.isProviderError(FeatureFlagError.InvalidConfiguration("reason")))
      },
      test("non-provider errors return false") {
        assertTrue(!FeatureFlagError.isProviderError(FeatureFlagError.FlagNotFound("key"))) &&
        assertTrue(!FeatureFlagError.isProviderError(FeatureFlagError.NestedTransactionNotAllowed))
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
          FeatureFlagError.toErrorCode(FeatureFlagError.EmptyFlagVariables("key")) == ErrorCode.TypeMismatch
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
        assertTrue(
          FeatureFlagError.toErrorCode(
            FeatureFlagError.ProviderInitializationFailed(new Exception)
          ) == ErrorCode.ProviderNotReady
        ) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.ProviderError(new Exception)) == ErrorCode.General) &&
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.InvalidConfiguration("reason")) == ErrorCode.General)
      },
      test("maps transaction errors correctly") {
        assertTrue(FeatureFlagError.toErrorCode(FeatureFlagError.NestedTransactionNotAllowed) == ErrorCode.General) &&
        assertTrue(
          FeatureFlagError.toErrorCode(FeatureFlagError.OverrideTypeMismatch("k", "A", "B")) == ErrorCode.TypeMismatch
        )
      }
    )
  )
