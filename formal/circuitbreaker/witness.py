#!/usr/bin/env python3
"""Check that the races the properties are about stay reachable.

Usage: python3 witness.py <path to tla2tools.jar>.  Each case asks TLC for one race, stated as if it could not happen, and
requires exactly that statement violated.  A race that became unreachable means the model no longer exercises the
property that rests on it, so a pass there would be vacuous.
"""
import os
import sys

from breakercheck import edit, main_cfg, signals_cfg, spec, tlc

jar = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar')
INVARIANT, PROPERTY = 'INVARIANTS\n', 'PROPERTIES\n'
cases = [
    ('a CAS loses to another thread', main_cfg, INVARIANT, 'LostCasNeverHappens'),
    ('an outcome arrives after its permit has passed', main_cfg, INVARIANT, 'OutcomesAreNeverLate'),
    ('a probe dies holding the slot', main_cfg, INVARIANT, 'SlotNeverWedges'),
    ('a reset meets a circuit that failures opened', main_cfg, INVARIANT, 'ResetNeverMeetsFailures'),
    ('a caller takes over an occupied slot', main_cfg, PROPERTY, 'NoTakeover'),
    ('probes close the circuit', main_cfg, PROPERTY, 'ProbesNeverClose'),
    ('a stale event takes over a stale poll', signals_cfg, PROPERTY, 'PollNeverHandsOverStale'),
]
failed = False
for name, config, section, statement in cases:
    verdict, out = tlc(jar, spec, edit(config, [(section, f'{section}    {statement}\n')], statement))
    if verdict == statement:
        print(f'{name}: reachable ({verdict})')
    else:
        print(f'{name}: expected {statement} violated, got {verdict}\n{out}')
        failed = True
sys.exit(1 if failed else 0)
