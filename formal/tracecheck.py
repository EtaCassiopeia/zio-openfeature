"""Trace validation shared by the models: does TLC find a behaviour of the model that takes a recorded run's steps?

A model's validator (formal/<model>/validate.py) supplies its `labels` (a recorded run to the model's action labels,
or a reason the model does not cover it) and its `check` (the labels to a verdict, by `tlc` with the model's trace
module and config); this module collects the traces, runs them in parallel, prints the summary and runs the selftest.

Verdicts: ACCEPTED when TLC reports the trace module's `TraceNotDone` violated (a behaviour reached the trace's end),
REJECTED when it finds no error (no behaviour does), UNDECIDED when it runs out of TRACE_TIMEOUT_SECS (default 300),
TOOL when TLC could not decide at all. A run fails when a trace is rejected, on a tool error, or when no trace was
accepted; an undecided trace does not fail it.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

TIMEOUT_SECS = int(os.environ.get("TRACE_TIMEOUT_SECS", "300"))
TLC_WORKERS = int(os.environ.get("TRACE_TLC_WORKERS", "2"))
PARALLEL = max(1, (os.cpu_count() or 2) // TLC_WORKERS)
# TOOL: TLC could not decide the trace at all (a missing jar or java, a crash, a parse error). Never a rejection: a
# broken checker would otherwise pass every reject fixture and turn every recorded run into a reported bug.
ACCEPTED, REJECTED, UNDECIDED, TOOL = "accepted", "rejected", "undecided", "tool error"


def tlc(tla_jar, cm_jar, here, modules, spec, cfg, actions):
    """(verdict, why, TLC's output) for `actions`, searched by `spec`.tla, copied from `here` with `modules`.
    `cfg(trace)` is the config's text, given the path of the trace file it names."""
    with tempfile.TemporaryDirectory() as d:
        for name in modules:
            with open(os.path.join(here, name)) as src, open(os.path.join(d, name), "w") as dst:
                dst.write(src.read())
        trace = os.path.join(d, "trace.ndjson")
        with open(trace, "w") as f:
            for a in actions:
                f.write(json.dumps(a) + "\n")
        with open(os.path.join(d, spec + ".cfg"), "w") as f:
            f.write(cfg(trace))
        try:
            run = subprocess.run(
                # TLC parses into java.io.tmpdir; a default one that is missing or unwritable fails the parse.
                ["java", "-XX:+UseParallelGC", f"-Djava.io.tmpdir={d}", "-cp", f"{tla_jar}{os.pathsep}{cm_jar}",
                 "tlc2.TLC", "-workers", str(TLC_WORKERS), "-deadlock", "-config", spec + ".cfg", spec + ".tla"],
                cwd=d, capture_output=True, text=True, timeout=TIMEOUT_SECS)
        except subprocess.TimeoutExpired:
            return UNDECIDED, f"undecided after {TIMEOUT_SECS}s", ""
        except OSError as e:
            return TOOL, f"TLC failed: could not run java: {e}", ""
    out = run.stdout
    if "Invariant TraceNotDone is violated" in out:
        return ACCEPTED, "", out
    if "No error has been found" in out:
        return REJECTED, "no behaviour of the model takes these steps", out
    # A JVM that could not start TLC says why on stderr.
    return TOOL, "TLC failed: " + out[-800:] + run.stderr[-500:], out


def collect(args):
    paths = []
    for arg in args:
        paths += sorted(glob.glob(os.path.join(arg, "*.ndjson"))) if os.path.isdir(arg) else [arg]
    return paths


def run(paths, labels, check):
    """(path, actions, verdict, why, extra) per trace. `labels(path)` is the run's labels, or None or a reason for a run
    the model does not cover (verdict None, why the reason); `check(actions)` is (verdict, why[, extra])."""
    def one(path):
        actions = labels(path)
        if actions is None or isinstance(actions, str):
            return path, None, None, actions or "not modelled", None
        verdict, why, *extra = check(actions)
        return path, actions, verdict, why, extra[0] if extra else None

    with ThreadPoolExecutor(PARALLEL) as pool:
        return list(pool.map(one, paths))


def selftest(root, labels, check):
    """Every trace under `root`/accept must be accepted and every one under `root`/reject rejected."""
    wrong = []
    for expected, sub in ((ACCEPTED, "accept"), (REJECTED, "reject")):
        for path, _, verdict, why, _ in run(collect([os.path.join(root, sub)]), labels, check):
            name = f"{sub}/{os.path.basename(path)}"
            print(f"{verdict or 'skipped'}: {name}")
            if verdict != expected:
                wrong.append(f"{name} was {verdict or 'skipped'}: {why}")
    for w in wrong:
        print(f"WRONG {w}")
    return 1 if wrong else 0


def report(results):
    """Print the summary of `results`; the exit status: non-zero when one was rejected, on a tool error, or when none
    was accepted."""
    count = {v: sum(1 for r in results if r[2] == v) for v in (ACCEPTED, REJECTED, UNDECIDED, TOOL, None)}
    print(f"{count[ACCEPTED]} accepted, {count[REJECTED]} rejected, {count[UNDECIDED]} undecided, "
          f"{count[TOOL]} tool error, {count[None]} skipped (not modelled), of {len(results)} traces")
    reasons = sorted({r[3] for r in results if r[2] is None and r[3] != "not modelled"})
    for reason in reasons:
        print(f"skipped, {reason}: {sum(1 for r in results if r[2] is None and r[3] == reason)}")
    for path, actions, verdict, why, _ in results:
        if verdict in (REJECTED, UNDECIDED, TOOL):
            print(f"{verdict.upper()} {os.path.basename(path)}: {[a['action'] for a in actions]}\n  {why}")
    if count[ACCEPTED] == 0:
        print("No trace was accepted: nothing was checked.")
    return 1 if count[REJECTED] or count[TOOL] or count[ACCEPTED] == 0 else 0
