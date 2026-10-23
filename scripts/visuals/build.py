"""Writes every animation to docs/assets. Run from the repository root: python3 scripts/visuals/build.py [scene ...]

Every module here other than `hud` and `build` is a scene: it defines `build() -> Scene`, and is written to
docs/assets/<module name with dashes>.svg (`circuit_breaker.py` -> `circuit-breaker.svg`).
"""

import importlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SCENES = sorted(
    f[:-3] for f in os.listdir(HERE) if f.endswith(".py") and f[:-3] not in ("hud", "build")
)

if __name__ == "__main__":
    out = os.path.join(HERE, "..", "..", "docs", "assets")
    wanted = [w.replace("-", "_") for w in sys.argv[1:]] or SCENES
    for module in wanted:
        name = module.replace("_", "-")
        importlib.import_module(module).build().write(os.path.join(out, f"{name}.svg"))
        print(f"docs/assets/{name}.svg")
