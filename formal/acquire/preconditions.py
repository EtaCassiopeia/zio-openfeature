"""Check that every guard the trace check rests on is exercised by a reject fixture, both ways.

Usage: python3 preconditions.py <tla2tools.jar> <CommunityModules-deps.jar>.  For each guard below (an action's
precondition in Acquire.tla, or a check AcquireTrace.tla adds), its reject fixture under traces/reject must be rejected
as the model stands and accepted once the guard is deleted: that shows the fixture is the run the guard rules out, and
that the check is not passing for some other reason. Exits non-zero when a guard is not exercised.
"""
import os
import shutil
import sys
import tempfile

import trace_labels
import validate
from tracecheck import ACCEPTED, REJECTED, TOOL

here = os.path.dirname(os.path.abspath(__file__))
TRACE = "AcquireTrace.tla"

# (the guard, the text it is deleted from, that text without it, the fixture it rules out, the file)
guards = [
    ('AttemptOk pc = "attempt"', 'AttemptOk ==\n    /\\ pc = "attempt"\n', 'AttemptOk ==\n', "accepted-twice"),
    ('VerifyFails pc = "attempt"', 'VerifyFails ==\n    /\\ pc = "attempt"\n', 'VerifyFails ==\n',
     "rejected-after-accept"),
    ('Compose pc = "accepted"', 'Compose ==\n    /\\ pc = "accepted"\n', 'Compose ==\n', "compose-before-accept"),
    ('InitOk pc = "composed"', 'InitOk ==\n    /\\ pc = "composed"\n', 'InitOk ==\n', "init-before-compose"),
    ('InitFails pc = "composed"', 'InitFails ==\n    /\\ pc = "composed"\n', 'InitFails ==\n', "init-fails-twice"),
    ('SwitchOk pc = "initialized"', 'SwitchOk ==\n    /\\ pc = "initialized"\n', 'SwitchOk ==\n',
     "switch-before-init"),
    ('SwitchRefused pc = "initialized"', 'SwitchRefused ==\n    /\\ pc = "initialized"\n', 'SwitchRefused ==\n',
     "switch-refused-before-init"),
    ('Timeout pc = "attempt"', 'Timeout ==\n    /\\ Fix /\\ pc = "attempt"\n', 'Timeout ==\n    /\\ Fix\n',
     "timeout-after-accept"),
    ('AcquireFails pc = "attempt"', 'AcquireFails ==\n    /\\ Fix /\\ pc = "attempt"\n',
     'AcquireFails ==\n    /\\ Fix\n', "acquire-fails-after-accept"),
    ('LateArrive ab[a].st = "running"', 'LateArrive(a) ==\n    /\\ ab[a].st = "running"\n', 'LateArrive(a) ==\n',
     "late-arrival-twice"),
    ('LateFail ab[a].st = "running"', 'LateFail(a) ==\n    /\\ ab[a].st = "running"\n', 'LateFail(a) ==\n',
     "late-fail-twice"),
    ("FixRelease1 rel = 0", "/\\ Fix /\\ rel = 0 /\\ rel' = 1\n    /\\ (Interrupt",
     "/\\ Fix /\\ rel' = 1\n    /\\ (Interrupt", "release-interrupt-twice"),
    ("FixRelease2 rel = 1", "FixRelease2 == Fix /\\ rel = 1 /\\ ApiShutdown(2)",
     "FixRelease2 == Fix /\\ ApiShutdown(2)", "api-shutdown-twice"),
    ("FixRelease3 rel = 2", "/\\ Fix /\\ rel = 2 /\\ rel' = 3", "/\\ Fix /\\ rel' = 3", "owned-released-twice"),
    ("the attempt in progress", "InProgress == att = Step.attempt", "InProgress == TRUE", "accepted-out-of-turn",
     TRACE),
    ("what the release shut down", "FixRelease3 /\\ owned = Step.owned", "FixRelease3", "owned-real-after-compose",
     TRACE),
    ("End: the release is over", "    /\\ Finished\n", "", "end-before-release", TRACE),
    ("End: the provider's shutdowns", "    /\\ Observed(Step.shuts, shutdowns)\n", "", "provider-never-shut-down",
     TRACE),
    ("End: a late provider's shutdowns", "    /\\ \\A a \\in Attempts : Observed(Step.abShuts[a], ab[a].shuts)\n", "",
     "late-arrival-not-shut-down", TRACE),
    ("End: the stack's shutdown", "    /\\ Observed(Step.stack, IF stackShut THEN 1 ELSE 0)\n", "",
     "stack-not-shut-down", TRACE),
    ("End: the resource closed", "    /\\ ObservedFlag(Step.closed, ClosedOf(Step.resource))\n", "",
     "resource-closed-under-its-constructor", TRACE),
    ("End: a shutdown after the resource closed",
     "    /\\ ObservedFlag(Step.shutAfterClose, ShutAfterCloseOf(Step.resource))\n", "",
     "shut-down-after-its-resource", TRACE),
]


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    unexercised = []
    for name, old, new, fixture, *file in guards:
        file = file[0] if file else "Acquire.tla"
        spec = open(os.path.join(here, file)).read()
        if spec.count(old) != 1:
            sys.exit(f"{name}: its text no longer occurs exactly once in {file}")
        actions = trace_labels.labels(trace_labels.read(os.path.join(here, "traces", "reject", fixture + ".ndjson")))
        validate.here = here
        baseline, why, _ = validate.check(tla_jar, cm_jar, actions)
        if baseline == TOOL:
            sys.exit(f"reject/{fixture}: {why}")
        if baseline != REJECTED:
            sys.exit(f"reject/{fixture}: {baseline} by the model as it stands, so it shows nothing")
        with tempfile.TemporaryDirectory() as d:
            for f in ("Acquire.tla", TRACE):
                shutil.copy(os.path.join(here, f), d)
            with open(os.path.join(d, file), "w") as f:
                f.write(spec.replace(old, new))
            validate.here = d
            verdict, why, _ = validate.check(tla_jar, cm_jar, actions)
        if verdict == TOOL:
            sys.exit(f"reject/{fixture} without {name}: {why}")
        ok = verdict == ACCEPTED
        print(f"{name}: {'exercised by' if ok else 'NOT exercised by'} reject/{fixture} ({verdict})")
        if not ok:
            unexercised.append(name)
    if unexercised:
        sys.exit("not exercised: " + ", ".join(unexercised))


if __name__ == "__main__":
    main()
