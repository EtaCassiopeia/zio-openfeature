"""Shortest behaviours of Generate.tla that reach the cases random behaviours reach only by luck.

Usage: python3 targets.py <tla2tools.jar> <CommunityModules-deps.jar> <out dir>

For each target, TLC (1.8) checks a statement that the case never happens, on `GSpec`, and its counterexample is
written to `<out dir>/target-<name>.ndjson` in the format `generate.py` writes, for `CachingReplaySpec`. The first
three are witness.py's counterexamples (InFlightNeverStale, NoStrandedWrite, NoJoinerRetries), restricted as GNext is.
The checked-in `mbt-regressions/` is this script's output; rerun it after a change to the model or to `Generate.tla`.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

TARGETS = [
    ("in-flight-stale", "SettledInFlightNeverStale"),
    ("stranded-write", "SettledNoStrandedWrite"),
    ("joiner-held-for-retry", "JoinerNeverHeldForRetry"),
    ("joiner-retry-returns", "JoinerRetryNeverReturns"),
    ("hit-kept-ok", "KeptOkNeverHit"),
    ("soft-removed", "SoftNeverRemoved"),
    ("own-failure-removed", "OwnFailureNeverRemoved"),
    ("stranded-then-miss", "StrandedNeverMissed"),
    ("miss-beside-retry", "RetryNeverMissedBeside"),
]

CONFIG = """CONSTANTS
    MaxCallers = 3
    MaxChanges = 1
    OutDir = "{out}"
    Depth = 1
SPECIFICATION GSpec
INVARIANTS
    {statement}
"""


def main():
    tla_jar, cm_jar, out = (os.path.abspath(a) for a in sys.argv[1:4])
    here = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as d:
        for name in os.listdir(here):
            if name.endswith(".tla"):
                shutil.copy(os.path.join(here, name), d)
        for name, statement in TARGETS:
            with open(os.path.join(d, "Target.cfg"), "w") as f:
                f.write(CONFIG.format(out=d, statement=statement))
            trace = os.path.join(d, name + ".json")
            run = subprocess.run(
                ["java", "-XX:+UseParallelGC", "-cp", f"{tla_jar}{os.pathsep}{cm_jar}", "tlc2.TLC", "-workers", "1",
                 "-deadlock", "-dumpTrace", "json", trace, "-config", "Target.cfg", "Generate.tla"],
                cwd=d, capture_output=True, text=True, timeout=1800)
            if not os.path.exists(trace) or f"Invariant {statement} is violated" not in run.stdout:
                sys.exit(f"{name}: TLC found no counterexample to {statement}:\n" + run.stdout[-1500:])
            with open(trace) as f:
                states = json.load(f)["counterexample"]["state"]
            with open(os.path.join(out, f"target-{name}.ndjson"), "w") as f:
                for _, state in states:
                    f.write(json.dumps(state, separators=(",", ":")) + "\n")
            print(f"{name}: {len(states)} states")


if __name__ == "__main__":
    main()
