package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}

/** The evaluation entry points over a provider that answers wrongly.
  *
  * Each sample scripts every evaluation op of a [[FaultyProvider]] with a malformed answer or a thrown exception or
  * `Error`, and its metadata with a malformed one or its own, then calls each type through the typed tier (the per-type
  * getter and `*Details` getter, `value[A]` and `valueDetails[A]`) and the total tier (`*OrDefault`, `valueOrDefault`
  * and `resolveOrDefault`) with a [[RecordingHook]] installed. Whatever the shape: no call dies; the typed tier fails
  * with the [[FeatureFlagError]] the table below pins, or serves the value it pins; the total tier serves the default
  * with the matching error code; `finallyAfter` runs once per call.
  */
object HostileProviderSweepSpec extends ZIOSpecDefault {

  /** What an evaluation must end in. */
  sealed private trait Expected
  private object Expected {
    final case class Serves(value: Any)                    extends Expected
    final case class Fails(error: String, code: ErrorCode) extends Expected
    val typeMismatch: Fails  = Fails("TypeMismatch", ErrorCode.TypeMismatch)
    val providerError: Fails = Fails("ProviderError", ErrorCode.General)
  }

  /** One flag type: its op, key, default, the provider's value, and every entry point for it. */
  final private case class Kind(
    op: Op,
    key: String,
    default: Any,
    fresh: Any,
    typed: List[FeatureFlags => IO[FeatureFlagError, Any]],
    orDefault: List[FeatureFlags => UIO[Any]],
    resolve: FeatureFlags => UIO[FlagResolution[_]]
  )

  private val Obj: Map[String, Any] = Map("a" -> "v")

  private def kind[A: FlagType](op: Op, key: String, default: A, fresh: A)(
    typed: FeatureFlags => IO[FeatureFlagError, A],
    details: FeatureFlags => IO[FeatureFlagError, FlagResolution[A]],
    orDefault: FeatureFlags => UIO[A]
  ): Kind =
    Kind(
      op,
      key,
      default,
      fresh,
      List(typed, _.value[A](key, default), details(_).map(_.value), _.valueDetails[A](key, default).map(_.value)),
      List(orDefault, _.valueOrDefault[A](key, default)),
      _.resolveOrDefault[A](key, default)
    )

  private val ObjDefault: Map[String, Any] = Map("z" -> 0)

  // Each provider value differs from what any shape could produce on its own, so a fault that is not applied shows.
  private val kinds: List[Kind] = List(
    kind(Op.EvaluateBoolean, "b", false, true)(
      _.boolean("b", false),
      _.booleanDetails("b", false),
      _.booleanOrDefault("b", false)
    ),
    kind(Op.EvaluateString, "s", "dflt", "x")(
      _.string("s", "dflt"),
      _.stringDetails("s", "dflt"),
      _.stringOrDefault("s", "dflt")
    ),
    kind(Op.EvaluateInteger, "i", 7, 5)(_.int("i", 7), _.intDetails("i", 7), _.intOrDefault("i", 7)),
    kind(Op.EvaluateLong, "l", 7L, 5L)(_.long("l", 7L), _.longDetails("l", 7L), _.longOrDefault("l", 7L)),
    kind(Op.EvaluateDouble, "d", 7.5, 1.5)(_.double("d", 7.5), _.doubleDetails("d", 7.5), _.doubleOrDefault("d", 7.5)),
    kind[Map[String, Any]](Op.EvaluateObject, "o", ObjDefault, Obj)(
      _.obj("o", ObjDefault),
      _.objDetails("o", ObjDefault),
      _.objOrDefault("o", ObjDefault)
    )
  )

  /** The outcome each shape must have for each type. */
  private def expected(kind: Kind, fault: Fault): Expected = (fault, kind.op) match {
    // A valueless scalar is a TypeMismatch (never a defect); a valueless object reads as an empty one today.
    case (Fault.Answer(AnswerShape.NullValue), Op.EvaluateObject) => Expected.Serves(Map.empty[String, Any])
    case (Fault.Answer(AnswerShape.NullValue), _)                 => Expected.typeMismatch
    // An Integer is a number: the long and double reads widen it; every other wrong box is a TypeMismatch.
    case (Fault.Answer(AnswerShape.WrongBox), Op.EvaluateLong)   => Expected.Serves(1L)
    case (Fault.Answer(AnswerShape.WrongBox), Op.EvaluateDouble) => Expected.Serves(1.0)
    case (Fault.Answer(AnswerShape.WrongBox), _)                 => Expected.typeMismatch
    // An error code fails the typed tier whatever value comes with it.
    case (Fault.Answer(AnswerShape.ErrorCodeWithValue), _) => Expected.providerError
    // A missing reason is not an error.
    case (Fault.Answer(AnswerShape.NullReason), _)     => Expected.Serves(kind.fresh)
    case (Fault.Answer(AnswerShape.NullEvaluation), _) => Expected.providerError
    case (Fault.Throw(_), _)                           => Expected.providerError
    case (other, _) => throw new IllegalArgumentException(s"no expectation for $other")
  }

  private val flags: Map[String, Any] = kinds.map(k => k.key -> k.fresh).toMap

  private def build(
    provider: FeatureProvider,
    hook: FeatureHook,
    initTimeout: Duration = FeatureFlags.DefaultInitTimeout
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"hostile-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = List(hook),
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def errorName(e: FeatureFlagError): String = e match {
    case _: FeatureFlagError.TypeMismatch  => "TypeMismatch"
    case _: FeatureFlagError.ProviderError => "ProviderError"
    case other                             => other.getClass.getSimpleName
  }

  /** The violations of one typed-tier call. */
  private def checkTyped(label: String, exit: Exit[FeatureFlagError, Any], want: Expected): List[String] =
    (exit, want) match {
      case (Exit.Failure(cause), _) if cause.isDie =>
        List(s"$label died: ${cause.prettyPrint.take(300)}")
      case (Exit.Success(v), Expected.Serves(w)) if v == w                                                     => Nil
      case (Exit.Failure(cause), Expected.Fails(name, _)) if cause.failureOption.map(errorName).contains(name) => Nil
      case (other, w) => List(s"$label ended $other, expected $w")
    }

  private def checkTotal(label: String, values: List[Any], resolution: FlagResolution[_], kind: Kind, want: Expected) =
    want match {
      case Expected.Serves(w) =>
        values.filterNot(_ == w).map(v => s"$label orDefault served $v, expected $w") ++
          (if (resolution.value == w && resolution.errorCode.isEmpty) Nil
           else List(s"$label resolveOrDefault gave ${resolution.value} / ${resolution.errorCode}, expected $w"))
      case Expected.Fails(_, code) =>
        values.filterNot(_ == kind.default).map(v => s"$label orDefault served $v, not the default") ++
          (if (resolution.value == kind.default && resolution.errorCode.contains(code)) Nil
           else List(s"$label resolveOrDefault gave ${resolution.value} / ${resolution.errorCode}, expected $code"))
    }

  private def run(script: HostileProvider.Script): UIO[List[String]] =
    ZIO.scoped {
      for {
        recorder <- RecordingHook.make()
        provider <- HostileProvider.scripted(Map.empty, flags)
        ff       <- build(provider, recorder).orDie
        // Scripted after the build: the provider starts cleanly and every evaluation then meets its fault.
        _     <- ZIO.foreachDiscard(script) { case (op, faults) => provider.script(op, faults: _*) }
        found <- ZIO.foreach(kinds) { kind =>
          val fault = script.get(kind.op).flatMap(_.headOption).getOrElse(Fault.Succeed)
          val want  = expected(kind, fault)
          for {
            typed      <- ZIO.foreach(kind.typed.zipWithIndex) { case (f, i) => f(ff).exit.map(i -> _) }
            values     <- ZIO.foreach(kind.orDefault)(_(ff))
            resolution <- kind.resolve(ff)
          } yield typed.flatMap { case (i, exit) => checkTyped(s"${kind.key}#$i under $fault", exit, want) } ++
            checkTotal(s"${kind.key} under $fault", values, resolution, kind, want)
        }
        calls <- recorder.calls
        perCall = kinds.map(k => k.typed.length + k.orDefault.length + 1).sum
        finals  = calls.count(_.isInstanceOf[HookCall.Finally])
        befores = calls.count(_.isInstanceOf[HookCall.Before])
        hooks   =
          if (finals == perCall && befores == perCall) Nil
          else List(s"hooks: $befores before and $finals finallyAfter for $perCall calls")
        alive <- ff.booleanOrDefault("b", false).as(Nil: List[String])
      } yield found.flatten ++ hooks ++ alive
    }

  def spec = suite("HostileProviderSweepSpec")(
    test("no evaluation entry point dies, and each ends as the table pins, over any hostile answer") {
      check(HostileProvider.evaluationScripts) { script =>
        run(script).map(found => assertTrue(found.mkString("\n") == ""))
      }
    },
    test("the table covers every answer shape and thrown corpus entry for every type") {
      val faults = AnswerShape.values.map(Fault.Answer(_)) ++ HostileProvider.throwables.map(Fault.Throw(_))
      ZIO
        .foreach(faults)(f => run(HostileProvider.evaluationOps.map(op => op -> Chunk(f)).toMap).map(f -> _))
        .map(results => assertTrue(results.collect { case (f, v) if v.nonEmpty => s"$f: ${v.mkString("; ")}" }.isEmpty))
    },
    test("a hostile start and shutdown, scripted before the build, fail it or let it serve, and never kill a fiber") {
      check(HostileProvider.scripts) { script =>
        ZIO.scoped {
          for {
            recorder <- RecordingHook.make()
            provider <- HostileProvider.scripted(script, flags)
            built    <- build(provider, recorder, initTimeout = 300.millis).exit
            served   <- built match {
              case Exit.Success(ff) => ff.booleanOrDefault("b", false).exit
              case _                => ZIO.succeed(Exit.succeed(false))
            }
          } yield assertTrue(!built.causeOption.exists(_.isDie), !served.causeOption.exists(_.isDie))
        }
      }
    } @@ TestAspect.samples(20)
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.minutes)
}
