#!/usr/bin/env python3
"""Put each registry bug the shared-API simulation's live invariant checks must catch into the code, and require the
simulation to turn red with that invariant named.

Usage: python3 simmutants.py. Run from anywhere; it runs sbt at the repository root.

The checks restate the registry model's safety invariants (`RegistryInvariants` in the simulation module; README,
"Checked on live state"). Each mutant is an exact edit of one source file. `SharedApiSimulationSpec` must fail, and its
output must name one of the mutant's invariants (`registry invariant: <name>`, or the spec's own `RegisteredNotShutDown`):
another oracle catching the bug as well is fine, one of these must too. The source is restored after every mutant,
whatever happens. Each mutant recompiles core, so this runs on main and on demand, not on pull requests.

Not here: dropping the sibling check from the orphan list (`OrphanedProviders.heldByVisibleSlot`, the
`ApiInstances.boundInAnySlot` half). Only a timed-out swap records an orphan, and the shared-API plans have no stalled
`initialize()`; the registry replay's targets catch that mutant instead (`codemutants.py`).
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
MAIN = os.path.join(ROOT, "core", "src", "main", "scala", "zio", "openfeature")
TIMEOUT_SECS = int(os.environ.get("SIMMUTANTS_TIMEOUT_SECS", "1800"))

TEARDOWN = """        ZIO.when(tearDownApi)(
          ProviderRegistration.boundedApiShutdown(api) *> orphans.resolveAll *> ApiInstances.releaseLeftBehind(api)
        ) *>
"""

# (name, the invariants any of which must be named, the file, old, new)
MUTANTS = [
    ("the owner's close does not retire its siblings", {"ClosedApiServesNone"},
     os.path.join(MAIN, "internal", "ApiInstances.scala"),
     "ZIO.succeed(on(api).filterNot(_ eq except)).flatMap(ZIO.foreachParDiscard(_)(_.retire))",
     "ZIO.succeed(on(api).filterNot(_ eq except)).unit"),
    ("a non-owner's close shuts its slot's provider down", {"ForeignNeverShutByLibrary", "RegisteredNotShutDown"},
     os.path.join(MAIN, "FeatureFlagsLive.scala"), TEARDOWN,
     TEARDOWN + "        ZIO.when(!tearDownApi)(ZIO.attemptBlocking(sdkBound.shutdown()).ignore) *>\n"),
    ("a non-owner's close empties its slot", {"ClosedNonOwnerKeepsSlot"},
     os.path.join(MAIN, "FeatureFlagsLive.scala"), TEARDOWN,
     TEARDOWN + "        ZIO.when(!tearDownApi)(ZIO.attemptBlocking(domain.foreach(api.setProvider(_, "
                "new dev.openfeature.sdk.NoOpProvider()))).ignore) *>\n"),
]


def named(invariant, out):
    if invariant == "RegisteredNotShutDown":
        return "RegisteredNotShutDown (" in out
    return f"registry invariant: {invariant}" in out


def simulate():
    """The simulation's exit code and output; a run over TIMEOUT_SECS counts as not caught."""
    try:
        run = subprocess.run(
            ["sbt", "-Dsbt.server.autostart=false",
             "simulation/testOnly zio.openfeature.simulation.SharedApiSimulationSpec"],
            cwd=ROOT, capture_output=True, text=True, timeout=TIMEOUT_SECS)
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return 124, out + f"\ntimed out after {TIMEOUT_SECS}s"
    return run.returncode, run.stdout + run.stderr


def main():
    if len(sys.argv) != 1:
        sys.exit(__doc__)
    sources = sorted({path for _, _, path, _, _ in MUTANTS})
    # A run killed before its `finally` leaves a mutant in a file: refuse to start from anything but the committed one.
    if subprocess.run(["git", "-C", ROOT, "diff", "--quiet", "HEAD", "--", *sources]).returncode != 0:
        sys.exit("a mutated source differs from HEAD (a mutant left by an interrupted run?); restore it first")
    missed = []
    for name, invariants, path, old, new in MUTANTS:
        original = open(path).read()
        if original.count(old) != 1:
            sys.exit(f"{name}: the edit no longer applies to {os.path.basename(path)}; update it")
        try:
            with open(path, "w") as f:
                f.write(original.replace(old, new))
            code, out = simulate()
        finally:
            with open(path, "w") as f:
                f.write(original)
        if "Compilation failed" in out:
            sys.exit(f"{name}: the mutant does not compile:\n{out[-3000:]}")
        hit = sorted(i for i in invariants if named(i, out))
        if code != 0 and hit:
            print(f"{name}: caught by {', '.join(hit)}")
        else:
            print(f"{name}: NOT CAUGHT by {sorted(invariants)}; exit {code}\n" + out[-3000:])
            missed.append(name)
    sys.exit(1 if missed else 0)


if __name__ == "__main__":
    main()
