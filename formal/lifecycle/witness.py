#!/usr/bin/env python3
"""Check that the lifecycle model can reach a silently dead provider reported as Ready.

Usage: python3 witness.py <path to tla2tools.jar>.  Runs TLC on Witness.cfg twice, with AllowDeath = TRUE:
  1. without SilentDeathUndetected: every invariant must hold while providers may die silently;
  2. with it: TLC must report SilentDeathUndetected violated, and nothing else.
A second run that finds no violation means the library now detects a silent death (or the model lost Die), and
README.md's account of the gap must change with the model.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
jar = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(here, 'tla2tools.jar')
witness_cfg = open(os.path.join(here, 'Witness.cfg')).read()


def tlc(config):
    with tempfile.TemporaryDirectory() as d:
        shutil.copy(os.path.join(here, 'Lifecycle.tla'), d)
        open(os.path.join(d, 'Witness.cfg'), 'w').write(config)
        return subprocess.run(['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto',
                               '-config', 'Witness.cfg', 'Lifecycle.tla'],
                              cwd=d, capture_output=True, text=True).stdout


def fail(message, out):
    print(message)
    print(out[-3000:])
    sys.exit(1)


if '    SilentDeathUndetected\n' not in witness_cfg:
    fail('Witness.cfg does not check SilentDeathUndetected', '')

out = tlc(witness_cfg.replace('    SilentDeathUndetected\n', ''))
if 'Model checking completed. No error has been found.' not in out:
    fail('with silent deaths allowed, the model fails:', out)
print('every invariant holds while providers may die silently')

out = tlc(witness_cfg)
violated = re.findall(r'Invariant (\w+) is violated', out)
if violated != ['SilentDeathUndetected']:
    fail('unreachable: TLC found no state where a silently dead provider is reported Ready' if not violated
         else 'violated: ' + ', '.join(violated), out)
print('reachable: the instance reports Ready while its provider has silently died')
