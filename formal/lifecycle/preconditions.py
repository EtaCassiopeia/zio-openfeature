"""Check that every library precondition a recorded run can show is exercised by a trace fixture.

Usage: python3 preconditions.py <tla2tools.jar> <CommunityModules-deps.jar>.  For each precondition below, the
precondition is deleted from Lifecycle.tla and its reject fixture under traces/reject is validated against the result:
the fixture, rejected as the model stands, must now be accepted, which shows it is the run that precondition rules
out.  A fixture that stays rejected
means no fixture exercises the precondition.  Exits non-zero when one is not exercised.

The preconditions recorded runs cannot show (the recorder stops at ScopeClose, and ShutdownDone only follows
ShutdownStart in the code) are checked elsewhere; formal/lifecycle/README.md, "Preconditions", says by what.
"""
import os
import shutil
import sys
import tempfile

import trace_labels
import validate

here = os.path.dirname(os.path.abspath(__file__))

# (action, precondition, the text it is deleted from, that text without it, the fixture it rules out)
preconditions = [
    ("SwapStart", 'lock = "free"',
     '    /\\ lock = "free" /\\ ~shutDown /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
     '    /\\ ~shutDown /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
     "swap-inside-a-swap"),
    ("SwapStart", "~shutDown",
     '    /\\ lock = "free" /\\ ~shutDown /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
     '    /\\ lock = "free" /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
     "swap-after-shutdown"),
    ("SwapStart", "inits[p] = 0",
     '    /\\ p # bound /\\ inits[p] = 0\n    /\\ ~\\E q \\in All : prevOf[q] = p /\\ inits[q] > 0\n',
     '    /\\ p # bound\n    /\\ ~\\E q \\in All : prevOf[q] = p /\\ inits[q] > 0\n',
     "swap-to-a-provider-still-initializing"),
    ("SwapStart", "~\\E q : prevOf[q] = p /\\ inits[q] > 0",
     '    /\\ ~\\E q \\in All : prevOf[q] = p /\\ inits[q] > 0\n    /\\ ~oldPending[p]\n',
     '    /\\ ~oldPending[p]\n',
     "swap-back-to-a-provider-replaced-mid-init"),
    ("SwapStart", "~oldPending[p]",
     '    /\\ ~oldPending[p]\n    /\\ ~\\E o \\in orphans : o.prev = p\n',
     '    /\\ ~\\E o \\in orphans : o.prev = p\n',
     "swap-back-before-the-sdk-shuts-it-down"),
    ("ShutdownStart", 'lock = "free"',
     'ShutdownStart ==\n    /\\ lock = "free"\n',
     'ShutdownStart ==\n',
     "shutdown-inside-a-swap"),
    ("CallerInterrupt", "Interruptible = {}",
     'Interruptible == {}',
     'Interruptible == {"reconcile"}',
     "swap-without-reconcile"),
    ("ScopeClose", 'lock = "free"',
     'ScopeClose ==\n    /\\ lock = "free" /\\ ~closed',
     'ScopeClose ==\n    /\\ ~closed',
     "swap-without-outcome"),
]


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    spec = open(os.path.join(here, "Lifecycle.tla")).read()
    unexercised = []
    for action, pre, old, new, fixture in preconditions:
        if spec.count(old) != 1:
            sys.exit(f"{action} {pre}: its text no longer occurs exactly once in Lifecycle.tla")
        actions = trace_labels.labels(trace_labels.read(os.path.join(here, "traces", "reject", fixture + ".ndjson")))
        validate.here = here
        baseline, why = validate.check(tla_jar, cm_jar, actions)
        if baseline == validate.TOOL:
            sys.exit(f"reject/{fixture}: {why}")
        if baseline != validate.REJECTED:
            sys.exit(f"reject/{fixture}: {baseline} by the model as it stands, so it shows nothing")
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "Lifecycle.tla"), "w") as f:
                f.write(spec.replace(old, new))
            shutil.copy(os.path.join(here, "TraceValidation.tla"), d)
            validate.here = d
            verdict, why = validate.check(tla_jar, cm_jar, actions)
        if verdict == validate.TOOL:
            sys.exit(f"reject/{fixture} without {action} {pre}: {why}")
        ok = verdict == validate.ACCEPTED
        print(f"{action} {pre}: {'exercised by' if ok else 'NOT exercised by'} reject/{fixture} ({verdict})")
        if not ok:
            unexercised.append(f"{action} {pre}")
    if unexercised:
        sys.exit("not exercised: " + ", ".join(unexercised))


if __name__ == "__main__":
    main()
