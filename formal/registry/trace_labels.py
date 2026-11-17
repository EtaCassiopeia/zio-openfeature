"""Turn a recorded run of several instances on one API (the probe points a test passed) into Registry.tla's actions.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>", ...}, possibly out
of order (several threads record). `instance` tags the instance a point belongs to; `providerInstance` names a provider
by its identity (not its wrapper's), and `domain` a slot, absent for the default one. A point a provider's wrapper
records carries the tag of the instance that wrapped it first. Lines without `instance` are the test's own steps on the
API (UserBind, UserApiShutdown, UserRetryInit). Output: a list of model actions {"action": label, ...}, or a string, the
reason the model does not cover the run.

The mapping (formal/registry/trace-map.md):
  BuildBind                        -> Check, then Create, carrying the provider and `own`; then Complete for an async
                                      build outside a registry, which returns once its provider is bound
  RegistryInsert                   -> no action (Check is taken at the binding: the semaphore is held from the lookup
                                      to the insert, and nothing in between touches another domain); a second insert for
                                      one domain is a retry, not covered
  RegistryComplete                 -> Complete, unless a failure of the instance is deciding
  (a sync build's success)         -> Complete, taken before the instance's first step that needs it open: its swap's
                                      binding, its close, the owner's close
  SdkBind in a swap                -> SwapStart, carrying the SwapStart point's provider. A swap without one was refused
                                      before it bound, and changed nothing
  ApplySignal(SwapSucceeded)       -> SwapDone
  FailedRegistration in a swap     -> SwapFailMark, the first; a later one is the rollback's decision, whose FailDecided
                                      gives SwapFail its `shut`
  SwapDone point, after a mark     -> SwapFail, with `shut`, or -1 when the library did not decide (the SDK did)
  FailedRegistration otherwise     -> FailMark; FailDecided -> FailDecide, carrying `shut`
  ShutdownDone, ScopeClose         -> CloseInstance of an instance that does not own the API (once, and not for one
                                      whose failed build was closed by its FailDecide)
  ShutdownStart, ScopeClose of the
    owner                          -> OwnerClose. Its RetireSiblings comes first: the others' teardowns after it are
                                      their retirement, part of OwnerClose, and nothing after it but the API's
                                      shutdowns is a step
  UserBind                         -> UserBind; not covered for a provider the library bound before, as its wrapper:
                                      the SDK then holds two registrations of it, which the model cannot tell apart
  UserApiShutdown, ApiShutdown     -> the end of what is checked: the API's own shutdown, by the test or by the registry
                                      (which owns its API but is no instance, so the model has no action for its close)
  ProviderShutDown                 -> counted per provider into End, up to the end of what is checked
  End                              -> last: `shuts`, how many times each provider's shutdown() ran (-1 for one the user
                                      bound, whose shutdowns no wrapper records)
A step of the model (STEPS, or a swap's success) from an instance that has closed, or after the API closed, is labelled
AfterClose; an owner's close before every other instance still on the API ended its retirement (a ShutdownDone after the
owner's RetireSiblings), UnretiredSibling; a second RegistryInsert for a domain whose client has neither closed nor
failed to bind, SecondInsert. No behaviour takes these labels, so the run is rejected. The shutdowns after the API's own
shutdown are counted apart into End's `late`: one the model ran may land there.
Everything else (the lifecycle points of each instance, Watchdog, InitOutcome, ...) is part of one of these steps.
Instances are named i1, i2, ... by first binding, providers p1, p2, ... by first appearance, domains default, d1, ...
"""
import json
import sys

# The points that are steps of the model: none may follow its instance's close, or the API's.
STEPS = ("BuildBind", "SdkBind", "SwapStart", "FailedRegistration", "FailDecided", "RegistryInsert", "RegistryComplete")


def labels(records):
    records = sorted(records, key=lambda r: r["seq"])
    tags, providers, domains = {}, {}, {}

    def prov(r):
        key = r.get("providerInstance")
        if key is None:
            raise ValueError(f"{r['point']} at {r['seq']} names no provider")
        return providers.setdefault(key, f"p{len(providers) + 1}")

    def dom(r):
        d = r.get("domain")
        if d is None:
            return "default"
        return domains.setdefault(d, f"d{len(domains) + 1}")

    for r in records:
        if r["point"] in ("UserBind", "BuildBind", "SwapStart", "FailedRegistration", "FailDecided"):
            prov(r)
        if r["point"] in ("UserBind", "BuildBind", "RegistryInsert"):
            dom(r)
        if r["point"] == "BuildBind" and r.get("instance") is not None:
            tags.setdefault(r["instance"], f"i{len(tags) + 1}")

    out = []

    def emit(action, **fields):
        out.append(dict(action=action, **fields))

    class Inst:
        def __init__(self):
            self.domain = None
            self.owns = self.registry = self.async_ = False
            self.phase = "absent"  # absent, building, open, closed
            self.failing = False  # a failure marked and not yet decided
            self.swap = None

    inst = {tag: Inst() for tag in tags}
    inserts = {}
    user_slots = {}  # provider -> the slots the user bound it to
    built_on = set()  # the domains an instance was built on
    owner = None
    retiring = False
    # The checked part ends at an API shutdown the model has no action for; after the owner's, which is OwnerClose,
    # only the shutdowns it runs are counted.
    stopped = owner_closed = False
    shut_count, late = {}, {}
    retired = set()  # the instances retired by the owner's RetireSiblings
    library = set()  # the providers the library has bound

    def complete(n):
        s = inst[n]
        if s.phase == "building" and not s.failing:
            emit("Complete", instance=tags[n])
            s.phase = "open"

    def after_close(r):
        """A step of the model after its instance, or the API, has closed: a label no behaviour takes."""
        emit("AfterClose", point=r["point"], seq=r["seq"])

    for r in records:
        point, n = r["point"], r.get("instance")
        if point == "ProviderShutDown":
            p = prov(r)
            counts = late if stopped else shut_count
            counts[p] = counts.get(p, 0) + 1
            continue
        if stopped or owner_closed:
            if point in STEPS or (point == "ApplySignal" and r.get("arg", "").startswith("SwapSucceeded")):
                after_close(r)
            continue
        if point == "SwapTimeout":
            return "a timed-out swap"
        if point == "UserRetryInit":
            return "a retry of a failed registration"
        if point in ("UserApiShutdown", "ApiShutdown"):
            stopped = True
            continue
        if point == "UserBind":
            p, d = prov(r), dom(r)
            if p in library:
                return "the user bound a provider the library had bound as its wrapper"
            user_slots.setdefault(p, set()).add(d)
            emit("UserBind", domain=d, provider=p)
            continue
        if point == "RegistryInsert":
            d = dom(r)
            inserts[d] = inserts.get(d, 0) + 1
            if inserts[d] > 1:
                # A retry evicts the failed build's promise first: its instance closed, or never bound its slot.
                if n not in inst or inst[n].phase in ("absent", "closed"):
                    return "a registry retry after a failed build"
                emit("SecondInsert", domain=d)
                continue
            if n in inst:
                inst[n].registry = True
            continue
        if n not in inst:
            # An instance that never bound its slot: its registration threw, so it took no step of the model.
            continue
        s = inst[n]
        if point == "BuildBind":
            d, p = dom(r), prov(r)
            if d in built_on and not s.registry:
                return "two shared-API layers on one domain"
            slots = user_slots.get(p, set())
            if slots and d not in slots and "default" not in slots and not (slots & built_on):
                return "a provider the user bound under a domain no instance is on"
            built_on.add(d)
            if p not in user_slots:
                library.add(p)
            s.domain, s.owns, s.async_ = d, r.get("owns") == "true", r.get("async") == "true"
            s.phase = "building"
            if s.owns:
                owner = n
            emit("Check", instance=tags[n], domain=d)
            emit("Create", instance=tags[n], provider=p, own=s.owns)
            if s.async_ and not s.registry:
                complete(n)
        elif s.phase == "absent":
            continue
        elif s.phase == "closed":
            if point in STEPS:
                after_close(r)
        elif point == "RegistryComplete":
            complete(n)
        elif point == "SwapStart":
            s.swap = {"provider": prov(r), "bound": False, "marked": False, "rolled": False, "deciding": False,
                      "shut": -1, "done": False}
        elif s.swap is not None and point == "SdkBind":
            p = s.swap["provider"]
            slots = user_slots.get(p, set())
            if slots and s.domain not in slots and "default" not in slots and not (slots & built_on):
                return "a provider the user bound under a domain no instance is on"
            complete(n)
            if p not in user_slots:
                library.add(p)
            emit("SwapStart", instance=tags[n], provider=p)
            s.swap["bound"] = True
        elif s.swap is not None and point == "ApplySignal" and r.get("arg", "").startswith("SwapSucceeded"):
            emit("SwapDone", instance=tags[n])
            s.swap["done"] = True
        elif s.swap is not None and point == "SwapRollback":
            s.swap["rolled"] = True
        elif s.swap is not None and point == "FailedRegistration":
            if not s.swap["marked"] and not s.swap["rolled"]:
                emit("SwapFailMark", instance=tags[n])
                s.swap["marked"] = True
            else:
                s.swap["deciding"] = True
        elif s.swap is not None and point == "FailDecided" and s.swap["deciding"]:
            s.swap["shut"] = 1 if r.get("arg") == "true" else 0
            s.swap["deciding"] = False
        elif s.swap is not None and point == "SwapDone":
            if s.swap["bound"] and not s.swap["done"]:
                if not s.swap["marked"]:
                    return "a swap whose registration was superseded"
                emit("SwapFail", instance=tags[n], shut=s.swap["shut"])
            s.swap = None
        elif point == "FailedRegistration":
            if s.phase == "building" and s.owns:
                return "an owner's failed build"
            emit("FailMark", instance=tags[n])
            s.failing = True
        elif point == "FailDecided":
            if not s.failing:
                raise ValueError(f"FailDecided at {r['seq']} follows no FailedRegistration of its instance")
            emit("FailDecide", instance=tags[n], shut=1 if r.get("arg") == "true" else 0)
            s.failing = False
            if s.phase == "building":
                s.phase = "closed"
        elif point == "RetireSiblings":
            retiring = True
        elif point in ("ShutdownStart", "ShutdownDone", "ScopeClose"):
            if n == owner:
                if point == "ShutdownDone":
                    continue
                # The owner retired every other instance still on the API first, each a whole teardown.
                if any(m != n and inst[m].phase in ("building", "open") and m not in retired for m in inst):
                    emit("UnretiredSibling", instance=tags[n])
                for m in inst:
                    if m != n and inst[m].phase == "building" and not inst[m].async_:
                        complete(m)
                complete(n)
                emit("OwnerClose", instance=tags[n])
                for m in inst.values():
                    if m.phase == "open":
                        m.phase = "closed"
                owner = None
                owner_closed = True
            elif retiring:
                if point == "ShutdownDone":
                    retired.add(n)
            elif point != "ShutdownStart":
                complete(n)
                emit("CloseInstance", instance=tags[n])
                s.phase = "closed"
    user = set(user_slots)
    names = sorted(set(providers.values()), key=lambda p: int(p[1:]))
    emit("End", shuts={p: (-1 if p in user else shut_count.get(p, 0)) for p in names},
         late={p: late.get(p, 0) for p in names})
    instances = sorted(set(tags.values()), key=lambda t: int(t[1:]))
    constants = {"instances": instances, "providers": names,
                 "domains": ["default"] + sorted(set(domains.values()), key=lambda d: int(d[1:])),
                 "swaps": sum(1 for a in out if a["action"] == "SwapStart")}
    if not instances:
        return "no instance bound a slot"
    for a in out:
        a["constants"] = constants
    return out


def read(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


if __name__ == "__main__":
    for a in labels(read(sys.argv[1])):
        print({k: v for k, v in a.items() if k != "constants"} if isinstance(a, dict) else a)
