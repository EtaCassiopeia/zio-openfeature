#!/usr/bin/env python3
"""Check that the cache's documented limits stay reachable, and that the model's properties are not vacuous.

Usage: python3 witness.py <path to tla2tools.jar>.  Each case runs TLC with one change and requires exactly the expected
verdict; a limit that became unreachable means the code or the model changed, and README.md must change with it.
"""
import os
import sys

from cachingcheck import BUMP_THEN_CLEAR, config, edit, spec, tlc

jar = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar')
# Clear, then bump, with the invalidation complete only after its last step.
clear_then_bump = [(BUMP_THEN_CLEAR, '''  IClear:
    covers := pv;
    map := [k \\in Epochs |-> Absent];
  IBump:
    epoch := epoch + 1;
    invalidatedAt := Max(invalidatedAt, covers);''')]
cases = [
    # A caller in flight across an invalidation may return its own lookup's older value.
    ('a caller in flight may return a value older than the invalidation', spec, config(['InFlightNeverStale']),
     'InFlightNeverStale'),
    # zio-cache writes the result back before the caller's invalidation removes it.
    ('the map holds a failed or DEFAULT result until its writer next steps', spec, config(['MapNeverHoldsBad']),
     'MapNeverHoldsBad'),
    # The write a lookup in flight makes across an invalidation is stranded under its old epoch, not prevented.
    ('a lookup in flight strands its write under the old epoch', spec, config(['NoStrandedWrite']), 'NoStrandedWrite'),
    # JoinersGetARun is not vacuous: a joiner does reach its retry.
    ('a caller can reach its retry', spec, config(['NoJoinerRetries']), 'NoJoinerRetries'),
    # The order does not matter once both steps have run: the old epoch's keys are unreachable after the bump.
    ('clear-then-bump keeps the contract for a caller that starts after both steps',
     edit(spec, clear_then_bump, 'clear-then-bump'), config(['NoStaleAfterInvalidate']), 'ok'),
    # Two handlers overlapping, which three callers and one change cannot show.
    ('two changes, with overlapping handlers, and two callers', spec,
     config(['NoStaleAfterInvalidate', 'NoCachedFailure', 'JoinersGetARun'], callers=2, changes=2), 'ok'),
]
failed = False
for name, module, cfg, expected in cases:
    verdict, out = tlc(jar, module, cfg)
    if verdict == expected:
        print(f'{name}: {verdict}')
    else:
        print(f'{name}: expected {expected}, got {verdict}\n{out}')
        failed = True
sys.exit(1 if failed else 0)
