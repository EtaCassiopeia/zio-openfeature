"""Generate random behaviours of the registry model for a replay through the real code.

Usage: python3 generate.py <tla2tools.jar> <CommunityModules-deps.jar> <out dir> --topology registry|shared
       [--num N] [--depth D] [--seed S] [--close-from L]

TLC (1.8, for `-generate` and the community modules' Json) walks Generate.tla at random: `num` behaviours of `depth`
states each, from `seed`, of one topology. "registry" is one FeatureFlagRegistry, whose API is private; "shared" is
instances built on one shared API. Each behaviour is written to `<out dir>/<n>.ndjson`, one state per line with every
variable of the model, the ghosts `ready`, `inFlight`, `total` and `then`, and `act`, the action that produced the state.
Exits non-zero when a behaviour is missing or shorter than `depth`, or when an action of `Next` has no label in
Generate.tla, or when an action is never generated, except those absent from the topology by design (`by_design`, each
while its condition holds); prints how often each action was generated.

The driver is `formal/generate.py`; this fixes the model (this directory, `Generate.cfg.in`), its action source and,
per topology, its instances and domains.
"""
import argparse
import importlib.util
import json
import os
import re
import sys

here = os.path.dirname(os.path.abspath(__file__))
# Loaded by path: this file is itself named generate.
_spec = importlib.util.spec_from_file_location("model_generate", os.path.join(os.path.dirname(here), "generate.py"))
generate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(generate)

# The shared topology's third instance needs a domain of its own to build on.
TOPOLOGIES = {
    "registry": {"instances": "i1, i2, i3", "domains": "default, d1"},
    "shared": {"instances": "i1, i2, i3", "domains": "default, d1, d2"},
}


def _step_disjuncts(generate_tla):
    """The disjuncts of Generate.tla's steps, Work and Closing: each runs from a `\\/` to the next one."""
    region = re.search(r"^Work ==\n(.*?)^Starts ==", generate_tla, re.S | re.M)
    return re.split(r"\n\s*\\/", region.group(1)) if region else []


def _guarded(generate_tla, guard, action):
    """Whether Generate.tla takes `action` only under `guard`: every disjunct that labels it has `guard` as a
    conjunct of its own (not negated, not one side of a disjunction)."""
    labelling = [d for d in _step_disjuncts(generate_tla) if f'name |-> "{action}"' in d]
    conjunct = re.compile(rf"(^|/\\)[ \t]*{guard}[ \t]*(/\\|$)", re.M)
    return bool(labelling) and all(conjunct.search(d) for d in labelling)


def _writers(registry_tla, change):
    """The operators of Registry.tla whose definition contains `change`."""
    definitions = re.split(r"^(?=[A-Z][A-Za-z0-9]*(?:\([^)]*\))? ==)", registry_tla, flags=re.M)
    return {re.match(r"[A-Z][A-Za-z0-9]*", d).group(0) for d in definitions
            if change in d and re.match(r"[A-Z][A-Za-z0-9]*", d)}


def by_design(topology, generate_tla, generate_cfg, registry_tla):
    """The actions `topology` never takes on purpose, each with why and whether that still holds."""
    no_timeouts = re.search(r"^\s*AllowTimeouts = FALSE\s*$", generate_cfg, re.M) is not None
    timeouts = {
        "SwapTimeout": ("AllowTimeouts is FALSE", no_timeouts),
        "InitSettle": ("AllowTimeouts is FALSE: only a timed-out swap leaves an initialize() to settle", no_timeouts),
    }
    if topology == "registry":
        fail_mark_shared = _guarded(generate_tla, "Shared", "FailMark")
        return {
            "UserBind": ("only a shared API binds a user's provider", _guarded(generate_tla, "Shared", "UserBind")),
            "OwnerClose": ("a registry's API has no owning instance", _guarded(generate_tla, "Shared", "OwnerClose")),
            "FailMark": ("only a shared API's build has an init deadline the replay drives", fail_mark_shared),
            # FailDecide takes an instance out of `deciding`, which only FailMark puts it in.
            "FailDecide": ("it follows only FailMark",
                           fail_mark_shared and _writers(registry_tla, "deciding' = deciding \\cup") == {"FailMark"}),
            **timeouts,
        }
    if topology == "shared":
        no_map = "a shared API has no registry promise map"
        return {
            "Lookup": (no_map, _guarded(generate_tla, "Reg", "Lookup")),
            "BuildFail": (no_map, _guarded(generate_tla, "Reg", "BuildFail")),
            "Evict": (no_map, _guarded(generate_tla, "Reg", "Evict")),
            "ReleaseReplaced": (no_map, _guarded(generate_tla, "Reg", "ReleaseReplaced")),
            "RegistryClose": ("a shared API has no registry", _guarded(generate_tla, "Reg", "RegistryClose")),
            **timeouts,
        }
    sys.exit(f"--topology: expected one of {sorted(TOPOLOGIES)}, got {topology!r}")


def check_shape(behaviour, topology, absent, default_provider="p1"):
    """What is wrong with one behaviour (its states, in order) that its restrictions rule out; [] when nothing is.

    TLC checks Generate.tla's invariants as it walks; this checks what a replay relies on in what it wrote: the ghost
    `total` counts every shutdown, a failed build is evicted next, a release is followed by the retry's failed build,
    a Check by its Create, no Lookup repeats, the registry's default slot is built from `default_provider`, and no
    action `absent` from the topology occurs."""
    problems = []
    for k in range(1, len(behaviour)):
        before, after = behaviour[k - 1], behaviour[k]
        act = after["act"]
        name = act["name"]
        for p, count in after["total"].items():
            grew = after["shuts"][p] - before["shuts"][p]
            if count != before["total"][p] + max(grew, 0):
                problems.append(f"state {k}: total[{p}] is {count}, not {before['total'][p] + max(grew, 0)}")
        held = before["then"]
        if isinstance(held, dict) and (name, act.get("i")) != (held["name"], held["i"]):
            problems.append(f"state {k}: {name} where {held['name']} of {held['i']} is held to")
        follows = _follower(behaviour, k - 1)
        if follows and (name, act.get("i")) != follows:
            problems.append(f"state {k}: {name} right after {before['act']['name']}, not {follows[0]} of {follows[1]}")
        if name in absent:
            problems.append(f"state {k}: {name}, which the {topology} topology leaves out")
        if name == "Lookup" and before["act"] == act:
            problems.append(f"state {k}: the same Lookup twice in a row")
        if topology == "registry" and name == "Create" and after["domainOf"][act["i"]] == "default" \
                and act["p"] != default_provider:
            problems.append(f"state {k}: the registry's default slot built from {act['p']}")
    return problems


def _retry(state, i):
    """The instance retrying, on i's domain, with the provider that replaced the one i's failed build bound."""
    built = state["builtOn"][i]
    if built == "None":
        return None
    for j, phase in state["phase"].items():
        if phase == "building" and state["domainOf"][j] == state["domainOf"][i] \
                and state["builtOn"][j] == state["replaced"][built]:
            return j
    return None


def _follower(behaviour, k):
    """The step that must come right after state k, the instance it is of, from the model's own variables rather than
    the `then` ghost (so a generator that stops holding to it is caught), or None."""
    act = behaviour[k]["act"]
    if act["name"] == "Check":
        return "Create", act["i"]
    if act["name"] == "BuildFail":
        return "Evict", act["i"]
    if act["name"] == "ReleaseReplaced" and k >= 1:
        retry = _retry(behaviour[k - 1], act["i"])
        return ("BuildFail", retry) if retry else None
    return None


def default_provider_of(generate_cfg):
    """The provider Generate.cfg.in builds the registry's default slot from."""
    found = re.search(r"^\s*DefaultProvider = (\w+)\s*$", generate_cfg, re.M)
    if not found:
        sys.exit("Generate.cfg.in sets no DefaultProvider")
    return found.group(1)


def check_shapes(out, topology, absent, default_provider):
    """`check_shape` over every behaviour in `out`; prints each problem and returns how many behaviours have one."""
    wrong = 0
    for name in sorted(os.listdir(out)):
        if not name.endswith(".ndjson"):
            continue
        with open(os.path.join(out, name)) as f:
            problems = check_shape([json.loads(line) for line in f if line.strip()], topology, absent, default_provider)
        if problems:
            wrong += 1
            print(f"MISSHAPEN {name}: " + "; ".join(problems[:3]))
    return wrong


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("tla_jar")
    parser.add_argument("cm_jar")
    parser.add_argument("out")
    parser.add_argument("--topology", required=True, choices=sorted(TOPOLOGIES))
    parser.add_argument("--num", type=int, default=2000)
    parser.add_argument("--depth", type=int, default=30)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--close-from", type=int, default=None,
                        help="the level from which an instance, the owner or the registry may close (default: depth - 6)")
    args = parser.parse_args()
    close_from = args.close_from if args.close_from is not None else max(1, args.depth - 6)
    keys = {"topology": args.topology, "close_from": str(close_from), **TOPOLOGIES[args.topology]}
    table = by_design(args.topology, generate.read(here, "Generate.tla"), generate.read(here, "Generate.cfg.in"),
                      generate.read(here, "Registry.tla"))

    status = generate.run(here, args.tla_jar, args.cm_jar, args.out, "next:Registry.tla:Next", args.num, args.depth,
                          args.seed, keys, f", topology {args.topology}, closes from level {close_from}", table)
    wrong = check_shapes(os.path.abspath(args.out), args.topology, {a for a, (_, holds) in table.items() if holds},
                         default_provider_of(generate.read(here, "Generate.cfg.in")))
    if wrong:
        print(f"{wrong} behaviours break the generator's restrictions")
    sys.exit(1 if status or wrong else 0)


if __name__ == "__main__":
    main()
