package zio.openfeature

import zio.openfeature.internal.ProviderStatusMachine
import zio.openfeature.internal.ProviderStatusMachine.{Context, Signal}
import zio.openfeature.testsupport.PropertyBudget
import zio.test._

/** The provider-status machine against the rules in docs/lifecycle.md ("Statuses") and the machine's own ScalaDoc,
  * stated as implications over (status, signal, context, outcome) so no expectation is computed by the machine itself.
  */
object ProviderStatusMachinePropertySpec extends ZIOSpecDefault {

  private val statuses: List[ProviderStatus] = List(
    ProviderStatus.NotReady,
    ProviderStatus.Ready,
    ProviderStatus.Stale,
    ProviderStatus.Error,
    ProviderStatus.Fatal,
    ProviderStatus.ShuttingDown
  )

  private val eventSignals: List[Signal] =
    List(Signal.EventReady, Signal.EventError(fatal = false), Signal.EventError(fatal = true), Signal.EventStale)

  // Signals the init watchdog and the bridge send: everything that is not this library's own authoritative write.
  private val nonLifecycle: List[Signal] = Signal.InitTimeout :: eventSignals

  private val lifecycle: List[Signal] = List(
    Signal.SwapStarted,
    Signal.SwapSucceeded,
    Signal.SwapFailed,
    Signal.RollbackSucceeded,
    Signal.ShutdownStarted,
    Signal.ShutdownCompleted
  )

  private val signals: List[Signal] = lifecycle ++ nonLifecycle

  private val contexts: List[Context] = for {
    ever <- List(false, true)
    swap <- List(false, true)
    down <- List(false, true)
  } yield Context(ever, swap, down)

  private def unchanged(cur: ProviderStatus, next: Option[ProviderStatus]): Boolean = next.forall(_ == cur)

  // Every documented rule that applies to this step, as the list of rules it breaks.
  private def violations(
    cur: ProviderStatus,
    sig: Signal,
    ctx: Context,
    next: Option[ProviderStatus]
  ): List[String] = {
    val blocked                        = ctx.swapInProgress || ctx.shutdownCompleted
    val sticky                         = cur == ProviderStatus.Fatal || cur == ProviderStatus.ShuttingDown
    val isEvent                        = eventSignals.contains(sig)
    val rules: List[(String, Boolean)] = List(
      "shutdown started shuts down" -> (sig != Signal.ShutdownStarted || next.contains(ProviderStatus.ShuttingDown)),
      "shutdown completed is not ready" -> (sig != Signal.ShutdownCompleted || next.contains(ProviderStatus.NotReady)),
      "a swap in progress is not ready" -> (sig != Signal.SwapStarted || next.contains(ProviderStatus.NotReady)),
      "lifecycle signals always apply"  -> (!lifecycle.contains(sig) || next.isDefined),
      "swap or shutdown blocks signals" -> (!nonLifecycle.contains(sig) || !blocked || next.isEmpty),
      "fatal and shutting down are sticky" ->
        (!(nonLifecycle.contains(sig) && sticky) || unchanged(cur, next)),
      "timeout never fatals a once-ready provider" ->
        (sig != Signal.InitTimeout || !ctx.everReady || unchanged(cur, next)),
      "timeout before first ready is fatal" ->
        (sig != Signal.InitTimeout || blocked || ctx.everReady || cur != ProviderStatus.NotReady ||
          next.contains(ProviderStatus.Fatal)),
      "ready event recovers" ->
        (sig != Signal.EventReady || blocked || sticky || next.contains(ProviderStatus.Ready) ||
          (cur == ProviderStatus.Ready && unchanged(cur, next))),
      "ready event never leaves ready" ->
        (sig != Signal.EventReady || cur != ProviderStatus.Ready || unchanged(cur, next)),
      "fatal error event is fatal" ->
        (sig != Signal.EventError(true) || blocked || sticky || next.contains(ProviderStatus.Fatal)),
      "error event is error" ->
        (sig != Signal.EventError(false) || blocked || sticky ||
          (next.forall(_ == ProviderStatus.Error) && (cur == ProviderStatus.Error || next.isDefined))),
      "stale from every non-final status" ->
        (sig != Signal.EventStale || blocked ||
          (cur match {
            case ProviderStatus.NotReady | ProviderStatus.Ready | ProviderStatus.Error =>
              next.contains(ProviderStatus.Stale)
            case _ => unchanged(cur, next)
          })),
      "events never enter fatal except by a fatal error" ->
        (!isEvent || sig == Signal.EventError(true) || !next.contains(ProviderStatus.Fatal) ||
          cur == ProviderStatus.Fatal)
    )
    rules.collect { case (name, false) => name }
  }

  private val signalGen: Gen[Any, Signal]   = Gen.elements(signals: _*)
  private val contextGen: Gen[Any, Context] = Gen.elements(contexts: _*)

  def spec: Spec[Any, Any] = suite("ProviderStatusMachinePropertySpec")(
    test("every status x signal x context combination obeys the documented rules") {
      val broken = for {
        cur <- statuses
        sig <- signals
        ctx <- contexts
        v   <- violations(cur, sig, ctx, ProviderStatusMachine.transition(cur, sig, ctx))
      } yield s"$v: $cur $sig $ctx"
      assertTrue(broken.isEmpty)
    },
    test("every combination is decided without throwing") {
      val all = for { cur <- statuses; sig <- signals; ctx <- contexts } yield (cur, sig, ctx)
      assertTrue(all.forall { case (cur, sig, ctx) =>
        scala.util.Try(ProviderStatusMachine.transition(cur, sig, ctx)).isSuccess
      })
    },
    test("a signal sequence under a fixed context obeys the rules at every step") {
      check(contextGen, Gen.listOfBounded(0, 25)(signalGen)) { (ctx, sigs) =>
        val (_, broken) =
          sigs.foldLeft((ProviderStatus.NotReady: ProviderStatus, List.empty[String])) { case ((cur, acc), sig) =>
            val next = ProviderStatusMachine.transition(cur, sig, ctx)
            (next.getOrElse(cur), acc ++ violations(cur, sig, ctx, next))
          }
        assertTrue(broken.isEmpty)
      }
    } @@ PropertyBudget(200),
    test("a signal sequence with a changing context obeys the rules at every step") {
      check(Gen.listOfBounded(0, 25)(signalGen.zip(contextGen))) { steps =>
        val (_, broken) = steps.foldLeft((ProviderStatus.NotReady: ProviderStatus, List.empty[String])) {
          case ((cur, acc), (sig, ctx)) =>
            val next = ProviderStatusMachine.transition(cur, sig, ctx)
            (next.getOrElse(cur), acc ++ violations(cur, sig, ctx, next))
        }
        assertTrue(broken.isEmpty)
      }
    } @@ PropertyBudget(200),
    test("once fatal, only a lifecycle signal leaves fatal") {
      check(Gen.listOfBounded(0, 25)(signalGen.zip(contextGen))) { steps =>
        val (_, bad) =
          steps.foldLeft((ProviderStatus.NotReady: ProviderStatus, false)) { case ((cur, bad), (sig, ctx)) =>
            val next = ProviderStatusMachine.transition(cur, sig, ctx).getOrElse(cur)
            (next, bad || (cur == ProviderStatus.Fatal && next != ProviderStatus.Fatal && !lifecycle.contains(sig)))
          }
        assertTrue(!bad)
      }
    } @@ PropertyBudget(200),
    test("after shutdown completed, no event or timeout changes the status") {
      check(
        Gen.elements(statuses: _*),
        Gen.listOfBounded(0, 25)(Gen.elements(nonLifecycle: _*)),
        Gen.boolean,
        Gen.boolean
      ) { (start, sigs, ever, swap) =>
        val ctx = Context(ever, swap, shutdownCompleted = true)
        val end = sigs.foldLeft(start)((cur, sig) => ProviderStatusMachine.transition(cur, sig, ctx).getOrElse(cur))
        assertTrue(end == start)
      }
    } @@ PropertyBudget(100)
  )
}
