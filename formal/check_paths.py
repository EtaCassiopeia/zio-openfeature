"""Every file a model describes exists and reruns the `Formal model` workflow when it changes.

GitHub reads a workflow's `paths:` filters statically, so they cannot be built from `formal/*/code-paths`. This checks
that each listed file exists and appears in both the `pull_request` and the `push` filters of formal.yml.

Usage: check_paths.py. Exits 1 naming each problem.
"""

import re
import sys
from pathlib import Path

from model_or_reason import FORMAL, code_paths

WORKFLOW = FORMAL.parent / ".github" / "workflows" / "formal.yml"


def filters(workflow_text):
    """{event: set of paths} for the pull_request and push filters."""
    out = {}
    event = None
    for line in workflow_text.splitlines():
        if re.match(r"^\S", line) and not line.startswith("on:"):
            if out:
                break
            continue
        m = re.match(r"^  (pull_request|push|schedule|workflow_dispatch):", line)
        if m:
            event = m.group(1)
            continue
        p = re.match(r"^\s+- '([^']+)'\s*$", line)
        if p and event in ("pull_request", "push"):
            out.setdefault(event, set()).add(p.group(1))
    return out


def problems(models, workflow_text, root):
    out = []
    found = filters(workflow_text)
    for model, paths in sorted(models.items()):
        if not paths:
            out.append(f"formal/{model}/code-paths lists no file")
        for p in sorted(paths):
            if not (root / p).is_file():
                out.append(f"formal/{model}/code-paths: {p} does not exist")
            for event in ("pull_request", "push"):
                if p not in found.get(event, set()):
                    out.append(f"formal/{model}/code-paths: {p} is not in formal.yml's {event} paths")
    return out


def main():
    issues = problems(code_paths(), WORKFLOW.read_text(), FORMAL.parent)
    for i in issues:
        print(f"::error::{i}")
    if not issues:
        print("Every model's code exists and is in both of formal.yml's path filters.")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
