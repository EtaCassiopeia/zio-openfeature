------------------------------ MODULE Generate ------------------------------
(***************************************************************************)
(* Random behaviours of CircuitBreaker, written out for the replay against *)
(* the code (extras' CircuitBreakerReplaySpec).                            *)
(*                                                                         *)
(* GNext is Next with every step labelled: the ghost `act` records the pc  *)
(* label the step ran from and the process that took it, and, where the    *)
(* step consumes it, the outcome or signal it carried, which the next      *)
(* state no longer holds.  CircuitBreaker itself is untouched.             *)
(* generate.py runs TLC in generation mode on this module and checks that  *)
(* every process of the translation's Next is stepped here.                *)
(*                                                                         *)
(* Export writes each behaviour, once it reaches Depth, as one ndjson file *)
(* of its states in order: every variable of the model plus `act`.  The    *)
(* file is named by the behaviour's number.                                *)
(***************************************************************************)
EXTENDS CircuitBreaker, Json, TLCExt, Sequences

CONSTANTS
    OutDir,  \* the directory the behaviours are written to, absolute
    Depth    \* the number of states in each behaviour

VARIABLE act

GVars == << vars, act >>

GInit == Init /\ act = [name |-> "Init"]

GNext ==
    \/ \E self \in Callers :
         Caller(self) /\ act' = [name |-> pc[self], p |-> self, outcome |-> outcome[self]]
    \/ \E self \in Signals :
         Signal(self) /\ act' = [name |-> pc[self], p |-> self, op |-> op[self]]
    \/ Clock /\ act' = [name |-> pc["clock"], p |-> "clock"]

GSpec == GInit /\ [][GNext]_GVars

\* The targets of targets.py: each is stated as if it could not happen, so that TLC's counterexample is a shortest
\* behaviour that does.  They are the cases that random behaviours reach only by luck.
\* The steps, not the states before them: the replay calls the code at the step, so a target ends with it.
StaleOutcomeNeverIgnored ==
    [][~(\E c \in Callers : /\ pc[c] = "CRecordRead" /\ pc'[c] = "CArrive"
                            /\ permit[c].epoch # st.epoch /\ RecordNext(outcome[c], st) # st)]_vars
ResetNeverMetFailures ==
    [][~(\E s \in Signals : /\ pc[s] = "SRead" /\ pc'[s] = "SArrive" /\ op[s].kind = "Reset"
                            /\ "External" \in op[s].openers /\ st.circuit.reason = "Failures")]_vars
LateProbeNeverRecords ==
    [][~(\E c, d \in Callers : /\ c # d /\ pc[c] = "CRecordRead" /\ pc'[c] = "CArrive"
                               /\ permit[c].kind = "probe" /\ permit[c].epoch # st.epoch
                               /\ RecordNext(outcome[c], st) # st
                               /\ st.circuit.probing /\ permit[d].kind = "probe" /\ permit[d].epoch = st.epoch)]_vars
TripNeverClearsStale == [][~(st.circuit.stale /\ st'.circuit.kind = "Open" /\ ~st'.circuit.stale)]_st
PollNeverHandsOver == [][~(st.circuit.reason = "Polled" /\ st'.circuit.reason = "External")]_st

\* TLC evaluates this on every step it generates; at the last step of a behaviour, Trace is the whole behaviour.  The
\* behaviour's number comes from TLC's own count, which is why generate.py runs one worker.
Export ==
    TLCGet("level") = Depth =>
        ndJsonSerialize(OutDir \o "/" \o ToString(TLCGet("stats").traces) \o ".ndjson", Trace)
=============================================================================
