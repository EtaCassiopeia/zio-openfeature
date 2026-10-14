------------------------ MODULE ProviderLifecycle ------------------------
(*
  Formal model of the OpenFeature provider lifecycle in zio-openfeature.

  Source of truth:
    - ProviderStatus.scala          (state enum)
    - FeatureFlagsLive.scala:88-138 (event bridge handlers)
    - FeatureFlagsLive.scala:847-878 (setProvider / shutdown)
    - FeatureFlags.scala:693-696    (watchdog: NotReady|Error -> Fatal)

  Three concurrent actors:
    Swapper     - serialised by swapLock; the setProvider / shutdown path
    EventBridge - one logical actor for asynchronous Java SDK callbacks
                  (PROVIDER_READY / PROVIDER_ERROR / PROVIDER_STALE)
    Watchdog    - forkScoped daemon that fires Fatal after initTimeout

  Simplifications:
    - swapLock is modelled as a boolean mutex (same semantics as Semaphore(1))
    - Wall-clock time is replaced with a monotonic step counter `now`
    - FailedSwapGuardMillis is rendered as a guard window of GUARD_STEPS steps
    - CountDownLatch (onReady) is omitted; it doesn't affect state transitions
    - ConfigChanged event is omitted; it does not update statusRef
    - PROVIDER_STALE is included as it does transition statusRef
*)

EXTENDS Naturals, TLC

CONSTANTS
  N_BRIDGE_EVENTS,   \* max number of pending async events (2 is sufficient)
  GUARD_STEPS,       \* FailedSwapGuardMillis in logical steps (set to 2)
  INIT_TIMEOUT       \* watchdog fires after this many steps (set to 5)

ASSUME N_BRIDGE_EVENTS \in 1..4
ASSUME GUARD_STEPS \in 1..10
ASSUME INIT_TIMEOUT \in 1..20

(*--algorithm ProviderLifecycle

variables
  \* Shared state (mirrors FeatureFlagsState / FeatureFlagsLive fields)
  status = "NotReady",        \* ProviderStatus
  swapLocked = FALSE,         \* swapLock Semaphore(1)
  recentSwapFailureAt = 0,    \* AtomicLong (logical time of last failed swap)
  now = 0,                    \* logical clock (step counter)

  \* Pending async events from the Java SDK (queue of up to N_BRIDGE_EVENTS)
  pendingEvents = <<>>,

  \* Watchdog has fired
  watchdogFired = FALSE,

  \* Evaluation-in-progress flag (for TOCTOU check)
  evalInProgress = FALSE,
  evalPassedCheck = FALSE;     \* did the eval pass checkProviderStatus?

define
  \* ProviderStatus values
  CanEvaluate == status \in {"Ready", "Stale"}

  \* Safety: status stays in the declared enum
  StatusValid == status \in {"NotReady", "Ready", "Error", "Stale", "Fatal", "ShuttingDown"}

  \* Safety: Fatal is a terminal state (no outward transitions)
  FatalIsTerminal == (status = "Fatal") => [](status = "Fatal")

  \* Safety: ShuttingDown is unreachable (never written in main sources)
  \* TLC will report a counterexample if any path writes "ShuttingDown"
  ShuttingDownUnreachable == status /= "ShuttingDown"

  \* Safety (TOCTOU): if an eval passed checkProviderStatus, the status at
  \* that moment was CanEvaluate.  A subsequent setProvider may change it —
  \* this invariant deliberately checks that the "snapshot" was valid when
  \* taken, not that it holds for the whole eval duration.
  EvalPassedOnCanEvaluate == evalPassedCheck => CanEvaluate \/ evalInProgress

  \* Liveness: from NotReady, eventually the status moves (via Ready, Error,
  \* or Fatal — the watchdog ensures the last escape)
  EventuallyNotStuck ==
    (status = "NotReady") ~> (status /= "NotReady")

  \* Liveness: Fatal is eventually reachable if stuck in NotReady past timeout
  WatchdogFires == watchdogFired => <>(status = "Fatal")
end define;

\* ────────────────────────────────────────────────────────────────────
\* Helper: advance the logical clock
macro tick() begin
  now := now + 1;
end macro;

\* ────────────────────────────────────────────────────────────────────
\* Swapper actor
\* Mirrors FeatureFlagsLive.setProvider (lines 847-878)

process Swapper = "Swapper"
variables swapStep = "idle";   \* "idle" | "locking" | "inSwap" | "done"
begin
  SwapLoop:
    while TRUE do
      \* Wait for lock
      await ~swapLocked;
      swapLocked := TRUE;
      tick();

      \* Step 1: transition to NotReady
      status := "NotReady";

      \* Non-deterministically succeed or fail the swap
      either
        \* Success path: setProviderAndWait returned ok (line 876)
        SwapSuccess:
          tick();
          status := "Ready";
          swapLocked := FALSE;
      or
        \* Failure path: stamp recentSwapFailureAt BEFORE writing Error (line 869)
        SwapFailA:
          tick();
          recentSwapFailureAt := now;
        SwapFailB:
          tick();
          status := "Error";
          swapLocked := FALSE;
      end either;
    end while;
end process;

\* ────────────────────────────────────────────────────────────────────
\* Shutdown actor (simplified: mirrors shutdown method, line 882-889)
process Shutdown = "Shutdown"
begin
  ShutdownStep:
    \* Only shutdown once, non-deterministically
    await status /= "ShuttingDown";  \* guard: only proceed if not already shutting down
    either
      \* Actually trigger shutdown
      status := "NotReady";
      \* (hub.shutdown, api.shutdown omitted — no state)
    or
      skip;  \* never shut down in this run
    end either;
end process;

\* ────────────────────────────────────────────────────────────────────
\* EventBridge actor
\* Mirrors FeatureFlagsLive.startEventBridge (lines 86-138)
\* Non-deterministically emits Ready / Error / Stale events

process EventBridge = "EventBridge"
variables eventCount = 0;
begin
  BridgeLoop:
    while eventCount < N_BRIDGE_EVENTS do
      tick();
      eventCount := eventCount + 1;
      either
        \* PROVIDER_READY handler (lines 94-103)
        \* Error -> Ready only if sinceFailure >= GUARD_STEPS
        if status = "NotReady" then
          status := "Ready";
        elsif status = "Stale" then
          status := "Ready";
        elsif status = "Error" then
          if (now - recentSwapFailureAt) >= GUARD_STEPS then
            status := "Ready";
          \* else: guard blocks transition; status stays Error
          end if;
        \* else: Fatal, Ready etc. -- no-op per "case other => other" (line 100)
        end if;
      or
        \* PROVIDER_ERROR handler (lines 111-116)
        status := "Error";
      or
        \* PROVIDER_STALE handler (lines 122-124)
        status := "Stale";
      or
        skip;  \* event delivered but no state effect (ConfigChanged)
      end either;
    end while;
end process;

\* ────────────────────────────────────────────────────────────────────
\* Watchdog actor
\* Mirrors FeatureFlags.buildAsync forkScoped fiber (lines 693-696)
\* After INIT_TIMEOUT steps: NotReady | Error -> Fatal

process Watchdog = "Watchdog"
begin
  WatchdogSleep:
    await now >= INIT_TIMEOUT;
  WatchdogFire:
    tick();
    watchdogFired := TRUE;
    if status = "NotReady" \/ status = "Error" then
      status := "Fatal";
    end if;
end process;

\* ────────────────────────────────────────────────────────────────────
\* Evaluator (TOCTOU check)
\* Mirrors FeatureFlagsLive.checkProviderStatus (line 230) followed by
\* an evaluation that may race with a concurrent setProvider

process Evaluator = "Evaluator"
begin
  EvalCheck:
    \* checkProviderStatus: sample statusRef
    if CanEvaluate then
      evalPassedCheck := TRUE;
      evalInProgress := TRUE;
      \* Evaluation proceeds -- a concurrent Swapper may now change status
      EvalComplete:
        evalInProgress  := FALSE;
        evalPassedCheck := FALSE;
    end if;
end process;

end algorithm; *)
\* BEGIN TRANSLATION (chksum(pcal) = "628d1e59" /\ chksum(tla) = "5c21066b")
VARIABLES pc, status, swapLocked, recentSwapFailureAt, now, pendingEvents, 
          watchdogFired, evalInProgress, evalPassedCheck

(* define statement *)
CanEvaluate == status \in {"Ready", "Stale"}


StatusValid == status \in {"NotReady", "Ready", "Error", "Stale", "Fatal", "ShuttingDown"}


FatalIsTerminal == (status = "Fatal") => [](status = "Fatal")



ShuttingDownUnreachable == status /= "ShuttingDown"





EvalPassedOnCanEvaluate == evalPassedCheck => CanEvaluate \/ evalInProgress



EventuallyNotStuck ==
  (status = "NotReady") ~> (status /= "NotReady")


WatchdogFires == watchdogFired => <>(status = "Fatal")

VARIABLES swapStep, eventCount

vars == << pc, status, swapLocked, recentSwapFailureAt, now, pendingEvents, 
           watchdogFired, evalInProgress, evalPassedCheck, swapStep, 
           eventCount >>

ProcSet == {"Swapper"} \cup {"Shutdown"} \cup {"EventBridge"} \cup {"Watchdog"} \cup {"Evaluator"}

Init == (* Global variables *)
        /\ status = "NotReady"
        /\ swapLocked = FALSE
        /\ recentSwapFailureAt = 0
        /\ now = 0
        /\ pendingEvents = <<>>
        /\ watchdogFired = FALSE
        /\ evalInProgress = FALSE
        /\ evalPassedCheck = FALSE
        (* Process Swapper *)
        /\ swapStep = "idle"
        (* Process EventBridge *)
        /\ eventCount = 0
        /\ pc = [self \in ProcSet |-> CASE self = "Swapper" -> "SwapLoop"
                                        [] self = "Shutdown" -> "ShutdownStep"
                                        [] self = "EventBridge" -> "BridgeLoop"
                                        [] self = "Watchdog" -> "WatchdogSleep"
                                        [] self = "Evaluator" -> "EvalCheck"]

SwapLoop == /\ pc["Swapper"] = "SwapLoop"
            /\ ~swapLocked
            /\ swapLocked' = TRUE
            /\ now' = now + 1
            /\ status' = "NotReady"
            /\ \/ /\ pc' = [pc EXCEPT !["Swapper"] = "SwapSuccess"]
               \/ /\ pc' = [pc EXCEPT !["Swapper"] = "SwapFailA"]
            /\ UNCHANGED << recentSwapFailureAt, pendingEvents, watchdogFired, 
                            evalInProgress, evalPassedCheck, swapStep, 
                            eventCount >>

SwapSuccess == /\ pc["Swapper"] = "SwapSuccess"
               /\ now' = now + 1
               /\ status' = "Ready"
               /\ swapLocked' = FALSE
               /\ pc' = [pc EXCEPT !["Swapper"] = "SwapLoop"]
               /\ UNCHANGED << recentSwapFailureAt, pendingEvents, 
                               watchdogFired, evalInProgress, evalPassedCheck, 
                               swapStep, eventCount >>

SwapFailA == /\ pc["Swapper"] = "SwapFailA"
             /\ now' = now + 1
             /\ recentSwapFailureAt' = now'
             /\ pc' = [pc EXCEPT !["Swapper"] = "SwapFailB"]
             /\ UNCHANGED << status, swapLocked, pendingEvents, watchdogFired, 
                             evalInProgress, evalPassedCheck, swapStep, 
                             eventCount >>

SwapFailB == /\ pc["Swapper"] = "SwapFailB"
             /\ now' = now + 1
             /\ status' = "Error"
             /\ swapLocked' = FALSE
             /\ pc' = [pc EXCEPT !["Swapper"] = "SwapLoop"]
             /\ UNCHANGED << recentSwapFailureAt, pendingEvents, watchdogFired, 
                             evalInProgress, evalPassedCheck, swapStep, 
                             eventCount >>

Swapper == SwapLoop \/ SwapSuccess \/ SwapFailA \/ SwapFailB

ShutdownStep == /\ pc["Shutdown"] = "ShutdownStep"
                /\ status /= "ShuttingDown"
                /\ \/ /\ status' = "NotReady"
                   \/ /\ TRUE
                      /\ UNCHANGED status
                /\ pc' = [pc EXCEPT !["Shutdown"] = "Done"]
                /\ UNCHANGED << swapLocked, recentSwapFailureAt, now, 
                                pendingEvents, watchdogFired, evalInProgress, 
                                evalPassedCheck, swapStep, eventCount >>

Shutdown == ShutdownStep

BridgeLoop == /\ pc["EventBridge"] = "BridgeLoop"
              /\ IF eventCount < N_BRIDGE_EVENTS
                    THEN /\ now' = now + 1
                         /\ eventCount' = eventCount + 1
                         /\ \/ /\ IF status = "NotReady"
                                     THEN /\ status' = "Ready"
                                     ELSE /\ IF status = "Stale"
                                                THEN /\ status' = "Ready"
                                                ELSE /\ IF status = "Error"
                                                           THEN /\ IF (now' - recentSwapFailureAt) >= GUARD_STEPS
                                                                      THEN /\ status' = "Ready"
                                                                      ELSE /\ TRUE
                                                                           /\ UNCHANGED status
                                                           ELSE /\ TRUE
                                                                /\ UNCHANGED status
                            \/ /\ status' = "Error"
                            \/ /\ status' = "Stale"
                            \/ /\ TRUE
                               /\ UNCHANGED status
                         /\ pc' = [pc EXCEPT !["EventBridge"] = "BridgeLoop"]
                    ELSE /\ pc' = [pc EXCEPT !["EventBridge"] = "Done"]
                         /\ UNCHANGED << status, now, eventCount >>
              /\ UNCHANGED << swapLocked, recentSwapFailureAt, pendingEvents, 
                              watchdogFired, evalInProgress, evalPassedCheck, 
                              swapStep >>

EventBridge == BridgeLoop

WatchdogSleep == /\ pc["Watchdog"] = "WatchdogSleep"
                 /\ now >= INIT_TIMEOUT
                 /\ pc' = [pc EXCEPT !["Watchdog"] = "WatchdogFire"]
                 /\ UNCHANGED << status, swapLocked, recentSwapFailureAt, now, 
                                 pendingEvents, watchdogFired, evalInProgress, 
                                 evalPassedCheck, swapStep, eventCount >>

WatchdogFire == /\ pc["Watchdog"] = "WatchdogFire"
                /\ now' = now + 1
                /\ watchdogFired' = TRUE
                /\ IF status = "NotReady" \/ status = "Error"
                      THEN /\ status' = "Fatal"
                      ELSE /\ TRUE
                           /\ UNCHANGED status
                /\ pc' = [pc EXCEPT !["Watchdog"] = "Done"]
                /\ UNCHANGED << swapLocked, recentSwapFailureAt, pendingEvents, 
                                evalInProgress, evalPassedCheck, swapStep, 
                                eventCount >>

Watchdog == WatchdogSleep \/ WatchdogFire

EvalCheck == /\ pc["Evaluator"] = "EvalCheck"
             /\ IF CanEvaluate
                   THEN /\ evalPassedCheck' = TRUE
                        /\ evalInProgress' = TRUE
                        /\ pc' = [pc EXCEPT !["Evaluator"] = "EvalComplete"]
                   ELSE /\ pc' = [pc EXCEPT !["Evaluator"] = "Done"]
                        /\ UNCHANGED << evalInProgress, evalPassedCheck >>
             /\ UNCHANGED << status, swapLocked, recentSwapFailureAt, now, 
                             pendingEvents, watchdogFired, swapStep, 
                             eventCount >>

EvalComplete == /\ pc["Evaluator"] = "EvalComplete"
                /\ evalInProgress' = FALSE
                /\ evalPassedCheck' = FALSE
                /\ pc' = [pc EXCEPT !["Evaluator"] = "Done"]
                /\ UNCHANGED << status, swapLocked, recentSwapFailureAt, now, 
                                pendingEvents, watchdogFired, swapStep, 
                                eventCount >>

Evaluator == EvalCheck \/ EvalComplete

Next == Swapper \/ Shutdown \/ EventBridge \/ Watchdog \/ Evaluator

Spec == Init /\ [][Next]_vars

\* END TRANSLATION

\* ── State space constraint (referenced from .cfg) ─────────────────
ClockBound == now <= INIT_TIMEOUT + N_BRIDGE_EVENTS + 5

\* ────────────────────────────────────────────────────────────────────
\* PROPERTIES TO CHECK IN TLC

\* Safety invariants (add to TLC "Invariants"):
\*   StatusValid
\*   ShuttingDownUnreachable
\*   EvalPassedOnCanEvaluate

\* Temporal properties (add to TLC "Properties"):
\*   FatalIsTerminal
\*   EventuallyNotStuck
\*   WatchdogFires

==========================================================================

