"""Check that every guard the trace check rests on is exercised by a reject fixture, both ways.

Usage: python3 preconditions.py <tla2tools.jar> <CommunityModules-deps.jar>.  For each guard below (an action's
precondition in Registry.tla, or a check RegistryTrace.tla adds), its reject fixture under traces/reject must be
rejected as the model stands and accepted once the guard is deleted: that shows the fixture is the run the guard rules
out, and that the check is not passing for some other reason. Exits non-zero when a guard is not exercised.
"""
import os
import shutil
import sys
import tempfile

import trace_labels
import validate
from tracecheck import ACCEPTED, REJECTED, TOOL

here = os.path.dirname(os.path.abspath(__file__))
TRACE = "RegistryTrace.tla"

# (the guard, the text it is deleted from, that text without it, the fixture it rules out, the file)
guards = [
    ("Create: one owner", "    /\\ own => owner = None\n", "", "two-owners"),
    # Each instance holds its own swap lock: its swaps never overlap, though different instances' may.
    ("SwapStart: one swap per instance", '    /\\ phase[i] = "open"\n    /\\ i \\notin swapping\n',
     '    /\\ phase[i] = "open"\n', "one-instance-two-swaps-at-once"),
    ("Create: the slot holds no other provider, unless a failed registry build left it",
     "    /\\ \\/ bound[d] \\in {None, p}\n"
     "       \\/ /\\ \\E j \\in Instances : domainOf[j] = d /\\ phase[j] = \"evicted\"\n"
     "          /\\ \\A j \\in Instances \\ {i} : domainOf[j] = d => phase[j] = \"evicted\"\n"
     "          /\\ bound[d] \\notin userOwned\n", "", "build-binds-over-a-user-provider"),
    ("BuildFail: the provider it replaced released first",
     "    /\\ ~\\E q \\in Providers : /\\ replaced[q] = builtOn[i]\n"
     "                            /\\ q \\in registered\n"
     "                            /\\ q \\notin userOwned\n"
     "                            /\\ SlotsOf(q) = {}\n", "", "registry-retry-fails-too-and-releases-the-first-only-at-close"),
    ("SwapDone: no failure marked", "    /\\ i \\in swapping\n    /\\ failedOn[i] # bound[d]\n", "    /\\ i \\in swapping\n",
     "succeeds-after-its-failure"),
    ("UserBind: an empty slot", "    /\\ bound[d] = None\n    /\\ \\/ p \\notin everBound\n",
     "    /\\ \\/ p \\notin everBound\n",
     "user-binds-a-held-slot"),
    ("InitSettle: the orphan list shuts down an orphan its failed replacement leaves",
     "    /\\ shuts' = [q \\in Providers |-> IF q \\in sdk \\cup lib THEN shuts[q] + 1 ELSE shuts[q]]\n",
     "    /\\ shuts' = [q \\in Providers |-> IF q \\in sdk THEN shuts[q] + 1 ELSE shuts[q]]\n",
     "timed-out-swap-fails-and-the-old-provider-is-left-running"),
    ("FailMark: once per provider",
     "    /\\ failedOn[i] # bound[domainOf[i]]\n    /\\ bound[domainOf[i]] \\in registered\n",
     "    /\\ bound[domainOf[i]] \\in registered\n", "failure-marked-twice"),
    # Anchored on the owner's phase: RegistryClose has the same deciding and lock guards.
    ("OwnerClose: no failure deciding",
     '    /\\ phase[owner] = "open"\n    /\\ swapping = {}\n    /\\ deciding = {}\n',
     '    /\\ phase[owner] = "open"\n    /\\ swapping = {}\n', "owner-closes-while-a-failure-decides"),
    ("FailDecide: the model's decision", "         /\\ Agrees(p, LibraryShuts(n, p), Step.shut)\n", "",
     "failure-defers-to-nobody", TRACE),
    ("SwapFail: the model's decision", 'Lib("SwapFail", SwapDecided(Step.instance) /\\ SwapFail(Step.instance))',
     'Lib("SwapFail", SwapFail(Step.instance))', "rollback-defers-to-a-failed-sibling-and-shuts-it-down", TRACE),
    ("FailMark: once per registration, one that has ended too",
     "         /\\ failedOn[n] # bound[domainOf[n]]\n         /\\ absorbed'", "         /\\ absorbed'",
     "second-deadline-on-one-registration", TRACE),
    ("SwapTimeout: the run's orphan record", "Lib(\"SwapTimeout\", OrphanDecided(Step.instance) /\\ ",
     "Lib(\"SwapTimeout\", ", "owner-timed-out-swap-records-no-orphan", TRACE),
    ("End: the shutdowns",
     "           Step.shuts[q] < 0 \\/ (Step.shuts[q] <= total[q] /\\ total[q] <= Step.shuts[q] + Step.late[q])",
     "           TRUE", "provider-shut-down-twice", TRACE),
]


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    unexercised = []
    for name, old, new, fixture, *file in guards:
        file = file[0] if file else "Registry.tla"
        spec = open(os.path.join(here, file)).read()
        if spec.count(old) != 1:
            sys.exit(f"{name}: its text no longer occurs exactly once in {file}")
        actions = trace_labels.labels(trace_labels.read(os.path.join(here, "traces", "reject", fixture + ".ndjson")))
        if isinstance(actions, str):
            sys.exit(f"reject/{fixture}: skipped ({actions}), so it shows nothing")
        validate.here = here
        baseline, why, _ = validate.check(tla_jar, cm_jar, actions)
        if baseline == TOOL:
            sys.exit(f"reject/{fixture}: {why}")
        if baseline != REJECTED:
            sys.exit(f"reject/{fixture}: {baseline} by the model as it stands, so it shows nothing")
        with tempfile.TemporaryDirectory() as d:
            for f in ("Registry.tla", TRACE):
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
