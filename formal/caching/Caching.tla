------------------------------- MODULE Caching -------------------------------
(***************************************************************************)
(* CachingProvider's cache (extras/.../CachingProvider.scala) over          *)
(* zio-cache 0.2.8, for one flag key and one context.                      *)
(*                                                                         *)
(* The delegate's value is a version, pv.  A configuration change bumps it *)
(* and queues an event; handling the event bumps the epoch and clears the  *)
(* map.  An evaluation reads the epoch, then asks the map under that       *)
(* epoch: a hit returns the entry, a miss installs a pending entry and     *)
(* evaluates the delegate, and a pending entry is joined.  The write-back  *)
(* is unconditional.  A failed or DEFAULT result is removed by whichever   *)
(* caller saw it.  A caller that saw a failure it did not produce retries  *)
(* once: it calls the delegate directly, past the map.                     *)
(***************************************************************************)
EXTENDS Naturals, TLC

CONSTANTS
    MaxCallers,  \* how many evaluations run
    MaxChanges   \* how many configuration changes the delegate makes

Callers == 1..MaxCallers
Epochs == 0..MaxChanges
Key(e) == e                         \* CacheKey's identity: the epoch is part of it
Max(a, b) == IF a >= b THEN a ELSE b

\* A delegate result: "ok", "soft" (an error code or the DEFAULT reason, returned but not kept) or "fail" (it threw).
NoExit == [res |-> "none", pv |-> 0]
Absent == [st |-> "absent"]
Pending(c) == [st |-> "pending", owner |-> c]
Complete(c, x) == [st |-> "complete", owner |-> c, exit |-> x]

(* --algorithm Caching
variables
    pv = 0,                                \* the delegate's version
    epoch = 0,                             \* CachingProvider.generation
    map = [k \in Epochs |-> Absent],       \* zio-cache's map, by key
    promise = [c \in Callers |-> NoExit],  \* the outcome of the lookup c runs
    invalidatedAt = 0;                     \* ghost: the version the last completed invalidation covers

\* The delegate changes its configuration, then emits: each change's event is handled once.
fair process Delegate = 0
begin
  Change:
    while pv < MaxChanges do
      pv := pv + 1;
    end while;
end process;

\* onDelegateEvent's invalidateAllNow.  The delegate's pool runs the handlers in any order, and they may overlap.
fair process Invalidator \in {MaxCallers + k : k \in 1..MaxChanges}
variable covers = 0;
begin
  IQueued:
    await pv >= self - MaxCallers;
  IBump:
    covers := pv;
    epoch := epoch + 1;
  IClear:
    map := [k \in Epochs |-> Absent];
    invalidatedAt := Max(invalidatedAt, covers);
end process;

fair process Caller \in Callers
variables
    ck = 0,          \* the key this evaluation built
    floor = 0,       \* ghost: invalidatedAt when it started
    ran = FALSE,     \* the AtomicBoolean its thunk flips
    joined = 0,      \* the owner of the lookup it awaits
    got = NoExit;    \* the outcome it holds
begin
  CStart:
    ck := Key(epoch);
    floor := invalidatedAt;
  CGet:
    if map[ck].st = "absent" then
      map[ck] := Pending(self);
    elsif map[ck].st = "pending" then
      joined := map[ck].owner;
      goto CAwait;
    else
      got := map[ck].exit;
      goto CTail;
    end if;
  CEval:
    ran := TRUE;
    with r \in {"ok", "soft", "fail"} do
      got := [res |-> r, pv |-> pv];
    end with;
  CWrite:
    map[ck] := Complete(self, got);
    promise[self] := got;
    goto CTail;
  CAwait:
    await promise[joined] # NoExit;
    got := promise[joined];
  CTail:
    if got.res # "ok" then
      map[ck] := Absent;
    end if;
    if got.res # "fail" \/ ran then
      goto Done;
    end if;
  CRetry:
    map[ck] := Absent;
  CRetryEval:
    ran := TRUE;
    with r \in {"ok", "soft", "fail"} do
      got := [res |-> r, pv |-> pv];
    end with;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
VARIABLES pv, epoch, map, promise, invalidatedAt, pc, covers, ck, floor, ran, 
          joined, got

vars == << pv, epoch, map, promise, invalidatedAt, pc, covers, ck, floor, ran, 
           joined, got >>

ProcSet == {0} \cup ({MaxCallers + k : k \in 1..MaxChanges}) \cup (Callers)

Init == (* Global variables *)
        /\ pv = 0
        /\ epoch = 0
        /\ map = [k \in Epochs |-> Absent]
        /\ promise = [c \in Callers |-> NoExit]
        /\ invalidatedAt = 0
        (* Process Invalidator *)
        /\ covers = [self \in {MaxCallers + k : k \in 1..MaxChanges} |-> 0]
        (* Process Caller *)
        /\ ck = [self \in Callers |-> 0]
        /\ floor = [self \in Callers |-> 0]
        /\ ran = [self \in Callers |-> FALSE]
        /\ joined = [self \in Callers |-> 0]
        /\ got = [self \in Callers |-> NoExit]
        /\ pc = [self \in ProcSet |-> CASE self = 0 -> "Change"
                                        [] self \in {MaxCallers + k : k \in 1..MaxChanges} -> "IQueued"
                                        [] self \in Callers -> "CStart"]

Change == /\ pc[0] = "Change"
          /\ IF pv < MaxChanges
                THEN /\ pv' = pv + 1
                     /\ pc' = [pc EXCEPT ![0] = "Change"]
                ELSE /\ pc' = [pc EXCEPT ![0] = "Done"]
                     /\ pv' = pv
          /\ UNCHANGED << epoch, map, promise, invalidatedAt, covers, ck, 
                          floor, ran, joined, got >>

Delegate == Change

IQueued(self) == /\ pc[self] = "IQueued"
                 /\ pv >= self - MaxCallers
                 /\ pc' = [pc EXCEPT ![self] = "IBump"]
                 /\ UNCHANGED << pv, epoch, map, promise, invalidatedAt, 
                                 covers, ck, floor, ran, joined, got >>

IBump(self) == /\ pc[self] = "IBump"
               /\ covers' = [covers EXCEPT ![self] = pv]
               /\ epoch' = epoch + 1
               /\ pc' = [pc EXCEPT ![self] = "IClear"]
               /\ UNCHANGED << pv, map, promise, invalidatedAt, ck, floor, ran, 
                               joined, got >>

IClear(self) == /\ pc[self] = "IClear"
                /\ map' = [k \in Epochs |-> Absent]
                /\ invalidatedAt' = Max(invalidatedAt, covers[self])
                /\ pc' = [pc EXCEPT ![self] = "Done"]
                /\ UNCHANGED << pv, epoch, promise, covers, ck, floor, ran, 
                                joined, got >>

Invalidator(self) == IQueued(self) \/ IBump(self) \/ IClear(self)

CStart(self) == /\ pc[self] = "CStart"
                /\ ck' = [ck EXCEPT ![self] = Key(epoch)]
                /\ floor' = [floor EXCEPT ![self] = invalidatedAt]
                /\ pc' = [pc EXCEPT ![self] = "CGet"]
                /\ UNCHANGED << pv, epoch, map, promise, invalidatedAt, covers, 
                                ran, joined, got >>

CGet(self) == /\ pc[self] = "CGet"
              /\ IF map[ck[self]].st = "absent"
                    THEN /\ map' = [map EXCEPT ![ck[self]] = Pending(self)]
                         /\ pc' = [pc EXCEPT ![self] = "CEval"]
                         /\ UNCHANGED << joined, got >>
                    ELSE /\ IF map[ck[self]].st = "pending"
                               THEN /\ joined' = [joined EXCEPT ![self] = map[ck[self]].owner]
                                    /\ pc' = [pc EXCEPT ![self] = "CAwait"]
                                    /\ got' = got
                               ELSE /\ got' = [got EXCEPT ![self] = map[ck[self]].exit]
                                    /\ pc' = [pc EXCEPT ![self] = "CTail"]
                                    /\ UNCHANGED joined
                         /\ map' = map
              /\ UNCHANGED << pv, epoch, promise, invalidatedAt, covers, ck, 
                              floor, ran >>

CEval(self) == /\ pc[self] = "CEval"
               /\ ran' = [ran EXCEPT ![self] = TRUE]
               /\ \E r \in {"ok", "soft", "fail"}:
                    got' = [got EXCEPT ![self] = [res |-> r, pv |-> pv]]
               /\ pc' = [pc EXCEPT ![self] = "CWrite"]
               /\ UNCHANGED << pv, epoch, map, promise, invalidatedAt, covers, 
                               ck, floor, joined >>

CWrite(self) == /\ pc[self] = "CWrite"
                /\ map' = [map EXCEPT ![ck[self]] = Complete(self, got[self])]
                /\ promise' = [promise EXCEPT ![self] = got[self]]
                /\ pc' = [pc EXCEPT ![self] = "CTail"]
                /\ UNCHANGED << pv, epoch, invalidatedAt, covers, ck, floor, 
                                ran, joined, got >>

CAwait(self) == /\ pc[self] = "CAwait"
                /\ promise[joined[self]] # NoExit
                /\ got' = [got EXCEPT ![self] = promise[joined[self]]]
                /\ pc' = [pc EXCEPT ![self] = "CTail"]
                /\ UNCHANGED << pv, epoch, map, promise, invalidatedAt, covers, 
                                ck, floor, ran, joined >>

CTail(self) == /\ pc[self] = "CTail"
               /\ IF got[self].res # "ok"
                     THEN /\ map' = [map EXCEPT ![ck[self]] = Absent]
                     ELSE /\ TRUE
                          /\ map' = map
               /\ IF got[self].res # "fail" \/ ran[self]
                     THEN /\ pc' = [pc EXCEPT ![self] = "Done"]
                     ELSE /\ pc' = [pc EXCEPT ![self] = "CRetry"]
               /\ UNCHANGED << pv, epoch, promise, invalidatedAt, covers, ck, 
                               floor, ran, joined, got >>

CRetry(self) == /\ pc[self] = "CRetry"
                /\ map' = [map EXCEPT ![ck[self]] = Absent]
                /\ pc' = [pc EXCEPT ![self] = "CRetryEval"]
                /\ UNCHANGED << pv, epoch, promise, invalidatedAt, covers, ck, 
                                floor, ran, joined, got >>

CRetryEval(self) == /\ pc[self] = "CRetryEval"
                    /\ ran' = [ran EXCEPT ![self] = TRUE]
                    /\ \E r \in {"ok", "soft", "fail"}:
                         got' = [got EXCEPT ![self] = [res |-> r, pv |-> pv]]
                    /\ pc' = [pc EXCEPT ![self] = "Done"]
                    /\ UNCHANGED << pv, epoch, map, promise, invalidatedAt, 
                                    covers, ck, floor, joined >>

Caller(self) == CStart(self) \/ CGet(self) \/ CEval(self) \/ CWrite(self)
                   \/ CAwait(self) \/ CTail(self) \/ CRetry(self)
                   \/ CRetryEval(self)

(* Allow infinite stuttering to prevent deadlock on termination. *)
Terminating == /\ \A self \in ProcSet: pc[self] = "Done"
               /\ UNCHANGED vars

Next == Delegate
           \/ (\E self \in {MaxCallers + k : k \in 1..MaxChanges}: Invalidator(self))
           \/ (\E self \in Callers: Caller(self))
           \/ Terminating

Spec == /\ Init /\ [][Next]_vars
        /\ WF_vars(Delegate)
        /\ \A self \in {MaxCallers + k : k \in 1..MaxChanges} : WF_vars(Invalidator(self))
        /\ \A self \in Callers : WF_vars(Caller(self))

Termination == <>(\A self \in ProcSet: pc[self] = "Done")

\* END TRANSLATION

Returned(c) == pc[c] = "Done"

\* A caller that started once an invalidation had completed never returns a value older than it covers.  A caller already
\* in flight may: its floor is lower, and InFlightNeverStale, the stronger claim, is violated on purpose (witness.py).
NoStaleAfterInvalidate ==
    \A c \in Callers : Returned(c) /\ got[c].res \in {"ok", "soft"} => got[c].pv >= floor[c]

\* A failed or DEFAULT result is in the map only between its lookup's write-back and that same caller's invalidation.
NoCachedFailure ==
    \A k \in Epochs : map[k].st = "complete" /\ map[k].exit.res # "ok" => pc[map[k].owner] = "CTail"

\* A caller that returns a failure ran the delegate itself: it was not handed another lookup's.
JoinersGetARun ==
    \A c \in Callers : Returned(c) /\ got[c].res = "fail" => ran[c]

\* Witnesses (witness.py): each is violated on purpose.
InFlightNeverStale ==
    \A c \in Callers : Returned(c) /\ got[c].res \in {"ok", "soft"} => got[c].pv >= invalidatedAt
MapNeverHoldsBad ==
    \A k \in Epochs : map[k].st = "complete" => map[k].exit.res = "ok"
NoStrandedWrite ==
    \A k \in Epochs : map[k].st = "complete" /\ k < epoch => pc[map[k].owner] = "CTail"
NoJoinerRetries ==
    \A c \in Callers : pc[c] # "CRetry"
=============================================================================
