"""Turn a recorded run of several instances on one API (the probe points a test passed) into Registry.tla's actions.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>", ...}, possibly out
of order (several threads record). `instance` tags the instance a point belongs to; `providerInstance` names a provider
by its identity (not its wrapper's), and `domain` a slot, absent for the default one. A point a provider's wrapper
records carries the tag of the instance that wrapped it first. Lines without `instance` are the test's own steps on the
API (UserBind, UserApiShutdown, UserRetryInit). Output: a list of model actions {"action": label, ...}, or a string, the
reason the model does not cover the run.

A model instance is one build attempt, not one tag: a registry's retry under a domain's tag is another instance.

The mapping (formal/registry/trace-map.md):
  BuildBind                        -> Check, then Create, carrying the provider and `own`; then Complete for an async
                                      build outside a registry, which returns once its provider is bound. Under a
                                      registry tag whose last instance was evicted, a new model instance
  RegistryInsert                   -> no action for the domain's first promise (Check is taken at the binding: the
                                      semaphore is held from the lookup to the insert, and nothing in between touches
                                      another domain), nor for a retry after an attempt that never bound its slot (it
                                      took no step). A retry after one that bound: BuildFail of that instance, unless its
                                      scope close took it already, then Evict; its domain then has no instance
  ScopeClose of a registry build
    still running                  -> BuildFail: its failed wait for readiness released what it had to, then closed its
                                      client's scope
  FailedRegistration, FailDecided
    of a previous attempt's
    provider under its tag         -> ReleaseReplaced of that evicted instance, at FailDecided, carrying `shut`: a retry
                                      with another provider failed too, and the previous failed client released its own
  RegistryComplete                 -> Complete, unless a failure of the instance is deciding
  (a sync build's success)         -> Complete, taken before the instance's first step that needs it open: its swap's
                                      binding, its close, the owner's close
  SdkBind in a swap                -> SwapStart, carrying the SwapStart point's provider. A swap without one was refused
                                      before it bound, and changed nothing
  ApplySignal(SwapSucceeded)       -> SwapDone
  ProviderShutDown of a swap's old
    provider before its outcome,
    while a sibling holds the
    swap's provider                -> SwapOldShut (once per swap; the shutdown is still counted)
  FailedRegistration in a swap     -> SwapFailMark, the first; a later one is the rollback's decision, whose FailDecided
                                      gives SwapFail its `shut`
  SwapDone point, after a mark     -> SwapFail, with `shut`, or -1 when the library did not decide (the SDK did)
  SwapTimeout in a swap            -> SwapTimeout, carrying `recorded`: 1 when the instance's OrphanRecorded follows
                                      (an instance that owns its API records the provider the swap replaced). Not
                                      covered for a swap to a provider the user bound, which no wrapper reports on.
                                      The swap's SwapDone point then ends it, with no outcome
  InitOutcome of a timed-out
    swap's provider                -> InitSettle, carrying the provider and `ok`; one recorded before the swap's
                                      SwapTimeout is taken right after it. The orphan list's OrphanResolved and
                                      OrphanDropped, and the SDK's or the library's shutdown that follows, are part of it;
                                      after the owner's close they are part of OwnerClose (OrphanedProviders.resolveAll)
  FailedRegistration otherwise     -> FailMark; FailDecided -> FailDecide, carrying `shut`
  ShutdownDone, ScopeClose         -> CloseInstance of an instance that does not own the API (once, and not for one
                                      whose failed build was closed by its FailDecide)
  ShutdownStart, ScopeClose of the
    owner                          -> OwnerClose. Its RetireSiblings comes first: the others' teardowns after it are
                                      their retirement, part of OwnerClose, and nothing after it but the API's
                                      shutdowns is a step
  UserBind                         -> UserBind; not covered for a provider the library bound before, as its wrapper:
                                      the SDK then holds two registrations of it, which the model cannot tell apart
  ApiShutdown                      -> RegistryClose, the registry's finalizer, taken at the end of the run: the
                                      retirements (ShutdownDone) and the API's shutdowns that follow are part of it. Every
                                      instance that opened must have been torn down by then, by its own shutdown or its
                                      retirement (UnretiredSibling otherwise)
  UserApiShutdown                  -> the end of what is checked: the test's own api.shutdown(), which retires nothing
  ProviderShutDown                 -> counted per provider into End, up to the end of what is checked; the SDK's
                                      shutdown of a provider a successful retry replaced is Complete's in the model
  End                              -> last: `shuts`, how many times each provider's shutdown() ran (-1 for one the user
                                      bound, whose shutdowns no wrapper records)
A step of the model (STEPS, or a swap's success) from an instance that has closed, or after the API closed, is labelled
AfterClose; an owner's close before every other instance still on the API ended its retirement (a ShutdownDone after the
owner's RetireSiblings), UnretiredSibling; a second RegistryInsert for a domain whose client completed (open or
closed: the registry keeps its promise), SecondInsert. No behaviour takes these labels, so the run is rejected. The shutdowns after the API's own
shutdown are counted apart into End's `late`: one the model ran may land there.
Everything else (the lifecycle points of each instance, Watchdog, any other InitOutcome, ...) is part of one of these
steps.
Instances are named i1, i2, ... by binding, providers p1, p2, ... by first appearance, domains default, d1, ...
"""
import json
import sys

# The points that are steps of the model: none may follow its instance's close, or the API's.
STEPS = ("BuildBind", "SdkBind", "SwapStart", "SwapTimeout", "FailedRegistration", "FailDecided", "RegistryInsert",
         "RegistryComplete")


def labels(records):
    records = sorted(records, key=lambda r: r["seq"])
    providers, domains = {}, {}

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

    out = []

    def emit(action, **fields):
        out.append(dict(action=action, **fields))

    class Inst:
        def __init__(self, name, tag, registry):
            self.name, self.tag, self.registry = name, tag, registry
            self.domain = None
            self.owns = self.async_ = False
            self.phase = "absent"  # absent, building, open, closed, failed (a registry build), evicted
            self.failing = False  # a failure marked and not yet decided
            self.failure_closed = False  # its build was closed by a failure's decision
            self.swap = None
            self.provider = None  # the provider its slot holds

    inst = {}  # model instance name -> Inst: one per build attempt
    cur = {}  # tag -> the name of its current model instance (a registry's retry is another)
    evicted = {}  # tag -> the names of its evicted model instances
    releasing = {}  # tag -> the evicted instance whose release of its provider is marked and not yet decided
    registry_tags = set()  # the tags a registry inserted a promise for
    user_slots = {}  # provider -> the slots the user bound it to
    built_on = set()  # the domains an instance was built on
    owner = None
    retiring = False
    pending = set()  # the providers whose initialize() a timed-out swap left running
    waits_on = {}  # each of those: the provider its swap replaced, which waits on that initialize()
    # The checked part ends at an API shutdown the model has no action for; after the owner's (OwnerClose) or the
    # registry's (RegistryClose), only the shutdowns it runs are counted.
    stopped = owner_closed = registry_closed = False
    opened, torn_down = set(), set()  # instances that opened; tags whose teardown ran (a ShutdownDone)
    shut_count, late = {}, {}
    retired = set()  # the instances retired by the owner's RetireSiblings
    library = set()  # the providers the library has bound

    def complete(s):
        if s.phase == "building" and not s.failing:
            emit("Complete", instance=s.name)
            s.phase = "open"
            opened.add(s.name)

    def fail(s):
        """A registry build that failed: its failed wait for readiness released what it had to, then closed its scope."""
        emit("BuildFail", instance=s.name)
        s.phase = "failed"

    def after_close(r):
        """A step of the model after its instance, or the API, has closed: a label no behaviour takes."""
        emit("AfterClose", point=r["point"], seq=r["seq"])

    def released(r, n):
        """The domain's previous failed instance whose provider a failure point under tag `n` names, or None."""
        p = prov(r)
        if n in cur and inst[cur[n]].provider == p:
            return None
        return next((e for e in reversed(evicted.get(n, [])) if inst[e].provider == p), None)

    for r in records:
        point, n = r["point"], r.get("instance")
        if point == "ProviderShutDown":
            p = prov(r)
            counts = late if stopped else shut_count
            counts[p] = counts.get(p, 0) + 1
            # The SDK shut a swap's old provider down before the swap's outcome: a sibling's binding of the swap's
            # provider shares its registration and started its initialize() first (the model's SwapOldShut).
            # One shutdown is one SwapOldShut, however many swaps in flight moved off p: two instances swapping off a
            # provider they share both see it shut down, and the model's SwapDone of the other then finds it no longer
            # registered.
            if not stopped and not owner_closed and not registry_closed:
                swaps_off = [(m, t.swap) for m, t in inst.items()
                             if t.swap is not None and t.swap["bound"] and not t.swap["done"]
                             and t.swap.get("prior") == p
                             and any(o is not t and o.provider == t.swap["provider"] for o in inst.values())]
                if swaps_off:
                    emit("SwapOldShut", instance=swaps_off[0][0])
                    for _, w in swaps_off:
                        w["prior"] = None
            continue
        if point == "ShutdownDone" and n is not None:
            torn_down.add(n)
        if stopped or owner_closed or registry_closed:
            if point in STEPS or (point == "ApplySignal" and r.get("arg", "").startswith("SwapSucceeded")):
                after_close(r)
            continue
        if point == "InitOutcome":
            p = providers.get(r.get("providerInstance"))
            if p in pending:
                # A timed-out swap's provider settled: the SDK's shutDownOld, or the orphan list's shutdown, follows.
                emit("InitSettle", provider=p, ok=r.get("arg") == "true")
                pending.discard(p)
                continue
            # The outcome of a swap's provider that the swap's timeout has not yet seen: settled once it does.
            for t in inst.values():
                w = t.swap
                if w is not None and w["bound"] and not w["done"] and w["provider"] == p:
                    w["outcome"] = r.get("arg") == "true"
        if point == "UserRetryInit":
            return "a retry of a failed registration"
        if point == "UserApiShutdown":
            stopped = True
            continue
        if point == "ApiShutdown":
            if any(m.failing for m in inst.values()) or releasing:
                return "a failure's decision interrupted by the registry's close"
            registry_closed = True
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
            registry_tags.add(n)
            m = cur.get(n)
            if m is None:
                # The first promise for the domain, or a retry after an attempt that never bound its slot (its
                # registration threw before it took a step of the model) or whose promise was evicted.
                continue
            s = inst[m]
            if s.phase in ("open", "closed"):
                if s.failure_closed:
                    return "a registry retry after a failure under its build"
                # A second promise while the domain's client is still in the map.
                emit("SecondInsert", domain=d)
                continue
            # A retry: the previous build failed, and its promise was evicted.
            if s.phase == "building":
                fail(s)
            emit("Evict", instance=m)
            s.phase = "evicted"
            evicted.setdefault(n, []).append(m)
            del cur[n]
            continue
        if point in ("FailedRegistration", "FailDecided") and n is not None:
            # A retry with another provider failed too: the previous failed client releases the provider it replaced.
            e = released(r, n)
            if e is not None:
                if point == "FailedRegistration":
                    releasing[n] = e
                else:
                    if releasing.pop(n, None) != e:
                        raise ValueError(f"FailDecided at {r['seq']} follows no FailedRegistration of its instance")
                    emit("ReleaseReplaced", instance=e, shut=1 if r.get("arg") == "true" else 0)
                continue
        if point == "BuildBind" and n is not None and n not in cur:
            name = f"i{len(inst) + 1}"
            inst[name] = Inst(name, n, n in registry_tags)
            cur[n] = name
        if n not in cur:
            # An instance that never bound its slot: its registration threw, so it took no step of the model.
            continue
        s = inst[cur[n]]
        m = s.name
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
            s.provider = p
            s.phase = "building"
            if s.owns:
                owner = m
            emit("Check", instance=m, domain=d)
            emit("Create", instance=m, provider=p, own=s.owns)
            if s.async_ and not s.registry:
                complete(s)
        elif s.phase == "absent":
            continue
        elif s.phase in ("closed", "failed"):
            if point in STEPS:
                after_close(r)
        elif point == "RegistryComplete":
            complete(s)
        elif point == "SwapStart":
            s.swap = {"provider": prov(r), "bound": False, "marked": False, "rolled": False, "deciding": False,
                      "shut": -1, "done": False}
        elif s.swap is not None and point == "SdkBind":
            p = s.swap["provider"]
            slots = user_slots.get(p, set())
            if slots and s.domain not in slots and "default" not in slots and not (slots & built_on):
                return "a provider the user bound under a domain no instance is on"
            complete(s)
            if p not in user_slots:
                library.add(p)
            emit("SwapStart", instance=m, provider=p)
            s.swap["bound"] = True
            s.swap["prior"] = s.swap["was"] = s.provider
            s.provider = p
        elif s.swap is not None and point == "SwapTimeout" and s.swap["bound"] and not s.swap["done"]:
            p = s.swap["provider"]
            if p in user_slots:
                return "a timed-out swap to a provider the user registered"
            if any(q in pending and w == s.swap["was"] for q, w in waits_on.items()):
                return "a timed-out swap off a provider another timed-out swap left waiting"
            emit("SwapTimeout", instance=m, recorded=0)
            s.swap["timeout"] = out[-1]
            s.swap["done"] = True
            if "outcome" in s.swap:
                emit("InitSettle", provider=p, ok=s.swap["outcome"])
            else:
                pending.add(p)
                waits_on[p] = s.swap["was"]
        elif s.swap is not None and point == "OrphanRecorded" and "timeout" in s.swap:
            s.swap["timeout"]["recorded"] = 1
        elif s.swap is not None and point == "ApplySignal" and r.get("arg", "").startswith("SwapSucceeded"):
            emit("SwapDone", instance=m)
            s.swap["done"] = True
        elif s.swap is not None and point == "SwapRollback":
            s.swap["rolled"] = True
        elif s.swap is not None and point == "FailedRegistration":
            if not s.swap["marked"] and not s.swap["rolled"]:
                emit("SwapFailMark", instance=m)
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
                emit("SwapFail", instance=m, shut=s.swap["shut"])
                s.provider = s.swap["was"]
            s.swap = None
        elif point == "FailedRegistration":
            if s.phase == "building" and s.owns:
                return "an owner's failed build"
            emit("FailMark", instance=m)
            s.failing = True
        elif point == "FailDecided":
            if not s.failing:
                raise ValueError(f"FailDecided at {r['seq']} follows no FailedRegistration of its instance")
            emit("FailDecide", instance=m, shut=1 if r.get("arg") == "true" else 0)
            s.failing = False
            if s.phase == "building":
                s.phase = "closed"
                s.failure_closed = True
        elif point == "RetireSiblings":
            retiring = True
        elif point == "ScopeClose" and s.registry and s.phase == "building" and not s.failing:
            # A registry build whose wait for readiness failed closes its client's own scope: the build failed.
            fail(s)
        elif point in ("ShutdownStart", "ShutdownDone", "ScopeClose"):
            if m == owner:
                if point == "ShutdownDone":
                    continue
                # The owner retired every other instance still on the API first, each a whole teardown.
                if any(o != m and inst[o].phase in ("building", "open") and o not in retired for o in inst):
                    emit("UnretiredSibling", instance=m)
                for o in inst.values():
                    if o.name != m and o.phase == "building" and not o.async_:
                        complete(o)
                complete(s)
                emit("OwnerClose", instance=m)
                for o in inst.values():
                    if o.phase == "open":
                        o.phase = "closed"
                owner = None
                owner_closed = True
            elif retiring:
                if point == "ShutdownDone":
                    retired.add(m)
            elif point != "ShutdownStart":
                complete(s)
                emit("CloseInstance", instance=m)
                s.phase = "closed"
    if registry_closed:
        # The registry retired every instance on its API (ApiInstances.retireAll) before shutting it down. One whose
        # build failed or was interrupted may have been collected already, and is not required.
        for m in sorted(opened, key=lambda m: int(m[1:])):
            if inst[m].tag not in torn_down:
                emit("UnretiredSibling", instance=m)
        emit("RegistryClose")
    user = set(user_slots)
    names = sorted(set(providers.values()), key=lambda p: int(p[1:]))
    emit("End", shuts={p: (-1 if p in user else shut_count.get(p, 0)) for p in names},
         late={p: late.get(p, 0) for p in names})
    instances = sorted(inst, key=lambda t: int(t[1:]))
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
