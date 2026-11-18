"""Check that every recorded run is a behaviour of the lifecycle model.

Usage: python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> <trace dir or files...>
       python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> --selftest traces

`--selftest` checks the checker: every trace under `traces/accept` must be accepted and every one under
`traces/reject` rejected (hand-written runs the model forbids, and a run recorded with shutdown's lock removed).

Each trace (ndjson of probe points) is turned into model action labels (trace_labels.py), then TLC searches
TraceValidation.tla for a behaviour of Lifecycle that takes those library steps in that order. Exits non-zero when a
trace is rejected, when TLC fails (a tool error: it could not run, or its output says neither way), when no trace was
checked, or when a trace cannot be read; prints a summary. A trace TLC cannot decide
within TRACE_TIMEOUT_SECS (default 300) is reported as undecided and does not fail the run. The driver (the TLC call,
the verdicts, the summary and the selftest) is formal/tracecheck.py, which the acquire model's validator shares.
"""
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(here))

import tracecheck  # noqa: E402
from tracecheck import ACCEPTED, REJECTED, TOOL, UNDECIDED, collect  # noqa: E402,F401
from trace_labels import labels, read  # noqa: E402


def check(tla_jar, cm_jar, actions):
    """ACCEPTED when TLC finds a behaviour matching `actions`, REJECTED when it shows there is none, UNDECIDED when it
    runs out of time, else TOOL."""
    swaps = sum(1 for a in actions if a["action"] == "SwapStart")
    # The deliveries the run recorded, those the swap's gate dropped included: a reconcile may read a dropped one.
    deliveries = sum(1 for a in actions if a["action"] == "Deliver") + sum(len(a["dropped"]) for a in actions)
    # Only the shutdown behaviours the run observed: each one multiplies the search by the registrations.
    behaviours = ['"ok"']
    if any(a["threw"] > 0 for a in actions):
        behaviours.append('"throws"')
    if any(a["shuts"] > a["returned"] >= 0 for a in actions):
        behaviours.append('"hangs"')
    # A run that names its providers (q0, q1, ... by trace_labels.py) may name more of them than it swaps.
    named = {int(a[k][1:]) for a in actions for k in ("provider", "watch") if a.get(k)}
    count = max([swaps] + list(named))
    providers = ", ".join(f"q{k}" for k in range(1, count + 1)) or ""

    def cfg(trace):
        return f"""CONSTANTS
    P0 = q0
    Providers = {{{providers}}}
    MaxSwaps = {swaps}
    MaxEvents = {deliveries}
    MaxEvals = 0
    AllowDeath = FALSE
    Behaviours = {{{", ".join(behaviours)}}}
    None = None
    TraceFile = "{trace}"
INIT TInit
NEXT TraceStep
INVARIANT TraceNotDone
"""
    verdict, why, _ = tracecheck.tlc(tla_jar, cm_jar, here, ("Lifecycle.tla", "TraceValidation.tla"),
                                     "TraceValidation", cfg, actions)
    return verdict, why


def run(tla_jar, cm_jar, paths):
    """(path, actions, verdict, why) per trace; verdict None for a run the model does not cover."""
    return [r[:4] for r in tracecheck.run(paths, lambda p: labels(read(p)), lambda a: check(tla_jar, cm_jar, a))]


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])

    def label(path):
        return labels(read(path))

    def checked(actions):
        return check(tla_jar, cm_jar, actions)

    if sys.argv[3] == "--selftest":
        sys.exit(tracecheck.selftest(sys.argv[4], label, checked))
    sys.exit(tracecheck.report(tracecheck.run(collect(sys.argv[3:]), label, checked)))


if __name__ == "__main__":
    main()
