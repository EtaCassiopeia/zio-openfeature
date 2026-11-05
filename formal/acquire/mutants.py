#!/usr/bin/env python3
"""Check the code at 2.0.0 and each bug the ownership design excludes against Acquire.tla.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when one is not caught by the invariant named for it.
Each runs with that invariant alone: with several, TLC reports whichever its workers reach first.
"""
import os
import re
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
jar = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar')
spec = open(os.path.join(here, 'Acquire.tla')).read()

FIX_RELEASE = spec[spec.index('FixRelease1 =='):spec.index('Release == CurrentRelease1')]
CLOSE_FIRST = r'''FixRelease1 ==
    /\ Fix /\ rel = 0 /\ rel' = 1
    /\ CloseCandidate
    /\ UNCHANGED <<pc, real, serving, owned, composed, stackShut>>

FixRelease2 ==
    /\ Fix /\ rel = 1 /\ rel' = 2
    /\ (InterruptBetween \/ InterruptInInit \/ (pc \in Stopped /\ UNCHANGED <<pc, real, serving, owned, shutdowns, closed, usedClosed, composed, stackShut>>))

FixRelease3 == Fix /\ rel = 2 /\ ApiShutdown(3)

'''

# name: (invariant, Fix, UserShuts, Owning, edits of the design)
cases = {
    'the code at 2.0.0 leaks a bare provider': ('NoLeak', False, False, True, []),
    'the code at 2.0.0 leaks a bare stack': ('NoStackLeak', False, False, True, []),
    'the code at 2.0.0 shuts a failed chain down twice': ('ShutDownOnce', False, False, True, []),
    'the code at 2.0.0 adds the finalizer\'s shutdown': ('ShutDownOnce', False, True, True, []),
    'the code at 2.0.0 closes resources first': ('ShutDownBeforeResources', False, False, True, []),
    'owned is not cleared on the switch': ('ShutDownOnce', True, False, True, [
        ('''    /\\ serving' = TRUE
    /\\ owned' = "none"''', '''    /\\ serving' = TRUE
    /\\ owned' = owned''')]),
    'owned is not set on the attempt': ('NoLeak', True, False, True, [
        ('''owned' = IF Fix THEN "real" ELSE "none"''', '''owned' = "none"''')]),
    'owned does not move to the stack on compose': ('NoStackLeak', True, False, True, [
        ('''owned' = IF Fix THEN "stack" ELSE "none"''', "owned' = owned")]),
    'the candidate closes before the API shuts down': ('ShutDownBeforeResources', True, False, True, [
        (FIX_RELEASE, CLOSE_FIRST)]),
    'the chain does not absorb its second shutdown': ('ShutDownOnce', True, False, True, [
        ('shutdowns + (IF Fix THEN 1 ELSE 2)', 'shutdowns + 2')]),
}


def edit(text, edits, what):
    for old, new in edits:
        if text.count(old) != 1:
            raise SystemExit(f'{what}: the edit no longer applies; update it')
        text = text.replace(old, new)
    return text


def tlc(module, invariant, fix, user_shuts, owning):
    cfg = f'''CONSTANTS
    Fix = {str(fix).upper()}
    UserShuts = {str(user_shuts).upper()}
    Owning = {str(owning).upper()}

SPECIFICATION Spec

INVARIANT {invariant}
'''
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Acquire.tla'), 'w').write(module)
        open(os.path.join(d, 'Acquire.cfg'), 'w').write(cfg)
        out = subprocess.run(['java', '-cp', jar, 'tlc2.TLC', '-config', 'Acquire.cfg', 'Acquire.tla'], cwd=d,
                             capture_output=True, text=True).stdout
    found = re.findall(r'Invariant (\w+) is violated', out)
    if found:
        return found[0], out
    return ('ok' if 'Model checking completed. No error has been found.' in out else 'error'), out


missed = []
for name, (invariant, fix, user_shuts, owning, edits) in cases.items():
    verdict, out = tlc(edit(spec, edits, name), invariant, fix, user_shuts, owning)
    if verdict == invariant:
        print(f'{name}: caught ({verdict})')
    else:
        print(f'{name}: NOT CAUGHT as {invariant}, got {verdict}\n{out[-1500:]}')
        missed.append(name)
sys.exit(1 if missed else 0)
