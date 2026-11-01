"""Put each fixed lifecycle bug back into the model and check that TLC reports an invariant violated for it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught.
"""
import os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Lifecycle.tla')).read()

mutants = {
    # A failed swap always rolls back, even onto a provider whose initialize() still runs.
    'a failed swap rolls back onto a provider still initializing': (
        '    /\\ ~(pending = swap.prev /\\ sdk[swap.prev] = "NOT_READY")\n    /\\ ok \\/ r',
        '    /\\ ok \\/ r'),
    # Shutdown does not wait for a swap in flight.
    'shutdown does not wait for a swap in flight': (
        'ShutdownStart ==\n    /\\ lock = "free"\n    /\\ lock\' = "shutdown"',
        'ShutdownStart ==\n    /\\ lock # "shutdown"\n    /\\ lock\' = IF lock = "swap" THEN lock ELSE "shutdown"'),
    # STALE moves the status only from Ready.
    'STALE moves the status only from Ready': (
        'IF s \\in {"NotReady", "Ready", "Error"} THEN "Stale" ELSE s',
        'IF s \\in {"Ready"} THEN "Stale" ELSE s'),
    # A successful swap always ends Ready.
    'a swap into a STALE provider ends Ready': (
        '    /\\ status\' = StatusOf(sdk[swap.new])',
        '    /\\ status\' = "Ready"'),
    # A timed-out swap forgets the provider it replaced.
    'a timed-out swap forgets the provider it replaced': (
        '    /\\ orphans\' = IF swap.prev # swap.new THEN orphans \\cup {Watch(swap.prev, swap.new)} ELSE orphans',
        '    /\\ orphans\' = orphans'),
    # The bridge applies the event's own type instead of the SDK's state for the slot.
    'the status follows the event type, not the SDK state': (
        '         ELSE Apply(status, sdk[bound])',
        '         ELSE Apply(status, ev.e)'),
    # A failed swap does not read the SDK state once its gate drops.
    'a failed swap does not read the SDK state after its gate drops': (
        '       /\\ status\' = StatusOf(r)',
        '       /\\ status\' = IF ok THEN StatusOf(r) ELSE "Error"'),
    # The swap's wait reads an ERROR the new provider reports right after initializing as a failed initialize().
    'a swap reads an error after initialize() returned as a failed initialization': [
        ('    /\\ sdk[swap.new] \\in {"READY", "STALE"} \\/ (ran[swap.new] /\\ sdk[swap.new] \\in {"ERROR", "FATAL"})',
         '    /\\ sdk[swap.new] \\in {"READY", "STALE"}'),
        ('    /\\ sdk[swap.new] \\in {"ERROR", "FATAL"} /\\ ~ran[swap.new]\n    /\\ ~(pending',
         '    /\\ sdk[swap.new] \\in {"ERROR", "FATAL"}\n    /\\ ~(pending'),
        ('    /\\ sdk[swap.new] \\in {"ERROR", "FATAL"} /\\ ~ran[swap.new]\n    /\\ pending',
         '    /\\ sdk[swap.new] \\in {"ERROR", "FATAL"}\n    /\\ pending'),
    ],
    # The rollback's wait reads an ERROR the restored provider reports right after initializing as a failed
    # rollback, whose cleanup shuts the failed provider down after the SDK already did.
    'a rollback reads an error after initialize() returned as a failed rollback': (
        "       /\\ shuts' = [shuts EXCEPT ![failed] = @ + 1]",
        "       /\\ shuts' = [shuts EXCEPT ![failed] = @ + (IF ok /\\ r \\in {\"ERROR\", \"FATAL\"} THEN 2 ELSE 1)]"),
    # The watcher of a timed-out swap's provider reads an ERROR it reports right after initializing as a failed one.
    'the orphan watcher reads an error after initialize() returned as a failed one': (
        'Released(o) == View(o) \\in {"READY", "STALE"} \\/ RanView(o)',
        'Released(o) == View(o) \\in {"READY", "STALE"}'),
    # A swap after shutdown is not refused, and revives the instance.
    'a swap after shutdown is not refused': (
        '    /\\ lock = "free" /\\ ~shutDown /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
        '    /\\ lock = "free" /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0'),
    # A failed swap that never bound its provider reads a still-initializing previous provider as Ready.
    'a failed swap that never bound reads a still-initializing provider as Ready': (
        '    /\\ status\' = IF sdk[swap.prev] = "NOT_READY" THEN swap.before ELSE Apply(swap.before, sdk[swap.prev])',
        '    /\\ status\' = IF sdk[swap.prev] = "NOT_READY" THEN "Ready" ELSE Apply(swap.before, sdk[swap.prev])'),
    # The async build does not record its provider as initializing, so a failed swap rolls back onto it.
    'the async build does not record its provider as initializing': (
        '    /\\ swap = None\n    /\\ pending = P0\n',
        '    /\\ swap = None\n    /\\ pending = None\n'),
    # The scope close does not wait for a swap in flight.
    'the scope close does not wait for a swap in flight': (
        'ScopeClose ==\n    /\\ lock = "free" /\\ ~closed',
        'ScopeClose ==\n    /\\ ~closed'),
    # A swap after the scope close is not refused.
    'a swap after the scope close is not refused': (
        '    /\\ lock = "free" /\\ ~shutDown /\\ ~closed /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0',
        '    /\\ lock = "free" /\\ ~shutDown /\\ ~Settling\n    /\\ p # bound /\\ inits[p] = 0'),
    # A provider whose earlier initialize() still runs is installed again (refuseStillInitializing removed).
    'a provider still initializing is installed again': (
        '    /\\ p # bound /\\ inits[p] = 0\n',
        '    /\\ p # bound\n'),
    # A provider a timed-out swap's entry still holds is installed again (refusePendingOrphan removed).
    'a provider a timed-out swap still holds is installed again': (
        '    /\\ ~\\E o \\in orphans : o.prev = p\n',
        ''),
    # The gate never goes up.
    'no swap gate': (
        '    /\\ gate\' = TRUE\n    /\\ status\' = "NotReady"',
        '    /\\ gate\' = FALSE\n    /\\ status\' = "NotReady"'),
}

jar = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, 'tla2tools.jar')
cfg = open(os.path.join(here, 'Lifecycle.cfg')).read()
missed = []
for name, edits in mutants.items():
    mutated = spec
    for old, new in (edits if isinstance(edits, list) else [edits]):
        if mutated.count(old) != 1:
            sys.exit(f'{name}: the mutation no longer applies to Lifecycle.tla; update mutants.py')
        mutated = mutated.replace(old, new)
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Lifecycle.tla'), 'w').write(mutated)
        open(os.path.join(d, 'Lifecycle.cfg'), 'w').write(cfg)
        out = subprocess.run(['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-deadlock',
                              '-config', 'Lifecycle.cfg', 'Lifecycle.tla'], cwd=d, capture_output=True, text=True).stdout
    m = re.search(r'Invariant (\w+) is violated', out)
    if m:
        print(f'{name}: caught by {m.group(1)}')
    else:
        print(f'{name}: NOT CAUGHT\n{out[-600:]}')
        missed.append(name)
sys.exit(1 if missed else 0)
