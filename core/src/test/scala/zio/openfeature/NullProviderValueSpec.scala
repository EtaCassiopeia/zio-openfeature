package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, OpenFeatureAPI}
import dev.openfeature.sdk.{ProviderEvaluation, Value}

/** A provider may answer a scalar evaluation with no value, or with a value of another box; the SDK passes either on
  * unchecked. The typed tier fails with `TypeMismatch` (an `Option` reads as `None`), the hook pipeline and the total
  * tier see the evaluation, and the fiber never dies.
  */
object NullProviderValueSpec extends ZIOSpecDefault {

  /** `null` answers with no value and reason DISABLED; `wrongbox` answers a `Boolean`, and `intbox` an `Integer`, from
    * the string and double getters.
    */
  final private class ValuelessProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "valueless" }

    private def valueless[T]: ProviderEvaluation[T] = ProviderEvaluations.of[T](null.asInstanceOf[T], "DISABLED")

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = valueless
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = boxed[String](k)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = valueless
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext)       = valueless
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   =
      boxed[java.lang.Double](k)

    // The erased evaluation is cast, not the value, so the foreign box reaches the library as the SDK would pass it.
    private def boxed[T](k: String): ProviderEvaluation[T] = k match {
      case "wrongbox" =>
        ProviderEvaluations.of[AnyRef](java.lang.Boolean.TRUE, "STATIC").asInstanceOf[ProviderEvaluation[T]]
      case "intbox" => ProviderEvaluations.of[AnyRef](Integer.valueOf(1), "STATIC").asInstanceOf[ProviderEvaluation[T]]
      case _        => valueless
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  /** Records the `error` and `finallyAfter` stages it sees. */
  final private class Recorder(ref: Ref[Chunk[String]]) extends FeatureHook {
    override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
      ref.update(_ :+ s"error:${error.getClass.getSimpleName}")
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      ref.update(_ :+ s"finally:${details.flatMap(_.errorCode).fold("none")(_.toString)}")
  }

  private def build: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.buildWithTracking(
      new ValuelessProvider,
      domain = Some(s"valueless-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  /** A typed failure (never a defect) that is a `TypeMismatch` for `key` whose `actual` contains `actual`. */
  private def mismatch[A](exit: Exit[FeatureFlagError, A], expected: String, actual: String): Boolean =
    !exit.causeOption.exists(_.isDie) && (exit match {
      case Exit.Failure(cause) =>
        cause.failureOption.exists {
          case FeatureFlagError.TypeMismatch(_, e, a) => e == expected && a.contains(actual)
          case _                                      => false
        }
      case _ => false
    })

  def spec = suite("NullProviderValueSpec")(
    test("a valueless scalar resolution fails each scalar type with TypeMismatch, not a defect") {
      ZIO.scoped {
        for {
          ff <- build
          b  <- ff.boolean("null", default = false).exit
          s  <- ff.string("null", default = "d").exit
          i  <- ff.int("null", default = 1).exit
          l  <- ff.long("null", default = 1L).exit
          d  <- ff.double("null", default = 1.0).exit
          f  <- ff.value[Float]("null", 1.0f).exit
        } yield assertTrue(
          mismatch(b, "Boolean", "null"),
          mismatch(s, "String", "null"),
          mismatch(i, "Int", "null"),
          mismatch(l, "Long", "null"),
          mismatch(d, "Double", "null"),
          mismatch(f, "Float", "null")
        )
      }
    },
    test("a value of another box fails with TypeMismatch naming that box") {
      ZIO.scoped {
        for {
          ff <- build
          d  <- ff.double("wrongbox", default = 0.0).exit
          s  <- ff.string("wrongbox", default = "d").exit
          si <- ff.string("intbox", default = "d").exit
        } yield assertTrue(
          mismatch(d, "Double", "Boolean"),
          mismatch(s, "String", "Boolean"),
          mismatch(si, "String", "Integer")
        )
      }
    },
    test("an Integer from the double getter is a number, and decodes as one") {
      ZIO.scoped {
        for {
          ff <- build
          d  <- ff.double("intbox", default = 0.0).exit
        } yield assertTrue(d == Exit.succeed(1.0))
      }
    },
    test("an Option read of a valueless resolution is None") {
      ZIO.scoped {
        for {
          ff <- build
          i  <- ff.value[Option[Int]]("null", None).exit
          b  <- ff.value[Option[Boolean]]("null", None).exit
        } yield assertTrue(i == Exit.succeed(None), b == Exit.succeed(None))
      }
    },
    test("the error and finally hook stages see the TypeMismatch") {
      ZIO.scoped {
        for {
          ff    <- build
          ref   <- Ref.make(Chunk.empty[String])
          _     <- ff.addHook(new Recorder(ref))
          _     <- ff.boolean("null", default = false).exit
          calls <- ref.get
        } yield assertTrue(calls == Chunk("error:TypeMismatch", "finally:TypeMismatch"))
      }
    },
    test("the total tier serves the default with error code TypeMismatch") {
      ZIO.scoped {
        for {
          ff    <- build
          value <- ff.booleanOrDefault("null", default = true)
          res   <- ff.resolveOrDefault[Boolean]("null", default = true)
        } yield assertTrue(value, res.value, res.errorCode == Some(ErrorCode.TypeMismatch))
      }
    }
  )
}
