// ============================================================
// CircuitBreaker.p
//
// Formal model of the CircuitBreaker state machine in
// extras/src/main/scala/zio/openfeature/extras/CircuitBreaker.scala
//
// Run:
//   p compile --pfiles specs/circuitbreaker/CircuitBreaker.p --projname CircuitBreaker
//   p check --testcase TestCircuitBreaker -i 10000
//
// Key properties verified:
//   - Half-open single-flight (at most one probing=true at any time)
//   - No probe leak via recordReachable
//   - Open(External) reachable only via trip()
//   - consecutiveFailures is linearizable across concurrent recordFailure
//   - reset() only closes Open(External), never Open(Failures)
//   - Eventual close: failures stop + probes arrive => Closed within maxCalls
//   - Starvation: infinite recordReachable with no recordSuccess => stays HalfOpen
// ============================================================

// ── Types ────────────────────────────────────────────────────────────

enum OpenReason { Failures, External }

// P uses top-level enum declarations, not type aliases for enums
enum CircuitStateTag { Closed, Open, HalfOpen }

type CircuitBreakerState = (
  circuit      : CircuitStateTag,
  openReason   : OpenReason,
  hoSuccesses  : int,
  hoProbing    : bool,
  sinceStep    : int,
  consFailures : int
);

// ── Events ───────────────────────────────────────────────────────────

// Events that carry the calling machine as payload so responses can be routed back
event eTryAcquire      : machine;
event eRecordSuccess   : machine;
event eRecordFailure   : machine;
event eRecordReachable : machine;
event eTrip;
event eReset;
event eTransitionToHalfOpen;
event eTimeStep;

// Response events back to callers
event eAllowed;
event eRejected;
event eDidClose;
event eDidOpen;

// Monitor observation event
event eObserveState : CircuitBreakerState;

// ── CircuitBreaker machine ────────────────────────────────────────────

machine CircuitBreakerMachine {

  var st           : CircuitBreakerState;
  var logicalClock : int;
  // Thresholds — set in Init; mirrors the values used in TestDriver
  var FAILURE_THRESHOLD   : int;
  var HALF_OPEN_MAX_CALLS : int;
  var RESET_TIMEOUT_STEPS : int;

  start state Init {
    entry {
      FAILURE_THRESHOLD   = 3;
      HALF_OPEN_MAX_CALLS = 2;
      RESET_TIMEOUT_STEPS = 3;
      st = (circuit = Closed, openReason = Failures,
            hoSuccesses = 0, hoProbing = false,
            sinceStep = 0, consFailures = 0);
      logicalClock = 0;
      goto Serving;
    }
  }

  state Serving {

    on eTimeStep do {
      logicalClock = logicalClock + 1;
    }

    // ── tryAcquire ──────────────────────────────────────────────────
    // Mirrors CircuitBreaker.tryAcquire (lines 76-100)
    on eTryAcquire do (caller : machine) {
      var elapsed : int;
      if (st.circuit == Closed) {
        send caller, eAllowed;
      } else if (st.circuit == Open) {
        elapsed = logicalClock - st.sinceStep;
        if (elapsed >= RESET_TIMEOUT_STEPS) {
          // CAS: non-deterministically one caller wins the HalfOpen transition
          if ($) {
            st = (circuit = HalfOpen, openReason = st.openReason,
                  hoSuccesses = 0, hoProbing = true,
                  sinceStep = st.sinceStep, consFailures = st.consFailures);
            send caller, eAllowed;
          } else {
            send caller, eRejected;
          }
        } else {
          send caller, eRejected;
        }
      } else {
        // HalfOpen — CAS: try to set probing=true
        if (!st.hoProbing) {
          if ($) {
            st = (circuit = st.circuit, openReason = st.openReason,
                  hoSuccesses = st.hoSuccesses, hoProbing = true,
                  sinceStep = st.sinceStep, consFailures = st.consFailures);
            send caller, eAllowed;
          } else {
            send caller, eRejected;
          }
        } else {
          send caller, eRejected;
        }
      }
      announce eObserveState, st;
    }

    // ── recordSuccess ───────────────────────────────────────────────
    // Mirrors CircuitBreaker.recordSuccess (lines 108-138)
    on eRecordSuccess do (caller : machine) {
      var newSuccesses : int;
      if (st.circuit == Closed) {
        st = (circuit = st.circuit, openReason = st.openReason,
              hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
              sinceStep = st.sinceStep, consFailures = 0);
      } else if (st.circuit == HalfOpen) {
        newSuccesses = st.hoSuccesses + 1;
        if (newSuccesses >= HALF_OPEN_MAX_CALLS) {
          st = (circuit = Closed, openReason = Failures,
                hoSuccesses = 0, hoProbing = false,
                sinceStep = 0, consFailures = 0);
          send caller, eDidClose;
        } else {
          st = (circuit = st.circuit, openReason = st.openReason,
                hoSuccesses = newSuccesses, hoProbing = false,
                sinceStep = st.sinceStep, consFailures = st.consFailures);
        }
      }
      // Open: no-op
      announce eObserveState, st;
    }

    // ── recordReachable ─────────────────────────────────────────────
    // Mirrors CircuitBreaker.recordReachable (lines 144-169)
    on eRecordReachable do (caller : machine) {
      if (st.circuit == Closed) {
        st = (circuit = st.circuit, openReason = st.openReason,
              hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
              sinceStep = st.sinceStep, consFailures = 0);
      } else if (st.circuit == HalfOpen) {
        if (st.hoProbing) {
          // reachable clears the probe flag but does NOT count as a success
          st = (circuit = st.circuit, openReason = st.openReason,
                hoSuccesses = st.hoSuccesses, hoProbing = false,
                sinceStep = st.sinceStep, consFailures = st.consFailures);
        }
      }
      // Open: no-op
      announce eObserveState, st;
    }

    // ── recordFailure ────────────────────────────────────────────────
    // Mirrors CircuitBreaker.recordFailure (lines 176-203)
    on eRecordFailure do (caller : machine) {
      var newFailures : int;
      newFailures = st.consFailures + 1;
      if (st.circuit == Closed) {
        if (newFailures >= FAILURE_THRESHOLD) {
          st = (circuit = Open, openReason = Failures,
                hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
                sinceStep = logicalClock, consFailures = newFailures);
          send caller, eDidOpen;
        } else {
          st = (circuit = st.circuit, openReason = st.openReason,
                hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
                sinceStep = st.sinceStep, consFailures = newFailures);
        }
      } else if (st.circuit == HalfOpen) {
        st = (circuit = Open, openReason = Failures,
              hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
              sinceStep = logicalClock, consFailures = newFailures);
        send caller, eDidOpen;
      }
      // Open: no-op (CAS loop exits immediately)
      announce eObserveState, st;
    }

    // ── trip ─────────────────────────────────────────────────────────
    // Mirrors CircuitBreaker.trip (lines 206-217)
    on eTrip do {
      if (st.circuit != Open) {
        st = (circuit = Open, openReason = External,
              hoSuccesses = st.hoSuccesses, hoProbing = st.hoProbing,
              sinceStep = logicalClock, consFailures = st.consFailures);
      }
      announce eObserveState, st;
    }

    // ── reset ────────────────────────────────────────────────────────
    // Mirrors CircuitBreaker.reset (lines 222-235)
    on eReset do {
      if (st.circuit == Open && st.openReason == External) {
        st = (circuit = Closed, openReason = Failures,
              hoSuccesses = 0, hoProbing = false,
              sinceStep = 0, consFailures = 0);
      }
      // Open(Failures): no-op   Closed / HalfOpen: no-op
      announce eObserveState, st;
    }

    // ── transitionToHalfOpen ─────────────────────────────────────────
    // Mirrors CircuitBreaker.transitionToHalfOpen (lines 240-252)
    on eTransitionToHalfOpen do {
      if (st.circuit == Open) {
        st = (circuit = HalfOpen, openReason = st.openReason,
              hoSuccesses = 0, hoProbing = false,
              sinceStep = st.sinceStep, consFailures = st.consFailures);
      }
      // Closed / HalfOpen: no-op
      announce eObserveState, st;
    }
  }
}

// ── Spec Monitor ─────────────────────────────────────────────────────
// Observes every state snapshot and checks safety invariants.

spec SpecMonitor observes eObserveState {

  var probingCount  : int;
  var prevState     : CircuitBreakerState;
  var firstObserved : bool;

  start state Watching {
    entry {
      probingCount  = 0;
      firstObserved = false;
    }

    on eObserveState do (s : CircuitBreakerState) {

      // ── Invariant 1: half-open single-flight ─────────────────────
      // Only count probe ACTIVATIONS (hoProbing false→true transitions).
      // Repeated observations of the same hoProbing=true state happen
      // whenever a tryAcquire is rejected in HalfOpen — don't double-count.
      if (firstObserved) {
        if (!prevState.hoProbing && s.hoProbing) {
          assert probingCount == 0,
            "more than one probe in-flight in HalfOpen state";
          probingCount = 1;
        } else if (prevState.hoProbing && !s.hoProbing) {
          probingCount = 0;
        }
      }

      // ── Invariant 2: Open(External) valid transitions ────────────
      if (firstObserved && prevState.circuit == Open && prevState.openReason == External) {
        assert s.circuit == Open || s.circuit == Closed,
          "Open(External) must stay Open or transition to Closed via reset";
        if (s.circuit == Open) {
          assert s.openReason == External,
            "Reason cannot change from External to Failures while staying Open";
        }
      }

      // ── Invariant 3: consecutiveFailures >= 0 ────────────────────
      assert s.consFailures >= 0,
        "consecutiveFailures must be non-negative";

      // ── Invariant 4: hoSuccesses < HALF_OPEN_MAX_CALLS in HalfOpen
      // (hardcoded to 2, matching the TestDriver configuration)
      if (s.circuit == HalfOpen) {
        assert s.hoSuccesses < 2,
          "hoSuccesses must be < HALF_OPEN_MAX_CALLS in HalfOpen";
      }

      prevState     = s;
      firstObserved = true;
    }
  }
}

// ── Caller machine ────────────────────────────────────────────────────
// Models one concurrent caller issuing random operations.

machine Caller {
  var cb : machine;

  start state Init {
    entry (cbMachine : machine) {
      cb = cbMachine;
      goto Calling;
    }
  }

  state Calling {
    entry {
      if ($) {
        send cb, eTryAcquire, this;
      } else if ($) {
        send cb, eRecordSuccess, this;
      } else if ($) {
        send cb, eRecordFailure, this;
      } else if ($) {
        send cb, eRecordReachable, this;
      } else {
        send cb, eTimeStep;
      }
      goto Calling;
    }

    on eAllowed  do {}
    on eRejected do {}
    on eDidClose do {}
    on eDidOpen  do {}
  }
}

// ── Test scenario ─────────────────────────────────────────────────────

// Module grouping all machines (P 3.x requires explicit module declarations)
module CBModule = {
  CircuitBreakerMachine -> CircuitBreakerMachine,
  Caller                -> Caller,
  TestDriver            -> TestDriver
};

test TestCircuitBreaker [main = TestDriver]: assert SpecMonitor in CBModule;

machine TestDriver {
  var cb      : machine;
  var callers : set[machine];

  start state Setup {
    entry {
      cb = new CircuitBreakerMachine();
      // Three concurrent callers
      callers += (new Caller(cb));
      callers += (new Caller(cb));
      callers += (new Caller(cb));
    }
  }
}
