"""Generate random behaviours of the lifecycle model for the replay against the code.

Usage: python3 generate.py <tla2tools.jar> <CommunityModules-deps.jar> <out dir> [--num N] [--depth D] [--seed S]

TLC (1.8, for `-generate` and the community modules' Json) walks Generate.tla at random: `num` behaviours of `depth`
states each, from `seed`. Each behaviour is written to `<out dir>/<n>.ndjson`, one state per line with every variable
of the model and `act`, the action that produced the state. `LifecycleReplaySpec` (core's tests) replays them against
`LifecycleStep.step`. Exits non-zero when a behaviour is missing or shorter than `depth`, or when an action of
`LifecycleNext` has no label in Generate.tla; prints how often each action was generated.
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
TIMEOUT_SECS = int(os.environ.get("GENERATE_TIMEOUT_SECS", "1800"))

CONSTANTS = """CONSTANTS
    P0 = p0
    Providers = {p1, p2}
    MaxSwaps = 3
    MaxEvents = 1
    MaxEvals = 0
    AllowDeath = FALSE
    None = None
    OutDir = "{out}"
    Depth = {depth}
    ShutdownFrom = {shutdown_from}
SPECIFICATION GSpec
ACTION_CONSTRAINT Export
"""


def labelled_actions():
    """The action names LifecycleNext disjoins, and the names Generate.tla labels."""
    with open(os.path.join(here, "Lifecycle.tla")) as f:
        lifecycle = f.read()
    block = re.search(r"^LifecycleNext ==\n(.*?)\n\n", lifecycle, re.S | re.M).group(1)
    actions = set(re.findall(r"\b([A-Z][A-Za-z0-9]+)\b", re.sub(r'"[^"]*"', "", block))) - {"BOOLEAN", "All"}
    with open(os.path.join(here, "Generate.tla")) as f:
        labels = set(re.findall(r'name \|-> "([A-Za-z]+)"', f.read()))
    return actions, labels


def generate(tla_jar, cm_jar, out, num, depth, seed, shutdown_from):
    os.makedirs(out, exist_ok=True)
    if os.listdir(out):
        sys.exit(f"{out} is not empty")
    with tempfile.TemporaryDirectory() as d:
        for name in ("Lifecycle.tla", "Generate.tla"):
            shutil.copy(os.path.join(here, name), d)
        with open(os.path.join(d, "Generate.cfg"), "w") as f:
            f.write(CONSTANTS.replace("{out}", out).replace("{depth}", str(depth))
                    .replace("{shutdown_from}", str(shutdown_from)))
        run = subprocess.run(
            ["java", "-XX:+UseParallelGC", "-cp", f"{tla_jar}{os.pathsep}{cm_jar}", "tlc2.TLC", "-workers", "1",
             "-deadlock", "-generate", f"num={num}", "-depth", str(depth), "-seed", str(seed),
             "-config", "Generate.cfg", "Generate.tla"],
            cwd=d, capture_output=True, text=True, timeout=TIMEOUT_SECS)
    if run.returncode != 0 or "traces generated" not in run.stdout:
        sys.exit("TLC failed:\n" + run.stdout[-1500:] + run.stderr[-500:])


def check(out, num, depth):
    """Every behaviour is there with `depth` states; returns how often each action occurs."""
    counts = collections.Counter()
    short = []
    for n in range(1, num + 1):
        path = os.path.join(out, f"{n}.ndjson")
        if not os.path.exists(path):
            short.append(f"{n}: missing")
            continue
        with open(path) as f:
            states = [json.loads(line) for line in f if line.strip()]
        if len(states) != depth:
            short.append(f"{n}: {len(states)} states")
        counts.update(s["act"]["name"] for s in states[1:])
    return counts, short


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("tla_jar")
    parser.add_argument("cm_jar")
    parser.add_argument("out")
    parser.add_argument("--num", type=int, default=2000)
    parser.add_argument("--depth", type=int, default=20)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--shutdown-from", type=int, default=None,
                        help="the level from which a shutdown or the scope close may start (default: depth - 4)")
    args = parser.parse_args()
    shutdown_from = args.shutdown_from if args.shutdown_from is not None else max(1, args.depth - 4)

    actions, labels = labelled_actions()
    unlabelled = sorted(actions - labels)
    if unlabelled:
        sys.exit(f"LifecycleNext has actions Generate.tla does not label: {unlabelled}")

    out = os.path.abspath(args.out)
    generate(os.path.abspath(args.tla_jar), os.path.abspath(args.cm_jar), out, args.num, args.depth, args.seed,
             shutdown_from)
    counts, short = check(out, args.num, args.depth)
    print(f"{args.num} behaviours of {args.depth} states, seed {args.seed}, shutdown from level {shutdown_from}, "
          f"in {out}")
    for name, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {name:24} {count}")
    never = sorted(actions - set(counts))
    if never:
        print(f"never generated: {never}")
    for s in short:
        print(f"SHORT {s}")
    sys.exit(1 if short else 0)


if __name__ == "__main__":
    main()
