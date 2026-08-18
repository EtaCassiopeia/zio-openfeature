package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** #388: the typed tier (`value`/`valueDetails`/every `*Details`) fails with the `FeatureFlagError` mapped from a
  * resolution's `errorCode` when one is present. Most providers report FLAG_NOT_FOUND / TYPE_MISMATCH / ... as a code
  * on the resolution rather than by throwing, and the tier a caller reaches for *because a default would be wrong* used
  * to hand back the default anyway with no signal — a fail-closed gate written with `value` silently became fail-open.
  *
  * What must NOT change: the total tier still serves the default (with the code); hooks still see the `error` stage and
  * a `finallyAfter` with details; a decode-side TypeMismatch still fails as it already did.
  *
  * Shared (cross-compiled) test source dir → braces only, no `enum`/`given`.
  */
object TypedTierErrorPromotionSpec extends ZIOSpecDefault {

  /** The key selects the error code the provider answers with; anything else is a clean STATIC value. Every resolver
    * answers the same way so the per-type `*Details` methods can each be exercised.
    */
  private class CodedProvider extends EventProvider {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)

    private def coded[T](k: String, d: T): ProviderEvaluation[T] = {
      calls.incrementAndGet()
      k match {
        case "not-found"       => ProviderEvaluations.error(d, OFErrorCode.FLAG_NOT_FOUND, "no such flag")
        case "type-mismatch"   => ProviderEvaluations.error(d, OFErrorCode.TYPE_MISMATCH, "flag is a string")
        case "parse-error"     => ProviderEvaluations.error(d, OFErrorCode.PARSE_ERROR, "bad json")
        case "targeting-key"   => ProviderEvaluations.error(d, OFErrorCode.TARGETING_KEY_MISSING, "no key")
        case "invalid-context" => ProviderEvaluations.error(d, OFErrorCode.INVALID_CONTEXT, "ctx rejected")
        case "not-ready"       => ProviderEvaluations.error(d, OFErrorCode.PROVIDER_NOT_READY, "warming up")
        case "fatal"           => ProviderEvaluations.error(d, OFErrorCode.PROVIDER_FATAL, "dead")
        case "general"         => ProviderEvaluations.error(d, OFErrorCode.GENERAL, "something else")
        case _                 => null
      }
    }

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Coded" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      Option(coded(k, d)).getOrElse(ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC"))
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      Option(coded(k, d)).getOrElse(ProviderEvaluations.of[String]("v", "STATIC"))
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      Option(coded(k, d)).getOrElse(ProviderEvaluations.of[java.lang.Integer](Int.box(7), "STATIC"))
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      Option(coded(k, d)).getOrElse(ProviderEvaluations.of[java.lang.Double](Double.box(1.5), "STATIC"))
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      Option(coded(k, d)).getOrElse(ProviderEvaluations.of[Value](new Value("x"), "STATIC"))
  }

  private def build(p: EventProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"promo-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def withFF[A](f: (FeatureFlags, CodedProvider) => ZIO[Scope, Any, A]): ZIO[Any, Any, A] =
    ZIO.scoped {
      val p = new CodedProvider
      build(p).flatMap(ff => f(ff, p))
    }

  private def recordingHook(log: Ref[List[String]], finallyDetails: Ref[Option[FlagResolution[_]]]): FeatureHook =
    new FeatureHook {
      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        log.update(_ :+ "after")
      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        log.update(_ :+ s"error:${err.getClass.getSimpleName}")
      override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
        log.update(_ :+ "finally") *> finallyDetails.set(details)
    }

  def spec = suite("TypedTierErrorPromotionSpec")(
    // --- the mapping table, through the generic typed tier ---------------------------------------------------------
    test("FLAG_NOT_FOUND → FlagNotFound(key) from value") {
      withFF((ff, _) =>
        ff.value[Boolean]("not-found", false)
          .either
          .map(r => assertTrue(r == Left(FeatureFlagError.FlagNotFound("not-found"))))
      )
    },
    test("TYPE_MISMATCH → TypeMismatch(key, expected = the flag's type, actual = the provider's message)") {
      withFF((ff, _) =>
        ff.value[String]("type-mismatch", "")
          .either
          .map(r => assertTrue(r == Left(FeatureFlagError.TypeMismatch("type-mismatch", "String", "flag is a string"))))
      )
    },
    test("PARSE_ERROR → ParseError(key, _) carrying the provider message") {
      withFF((ff, _) =>
        ff.value[Int]("parse-error", 0).either.map {
          case Left(FeatureFlagError.ParseError("parse-error", t)) => assertTrue(t.getMessage == "bad json")
          case other                                               => assertNever(s"unexpected: $other")
        }
      )
    },
    test("TARGETING_KEY_MISSING → TargetingKeyMissing(key)") {
      withFF((ff, _) =>
        ff.value[Boolean]("targeting-key", false)
          .either
          .map(r => assertTrue(r == Left(FeatureFlagError.TargetingKeyMissing("targeting-key"))))
      )
    },
    test("INVALID_CONTEXT → InvalidContext(provider message)") {
      withFF((ff, _) =>
        ff.value[Boolean]("invalid-context", false)
          .either
          .map(r => assertTrue(r == Left(FeatureFlagError.InvalidContext("ctx rejected"))))
      )
    },
    test("PROVIDER_NOT_READY → ProviderNotReady, PROVIDER_FATAL → ProviderFatal") {
      withFF((ff, _) =>
        for {
          nr <- ff.value[Boolean]("not-ready", false).either
          f  <- ff.value[Boolean]("fatal", false).either
        } yield assertTrue(
          nr == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          f == Left(FeatureFlagError.ProviderFatal)
        )
      )
    },
    test("GENERAL → ProviderError carrying the provider message") {
      withFF((ff, _) =>
        ff.value[Boolean]("general", false).either.map {
          case Left(FeatureFlagError.ProviderError(t)) => assertTrue(t.getMessage == "something else")
          case other                                   => assertNever(s"unexpected: $other")
        }
      )
    },
    // --- every entry point of the tier, not just the generic one --------------------------------------------------
    test("the per-type *Details methods and the FlagDef overloads promote too") {
      withFF { (ff, _) =>
        val flag = FlagDef("not-found", 0)
        for {
          b  <- ff.booleanDetails("not-found", false).either
          s  <- ff.stringDetails("not-found", "").either
          i  <- ff.intDetails("not-found", 0).either
          l  <- ff.longDetails("not-found", 0L).either
          d  <- ff.doubleDetails("not-found", 0.0).either
          o  <- ff.objDetails("not-found", Map.empty[String, Any]).either
          v  <- ff.valueDetails("not-found", false).either
          f  <- ff.value(flag).either
          fd <- ff.valueDetails(flag).either
        } yield assertTrue(
          List(b, s, i, l, d, o, v, f, fd).forall(_ == Left(FeatureFlagError.FlagNotFound("not-found")))
        )
      }
    },
    test("a clean resolution is untouched: value and details still succeed") {
      withFF((ff, _) =>
        for {
          v <- ff.value[Boolean]("fine", false)
          d <- ff.booleanDetails("fine", false)
        } yield assertTrue(v == true, d.value == true, d.errorCode.isEmpty, d.reason == ResolutionReason.Static)
      )
    },
    // --- the total tier is unchanged in what it serves --------------------------------------------------------------
    test("total tier: valueOrDefault serves the default; resolveOrDefault carries the code and reason Error") {
      withFF((ff, _) =>
        for {
          v <- ff.valueOrDefault[Boolean]("not-found", true)
          r <- ff.resolveOrDefault[Boolean]("not-found", true)
        } yield assertTrue(
          v == true,
          r.value == true,
          r.errorCode.contains(ErrorCode.FlagNotFound),
          r.reason == ResolutionReason.Error,
          r.errorMessage.contains("Flag 'not-found' not found")
        )
      )
    },
    // --- hooks observe exactly what they observed before ------------------------------------------------------------
    test("hooks: error stage once, no after, finallyAfter with Some(details carrying the code)") {
      withFF { (ff, _) =>
        for {
          log     <- Ref.make(List.empty[String])
          details <- Ref.make(Option.empty[FlagResolution[_]])
          _       <- ff.addHook(recordingHook(log, details))
          out     <- ff.value[Boolean]("not-found", false).either
          stages  <- log.get
          fin     <- details.get
        } yield assertTrue(
          out.isLeft,
          stages == List("error:FlagNotFound", "finally"),
          fin.exists(_.errorCode.contains(ErrorCode.FlagNotFound))
        )
      }
    },
    // --- transactions: an error is never served from the cache, but it is still recorded ---------------------------
    test(
      "transaction: a second read of the coded key fails again (not a CACHED default) and the provider is re-asked"
    ) {
      withFF { (ff, p) =>
        ff.transactionEither() {
          for {
            first  <- ff.value[Boolean]("not-found", false).either
            second <- ff.value[Boolean]("not-found", false).either
          } yield (first, second)
        }.map { out =>
          assertTrue(
            out.result._1 == Left(FeatureFlagError.FlagNotFound("not-found")),
            out.result._2 == Left(FeatureFlagError.FlagNotFound("not-found")),
            p.calls.get == 2,
            // still recorded, so an audit of the transaction sees that this key was asked for and errored
            out.wasEvaluated("not-found"),
            out.getEvaluation("not-found").exists(_.resolution.errorCode.contains(ErrorCode.FlagNotFound))
          )
        }
      }
    },
    test("transaction: a good value IS still served from the cache on the second read") {
      withFF { (ff, p) =>
        ff.transactionEither() {
          ff.value[Boolean]("fine", false) *> ff.value[Boolean]("fine", false)
        }.map(out => assertTrue(out.result == true, p.calls.get == 1))
      }
    },
    test("transaction: an override still wins over a provider that would answer with a code") {
      withFF { (ff, p) =>
        ff.transactionEither(overrides = Map("not-found" -> true)) {
          ff.value[Boolean]("not-found", false)
        }.map(out => assertTrue(out.result == true, p.calls.get == 0))
      }
    },
    // --- decode-side failure is unchanged: it already failed typed ---------------------------------------------------
    test("a decode-side TypeMismatch still fails typed (regression pin for the pre-existing half of the tier)") {
      // "v" from the string resolver is not a Level; the strict decoder rejects it → TypeMismatch, as before.
      implicit val strict: FlagType[Int] = new FlagType[Int] {
        def typeName: String                        = "Strict"
        override def wireType: String               = "String"
        def defaultValue: Int                       = 0
        def decode(value: Any): Either[String, Int] = Left(s"nope: $value")
        override def encode(value: Int): Any        = value.toString
      }
      withFF((ff, _) =>
        ff.value[Int]("fine", 0).either.map {
          case Left(FeatureFlagError.TypeMismatch("fine", "Strict", _)) => assertTrue(true)
          case other                                                    => assertNever(s"unexpected: $other")
        }
      )
    },
    // --- the total tier inside a transaction: record, then fail, then absorb — all three must compose ---------------
    test(
      "total tier inside a transaction: the default is served, the code kept, the evaluation recorded, and a re-read re-asks the provider"
    ) {
      withFF { (ff, p) =>
        ff.transactionEither() {
          for {
            v1 <- ff.valueOrDefault[Boolean]("not-found", true)
            r  <- ff.resolveOrDefault[Boolean]("not-found", true)
          } yield (v1, r)
        }.map { out =>
          val (v1, r) = out.result
          assertTrue(
            v1 == true,
            r.value == true,
            r.errorCode.contains(ErrorCode.FlagNotFound),
            // Both reads went to the provider: an error-coded evaluation is recorded but never served from the cache.
            p.calls.get == 2,
            out.wasEvaluated("not-found"),
            out.getEvaluation("not-found").exists(_.resolution.errorCode.contains(ErrorCode.FlagNotFound))
          )
        }
      }
    },
    // --- what `expected` names, on each side, for a custom type -----------------------------------------------------
    test(
      "provider TYPE_MISMATCH on a custom type: the typed failure names the domain type, the hook's error names the wire type"
    ) {
      // Deliberately different, and pinned so the difference is a decision rather than an accident: the caller asked
      // for the DOMAIN type ("Strict"), so its failure says so; a hook filters and reasons in WIRE types
      // (`HookContext.flagType` is the wire type), so its error names what the provider was actually asked for.
      implicit val strict: FlagType[Int] = new FlagType[Int] {
        def typeName: String                        = "Strict"
        override def wireType: String               = "String"
        def defaultValue: Int                       = 0
        def decode(value: Any): Either[String, Int] = Right(0)
        override def encode(value: Int): Any        = value.toString
      }
      withFF { (ff, _) =>
        for {
          seen <- Ref.make(Option.empty[FeatureFlagError])
          hook = new FeatureHook {
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              seen.set(Some(err))
          }
          _        <- ff.addHook(hook)
          typed    <- ff.value[Int]("type-mismatch", 0).either
          fromHook <- seen.get
        } yield assertTrue(
          typed == Left(FeatureFlagError.TypeMismatch("type-mismatch", "Strict", "flag is a string")),
          fromHook == Some(FeatureFlagError.TypeMismatch("type-mismatch", "String", "flag is a string"))
        )
      }
    }
  )
}
