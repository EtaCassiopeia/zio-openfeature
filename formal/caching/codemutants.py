#!/usr/bin/env python3
"""Put each bug the replay must catch into CachingProvider.scala, and require the replay to turn red with it.

Usage: python3 codemutants.py <behaviours>, the comma-separated files or directories `CachingReplaySpec` reads (the
generated behaviours and mbt-regressions). Run from anywhere; it runs sbt at the repository root.

Each mutant is an exact edit of the source. The replay must fail, with a mismatch of the mutant's kind among both the
generated and the targeted behaviours, per the counts the spec logs. The source is restored after every mutant,
whatever happens. Each mutant recompiles extras, so this runs on main and on demand, not on pull requests.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SOURCE = os.path.join(ROOT, "extras", "src", "main", "scala", "zio", "openfeature", "extras", "CachingProvider.scala")
TIMEOUT_SECS = int(os.environ.get("CODEMUTANTS_TIMEOUT_SECS", "1800"))

# (name, the mismatch field that must show it, old, new)
MUTANTS = [
    ("the key's epoch is always 0: a later caller hits the stranded old value", "lookup",
     "      generation.get(),\n", "      0L,\n"),
    ("a failure is not invalidated: it stays in the map", "map",
     ".onError(_ => cache.invalidate(ck))", ".onError(_ => ZIO.unit)"),
    ("a joiner never retries: it returns the failure it joined", "retry",
     "case Exit.Failure(_) if !ran.get() && !Thread.currentThread().isInterrupted =>",
     "case Exit.Failure(_) if false =>"),
    ("the retry goes back through the shared cache: its pending entry stays in the map", "map",
     "        Exit.attempt(ck.evaluate())\n", "        run(cache.get(ck))\n"),
]


def by_field(text, label):
    m = re.search(rf"{label}: \[([^\]]*)\]", text)
    if not m:
        return None
    return dict((k, int(v)) for k, v in (kv.split("=") for kv in m.group(1).split()))


def replay(behaviours):
    env = dict(os.environ, ZOF_CACHING_BEHAVIOURS_REQUIRED="1")
    run = subprocess.run(
        ["sbt", "-Dsbt.server.autostart=false", f"-Dzof.caching.behaviours={behaviours}",
         "extras/testOnly zio.openfeature.extras.CachingReplaySpec"],
        cwd=ROOT, env=env, capture_output=True, text=True, timeout=TIMEOUT_SECS)
    return run.returncode, run.stdout + run.stderr


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    behaviours = sys.argv[1]
    # A run killed before its `finally` leaves a mutant in the file: refuse to start from anything but the committed one.
    if subprocess.run(["git", "-C", ROOT, "diff", "--quiet", "HEAD", "--", SOURCE]).returncode != 0:
        sys.exit("CachingProvider.scala differs from HEAD (a mutant left by an interrupted run?); restore it first")
    original = open(SOURCE).read()
    missed = []
    for name, field, old, new in MUTANTS:
        if original.count(old) != 1:
            sys.exit(f"{name}: the edit no longer applies to CachingProvider.scala; update it")
        try:
            with open(SOURCE, "w") as f:
                f.write(original.replace(old, new))
            code, out = replay(behaviours)
        finally:
            with open(SOURCE, "w") as f:
                f.write(original)
        if "Compilation failed" in out:
            sys.exit(f"{name}: the mutant does not compile:\n{out[-3000:]}")
        generated, targeted = by_field(out, "; by field"), by_field(out, "targeted by field")
        if code != 0 and generated and targeted and generated.get(field) and targeted.get(field):
            print(f"{name}: caught ({field}); by field {generated}, targeted {targeted}")
        else:
            print(f"{name}: NOT CAUGHT as {field}; exit {code}, by field {generated}, targeted {targeted}\n"
                  + out[-3000:])
            missed.append(name)
    sys.exit(1 if missed else 0)


if __name__ == "__main__":
    main()
