----------------------------- MODULE Lifecycle -----------------------------
(***************************************************************************)
(* The provider-lifecycle protocol of zio-openfeature's FeatureFlagsLive,  *)
(* with the OpenFeature Java SDK (1.23.0) as its environment.             *)
(*                                                                         *)
(* The library: setProvider's swap (gate, outcome, rollback), the event   *)
(* bridge (status from the SDK's state for the slot), the providers a     *)
(* timed-out swap leaves behind (OrphanedProviders), shutdown and the     *)
(* scope close.  The SDK: it binds a new provider to the slot before      *)
(* initialize(), shuts the previous one down only after a successful      *)
(* initialize(), sets a provider's state when it emits an event, and      *)
(* delivers events to the bridge later, in any order, and only for the    *)
(* provider bound to the slot.                                            *)
(*                                                                         *)
(* Action names follow the lifecycle probe points (internal/LifecycleProbe);*)
(* README.md maps each action to the code.  Time is not modelled: a       *)
(* timeout is an action that is enabled while an initialize() runs.       *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
    P0,          \* the provider the instance is built with
    Providers,   \* providers setProvider can install, each at most once
    MaxEvents,   \* how many events providers may emit in one behaviour
    None

All == {P0} \cup Providers

Statuses == {"NotReady", "Ready", "Stale", "Error", "Fatal", "ShuttingDown"}
SdkStates == {"NONE", "NOT_READY", "READY", "STALE", "ERROR", "FATAL"}

VARIABLES
    \* The library
    status,      \* the instance's provider status
    installed,   \* the provider the instance reports (providerRef)
    lock,        \* who holds the swap lock: "free", "swap" or "shutdown"
    gate,        \* swapInProgress: bridge events do not move the status
    swap,        \* the swap in flight, or None
    pending,     \* the provider the last timed-out swap installed, or None
    orphans,     \* providers a swap left behind, each watched against a registration of the provider that replaced it
    bridgeOn,    \* the bridge's handlers are registered
    shutDown,    \* shutdownCompleted: the final NotReady
    closed,      \* the scope has closed
    \* The SDK
    bound,       \* the provider bound to the instance's slot
    sdk,         \* the SDK state of each provider's current registration ("NONE": never registered)
    inits,       \* how many initialize() calls are running, per provider
ran,         \* whether the provider's current registration's initialize() returned (the SDK dispatched its READY)
    prevOf,      \* the provider the slot held when this one was bound (for shutDownOld)
    events,      \* emitted events not yet delivered to the bridge
    emitted,     \* events emitted so far (bounded by MaxEvents)
    shuts,       \* how many times each provider's shutdown() was called
    twoInits     \* a provider's initialize() was entered while an earlier call ran

vars == << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
           bound, sdk, inits, ran, prevOf, events, emitted, shuts, twoInits >>
sdkVars == << bound, sdk, inits, ran, prevOf, events, emitted, shuts, twoInits >>

Used == {p \in All : sdk[p] # "NONE"}

TypeOK ==
    /\ status \in Statuses
    /\ installed \in All
    /\ lock \in {"free", "swap", "shutdown"}
    /\ gate \in BOOLEAN /\ bridgeOn \in BOOLEAN /\ shutDown \in BOOLEAN /\ closed \in BOOLEAN
    /\ pending \in All \cup {None}
    /\ bound \in All
    /\ sdk \in [All -> SdkStates]
    /\ inits \in [All -> Nat]
    /\ ran \in [All -> BOOLEAN]
    /\ shuts \in [All -> Nat]
    /\ twoInits \in BOOLEAN

-----------------------------------------------------------------------------
(* The status machine (ProviderStatusMachine) for the signals the bridge  *)
(* applies.  Events never move Fatal or ShuttingDown.                     *)

Apply(s, signal) ==
    CASE signal = "READY" ->
            IF s \in {"NotReady", "Stale", "Error"} THEN "Ready" ELSE s
      [] signal = "STALE" ->
            IF s \in {"NotReady", "Ready", "Error"} THEN "Stale" ELSE s
      [] signal = "ERROR" ->
            IF s \in {"NotReady", "Ready", "Stale"} THEN "Error" ELSE s
      [] signal = "FATAL" ->
            IF s \in {"Fatal", "ShuttingDown"} THEN s ELSE "Fatal"
      [] OTHER -> s

(* The status the SDK's state for the installed provider corresponds to.  *)
(* NOT_READY: a timed-out swap's provider still initializing, Error.      *)
Matches(s, state) ==
    CASE state = "READY"     -> s = "Ready"
      [] state = "STALE"     -> s = "Stale"
      [] state = "ERROR"     -> s = "Error"
      [] state = "FATAL"     -> s = "Fatal"
      [] state = "NOT_READY" -> s = "Error"
      [] OTHER               -> FALSE

(* The status a swap or rollback ends in for a provider that settled in   *)
(* `state` (ProviderStatusMachine.statusOf).                              *)
StatusOf(state) ==
    CASE state = "STALE" -> "Stale"
      [] state = "ERROR" -> "Error"
      [] state = "FATAL" -> "Fatal"
      [] OTHER           -> "Ready"

-----------------------------------------------------------------------------
\* The state after the build, with `events0` the build's events not yet delivered.
InitWith(events0) ==
    /\ status = "Ready"
    /\ installed = P0
    /\ lock = "free"
    /\ gate = FALSE
    /\ swap = None
    /\ pending = None
    /\ orphans = {}
    /\ bridgeOn = TRUE
    /\ shutDown = FALSE
    /\ closed = FALSE
    /\ bound = P0
    /\ sdk = [p \in All |-> IF p = P0 THEN "READY" ELSE "NONE"]
    /\ inits = [p \in All |-> 0]
    /\ ran = [p \in All |-> p = P0]
    /\ prevOf = [p \in All |-> None]
    /\ events = events0
    /\ emitted = 0
    /\ shuts = [p \in All |-> 0]
    /\ twoInits = FALSE

Init == InitWith({})

\* The state of the registration an orphan entry watches: the provider's current one, or, once that provider has been
\* registered again, the state its watched registration was left in (a new registration does not change it).
View(o) == IF o.frozen = "LIVE" THEN sdk[o.watch] ELSE o.frozen

\* Whether the watched registration's initialize() returned, which the watcher learns from the SDK's READY.
RanView(o) == IF o.frozen = "LIVE" THEN ran[o.watch] ELSE o.ran

\* The watched provider initialized, or reports a serving state: the SDK has shut the previous provider down.
Released(o) == View(o) \in {"READY", "STALE"} \/ RanView(o)

Watch(prev, watch) == [prev |-> prev, watch |-> watch, frozen |-> "LIVE", ran |-> FALSE]

\* A provider is registered again: entries watching its old registration keep that registration's last state.
Freeze(os, p) ==
    {IF o.watch = p /\ o.frozen = "LIVE" THEN [o EXCEPT !.frozen = sdk[p], !.ran = ran[p]] ELSE o : o \in os}

\* A provider that reported STALE while starting finishes starting before anything else happens (see EmitStaleWhileStarting).
Settling == \E p \in All : inits[p] > 0 /\ sdk[p] = "STALE"

\* The SDK's shutDownOld: once a registration's initialize() returns normally, shut down the provider the slot held
\* before it, unless the slot still binds it.
ShutDownOld(p, b, sh) ==
    IF prevOf[p] # None /\ prevOf[p] # b THEN [sh EXCEPT ![prevOf[p]] = @ + 1] ELSE sh

-----------------------------------------------------------------------------
(* setProvider *)

\* SwapStart: the swap holds the lock and raises the gate; the status becomes NotReady.
SwapStart(p) ==
    /\ lock = "free" /\ ~shutDown /\ ~closed /\ ~Settling
    /\ sdk[p] = "NONE"
    /\ lock' = "swap"
    /\ gate' = TRUE
    /\ status' = "NotReady"
    /\ swap' = [new |-> p, prev |-> installed, phase |-> "bind"]
    /\ installed' = p
    /\ UNCHANGED << pending, orphans, bridgeOn, shutDown, closed, sdkVars >>

\* SdkBind: the SDK binds the new provider to the slot and starts its initialize().
SdkBind ==
    /\ swap # None /\ swap.phase = "bind"
    /\ bound' = swap.new
    /\ sdk' = [sdk EXCEPT ![swap.new] = "NOT_READY"]
    /\ inits' = [inits EXCEPT ![swap.new] = @ + 1]
    /\ ran' = [ran EXCEPT ![swap.new] = FALSE]
    /\ prevOf' = [prevOf EXCEPT ![swap.new] = bound]
    /\ swap' = [swap EXCEPT !.phase = "wait"]
    /\ UNCHANGED << status, installed, lock, gate, pending, orphans, bridgeOn, shutDown, closed,
                    events, emitted, shuts, twoInits >>

\* SdkInitOk: a running initialize() returns.  The SDK reports READY, dispatches its PROVIDER_READY and shuts the
\* previous provider down.
SdkInitOk(p) ==
    /\ inits[p] > 0
    /\ inits' = [inits EXCEPT ![p] = @ - 1]
    /\ ran' = [ran EXCEPT ![p] = TRUE]
    /\ sdk' = [sdk EXCEPT ![p] = "READY"]
    /\ events' = events \cup {[p |-> p, e |-> "READY"]}
    /\ shuts' = ShutDownOld(p, bound, shuts)
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, prevOf, emitted, twoInits >>

\* SdkInitFail: a running initialize() throws.  The SDK reports ERROR; nothing is shut down.  (A provider that reported
\* STALE while starting and then fails is the documented "taken at its word" limit, outside this model.)
SdkInitFail(p) ==
    /\ inits[p] > 0
    /\ sdk[p] = "NOT_READY"
    /\ inits' = [inits EXCEPT ![p] = @ - 1]
    /\ sdk' = [sdk EXCEPT ![p] = "ERROR"]
    /\ events' = events \cup {[p |-> p, e |-> "ERROR"]}
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, ran, prevOf, emitted, shuts, twoInits >>

\* SwapDone: the new provider initialized (or reports STALE from inside initialize(), taken at its word).  The swap
\* ends in the state the SDK reports for it, an ERROR or FATAL it reported since included, drops the gate, and reads
\* the slot once more (a state the SDK reached while the gate was up is not lost).
SwapDone ==
    /\ swap # None /\ swap.phase = "wait"
    /\ bound = swap.new
    /\ sdk[swap.new] \in {"READY", "STALE"} \/ (ran[swap.new] /\ sdk[swap.new] \in {"ERROR", "FATAL"})
    /\ status' = StatusOf(sdk[swap.new])
    /\ gate' = FALSE /\ lock' = "free" /\ swap' = None /\ pending' = None
    /\ UNCHANGED << installed, orphans, bridgeOn, shutDown, closed, sdkVars >>

\* SwapTimeout: the wait reached initTimeout while the new provider's initialize() runs.  No rollback: the new provider
\* stays installed with status Error, and the provider it replaced is watched against it.
SwapTimeout ==
    /\ swap # None /\ swap.phase = "wait"
    /\ sdk[swap.new] = "NOT_READY"
    /\ status' = "Error"
    /\ pending' = swap.new
    /\ orphans' = IF swap.prev # swap.new THEN orphans \cup {Watch(swap.prev, swap.new)} ELSE orphans
    /\ gate' = FALSE /\ lock' = "free" /\ swap' = None
    /\ UNCHANGED << installed, bridgeOn, shutDown, closed, sdkVars >>

\* The wait saw the new provider fail.  When the previous provider is the one a timed-out swap installed and its
\* initialize() still runs, there is nothing to roll back to: the failed provider stays, status Error, and the
\* previous provider is watched against it.
SwapFailNoRollback ==
    /\ swap # None /\ swap.phase = "wait"
    /\ sdk[swap.new] \in {"ERROR", "FATAL"} /\ ~ran[swap.new]
    /\ pending = swap.prev /\ sdk[swap.prev] = "NOT_READY"
    /\ status' = "Error"
    /\ orphans' = orphans \cup {Watch(swap.prev, swap.new)}
    /\ pending' = None
    /\ gate' = FALSE /\ lock' = "free" /\ swap' = None
    /\ UNCHANGED << installed, bridgeOn, shutDown, closed, sdkVars >>

\* SwapRollback: otherwise the previous provider is registered again, which initializes it again.  The rollback's own
\* wait is folded into this step: `ok` when that initialize() returns, and the provider then settles in `r`, which may
\* be an ERROR or FATAL it reports before the wait reads it; else initialize() throws, `r` ERROR or FATAL.  On success
\* the SDK shuts the failed provider down; on failure the library does.  The SDK's events for that initialization may
\* reach the bridge while the gate is still up (`early`), which drops them; so the swap reads the SDK's state for the
\* previous provider once its gate is down.
SwapRollback(ok, r, early) ==
    /\ swap # None /\ swap.phase = "wait"
    /\ sdk[swap.new] \in {"ERROR", "FATAL"} /\ ~ran[swap.new]
    /\ ~(pending = swap.prev /\ sdk[swap.prev] = "NOT_READY")
    /\ ok \/ r \in {"ERROR", "FATAL"}
    /\ LET prev == swap.prev
           failed == swap.new
           sent == IF ok THEN {[p |-> prev, e |-> "READY"]} \cup (IF r = "READY" THEN {} ELSE {[p |-> prev, e |-> r]})
                   ELSE {[p |-> prev, e |-> r]} IN
       /\ twoInits' = (twoInits \/ inits[prev] > 0)
       /\ installed' = prev
       /\ bound' = prev
       /\ prevOf' = [prevOf EXCEPT ![prev] = failed]
       /\ ran' = [ran EXCEPT ![prev] = ok]
       /\ sdk' = [sdk EXCEPT ![prev] = r]
       /\ status' = StatusOf(r)
       /\ events' = IF early THEN events ELSE events \cup sent
       /\ shuts' = [shuts EXCEPT ![failed] = @ + 1]
    /\ orphans' = Freeze(orphans, swap.prev)
    /\ gate' = FALSE /\ lock' = "free" /\ swap' = None /\ pending' = None
    /\ UNCHANGED << bridgeOn, shutDown, closed, inits, emitted >>

-----------------------------------------------------------------------------
(* Provider events *)

\* A provider that has initialized and is not shut down reports a state, at any time: also right after initialize()
\* returned, before the swap waiting on it or the watcher of the provider it replaced has seen the outcome.  The SDK
\* sets the state of its registration at once; the bridge sees the event later.  A provider reporting a state from
\* inside initialize() is outside this model, apart from EmitStaleWhileStarting: the library takes such a report at
\* its word.
Emit(p, e) ==
    /\ emitted < MaxEvents
    /\ ran[p] /\ inits[p] = 0 /\ shuts[p] = 0
    /\ sdk[p] \in {"READY", "STALE", "ERROR"}
    /\ sdk' = [sdk EXCEPT ![p] = e]
    /\ events' = events \cup {[p |-> p, e |-> e]}
    /\ emitted' = emitted + 1
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, inits, ran, prevOf, shuts, twoInits >>

\* A provider reports STALE from inside initialize(), and then starts.  The SDK's state is STALE until initialize()
\* returns; a swap waiting on the provider sees it as initialized.  The model holds such a provider to finishing its
\* start before anything else happens (no failure, no other swap, no teardown): the SDK does not expose whether
\* initialize() has returned, so a provider that reports a state before it returns is taken at its word.  If it then
\* fails or is interrupted, the provider it replaced may never be shut down, and a later rollback onto it would call
\* initialize() a second time: documented limits, outside this model.  Not while
\* a watcher waits on the provider, which would take the STALE at its word too.
EmitStaleWhileStarting(p) ==
    /\ emitted < MaxEvents
    /\ inits[p] > 0 /\ sdk[p] = "NOT_READY"
    /\ ~\E o \in orphans : o.watch = p /\ o.frozen = "LIVE"
    /\ sdk' = [sdk EXCEPT ![p] = "STALE"]
    /\ events' = events \cup {[p |-> p, e |-> "STALE"]}
    /\ emitted' = emitted + 1
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, inits, ran, prevOf, shuts, twoInits >>

\* DeliverEvent + ApplySignal: the SDK delivers a pending event, in any order, to the handlers of the domains its
\* provider is bound to; the bridge's handler runs only for the provider bound to the slot and while it is registered.
\* The bridge applies the signal the SDK's state for the slot implies (a FATAL as sent); an event found while the slot
\* is NOT_READY changes nothing, and so does every event while the swap gate is up.
DeliverToBridge(ev) ==
    /\ ev \in events
    /\ bridgeOn /\ ev.p = bound
    /\ events' = events \ {ev}
    /\ status' =
         IF gate \/ shutDown THEN status
         ELSE IF ev.e = "FATAL" THEN Apply(status, "FATAL")
         ELSE IF sdk[bound] = "NOT_READY" THEN status
         ELSE Apply(status, sdk[bound])
    /\ UNCHANGED << installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, sdk, inits, ran, prevOf, emitted, shuts, twoInits >>

\* An event no handler of this instance receives: from a provider no longer bound, or after the bridge is gone.
Discard(ev) ==
    /\ ev \in events
    /\ ~(bridgeOn /\ ev.p = bound)
    /\ events' = events \ {ev}
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, orphans, bridgeOn, shutDown, closed,
                    bound, sdk, inits, ran, prevOf, emitted, shuts, twoInits >>

Deliver(ev) == DeliverToBridge(ev) \/ Discard(ev)

-----------------------------------------------------------------------------
(* OrphanedProviders *)

\* OrphanResolved: a watched provider settles.  It initialized, or reports READY or STALE: the SDK shut the previous
\* provider down when that initialize() returned; the entry is dropped.  ERROR or FATAL without having initialized:
\* the library shuts the previous provider down.
OrphanResolved(o) ==
    /\ o \in orphans
    /\ View(o) \in {"READY", "STALE", "ERROR", "FATAL"}
    /\ orphans' = orphans \ {o}
    /\ shuts' = IF Released(o) THEN shuts ELSE [shuts EXCEPT ![o.prev] = @ + 1]
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, bridgeOn, shutDown, closed,
                    bound, sdk, inits, ran, prevOf, events, emitted, twoInits >>

\* The API's shutdown, then the forced resolve of every pending entry: the API shuts the provider bound to the slot
\* down and interrupts every initialize() still running; an entry whose watched provider is not READY or STALE gets
\* its previous provider shut down.
TearDown ==
    /\ ~Settling
    /\ shuts' = [p \in All |->
                   shuts[p]
                   + (IF p = bound THEN 1 ELSE 0)
                   + Cardinality({o \in orphans : o.prev = p /\ ~Released(o)})]
    /\ inits' = [p \in All |-> 0]
    /\ orphans' = {}

-----------------------------------------------------------------------------
(* shutdown and the scope close *)

\* ShutdownStart: shutdown holds the lock (it waits for a swap in flight); the status is ShuttingDown and the bridge
\* is deregistered.  A shutdown may be called again after one completed: it runs again, harmlessly.
ShutdownStart ==
    /\ lock = "free" /\ ~closed
    /\ lock' = "shutdown"
    /\ status' = "ShuttingDown"
    /\ bridgeOn' = FALSE
    /\ UNCHANGED << installed, gate, swap, pending, orphans, shutDown, closed, sdkVars >>

\* ShutdownDone: the owning instance tears the API down; the status is NotReady for good.  A later shutdown finds the
\* API already torn down: the SDK's shutdown cleared its registrations, so nothing is shut down again.
ShutdownDone ==
    /\ lock = "shutdown"
    /\ IF shutDown THEN UNCHANGED << shuts, inits, orphans >> ELSE TearDown
    /\ status' = "NotReady"
    /\ shutDown' = TRUE
    /\ lock' = "free"
    /\ UNCHANGED << installed, gate, swap, pending, bridgeOn, closed,
                    bound, sdk, ran, prevOf, events, emitted, twoInits >>

\* ScopeClose: the API's scope finalizer.  After a shutdown there is nothing left to tear down.
ScopeClose ==
    /\ lock = "free" /\ ~closed
    /\ closed' = TRUE
    /\ bridgeOn' = FALSE
    /\ IF shutDown
         THEN UNCHANGED << shuts, inits, orphans >>
         ELSE TearDown
    /\ UNCHANGED << status, installed, lock, gate, swap, pending, shutDown,
                    bound, sdk, ran, prevOf, events, emitted, twoInits >>

-----------------------------------------------------------------------------
Next ==
    \/ \E p \in Providers : SwapStart(p)
    \/ SdkBind
    \/ \E p \in All : SdkInitOk(p) \/ SdkInitFail(p)
    \/ SwapDone
    \/ SwapTimeout
    \/ SwapFailNoRollback
    \/ \E ok, early \in BOOLEAN, r \in {"READY", "STALE", "ERROR", "FATAL"} : SwapRollback(ok, r, early)
    \/ \E p \in All, e \in {"READY", "STALE", "ERROR", "FATAL"} : Emit(p, e)
    \/ \E p \in All : EmitStaleWhileStarting(p)
    \/ \E ev \in events : Deliver(ev)
    \/ \E o \in orphans : OrphanResolved(o)
    \/ ShutdownStart
    \/ ShutdownDone
    \/ ScopeClose

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Invariants *)

\* Once shutdown has completed the status stays NotReady, apart from a later shutdown call's own ShuttingDown.
FinalNotReady == shutDown => status = IF lock = "shutdown" THEN "ShuttingDown" ELSE "NotReady"

\* "The status becomes NotReady while the SDK initializes the new provider", whatever events arrive meanwhile.
NotReadyDuringSwap == (swap # None) => status = "NotReady"

\* The gate is down whenever no swap holds the lock.
GateDownWhenIdle == lock # "swap" => ~gate

\* Shutdown and a swap never overlap: the swap lock has one holder.
OneLockHolder == (lock = "swap") <=> (swap # None)

\* No provider has initialize() entered while an earlier call runs.
NoConcurrentInit == ~twoInits /\ \A p \in All : inits[p] <= 1

\* No provider is shut down twice.
ShutDownAtMostOnce == \A p \in All : shuts[p] <= 1

\* Outside a swap or a shutdown, with every event of the installed provider delivered, the status is the one the SDK's
\* state for it implies (or Fatal, which no event leaves), and the slot binds the installed provider.
StatusFollowsSdk ==
    (lock = "free" /\ ~shutDown /\ ~closed /\ {ev \in events : ev.p = bound} = {})
      => /\ bound = installed
         /\ (status = "Fatal" \/ Matches(status, sdk[bound]))

\* When the scope has closed, nothing is left pending and every provider the instance ever registered has been shut
\* down exactly once.
ClosedCleanly ==
    closed => /\ orphans = {}
              /\ \A p \in Used : shuts[p] = 1
=============================================================================
