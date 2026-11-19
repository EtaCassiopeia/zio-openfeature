---------------------------- MODULE RegistryTrace ----------------------------
(* Does a recorded run of several instances on one API take steps Registry allows, in that order, and end with the
   shutdowns the run observed?

   A trace is the sequence of steps a test run took, as action labels (trace_labels.py derives them from the probe
   points every instance records, tagged with the instance). The model folds the SDK into the library's actions, so
   every action is a step a point records, or the test's own UserBind: each is enabled only when it is the trace's next
   label, and advances the cursor. A failure's decision is checked against the model's: whether the library shuts the
   provider down; a failed registry retry's release of the provider it replaced too. The last label, End, carries how many times each provider's shutdown() ran, which `total` counts in
   the model across registrations. The trace is accepted when some behaviour reaches its end: TLC reports
   `TraceNotDone` violated, which validate.py reads as acceptance. *)
EXTENDS Registry, Json, Sequences

CONSTANT TraceFile

Trace == ndJsonDeserialize(TraceFile)

VARIABLES
    i,          \* the cursor: how many labels the behaviour has taken
    total,      \* each provider: how many times its shutdown() ran, over all its registrations
    absorbed    \* instances whose failure hit a registration that had ended: its wrapper absorbs that shutdown

traceVars == << i, total, absorbed >>

Next1 == IF i < Len(Trace) THEN Trace[i + 1].action ELSE "END"
Step == Trace[i + 1]

(* Each shutdown the step ran, of the registration then current. *)
Counted == total' = [q \in Providers |-> total[q] + (IF shuts'[q] > shuts[q] THEN shuts'[q] - shuts[q] ELSE 0)]

Lib(label, A) == Next1 = label /\ A /\ i' = i + 1 /\ Counted /\ UNCHANGED absorbed
LibA(label, A) == Next1 = label /\ A /\ i' = i + 1 /\ Counted

(* The run's decision, `s` (1 to shut the provider down), is the model's, `shut`: for a registration that has not
   ended. One that has is absorbed by its wrapper, whichever the run decided. *)
Agrees(p, shut, s) == s >= 0 /\ p \in registered => shut = (s = 1)

(* A failure under n on a registration that had ended marks nothing, and its decision changes nothing. *)
TFailMark(n) ==
    IF bound[domainOf[n]] \in registered
    THEN FailMark(n) /\ UNCHANGED absorbed
    ELSE /\ apiOpen
         /\ phase[n] \in {"building", "open"}
         /\ swapping # n
         /\ bound[domainOf[n]] # None
         /\ failedOn[n] # bound[domainOf[n]]
         /\ absorbed' = absorbed \cup {n}
         /\ UNCHANGED vars

TFailDecide(n) ==
    IF n \in absorbed
    THEN absorbed' = absorbed \ {n} /\ UNCHANGED vars
    ELSE LET p == bound[domainOf[n]] IN
         /\ FailDecide(n)
         /\ Agrees(p, LibraryShuts(n, p), Step.shut)
         /\ UNCHANGED absorbed

(* The decision a failed swap's rollback made, when the library made one: SwapFail's own. *)
SwapDecided(n) ==
    LET d == domainOf[n]
        p == bound[d]
        others == SlotsOf(p) \ {d}
        shut == IF others = {} THEN p \in registered ELSE p \in registered /\ LibraryShuts(n, p)
    IN Agrees(p, shut, Step.shut)

(* The decision the domain's previous failed client made about the provider a retry replaced, when the retry failed
   too: ReleaseReplaced's own. *)
ReleaseDecided(n) == LET q == builtOn[n] IN Agrees(q, LibraryShuts(n, q), Step.shut)

(* What is checked is over, and each provider's shutdown() ran as often as the run counted. One the model ran may land
   after the API's own shutdown ended what is checked (the SDK runs a replaced provider's on its executor), where the
   run counts it among `late`, with the API's own shutdowns. *)
Ended ==
    /\ \A q \in Providers :
           Step.shuts[q] < 0 \/ (Step.shuts[q] <= total[q] /\ total[q] <= Step.shuts[q] + Step.late[q])
    /\ UNCHANGED << vars, total, absorbed >>

TInit == Init /\ i = 0 /\ total = [q \in Providers |-> 0] /\ absorbed = {}

TraceStep ==
    \/ Lib("Check", Check(Step.instance, Step.domain))
    \/ Lib("Create", Create(Step.instance, Step.provider, Step.own))
    \/ Lib("Complete", Complete(Step.instance))
    \/ Lib("BuildFail", BuildFail(Step.instance))
    \/ Lib("Evict", Evict(Step.instance))
    \/ Lib("ReleaseReplaced", ReleaseDecided(Step.instance) /\ ReleaseReplaced(Step.instance))
    \/ Lib("SwapStart", SwapStart(Step.instance, Step.provider))
    \/ Lib("SwapDone", SwapDone(Step.instance))
    \/ Lib("SwapOldShut", SwapOldShut(Step.instance))
    \/ Lib("SwapFailMark", SwapFailMark(Step.instance))
    \/ Lib("SwapFail", SwapDecided(Step.instance) /\ SwapFail(Step.instance))
    \/ Lib("UserBind", UserBind(Step.domain, Step.provider))
    \/ LibA("FailMark", TFailMark(Step.instance))
    \/ LibA("FailDecide", TFailDecide(Step.instance))
    \/ Lib("CloseInstance", CloseInstance(Step.instance))
    \* The run's owner is the model's.
    \/ Lib("OwnerClose", owner = Step.instance /\ OwnerClose)
    \/ Lib("RegistryClose", RegistryClose)
    \/ Next1 = "End" /\ Ended /\ i' = i + 1

TraceSpec == TInit /\ [][TraceStep]_<< vars, traceVars >>

(* Violated exactly when some behaviour of the model matches the whole trace. *)
TraceNotDone == i < Len(Trace)
==============================================================================
