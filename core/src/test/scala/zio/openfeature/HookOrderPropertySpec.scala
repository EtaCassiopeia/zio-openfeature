package zio.openfeature

import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testsupport.PropertyBudget
import zio.test._
import zio.test.TestAspect.withLiveClock

/** Hook ordering across the API, client and invocation tiers, from docs/hooks.md ("Order and outcomes", "Failing
  * hooks") and docs/spec-compliance.md: `before` runs API, client, invocation; `error` and `finallyAfter` run in the
  * reverse order; a defect in `before` or `after` skips the rest of that stage and `finallyAfter` still runs once per
  * hook; after an `after` defect the total tier serves the default with reason Error and code General.
  */
object HookOrderPropertySpec extends ZIOSpecDefault {

  private class FlagProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Order" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      if (k == "missing") ProviderEvaluations.error[String](d, OFErrorCode.FLAG_NOT_FOUND, "not found")
      else ProviderEvaluations.of[String]("provider", "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  // What goes wrong, and in which hook (an index into the hooks in registration order: API, client, invocation).
  sealed private trait Failure
  private case object NoFailure                extends Failure
  final private case class BeforeDies(at: Int) extends Failure
  final private case class AfterDies(at: Int)  extends Failure

  final private case class Plan(api: Int, client: Int, invocation: Int, found: Boolean, failure: Failure) {
    def total: Int = api + client + invocation
  }

  private val planGen: Gen[Any, Plan] = planGen0.filter(_.total > 0)

  private def planGen0: Gen[Any, Plan] =
    for {
      api    <- Gen.int(0, 3)
      client <- Gen.int(0, 3)
      inv    <- Gen.int(0, 3)
      found  <- Gen.boolean
      n = api + client + inv
      failure <- Gen.oneOf(
        Gen.const(NoFailure),
        Gen.int(0, math.max(0, n - 1)).map(i => BeforeDies(i)),
        Gen.int(0, math.max(0, n - 1)).map(i => AfterDies(i))
      )
    } yield Plan(api, client, inv, found, failure)

  private def hook(id: String, log: Ref[Vector[String]], dieBefore: Boolean, dieAfter: Boolean): FeatureHook =
    new FeatureHook {
      override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
        log.update(_ :+ s"$id:before") *> ZIO.when(dieBefore)(ZIO.die(new RuntimeException(s"$id before"))).as(None)
      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        log.update(_ :+ s"$id:after") *> ZIO.when(dieAfter)(ZIO.die(new RuntimeException(s"$id after"))).unit
      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        log.update(_ :+ s"$id:error")
      override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
        log.update(_ :+ s"$id:finally")
    }

  final private case class Tiers(api: List[FeatureHook], client: List[FeatureHook], invocation: List[FeatureHook])

  private def tiersOf(plan: Plan, hooks: List[FeatureHook]): Tiers = {
    val (api, rest)          = hooks.splitAt(plan.api)
    val (client, invocation) = rest.splitAt(plan.client)
    Tiers(api, client, invocation)
  }

  private def build(provider: EventProvider, hooks: List[FeatureHook]): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"hook-order-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = hooks,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  // Expected log, written from the documented order and outcome rules.
  private def expectedLog(plan: Plan, ids: Vector[String]): Vector[String] = {
    val n                                  = plan.total
    val forward                            = (0 until n).toVector
    val reverse                            = forward.reverse
    def stage(name: String, idx: Seq[Int]) = idx.map(i => s"${ids(i)}:$name").toVector
    val beforeDied                         = plan.failure match { case BeforeDies(_) => true; case _ => false }
    val before                             = plan.failure match {
      case BeforeDies(at) => stage("before", forward.take(at + 1))
      case _              => stage("before", forward)
    }
    val afterDied = !beforeDied && plan.found && (plan.failure match { case AfterDies(_) => true; case _ => false })
    val after     =
      if (beforeDied || !plan.found) Vector.empty
      else
        plan.failure match {
          case AfterDies(at) => stage("after", reverse.takeWhile(_ >= at))
          case _             => stage("after", reverse)
        }
    val error = if (beforeDied || afterDied || !plan.found) stage("error", reverse) else Vector.empty
    before ++ after ++ error ++ stage("finally", reverse)
  }

  def spec: Spec[Any, Any] = suite("HookOrderPropertySpec")(
    test("hooks run in the documented order across tiers, with defects and error codes") {
      check(planGen) { plan =>
        ZIO.scoped {
          val ids       = Vector.tabulate(plan.total)(i => s"h$i")
          val dieBefore = plan.failure match { case BeforeDies(at) => Some(at); case _ => None }
          val dieAfter  = plan.failure match { case AfterDies(at) => Some(at); case _ => None }
          for {
            log <- Ref.make(Vector.empty[String])
            hooks = ids.indices.toList.map(i => hook(ids(i), log, dieBefore.contains(i), dieAfter.contains(i)))
            tiers = tiersOf(plan, hooks)
            ff <- build(new FlagProvider, tiers.client)
            _  <- ZIO.foreachDiscard(tiers.api)(ff.addZioApiHook)
            key = if (plan.found) "present" else "missing"
            result <- ff
              .stringDetails(key, "dflt", EvaluationContext.empty, EvaluationOptions(hooks = tiers.invocation))
              .sandbox
              .either
            seen <- log.get
          } yield {
            val diedAsDefect = result.left.exists(_.isDie)
            val expectDefect = plan.failure match {
              case BeforeDies(_) => true
              case AfterDies(_)  => plan.found
              case NoFailure     => false
            }
            assertTrue(
              seen == expectedLog(plan, ids),
              diedAsDefect == expectDefect,
              result.isRight == (plan.found && plan.failure == NoFailure),
              seen.count(_.endsWith(":finally")) == plan.total
            )
          }
        }
      }
    } @@ PropertyBudget(100) @@ withLiveClock,
    test("an after-hook defect serves the default with reason Error and code General on the total tier") {
      check(planGen.filter(_.found)) { plan0 =>
        ZIO.scoped {
          val plan = plan0.copy(failure = plan0.failure match {
            case AfterDies(at) => AfterDies(at); case _ => AfterDies(0)
          })
          val at = plan.failure match { case AfterDies(i) => i; case _ => 0 }
          for {
            log <- Ref.make(Vector.empty[String])
            hooks = List.tabulate(plan.total)(i => hook(s"h$i", log, dieBefore = false, dieAfter = i == at))
            tiers = tiersOf(plan, hooks)
            ff     <- build(new FlagProvider, tiers.client)
            _      <- ZIO.foreachDiscard(tiers.api)(ff.addZioApiHook)
            served <- ff.resolveOrDefault[String](
              "present",
              "dflt",
              EvaluationContext.empty,
              EvaluationOptions(hooks = tiers.invocation)
            )
          } yield assertTrue(
            served.value == "dflt",
            served.reason == ResolutionReason.Error,
            served.errorCode.contains(ErrorCode.General)
          )
        }
      }
    } @@ PropertyBudget(50) @@ withLiveClock
  )
}
