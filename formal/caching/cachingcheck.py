"""Shared by witness.py and mutants.py: translate the PlusCal and run TLC on a copy of Caching.tla in a scratch directory."""
import os
import re
import subprocess
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Caching.tla')).read()
caching_cfg = open(os.path.join(here, 'Caching.cfg')).read()

# The handler's two steps in the code's order: bump the epoch, then clear the map.
BUMP_THEN_CLEAR = '''  IBump:
    covers := pv;
    epoch := epoch + 1;
  IClear:
    map := [k \\in Epochs |-> Absent];
    invalidatedAt := Max(invalidatedAt, covers);'''


def edit(text, edits, what):
    for old, new in edits:
        if text.count(old) != 1:
            raise SystemExit(f'{what}: the edit no longer applies; update it')
        text = text.replace(old, new)
    return text


def config(invariants, callers=3, changes=1):
    lines = '\n'.join(f'    {name}' for name in invariants)
    return (f'CONSTANTS\n    MaxCallers = {callers}\n    MaxChanges = {changes}\n\n'
            f'SPECIFICATION Spec\n\nINVARIANTS\n{lines}\n')


def tlc(jar, module, cfg):
    """TLC's verdict: the violated invariant, 'deadlock', or 'ok'."""
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Caching.tla'), 'w').write(module)
        open(os.path.join(d, 'Caching.cfg'), 'w').write(cfg)
        run = subprocess.run(['java', '-cp', jar, 'pcal.trans', '-nocfg', 'Caching.tla'], cwd=d, capture_output=True,
                             text=True)
        pcal = run.stdout + run.stderr
        if 'Translation completed' not in pcal:
            return 'pcal failed', pcal[-1500:]
        out = subprocess.run(['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto',
                              '-config', 'Caching.cfg', 'Caching.tla'], cwd=d, capture_output=True, text=True).stdout
    found = re.findall(r'Invariant (\w+) is violated|(Deadlock) reached', out)
    names = [x.lower() if x == 'Deadlock' else x for t in found for x in t if x]
    if names:
        return names[0], out[-1500:]
    return ('ok' if 'Model checking completed. No error has been found.' in out else 'error'), out[-1500:]
