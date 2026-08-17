package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicReference

object VerifySpec extends ZIOSpecDefault {

  /** Records which typed getter was called (and its key) and answers according to `mode`. */
  private class RecordingProvider(mode: RecordingProvider.Mode) extends FeatureProvider {
    val lastCall = new AtomicReference[String]("")
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata   = new Metadata { override def getName: String = "recording" }
    override def getState: ProviderState = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()

    private def answer[T](getter: String, key: String, d: T): ProviderEvaluation[T] = {
      lastCall.set(s"$getter:$key")
      mode match {
        case RecordingProvider.Mode.Found   => ProviderEvaluations.of(d, "STATIC")
        case RecordingProvider.Mode.Default => ProviderEvaluations.of(d, "DEFAULT")
        case RecordingProvider.Mode.NotFound =>
          ProviderEvaluations.error(d, OFErrorCode.FLAG_NOT_FOUND, s"no flag $key")
        case RecordingProvider.Mode.Mismatch => ProviderEvaluations.error(d, OFErrorCode.TYPE_MISMATCH, "wrong type")
        case RecordingProvider.Mode.Throwing => throw new IllegalStateException("getter exploded")
      }
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = answer("boolean", k, d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = answer("string", k, d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = answer("integer", k, d)
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext)       = answer("long", k, d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = answer("double", k, d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = answer("object", k, d)
  }
  private object RecordingProvider {
    sealed trait Mode
    object Mode {
      case object Found    extends Mode
      case object Default  extends Mode
      case object NotFound extends Mode
      case object Mismatch extends Mode
      case object Throwing extends Mode
    }
  }

  def spec = suite("VerifySpec")(
    test("flagExists succeeds when the provider answers the key (STATIC reason)") {
      val p = new RecordingProvider(RecordingProvider.Mode.Found)
      Verify.flagExists[Boolean]("sentinel").apply(p).as(assertTrue(p.lastCall.get() == "boolean:sentinel"))
    },
    test("flagExists succeeds when the provider serves its default without an error code") {
      // A DEFAULT-reason answer with no errorCode still proves the provider knows the key: only errorCode is judged.
      val p = new RecordingProvider(RecordingProvider.Mode.Default)
      Verify.flagExists[Boolean]("sentinel").apply(p).as(assertCompletes)
    },
    test("flagExists fails with VerificationFailed(FlagNotFound) when the errorCode is FLAG_NOT_FOUND") {
      val p = new RecordingProvider(RecordingProvider.Mode.NotFound)
      Verify.flagExists[Boolean]("sentinel").apply(p).exit.map { exit =>
        assertTrue(
          exit == Exit.fail(Verify.VerificationFailed("sentinel", ErrorCode.FlagNotFound, Some("no flag sentinel")))
        )
      }
    },
    test("flagExists fails on any other error code too (TYPE_MISMATCH)") {
      val p = new RecordingProvider(RecordingProvider.Mode.Mismatch)
      Verify.flagExists[String]("sentinel").apply(p).exit.map { exit =>
        assertTrue(
          exit == Exit.fail(Verify.VerificationFailed("sentinel", ErrorCode.TypeMismatch, Some("wrong type")))
        )
      }
    },
    test("flagExists propagates a throwing getter as the verification failure") {
      val p = new RecordingProvider(RecordingProvider.Mode.Throwing)
      Verify.flagExists[Boolean]("sentinel").apply(p).exit.map { exit =>
        assertTrue(
          exit.isFailure,
          exit.causeOption.flatMap(_.failureOption).exists {
            case e: IllegalStateException => e.getMessage == "getter exploded"
            case _                        => false
          }
        )
      }
    },
    test("flagExists fails typed (not a defect) when the provider returns a null evaluation") {
      val p = new RecordingProvider(RecordingProvider.Mode.Found) {
        override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = null
      }
      Verify.flagExists[Boolean]("sentinel").apply(p).exit.map { exit =>
        assertTrue(
          exit == Exit.fail(
            Verify
              .VerificationFailed("sentinel", ErrorCode.General, Some("provider returned a null ProviderEvaluation"))
          )
        )
      }
    },
    test("flagExists dispatches on the FlagType's wireType") {
      val p = new RecordingProvider(RecordingProvider.Mode.Found)
      for {
        _ <- Verify.flagExists[String]("s").apply(p)
        s <- ZIO.succeed(p.lastCall.get())
        _ <- Verify.flagExists[Int]("i").apply(p)
        i <- ZIO.succeed(p.lastCall.get())
        _ <- Verify.flagExists[Long]("l").apply(p)
        l <- ZIO.succeed(p.lastCall.get())
        _ <- Verify.flagExists[Double]("d").apply(p)
        d <- ZIO.succeed(p.lastCall.get())
        _ <- Verify.flagExists[Float]("f").apply(p)
        f <- ZIO.succeed(p.lastCall.get())
        _ <- Verify.flagExists[Map[String, Any]]("o").apply(p)
        o <- ZIO.succeed(p.lastCall.get())
      } yield assertTrue(
        s == "string:s",
        i == "integer:i",
        l == "long:l",
        d == "double:d",
        f == "double:f",
        o == "object:o"
      )
    },
    test("flagExists passes the supplied evaluation context to the provider") {
      val seen = new AtomicReference[String]("")
      val p = new RecordingProvider(RecordingProvider.Mode.Found) {
        override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
          seen.set(c.getTargetingKey)
          super.getBooleanEvaluation(k, d, c)
        }
      }
      val ctx = EvaluationContext.empty.withTargetingKey("probe-user")
      Verify.flagExists[Boolean]("sentinel", ctx).apply(p).as(assertTrue(seen.get() == "probe-user"))
    },
    test("all runs checks in order and stops at the first failure") {
      val p = new RecordingProvider(RecordingProvider.Mode.Found)
      for {
        order <- Ref.make(List.empty[String])
        ok  = (tag: String) => (_: FeatureProvider) => order.update(tag :: _)
        bad = (_: FeatureProvider) => order.update("bad" :: _) *> ZIO.fail(new RuntimeException("stop"))
        exit  <- Verify.all(ok("a"), ok("b"), bad, ok("c")).apply(p).exit
        ran   <- order.get
        empty <- Verify.all().apply(p).exit
      } yield assertTrue(
        exit.isFailure,
        ran.reverse == List("a", "b", "bad"),
        empty == Exit.unit
      )
    }
  )
}
