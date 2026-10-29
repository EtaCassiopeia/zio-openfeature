#!/usr/bin/env python3
"""Summarize a stryker4s JSON report and enforce a mutation-score floor.

Usage: mutation-summary.py <report.json> <module> <floor> [--summary <file>]

Prints, and appends to the step summary when given, the mutation score, the same score with string-literal mutants
left out (most of those are log messages), and the surviving mutants grouped by file. Exits 1 when the score is below
<floor>, so a drop shows up as a failed run instead of a number nobody reads.
"""
import json
import sys
from collections import defaultdict

DETECTED = {"Killed", "Timeout"}
UNDETECTED = {"Survived", "NoCoverage"}


def score(mutants):
    detected = sum(1 for m in mutants if m["status"] in DETECTED)
    valid = detected + sum(1 for m in mutants if m["status"] in UNDETECTED)
    return (100.0 * detected / valid if valid else 100.0), detected, valid


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    report_path, module, floor = argv[1], argv[2], float(argv[3])
    summary_path = argv[argv.index("--summary") + 1] if "--summary" in argv else None

    with open(report_path, encoding="utf-8") as f:
        report = json.load(f)

    mutants, survivors = [], defaultdict(list)
    for path, data in report["files"].items():
        for m in data["mutants"]:
            mutants.append(m)
            if m["status"] in UNDETECTED:
                line = m["location"]["start"]["line"]
                survivors[path].append((line, m["mutatorName"], m.get("replacement", ""), m["status"]))

    total, detected, valid = score(mutants)
    logic, _, logic_valid = score([m for m in mutants if m["mutatorName"] != "StringLiteral"])
    passed = total >= floor

    out = [
        f"## Mutation testing ({module})",
        "",
        f"- Mutation score: **{total:.2f}%** ({detected}/{valid} detected); floor {floor:.0f}%: "
        + ("passed" if passed else "**below the floor**"),
        f"- Without string-literal mutants: {logic:.2f}% (of {logic_valid})",
        f"- Survived or uncovered: {valid - detected}",
        "",
    ]
    if survivors:
        out += ["| File | Line | Mutator | Replacement | Status |", "|---|---|---|---|---|"]
        for path in sorted(survivors, key=lambda p: (-len(survivors[p]), p)):
            name = path.split("/")[-1]
            for line, mutator, replacement, status in sorted(survivors[path]):
                shown = replacement.replace("|", "\\|").replace("\n", " ")[:60]
                out.append(f"| {name} | {line} | {mutator} | `{shown}` | {status} |")
        out.append("")

    text = "\n".join(out)
    print(text)
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
