#!/usr/bin/env python3
"""Put each bug the cache's design excludes into the model and check that TLC reports it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught, or is caught by
anything but its expected verdict.
"""
import os
import sys

from cachingcheck import BUMP_THEN_CLEAR, caching_cfg, edit, spec, tlc

jar = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar')
INVALIDATE = '''    if got.res # "ok" then
      map[ck] := Absent;'''
mutants = {
    # One key for every epoch: a lookup in flight across an invalidation repopulates the map a later caller reads.
    'the epoch is dropped from the key': ('NoStaleAfterInvalidate', [('Key(e) == e ', 'Key(e) == 0 ')]),
    # Only DEFAULT and error-code results are removed: a thrown failure stays for the TTL.
    'a failure is not invalidated': ('NoCachedFailure', [
        (INVALIDATE, INVALIDATE.replace('got.res # "ok"', 'got.res = "soft"'))]),
    # Only thrown failures are removed: one caller's DEFAULT stays, for others to be served.
    'a DEFAULT result is not invalidated': ('NoCachedFailure', [
        (INVALIDATE, INVALIDATE.replace('got.res # "ok"', 'got.res = "fail"'))]),
    # No retry at all: a joiner returns the failure of the lookup it joined.
    'a joiner is not retried': ('JoinersGetARun', [('if got.res # "fail" \\/ ran then', 'if TRUE then')]),
    # The retry runs cache.invalidate(ck) *> lookup: between the two another caller's lookup may answer it.
    'the retry goes back through the shared cache': ('JoinersGetARun', [
        ('    got = NoExit;    \\* the outcome it holds', '    retried = FALSE,\n    got = NoExit;'),
        ('if got.res # "fail" \\/ ran then', 'if got.res # "fail" \\/ ran \\/ retried then'),
        ('''  CRetry:
    map[ck] := Absent;
  CRetryEval:
    ran := TRUE;
    with r \\in {"ok", "soft", "fail"} do
      got := [res |-> r, pv |-> pv];
    end with;
''', '''  CRetry:
    map[ck] := Absent;
    retried := TRUE;
    goto CGet;
''')]),
    # Clear, then bump: a caller that starts once the map is cleared still reads the old epoch, and a lookup in flight
    # can write under it first.  The invalidation counts from its clear here; witness.py has the order's control.
    'clear-then-bump, for a caller that starts after the clear': ('NoStaleAfterInvalidate', [(BUMP_THEN_CLEAR, '''  IClear:
    map := [k \\in Epochs |-> Absent];
    invalidatedAt := Max(invalidatedAt, pv);
  IBump:
    epoch := epoch + 1;''')]),
}
missed = []
for name, (expected, edits) in mutants.items():
    verdict, out = tlc(jar, edit(spec, edits, name), caching_cfg)
    if verdict == expected:
        print(f'{name}: caught ({verdict})')
    else:
        print(f'{name}: NOT CAUGHT as {expected}, got {verdict}\n{out}')
        missed.append(name)
sys.exit(1 if missed else 0)
