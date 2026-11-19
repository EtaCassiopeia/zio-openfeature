"""Check that every recorded run of several instances on one API is a behaviour of the registry model.

Usage: python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> <trace dir or files...> [--reached]
       python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> --selftest traces

Each trace (ndjson of probe points, tagged per instance) is turned into model action labels (trace_labels.py), then TLC
searches RegistryTrace.tla for a behaviour of Registry that takes those steps in that order and ends with the shutdowns
the run counted. The model's constants come from the run: its instances, domains and providers, and as many swaps as it
started. The verdicts, the summary and the exit status are formal/tracecheck.py's; a run the model does not cover is
skipped with its reason, counted per spec (the part of a trace's name before its id). `--selftest` checks the checker:
every trace under `traces/accept` must be accepted and every one under `traces/reject` rejected. `--reached` also fails
when an action of the model other than Lookup is taken by no accepted run.
"""
import os
import sys
from collections import Counter

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(here))

import tracecheck  # noqa: E402
from trace_labels import labels, read  # noqa: E402

ACTIONS = ("Check", "Create", "Complete", "BuildFail", "Evict", "ReleaseReplaced", "SwapStart", "SwapDone", "SwapOldShut",
           "SwapFailMark", "SwapFail", "SwapTimeout", "InitSettle", "UserBind", "FailMark", "FailDecide", "CloseInstance",
           "OwnerClose", "RegistryClose")


def strings(xs):
    return "{" + ", ".join(f'"{x}"' for x in xs) + "}"


def check(tla_jar, cm_jar, actions):
    """(verdict, why, TLC's output) for one run's labels."""
    c = actions[0]["constants"]

    def cfg(trace):
        return f"""CONSTANTS
    Instances = {strings(c["instances"])}
    Domains = {strings(c["domains"])}
    Default = "default"
    Providers = {strings(c["providers"])}
    MaxSwaps = {c["swaps"]}
    None = "none"
    TraceFile = "{trace}"
INIT TInit
NEXT TraceStep
INVARIANT TraceNotDone
"""
    stripped = [{k: v for k, v in a.items() if k != "constants"} for a in actions]
    return tracecheck.tlc(tla_jar, cm_jar, here, ("Registry.tla", "RegistryTrace.tla"), "RegistryTrace", cfg,
                          stripped)


def spec_of(path):
    return os.path.basename(path).rsplit("-", 1)[0]


def per_spec(results):
    """Print, per spec, how many runs were checked and how many skipped for each reason."""
    specs = sorted({spec_of(r[0]) for r in results})
    for spec in specs:
        mine = [r for r in results if spec_of(r[0]) == spec]
        skipped = Counter(r[3] for r in mine if r[2] is None)
        accepted = sum(1 for r in mine if r[2] == tracecheck.ACCEPTED)
        line = f"{spec}: {len(mine)} runs, {accepted} accepted, {sum(skipped.values())} not modelled"
        print(line + "".join(f"; {why}: {k}" for why, k in sorted(skipped.items())))


def reached(results):
    """Non-zero when a model action other than Lookup is in no accepted run."""
    taken = {a["action"] for r in results if r[2] == tracecheck.ACCEPTED for a in r[1]}
    never = [a for a in ACTIONS if a not in taken]
    print("NEVER TAKEN by an accepted run: " + ", ".join(never) if never
          else "Every action but Lookup is taken by some accepted run.")
    return 1 if never else 0


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    args = sys.argv[3:]

    def label(path):
        return labels(read(path))

    def checked(actions):
        return check(tla_jar, cm_jar, actions)

    if args[0] == "--selftest":
        sys.exit(tracecheck.selftest(args[1], label, checked))
    want_reached = "--reached" in args
    results = tracecheck.run(tracecheck.collect([a for a in args if a != "--reached"]), label, checked)
    status = tracecheck.report(results)
    per_spec(results)
    if want_reached:
        status |= reached(results)
    sys.exit(status)


if __name__ == "__main__":
    main()
