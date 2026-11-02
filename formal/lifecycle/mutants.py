"""Put each fixed lifecycle bug back into the model and check that TLC reports an invariant violated for it; then put
each progress bug in and check that TLC reports its recovery property violated under FairSpec.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught.
"""
import os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Lifecycle.tla')).read()

mutants = {
    # The swap body is interruptible: an interrupt of the caller skips the outcome and the rollback.
    'the swap body is interruptible': (
        'Interruptible == {}',
        'Interruptible == {"bind", "wait"}'),
    # The re-read of the slot after the outcome is interruptible: an interrupt skips it.
    'the reconcile is interruptible': (
        'Interruptible == {}',
        'Interruptible == {"reconcile"}'),
    # An interrupted swap does not drop its gate.
    'an interrupted swap leaves its gate up': [
        ('Interruptible == {}', 'Interruptible == {"reconcile"}'),
        ("    /\\ gate' = FALSE /\\ lock' = \"free\" /\\ swap' = None\n    /\\ UNCHANGED << status, installed,",
         "    /\\ lock' = \"free\" /\\ swap' = None\n    /\\ UNCHANGED << gate, status, installed,"),
    ],
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
        '    /\\ orphans\' = IF owns /\\ swap.prev # swap.new THEN orphans \\cup {Watch(swap.prev, swap.new)} ELSE orphans',
        '    /\\ orphans\' = orphans'),
    # The bridge applies the event's own type instead of the SDK's state for the slot.
    'the status follows the event type, not the SDK state': (
        '         ELSE Apply(status, sdk[bound])',
        '         ELSE Apply(status, ev.e)'),
    # A swap does not read the SDK state once its gate drops.
    'a swap does not read the SDK state after its gate drops': (
        '    /\\ status\' = IF sdk[bound] = "NOT_READY" THEN status ELSE Apply(status, sdk[bound])',
        '    /\\ status\' = status'),
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
        '    /\\ status\' = swap.before\n',
        '    /\\ status\' = IF sdk[swap.prev] = "NOT_READY" THEN "Ready" ELSE swap.before\n'),
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
    # A provider that a registration still initializing replaced is installed again (refuseReplacedByPendingInit
    # removed): an instance that shares its API, which records no orphans, gets it shut down by the SDK.
    'a provider a registration still initializing replaced is installed again': (
        '    /\\ ~\\E q \\in All : prevOf[q] = p /\\ inits[q] > 0\n',
        ''),
    # The gate never goes up.
    'no swap gate': (
        '    /\\ gate\' = TRUE\n    /\\ status\' = "NotReady"',
        '    /\\ gate\' = FALSE\n    /\\ status\' = "NotReady"'),
}

# Progress bugs: each must make TLC report the named temporal property violated under FairSpec (Liveness.cfg's bounds,
# checked against that property alone, so the violation is that property's).
liveness = {
    # The wait for a new provider has no timeout: a provider whose initialize() never returns holds the swap forever.
    'a swap waits for a hanging initialize() without a timeout': ('SwapEnds', [
        ('    \\/ SdkBind \\/ SdkBindFail \\/ SwapDone \\/ SwapTimeout \\/ SwapFailNoRollback',
         '    \\/ SdkBind \\/ SdkBindFail \\/ SwapDone \\/ SwapFailNoRollback'),
        ('    \\/ SwapTimeout\n', ''),
    ]),
    # The teardown waits for every orphan to settle, which a provider that never settles holds back forever.
    'shutdown waits for the orphans to settle': ('ShutdownEnds', [
        ('ShutdownDone ==\n    /\\ lock = "shutdown"\n', 'ShutdownDone ==\n    /\\ lock = "shutdown" /\\ orphans = {}\n'),
    ]),
    # A watch that resolved is never acted on.
    'a resolved orphan is never settled': ('OrphansSettleOrTearDown', [
        ('OrphanResolved(o) ==\n    /\\ o \\in orphans\n', 'OrphanResolved(o) ==\n    /\\ FALSE /\\ o \\in orphans\n'),
    ]),
}

jar = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, 'tla2tools.jar')
cfg = open(os.path.join(here, 'Lifecycle.cfg')).read()
live_cfg = open(os.path.join(here, 'Liveness.cfg')).read()
missed = []


def mutate(name, edits):
    mutated = spec
    for old, new in (edits if isinstance(edits, list) else [edits]):
        if mutated.count(old) != 1:
            sys.exit(f'{name}: the mutation no longer applies to Lifecycle.tla; update mutants.py')
        mutated = mutated.replace(old, new)
    return mutated


def tlc(mutated, config, deadlock):
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Lifecycle.tla'), 'w').write(mutated)
        open(os.path.join(d, 'Lifecycle.cfg'), 'w').write(config)
        args = ['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto']
        args += (['-deadlock'] if deadlock else []) + ['-config', 'Lifecycle.cfg', 'Lifecycle.tla']
        return subprocess.run(args, cwd=d, capture_output=True, text=True).stdout


for name, edits in mutants.items():
    out = tlc(mutate(name, edits), cfg, deadlock=True)
    m = re.search(r'Invariant (\w+) is violated', out)
    if m:
        print(f'{name}: caught by {m.group(1)}')
    else:
        print(f'{name}: NOT CAUGHT\n{out[-600:]}')
        missed.append(name)

for name, (prop, edits) in liveness.items():
    config = re.sub(r'PROPERTIES\n(    \w+\n)+', f'PROPERTIES\n    {prop}\n', live_cfg)
    out = tlc(mutate(name, edits), config, deadlock=True)
    m = re.search(r'Temporal propert(?:y|ies) (\w+)? ?was violated', out)
    if m and m.group(1) in (None, prop):
        print(f'{name}: caught by {prop}')
    else:
        print(f'{name}: NOT CAUGHT by {prop}\n{out[-600:]}')
        missed.append(name)
sys.exit(1 if missed else 0)
