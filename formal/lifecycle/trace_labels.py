"""Turn a recorded run (the lifecycle probe points a test passed) into the model actions it took.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>"}, possibly
out of order (several threads record), with a {"point": "Begin"} marker once the instance is built. A line may also
name the providers its point concerns: `providerInstance` (and `watchInstance`), an identity hash that tells apart
providers sharing a name; `provider`/`watch` are their names, `caller` the thread of a shutdown.
Output: a list of model actions, each {"action": label, ...} with what the run recorded about it (a delivered event's
type, the SDK state a swap or its rollback settled in, whether the rollback succeeded), or None when the run uses something
the model does not cover. A run without its Begin marker raises ValueError: the recording is broken.

The mapping (formal/lifecycle/README.md, "Trace validation"):
  SwapStart                          -> SwapStart, naming its provider
  SdkBind                            -> SdkBind
  ApplySignal(SwapSucceeded(_))      -> SwapDone, settled in its state
  SwapTimeout                        -> SwapTimeout
  SwapReconcile                      -> SwapReconcile
  SwapRollback                       -> SwapRollback, or SwapRollbackUnbound when the swap
                                        recorded no SdkBind (its registration was refused)
  OrphanRecorded, no timeout or
    rollback in the same swap        -> SwapFailNoRollback
  OrphanRecorded after a rollback    -> the run is not covered: a rollback that timed out, whose wait the model folds
                                        into SwapRollback
  DeliverEvent, outside a swap and
    before any shutdown              -> Deliver
  OrphanResolved, outside a shutdown
    or scope close, inside a swap
    too (a failed swap settles an
    earlier one's entry)             -> OrphanResolved, naming the entry's provider and the one it watches
  ShutdownStart, ShutdownDone,
  ScopeClose                         -> themselves
A named provider is `q0` (the Begin line's instance, and only that), `q1`, `q2`, ... by first appearance of its
instance in seq order: a label's `provider` and `watch` hold that name, or "" when the record carries no identity.
Everything else is part of one of those steps, or no action at all: the other status writes, LateReady, SwapSkipped (a
setProvider of the bound provider, which changes nothing), the SwapDone point that closes
every swap, GateDown (the gate dropping inside it), deliveries while a swap's gate is up (the model drops those), and deliveries once a shutdown has started
(the bridge is deregistered then; a handler already running records one anyway). Watchdog is not modelled.
AwaitSdkShutDown is the wait SwapStart's guard stands for, before the swap starts.
InitEntered and InitReturned, which the registered wrapper records, are SDK steps (an `initialize()` running and
returning), which the check leaves free. ProviderShutDown and ShutdownReturned(ok) are counted on the teardown they
fall in: a ShutdownDone or ScopeClose label carries `shuts`, how many providers' shutdown() the teardown called,
`threw`, how many of those calls threw, and `returned`, how many returned at all (the rest outlived the bound); the
model's TearDown may shut down no more providers, and no more of each behaviour, than the run counted (the SDK's
executor can run a queued shutdown inside the teardown that the model placed before it, so the run may count more).
Every label carries `owner`: an OrphanRecorded point marks the instance as owning its API, the topology whose teardown
the counts constrain. Shutdown points outside a teardown (the SDK's shutDownOld, an orphan's settle, a rollback's
cleanup) stay free. A run recorded before the wrapper had these
points carries `shuts = -1`, and the check does not constrain its teardowns.
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
    names = {}

    def who(r, key):
        """The model name of the provider instance `r` records under `key`, or "" when it records none."""
        instance = r.get(key)
        if not instance:
            return ""
        # q0 is the Begin line's provider alone: a run whose Begin names none must not make its first swap's q0.
        return names.setdefault(instance, f"q{len(names) + (0 if 'q0' in names.values() else 1)}")

    initial = records[begin].get("providerInstance")
    if initial:
        names[initial] = "q0"
    for r in records[begin + 1:]:  # first appearance in seq order, not in the order the labels are made
        for key in ("providerInstance", "watchInstance"):
            who(r, key)
    recorded = any(r["point"] == "ProviderShutDown" for r in records)
    # Only an instance that owns its API records an orphan, so one marks the run's instance as the owner; the model
    # then takes the owning topology, whose teardown the counts constrain.
    owner = any(r["point"] == "OrphanRecorded" for r in records)
    teardown = None
    in_swap = False
    swap_outcome = None
    rollback = None
    bound_new = False
    in_shutdown = False
    shut_down = False
    closed = False

    def act(label, **fields):
        entry = {"action": label, "event": "", "state": "", "ok": False, "shuts": -1, "threw": 0, "returned": 0,
                 "owner": owner, "provider": "", "watch": ""}
        entry.update(fields)
        out.append(entry)
        return entry

    calls = set()  # the shutdown() calls the current teardown made, by number, until they return

    def count(entry, point, arg):
        if entry is None or not recorded:
            return
        if point == "ProviderShutDown":
            entry["shuts"] = max(entry["shuts"], 0) + 1
            calls.add(arg)
        # A return counts for the teardown that made the call; one whose call came before the teardown (a late return
        # of the SDK's queued shutdown) does not.  A run recorded before the points numbered their call pairs returns
        # with calls by count.
        elif point == "ShutdownReturned":
            parts = arg.split()
            call, ok = (parts[0], parts[1]) if len(parts) == 2 else ("", parts[0])
            if (call in calls) if call else entry["returned"] < entry["shuts"]:
                calls.discard(call)
                entry["returned"] += 1
                entry["threw"] += ok == "false"

    for r in records[begin + 1:]:
        point, arg = r["point"], r.get("arg", "")
        if point == "Watchdog":
            return None
        if closed:
            count(teardown, point, arg)
            continue
        if point == "ScopeClose":
            teardown = act("ScopeClose", shuts=0 if recorded else -1)
            calls.clear()
            closed = True
            continue
        if in_shutdown:
            if point == "ShutdownDone":
                teardown["action"] = "ShutdownDone"
                in_shutdown = False
            else:
                count(teardown, point, arg)
            continue
        if point == "ShutdownStart":
            act("ShutdownStart")
            # The teardown's points come between ShutdownStart and ShutdownDone; the entry is labelled at ShutdownDone.
            teardown = act("ShutdownDone", shuts=0 if recorded else -1)
            calls.clear()
            in_shutdown = shut_down = True
            continue
        if point == "SwapStart":
            act("SwapStart", provider=who(r, "providerInstance"))
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
            elif point == "OrphanRecorded" and swap_outcome == "SwapRollback":
                return None
            elif point == "OrphanRecorded" and swap_outcome is None:
                swap_outcome = "SwapFailNoRollback"
                act("SwapFailNoRollback")
            elif point == "SwapReconcile":
                act("SwapReconcile")
            # A failed swap settles an earlier swap's orphan before it ends; the model's OrphanResolved takes no lock.
            elif point == "OrphanResolved":
                act("OrphanResolved", provider=who(r, "providerInstance"), watch=who(r, "watchInstance"))
            elif point == "SwapDone":
                in_swap = False
            continue
        if point == "DeliverEvent" and not shut_down:
            act("Deliver", event=arg.replace("PROVIDER_", ""))
        elif point == "OrphanResolved":
            act("OrphanResolved", provider=who(r, "providerInstance"), watch=who(r, "watchInstance"))
    # A run cut off inside a shutdown never reached ShutdownDone: the entry made for it is not a step it took.
    if in_shutdown:
        del out[-1]
    return out


def read(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


if __name__ == "__main__":
    print(labels(read(sys.argv[1])))
