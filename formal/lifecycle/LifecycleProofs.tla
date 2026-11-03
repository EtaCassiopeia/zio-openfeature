-------------------------- MODULE LifecycleProofs --------------------------
(***************************************************************************)
(* Proofs, for any number of providers, swaps, events and evaluations, of  *)
(* the lifecycle model's structural invariants: every variable's type, one *)
(* lock holder, and the gate down whenever no swap holds the lock.         *)
(* Checked with TLAPS: tlapm LifecycleProofs.tla.                          *)
(***************************************************************************)
EXTENDS Lifecycle, FiniteSetTheorems, TLAPS

-----------------------------------------------------------------------------
(* Every variable keeps its type. *)

\* Freezing the entries that watch a provider keeps them entries, and finitely many.
LEMMA FreezeType ==
  ASSUME TypeInv, NEW p \in All
  PROVE  Freeze(orphans, p) \subseteq OrphanRec /\ IsFiniteSet(Freeze(orphans, p))
<1> DEFINE f == [o \in orphans |-> IF o.watch = p /\ o.frozen = "LIVE" THEN [o EXCEPT !.frozen = sdk[p], !.ran = ran[p]]
                                                                         ELSE o]
<1>1. Freeze(orphans, p) = {f[o] : o \in orphans}
  BY DEF Freeze
<1>2. IsFiniteSet({f[o] : o \in orphans})
  BY FS_Image DEF TypeInv
<1>3. Freeze(orphans, p) \subseteq OrphanRec
  BY DEF Freeze, TypeInv, OrphanRec, SdkStates, All
<1> QED
  BY <1>1, <1>2, <1>3

LEMMA InitType == Init => TypeInv
  BY ConstantsOK, FS_EmptySet DEF Init, InitWith, InitAsync, TypeInv, SwapRec, OrphanRec, EventRec, EvalRec, EvalIdle,
                                 All, Statuses, SdkStates, Signals

LEMMA NextType == TypeInv /\ [Next]_vars => TypeInv'
<1> SUFFICES ASSUME TypeInv, [Next]_vars PROVE TypeInv'
  OBVIOUS
<1> USE ConstantsOK DEF TypeInv, SwapRec, OrphanRec, EventRec, EvalRec, All, Signals, SdkStates, Statuses
<1> SUFFICES ASSUME LifecycleNext, UNCHANGED evals PROVE TypeInv'
  <2>1. CASE EvalNext /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                                      owns, swaps, sdkVars >>
    BY <2>1 DEF EvalNext, EvalRead, EvalCheck, EvalCall, EvalReturn, Gen, sdkVars
  <2>2. CASE UNCHANGED vars
    BY <2>2 DEF vars
  <2> QED
    BY <2>1, <2>2 DEF Next
<1>1. CASE \E p \in All : SwapStart(p)
  BY <1>1 DEF SwapStart, sdkVars
<1>2. CASE SdkBind
  <2>1. Freeze(orphans, swap.new) \subseteq OrphanRec /\ IsFiniteSet(Freeze(orphans, swap.new))
    BY <1>2, FreezeType DEF SdkBind
  <2> QED
    BY <1>2, <2>1 DEF SdkBind
<1>3. CASE SdkBindFail
  BY <1>3 DEF SdkBindFail, sdkVars
<1>4. CASE SwapRollbackUnbound
  BY <1>4 DEF SwapRollbackUnbound, Outcome, sdkVars
<1>5. CASE \E p \in All : SdkInitOk(p) \/ SdkInitFail(p)
  BY <1>5 DEF SdkInitOk, SdkInitFail
<1>6. CASE SwapDone
  BY <1>6 DEF SwapDone, Outcome, StatusOf, sdkVars
<1>7. CASE SwapTimeout
  BY <1>7, FS_AddElement DEF SwapTimeout, Outcome, Watch, sdkVars
<1>8. CASE SwapFailNoRollback
  BY <1>8, FS_AddElement DEF SwapFailNoRollback, Outcome, Watch, sdkVars
<1>9. CASE \E ok, early \in BOOLEAN, r \in {"READY", "STALE", "ERROR", "FATAL"} : SwapRollback(ok, r, early)
  <2>1. Freeze(orphans, swap.prev) \subseteq OrphanRec /\ IsFiniteSet(Freeze(orphans, swap.prev))
    BY <1>9, FreezeType DEF SwapRollback
  <2> QED
    BY <1>9, <2>1 DEF SwapRollback, Outcome, StatusOf
<1>10. CASE SwapReconcile
  BY <1>10 DEF SwapReconcile, Apply, sdkVars
<1>11. CASE CallerInterrupt
  BY <1>11 DEF CallerInterrupt, Interruptible, sdkVars
<1>12. CASE \E p \in All, e \in {"READY", "STALE", "ERROR", "FATAL"} : Emit(p, e)
  BY <1>12 DEF Emit
<1>13. CASE \E p \in All : EmitStaleWhileStarting(p)
  BY <1>13 DEF EmitStaleWhileStarting
<1>14. CASE \E p \in All : Die(p)
  BY <1>14 DEF Die
<1>15. CASE \E ev \in events : Deliver(ev)
  BY <1>15 DEF Deliver, DeliverToBridge, Discard, Apply
<1>16. CASE \E o \in orphans : OrphanResolved(o)
  BY <1>16, FS_RemoveElement DEF OrphanResolved, Released, View, RanView
<1>17. CASE ShutdownStart
  BY <1>17 DEF ShutdownStart, sdkVars
<1>18. CASE ShutdownDone
  BY <1>18, FS_EmptySet, FS_Subset, FS_CardinalityType DEF ShutdownDone, TearDown
<1>19. CASE ScopeClose
  BY <1>19, FS_EmptySet, FS_Subset, FS_CardinalityType DEF ScopeClose, TearDown
<1>20. CASE \E p \in All : SdkShutDownOld(p)
  BY <1>20 DEF SdkShutDownOld
<1> QED
  BY <1>1, <1>2, <1>3, <1>4, <1>5, <1>6, <1>7, <1>8, <1>9, <1>10, <1>11, <1>12, <1>13, <1>14, <1>15, <1>16, <1>17,
     <1>18, <1>19, <1>20 DEF LifecycleNext

THEOREM TypeCorrect == Spec => []TypeInv
  BY InitType, NextType, PTL DEF Spec

-----------------------------------------------------------------------------
(* One lock holder, and the gate down whenever no swap holds the lock. *)

IndInv == TypeInv /\ OneLockHolder /\ GateDownWhenIdle

LEMMA InitInd == Init => IndInv
  BY InitType, ConstantsOK DEF Init, InitWith, InitAsync, IndInv, OneLockHolder, GateDownWhenIdle

LEMMA NextInd == IndInv /\ [Next]_vars => IndInv'
<1> SUFFICES ASSUME IndInv, [Next]_vars PROVE IndInv'
  OBVIOUS
<1>0. TypeInv'
  BY NextType DEF IndInv
<1>t. /\ swap \in {None} \cup SwapRec /\ installed \in All /\ status \in Statuses
      /\ OneLockHolder /\ GateDownWhenIdle
  BY DEF IndInv, TypeInv
<1> SUFFICES OneLockHolder' /\ GateDownWhenIdle'
  BY <1>0, <1>t, ConstantsOK DEF IndInv, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1> SUFFICES ASSUME LifecycleNext, UNCHANGED evals PROVE OneLockHolder' /\ GateDownWhenIdle'
  <2>1. CASE EvalNext /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                                      owns, swaps, sdkVars >>
    BY <2>1, <1>t DEF OneLockHolder, GateDownWhenIdle
  <2>2. CASE UNCHANGED vars
    BY <2>2, <1>t DEF vars, OneLockHolder, GateDownWhenIdle
  <2> QED
    BY <2>1, <2>2 DEF Next
<1>1. CASE \E p \in All : SwapStart(p)
  BY <1>1, <1>t, ConstantsOK DEF SwapStart, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>2. CASE SdkBind
  BY <1>2, <1>t, ConstantsOK DEF SdkBind, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>3. CASE SdkBindFail
  BY <1>3, <1>t, ConstantsOK DEF SdkBindFail, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>4. CASE SwapRollbackUnbound
  BY <1>4, <1>t, ConstantsOK DEF SwapRollbackUnbound, Outcome,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>5. CASE \E p \in All : SdkInitOk(p) \/ SdkInitFail(p)
  BY <1>5, <1>t, ConstantsOK DEF SdkInitOk, SdkInitFail,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>6. CASE SwapDone
  BY <1>6, <1>t, ConstantsOK DEF SwapDone, Outcome, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>7. CASE SwapTimeout
  BY <1>7, <1>t, ConstantsOK DEF SwapTimeout, Outcome,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>8. CASE SwapFailNoRollback
  BY <1>8, <1>t, ConstantsOK DEF SwapFailNoRollback, Outcome,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>9. CASE \E ok, early \in BOOLEAN, r \in {"READY", "STALE", "ERROR", "FATAL"} : SwapRollback(ok, r, early)
  BY <1>9, <1>t, ConstantsOK DEF SwapRollback, Outcome,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>10. CASE SwapReconcile
  BY <1>10, <1>t, ConstantsOK DEF SwapReconcile, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>11. CASE CallerInterrupt
  BY <1>11, <1>t, ConstantsOK DEF CallerInterrupt, Interruptible,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>12. CASE \E p \in All, e \in {"READY", "STALE", "ERROR", "FATAL"} : Emit(p, e)
  BY <1>12, <1>t, ConstantsOK DEF Emit, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>13. CASE \E p \in All : EmitStaleWhileStarting(p)
  BY <1>13, <1>t, ConstantsOK DEF EmitStaleWhileStarting,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>14. CASE \E p \in All : Die(p)
  BY <1>14, <1>t, ConstantsOK DEF Die, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>15. CASE \E ev \in events : Deliver(ev)
  BY <1>15, <1>t, ConstantsOK DEF Deliver, DeliverToBridge, Discard,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>16. CASE \E o \in orphans : OrphanResolved(o)
  BY <1>16, <1>t, ConstantsOK DEF OrphanResolved, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>17. CASE ShutdownStart
  BY <1>17, <1>t, ConstantsOK DEF ShutdownStart, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>18. CASE ShutdownDone
  BY <1>18, <1>t, ConstantsOK DEF ShutdownDone, TearDown,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>19. CASE ScopeClose
  BY <1>19, <1>t, ConstantsOK DEF ScopeClose, TearDown,
       OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1>20. CASE \E p \in All : SdkShutDownOld(p)
  BY <1>20, <1>t, ConstantsOK DEF SdkShutDownOld, OneLockHolder, GateDownWhenIdle, SwapRec, All, Statuses, SdkStates
<1> QED
  BY <1>1, <1>2, <1>3, <1>4, <1>5, <1>6, <1>7, <1>8, <1>9, <1>10, <1>11, <1>12, <1>13, <1>14, <1>15, <1>16, <1>17,
     <1>18, <1>19, <1>20 DEF LifecycleNext

THEOREM LockAndGate == Spec => [](OneLockHolder /\ GateDownWhenIdle)
<1>1. Spec => []IndInv
  BY InitInd, NextInd, PTL DEF Spec
<1> QED
  BY <1>1, PTL DEF IndInv
=============================================================================
