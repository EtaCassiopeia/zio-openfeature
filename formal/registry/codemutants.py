#!/usr/bin/env python3
"""Put back each registry leak the replay must catch, and require the replay to turn red with it.

Usage: python3 codemutants.py <behaviours>, the comma-separated files or directories `RegistryReplaySpec` reads (the
generated behaviours of both topologies and mbt-regressions). Run from anywhere; it runs sbt at the repository root.

Each mutant is an exact edit of one source file: it undoes a fix the targets in mbt-regressions/ exercise. The replay
must fail with a mismatch on one of the mutant's fields, both among all the behaviours and among the targeted ones
alone, per the counts the spec logs. The source is restored after every mutant, whatever happens. Each mutant
recompiles core, so this runs on main and on demand, not on pull requests.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
MAIN = os.path.join(ROOT, "core", "src", "main", "scala", "zio", "openfeature")
TIMEOUT_SECS = int(os.environ.get("CODEMUTANTS_TIMEOUT_SECS", "3600"))
# Under a mutant up to a quarter of the generated behaviours mismatch, and each waits out the replay's deadline: the
# whole PR-sized set then runs past the spec's 30-minute timeout. The targets catch every mutant alone, so a sample of
# the generated ones is enough to show the mismatch among all behaviours too.
PER_LOCATION = os.environ.get("ZOF_REGISTRY_BEHAVIOURS_MAX", "150")

# (name, source file, the mismatch fields any of which must show it, old, new). A wrapper missing from a list the
# model tracks shows there first, before any shutdown count differs.
MUTANTS = [
    ("a retry with another provider does not leave the failed build's wrapper behind: the registry's close never "
     "releases it", os.path.join(MAIN, "FeatureFlagRegistry.scala"), {"leftBehind", "total"},
     "            .foreach(ApiInstances.leaveBehind(api, _))\n",
     "            .foreach(_ => ())\n"),
    ("the owner's orphan list ignores a sibling's named slot: it shuts down a wrapper the sibling still serves",
     os.path.join(MAIN, "internal", "OrphanedProviders.scala"), {"orphans", "total"},
     "ProviderStateAccess.boundIn(api, null, previous.sdk) || ApiInstances.boundInAnySlot(api, previous.sdk)",
     "ProviderStateAccess.boundIn(api, null, previous.sdk)"),
    ("the owner's close does not release the wrappers a non-owner's timed-out swaps left behind",
     os.path.join(MAIN, "internal", "FeatureFlagsFactories.scala"), {"total"},
     "ProviderRegistration.boundedApiShutdown(api) *> orphans.resolveAll *> ApiInstances.releaseLeftBehind(api)",
     "ProviderRegistration.boundedApiShutdown(api) *> orphans.resolveAll"),
    ("a swap claims nothing it moves off: a second swap off that provider, or a swap to it while no slot holds it, "
     "goes ahead under the SDK's queued shutdown", os.path.join(MAIN, "FeatureFlagsLive.scala"), {"step", "total"},
     "case None if !ApiInstances.claimSwapOff(api, this, domain, off) =>",
     "case None if false =>"),
]


def by_field(text, label):
    """The `[field=n ...]` counts after `label`, or None when the line is missing."""
    m = re.search(rf"{re.escape(label)}: \d+ (?:behaviours, \d+ )?mismatches \[([^\]]*)\]", text)
    if not m:
        return None
    return dict((k, int(v)) for k, v in (kv.split("=") for kv in m.group(1).split()))


def replay(behaviours):
    env = dict(os.environ, ZOF_REGISTRY_BEHAVIOURS=behaviours, ZOF_REGISTRY_BEHAVIOURS_REQUIRED="1",
               ZOF_REGISTRY_BEHAVIOURS_MAX=PER_LOCATION)
    run = subprocess.run(
        ["sbt", "-Dsbt.server.autostart=false", "core/testOnly zio.openfeature.RegistryReplaySpec"],
        cwd=ROOT, env=env, capture_output=True, text=True, timeout=TIMEOUT_SECS)
    # Strip the colours sbt puts around the log line.
    return run.returncode, re.sub(r"\x1b\[[0-9;]*m", "", run.stdout + run.stderr)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    behaviours = sys.argv[1]
    sources = sorted({source for _, source, _, _, _ in MUTANTS})
    # A run killed before its `finally` leaves a mutant in a file: refuse to start from anything but the committed one.
    if subprocess.run(["git", "-C", ROOT, "diff", "--quiet", "HEAD", "--", *sources]).returncode != 0:
        sys.exit("a mutated source differs from HEAD (a mutant left by an interrupted run?); restore it first")
    missed = []
    for name, source, fields, old, new in MUTANTS:
        original = open(source).read()
        if original.count(old) != 1:
            sys.exit(f"{name}: the edit no longer applies to {os.path.basename(source)}; update it")
        try:
            with open(source, "w") as f:
                f.write(original.replace(old, new))
            code, out = replay(behaviours)
        finally:
            with open(source, "w") as f:
                f.write(original)
        if "Compilation failed" in out:
            sys.exit(f"{name}: the mutant does not compile:\n{out[-3000:]}")
        every, targeted = by_field(out, "registry replay"), by_field(out, "registry replay targeted")
        if code != 0 and every and targeted and fields & every.keys() and fields & targeted.keys():
            print(f"{name}: caught; by field {every}, targeted {targeted}")
        else:
            print(f"{name}: NOT CAUGHT as {sorted(fields)}; exit {code}, by field {every}, targeted {targeted}\n"
                  + out[-3000:])
            missed.append(name)
    sys.exit(1 if missed else 0)


if __name__ == "__main__":
    main()
