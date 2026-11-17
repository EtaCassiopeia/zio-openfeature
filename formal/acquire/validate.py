"""Check that every recorded fromAcquireAsync run is a behaviour of the acquire model, and what of the model they cover.

Usage: python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> <trace dir or files...> [--coverage]
       python3 validate.py <tla2tools.jar> <CommunityModules-deps.jar> --selftest traces

Each trace (ndjson of probe points) is turned into model action labels (trace_labels.py), then TLC searches
AcquireTrace.tla for a behaviour of Acquire, under Fix, that takes those steps in that order and ends as the run
observed. The verdicts, the summary and the exit status are formal/tracecheck.py's; a run the model does not cover is
skipped with its reason. `--selftest` checks the checker: every trace under `traces/accept` must be accepted and every
one under `traces/reject` rejected.

`--coverage` also matches the accepted runs against the model's state graph: for each set of constants the runs were
checked with, TLC dumps every transition of Acquire under Fix (`-dump dot`), and each transition some accepted run's
behaviour takes is covered. It prints the transitions covered per action, and fails when an action is never taken: a
transition no run reaches is reported, not failed on (formal/acquire/README.md says why some cannot be reached).
"""
import os
import re
import subprocess
import sys
import tempfile
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(here))

import tracecheck  # noqa: E402
from trace_labels import labels, read  # noqa: E402

MODEL_VARS = ("pc", "rel", "real", "serving", "owned", "shutdowns", "closed", "usedClosed", "composed", "stackShut",
              "att", "realFrom", "ab")


def constants(actions):
    a = actions[0]
    return a["owning"], a["userShuts"], a["attempts"]


def constants_cfg(owning, user_shuts, attempts):
    return f"""CONSTANTS
    Fix = TRUE
    UserShuts = {str(user_shuts).upper()}
    Owning = {str(owning).upper()}
    MaxAttempts = {attempts}
"""


def check(tla_jar, cm_jar, actions):
    """(verdict, why, TLC's output) for one run's labels."""
    def cfg(trace):
        return constants_cfg(*constants(actions)) + f"""    TraceFile = "{trace}"
INIT TInit
NEXT TraceStep
INVARIANT TraceNotDone
"""
    return tracecheck.tlc(tla_jar, cm_jar, here, ("Acquire.tla", "AcquireTrace.tla"), "AcquireTrace", cfg, actions)


def state(lines):
    """The model's variables in a state TLC printed as `/\\ var = value` lines: whitespace dropped, which no value of
    this model carries inside a string, and each record's fields sorted, which TLC prints in no fixed order."""
    out, var = {}, None
    for line in lines:
        m = re.match(r"^/\\ (\w+) = (.*)$", line)
        if m:
            var = m.group(1)
            out[var] = m.group(2)
        elif var:
            out[var] += line
    def canonical(value):
        value = re.sub(r"\s+", "", value)
        return re.sub(r"\[([^\[\]]*)\]", lambda m: "[" + ",".join(sorted(m.group(1).split(","))) + "]", value)

    return tuple(canonical(out[v]) for v in MODEL_VARS)


def behaviour(output):
    """The states of the behaviour TLC printed for an accepted run."""
    text = output.split("The behavior up to this point is:", 1)[-1]
    blocks = re.split(r"^State \d+: .*$", text, flags=re.M)[1:]
    return [state([ln for ln in b.splitlines() if ln.strip()]) for b in blocks]


def transitions(tla_jar, owning, user_shuts, attempts):
    """{(state, state): action labels} of Acquire's state graph under Fix, without its stuttering steps."""
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(here, "Acquire.tla")) as src, open(os.path.join(d, "Acquire.tla"), "w") as dst:
            dst.write(src.read())
        with open(os.path.join(d, "Graph.cfg"), "w") as f:
            f.write(constants_cfg(owning, user_shuts, attempts) + "SPECIFICATION Spec\n")
        run = subprocess.run(["java", f"-Djava.io.tmpdir={d}", "-cp", tla_jar, "tlc2.TLC", "-dump", "dot,actionlabels",
                              "graph", "-config", "Graph.cfg", "Acquire.tla"], cwd=d, capture_output=True, text=True)
        if "Model checking completed" not in run.stdout:
            sys.exit("TLC could not dump the state graph:\n" + run.stdout[-800:] + run.stderr[-500:])
        dot = open(os.path.join(d, "graph.dot")).read()
    nodes = {}
    for m in re.finditer(r'^(-?\d+) \[label="((?:[^"\\]|\\.)*)"', dot, flags=re.M):
        label = m.group(2).replace('\\"', '"').replace("\\\\", "\\")
        nodes[m.group(1)] = state(label.split("\\n"))
    edges = defaultdict(set)
    for m in re.finditer(r'^(-?\d+) -> (-?\d+) \[label="([^"]*)"', dot, flags=re.M):
        if m.group(1) != m.group(2):
            edges[(nodes[m.group(1)], nodes[m.group(2)])].add(m.group(3).split("(")[0])
    return edges


def coverage(tla_jar, results):
    """Print what of the state graph the accepted runs cover; non-zero when an action is never taken."""
    taken = defaultdict(set)
    for _, actions, verdict, _, output in results:
        if verdict == tracecheck.ACCEPTED:
            states = behaviour(output)
            taken[constants(actions)] |= set(zip(states, states[1:]))
    reached, every = set(), set()
    for key in sorted(taken):
        edges = transitions(tla_jar, *key)
        per = defaultdict(lambda: [0, 0])
        for edge, names in edges.items():
            for name in names:
                per[name][1] += 1
                per[name][0] += edge in taken[key]
        covered = sum(1 for e in edges if e in taken[key])
        print(f"Owning={key[0]} UserShuts={key[1]} MaxAttempts={key[2]}: {covered} of {len(edges)} transitions")
        for name in sorted(per):
            print(f"  {name}: {per[name][0]} of {per[name][1]}")
        for (src, dst), names in sorted(edges.items(), key=lambda e: sorted(e[1])):
            if (src, dst) not in taken[key]:
                was = dict(zip(MODEL_VARS, src))
                changed = ", ".join(f"{v}={b}" for v, a, b in zip(MODEL_VARS, src, dst) if a != b)
                print(f"    not taken: {'/'.join(sorted(names))} from pc={was['pc']} rel={was['rel']}: {changed}")
        every |= set(per)
        reached |= {n for n, (c, _) in per.items() if c}
    never = sorted(every - reached)
    if never:
        print("NEVER TAKEN by an accepted run: " + ", ".join(never))
    elif every:
        print("Every action is taken by some accepted run.")
    return 1 if never or not every else 0


def main():
    tla_jar, cm_jar = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    args = sys.argv[3:]

    def label(path):
        return labels(read(path))

    def checked(actions):
        return check(tla_jar, cm_jar, actions)

    if args[0] == "--selftest":
        sys.exit(tracecheck.selftest(args[1], label, checked))
    with_coverage = "--coverage" in args
    results = tracecheck.run(tracecheck.collect([a for a in args if a != "--coverage"]), label, checked)
    status = tracecheck.report(results)
    if with_coverage:
        status |= coverage(tla_jar, results)
    sys.exit(status)


if __name__ == "__main__":
    main()
