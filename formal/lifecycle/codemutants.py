#!/usr/bin/env python3
"""Put each lifecycle bug the simulation's live invariant checks must catch into the code, and require the concurrent
simulation to turn red with that invariant named.

Usage: python3 codemutants.py. Run from anywhere; it runs sbt at the repository root.

The checks restate the model's safety invariants (`ModelInvariants` in the simulation module; README, "Checked on the
live record"). Each mutant is an exact edit of one source file, a bug the model's own mutants re-plant in the spec. The
simulation must fail, and its output must name the invariant (`model invariant: <name>`): another oracle catching the
bug as well is fine, this one must too. The source is restored after every mutant, whatever happens. Each mutant
recompiles core, so this runs on main and on demand, not on pull requests.

Not here: FinalNotReady's bug, a late event moving a shut-down instance's status. The simulation does shut instances down
mid-run, but a shutdown stops the event bridge before it tears down (its `stopped` flag drops even a handler the SDK is
already running), and between the shutdown's start and that, ShuttingDown is sticky, so no event moves the status after it:
re-planting that bug left the simulation green at five times its budget, over 57 mid-run shutdowns. The guard is pinned by
the core shutdown specs and by the model's own mutants instead.
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
INTERNAL = os.path.join(ROOT, "core", "src", "main", "scala", "zio", "openfeature", "internal")
TIMEOUT_SECS = int(os.environ.get("CODEMUTANTS_TIMEOUT_SECS", "1800"))

# (name, the invariant that must be named, the file, old, new, the simulation's budget scale)
MUTANTS = [
    ("bridge events move the status during a swap", "NotReadyDuringSwap", "ProviderStatusMachine.scala",
     "case _ if ctx.swapInProgress || ctx.shutdownCompleted => None",
     "case _ if ctx.shutdownCompleted => None", 1),
    ("a swap keeps the old provider installed", "BoundNotShutDown", "LifecycleState.scala",
     "installed = incoming,", "installed = state.installed,", 1),
    ("lowering the gate frees the lock but keeps the swap", "OneLockHolder", "LifecycleState.scala",
     "state.copy(gate = false, lock = Lock.Free, swap = None)", "state.copy(gate = false, lock = Lock.Free)", 1),
    ("lowering the gate leaves it up", "GateDownWhenIdle", "LifecycleState.scala",
     "state.copy(gate = false, lock = Lock.Free, swap = None)", "state.copy(gate = true, lock = Lock.Free, swap = None)",
     1),
]


def simulate(scale):
    """The simulation's exit code and output; a run over TIMEOUT_SECS counts as not caught."""
    env = dict(os.environ, ZOF_PBT_SCALE=str(scale))
    try:
        run = subprocess.run(
            ["sbt", "-Dsbt.server.autostart=false",
             "simulation/testOnly zio.openfeature.simulation.ConcurrentSimulationSpec"],
            cwd=ROOT, env=env, capture_output=True, text=True, timeout=TIMEOUT_SECS)
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return 124, out + f"\ntimed out after {TIMEOUT_SECS}s"
    return run.returncode, run.stdout + run.stderr


def main():
    if len(sys.argv) != 1:
        sys.exit(__doc__)
    sources = sorted({file for _, _, file, _, _, _ in MUTANTS})
    # A run killed before its `finally` leaves a mutant in a file: refuse to start from anything but the committed one.
    for file in sources:
        path = os.path.join(INTERNAL, file)
        if subprocess.run(["git", "-C", ROOT, "diff", "--quiet", "HEAD", "--", path]).returncode != 0:
            sys.exit(f"{file} differs from HEAD (a mutant left by an interrupted run?); restore it first")
    missed = []
    for name, invariant, file, old, new, scale in MUTANTS:
        path = os.path.join(INTERNAL, file)
        original = open(path).read()
        if original.count(old) != 1:
            sys.exit(f"{name}: the edit no longer applies to {file}; update it")
        try:
            with open(path, "w") as f:
                f.write(original.replace(old, new))
            code, out = simulate(scale)
        finally:
            with open(path, "w") as f:
                f.write(original)
        if "Compilation failed" in out:
            sys.exit(f"{name}: the mutant does not compile:\n{out[-3000:]}")
        if code != 0 and f"model invariant: {invariant}" in out:
            print(f"{name}: caught by {invariant}")
        else:
            print(f"{name}: NOT CAUGHT by {invariant}; exit {code}\n" + out[-3000:])
            missed.append(name)
    sys.exit(1 if missed else 0)


if __name__ == "__main__":
    main()
