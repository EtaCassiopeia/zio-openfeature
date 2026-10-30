"""Turn a recorded run (the lifecycle probe points a test passed) into the model actions it took.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>"}, possibly
out of order (several threads record), with a {"point": "Begin"} marker once the instance is built.
Output: a list of model actions, each {"action": label, ...} with what the run recorded about it (a delivered event's
type, the SDK state a swap or its rollback settled in, whether the rollback succeeded), or None when the run uses something
the model does not cover. A run without its Begin marker raises ValueError: the recording is broken.

The mapping (formal/lifecycle/README.md, "Trace validation"):
  SwapStart                          -> SwapStart
  SdkBind                            -> SdkBind
  ApplySignal(SwapSucceeded(_))      -> SwapDone, settled in its state
  SwapTimeout                        -> SwapTimeout
  SwapRollback                       -> SwapRollback, or SwapRollbackUnbound when the swap
                                        recorded no SdkBind (its registration was refused)
  OrphanRecorded, no timeout or
    rollback in the same swap        -> SwapFailNoRollback
  DeliverEvent, outside a swap and
    before any shutdown              -> Deliver
  OrphanResolved, outside a shutdown
    or scope close                   -> OrphanResolved
  ShutdownStart, ShutdownDone,
  ScopeClose                         -> themselves
Everything else is part of one of those steps: the other status writes, LateReady, the SwapDone point that closes
every swap, deliveries while a swap's gate is up (the model drops those), and deliveries once a shutdown has started
(the bridge is deregistered then; a handler already running records one anyway). Watchdog is not modelled.
"""
import json
import re
import sys


def labels(records):
    records = sorted(records, key=lambda r: r["seq"])
    begin = next((k for k, r in enumerate(records) if r["point"] == "Begin"), None)
    if begin is None:
        raise ValueError("the run has no Begin marker")
    out = []
    in_swap = False
    swap_outcome = None
    rollback = None
    bound_new = False
    in_shutdown = False
    shut_down = False
    closed = False

    def act(label, **fields):
        entry = {"action": label, "event": "", "state": "", "ok": False}
        entry.update(fields)
        out.append(entry)
        return entry

    for r in records[begin + 1:]:
        point, arg = r["point"], r.get("arg", "")
        if point == "Watchdog":
            return None
        if closed:
            continue
        if point == "ScopeClose":
            act("ScopeClose")
            closed = True
            continue
        if in_shutdown:
            if point == "ShutdownDone":
                act("ShutdownDone")
                in_shutdown = False
            continue
        if point == "ShutdownStart":
            act("ShutdownStart")
            in_shutdown = shut_down = True
            continue
        if point == "SwapStart":
            act("SwapStart")
            in_swap, swap_outcome, rollback, bound_new = True, None, None, False
            continue
        if in_swap:
            succeeded = (re.match(r"(SwapSucceeded|RollbackSucceeded)\((Ready|Stale|Error|Fatal)\b", arg)
                         if point == "ApplySignal" else None)
            if point == "SdkBind":
                act("SdkBind")
                bound_new = True
            elif succeeded and succeeded.group(1) == "SwapSucceeded" and swap_outcome is None:
                swap_outcome = "SwapDone"
                act("SwapDone", state=succeeded.group(2).upper())
            elif succeeded and succeeded.group(1) == "RollbackSucceeded" and rollback is not None:
                rollback["ok"] = True
                rollback["state"] = succeeded.group(2).upper()
            elif point == "SwapTimeout" and swap_outcome is None:
                swap_outcome = "SwapTimeout"
                act("SwapTimeout")
            elif point == "SwapRollback" and swap_outcome is None:
                swap_outcome = "SwapRollback"
                rollback = act("SwapRollback" if bound_new else "SwapRollbackUnbound")
            elif point == "OrphanRecorded" and swap_outcome is None:
                swap_outcome = "SwapFailNoRollback"
                act("SwapFailNoRollback")
            elif point == "SwapDone":
                in_swap = False
            continue
        if point == "DeliverEvent" and not shut_down:
            act("Deliver", event=arg.replace("PROVIDER_", ""))
        elif point == "OrphanResolved":
            act("OrphanResolved")
    return out


def read(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


if __name__ == "__main__":
    print(labels(read(sys.argv[1])))
