#!/usr/bin/env python3
"""Summarise scoverage reports as a Markdown table: one row per module, plus the classes with the most uncovered
statements across all of them.

Usage: coverage-summary.py <repo-root> [--summary <file>] [--top <n>]

Reads every `<module>/target/scala-*/scoverage-report/scoverage.xml` under the root; the root's own report is the
aggregate. Fails (exit 1) when it finds none, so a run that produced no report cannot read as an empty, clean summary.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def reports(root):
    pattern = os.path.join(root, "**", "target", "scala-*", "scoverage-report", "scoverage.xml")
    for path in sorted(glob.glob(pattern, recursive=True)):
        rel = os.path.relpath(path, root)
        if rel.startswith(".claude" + os.sep):
            continue
        module = rel.split(os.sep + "target" + os.sep)[0]
        yield ("(all modules)" if module == "target" or rel.startswith("target" + os.sep) else module), path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    parser.add_argument("--summary")
    parser.add_argument("--top", type=int, default=10)
    args = parser.parse_args()

    rows, classes = [], []
    for module, path in reports(args.root):
        doc = ET.parse(path).getroot()
        count = int(doc.get("statement-count"))
        invoked = int(doc.get("statements-invoked"))
        rows.append((module, count, invoked, float(doc.get("statement-rate")), float(doc.get("branch-rate"))))
        if module != "(all modules)":
            for cls in doc.iter("class"):
                missed = int(cls.get("statement-count")) - int(cls.get("statements-invoked"))
                if missed > 0:
                    classes.append((missed, module, cls.get("filename")))

    if not rows:
        print("error: no scoverage.xml found under " + args.root, file=sys.stderr)
        return 1

    rows.sort(key=lambda r: (r[0] == "(all modules)", r[0]))
    lines = [
        "## Coverage",
        "",
        "| Module | Statements | Covered | Statement % | Branch % |",
        "|:--|--:|--:|--:|--:|",
    ]
    for module, count, invoked, statement, branch in rows:
        lines.append(f"| {module} | {count} | {invoked} | {statement:.2f} | {branch:.2f} |")

    classes.sort(reverse=True)
    if classes:
        lines += ["", f"### Most uncovered statements (top {args.top})", "", "| Module | File | Uncovered |", "|:--|:--|--:|"]
        for missed, module, filename in classes[: args.top]:
            lines.append(f"| {module} | `{filename}` | {missed} |")

    text = "\n".join(lines) + "\n"
    print(text)
    if args.summary:
        with open(args.summary, "a") as out:
            out.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
