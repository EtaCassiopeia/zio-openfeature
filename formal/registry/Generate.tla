------------------------------ MODULE Generate ------------------------------
(***************************************************************************)
(* Random behaviours of Registry for a replay through the real code.       *)
(* GNext is Next with every disjunct labelled by the ghost `act`,          *)
(* restricted to what one replay can drive:                                *)
(*                                                                         *)
(*  - Topology: a behaviour is either one FeatureFlagRegistry ("registry": *)
(*    its API is private, so no owner, no user binding, no owner close) or *)
(*    layers built on one shared API ("shared": no registry promise map,   *)
(*    so no lookup, failed build, eviction, release or registry close).    *)
(*  - The registry builds its default slot from the provider it was        *)
(*    constructed with (FeatureFlagRegistry.defaultClient): there, Create  *)
(*    on Default takes DefaultProvider only.                               *)
(*  - FailMark only under a build of the shared topology: the init         *)
(*    deadline of an open instance needs a provider that never became      *)
(*    ready, which this model does not track.                              *)
(*  - One initialize() call per provider at a time (`inFlight`): no build  *)
(*    or swap binds a provider whose call is in flight, and only a call    *)
(*    the instance started can fail or time out under it.  A binding of a  *)
(*    provider already `ready`, or of one another slot binds (the SDK      *)
(*    reuses that registration, whatever its state), starts no call, so it *)
(*    cannot fail; a registry retry on the slot that holds its failed      *)
(*    provider initializes that provider again (StartsCall).               *)
(*  - No build or swap binds a provider another slot holds whose shutdown *)
(*    the library's wrapper absorbed (BindOk): the SDK reuses that         *)
(*    registration, which `registered` abstracts (README).  No build binds *)
(*    one another slot holds that is not ready, but a registry retry on    *)
(*    the slot holding its failed provider: the build reads that           *)
(*    registration's ERROR.  Nor one whose shutdown the SDK has queued:    *)
(*    one a retry's binding replaced, or one a swap in flight replaced,    *)
(*    neither by a                                                         *)
(*    build, a swap or the user, nor by a second swap off that provider    *)
(*    (whose rollback would bind it again).  The SDK decides at the        *)
(*    binding, where the model decides at SwapDone.                        *)
(*  - A registry retry on a provider already ready completes at once: the  *)
(*    SDK starts no initialize(), and shuts the provider it replaced down  *)
(*    in the binding (Complete is held next).                              *)
(*  - No swap times out unless AllowTimeouts: the replay cannot fire a     *)
(*    swap's deadline yet, and without a timeout no initialize() is left   *)
(*    to settle late (InitSettle).                                         *)
(*  - Steps the code takes in one go, with no probe point between them,    *)
(*    stay adjacent (`then`): a failed registry build is evicted next, and *)
(*    a swap whose provider another slot shares, already initialized, is   *)
(*    followed by the SDK's shutdown of the provider it replaced when no    *)
(*    slot holds that one (SwapOldShut, which the SDK runs in the binding); *)
(*    a release of the provider a retry replaced is followed by that       *)
(*    retry's failed build (releaseReplacedFailure runs inside it).  So a  *)
(*    build fails only with the semaphore free, for its eviction to take   *)
(*    it next, and a release is taken only when the retry's failure can    *)
(*    follow it.  ThenEnabled checks the step held to is always enabled.   *)
(*  - A Check takes the semaphore only when a Create can follow it, and   *)
(*    its Create follows at once, so nothing between them can take away   *)
(*    the provider it builds on (the registry's default slot builds on    *)
(*    DefaultProvider only, which a swap may have in flight).             *)
(*    LockProgresses and ThenEnabled check that the semaphore is always   *)
(*    released by the next step.                                          *)
(*  - The same Lookup twice in a row would change no variable: a stutter,  *)
(*    which Trace drops, leaving the behaviour short.  It is not taken.    *)
(*  - Closes are held back to the levels from CloseFrom on; once nothing   *)
(*    can happen, `Done` repeats, counting, so every behaviour reaches     *)
(*    Depth: TLC's Trace keeps no stuttering step.                         *)
(*                                                                         *)
(* Restricting only removes choices: every behaviour is one of Registry.   *)
(* The ghost `total` counts each provider's shutdowns over all its         *)
(* registrations (`shuts` restarts at a new registration), with the rule   *)
(* RegistryTrace's Counted uses, so the replay can compare it with the     *)
(* code's count.  Export writes each behaviour, once it reaches Depth, as  *)
(* one ndjson file of its states: every variable of the model, the ghosts  *)
(* and `act`.                                                              *)
(***************************************************************************)
EXTENDS Registry, Json, TLCExt, Sequences

CONSTANTS
    OutDir,          \* the directory the behaviours are written to, absolute
    Depth,           \* the number of states in each behaviour
    CloseFrom,       \* the level from which an instance, the owner or the registry may close
    Topology,        \* "registry" or "shared"
    DefaultProvider, \* the provider a registry builds its default slot from
    AllowTimeouts    \* whether a swap may time out

ASSUME /\ Topology \in {"registry", "shared"}
       /\ DefaultProvider \in Providers
       /\ AllowTimeouts \in BOOLEAN

VARIABLES
    act,
    ready,     \* providers whose initialize() returned in their current registration
    inFlight,  \* providers with an initialize() call a build or swap started that has not settled
    total,     \* each provider: how many times its shutdown() ran, over all its registrations
    then       \* the step the code takes next with no probe point before it, or None

GVars == << vars, act, ready, inFlight, total, then >>

Reg == Topology = "registry"
Shared == Topology = "shared"

GInit ==
    /\ Init
    /\ act = [name |-> "Init"]
    /\ ready = {}
    /\ inFlight = {}
    /\ total = [q \in Providers |-> 0]
    /\ then = None

Prov(i) == IF domainOf[i] = None THEN None ELSE bound[domainOf[i]]
Fresh(i) == Prov(i) \in inFlight

\* Whether a build (Create) or a swap may bind p on slot d. Any provider a slot does not hold binds again, whatever ended
\* its last registration: the library resets a stale attachment as it binds its wrapper. Not one a slot holds whose
\* shutdown the library's wrapper absorbed, whose registration the SDK reuses (`registered` abstracts it). A build binds
\* one another slot holds only once it is ready, or for a registry retry the failed provider its slot holds. Never one
\* whose shutdown the SDK has queued (a swap in flight replaced it, or a retry's binding did).
BindOk(name, d, p) ==
    /\ ~\E j \in swapping : prior[j] = p
    /\ replaced[p] = None
    /\ SlotsOf(p) # {} => p \in registered
    /\ name = "Create" =>
         /\ \/ p \in ready
            \/ SlotsOf(p) = {}
            \/ Reg /\ d # None /\ bound[d] = p
         \* No build starts a call on a provider a swap in flight binds: the code's swap ended on the registration it
         \* found, where the model would have that call decide it (one initialize() shared by two bindings).
         /\ ~\E j \in swapping : domainOf[j] # None /\ bound[domainOf[j]] = p

\* Whether binding p for instance i (a build or a swap) starts an initialize() call: when no slot holds p, which leaves
\* the SDK no registration of it to reuse; and for a registry retry on the slot holding its failed provider, which
\* initializes it again (ProviderRegistration.retryFailedInitialization). A binding of a provider another slot holds
\* reuses that registration, whatever its state, and starts none.
StartsCall(name, i, p) ==
    \/ SlotsOf(p) = {}
    \/ name = "Create" /\ bound[domainOf[i]] = p /\ p \notin ready

\* Held to `then`: only that step may run.
Free(name, i) == then = None \/ then = [name |-> name, i |-> i]

\* The retry on i's domain built on the provider i's failed build was replaced by: the one ReleaseReplaced(i) precedes.
Retry(i) ==
    {j \in Instances : /\ domainOf[j] = domainOf[i]
                       /\ phase[j] = "building"
                       /\ builtOn[i] # None
                       /\ builtOn[j] = replaced[builtOn[i]]}

\* BuildFail(j)'s guards but the one ReleaseReplaced lifts, and the semaphore free for the eviction after it.
CanFail(j) ==
    /\ phase[j] = "building"
    /\ owner # j
    /\ failedOn[j] = None
    /\ j \notin deciding
    /\ lock = None
    /\ Fresh(j)

\* Create as Work takes it: right after its Check.
CreateStep(i, p, own) ==
    /\ Free("Create", i)
    /\ (Reg => ~own)
    /\ (Reg /\ domainOf[i] = Default => p = DefaultProvider)
    /\ p \notin inFlight
    /\ BindOk("Create", domainOf[i], p)
    /\ Create(i, p, own)

\* A Check takes the semaphore only when a Create can follow it, which then does at once: one that could not would hold
\* the semaphore for good, leaving nothing to complete, fail or close.  These are Create's guards that read the
\* provider and the slot, which a Check leaves as they are.
CanCreateOn(d) ==
    \E p \in Providers \ inFlight :
        /\ Reg /\ d = Default => p = DefaultProvider
        /\ BindOk("Create", d, p)
        /\ p \notin pending /\ ~Waiting(p)
        /\ \/ bound[d] \in {None, p}
           \/ /\ \E j \in Instances : domainOf[j] = d /\ phase[j] = "evicted"
              /\ bound[d] \notin userOwned
        /\ p \in userOwned => bound[d] = p \/ HeldVisibly(p)

Work ==
    \/ /\ then = None
       /\ Reg
       /\ \E i \in Instances, d \in Domains :
            /\ act # [name |-> "Lookup", i |-> i, d |-> d]
            /\ Lookup(i, d) /\ act' = [name |-> "Lookup", i |-> i, d |-> d]
    \/ /\ then = None
       /\ \E i \in Instances, d \in Domains :
            CanCreateOn(d) /\ Check(i, d) /\ act' = [name |-> "Check", i |-> i, d |-> d]
    \/ \E i \in Instances, p \in Providers, own \in BOOLEAN :
         CreateStep(i, p, own) /\ act' = [name |-> "Create", i |-> i, p |-> p, own |-> own]
    \/ \E i \in Instances : Free("Complete", i) /\ Complete(i) /\ act' = [name |-> "Complete", i |-> i]
    \/ /\ Reg
       /\ \E i \in Instances :
            /\ Free("BuildFail", i)
            /\ CanFail(i)
            /\ BuildFail(i) /\ act' = [name |-> "BuildFail", i |-> i]
    \/ /\ Reg
       /\ \E i \in Instances : Free("Evict", i) /\ Evict(i) /\ act' = [name |-> "Evict", i |-> i]
    \/ /\ then = None
       /\ Reg
       /\ \E i \in Instances :
            /\ \A j \in Retry(i) : CanFail(j)
            /\ ReleaseReplaced(i) /\ act' = [name |-> "ReleaseReplaced", i |-> i]
    \/ /\ then = None
       /\ \E i \in Instances, p \in Providers :
            /\ p \notin inFlight
            /\ BindOk("SwapStart", domainOf[i], p)
            /\ ~\E j \in swapping : domainOf[i] # None /\ prior[j] = bound[domainOf[i]]
            \* Nor off a provider whose shutdown a timed-out swap queued: the SDK runs it when that call returns.
            /\ domainOf[i] # None /\ bound[domainOf[i]] # None => replaced[bound[domainOf[i]]] = None
            /\ SwapStart(i, p) /\ act' = [name |-> "SwapStart", i |-> i, p |-> p]
    \/ then = None /\ \E i \in Instances : SwapDone(i) /\ act' = [name |-> "SwapDone", i |-> i]
    \* Only in the binding that shares the registration: the SDK's early shutdown runs there or not at all, since no
    \* binding joins a call in flight here.
    \/ \E i \in Instances :
         then = [name |-> "SwapOldShut", i |-> i] /\ SwapOldShut(i) /\ act' = [name |-> "SwapOldShut", i |-> i]
    \/ then = None /\ \E i \in Instances : Fresh(i) /\ SwapFailMark(i) /\ act' = [name |-> "SwapFailMark", i |-> i]
    \/ then = None /\ \E i \in Instances : SwapFail(i) /\ act' = [name |-> "SwapFail", i |-> i]
    \/ /\ then = None
       /\ AllowTimeouts
       /\ \E i \in Instances : Fresh(i) /\ SwapTimeout(i) /\ act' = [name |-> "SwapTimeout", i |-> i]
    \/ /\ then = None
       /\ \E p \in Providers, ok \in BOOLEAN : InitSettle(p, ok) /\ act' = [name |-> "InitSettle", p |-> p, ok |-> ok]
    \/ /\ then = None
       /\ Shared
       /\ \E d \in Domains, p \in Providers :
            /\ ~\E j \in swapping : prior[j] = p
            /\ replaced[p] = None
            /\ UserBind(d, p) /\ act' = [name |-> "UserBind", d |-> d, p |-> p]
    \/ /\ then = None
       /\ Shared /\ \E i \in Instances : phase[i] = "building" /\ Fresh(i) /\ FailMark(i) /\ act' = [name |-> "FailMark", i |-> i]
    \* Not while a swap in flight would roll back to the provider this decision may shut down: the rollback reuses the
    \* registration the library's wrapper then absorbs, which `registered` abstracts. The decision waits for that swap.
    \/ /\ then = None
       /\ \E i \in Instances :
            /\ ~\E j \in swapping : domainOf[i] # None /\ prior[j] = bound[domainOf[i]]
            /\ FailDecide(i) /\ act' = [name |-> "FailDecide", i |-> i]

Late == TLCGet("level") >= CloseFrom \/ ~ENABLED Work

Closing ==
    /\ then = None
    /\ \/ \E i \in Instances : CloseInstance(i) /\ act' = [name |-> "CloseInstance", i |-> i]
       \/ Shared /\ OwnerClose /\ act' = [name |-> "OwnerClose"]
       \/ Reg /\ RegistryClose /\ act' = [name |-> "RegistryClose"]

\* The swap of i that just started shares a registration already initialized in another slot, and no slot holds the
\* provider it replaced: the SDK shuts that one down as part of the binding, before anything else (SwapOldShut).
EarlyShut(i) ==
    LET d == domainOf[i]
        old == bound[d]
    IN /\ old # None
       /\ old \in registered
       /\ \A e \in Domains \ {d} : bound[e] # old
       /\ \E e \in Domains \ {d} : bound[e] = act'.p

Starts == {"Create", "SwapStart"}
Outcomes == {"Complete", "BuildFail", "SwapDone", "SwapFailMark", "SwapTimeout", "FailMark"}
Successes == {"Complete", "SwapDone"}

Ghosts ==
    LET q == IF act'.name \in Outcomes THEN Prov(act'.i) ELSE None
    IN
    /\ inFlight' = IF act'.name \in Starts /\ StartsCall(act'.name, act'.i, act'.p) THEN inFlight \cup {act'.p}
                   ELSE IF act'.name \in Outcomes THEN inFlight \ {q}
                   ELSE inFlight
    \* Ready: a registration a slot still binds, whose initialize() returned. A success that ran no call (a swap onto a
    \* registration another slot holds in ERROR) leaves it as it was; once no slot holds a provider, the SDK has no
    \* registration of it left, and its next binding initializes it again.
    /\ ready' = (ready \cup (IF act'.name \in Successes /\ q \in inFlight THEN {q}
                             ELSE IF act'.name = "InitSettle" THEN (IF act'.ok THEN {act'.p} ELSE {})
                             ELSE IF act'.name = "UserBind" THEN {act'.p}
                             \* The rollback registers the previous provider again; when no other slot holds it, that
                             \* initializes it (the model takes that call's success), else it reuses that registration.
                             ELSE IF act'.name = "SwapFail" /\ SlotsOf(prior[act'.i]) = {} THEN {prior[act'.i]}
                             ELSE {})) \cap registered' \cap {r \in Providers : \E e \in Domains : bound'[e] = r}
    /\ total' = [p \in Providers |-> total[p] + (IF shuts'[p] > shuts[p] THEN shuts'[p] - shuts[p] ELSE 0)]
    /\ then' = CASE act'.name = "Check" -> [name |-> "Create", i |-> act'.i]
                 [] act'.name = "BuildFail" -> [name |-> "Evict", i |-> act'.i]
                 [] act'.name = "SwapStart" /\ EarlyShut(act'.i) -> [name |-> "SwapOldShut", i |-> act'.i]
                 [] act'.name = "Create" /\ act'.p \in ready /\ bound[domainOf[act'.i]] \notin {None, act'.p} ->
                        [name |-> "Complete", i |-> act'.i]
                 [] act'.name = "ReleaseReplaced" /\ Retry(act'.i) # {} ->
                        [name |-> "BuildFail", i |-> CHOOSE j \in Retry(act'.i) : TRUE]
                 [] OTHER -> None

GNext ==
    /\ \/ Work
       \/ Late /\ Closing
       \/ /\ ~ENABLED Work /\ ~ENABLED Closing
          /\ act.name = "Done" => act.n < Depth
          /\ UNCHANGED vars
          /\ act' = [name |-> "Done", n |-> IF act.name = "Done" THEN act.n + 1 ELSE 1]
    /\ Ghosts

GSpec == GInit /\ [][GNext]_GVars

\* The step held to can always be taken: every other step of Work waits while `then` is set, so Work is exactly that
\* step, and holding to it never strands a behaviour in `Done`.
ThenEnabled == then # None => ENABLED Work

\* The semaphore a Check took is released by its Create, the step held to next (which ThenEnabled checks can be taken).
LockProgresses == lock # None => then = [name |-> "Create", i |-> lock]

Export ==
    TLCGet("level") = Depth =>
        ndJsonSerialize(OutDir \o "/" \o ToString(TLCGet("stats").traces) \o ".ndjson", Trace)
=============================================================================
