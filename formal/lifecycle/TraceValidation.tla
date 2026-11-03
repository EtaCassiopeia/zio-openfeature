-------------------------- MODULE TraceValidation --------------------------
(***************************************************************************)
(* Do a recorded run's library steps follow an order Lifecycle allows?   *)
(*                                                                         *)
(* A trace is the sequence of library steps a test run took, as action    *)
(* labels (trace_labels.py derives them from the lifecycle probe points   *)
(* the run recorded).  Each library action of the model is enabled only   *)
(* when it is the trace's next label, and advances the cursor; the SDK's  *)
(* steps (initialize() returning or throwing, providers emitting) are     *)
(* free.  The trace is accepted when some behaviour reaches its end: TLC  *)
(* reports `TraceNotDone` violated, which validate.py reads as acceptance. *)
(***************************************************************************)
EXTENDS Lifecycle, Json, Sequences, TLC

CONSTANT TraceFile

Trace == ndJsonDeserialize(TraceFile)

VARIABLE i

Next1 == IF i < Len(Trace) THEN Trace[i + 1].action ELSE "END"
Step == Trace[i + 1]

\* A recorded ERROR is the SDK's PROVIDER_ERROR, which carries a fatal one too.
Recorded(e) == IF Step.event = "ERROR" THEN e \in {"ERROR", "FATAL"} ELSE e = Step.event

Lib(label, A) == Next1 = label /\ A /\ i' = i + 1
Env(A) == A /\ UNCHANGED i

\* The shutdowns a teardown made, as the run recorded them (trace_labels.py): when the step tears the API down, the
\* model's teardown shuts down no more providers than the run counted, and no more of them throwing, hanging past the
\* bound, or returning.  No more, rather than exactly: the SDK's executor may run a shutdown it queued inside the
\* teardown that the model placed before it, when the orphan watcher's bounded wait for it elapsed, a case time leaves
\* out, so the run may count one more.  A run recorded before the wrapper had these points (`shuts` = -1) is not
\* constrained.
TornDown(tearsDown) ==
    \/ Step.shuts < 0
    \/ ~tearsDown
    \/ /\ Cardinality(TearDownShuts) <= Step.shuts
       /\ Cardinality({p \in TearDownShuts : shutBehaviour[p] = "throws"}) <= Step.threw
       /\ Cardinality({p \in TearDownShuts : shutBehaviour[p] = "hangs"}) <= Step.shuts - Step.returned
       /\ Cardinality({p \in TearDownShuts : shutBehaviour[p] = "ok"}) <= Step.returned - Step.threw

\* The instance is built on P0, which the SDK has reported READY; that READY may still be on its way to the bridge.
TInit ==
    /\ InitWith({[p |-> P0, e |-> "READY"]})
    \* A run that recorded an orphan is an owning instance's (only an owner records them).
    /\ (Trace[1].owner => owns)
    /\ i = 0

\* The SDK's steps happen only where the trace's next library step can need them: an initialize() outcome before the
\* swap or watcher step that reads it, or before a delivery; an emitted event only before a delivery, and then only when
\* no event the delivery could take is already pending.  An event no handler receives is
\* never discarded: dropping it enables nothing, since the bound on events counts emits, not pending ones.  The model
\* allows all of these anywhere; restricting them keeps the search small.  The restriction is not proved
\* to lose no matching behaviour: an SDK step taken later can read state a library step changed.  The swap, timeout
\* and rollback cases were checked by hand, and the accept fixtures (formal/lifecycle/traces) keep it honest.
NeedsInit == Next1 \in {"SwapDone", "SwapRollback", "SwapFailNoRollback", "SwapReconcile", "OrphanResolved", "Deliver",
                          "ScopeClose", "ShutdownDone"}

TraceNext ==
    \/ \E p \in Providers : Lib("SwapStart", SwapStart(p))
    \/ Lib("SdkBind", SdkBind)
    \/ Lib("SwapDone", SwapDone /\ sdk[swap.new] = Step.state)
    \/ Lib("SwapTimeout", SwapTimeout)
    \/ Lib("SwapFailNoRollback", SwapFailNoRollback)
    \/ Next1 = "SwapRollbackUnbound" /\ Env(SdkBindFail)
    \/ Lib("SwapRollbackUnbound", SwapRollbackUnbound)
    \/ Lib("SwapReconcile", SwapReconcile)
    \* No probe point records an interrupt of the caller, so the model's own steps for it are free.
    \/ Env(CallerInterrupt)
    \/ \E early \in BOOLEAN, r \in {"READY", "STALE", "ERROR", "FATAL"} :
         Lib("SwapRollback", (Step.ok => r = Step.state) /\ SwapRollback(Step.ok, r, early))
    \/ \E ev \in events : Lib("Deliver", Recorded(ev.e) /\ DeliverToBridge(ev))
    \/ \E o \in orphans : Lib("OrphanResolved", OrphanResolved(o))
    \/ Lib("ShutdownStart", ShutdownStart)
    \/ Lib("ShutdownDone", ShutdownDone /\ TornDown(~(shutDown \/ closed \/ ~owns)))
    \/ Lib("ScopeClose", ScopeClose /\ TornDown(~(shutDown \/ ~owns)))
    \/ NeedsInit /\ \E p \in All : Env(SdkInitOk(p) \/ SdkInitFail(p) \/ EmitStaleWhileStarting(p))
    \* No probe point records the SDK's executor running a shutdown it queued, so that step is free, but only before the
    \* steps that read whether it has run: the two guards, and a teardown, which runs any still queued itself and counts
    \* it.  Free everywhere, it multiplies the search on a long trace past the time limit.
    \/ Next1 \in {"SwapStart", "OrphanResolved", "ShutdownDone", "ScopeClose"} /\ \E p \in All : Env(SdkShutDownOld(p))
    \/ /\ Next1 = "Deliver" /\ ~\E ev \in events : ev.p = bound /\ Recorded(ev.e)
       /\ \E e \in {"READY", "STALE", "ERROR", "FATAL"} : Recorded(e) /\ Env(Emit(bound, e))

\* The providers a swap installs are interchangeable.
Symmetry == Permutations(Providers)

\* Recorded runs carry no evaluations (MaxEvals = 0).
TraceStep == TraceNext /\ UNCHANGED evals

TraceSpec == TInit /\ [][TraceStep]_<< vars, i >>

\* Violated exactly when some behaviour of the model matches the whole trace.
TraceNotDone == i < Len(Trace)
=============================================================================
