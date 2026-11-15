"""Generate random behaviours of the lifecycle model for the replay against the code.

Usage: python3 generate.py <tla2tools.jar> <CommunityModules-deps.jar> <out dir> [--num N] [--depth D] [--seed S]
       [--shutdown-from L]

TLC (1.8, for `-generate` and the community modules' Json) walks Generate.tla at random: `num` behaviours of `depth`
states each, from `seed`. Each behaviour is written to `<out dir>/<n>.ndjson`, one state per line with every variable
of the model and `act`, the action that produced the state. `LifecycleReplaySpec` (core's tests) replays them against
`LifecycleStep.step`. Exits non-zero when a behaviour is missing or shorter than `depth`, or when an action of
`LifecycleNext` has no label in Generate.tla; prints how often each action was generated.

The driver is `formal/generate.py`; this fixes the model (this directory, `Generate.cfg.in`) and its action source.
"""
import argparse
import importlib.util
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
# Loaded by path: this file is itself named generate.
_spec = importlib.util.spec_from_file_location("model_generate", os.path.join(os.path.dirname(here), "generate.py"))
generate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(generate)


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

    sys.exit(generate.run(here, args.tla_jar, args.cm_jar, args.out, "next:Lifecycle.tla:LifecycleNext", args.num,
                          args.depth, args.seed, {"shutdown_from": str(shutdown_from)},
                          f", shutdown from level {shutdown_from}"))


if __name__ == "__main__":
    main()
