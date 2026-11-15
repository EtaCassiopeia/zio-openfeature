------------------------------ MODULE Generate ------------------------------
(***************************************************************************)
(* Random behaviours of Caching, written out for the replay through the    *)
(* real CachingProvider (extras' CachingReplaySpec).                       *)
(*                                                                         *)
(* GNext is Next with every step labelled: the ghost `act` records the pc  *)
(* label the step ran from and the process that took it.  Caching itself   *)
(* is untouched.  generate.py runs TLC in generation mode on this module   *)
(* and checks that every process of the translation's Next is stepped      *)
(* here.                                                                   *)
(*                                                                         *)
(* The replay cannot split some sequences of steps, so GNext restricts     *)
(* Next: while a process is in the middle of one, only the processes in    *)
(* the middle of one may step (Busy).  The sequences are a caller's CStart *)
(* and CGet, an invalidation's IBump and IClear, a caller's CTail and      *)
(* CRetry, and a joiner's wake-up once the lookup it joined has written.   *)
(* This only removes choices, so every behaviour generated is still a      *)
(* behaviour of Caching.  The steps of one cluster commute: each one       *)
(* removes the same key, so the replay compares the state only once Busy   *)
(* is empty (Settled).                                                     *)
(*                                                                         *)
(* Once every process is done, the step `Done` repeats, counting, so that  *)
(* every behaviour reaches Depth: TLC's Trace keeps no stuttering step.    *)
(* Export writes each behaviour, once it                                   *)
(* reaches Depth, as one ndjson file of its states in order: every         *)
(* variable of the model plus `act`.  The file is named by the             *)
(* behaviour's number.                                                     *)
(***************************************************************************)
EXTENDS Caching, Json, TLCExt, Sequences

CONSTANTS
    OutDir,  \* the directory the behaviours are written to, absolute
    Depth    \* the number of states in each behaviour

VARIABLE act

GVars == << vars, act >>

Invalidators == {MaxCallers + k : k \in 1..MaxChanges}

Busy ==
    {c \in Callers : \/ pc[c] \in {"CGet", "CTail", "CRetry"}
                     \/ pc[c] = "CAwait" /\ promise[joined[c]] # NoExit}
    \cup {i \in Invalidators : pc[i] = "IClear"}

Settled == Busy = {}

May(p) == Settled \/ p \in Busy

GInit == Init /\ act = [name |-> "Init"]

GNext ==
    \/ May(0) /\ Delegate /\ act' = [name |-> pc[0], p |-> 0]
    \/ \E self \in Invalidators : May(self) /\ Invalidator(self) /\ act' = [name |-> pc[self], p |-> self]
    \/ \E self \in Callers : May(self) /\ Caller(self) /\ act' = [name |-> pc[self], p |-> self]
    \/ /\ Terminating
       /\ act.name = "Done" => act.n < Depth
       /\ act' = [name |-> "Done", n |-> IF act.name = "Done" THEN act.n + 1 ELSE 1]

GSpec == GInit /\ [][GNext]_GVars

\* The targets of targets.py: each is stated as if it could not happen, so that TLC's counterexample is a shortest
\* behaviour that does.  Each holds only once the state is settled, so that a target ends where the replay compares.
SettledInFlightNeverStale == Settled => InFlightNeverStale
SettledNoStrandedWrite == Settled => NoStrandedWrite
\* A joiner held at the gate of its retry, with the retry's evaluation never taken.
JoinerNeverHeldForRetry == Settled => \A c \in Callers : pc[c] # "CRetryEval"
JoinerRetryNeverReturns == Settled => \A c \in Callers : ~(Returned(c) /\ joined[c] # 0 /\ ran[c])
KeptOkNeverHit == Settled => \A c \in Callers : ~(Returned(c) /\ ~ran[c] /\ joined[c] = 0 /\ got[c].res = "ok")
SoftNeverRemoved == Settled => \A c \in Callers : ~(Returned(c) /\ ran[c] /\ joined[c] = 0 /\ got[c].res = "soft")
OwnFailureNeverRemoved ==
    Settled => \A c \in Callers : ~(Returned(c) /\ ran[c] /\ joined[c] = 0 /\ got[c].res = "fail")
\* A caller that starts after an invalidation misses, while a lookup in flight across it has stranded its write.
StrandedNeverMissed ==
    Settled => \A c, d \in Callers :
                   ~(/\ map[ck[c]].st = "complete" /\ ck[c] < epoch /\ Returned(c)
                     /\ pc[d] = "CEval" /\ ck[d] = epoch)
\* A caller misses while a joiner of a failed lookup is held at its retry.
RetryNeverMissedBeside ==
    Settled => \A c, d \in Callers : ~(c # d /\ pc[c] = "CRetryEval" /\ pc[d] = "CEval" /\ ck[c] = ck[d])

\* TLC evaluates this on every step it generates; at the last step of a behaviour, Trace is the whole behaviour.  The
\* behaviour's number comes from TLC's own count, which is why generate.py runs one worker.
Export ==
    TLCGet("level") = Depth =>
        ndJsonSerialize(OutDir \o "/" \o ToString(TLCGet("stats").traces) \o ".ndjson", Trace)
=============================================================================
