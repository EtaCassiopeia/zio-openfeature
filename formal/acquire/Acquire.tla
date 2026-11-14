------------------------------- MODULE Acquire -------------------------------
(* fromAcquireAsync's hand-off of a provider from `acquire` to the switch, against a layer release that can arrive
   between any two steps.

   Up to MaxAttempts attempts and one release. A step of the construct fiber is one action: an interrupt is taken
   between actions, which is where ZIO takes it, or inside `initialize`, which is the one interruptible wait that
   matters here. `compose` and the switch are atomic: compose is synchronous and the switch is uninterruptible.

   With the design an attempt still waiting for its constructor can be abandoned, by its timeout or by the release's
   interrupt. Its constructor keeps running, and what it returns is the abandoned fiber's to shut down, before that
   fiber closes the attempt's scope: nothing else may close a scope its constructor may still be using. Each attempt
   that is not the one in progress keeps its own record in `ab`.

   Fix = FALSE is the code at 2.0.0. Fix = TRUE is the ownership design: the library holds what it has to shut down in
   one reference, set in the same step that creates the provider, and one finalizer registered before `build` releases
   it and closes the candidate scope, so it runs after the fiber's interrupt and the API's shutdown. *)
EXTENDS Naturals

CONSTANTS
    Fix,        \* the ownership design, or the code at 2.0.0
    UserShuts,  \* acquire's finalizer calls provider.shutdown(), the idiom the 2.0.0 scaladoc points at
    Owning,     \* the layer's release shuts the API down, so the serving stack goes with it
    MaxAttempts \* attempts the retry schedule allows

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
    stackShut,  \* the stack's shutdown() ran, which also stops the fresh fallback
    att,        \* Fix only: the attempt in progress
    realFrom,   \* the attempt that returned `real`
    ab          \* Fix only: each attempt not in progress, abandoned ("running": its constructor runs on) or "over"

(* The attempts' records, which the lifecycle of the one in progress leaves alone. *)
attempts == <<att, realFrom, ab>>

vars == <<pc, rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, att, realFrom, ab>>

Attempts == 1..MaxAttempts

NoAttempt == [st |-> "none", provider |-> FALSE, shuts |-> 0, scopeClosed |-> FALSE, shutAfterClose |-> FALSE]

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
    /\ att = 1
    /\ realFrom = 1
    /\ ab = [a \in Attempts |-> NoAttempt]

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
    /\ realFrom' = att
    /\ UNCHANGED <<rel, serving, shutdowns, closed, usedClosed, composed, stackShut, att, ab>>

(* verify rejects the candidate. At 2.0.0 only acquire's finalizer can reach it. *)
VerifyFails ==
    /\ pc = "attempt"
    /\ pc' = "failed"
    /\ real' = TRUE
    /\ owned' = "none"
    /\ closed' = TRUE
    /\ shutdowns' = (IF Fix THEN 1 ELSE 0) + (IF UserShuts /\ ~closed THEN 1 ELSE 0)
    /\ usedClosed' = usedClosed
    /\ realFrom' = att
    /\ UNCHANGED <<rel, serving, composed, stackShut, att, ab>>

Compose ==
    /\ pc = "accepted"
    /\ pc' = "composed"
    /\ owned' = IF Fix THEN "stack" ELSE "none"
    /\ composed' = TRUE
    /\ UNCHANGED <<rel, real, serving, shutdowns, closed, usedClosed, stackShut>>
    /\ UNCHANGED attempts

InitOk ==
    /\ pc = "composed"
    /\ pc' = "initialized"
    /\ UNCHANGED <<rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>
    /\ UNCHANGED attempts

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
    /\ UNCHANGED attempts

SwitchOk ==
    /\ pc = "initialized"
    /\ pc' = "live"
    /\ serving' = TRUE
    /\ owned' = "none"
    /\ UNCHANGED <<rel, real, shutdowns, closed, usedClosed, composed, stackShut>>
    /\ UNCHANGED attempts

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
    /\ UNCHANGED attempts

(* The design: the attempt's timeout fires while it waits for its constructor. The attempt is abandoned, and the next
   one starts while the constructor runs on, or construction fails. *)
Timeout ==
    /\ Fix /\ pc = "attempt"
    /\ ab' = [ab EXCEPT ![att].st = "running"]  \* its scope stays open for its constructor
    /\ IF att < MaxAttempts THEN att' = att + 1 /\ pc' = pc ELSE pc' = "failed" /\ att' = att
    /\ UNCHANGED <<rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, realFrom>>

(* The design: the constructor fails or is interrupted, and its attempt closes its scope. *)
AcquireFails ==
    /\ Fix /\ pc = "attempt"
    /\ ab' = [ab EXCEPT ![att] = [NoAttempt EXCEPT !.st = "over", !.scopeClosed = TRUE]]
    /\ IF att < MaxAttempts THEN att' = att + 1 /\ pc' = pc ELSE pc' = "failed" /\ att' = att
    /\ UNCHANGED <<rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, realFrom>>

Fiber ==
    \/ AttemptOk \/ VerifyFails \/ Compose \/ InitOk \/ InitFails \/ SwitchOk \/ SwitchRefused
    \/ Timeout \/ AcquireFails

(* The design: the constructor of an abandoned attempt returns. Its fiber shuts the provider down, then closes the
   attempt's scope; the provider is never verified, owned or served. *)
LateArrive(a) ==
    /\ ab[a].st = "running"
    /\ ab' = [ab EXCEPT ![a] = [st |-> "over", provider |-> TRUE, shuts |-> 1,
                                 scopeClosed |-> TRUE, shutAfterClose |-> FALSE]]
    /\ UNCHANGED <<pc, rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, att, realFrom>>

(* The design: the constructor of an abandoned attempt fails, or is interrupted once it suspends. *)
LateFail(a) ==
    /\ ab[a].st = "running"
    /\ ab' = [ab EXCEPT ![a] = [NoAttempt EXCEPT !.st = "over", !.scopeClosed = TRUE]]
    /\ UNCHANGED <<pc, rel, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, att, realFrom>>

Abandoned == \E a \in Attempts : LateArrive(a) \/ LateFail(a)

(* The interrupt, taken between two steps. At 2.0.0 nothing is attached there, so nothing runs. With the design the
   owned reference covers it, and an attempt still waiting for its constructor is abandoned. *)
InterruptBetween ==
    /\ pc \notin Stopped
    /\ pc' = "interrupted"
    /\ ab' = IF Fix /\ pc = "attempt" THEN [ab EXCEPT ![att].st = "running"] ELSE ab
    /\ UNCHANGED <<real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, att, realFrom>>

(* The interrupt, taken inside initialize. At 2.0.0 the step's onError runs discard. *)
InterruptInInit ==
    /\ pc = "composed"
    /\ pc' = "interrupted"
    /\ IF Fix
       THEN UNCHANGED <<real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut, ab>>
       ELSE /\ closed' = TRUE
            /\ shutdowns' = shutdowns + 1 + (IF ~closed /\ UserShuts THEN 1 ELSE 0)
            /\ usedClosed' = (usedClosed \/ closed)
            /\ stackShut' = TRUE
            /\ UNCHANGED <<real, serving, owned, composed, ab>>
    /\ UNCHANGED <<att, realFrom>>

(* The layer's release: finalizers in reverse order of registration. *)

(* 2.0.0: candidate.close, then the fiber's interrupt, then the API's shutdown. The fiber runs on between them. *)
CurrentRelease1 ==
    /\ ~Fix /\ rel = 0 /\ rel' = 1
    /\ CloseCandidate
    /\ UNCHANGED <<pc, real, serving, owned, composed, stackShut>>
    /\ UNCHANGED attempts

CurrentRelease2 ==
    /\ ~Fix /\ rel = 1 /\ rel' = 2
    /\ (InterruptBetween \/ InterruptInInit \/ (pc \in Stopped /\ UNCHANGED <<pc, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>
                                                          /\ UNCHANGED attempts))

ApiShutdown(next) ==
    /\ rel' = next
    /\ IF Owning /\ serving
       THEN LibShut(1) /\ stackShut' = TRUE
       ELSE UNCHANGED <<shutdowns, usedClosed, stackShut>>
    /\ UNCHANGED <<pc, real, serving, owned, closed, composed>>
    /\ UNCHANGED attempts

CurrentRelease3 == ~Fix /\ rel = 2 /\ ApiShutdown(3)

(* The design: the fiber's interrupt, then the API's shutdown, then what the library still owns and the candidate. The
   candidate is the accepted attempt's scope; an abandoned attempt's is its own fiber's to close. *)
FixRelease1 ==
    /\ Fix /\ rel = 0 /\ rel' = 1
    /\ (InterruptBetween \/ InterruptInInit \/ (pc \in Stopped /\ UNCHANGED <<pc, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>
                                                          /\ UNCHANGED attempts))

FixRelease2 == Fix /\ rel = 1 /\ ApiShutdown(2)

FixRelease3 ==
    /\ Fix /\ rel = 2 /\ rel' = 3
    /\ owned' = "none"
    /\ closed' = (closed \/ real)
    /\ shutdowns' = shutdowns + (IF owned /= "none" THEN 1 ELSE 0) + (IF ~closed /\ UserShuts /\ real THEN 1 ELSE 0)
    /\ stackShut' = (stackShut \/ owned = "stack")
    /\ ab' = ab  \* an abandoned attempt's scope is its own fiber's to close
    /\ UNCHANGED <<pc, real, serving, usedClosed, composed, att, realFrom>>

Release == CurrentRelease1 \/ CurrentRelease2 \/ CurrentRelease3 \/ FixRelease1 \/ FixRelease2 \/ FixRelease3

Done == rel = 3 /\ pc \in Stopped /\ UNCHANGED vars

Next == Fiber \/ Abandoned \/ Release \/ Done

Spec == Init /\ [][Next]_vars

(* Invariants *)

Finished == rel = 3 /\ pc \in Stopped

(* A stack still bound to a shared API keeps serving past this layer's release: that API's owner shuts it down. *)
StillBound == serving /\ ~Owning

(* The release is over and no abandoned constructor still runs. *)
Quiescent == Finished /\ \A a \in Attempts : ab[a].st /= "running"

(* Every provider acquire returned is shut down: the attempt in progress at the end of a release, an abandoned one once
   its constructor has returned. *)
NoLeak ==
    /\ (Finished /\ real /\ ~StillBound) => shutdowns >= 1
    /\ Quiescent => \A a \in Attempts : ab[a].provider => ab[a].shuts >= 1

(* The stack, and so the fresh fallback in it, is shut down too. *)
NoStackLeak == (Finished /\ composed /\ ~StillBound) => stackShut

(* No provider is shut down twice. *)
ShutDownOnce == shutdowns <= 1 /\ \A a \in Attempts : ab[a].shuts <= 1

(* A provider is shut down before the resources it uses go away. *)
ShutDownBeforeResources == ~usedClosed /\ \A a \in Attempts : ~ab[a].shutAfterClose

(* acquire's finalizers run for every attempt whose constructor has returned. *)
FinalizersRun ==
    /\ (Finished /\ real) => closed
    /\ Quiescent => \A a \in Attempts : ab[a].st = "over" => ab[a].scopeClosed

(* No scope is closed while its constructor may still be using it. *)
NoCloseUnderConstructor == \A a \in Attempts : ab[a].st = "running" => ~ab[a].scopeClosed

(* A provider an abandoned attempt returns is never the library's to hand on, and so never served: serving needs it. *)
AbandonedNeverOwned == real => ab[realFrom].st = "none"
==============================================================================
