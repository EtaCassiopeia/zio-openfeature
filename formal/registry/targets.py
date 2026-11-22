"""Shortest behaviours of Generate.tla that reach the registry's rare leak shapes, for RegistryReplaySpec.

Usage: python3 targets.py <tla2tools.jar> <CommunityModules-deps.jar> <out dir>

Each target is a statement in Targets.tla that a step never happens, checked by TLC (1.8) on `GSpec` with the
target's topology and the smallest constants that reach it. Its counterexample is written to
`<out dir>/<topology>/target-<name>.ndjson` in the format `generate.py` writes (the replay reads a behaviour's topology
from that path segment). A statement TLC finds no counterexample to is reported by name and fails the run: the shape
is then outside what Generate.tla lets the replay drive.

The checked-in `mbt-regressions/` is this script's output; rerun it after a change to the model, Generate.tla or
Targets.tla.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

# (name, topology, statement, constants that differ from the defaults below). The replay always builds p1 to p3, so
# every target takes all three providers.
TARGETS = [
    ("retry-left-behind-released", "registry", "RegistryNeverReleasesARetrysWrapper",
     {"Instances": "{i1, i2}", "Domains": "{default, d1}", "Providers": "{p1, p2, p3}", "AllowTimeouts": "FALSE"}),
    ("sibling-wrapper-not-orphaned", "shared", "OwnerNeverOrphansASiblingsWrapper",
     {"Instances": "{i1, i2}", "Domains": "{default, d1}", "Providers": "{p1, p2, p3}"}),
    ("left-behind-released-at-owner-close", "shared", "OwnerCloseNeverReleasesLeftBehind",
     {"Instances": "{i1, i2}", "Domains": "{default, d1}", "Providers": "{p1, p2, p3}"}),
]

DEFAULTS = {
    "Default": "default",
    "MaxSwaps": "2",
    "None": "None",
    "Depth": "1000",  # Export writes nothing: no behaviour reaches it
    "CloseFrom": "0",  # a close may come at any level: the shortest behaviour wins
    "DefaultProvider": "p1",
    "AllowTimeouts": "TRUE",
}


def config(topology, statement, constants, out):
    values = dict(DEFAULTS, **constants, OutDir=f'"{out}"', Topology=f'"{topology}"')
    lines = "\n".join(f"    {k} = {v}" for k, v in values.items())
    return f"CONSTANTS\n{lines}\nSPECIFICATION GSpec\nPROPERTIES\n    {statement}\n"


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    tla_jar, cm_jar, out = (os.path.abspath(a) for a in sys.argv[1:4])
    here = os.path.dirname(os.path.abspath(__file__))
    missing = []
    with tempfile.TemporaryDirectory() as d:
        for name in os.listdir(here):
            if name.endswith(".tla"):
                shutil.copy(os.path.join(here, name), d)
        for name, topology, statement, constants in TARGETS:
            with open(os.path.join(d, "Target.cfg"), "w") as f:
                f.write(config(topology, statement, constants, d))
            trace = os.path.join(d, name + ".json")
            run = subprocess.run(
                ["java", "-XX:+UseParallelGC", "-cp", f"{tla_jar}{os.pathsep}{cm_jar}", "tlc2.TLC", "-workers", "1",
                 "-deadlock", "-dumpTrace", "json", trace, "-config", "Target.cfg", "Targets.tla"],
                cwd=d, capture_output=True, text=True, timeout=1800)
            if not os.path.exists(trace) or f"property {statement} is violated" not in run.stdout:
                print(f"{name}: TLC found no counterexample to {statement} ({topology}): the shape is outside what "
                      f"Generate.tla lets the replay drive\n" + run.stdout[-1500:])
                missing.append(name)
                continue
            with open(trace) as f:
                states = json.load(f)["counterexample"]["state"]
            os.makedirs(os.path.join(out, topology), exist_ok=True)
            with open(os.path.join(out, topology, f"target-{name}.ndjson"), "w") as f:
                for _, state in states:
                    f.write(json.dumps(state, separators=(",", ":")) + "\n")
            print(f"{name}: {len(states)} states ({topology})")
    if missing:
        sys.exit(f"no behaviour for: {', '.join(missing)}")


if __name__ == "__main__":
    main()
