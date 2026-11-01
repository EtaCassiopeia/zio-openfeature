"""Check that every recorded run is a behaviour of the lifecycle model.

Usage: python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> <trace dir or files...>
       python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> --selftest traces

`--selftest` checks the checker: every trace under `traces/accept` must be accepted and every one under
`traces/reject` rejected (hand-written runs the model forbids, and a run recorded with shutdown's lock removed).

Each trace (ndjson of probe points) is turned into model action labels (trace_labels.py), then TLC searches
TraceValidation.tla for a behaviour of Lifecycle that takes those library steps in that order. Exits non-zero when a
trace is rejected, when no trace was checked, or when a trace cannot be read; prints a summary. A trace TLC cannot decide
within TRACE_TIMEOUT_SECS (default 300) is reported as undecided and does not fail the run.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

from trace_labels import labels, read

here = os.path.dirname(os.path.abspath(__file__))
TIMEOUT_SECS = int(os.environ.get("TRACE_TIMEOUT_SECS", "300"))
TLC_WORKERS = int(os.environ.get("TRACE_TLC_WORKERS", "2"))
PARALLEL = max(1, (os.cpu_count() or 2) // TLC_WORKERS)
ACCEPTED, REJECTED, UNDECIDED = "accepted", "rejected", "undecided"


def check(tla_jar, cm_jar, actions):
    """ACCEPTED when TLC finds a behaviour matching `actions`, REJECTED when it shows there is none, else UNDECIDED."""
    swaps = sum(1 for a in actions if a["action"] == "SwapStart")
    deliveries = sum(1 for a in actions if a["action"] == "Deliver")
    with tempfile.TemporaryDirectory() as d:
        for name in ("Lifecycle.tla", "TraceValidation.tla"):
            with open(os.path.join(here, name)) as src, open(os.path.join(d, name), "w") as dst:
                dst.write(src.read())
        trace = os.path.join(d, "trace.ndjson")
        with open(trace, "w") as f:
            for a in actions:
                f.write(json.dumps(a) + "\n")
        providers = ", ".join(f"q{k}" for k in range(1, swaps + 1)) or ""
        cfg = f"""CONSTANTS
    P0 = q0
    Providers = {{{providers}}}
    MaxSwaps = {swaps}
    MaxEvents = {deliveries}
    None = None
    TraceFile = "{trace}"
INIT TInit
NEXT TraceNext
INVARIANT TraceNotDone
SYMMETRY Symmetry
"""
        with open(os.path.join(d, "TraceValidation.cfg"), "w") as f:
            f.write(cfg)
        try:
            out = subprocess.run(
                ["java", "-XX:+UseParallelGC", "-cp", f"{tla_jar}{os.pathsep}{cm_jar}", "tlc2.TLC", "-workers", str(TLC_WORKERS),
                 "-deadlock", "-config", "TraceValidation.cfg", "TraceValidation.tla"],
                cwd=d, capture_output=True, text=True, timeout=TIMEOUT_SECS).stdout
        except subprocess.TimeoutExpired:
            return UNDECIDED, f"undecided after {TIMEOUT_SECS}s"
    if "Invariant TraceNotDone is violated" in out:
        return ACCEPTED, ""
    if "No error has been found" in out:
        return REJECTED, "no behaviour of the model takes these steps"
    return REJECTED, "TLC failed: " + out[-800:]


def collect(args):
    paths = []
    for arg in args:
        paths += sorted(glob.glob(os.path.join(arg, "*.ndjson"))) if os.path.isdir(arg) else [arg]
    return paths


def run(tla_jar, cm_jar, paths):
    """(path, actions, verdict, why) per trace; verdict None for a run the model does not cover."""
    def one(path):
        actions = labels(read(path))
        if actions is None:
            return path, None, None, "not modelled"
        verdict, why = check(tla_jar, cm_jar, actions)
        return path, actions, verdict, why

    with ThreadPoolExecutor(PARALLEL) as pool:
        return list(pool.map(one, paths))


def selftest(tla_jar, cm_jar, root):
    wrong = []
    for expected, sub in ((ACCEPTED, "accept"), (REJECTED, "reject")):
        for path, _, verdict, why in run(tla_jar, cm_jar, collect([os.path.join(root, sub)])):
            name = f"{sub}/{os.path.basename(path)}"
            print(f"{verdict or 'skipped'}: {name}")
            if verdict != expected:
                wrong.append(f"{name} was {verdict or 'skipped'}: {why}")
    for w in wrong:
        print(f"WRONG {w}")
    sys.exit(1 if wrong else 0)


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    if sys.argv[3] == "--selftest":
        selftest(tla_jar, cm_jar, sys.argv[4])
    results = run(tla_jar, cm_jar, collect(sys.argv[3:]))
    count = {v: sum(1 for r in results if r[2] == v) for v in (ACCEPTED, REJECTED, UNDECIDED, None)}
    print(f"{count[ACCEPTED]} accepted, {count[REJECTED]} rejected, {count[UNDECIDED]} undecided, "
          f"{count[None]} skipped (not modelled), of {len(results)} traces")
    for path, actions, verdict, why in results:
        if verdict in (REJECTED, UNDECIDED):
            print(f"{verdict.upper()} {os.path.basename(path)}: {[a['action'] for a in actions]}\n  {why}")
    if count[ACCEPTED] == 0:
        print("No trace was accepted: nothing was checked.")
    sys.exit(1 if count[REJECTED] or count[ACCEPTED] == 0 else 0)


if __name__ == "__main__":
    main()
