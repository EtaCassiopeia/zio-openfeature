"""Shared by witness.py and mutants.py: translate the PlusCal and run TLC on a copy of Lane.tla in a scratch directory."""
import os
import re
import subprocess
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Lane.tla')).read()
lane_cfg = open(os.path.join(here, 'Lane.cfg')).read()
witness_cfg = open(os.path.join(here, 'Witness.cfg')).read()


def edit(text, edits, what):
    for old, new in edits:
        if text.count(old) != 1:
            raise SystemExit(f'{what}: the edit no longer applies; update it')
        text = text.replace(old, new)
    return text


def tlc(jar, module, config):
    """TLC's verdict: the violated invariants and properties, 'deadlock', or 'ok'."""
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Lane.tla'), 'w').write(module)
        open(os.path.join(d, 'Lane.cfg'), 'w').write(config)
        run = subprocess.run(['java', '-cp', jar, 'pcal.trans', '-nocfg', 'Lane.tla'], cwd=d, capture_output=True,
                             text=True)
        pcal = run.stdout + run.stderr
        if 'Translation completed' not in pcal:
            return 'pcal failed', pcal[-1500:]
        out = subprocess.run(['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto',
                              '-config', 'Lane.cfg', 'Lane.tla'], cwd=d, capture_output=True, text=True).stdout
    found = re.findall(r'Invariant (\w+) is violated|Temporal propert(?:y|ies) (\w+) was violated|(Deadlock) reached', out)
    names = [x.lower() if x == 'Deadlock' else x for t in found for x in t if x]
    if names:
        return names[0], out[-1500:]
    return ('ok' if 'Model checking completed. No error has been found.' in out else 'error'), out[-1500:]
