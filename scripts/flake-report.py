#!/usr/bin/env python3
"""Compare JUnit XML reports from repeated runs of the same suite and report the tests whose outcome changed.

Usage: flake-report.py <run-dir> [<run-dir> ...] [--known <file>] [--summary <file>]

Each run directory holds the `TEST-*.xml` files of one full run, in any layout below it. A test that failed in some
runs and passed in others is flaky; one that failed in every run it ran in is broken; a suite present in some runs and
missing from others crashed or hung there.

`--known` lists tracked flakes, one `<suite class>` or `<suite class>::<test name>` per line, each followed by
`# <tracking note>`; they are reported but do not fail the run. Exit 1 when anything untracked was found, or when no
report was found at all, so a run that produced nothing cannot read as clean.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def outcomes(run_dir):
    """{(suite, test): "pass" | "fail" | "skip"} and the set of suites, for one run."""
    results, suites = {}, set()
    for path in glob.glob(os.path.join(run_dir, "**", "TEST-*.xml"), recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # A JVM killed mid-write leaves a truncated report: its suite counts as missing from this run.
            print(f"warning: unreadable report {path}", file=sys.stderr)
            continue
        for suite in [root] if root.tag == "testsuite" else root.iter("testsuite"):
            suite_name = suite.get("name")
            suites.add(suite_name)
            for case in suite.iter("testcase"):
                # zio-test writes each name as " - <label>"; keep just the label.
                key = (suite_name, case.get("name", "").strip().removeprefix("- "))
                if case.find("failure") is not None or case.find("error") is not None:
                    outcome = "fail"
                elif case.find("skipped") is not None:
                    outcome = "skip"
                else:
                    outcome = "pass"
                # A test reported twice in one run keeps its worst outcome.
                if results.get(key) != "fail":
                    results[key] = outcome
    return results, suites


def load_known(path):
    known = {}
    if not path:
        return known
    with open(path) as f:
        for line in f:
            entry, _, note = line.partition("#")
            entry = entry.strip()
            if entry:
                known[entry] = note.strip()
    return known


def tracked(known, suite, test):
    return known.get(f"{suite}::{test}") or known.get(suite)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runs", nargs="+")
    parser.add_argument("--known")
    parser.add_argument("--summary")
    args = parser.parse_args()

    runs = [outcomes(d) for d in args.runs]
    if not any(results for results, _ in runs):
        print("error: no test reports found in " + ", ".join(args.runs), file=sys.stderr)
        return 1
    known = load_known(args.known)

    all_tests = sorted(set().union(*(results.keys() for results, _ in runs)))
    flaky, broken = [], []
    for key in all_tests:
        seen = [results[key] for results, _ in runs if key in results]
        fails, passes = seen.count("fail"), seen.count("pass")
        if fails and passes:
            flaky.append((key, fails, len(seen)))
        elif fails and fails == len(seen):
            broken.append((key, fails, len(seen)))

    all_suites = set().union(*(suites for _, suites in runs))
    missing = sorted(
        (suite, [i + 1 for i, (_, suites) in enumerate(runs) if suite not in suites])
        for suite in all_suites
        if any(suite not in suites for _, suites in runs)
    )

    lines = [f"## Flake hunt: {len(runs)} runs, {len(all_tests)} tests", ""]
    untracked = 0

    def section(title, rows):
        nonlocal untracked
        if not rows:
            return
        lines.extend([f"### {title}", "", "| Suite | Test | Failed | Tracked |", "|:--|:--|--:|:--|"])
        for (suite, test), fails, ran in rows:
            note = tracked(known, suite, test)
            untracked += 0 if note else 1
            lines.append(f"| `{suite}` | {test} | {fails}/{ran} | {note or '**no**'} |")
        lines.append("")

    section("Flaky (failed, then passed in another run)", flaky)
    section("Failing in every run", broken)
    if missing:
        lines.extend(["### Suites missing from some runs (crashed or hung)", "", "| Suite | Missing from run | Tracked |", "|:--|:--|:--|"])
        for suite, absent in missing:
            note = known.get(suite)
            untracked += 0 if note else 1
            lines.append(f"| `{suite}` | {', '.join(map(str, absent))} | {note or '**no**'} |")
        lines.append("")
    if not (flaky or broken or missing):
        lines.append("No test changed outcome between runs.")

    text = "\n".join(lines) + "\n"
    print(text)
    if args.summary:
        with open(args.summary, "a") as out:
            out.write(text)
    return 1 if untracked else 0


if __name__ == "__main__":
    sys.exit(main())
