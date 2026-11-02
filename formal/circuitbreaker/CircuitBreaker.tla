------------------------------ MODULE CircuitBreaker -------------------------------
(***************************************************************************)
(* The CircuitBreaker state machine (extras/.../CircuitBreaker.scala) and  *)
(* its CAS discipline.  One AtomicReference holds the circuit, the failure *)
(* count and the epoch.  Every operation reads it, decides, and            *)
(* compareAndSets, so other threads interleave between the read and the    *)
(* CAS, and a lost CAS decides again on the state as it now is.            *)
(*                                                                         *)
(* Callers take a permit, run the delegate, and record the outcome against *)
(* the permit, or die without recording.  Signals are the delegate's       *)
(* events and polls: trip, reset and transitionToHalfOpen.  The clock only *)
(* says whether a deadline has passed.  Budgets keep the state space       *)
(* finite; a caller may always arrive once a probe has died, so a budget   *)
(* cannot be what leaves a slot pinned.                                    *)
(***************************************************************************)
EXTENDS Integers, FiniteSets, TLC

CONSTANTS
    Callers,           \* concurrent evaluations
    Signals,           \* concurrent event or poll threads
    FailureThreshold,  \* config.failureThreshold
    HalfOpenMaxCalls,  \* config.halfOpenMaxCalls
    MaxCalls,          \* evaluations that arrive, apart from those that follow a dead probe
    MaxDeaths,         \* evaluations that die without recording an outcome
    MaxSignals         \* signals that arrive

None == -1

\* Real time is not state: sinceNanos and probeStartNanos only change with the epoch, so CAS equality over this record
\* decides exactly as equality over the real CircuitBreakerState does.
Closed == [kind |-> "Closed", reason |-> "none", stale |-> FALSE, successes |-> 0, probing |-> FALSE]
Open(reason, stale) == [Closed EXCEPT !.kind = "Open", !.reason = reason, !.stale = stale]
HalfOpen(successes, probing) == [Closed EXCEPT !.kind = "HalfOpen", !.successes = successes, !.probing = probing]

\* CircuitBreaker.transition: every circuit transition starts a new epoch.
Transition(s, to, failures) == [circuit |-> to, failures |-> failures, epoch |-> s.epoch + 1]

\* recordSuccess, recordReachable and recordFailure, given a permit that is current.
RecordNext(outcome, s) ==
    LET c == s.circuit IN
    CASE c.kind = "Open" -> s
      [] outcome = "success" ->
           IF c.kind = "Closed" THEN [s EXCEPT !.failures = 0]
           ELSE IF c.successes + 1 >= HalfOpenMaxCalls THEN Transition(s, Closed, 0)
           ELSE Transition(s, HalfOpen(c.successes + 1, FALSE), 0)
      [] outcome = "reachable" ->
           IF c.kind = "Closed" THEN [s EXCEPT !.failures = 0]
           ELSE IF ~c.probing THEN s
           ELSE Transition(s, HalfOpen(c.successes, FALSE), s.failures)
      [] outcome = "failure" ->
           IF c.kind = "Closed" /\ s.failures + 1 < FailureThreshold THEN [s EXCEPT !.failures = s.failures + 1]
           ELSE Transition(s, Open("Failures", FALSE), s.failures + 1)

\* trip(reason, stale).
TripNext(op, s) ==
    LET c == s.circuit IN
    IF c.kind = "Open" THEN
        IF c.reason = "Polled" /\ op.reason = "External"
        THEN Transition(s, Open("External", c.stale /\ op.stale), s.failures)
        ELSE IF c.stale /\ ~op.stale THEN Transition(s, [c EXCEPT !.stale = FALSE], s.failures)
        ELSE s
    ELSE Transition(s, Open(op.reason, op.stale), s.failures)

\* resetIf(opener).
ResetNext(op, s) ==
    IF s.circuit.kind = "Open" /\ s.circuit.reason \in op.openers THEN Transition(s, Closed, 0) ELSE s

\* transitionToHalfOpen.
ToHalfOpenNext(s) ==
    IF s.circuit.kind = "Open" /\ s.circuit.reason # "Failures"
    THEN Transition(s, HalfOpen(0, FALSE), s.failures)
    ELSE s

SignalNext(op, s) ==
    CASE op.kind = "Trip" -> TripNext(op, s)
      [] op.kind = "Reset" -> ResetNext(op, s)
      [] op.kind = "ToHalfOpen" -> ToHalfOpenNext(s)

Op(kind, reason, stale, openers) == [kind |-> kind, reason |-> reason, stale |-> stale, openers |-> openers]
\* The trips of an event and of a poll, stale or worse; an event READY; a polled READY; a stale delegate under
\* StalePolicy.HalfOpen.
Ops == {Op("Trip", r, s, {}) : r \in {"External", "Polled"}, s \in BOOLEAN}
       \cup {Op("Reset", "none", FALSE, {"External", "Polled"}), Op("Reset", "none", FALSE, {"Polled"}),
             Op("ToHalfOpen", "none", FALSE, {})}
NoOp == Op("none", "none", FALSE, {})

Init0 == [circuit |-> Closed, failures |-> 0, epoch |-> 0]
NoSnap == [circuit |-> Closed, failures |-> None, epoch |-> None]
NoPermit == [kind |-> "none", epoch |-> None, generation |-> None]

(* --algorithm CircuitBreaker
variables
    st = Init0,                      \* the AtomicReference
    resetElapsed = FALSE,            \* resetTimeout has passed since the open circuit's sinceNanos
    probeElapsed = FALSE,            \* probeTimeout has passed since the probe's probeStartNanos
    permit = [c \in Callers |-> NoPermit],  \* what each caller was admitted under; its generation is a ghost
    calls = 0, deaths = 0, signals = 0,     \* budgets
    generation = 0,                  \* ghost: the circuit transitions so far, whatever happens to the epoch
    probesInFlight = 0,              \* ghost: the probes occupying the half-open slot, a dead one included
    staleOutcomesApplied = 0,        \* ghost: outcomes that changed the state after their permit's generation had passed
    staleAllowed = FALSE;            \* ghost: a stale trip opened this circuit, and no worse trip has landed since

define
    Probing == st.circuit.kind = "HalfOpen" /\ st.circuit.probing
    \* A slot held by no running call: its probe died.  Only a fresh caller, after probeTimeout, can free it.
    Wedged == Probing /\ \A c \in Callers : permit[c].generation # generation
    \* probeFrom: the success count a probe starts from, or None when this circuit admits no probe now.
    ProbeFrom(c) ==
        IF c.kind = "Open" THEN (IF resetElapsed THEN 0 ELSE None)
        ELSE IF ~c.probing THEN c.successes
        ELSE IF probeElapsed THEN c.successes
        ELSE None
end define;

\* One successful CAS.  Open to Open keeps sinceNanos, so it keeps resetElapsed; any other transition starts afresh.
\* st goes last: the assignments before it read the state the CAS replaced.
macro install(to, staleNow) begin
    generation := IF to.circuit # st.circuit THEN generation + 1 ELSE generation;
    resetElapsed := resetElapsed /\ st.circuit.kind = "Open" /\ to.circuit.kind = "Open";
    probeElapsed := FALSE;
    probesInFlight := 0;
    staleAllowed := staleNow;
    st := to;
end macro;

fair process Caller \in Callers
variables snap = NoSnap, outcome = "none";
begin
  CArrive:
    await calls < MaxCalls \/ Wedged;
    calls := IF Wedged THEN calls ELSE calls + 1;
  \* acquire: a closed circuit admits on the read alone.
  CAcquireRead:
    if st.circuit.kind = "Closed" then
      permit[self] := [kind |-> "call", epoch |-> st.epoch, generation |-> generation];
      goto CEvaluate;
    elsif ProbeFrom(st.circuit) = None then
      goto CArrive;
    else
      snap := st;
    end if;
  \* The CAS that takes the slot.  Taking an occupied slot needs the occupant's probeTimeout to have passed, and replaces
  \* it; anything else adds a probe.
  CAcquireCas:
    if st = snap then
      permit[self] := [kind |-> "probe",
                       epoch |-> Transition(snap, HalfOpen(ProbeFrom(snap.circuit), TRUE), snap.failures).epoch,
                       generation |-> generation + 1];
      probesInFlight := IF Probing /\ probeElapsed THEN probesInFlight ELSE probesInFlight + 1;
      generation := generation + 1;
      resetElapsed := FALSE;
      probeElapsed := FALSE;
      staleAllowed := FALSE;
      st := Transition(snap, HalfOpen(ProbeFrom(snap.circuit), TRUE), snap.failures);
      snap := NoSnap;
    else
      snap := NoSnap;
      goto CAcquireRead;
    end if;
  CEvaluate:
    either
      await deaths < MaxDeaths;
      deaths := deaths + 1;
      permit[self] := NoPermit;
      goto CArrive;
    or
      with o \in {"success", "reachable", "failure"} do outcome := o; end with;
    end either;
  \* current(permit), then the outcome's decision on that state.
  CRecordRead:
    if permit[self].epoch = st.epoch /\ RecordNext(outcome, st) # st then
      snap := st;
    else
      permit[self] := NoPermit;
      outcome := "none";
      goto CArrive;
    end if;
  CRecordCas:
    if st = snap then
      staleOutcomesApplied := staleOutcomesApplied + (IF permit[self].generation # generation THEN 1 ELSE 0);
      install(RecordNext(outcome, snap), FALSE);
      permit[self] := NoPermit;
      outcome := "none";
      snap := NoSnap;
      goto CArrive;
    else
      snap := NoSnap;
      goto CRecordRead;
    end if;
end process;

fair process Signal \in Signals
variables sigSnap = NoSnap, op = NoOp;
begin
  SArrive:
    await signals < MaxSignals;
    signals := signals + 1;
    with o \in Ops do op := o; end with;
  SRead:
    if SignalNext(op, st).circuit = st.circuit then
      if op.kind = "Trip" then staleAllowed := staleAllowed /\ op.stale; end if;
      op := NoOp;
      goto SArrive;
    else
      sigSnap := st;
    end if;
  SCas:
    if st = sigSnap then
      install(SignalNext(op, sigSnap),
              op.kind = "Trip" /\ IF sigSnap.circuit.kind = "Open" THEN staleAllowed /\ op.stale ELSE op.stale);
      op := NoOp;
      sigSnap := NoSnap;
      goto SArrive;
    else
      sigSnap := NoSnap;
      goto SRead;
    end if;
end process;

\* Deadlines pass at any moment, and eventually.
fair process Clock = "clock"
begin
  CTick:
    either
      await st.circuit.kind = "Open" /\ ~resetElapsed;
      resetElapsed := TRUE;
    or
      await Probing /\ ~probeElapsed;
      probeElapsed := TRUE;
    end either;
    goto CTick;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
VARIABLES st, resetElapsed, probeElapsed, permit, calls, deaths, signals, 
          generation, probesInFlight, staleOutcomesApplied, staleAllowed, pc

(* define statement *)
Probing == st.circuit.kind = "HalfOpen" /\ st.circuit.probing

Wedged == Probing /\ \A c \in Callers : permit[c].generation # generation

ProbeFrom(c) ==
    IF c.kind = "Open" THEN (IF resetElapsed THEN 0 ELSE None)
    ELSE IF ~c.probing THEN c.successes
    ELSE IF probeElapsed THEN c.successes
    ELSE None

VARIABLES snap, outcome, sigSnap, op

vars == << st, resetElapsed, probeElapsed, permit, calls, deaths, signals, 
           generation, probesInFlight, staleOutcomesApplied, staleAllowed, pc, 
           snap, outcome, sigSnap, op >>

ProcSet == (Callers) \cup (Signals) \cup {"clock"}

Init == (* Global variables *)
        /\ st = Init0
        /\ resetElapsed = FALSE
        /\ probeElapsed = FALSE
        /\ permit = [c \in Callers |-> NoPermit]
        /\ calls = 0
        /\ deaths = 0
        /\ signals = 0
        /\ generation = 0
        /\ probesInFlight = 0
        /\ staleOutcomesApplied = 0
        /\ staleAllowed = FALSE
        (* Process Caller *)
        /\ snap = [self \in Callers |-> NoSnap]
        /\ outcome = [self \in Callers |-> "none"]
        (* Process Signal *)
        /\ sigSnap = [self \in Signals |-> NoSnap]
        /\ op = [self \in Signals |-> NoOp]
        /\ pc = [self \in ProcSet |-> CASE self \in Callers -> "CArrive"
                                        [] self \in Signals -> "SArrive"
                                        [] self = "clock" -> "CTick"]

CArrive(self) == /\ pc[self] = "CArrive"
                 /\ calls < MaxCalls \/ Wedged
                 /\ calls' = IF Wedged THEN calls ELSE calls + 1
                 /\ pc' = [pc EXCEPT ![self] = "CAcquireRead"]
                 /\ UNCHANGED << st, resetElapsed, probeElapsed, permit, 
                                 deaths, signals, generation, probesInFlight, 
                                 staleOutcomesApplied, staleAllowed, snap, 
                                 outcome, sigSnap, op >>

CAcquireRead(self) == /\ pc[self] = "CAcquireRead"
                      /\ IF st.circuit.kind = "Closed"
                            THEN /\ permit' = [permit EXCEPT ![self] = [kind |-> "call", epoch |-> st.epoch, generation |-> generation]]
                                 /\ pc' = [pc EXCEPT ![self] = "CEvaluate"]
                                 /\ snap' = snap
                            ELSE /\ IF ProbeFrom(st.circuit) = None
                                       THEN /\ pc' = [pc EXCEPT ![self] = "CArrive"]
                                            /\ snap' = snap
                                       ELSE /\ snap' = [snap EXCEPT ![self] = st]
                                            /\ pc' = [pc EXCEPT ![self] = "CAcquireCas"]
                                 /\ UNCHANGED permit
                      /\ UNCHANGED << st, resetElapsed, probeElapsed, calls, 
                                      deaths, signals, generation, 
                                      probesInFlight, staleOutcomesApplied, 
                                      staleAllowed, outcome, sigSnap, op >>

CAcquireCas(self) == /\ pc[self] = "CAcquireCas"
                     /\ IF st = snap[self]
                           THEN /\ permit' = [permit EXCEPT ![self] = [kind |-> "probe",
                                                                       epoch |-> Transition(snap[self], HalfOpen(ProbeFrom(snap[self].circuit), TRUE), snap[self].failures).epoch,
                                                                       generation |-> generation + 1]]
                                /\ probesInFlight' = (IF Probing /\ probeElapsed THEN probesInFlight ELSE probesInFlight + 1)
                                /\ generation' = generation + 1
                                /\ resetElapsed' = FALSE
                                /\ probeElapsed' = FALSE
                                /\ staleAllowed' = FALSE
                                /\ st' = Transition(snap[self], HalfOpen(ProbeFrom(snap[self].circuit), TRUE), snap[self].failures)
                                /\ snap' = [snap EXCEPT ![self] = NoSnap]
                                /\ pc' = [pc EXCEPT ![self] = "CEvaluate"]
                           ELSE /\ snap' = [snap EXCEPT ![self] = NoSnap]
                                /\ pc' = [pc EXCEPT ![self] = "CAcquireRead"]
                                /\ UNCHANGED << st, resetElapsed, probeElapsed, 
                                                permit, generation, 
                                                probesInFlight, staleAllowed >>
                     /\ UNCHANGED << calls, deaths, signals, 
                                     staleOutcomesApplied, outcome, sigSnap, 
                                     op >>

CEvaluate(self) == /\ pc[self] = "CEvaluate"
                   /\ \/ /\ deaths < MaxDeaths
                         /\ deaths' = deaths + 1
                         /\ permit' = [permit EXCEPT ![self] = NoPermit]
                         /\ pc' = [pc EXCEPT ![self] = "CArrive"]
                         /\ UNCHANGED outcome
                      \/ /\ \E o \in {"success", "reachable", "failure"}:
                              outcome' = [outcome EXCEPT ![self] = o]
                         /\ pc' = [pc EXCEPT ![self] = "CRecordRead"]
                         /\ UNCHANGED <<permit, deaths>>
                   /\ UNCHANGED << st, resetElapsed, probeElapsed, calls, 
                                   signals, generation, probesInFlight, 
                                   staleOutcomesApplied, staleAllowed, snap, 
                                   sigSnap, op >>

CRecordRead(self) == /\ pc[self] = "CRecordRead"
                     /\ IF permit[self].epoch = st.epoch /\ RecordNext(outcome[self], st) # st
                           THEN /\ snap' = [snap EXCEPT ![self] = st]
                                /\ pc' = [pc EXCEPT ![self] = "CRecordCas"]
                                /\ UNCHANGED << permit, outcome >>
                           ELSE /\ permit' = [permit EXCEPT ![self] = NoPermit]
                                /\ outcome' = [outcome EXCEPT ![self] = "none"]
                                /\ pc' = [pc EXCEPT ![self] = "CArrive"]
                                /\ snap' = snap
                     /\ UNCHANGED << st, resetElapsed, probeElapsed, calls, 
                                     deaths, signals, generation, 
                                     probesInFlight, staleOutcomesApplied, 
                                     staleAllowed, sigSnap, op >>

CRecordCas(self) == /\ pc[self] = "CRecordCas"
                    /\ IF st = snap[self]
                          THEN /\ staleOutcomesApplied' = staleOutcomesApplied + (IF permit[self].generation # generation THEN 1 ELSE 0)
                               /\ generation' = (IF (RecordNext(outcome[self], snap[self])).circuit # st.circuit THEN generation + 1 ELSE generation)
                               /\ resetElapsed' = (resetElapsed /\ st.circuit.kind = "Open" /\ (RecordNext(outcome[self], snap[self])).circuit.kind = "Open")
                               /\ probeElapsed' = FALSE
                               /\ probesInFlight' = 0
                               /\ staleAllowed' = FALSE
                               /\ st' = RecordNext(outcome[self], snap[self])
                               /\ permit' = [permit EXCEPT ![self] = NoPermit]
                               /\ outcome' = [outcome EXCEPT ![self] = "none"]
                               /\ snap' = [snap EXCEPT ![self] = NoSnap]
                               /\ pc' = [pc EXCEPT ![self] = "CArrive"]
                          ELSE /\ snap' = [snap EXCEPT ![self] = NoSnap]
                               /\ pc' = [pc EXCEPT ![self] = "CRecordRead"]
                               /\ UNCHANGED << st, resetElapsed, probeElapsed, 
                                               permit, generation, 
                                               probesInFlight, 
                                               staleOutcomesApplied, 
                                               staleAllowed, outcome >>
                    /\ UNCHANGED << calls, deaths, signals, sigSnap, op >>

Caller(self) == CArrive(self) \/ CAcquireRead(self) \/ CAcquireCas(self)
                   \/ CEvaluate(self) \/ CRecordRead(self)
                   \/ CRecordCas(self)

SArrive(self) == /\ pc[self] = "SArrive"
                 /\ signals < MaxSignals
                 /\ signals' = signals + 1
                 /\ \E o \in Ops:
                      op' = [op EXCEPT ![self] = o]
                 /\ pc' = [pc EXCEPT ![self] = "SRead"]
                 /\ UNCHANGED << st, resetElapsed, probeElapsed, permit, calls, 
                                 deaths, generation, probesInFlight, 
                                 staleOutcomesApplied, staleAllowed, snap, 
                                 outcome, sigSnap >>

SRead(self) == /\ pc[self] = "SRead"
               /\ IF SignalNext(op[self], st).circuit = st.circuit
                     THEN /\ IF op[self].kind = "Trip"
                                THEN /\ staleAllowed' = (staleAllowed /\ op[self].stale)
                                ELSE /\ TRUE
                                     /\ UNCHANGED staleAllowed
                          /\ op' = [op EXCEPT ![self] = NoOp]
                          /\ pc' = [pc EXCEPT ![self] = "SArrive"]
                          /\ UNCHANGED sigSnap
                     ELSE /\ sigSnap' = [sigSnap EXCEPT ![self] = st]
                          /\ pc' = [pc EXCEPT ![self] = "SCas"]
                          /\ UNCHANGED << staleAllowed, op >>
               /\ UNCHANGED << st, resetElapsed, probeElapsed, permit, calls, 
                               deaths, signals, generation, probesInFlight, 
                               staleOutcomesApplied, snap, outcome >>

SCas(self) == /\ pc[self] = "SCas"
              /\ IF st = sigSnap[self]
                    THEN /\ generation' = (IF (SignalNext(op[self], sigSnap[self])).circuit # st.circuit THEN generation + 1 ELSE generation)
                         /\ resetElapsed' = (resetElapsed /\ st.circuit.kind = "Open" /\ (SignalNext(op[self], sigSnap[self])).circuit.kind = "Open")
                         /\ probeElapsed' = FALSE
                         /\ probesInFlight' = 0
                         /\ staleAllowed' = (op[self].kind = "Trip" /\ IF sigSnap[self].circuit.kind = "Open" THEN staleAllowed /\ op[self].stale ELSE op[self].stale)
                         /\ st' = SignalNext(op[self], sigSnap[self])
                         /\ op' = [op EXCEPT ![self] = NoOp]
                         /\ sigSnap' = [sigSnap EXCEPT ![self] = NoSnap]
                         /\ pc' = [pc EXCEPT ![self] = "SArrive"]
                    ELSE /\ sigSnap' = [sigSnap EXCEPT ![self] = NoSnap]
                         /\ pc' = [pc EXCEPT ![self] = "SRead"]
                         /\ UNCHANGED << st, resetElapsed, probeElapsed, 
                                         generation, probesInFlight, 
                                         staleAllowed, op >>
              /\ UNCHANGED << permit, calls, deaths, signals, 
                              staleOutcomesApplied, snap, outcome >>

Signal(self) == SArrive(self) \/ SRead(self) \/ SCas(self)

CTick == /\ pc["clock"] = "CTick"
         /\ \/ /\ st.circuit.kind = "Open" /\ ~resetElapsed
               /\ resetElapsed' = TRUE
               /\ UNCHANGED probeElapsed
            \/ /\ Probing /\ ~probeElapsed
               /\ probeElapsed' = TRUE
               /\ UNCHANGED resetElapsed
         /\ pc' = [pc EXCEPT !["clock"] = "CTick"]
         /\ UNCHANGED << st, permit, calls, deaths, signals, generation, 
                         probesInFlight, staleOutcomesApplied, staleAllowed, 
                         snap, outcome, sigSnap, op >>

Clock == CTick

(* Allow infinite stuttering to prevent deadlock on termination. *)
Terminating == /\ \A self \in ProcSet: pc[self] = "Done"
               /\ UNCHANGED vars

Next == Clock
           \/ (\E self \in Callers: Caller(self))
           \/ (\E self \in Signals: Signal(self))
           \/ Terminating

Spec == /\ Init /\ [][Next]_vars
        /\ \A self \in Callers : WF_vars(Caller(self))
        /\ \A self \in Signals : WF_vars(Signal(self))
        /\ WF_vars(Clock)

Termination == <>(\A self \in ProcSet: pc[self] = "Done")

\* END TRANSLATION

\* At most one probe occupies the half-open slot, and the slot is occupied exactly while the circuit says probing.  The
\* takeover rule: a grant on an occupied slot is legal only once that occupant's probeTimeout has passed, and it replaces
\* the occupant, whose permit is then of an earlier epoch.  So an abandoned probe may still be running, but at most one
\* probe's outcome can count.
CurrentProbes == {c \in Callers : permit[c].kind = "probe" /\ permit[c].generation = generation}
AtMostOneProbe ==
    /\ probesInFlight = (IF Probing THEN 1 ELSE 0)
    /\ Cardinality(CurrentProbes) <= 1
    /\ CurrentProbes # {} => Probing

\* An outcome recorded against a permit from an earlier state changes nothing.  Counted against the ghost generation, not
\* the epoch, so that a model whose epoch stops moving cannot hide it.
StaleOutcomeIgnored == staleOutcomesApplied = 0

\* The code's rules for which step may move which circuit (CircuitBreaker.scala, trip, resetIf, transitionToHalfOpen).
ReasonStep ==
    LET o == st.circuit
        n == st'.circuit
        eventReset == \E s \in Signals : pc[s] = "SCas" /\ op[s].kind = "Reset" /\ "External" \in op[s].openers
    IN  /\ (o.kind = "Open" /\ n.kind = "Closed") => o.reason # "Failures" /\ (o.reason = "External" => eventReset)
        /\ (o.kind = "Open" /\ n.kind = "HalfOpen") => (n.probing => resetElapsed) /\ (~n.probing => o.reason # "Failures")
        /\ (o.kind = "Open" /\ n.kind = "Open") => n.reason = o.reason \/ (o.reason = "Polled" /\ n.reason = "External")
ReasonRespected == [][ReasonStep]_st

\* An open circuit is stale exactly when a stale trip opened it and no worse trip has landed since.
StaleFlag == st.circuit.kind = "Open" => (st.circuit.stale <=> staleAllowed)

\* A dead probe cannot pin the breaker.
SlotFreed == Probing ~> ~Probing

\* The race each property is about, stated as if it could not happen.  witness.py requires each of these violated, so
\* that a shrinking model cannot make a property above hold vacuously.
LostCasNeverHappens ==
    /\ \A c \in Callers : pc[c] \in {"CAcquireCas", "CRecordCas"} => st = snap[c]
    /\ \A s \in Signals : pc[s] = "SCas" => st = sigSnap[s]
OutcomesAreNeverLate == \A c \in Callers : pc[c] = "CRecordRead" => permit[c].epoch = st.epoch
SlotNeverWedges == ~Wedged
ResetNeverMeetsFailures == ~\E s \in Signals : pc[s] = "SRead" /\ op[s].kind = "Reset" /\ st.circuit.reason = "Failures"
NoTakeover == [][~(Probing /\ Probing' /\ generation' # generation)]_generation
ProbesNeverClose == [][~(st.circuit.kind = "HalfOpen" /\ st'.circuit.kind = "Closed")]_st
PollNeverHandsOverStale ==
    [][~(st.circuit.reason = "Polled" /\ st'.circuit.reason = "External" /\ st'.circuit.stale)]_st

================================================================================
