"""Generate random behaviours of a TLA+ model for a replay against the code.

Usage: python3 generate.py <model dir> <tla2tools.jar> <CommunityModules-deps.jar> <out dir> --actions KIND:FILE[:OP]
       [--num N] [--depth D] [--seed S] [--set key=value ...]

TLC (1.8, for `-generate` and the community modules' Json) walks `<model dir>/Generate.tla` at random: `num`
behaviours of `depth` states each, from `seed`. Each behaviour is written to `<out dir>/<n>.ndjson`, one state per line
with every variable of the model and `act`, the action that produced the state. The model's TLC configuration is
`<model dir>/Generate.cfg.in`, in which `{out}`, `{depth}` and each `--set key=value` (as `{key}`) are replaced.

`--actions` says where the model's actions are discovered, so that each can be required to have a label in Generate.tla
(`name |-> "Action"`): `next:FILE:OP` takes the names `OP == ...` in FILE disjoins. `pluscal:FILE` takes the labels
that `pc[...] = "Label"` guards test in FILE; a PlusCal GNext labels a step with the pc label it ran from, so what must
appear in Generate.tla is each process of the translation's `Next` (`Caller`, `Clock`, ...), and the labels are only
counted. Exits non-zero when a behaviour is missing or shorter than `depth`, or when an action has no label; prints
how often each action was generated.
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

TIMEOUT_SECS = int(os.environ.get("GENERATE_TIMEOUT_SECS", "1800"))


def read(model_dir, name):
    with open(os.path.join(model_dir, name)) as f:
        return f.read()


def discover_next(model_dir, file, operator):
    """The action names that `operator` disjoins."""
    block = re.search(rf"^{re.escape(operator)} ==\n(.*?)\n\n", read(model_dir, file), re.S | re.M).group(1)
    return set(re.findall(r"\b([A-Z][A-Za-z0-9]+)\b", re.sub(r'"[^"]*"', "", block))) - {"BOOLEAN", "All"}


def discover_pluscal(model_dir, file):
    """The labels that the translation's `pc[...] = "Label"` guards test, but for PlusCal's own final `Done`."""
    return set(re.findall(r'pc\[[^\]]*\]\s*=\s*"([A-Za-z0-9_]+)"', read(model_dir, file))) - {"Done"}


DISCOVERERS = {"next": discover_next, "pluscal": lambda model_dir, file, _operator: discover_pluscal(model_dir, file)}


def actions_of(model_dir, spec):
    """The model's action names, and what they were discovered from, for a `KIND:FILE[:OP]` setting."""
    kind, _, rest = spec.partition(":")
    if kind not in DISCOVERERS:
        sys.exit(f"--actions: unknown kind {kind!r}, expected one of {sorted(DISCOVERERS)}")
    file, _, operator = rest.partition(":")
    if not file or (kind == "next" and not operator):
        sys.exit(f"--actions: expected {'next:FILE:OP' if kind == 'next' else 'pluscal:FILE'}, got {spec!r}")
    return DISCOVERERS[kind](model_dir, file, operator or None), operator or file


def unlabelled_of(model_dir, spec, actions):
    """The actions that Generate.tla does not label."""
    kind, _, rest = spec.partition(":")
    if kind == "pluscal":
        file = rest.partition(":")[0]
        block = re.search(r"^Next ==(.*?)\n\n", read(model_dir, file), re.S | re.M).group(1)
        processes = set(re.findall(r"\b([A-Z][A-Za-z0-9]+)\b", re.sub(r"\bself\b", "", block))) - {"Terminating"}
        code = re.sub(r"\\\*.*", "", re.sub(r"\(\*.*?\*\)", "", read(model_dir, "Generate.tla"), flags=re.S))
        return sorted(p for p in processes if not re.search(rf"\b{p}\b", code))
    return sorted(actions - labels_of(model_dir))


def labels_of(model_dir):
    return set(re.findall(r'name \|-> "([A-Za-z]+)"', read(model_dir, "Generate.tla")))


def configuration(model_dir, out, depth, keys):
    text = read(model_dir, "Generate.cfg.in").replace("{out}", out).replace("{depth}", str(depth))
    for key, value in keys.items():
        text = text.replace("{" + key + "}", value)
    left = re.findall(r"\{[a-z_]+\}", text)
    if left:
        sys.exit(f"Generate.cfg.in has placeholders without a value: {sorted(set(left))}")
    return text


def generate(model_dir, tla_jar, cm_jar, out, num, depth, seed, keys):
    os.makedirs(out, exist_ok=True)
    if os.listdir(out):
        sys.exit(f"{out} is not empty")
    config = configuration(model_dir, out, depth, keys)
    with tempfile.TemporaryDirectory() as d:
        for name in os.listdir(model_dir):
            if name.endswith(".tla"):
                shutil.copy(os.path.join(model_dir, name), d)
        with open(os.path.join(d, "Generate.cfg"), "w") as f:
            f.write(config)
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


def run(model_dir, tla_jar, cm_jar, out, actions_spec, num, depth, seed, keys, note=""):
    """Generates and checks; `note` is added to the summary line after the seed. Returns the exit status."""
    model_dir = os.path.abspath(model_dir)
    actions, source = actions_of(model_dir, actions_spec)
    unlabelled = unlabelled_of(model_dir, actions_spec, actions)
    if unlabelled:
        sys.exit(f"{source} has actions Generate.tla does not label: {unlabelled}")

    out = os.path.abspath(out)
    generate(model_dir, os.path.abspath(tla_jar), os.path.abspath(cm_jar), out, num, depth, seed, keys)
    counts, short = check(out, num, depth)
    print(f"{num} behaviours of {depth} states, seed {seed}{note}, in {out}")
    for name, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {name:24} {count}")
    never = sorted(actions - set(counts))
    if never:
        print(f"never generated: {never}")
    for s in short:
        print(f"SHORT {s}")
    return 1 if short else 0


def key_value(text):
    key, sep, value = text.partition("=")
    if not sep or not key:
        raise argparse.ArgumentTypeError(f"expected key=value, got {text!r}")
    return key, value


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("model_dir")
    parser.add_argument("tla_jar")
    parser.add_argument("cm_jar")
    parser.add_argument("out")
    parser.add_argument("--actions", required=True, help="KIND:FILE[:OP], see the module docstring")
    parser.add_argument("--num", type=int, default=2000)
    parser.add_argument("--depth", type=int, default=20)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--set", dest="keys", type=key_value, action="append", default=[],
                        help="a value for a {key} of Generate.cfg.in")
    args = parser.parse_args()
    keys = dict(args.keys)
    note = "".join(f", {k}={v}" for k, v in keys.items())
    sys.exit(run(args.model_dir, args.tla_jar, args.cm_jar, args.out, args.actions, args.num, args.depth, args.seed,
                 keys, note))


if __name__ == "__main__":
    main()
