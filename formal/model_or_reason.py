"""A pull request that changes the code a model describes changes that model too, or says why not.

Each model lists the code it describes in `formal/<model>/code-paths`. For every model whose code the pull request
changes while nothing under `formal/<model>/` changes, the description needs a line
`formal/<model>: unaffected because <reason>` (a list bullet in front is fine); a file two models describe needs both.
The older `formal: unaffected because <reason>` still counts for the lifecycle model.

Usage: model_or_reason.py <base> <head>, with the description in $BODY. Exits 1 naming each model missing its line.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

FORMAL = Path(__file__).resolve().parent


def code_paths(formal=FORMAL):
    """{model: set of repo-relative paths} from every formal/<model>/code-paths."""
    models = {}
    for f in sorted(formal.glob("*/code-paths")):
        lines = (line.strip() for line in f.read_text().splitlines())
        models[f.parent.name] = {line for line in lines if line and not line.startswith("#")}
    return models


def reasons(body):
    """The models the description gives a reason for."""
    found = set()
    for line in body.splitlines():
        m = re.match(r"\s*(?:[-*+>]\s+)?formal(?:/([\w-]+))?:\s+unaffected\s+because\s+\S", line, re.IGNORECASE)
        if m:
            found.add((m.group(1) or "lifecycle").lower())
    return found


def missing(changed, body, models):
    """{model: its changed code files}, for each model that needs a reason and has none."""
    changed = set(changed)
    given = reasons(body)
    out = {}
    for model, paths in models.items():
        touched = sorted(changed & paths)
        updated = any(p.startswith(f"formal/{model}/") for p in changed)
        if touched and not updated and model not in given:
            out[model] = touched
    return out


def main(argv):
    base, head = argv[1], argv[2]
    # No rename detection: a renamed file counts under its old path too.
    diff = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "-z", f"{base}...{head}"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.split("\0")
    gaps = missing([f for f in diff if f], os.environ.get("BODY", ""), code_paths())
    if not gaps:
        print("Every model whose code changed was changed with it, or the description says why not.")
        return 0
    for model, files in gaps.items():
        print(f"::error::This pull request changes code formal/{model} describes without changing the model:")
        for f in files:
            print(f"  {f}")
        print(f"::error::Update formal/{model}, or add a description line "
              f"'formal/{model}: unaffected because <reason>'.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
