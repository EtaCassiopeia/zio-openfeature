------------------------------ MODULE Generate ------------------------------
(***************************************************************************)
(* Random behaviours of Lifecycle, written out for the replay against the *)
(* code (core's LifecycleReplaySpec).                                      *)
(*                                                                         *)
(* GNext is Next with every disjunct labelled: the ghost `act` records the *)
(* action that produced each state and its parameters, which the replay   *)
(* turns into the inputs of LifecycleStep.step.  Lifecycle itself is       *)
(* untouched.  generate.py runs TLC in generation mode on this module and  *)
(* checks that every action of LifecycleNext has a label here.            *)
(*                                                                         *)
(* Export writes each behaviour, once it reaches Depth, as one ndjson file *)
(* of its states in order: every variable of the model plus `act`.  The   *)
(* file is named by the behaviour's number; the Json module overwrites a   *)
(* file rather than appending, so one file per behaviour.                 *)
(***************************************************************************)
EXTENDS Lifecycle, Json, TLCExt, Sequences

CONSTANTS
    OutDir,       \* the directory the behaviours are written to, absolute
    Depth,        \* the number of states in each behaviour
    ShutdownFrom  \* the level from which a shutdown or the scope close may start

VARIABLE act

GVars == << vars, act >>

Rollbacks == {"READY", "STALE", "ERROR", "FATAL"}

GInit == Init /\ act = [name |-> "Init"]

\* One disjunct per disjunct of LifecycleNext, in its order, apart from shutdown and the scope close.  Deliver is split
\* into its two cases, since only DeliverToBridge reaches the bridge.  OrphanResolved records whether the entry was
\* released (the SDK shut the previous provider down) or the library shuts it down.  SwapDone records the state the
\* swap settled in, which the code's SwapSucceeded carries.
Work ==
    \/ \E p \in All : SwapStart(p) /\ act' = [name |-> "SwapStart", p |-> p]
    \/ SdkBind /\ act' = [name |-> "SdkBind"]
    \/ SdkBindFail /\ act' = [name |-> "SdkBindFail"]
    \/ SwapRollbackUnbound /\ act' = [name |-> "SwapRollbackUnbound"]
    \/ \E p \in All : \/ SdkInitOk(p) /\ act' = [name |-> "SdkInitOk", p |-> p]
                      \/ SdkInitFail(p) /\ act' = [name |-> "SdkInitFail", p |-> p]
    \/ SwapDone /\ act' = [name |-> "SwapDone", settled |-> sdk[swap.new]]
    \/ SwapTimeout /\ act' = [name |-> "SwapTimeout"]
    \/ SwapFailNoRollback /\ act' = [name |-> "SwapFailNoRollback"]
    \/ \E ok, early \in BOOLEAN, r \in Rollbacks :
         SwapRollback(ok, r, early) /\ act' = [name |-> "SwapRollback", ok |-> ok, r |-> r, early |-> early]
    \/ SwapReconcile /\ act' = [name |-> "SwapReconcile"]
    \/ CallerInterrupt /\ act' = [name |-> "CallerInterrupt"]
    \/ \E p \in All, e \in Signals : Emit(p, e) /\ act' = [name |-> "Emit", p |-> p, e |-> e]
    \/ \E p \in All : EmitStaleWhileStarting(p) /\ act' = [name |-> "EmitStaleWhileStarting", p |-> p]
    \/ \E p \in All : Die(p) /\ act' = [name |-> "Die", p |-> p]
    \/ \E ev \in events : \/ DeliverToBridge(ev) /\ act' = [name |-> "Deliver", p |-> ev.p, e |-> ev.e]
                          \/ Discard(ev) /\ act' = [name |-> "Discard", p |-> ev.p, e |-> ev.e]
    \/ \E o \in orphans :
         OrphanResolved(o) /\ act' = [name |-> "OrphanResolved", prev |-> o.prev, watch |-> o.watch,
                                      released |-> Released(o)]

\* Shutdown and the scope close are enabled whenever the lock is free, and once the instance is shut down nothing but
\* another shutdown can happen; a walk that takes one early spends the rest of its steps there.  They are held back to
\* the last levels, unless nothing else can happen, which keeps the swaps, timeouts and rollbacks in the sample.  This
\* only removes choices, so every behaviour generated is still a behaviour of Lifecycle.
Late == TLCGet("level") >= ShutdownFrom \/ ~ENABLED Work

GNext ==
    /\ UNCHANGED evals
    /\ \/ Work
       \/ Late /\ ShutdownStart /\ act' = [name |-> "ShutdownStart"]
       \/ ShutdownDone /\ act' = [name |-> "ShutdownDone"]
       \/ Late /\ ScopeClose /\ act' = [name |-> "ScopeClose"]

GSpec == GInit /\ [][GNext]_GVars

\* TLC evaluates this on every step it generates; at the last step of a behaviour, Trace is the whole behaviour.  The
\* behaviour's number comes from TLC's own count, which is why generate.py runs one worker.
Export ==
    TLCGet("level") = Depth =>
        ndJsonSerialize(OutDir \o "/" \o ToString(TLCGet("stats").traces) \o ".ndjson", Trace)
=============================================================================
