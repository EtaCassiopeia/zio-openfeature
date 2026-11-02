#!/usr/bin/env python3
"""Check that the lane's documented limits stay reachable.

Usage: python3 witness.py <path to tla2tools.jar>.  Each case runs TLC with one change and requires exactly the expected
verdict; a limit that became unreachable means the code or the model changed, and README.md must change with it.
"""
import sys

from lanecheck import edit, lane_cfg, spec, tlc, witness_cfg

jar = sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar'
order = [('    NoDrop\n', '    NoDrop\n    DelegateOrderKept\n')]
cases = [
    # A delegate that does not await its own emissions: its pool may hand them over swapped, before any lane.
    ('a delegate that does not await may see its emissions swapped', edit(lane_cfg, order, 'Lane.cfg'),
     'DelegateOrderKept'),
    ('a delegate that awaits keeps its order', edit(lane_cfg, order + [('DelegateAwaits = FALSE', 'DelegateAwaits = TRUE')],
                                                     'Lane.cfg'), 'ok'),
    # setProviderAndWait's shutdown of the old provider closes its lane under the write lock: the queue is cut off.
    ('setProviderAndWait cuts off a stalled lane', witness_cfg, 'NoDrop'),
    # setProviderAndWait with an initialize() that awaits its own emission: the emission needs the read lock.
    ('setProviderAndWait deadlocks on an initialize() that awaits its emission',
     edit(witness_cfg, [('InitAwaitsEmission = FALSE', 'InitAwaitsEmission = TRUE'), ('    NoDrop\n', '')],
          'Witness.cfg'), 'deadlock'),
]
failed = False
for name, config, expected in cases:
    verdict, out = tlc(jar, spec, config)
    if verdict == expected:
        print(f'{name}: {verdict}')
    else:
        print(f'{name}: expected {expected}, got {verdict}\n{out}')
        failed = True
sys.exit(1 if failed else 0)
