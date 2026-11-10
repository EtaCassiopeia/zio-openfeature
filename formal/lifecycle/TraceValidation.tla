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

\* The providers a SwapStart has installed.  Those it has not are interchangeable (none has state the model reads before
\* SdkBind), so in a run that names no provider a SwapStart installs one already seen or one canonical unseen one: the
\* symmetry reduction, without TLC permuting every state over all orderings of the providers.  A run that names some
\* steps but not a SwapStart lets it install any provider: the canonical one may not be the one a later step names.
VARIABLE seen

\* The SDK's steps a run records: the initialize() outcomes it ended with (`owed[p][ok]`: those a recorded InitOutcome added
\* and the model's SdkInitOk / SdkInitFail has not yet used up), and the shutdowns its executor ran (`called[p]`, from a
\* recorded ShutDownOld, used up by SdkShutDownOld).  A run that records some of one kind pins that kind: the model may
\* take such a step only where the run recorded it.  A run that records none leaves those steps free.
VARIABLES owed, called

Candidates == seen \cup (IF Providers \subseteq seen THEN {} ELSE {CHOOSE q \in Providers \ seen : TRUE})

\* A run whose recorder records every initialize() outcome (it says so on Begin), or one that records some.
PinInits == (Len(Trace) > 0 /\ Trace[1].pinInits) \/ \E k \in 1..Len(Trace) : Trace[k].action = "InitOutcome"
\* Pinned only in a run that records some: the wrapper records a provider's first shutdown() of a cycle only, so a
\* queued SDK shutdown it absorbs leaves no record, and an unpinned run must leave that step free.
PinShuts == \E k \in 1..Len(Trace) : Trace[k].action = "ShutDownOld"

Next1 == IF i < Len(Trace) THEN Trace[i + 1].action ELSE "END"
Step == Trace[i + 1]

\* A recorded ERROR is the SDK's PROVIDER_ERROR, which carries a fatal one too.
Recorded(e) == IF Step.event = "ERROR" THEN e \in {"ERROR", "FATAL"} ELSE e = Step.event

Lib(label, A) == Next1 = label /\ A /\ i' = i + 1
Env(A) == A /\ UNCHANGED i

\* The status the run recorded the step as leaving (trace_labels.py), if it recorded one: the status the step's own
\* update must write.
WroteStatus == Step.status = "" \/ status' = Step.status

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

\* The instance is built on P0: which the SDK has reported READY, that READY perhaps still on its way to the bridge, or
\* which is still initializing when the run begins (an async build; trace_labels.py reads that off the Begin line).
TInit ==
    /\ IF Trace[1].pending THEN InitAsync ELSE InitWith({[p |-> P0, e |-> "READY"]})
    \* A run that recorded an orphan is an owning instance's (only an owner records them).
    /\ (Trace[1].owner => owns)
    /\ i = 0
    /\ seen = {}
    /\ owed = [p \in All |-> [ok \in BOOLEAN |-> 0]]
    /\ called = [p \in All |-> 0]

\* The SDK's steps happen only where the trace's next library step can need them: an initialize() outcome before the
\* swap or watcher step that reads it, or before a delivery; an emitted event only before a delivery, and then only when
\* no event the delivery could take is already pending.  An event no handler receives is
\* never discarded: dropping it enables nothing, since the bound on events counts emits, not pending ones.  The model
\* allows all of these anywhere; restricting them keeps the search small.  The restriction is not proved
\* to lose no matching behaviour: an SDK step taken later can read state a library step changed.  The swap, timeout
\* and rollback cases were checked by hand, and the accept fixtures (formal/lifecycle/traces) keep it honest.
NeedsInit == Next1 \in {"SwapDone", "SwapRollback", "SwapFailNoRollback", "SwapReconcile", "OrphanResolved", "Deliver",
                          "ScopeClose", "ShutdownDone"}

\* Whether the recorded name `n` is `p`: a model value q1 prints as "q1" (trace_labels.py).  A record without identity
\* ("") names nothing, so any provider will do.
Named(p, n) == n = "" \/ ToString(p) = n

\* No step of the run names a provider.
Unnamed == \A k \in 1..Len(Trace) : Trace[k].provider = "" /\ Trace[k].watch = ""

SwapNext ==
    Next1 = "SwapStart" /\ \E p \in (IF Step.provider # "" THEN All ELSE IF Unnamed THEN Candidates ELSE Providers) :
        /\ Lib("SwapStart", Named(p, Step.provider) /\ SwapStart(p))
        /\ seen' = seen \cup ({p} \cap Providers)

Took(p, ok) ==
    IF PinInits THEN owed[p][ok] > 0 /\ owed' = [owed EXCEPT ![p][ok] = @ - 1] ELSE UNCHANGED owed

\* The SDK's executor ran the shutdown it queued for p; only before the steps that read whether it has run (the two
\* guards, and a teardown, which runs any still queued itself and counts it).  Free everywhere, it multiplies the search
\* on a long trace past the time limit.
CalledOld(p) ==
    IF PinShuts THEN called[p] > 0 /\ called' = [called EXCEPT ![p] = @ - 1] ELSE UNCHANGED called

InitNext ==
    \/ \E p \in All : Lib("InitOutcome", Named(p, Step.provider) /\ owed' = [owed EXCEPT ![p][Step.ok] = @ + 1] /\ UNCHANGED vars)
    \/ /\ PinInits \/ NeedsInit
       /\ \E p \in All : (Env(SdkInitOk(p)) /\ Took(p, TRUE)) \/ (Env(SdkInitFail(p)) /\ Took(p, FALSE))

ShutNext ==
    \/ \E p \in All : Lib("ShutDownOld", Named(p, Step.provider) /\ called' = [called EXCEPT ![p] = @ + 1] /\ UNCHANGED vars)
    \/ Next1 \in {"SwapStart", "OrphanResolved", "ShutdownDone", "ScopeClose"} /\ \E p \in All : Env(SdkShutDownOld(p)) /\ CalledOld(p)

PlainNext ==
    \/ Lib("SdkBind", SdkBind)
    \/ Lib("SwapDone", SwapDone /\ sdk[swap.new] = Step.state)
    \/ Lib("SwapTimeout", SwapTimeout)
    \/ Lib("SwapFailNoRollback", SwapFailNoRollback)
    \/ Next1 = "SwapRollbackUnbound" /\ Env(SdkBindFail)
    \/ Lib("SwapRollbackUnbound", SwapRollbackUnbound)
    \/ Lib("SwapReconcile", SwapReconcile /\ WroteStatus)
    \* No probe point records an interrupt of the caller, so the model's own steps for it are free.
    \/ Env(CallerInterrupt)
    \/ \E early \in BOOLEAN, r \in {"READY", "STALE", "ERROR", "FATAL"} :
         Lib("SwapRollback", (Step.ok => r = Step.state) /\ SwapRollback(Step.ok, r, early))
    \/ \E ev \in events : Lib("Deliver", Recorded(ev.e) /\ DeliverToBridge(ev) /\ WroteStatus)
    \/ \E o \in orphans : Lib("OrphanResolved", Named(o.prev, Step.provider) /\ Named(o.watch, Step.watch) /\ OrphanResolved(o))
    \/ Lib("ShutdownStart", ShutdownStart)
    \/ Lib("ShutdownDone", ShutdownDone /\ TornDown(~(shutDown \/ closed \/ ~owns)))
    \/ Lib("ScopeClose", ScopeClose /\ TornDown(~(shutDown \/ ~owns)))
    \/ NeedsInit /\ \E p \in All : Env(EmitStaleWhileStarting(p))
    \/ /\ Next1 = "Deliver" /\ ~\E ev \in events : ev.p = bound /\ Recorded(ev.e)
       /\ \E e \in {"READY", "STALE", "ERROR", "FATAL"} : Recorded(e) /\ Env(Emit(bound, e))
    \* A step that must write a given status reads the SDK's state, which an emit the run has not delivered yet may have
    \* set.  A delivery's own kind may be emitted again even while one of it is pending; a reconcile may read only an emit
    \* the run delivers later.  Any emit at all would let the model write the status a wrong run recorded.
    \/ /\ Next1 = "Deliver" /\ Step.status # ""
       /\ \E e \in {"READY", "STALE", "ERROR", "FATAL"} : Recorded(e) /\ Env(Emit(bound, e))
    \/ /\ Next1 = "SwapReconcile" /\ Step.status # ""
       /\ \E e \in {"READY", "STALE", "ERROR", "FATAL"} :
            /\ \E k \in (i + 2)..Len(Trace) :
                 Trace[k].action = "Deliver" /\ Trace[k].event = (IF e = "FATAL" THEN "ERROR" ELSE e)
            /\ Env(Emit(bound, e))

\* Recorded runs carry no evaluations (MaxEvals = 0).
TraceStep ==
    /\ \/ SwapNext /\ UNCHANGED << owed, called >>
       \/ PlainNext /\ UNCHANGED << seen, owed, called >>
       \/ InitNext /\ UNCHANGED << seen, called >>
       \/ ShutNext /\ UNCHANGED << seen, owed >>
    /\ UNCHANGED evals

TraceSpec == TInit /\ [][TraceStep]_<< vars, i, seen, owed, called >>

\* Violated exactly when some behaviour of the model matches the whole trace.
TraceNotDone == i < Len(Trace)
=============================================================================
