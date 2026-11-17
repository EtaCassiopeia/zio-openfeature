"""Turn a recorded fromAcquireAsync run (the `Acquire` probe points a test passed) into the actions of Acquire.tla.

Input: ndjson lines {"seq": n, "point": "<LifecycleProbe point name>", "arg": "<payload or empty>", ...}, possibly out
of order (several threads record). `attempt` names an attempt (its scope's identity) and `providerInstance` a provider
(the identity of the provider itself, not of the library's wrapper). The run starts with a Begin line, carrying
`owning` and `userShuts` (the model's constants), and has one End line, written once the layer was released, with what
the test observed: `shuts` ("<instance>:<count>,..."), `stack`, `closed` and `shutAfterClose` ("" when not observed).
Output: a list of model actions {"action": label, ...}, or a string, the reason the model does not cover the run. A run
without its Begin or End line raises ValueError: the recording is broken.

The mapping (formal/acquire/trace-map.md):
  AcquireAttemptStart                 -> no action; numbers the attempts 1, 2, ... in the order they start
  AcquireAccepted                     -> AttemptOk
  AcquireRejected after the attempt's
    AcquireVerifyFailed               -> VerifyFails; the run is not covered when another attempt follows it
  AcquireRejected otherwise           -> LateArrive: verify was interrupted when the attempt was abandoned, and its
                                         fiber released the provider. The attempt's AcquireAbandoned may come before it
                                         or after it; after, its Timeout (or FixRelease1) is taken first. The run is
                                         not covered when there is none (a verify that interrupted itself)
  AcquireAbandoned, not interrupted   -> Timeout
  AcquireAbandoned, interrupted       -> FixRelease1: the release's interrupt abandoned the attempt in progress
  AcquireFailed before the attempt's
    AcquireAbandoned                  -> AcquireFails, or, when that AcquireAbandoned is the release's, FixRelease1 and
                                         then LateFail (the interrupt reached the constructor, which closed its scope,
                                         before the attempt's handler recorded the abandonment)
  AcquireFailed after it              -> LateFail
  AcquireLateArrival                  -> LateArrive: the constructor, or verify, returned for an abandoned attempt, or
                                         the attempt was abandoned after verify passed; an AcquireAbandoned recorded
                                         after it is taken first
  AcquireComposed                     -> Compose
  AcquireStackInit(true)              -> InitOk
  AcquireSwitched(true)               -> SwitchOk
  AcquireDiscarded                    -> SwitchRefused after an AcquireSwitched(false), else InitFails; the run is not
                                         covered when nothing was composed (a compose that threw)
  AcquireReleaseInterrupted           -> FixRelease1, unless an interrupted AcquireAbandoned already took it
  ScopeClose                          -> FixRelease2
  AcquireReleaseOwned                 -> FixRelease3, carrying `owned`: "none", "real" or "stack", by the instance held
  End                                 -> End, last whatever its sequence number: a step recorded after the test read its
                                         counts happened before it read them, since every point follows its effect
Every other point (the instance's lifecycle points, AcquireStackInit(false), AcquireSwitched(false),
AcquireVerifyFailed) is part of one of those steps or no step at all.
"""
import json
import sys


def labels(records):
    records = sorted(records, key=lambda r: r["seq"])
    begins = [k for k, r in enumerate(records) if r["point"] == "Begin"]
    if len(begins) != 1:
        raise ValueError(f"the run has {len(begins)} Begin markers")
    ends = [r for r in records if r["point"] == "End"]
    if len(ends) != 1:
        raise ValueError(f"the run has {len(ends)} End markers")
    begin = records[begins[0]]
    run = records[begins[0] + 1:]
    index = {}
    for r in run:
        if r["point"] == "AcquireAttemptStart":
            index.setdefault(r["attempt"], len(index) + 1)
    attempts = max(1, len(index))
    common = {"owning": begin.get("owning") == "true", "userShuts": begin.get("userShuts") == "true",
              "attempts": attempts}
    out = []

    def emit(action, attempt=0, owned=""):
        out.append(dict(common, action=action, attempt=attempt, owned=owned))

    def attempt_of(r):
        if r["attempt"] not in index:
            raise ValueError(f"{r['point']} names an attempt that never started")
        return index[r["attempt"]]

    failed, abandoned, verify_failed, settled = set(), set(), set(), set()
    real = stack = None
    late = {}
    composed = refused = False
    release_taken = False
    def abandon_early(k, r, pos):
        """The attempt's abandonment, when the step at `pos` follows it in the code but was recorded first: the
        abandonment's own point comes once the attempt's handler has marked it abandoned, and its fiber may settle the
        attempt in between. True when the run records one."""
        nonlocal release_taken
        later = next((s for s in run[pos + 1:]
                      if s["point"] == "AcquireAbandoned" and s["attempt"] == r["attempt"]), None)
        if later is None:
            return False
        abandoned.add(k)
        settled.add(k)
        if later["arg"] == "true":
            emit("FixRelease1")
            release_taken = True
        else:
            emit("Timeout", k)
        return True

    for pos, r in enumerate(run):
        point = r["point"]
        if point == "AcquireVerifyFailed":
            verify_failed.add(attempt_of(r))
        elif point == "AcquireAccepted":
            emit("AttemptOk", attempt_of(r))
            real = r["providerInstance"]
        elif point == "AcquireRejected":
            k = attempt_of(r)
            if k in verify_failed:
                settled.add(k)
                if any(s["point"] == "AcquireAttemptStart" for s in run[pos + 1:]):
                    return "an attempt after a rejected one"
                emit("VerifyFails", k)
                real = r["providerInstance"]
                continue
            # verify was interrupted, which only the attempt's abandonment does: before or after this point.
            if k not in abandoned and not abandon_early(k, r, pos):
                return "a verify interrupted with its attempt in progress"
            emit("LateArrive", k)
            late[k] = r["providerInstance"]
        elif point == "AcquireFailed":
            k = attempt_of(r)
            if k in abandoned:
                emit("LateFail", k)
            else:
                later = next((s for s in run[pos + 1:]
                              if s["point"] == "AcquireAbandoned" and s["attempt"] == r["attempt"]), None)
                if later is not None and later["arg"] == "true":
                    emit("FixRelease1")
                    emit("LateFail", k)
                    release_taken = True
                    abandoned.add(k)
                else:
                    emit("AcquireFails", k)
            failed.add(k)
        elif point == "AcquireAbandoned":
            k = attempt_of(r)
            if k in failed or k in settled:
                continue
            abandoned.add(k)
            if r["arg"] == "true":
                emit("FixRelease1")
                release_taken = True
            else:
                emit("Timeout", k)
        elif point == "AcquireLateArrival":
            k = attempt_of(r)
            if k not in abandoned:
                abandon_early(k, r, pos)
            emit("LateArrive", k)
            late[k] = r["providerInstance"]
        elif point == "AcquireComposed":
            emit("Compose")
            composed, stack = True, r["providerInstance"]
        elif point == "AcquireStackInit" and r["arg"] == "true":
            emit("InitOk")
        elif point == "AcquireSwitched":
            if r["arg"] == "true":
                emit("SwitchOk")
            else:
                refused = True
        elif point == "AcquireDiscarded":
            if not composed:
                return "a compose that threw"
            emit("SwitchRefused" if refused else "InitFails")
        elif point == "AcquireReleaseInterrupted":
            if release_taken:
                release_taken = False
            else:
                emit("FixRelease1")
        elif point == "ScopeClose":
            emit("FixRelease2")
        elif point == "AcquireReleaseOwned":
            held = r.get("providerInstance", "")
            owned = ("none" if not held else "stack" if composed and held == stack
                     else "real" if held == real else "unknown")
            emit("FixRelease3", owned=owned)
    end = ends[0]
    shuts = {}
    for entry in filter(None, end.get("shuts", "").split(",")):
        instance, n = entry.rsplit(":", 1)
        shuts[instance] = int(n)

    def flag(key):
        return {"true": 1, "false": 0}.get(end.get(key, ""), -1)

    # The resource is the one attempt's: a run with several has one per attempt, and the test counts them together.
    single = len(index) == 1
    out.append(dict(common, action="End", attempt=0, owned="",
                    shuts=shuts.get(real, -1) if real else -1,
                    abShuts=[shuts.get(late[k], -1) if k in late else -1 for k in range(1, attempts + 1)],
                    stack=int(end["stack"]) if end.get("stack") else -1,
                    resource=1,
                    closed=flag("closed") if single else -1,
                    shutAfterClose=flag("shutAfterClose") if single else -1))
    return out


def read(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


if __name__ == "__main__":
    print(labels(read(sys.argv[1])))
