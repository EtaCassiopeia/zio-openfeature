"""Shared by witness.py and mutants.py: translate the PlusCal and run TLC on a copy of CircuitBreaker.tla in a scratch
directory."""
import os
import re
import subprocess
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
# The checked-in translation is left out, so an edit sees the algorithm's text only once. pcal.trans fills it back in.
spec = re.sub(r'(\\\* BEGIN TRANSLATION).*?(\\\* END TRANSLATION)[^\n]*', r'\1\n\2',
              open(os.path.join(here, 'CircuitBreaker.tla')).read(), flags=re.DOTALL)
main_cfg = open(os.path.join(here, 'CircuitBreaker.cfg')).read()
signals_cfg = open(os.path.join(here, 'Signals.cfg')).read()
liveness_cfg = open(os.path.join(here, 'Liveness.cfg')).read()


def edit(text, edits, what):
    for old, new in edits:
        if text.count(old) != 1:
            raise SystemExit(f'{what}: the edit no longer applies; update it')
        text = text.replace(old, new)
    return text


def tlc(jar, module, config):
    """TLC's verdict: the violated invariant or action property, 'temporal' for a violated liveness property, or 'ok'."""
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'CircuitBreaker.tla'), 'w').write(module)
        open(os.path.join(d, 'CircuitBreaker.cfg'), 'w').write(config)
        run = subprocess.run(['java', '-cp', jar, 'pcal.trans', '-nocfg', 'CircuitBreaker.tla'], cwd=d,
                             capture_output=True, text=True)
        pcal = run.stdout + run.stderr
        if 'Translation completed' not in pcal:
            return 'pcal failed', pcal[-1500:]
        out = subprocess.run(['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-deadlock',
                              '-config', 'CircuitBreaker.cfg', 'CircuitBreaker.tla'], cwd=d, capture_output=True,
                             text=True).stdout
    named = re.search(r'(?:Invariant|Action property) (\w+) is violated', out)
    if named:
        return named.group(1), out[-1500:]
    # TLC 1.7.4 does not name the property and 1.8 does, so a config checks one temporal property per run.
    if re.search(r'Temporal (?:properties were|property \w+ was) violated', out):
        return 'temporal', out[-1500:]
    return ('ok' if 'Model checking completed. No error has been found.' in out else 'error'), out[-1500:]
