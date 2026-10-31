package zio.openfeature

import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, ErrorCode, EventProvider, Metadata}
import dev.openfeature.sdk.{OpenFeatureAPI, ProviderState, Value}

/** A key read again as another type inside a transaction is served from the cache only when the cached wire value could
  * have come from that type's resolver. A string is never served to a boolean or numeric read: the provider answers it,
  * as it would outside a transaction.
  *
  * Shared (cross-compiled) test source dir: braces only.
  */
object TransactionCrossTypeReadSpec extends ZIOSpecDefault {

  // String flags "mode" = "true" and "n" = "42"; integer flag "n7" = 7. A string flag read through another resolver is a
  // TYPE_MISMATCH, as every provider in this repo answers.
  private class StringFlagsProvider extends EventProvider {
    val booleanCalls                                        = new AtomicInteger(0)
    val integerCalls                                        = new AtomicInteger(0)
    val doubleCalls                                         = new AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "StringFlags" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = k match {
      case "mode" => ProviderEvaluations.of[String]("true", "STATIC")
      case "n"    => ProviderEvaluations.of[String]("42", "STATIC")
      case _      => ProviderEvaluations.error[String](d, ErrorCode.TYPE_MISMATCH, s"$k is not a string flag")
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      booleanCalls.incrementAndGet()
      ProviderEvaluations.error[java.lang.Boolean](d, ErrorCode.TYPE_MISMATCH, s"$k is a string flag")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      integerCalls.incrementAndGet()
      if (k == "n7") ProviderEvaluations.of[java.lang.Integer](Integer.valueOf(7), "STATIC")
      else ProviderEvaluations.error[java.lang.Integer](d, ErrorCode.TYPE_MISMATCH, s"$k is a string flag")
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) = {
      doubleCalls.incrementAndGet()
      ProviderEvaluations.error[java.lang.Double](d, ErrorCode.TYPE_MISMATCH, s"$k is an integer flag")
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def build(p: StringFlagsProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"txcross-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def isTypeMismatch[A](result: Either[FeatureFlagError, A], key: String): Boolean = result match {
    case Left(FeatureFlagError.TypeMismatch(k, _, _)) => k == key
    case _                                            => false
  }

  def spec = suite("TransactionCrossTypeReadSpec")(
    test("a cached String is not served to a Boolean read; the provider decides") {
      ZIO.scoped {
        val p = new StringFlagsProvider
        for {
          ff  <- build(p)
          out <- ff.transaction()(ff.string("mode", "").zip(ff.boolean("mode", default = false).either))
          (s, b) = out.result
          calls  = p.booleanCalls.get()
        } yield assertTrue(s == "true", isTypeMismatch(b, "mode"), calls == 1)
      }
    },
    test("a cached String is not served to an Int read; the provider decides") {
      ZIO.scoped {
        val p = new StringFlagsProvider
        for {
          ff  <- build(p)
          out <- ff.transaction()(ff.string("n", "").zip(ff.int("n", 0).either))
          (s, i) = out.result
          calls  = p.integerCalls.get()
        } yield assertTrue(s == "42", isTypeMismatch(i, "n"), calls == 1)
      }
    },
    test("a cached Int read as a Double is still served from the cache") {
      ZIO.scoped {
        val p = new StringFlagsProvider
        for {
          ff  <- build(p)
          out <- ff.transaction()(ff.int("n7", 0).zip(ff.doubleDetails("n7", 0.0)))
          (i, d) = out.result
          calls  = p.doubleCalls.get()
        } yield assertTrue(i == 7, d.value == 7.0, d.reason == ResolutionReason.Cached, calls == 0)
      }
    }
  )
}
