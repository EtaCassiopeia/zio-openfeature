#!/usr/bin/env python3
"""Put each bug the lane's design excludes into the model and check that TLC reports it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught, or is caught by
anything but its expected verdict.
"""
import sys

from lanecheck import edit, lane_cfg, spec, tlc

jar = sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar'
LANE_WAIT = '''  LEmit:
      task[laneCur] := "lock";
  LAwait:
      await task[laneCur] = "done";'''
mutants = {
    # A second lane thread: two emissions run at once and may write the slot in either order.
    'two lane threads': ('SlotOrderFollowsReceipt', spec, [
        ('fair process Lane = "lane"', 'fair process Lane \\in {"lane", "lane2"}')]),
    # The lane holds the write lock while it waits: its emission's task needs the read lock.  A direct cycle.
    'the lane holds the write lock while it waits': ('deadlock', spec, [(LANE_WAIT, '''  LEmit:
      await CanWrite;
      writer := "lane";
      task[laneCur] := "lock";
  LAwait:
      await task[laneCur] = "done";
      writer := "none";''')]),
    # The lane holds the read lock while it waits: a queued writer blocks the task's read request, and waits for the
    # lane's read lock.
    'the lane holds the read lock while it waits': ('deadlock', spec, [(LANE_WAIT, '''  LEmit:
      await CanRead;
      readers := readers + 1;
      task[laneCur] := "lock";
  LAwait:
      await task[laneCur] = "done";
      readers := readers - 1;''')]),
    # shutdown closes the lane under the write lock and waits for it without a bound.
    'shutdown waits for the lane under the write lock': ('deadlock', spec, [('''  SRelease:
    writer := "none";
  SClose:
    open := FALSE;
  SWait:
    either
      await queue = <<>> /\\ pc["lane"] \\in {"LTake", "Done"};
    or
      await Stalled;
      cutOff := cutOff \\o queue;
      queue := <<>>;
    end either;''', '''  SClose:
    open := FALSE;
  SWait:
    await queue = <<>> /\\ pc["lane"] \\in {"LTake", "Done"};
  SRelease:
    writer := "none";''')]),
    # The forwarder waits for the lane to go idle: it blocks the delegate's pool.
    'the forwarder waits for the lane': ('ForwardNeverBlocks', spec, [
        ('      with e \\in inflight do', '      await queue = <<>>;\n      with e \\in inflight do')]),
}
missed = []
for name, (expected, module, edits) in mutants.items():
    verdict, out = tlc(jar, edit(module, edits, name), lane_cfg)
    if verdict == expected:
        print(f'{name}: caught ({verdict})')
    else:
        print(f'{name}: NOT CAUGHT as {expected}, got {verdict}\n{out}')
        missed.append(name)
# The library calls setProviderAndWait: the clause the lock argument rests on.
verdict, out = tlc(jar, spec, edit(lane_cfg, [('LibraryCallsSetProviderAndWait = FALSE',
                                               'LibraryCallsSetProviderAndWait = TRUE')], 'Lane.cfg'))
if verdict == 'NoDrop':
    print(f'the library calls setProviderAndWait: caught ({verdict})')
else:
    print(f'the library calls setProviderAndWait: NOT CAUGHT as NoDrop, got {verdict}\n{out}')
    missed.append('setProviderAndWait')
sys.exit(1 if missed else 0)
