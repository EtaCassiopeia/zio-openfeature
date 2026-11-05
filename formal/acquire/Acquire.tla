------------------------------- MODULE Acquire -------------------------------
(* fromAcquireAsync's hand-off of a provider from `acquire` to the switch, against a layer release that can arrive
   between any two steps.

   One attempt and one release. A step of the construct fiber is one action: an interrupt is taken between actions,
   which is where ZIO takes it, or inside `initialize`, which is the one interruptible wait that matters here.
   `compose` and the switch are atomic: compose is synchronous and the switch is uninterruptible.

   Fix = FALSE is the code at 2.0.0. Fix = TRUE is the ownership design: the library holds what it has to shut down in
   one reference, set in the same step that creates the provider, and one finalizer registered before `build` releases
   it and closes the candidate scope, so it runs after the fiber's interrupt and the API's shutdown. *)
EXTENDS Naturals

CONSTANTS
    Fix,        \* the ownership design, or the code at 2.0.0
    UserShuts,  \* acquire's finalizer calls provider.shutdown(), the idiom the 2.0.0 scaladoc points at
    Owning      \* the layer's release shuts the API down, so the serving stack goes with it

VARIABLES
    pc,         \* the construct fiber
    rel,        \* how far the layer's release has got; 0 = not started
    real,       \* acquire returned a provider
    serving,    \* the switch routes to the composed stack
    owned,      \* Fix only: what the library must shut down, "none", "real" or "stack"
    shutdowns,  \* calls that reached the real provider's shutdown()
    closed,     \* the candidate scope has been closed: acquire's other resources are gone
    usedClosed, \* a shutdown() reached the provider after its resources were closed
    composed,   \* compose ran, so a stack holding the fresh fallback exists
    stackShut   \* the stack's shutdown() ran, which also stops the fresh fallback

vars == <<pc, rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>

Stopped == {"failed", "live", "interrupted"}

Init ==
    /\ pc = "attempt"
    /\ rel = 0
    /\ real = FALSE
    /\ serving = FALSE
    /\ owned = "none"
    /\ shutdowns = 0
    /\ closed = FALSE
    /\ usedClosed = FALSE
    /\ composed = FALSE
    /\ stackShut = FALSE

(* The library calling the real provider's shutdown(), `n` times. *)
LibShut(n) ==
    /\ shutdowns' = shutdowns + n
    /\ usedClosed' = (usedClosed \/ (closed /\ n > 0))

(* Closing the candidate scope: acquire's finalizers run, once. *)
CloseCandidate ==
    /\ closed' = TRUE
    /\ shutdowns' = shutdowns + (IF ~closed /\ UserShuts /\ real THEN 1 ELSE 0)
    /\ UNCHANGED usedClosed

(* The fiber's steps *)

AttemptOk ==
    /\ pc = "attempt"
    /\ pc' = "accepted"
    /\ real' = TRUE
    /\ owned' = IF Fix THEN "real" ELSE "none"
    /\ UNCHANGED <<rel, serving, shutdowns, closed, usedClosed, composed, stackShut>>

(* verify rejects the candidate. At 2.0.0 only acquire's finalizer can reach it. *)
VerifyFails ==
    /\ pc = "attempt"
    /\ pc' = "failed"
    /\ real' = TRUE
    /\ owned' = "none"
    /\ closed' = TRUE
    /\ shutdowns' = (IF Fix THEN 1 ELSE 0) + (IF UserShuts /\ ~closed THEN 1 ELSE 0)
    /\ usedClosed' = usedClosed
    /\ UNCHANGED <<rel, serving, composed, stackShut>>

Compose ==
    /\ pc = "accepted"
    /\ pc' = "composed"
    /\ owned' = IF Fix THEN "stack" ELSE "none"
    /\ composed' = TRUE
    /\ UNCHANGED <<rel, real, serving, shutdowns, closed, usedClosed, stackShut>>

InitOk ==
    /\ pc = "composed"
    /\ pc' = "initialized"
    /\ UNCHANGED <<rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>

(* A chain whose initialize() fails has already shut itself down: the SDK's MultiProvider does that. discard then shuts
   it down again, unless the chain absorbs the second call. *)
InitFails ==
    /\ pc = "composed"
    /\ pc' = "failed"
    /\ owned' = "none"
    /\ closed' = TRUE
    /\ shutdowns' = shutdowns + (IF Fix THEN 1 ELSE 2) + (IF ~closed /\ UserShuts THEN 1 ELSE 0)
    /\ usedClosed' = (usedClosed \/ closed)
    /\ stackShut' = TRUE
    /\ UNCHANGED <<rel, real, serving, composed>>

SwitchOk ==
    /\ pc = "initialized"
    /\ pc' = "live"
    /\ serving' = TRUE
    /\ owned' = "none"
    /\ UNCHANGED <<rel, real, shutdowns, closed, usedClosed, composed, stackShut>>

(* A setProvider won, or the instance shut down: the switch refuses and the caller discards. *)
SwitchRefused ==
    /\ pc = "initialized"
    /\ pc' = "failed"
    /\ owned' = "none"
    /\ closed' = TRUE
    /\ shutdowns' = shutdowns + 1 + (IF ~closed /\ UserShuts THEN 1 ELSE 0)
    /\ usedClosed' = (usedClosed \/ closed)
    /\ stackShut' = TRUE
    /\ UNCHANGED <<rel, real, serving, composed>>

Fiber == AttemptOk \/ VerifyFails \/ Compose \/ InitOk \/ InitFails \/ SwitchOk \/ SwitchRefused

(* The interrupt, taken between two steps. At 2.0.0 nothing is attached there, so nothing runs. With the design the
   owned reference covers it. *)
InterruptBetween ==
    /\ pc \notin Stopped
    /\ pc' = "interrupted"
    /\ UNCHANGED <<real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>

(* The interrupt, taken inside initialize. At 2.0.0 the step's onError runs discard. *)
InterruptInInit ==
    /\ pc = "composed"
    /\ pc' = "interrupted"
    /\ IF Fix
       THEN UNCHANGED <<real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>
       ELSE /\ closed' = TRUE
            /\ shutdowns' = shutdowns + 1 + (IF ~closed /\ UserShuts THEN 1 ELSE 0)
            /\ usedClosed' = (usedClosed \/ closed)
            /\ stackShut' = TRUE
            /\ UNCHANGED <<real, serving, owned, composed>>

(* The layer's release: finalizers in reverse order of registration. *)

(* 2.0.0: candidate.close, then the fiber's interrupt, then the API's shutdown. The fiber runs on between them. *)
CurrentRelease1 ==
    /\ ~Fix /\ rel = 0 /\ rel' = 1
    /\ CloseCandidate
    /\ UNCHANGED <<pc, real, serving, owned, composed, stackShut>>

CurrentRelease2 ==
    /\ ~Fix /\ rel = 1 /\ rel' = 2
    /\ (InterruptBetween \/ InterruptInInit \/ (pc \in Stopped /\ UNCHANGED <<pc, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>))

ApiShutdown(next) ==
    /\ rel' = next
    /\ IF Owning /\ serving
       THEN LibShut(1) /\ stackShut' = TRUE
       ELSE UNCHANGED <<shutdowns, usedClosed, stackShut>>
    /\ UNCHANGED <<pc, real, serving, owned, closed, composed>>

CurrentRelease3 == ~Fix /\ rel = 2 /\ ApiShutdown(3)

(* The design: the fiber's interrupt, then the API's shutdown, then what the library still owns and the candidate. *)
FixRelease1 ==
    /\ Fix /\ rel = 0 /\ rel' = 1
    /\ (InterruptBetween \/ InterruptInInit \/ (pc \in Stopped /\ UNCHANGED <<pc, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>))

FixRelease2 == Fix /\ rel = 1 /\ ApiShutdown(2)

FixRelease3 ==
    /\ Fix /\ rel = 2 /\ rel' = 3
    /\ owned' = "none"
    /\ closed' = TRUE
    /\ shutdowns' = shutdowns + (IF owned /= "none" THEN 1 ELSE 0) + (IF ~closed /\ UserShuts /\ real THEN 1 ELSE 0)
    /\ stackShut' = (stackShut \/ owned = "stack")
    /\ UNCHANGED <<pc, real, serving, usedClosed, composed>>

Release == CurrentRelease1 \/ CurrentRelease2 \/ CurrentRelease3 \/ FixRelease1 \/ FixRelease2 \/ FixRelease3

Done == rel = 3 /\ pc \in Stopped /\ UNCHANGED vars

Next == Fiber \/ Release \/ Done

Spec == Init /\ [][Next]_vars

(* Invariants *)

Finished == rel = 3 /\ pc \in Stopped

(* A stack still bound to a shared API keeps serving past this layer's release: that API's owner shuts it down. *)
StillBound == serving /\ ~Owning

(* Every provider acquire returned is shut down, at the end of a release. *)
NoLeak == (Finished /\ real /\ ~StillBound) => shutdowns >= 1

(* The stack, and so the fresh fallback in it, is shut down too. *)
NoStackLeak == (Finished /\ composed /\ ~StillBound) => stackShut

(* No provider is shut down twice. *)
ShutDownOnce == shutdowns <= 1

(* A provider is shut down before the resources it uses go away. *)
ShutDownBeforeResources == ~usedClosed

(* acquire's finalizers run, whatever happened. *)
FinalizersRun == Finished => closed
==============================================================================
