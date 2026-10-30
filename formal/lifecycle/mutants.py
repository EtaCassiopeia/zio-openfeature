"""Put each fixed lifecycle bug back into the model and check that TLC reports an invariant violated for it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught.
"""
import os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Lifecycle.tla')).read()

mutants = {
    # A failed swap always rolls back, even onto a provider whose initialize() still runs.
    'a failed swap rolls back onto a provider still initializing': (
        '    /\\ ~(pending = swap.prev /\\ sdk[swap.prev] = "NOT_READY")\n    /\\ LET prev == swap.prev',
        '    /\\ LET prev == swap.prev'),
    # Shutdown does not wait for a swap in flight.
    'shutdown does not wait for a swap in flight': (
        'ShutdownStart ==\n    /\\ lock = "free" /\\ ~closed\n    /\\ lock\' = "shutdown"',
        'ShutdownStart ==\n    /\\ lock # "shutdown" /\\ ~closed\n    /\\ lock\' = IF lock = "swap" THEN lock ELSE "shutdown"'),
    # STALE moves the status only from Ready.
    'STALE moves the status only from Ready': (
        'IF s \\in {"NotReady", "Ready", "Error"} THEN "Stale" ELSE s',
        'IF s \\in {"Ready"} THEN "Stale" ELSE s'),
    # A successful swap always ends Ready.
    'a swap into a STALE provider ends Ready': (
        '    /\\ status\' = IF sdk[swap.new] = "STALE" THEN "Stale" ELSE "Ready"\n    /\\ gate\' = FALSE /\\ lock\' = "free" /\\ swap\' = None /\\ pending\' = None\n    /\\ UNCHANGED << installed, orphans',
        '    /\\ status\' = "Ready"\n    /\\ gate\' = FALSE /\\ lock\' = "free" /\\ swap\' = None /\\ pending\' = None\n    /\\ UNCHANGED << installed, orphans'),
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
        '                 /\\ status\' = IF fatal THEN "Fatal" ELSE "Error"',
        '                 /\\ status\' = "Error"'),
    # The gate never goes up.
    'no swap gate': (
        '    /\\ gate\' = TRUE\n    /\\ status\' = "NotReady"',
        '    /\\ gate\' = FALSE\n    /\\ status\' = "NotReady"'),
}

jar = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, 'tla2tools.jar')
cfg = open(os.path.join(here, 'Lifecycle.cfg')).read()
missed = []
for name, (old, new) in mutants.items():
    if spec.count(old) != 1:
        sys.exit(f'{name}: the mutation no longer applies to Lifecycle.tla; update mutants.py')
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Lifecycle.tla'), 'w').write(spec.replace(old, new))
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
