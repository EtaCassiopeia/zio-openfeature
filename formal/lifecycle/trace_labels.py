"""Turn a recorded run (the lifecycle probe points a test passed) into the model actions it took.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>"}, possibly
out of order (several threads record), with a {"point": "Begin"} marker once the instance is built. A line may also
name the providers its point concerns: `providerInstance` (and `watchInstance`), an identity hash that tells apart
providers sharing a name; `provider`/`watch` are their names, `caller` the thread of a shutdown.
Output: a list of model actions, each {"action": label, ...} with what the run recorded about it (a delivered event's
type, the SDK state a swap or its rollback settled in, whether the rollback succeeded), or None when the run uses something
the model does not cover. The Begin line's arg is "pending" when the initial provider's initialize() had not settled by
then (an async build); every label carries that as `pending`. A run without its Begin marker raises ValueError: the
recording is broken.

The mapping (formal/lifecycle/README.md, "Trace validation"):
  SwapStart                          -> SwapStart, naming its provider
  SdkBind                            -> SdkBind
  ApplySignal(SwapSucceeded(_))      -> SwapDone, settled in its state
  SwapTimeout                        -> SwapTimeout
  SwapReconcile                      -> SwapReconcile, carrying `status`: the last StatusWritten before the swap's
                                        SwapDone point, which is the status the swap ends on ("" when it wrote none, or
                                        when an event was delivered between SwapReconcile and SwapDone: the model's
                                        step does not include a write that comes after it)
  SwapRollback                       -> SwapRollback, or SwapRollbackUnbound when the swap
                                        recorded no SdkBind (its registration was refused)
  OrphanRecorded, no timeout or
    rollback in the same swap        -> SwapFailNoRollback
  ApplySignal(SwapFailed), no other
    outcome by SwapReconcile         -> SwapFailNoRollback, before SwapReconcile (an instance that does not own its API
                                        records no orphan)
  OrphanRecorded after a rollback    -> the run is not covered: a rollback that timed out, whose wait the model folds
                                        into SwapRollback
  DeliverEvent, outside a swap and
    before any shutdown              -> Deliver, carrying `status`: the StatusWritten that follows it, when exactly one
                                        delivery is waiting for its write and the status is not Fatal (the model's SDK
                                        state FATAL is final for a provider, the SDK's is not)
  DeliverEvent in a swap after
    GateDown, no InitOutcome since,
    before SwapReconcile, from the
    provider the slot binds (by name) -> Deliver, without a status: the gate is down in the code but not in the model,
                                        whose SwapReconcile reads the state the event reported
  OrphanDropped, outside a shutdown
    or scope close, inside a swap
    too (a failed swap settles an
    earlier one's entry)             -> OrphanResolved, naming the entry's provider and the one it watches. The claim
                                        (the OrphanResolved point) comes before the settle's wait for the SDK's shutdown
                                        and is no step: the entry leaves the list at OrphanDropped.
                                        An entry claimed and its provider shut down before a teardown starts is resolved
                                        there, before the teardown's step, when its drop comes after the teardown began.
  InitOutcome                        -> InitOutcome, naming the provider, `ok` whether its initialize() returned. A
                                        pending Begin's own provider may record it just before the Begin line.
  ProviderShutDown outside a
    teardown, on the SDK's executor  -> ShutDownOld, naming the provider (the SDK's queued shutdown of a replaced one)
  ShutdownStart, ShutdownDone,
  ScopeClose                         -> themselves
A named provider is `q0` (the Begin line's instance, and only that), `q1`, `q2`, ... by first appearance of its
instance in seq order: a label's `provider` and `watch` hold that name, or "" when the record carries no identity.
Everything else is part of one of those steps, or no action at all: the StatusWritten points (read as the `status` of the
step they fall in), LateReady, SwapSkipped (a
setProvider of the bound provider, which changes nothing), the SwapDone point that closes every swap, GateDown (the
gate dropping inside it), deliveries while a swap's gate is up (the model drops those) and those after it that are not
the bound provider's, and deliveries once a shutdown
has started (the bridge is deregistered then; a handler already running records one anyway). Watchdog is not modelled.
AwaitSdkShutDown is the wait SwapStart's guard stands for, before the swap starts.
InitEntered and InitReturned, which the registered wrapper records, are SDK steps (an `initialize()` running and
returning), which the check leaves free. The model's SdkInitOk and SdkInitFail are pinned to the InitOutcome records,
and its SdkShutDownOld to the ShutDownOld ones, but only in a run that records some: an older recording leaves them
free. A recording without OrphanDropped resolves an entry at its OrphanResolved claim, as before. ProviderShutDown and
ShutdownReturned(ok) are counted on the teardown they
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
    # The SDK's executor, which runs shutDownOld; the library's own calls run on the ZIO blocking pool.
    sdk_thread = "openfeature-provider-thread-"

    def who(r, key):
        """The model name of the provider instance `r` records under `key`, or "" when it records none."""
        instance = r.get(key)
        if not instance:
            return ""
        # q0 is the Begin line's provider alone: a run whose Begin names none must not make its first swap's q0.
        return names.setdefault(instance, f"q{len(names) + (0 if 'q0' in names.values() else 1)}")

    pending = records[begin].get("arg") == "pending"
    # A recorder that records every initialize() outcome says so on Begin; one that does not leaves the model's free.
    pin_inits = records[begin].get("pins") == "inits"
    initial = records[begin].get("providerInstance")
    if initial:
        names[initial] = "q0"
    # The metadata name of each model provider: a delivered event names its provider by that alone.
    provider_names = {"q0": records[begin].get("provider", "")}
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
    failed = False
    bound_new = False
    swap_prev = None  # the provider bound when the swap started
    bound = "q0"  # the provider the slot binds
    gate_down = False  # a swap's GateDown has been recorded, and no initialize() outcome since
    reconcile = None  # the swap's SwapReconcile label, which closes its status
    swap_status = ""  # the last status written since the swap started
    late_delivery = False  # an event was delivered between the swap's SwapReconcile and SwapDone
    open_delivers = []  # Deliver labels outside a swap that no status write has been attributed to yet
    in_shutdown = False
    shut_down = False
    closed = False
    claims = []  # entries claimed and not yet dropped: [previous, watch, whether previous was shut down since]
    # A recording made before the settle recorded its drop marks an entry resolved at its claim, as before.
    resolved = "OrphanDropped" if any(r["point"] == "OrphanDropped" for r in records) else "OrphanResolved"

    def act(label, **fields):
        entry = {"action": label, "event": "", "state": "", "ok": False, "shuts": -1, "threw": 0, "returned": 0,
                 "owner": owner, "provider": "", "watch": "", "pending": pending,
                 "pinInits": pin_inits, "status": ""}
        entry.update(fields)
        if label != "Deliver":
            open_delivers.clear()
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

    # The pending initial provider's init can end between Begin's check and its line, so its outcome precedes the line.
    if pending and initial:
        for r in records[:begin]:
            if r["point"] == "InitOutcome" and r.get("providerInstance") == initial:
                act("InitOutcome", provider="q0", ok=r.get("arg") == "true")

    def resolve_claims():
        """An entry whose provider was shut down after its claim has settled, though its drop may come after the
        teardown that is starting: the model resolves it before the teardown's step."""
        for claim in [c for c in claims if c[2]]:
            act("OrphanResolved", provider=claim[0], watch=claim[1])
            claims.remove(claim)

    for r in records[begin + 1:]:
        point, arg = r["point"], r.get("arg", "")
        if point == "Watchdog":
            return None
        claiming = resolved == "OrphanDropped" and point == "OrphanResolved"
        if claiming and not (closed or in_shutdown) and who(r, "providerInstance"):
            claims.append([who(r, "providerInstance"), who(r, "watchInstance"), False])
        if point == "ProviderShutDown" and not (closed or in_shutdown):
            for claim in claims:
                claim[2] = claim[2] or claim[0] == who(r, "providerInstance")
        if point == "OrphanDropped":
            claims[:] = [c for c in claims if c[:2] != [who(r, "providerInstance"), who(r, "watchInstance")]]
        if closed:
            count(teardown, point, arg)
            continue
        if point == "ScopeClose":
            resolve_claims()
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
            resolve_claims()
            act("ShutdownStart")
            # The teardown's points come between ShutdownStart and ShutdownDone; the entry is labelled at ShutdownDone.
            teardown = act("ShutdownDone", shuts=0 if recorded else -1)
            calls.clear()
            in_shutdown = shut_down = True
            continue
        if point == "InitOutcome":
            gate_down = False
            act("InitOutcome", provider=who(r, "providerInstance"), ok=arg == "true")
            continue
        if point == "ProviderShutDown" and r.get("caller", "").startswith(sdk_thread):
            act("ShutDownOld", provider=who(r, "providerInstance"))
            continue
        if point == "SwapStart":
            act("SwapStart", provider=who(r, "providerInstance"))
            provider_names[who(r, "providerInstance")] = r.get("provider", "")
            in_swap, swap_outcome, rollback, bound_new, failed = True, None, None, False, False
            swap_prev, swap_new = bound, who(r, "providerInstance")
            gate_down, reconcile, swap_status, late_delivery = False, None, "", False
            continue
        if in_swap:
            succeeded = (re.match(r"(SwapSucceeded|RollbackSucceeded)\((Ready|Stale|Error|Fatal)\b", arg)
                         if point == "ApplySignal" else None)
            if point == "SdkBind":
                act("SdkBind")
                bound_new = True
                bound = swap_new
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
                bound = swap_prev
            elif point == "OrphanRecorded" and swap_outcome == "SwapRollback":
                return None
            elif point == "OrphanRecorded" and swap_outcome is None:
                swap_outcome = "SwapFailNoRollback"
                act("SwapFailNoRollback")
            elif point == "ApplySignal" and arg.startswith("SwapFailed"):
                failed = True
            elif point == "SwapReconcile":
                if failed and swap_outcome is None:
                    swap_outcome = "SwapFailNoRollback"
                    act("SwapFailNoRollback")
                reconcile = act("SwapReconcile")
            # A failed swap settles an earlier swap's orphan before it ends; the model's OrphanResolved takes no lock.
            elif point == resolved:
                act("OrphanResolved", provider=who(r, "providerInstance"), watch=who(r, "watchInstance"))
            elif point == "GateDown":
                gate_down = True
            elif point == "StatusWritten":
                swap_status = arg
            elif point == "DeliverEvent":
                if reconcile is not None:
                    late_delivery = True
                elif gate_down and not shut_down and r.get("provider") and r.get("provider") == provider_names.get(bound):
                    act("Deliver", event=arg.replace("PROVIDER_", ""))
            elif point == "SwapDone":
                in_swap = False
                if reconcile is not None and not late_delivery:
                    reconcile["status"] = swap_status
            continue
        if point == "DeliverEvent" and not shut_down:
            open_delivers.append(act("Deliver", event=arg.replace("PROVIDER_", "")))
        elif point == "StatusWritten":
            # Not Fatal: the model's SDK state FATAL is final for a provider, which the SDK's is not, so a run that
            # delivers more events after a fatal one would have no behaviour.
            if len(open_delivers) == 1 and arg != "Fatal":
                open_delivers[0]["status"] = arg
            open_delivers.clear()
        elif point == resolved:
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
