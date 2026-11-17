---------------------------- MODULE AcquireTrace ----------------------------
(* Does a recorded fromAcquireAsync run take steps Acquire allows, in that order, and end in the state the run observed?

   A trace is the sequence of steps a test run took, as action labels (trace_labels.py derives them from the `Acquire`
   probe points the run recorded). Under Fix every action of the model is a step a point records, so each is enabled
   only when it is the trace's next label, and advances the cursor; nothing is left free. The last label, End, carries
   what the run observed once the layer was released: shutdown counts, and whether the resource `acquire` registered
   closed, and before or after a shutdown. The trace is accepted when some behaviour reaches its end: TLC reports
   `TraceNotDone` violated, which validate.py reads as acceptance. *)
EXTENDS Acquire, Json, Sequences, TLC

CONSTANT TraceFile

Trace == ndJsonDeserialize(TraceFile)

VARIABLE i

Next1 == IF i < Len(Trace) THEN Trace[i + 1].action ELSE "END"
Step == Trace[i + 1]

Lib(label, A) == Next1 = label /\ A /\ i' = i + 1

(* The step is the attempt in progress's: the run numbers its attempts in the order they start. *)
InProgress == att = Step.attempt

(* What became of attempt a's scope: the accepted or rejected attempt's is the candidate, an abandoned one's its own. *)
ClosedOf(a) == IF ab[a].st = "none" THEN closed ELSE ab[a].scopeClosed
ShutAfterCloseOf(a) == IF ab[a].st = "none" THEN usedClosed ELSE ab[a].shutAfterClose

(* An observation the run made, `n`, of the model's `v`; a negative `n` is one it did not make. *)
Observed(n, v) == n < 0 \/ n = v
ObservedFlag(n, b) == n < 0 \/ (n = 1) = b

(* The release is over, and the outcome is the one the run observed. *)
Ended ==
    /\ Finished
    /\ Observed(Step.shuts, shutdowns)
    /\ \A a \in Attempts : Observed(Step.abShuts[a], ab[a].shuts)
    /\ Observed(Step.stack, IF stackShut THEN 1 ELSE 0)
    /\ ObservedFlag(Step.closed, ClosedOf(Step.resource))
    /\ ObservedFlag(Step.shutAfterClose, ShutAfterCloseOf(Step.resource))
    /\ UNCHANGED vars

TInit == Init /\ i = 0

TraceStep ==
    \/ Lib("AttemptOk", InProgress /\ AttemptOk)
    \/ Lib("VerifyFails", InProgress /\ VerifyFails)
    \/ Lib("Timeout", InProgress /\ Timeout)
    \/ Lib("AcquireFails", InProgress /\ AcquireFails)
    \/ Lib("LateArrive", LateArrive(Step.attempt))
    \/ Lib("LateFail", LateFail(Step.attempt))
    \/ Lib("Compose", Compose)
    \/ Lib("InitOk", InitOk)
    \/ Lib("InitFails", InitFails)
    \/ Lib("SwitchOk", SwitchOk)
    \/ Lib("SwitchRefused", SwitchRefused)
    \/ Lib("FixRelease1", FixRelease1)
    \/ Lib("FixRelease2", FixRelease2)
    \* What the release shut down is what the run held.
    \/ Lib("FixRelease3", FixRelease3 /\ owned = Step.owned)
    \/ Lib("End", Ended)

TraceSpec == TInit /\ [][TraceStep]_<< vars, i >>

(* Violated exactly when some behaviour of the model matches the whole trace. *)
TraceNotDone == i < Len(Trace)
==============================================================================
