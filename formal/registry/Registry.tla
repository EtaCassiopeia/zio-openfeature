------------------------------ MODULE Registry ------------------------------
(***************************************************************************)
(* The ownership rules of one OpenFeature API shared by several            *)
(* FeatureFlagsLive instances on several domains: the registry topology    *)
(* (FeatureFlagRegistry) and the shared-API topology (several layers on    *)
(* one API, of which one owns it).                                         *)
(*                                                                         *)
(* Coarser than formal/lifecycle: each instance's swap protocol is         *)
(* abstracted to a phase, and is assumed correct, as Lifecycle.tla checks  *)
(* it for one instance.  What this model keeps is what the ownership bugs  *)
(* were about: who tears the API down, which provider is shut down by      *)
(* whom and how often, the registry's promise per domain under its         *)
(* semaphore, and the SDK's one registration per provider across slots    *)
(* (a provider bound to a second domain reuses its state manager: a        *)
(* wrapper stays Bound.Managed, and only a provider the user registered    *)
(* is Bound.Foreign; a failure shuts down neither while it is in use).     *)
(* README.md maps each action to the code.                                 *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
    Instances,   \* the FeatureFlagsLive instances that may be built on the API
    Domains,     \* the SDK's slots, one of them Default
    Default,     \* the default slot: the registry's defaultClient, and the fallback provider's slot
    Providers,   \* the providers an instance may be built with or swapped to
    MaxSwaps,    \* how many swaps may start in one behaviour
    None

ASSUME ConstantsOK ==
    /\ Default \in Domains
    /\ None \notin Instances /\ None \notin Domains /\ None \notin Providers
    /\ IsFiniteSet(Instances) /\ IsFiniteSet(Domains) /\ IsFiniteSet(Providers)
    /\ MaxSwaps \in Nat

Phases == {"absent", "checking", "building", "open", "closed"}

VARIABLES
    \* The library
    phase,       \* each instance: absent (not asked for), checking (looking the domain up under the semaphore),
                 \* building (its promise inserted, the build running outside the semaphore), open, closed
    domainOf,    \* each instance's slot, or None
    owner,       \* the instance that owns the API, or None: its shutdown tears the API down
    apiOpen,     \* the API has not been torn down
    lock,        \* the registry's semaphore: the instance holding it, or None
    swapping,    \* the instance with a swap in flight, or None (one at a time: each holds its own lock, and the
                 \* registry's setProvider swaps the one client of the domain)
    swaps,       \* how many swaps started
    \* The SDK
    bound,       \* each slot's provider, or None
    registered,  \* providers with a state manager (ProviderRepository.isStateManagerRegistered)
    userOwned,   \* registered providers the user bound to a slot directly, outside the library: Bound.Foreign
    \* Ghosts
    shuts,       \* how many times each provider's shutdown() ran
    by,          \* who ran it last: "none", "sdk" or "library"
    everBound,   \* providers that were bound to some slot at some point
    failedOn,    \* each instance: the provider a failure under it marked (failedRegistration), or None
    deciding,    \* instances that marked a failure and have not yet decided about the shutdown
    prior,       \* each instance: the provider its swap in flight replaced, or None
    swappedOff   \* ghost: providers a swap that succeeded moved off a slot

vars == << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
           failedOn, deciding, prior, swappedOff >>
libVars == << phase, domainOf, owner, apiOpen, lock, swapping, swaps >>

TypeInv ==
    /\ phase \in [Instances -> Phases]
    /\ domainOf \in [Instances -> Domains \cup {None}]
    /\ owner \in Instances \cup {None}
    /\ apiOpen \in BOOLEAN
    /\ lock \in Instances \cup {None}
    /\ swapping \in Instances \cup {None}
    /\ swaps \in 0..MaxSwaps
    /\ bound \in [Domains -> Providers \cup {None}]
    /\ registered \subseteq Providers
    /\ userOwned \subseteq registered
    /\ failedOn \in [Instances -> Providers \cup {None}]
    /\ deciding \subseteq Instances
    /\ prior \in [Instances -> Providers \cup {None}]
    /\ swappedOff \subseteq Providers
    /\ shuts \in [Providers -> Nat]
    /\ by \in [Providers -> {"none", "sdk", "library"}]
    /\ everBound \subseteq Providers

Init ==
    /\ phase = [i \in Instances |-> "absent"]
    /\ domainOf = [i \in Instances |-> None]
    /\ owner = None
    /\ apiOpen = TRUE
    /\ lock = None
    /\ swapping = None
    /\ swaps = 0
    /\ bound = [d \in Domains |-> None]
    /\ registered = {}
    /\ userOwned = {}
    /\ shuts = [p \in Providers |-> 0]
    /\ by = [p \in Providers |-> "none"]
    /\ everBound = {}
    /\ failedOn = [i \in Instances |-> None]
    /\ deciding = {}
    /\ prior = [i \in Instances |-> None]
    /\ swappedOff = {}

\* The slots that hold p.
SlotsOf(p) == {d \in Domains : bound[d] = p}

\* A provider gets a state manager when it is bound while it has none; a provider shut down earlier and bound again is
\* a new registration, whose shutdown count starts over (as `shuts` does at SdkBind in Lifecycle.tla).
Register(p) ==
    IF p \in registered THEN UNCHANGED << shuts, by >>
    ELSE shuts' = [shuts EXCEPT ![p] = 0] /\ by' = [by EXCEPT ![p] = "none"]

\* The provider the registry builds domain d's client with: the domain's own, or the default slot's (the registry's
\* fallback to its default provider, which the default slot binds).
Live(i) == phase[i] \in {"building", "open"}

---------------------------------------------------------------------------
\* The registry: getClient(d) / defaultClient.  The semaphore guards the map of promises, never a build: the first
\* caller finds no promise for the domain and inserts one under the semaphore, then builds outside it, and completes
\* the promise under it again; later callers find the promise and await it.

\* The domain has an instance (its promise is in the map): the caller awaits it and gets that instance, open or closed:
\* the registry keeps a client's promise until it closes itself, so a client shut down on its own is what later callers
\* get.  No state changes; the action is here so the README can name it.
Lookup(i, d) ==
    /\ apiOpen
    /\ phase[i] = "absent"
    /\ \E j \in Instances : j # i /\ phase[j] # "absent" /\ domainOf[j] = d
    /\ UNCHANGED vars

\* No promise for d: the caller takes the semaphore to look the domain up ...
Check(i, d) ==
    /\ apiOpen
    /\ phase[i] = "absent"
    /\ lock = None
    /\ ~\E j \in Instances : phase[j] # "absent" /\ domainOf[j] = d
    /\ lock' = i
    /\ phase' = [phase EXCEPT ![i] = "checking"]
    /\ domainOf' = [domainOf EXCEPT ![i] = d]
    /\ UNCHANGED << owner, apiOpen, swapping, swaps, bound, registered, userOwned, shuts, by, everBound, failedOn, deciding, prior, swappedOff >>

\* ... inserts its promise, releases the semaphore and runs the build.  The build registers the domain's provider in
\* the slot: the SDK reuses the state manager of a provider that is registered already (the instance holds the same
\* wrapper, or, for a provider the user registered, Bound.Foreign), and creates one otherwise.  The first instance built on the API may own it (a sole-owner factory);
\* a registry's client never does (ownsApi = false): both are allowed here, and which provider the domain gets is left
\* open (the registry's own map, with the default provider as its fallback, is a choice of p).
Create(i, p, own) ==
    LET d == domainOf[i] IN
    /\ phase[i] = "checking"
    /\ lock = i
    /\ own => owner = None
    /\ lock' = None
    /\ bound[d] \in {None, p}
    \* A provider the user bound under another domain is wrapped and initialized again: outside the model (README).
    /\ p \in userOwned => bound[d] = p \/ bound[Default] = p
    /\ phase' = [phase EXCEPT ![i] = "building"]
    /\ owner' = IF own THEN i ELSE owner
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ swappedOff' = swappedOff \ {p}
    /\ UNCHANGED << domainOf, apiOpen, swapping, swaps, userOwned, failedOn, deciding, prior >>

\* The build ends and the promise is completed under the semaphore (taken and released in this step); the instance is
\* open.
Complete(i) ==
    /\ phase[i] = "building"
    /\ lock = None
    /\ failedOn[i] = None
    /\ phase' = [phase EXCEPT ![i] = "open"]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    failedOn, deciding, prior, swappedOff >>

---------------------------------------------------------------------------
\* What a failure under instance i does to its provider p (FeatureFlagsLive.shutDownFailedRegistration): the library
\* shuts p down only when p is its own wrapper and no other live instance that has not failed on it is bound to it.
\* The wrapper stays in its slot and absorbs the SDK's later shutdown(), so `registered` here means a registration with
\* a shutdown still to come, not the SDK's state manager, which stays (README).
LibraryShuts(i, p) ==
    /\ p \notin userOwned
    /\ \A j \in Instances \ {i} : ~(Live(j) /\ bound[domainOf[j]] = p /\ failedOn[j] # p)

ShutFailed(i, p) ==
    IF p \in registered /\ LibraryShuts(i, p)
    THEN /\ shuts' = [shuts EXCEPT ![p] = @ + 1]
         /\ by' = [by EXCEPT ![p] = "library"]
         /\ registered' = registered \ {p}
    ELSE UNCHANGED << shuts, by, registered >>

\* A swap at the registry grain.  The SDK binds the new provider before its initialize() runs, so a sibling may reuse its
\* registration from then on, and a swap admitted before the sibling registered shares the sibling's attempt.  Here the
\* provider it replaces stays registered until the swap's outcome; the SDK may shut it down sooner in that shared case.
SwapStart(i, p) ==
    LET d == domainOf[i] IN
    /\ apiOpen
    /\ phase[i] = "open"
    /\ swapping = None
    /\ swaps < MaxSwaps
    /\ p # bound[d]
    /\ i \notin deciding
    /\ p \in userOwned => bound[Default] = p
    /\ swapping' = i
    /\ swaps' = swaps + 1
    /\ prior' = [prior EXCEPT ![i] = bound[d]]
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ failedOn' = [failedOn EXCEPT ![i] = None]
    /\ swappedOff' = swappedOff \ {p}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, userOwned, deciding >>

\* It initialized: the SDK shuts the old provider down only when no slot holds it any more (shutDownOld's guard); one
\* another slot still holds keeps its state manager.
SwapDone(i) ==
    LET d == domainOf[i]
        old == prior[i]
    IN
    /\ swapping = i
    /\ failedOn[i] # bound[d]
    /\ swapping' = None
    /\ prior' = [prior EXCEPT ![i] = None]
    /\ registered' = registered \ (IF SlotsOf(old) = {} THEN {old} ELSE {})
    /\ userOwned' = userOwned \ (IF SlotsOf(old) = {} THEN {old} ELSE {})
    \* A wrapper the library already shut down (a failed build's, a deadline's) absorbs this call: not in `registered`.
    /\ shuts' = [q \in Providers |-> IF q = old /\ SlotsOf(old) = {} /\ old \in registered THEN shuts[q] + 1 ELSE shuts[q]]
    /\ by' = [q \in Providers |-> IF q = old /\ SlotsOf(old) = {} /\ old \in registered THEN "sdk" ELSE by[q]]
    /\ failedOn' = [failedOn EXCEPT ![i] = None]
    /\ swappedOff' = swappedOff \cup {old}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, bound, everBound, deciding >>

\* The new provider failed to initialize: the instance counts as failed on it for its siblings from here on.
SwapFailMark(i) ==
    /\ swapping = i
    /\ failedOn[i] # bound[domainOf[i]]
    /\ failedOn' = [failedOn EXCEPT ![i] = bound[domainOf[i]]]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    deciding, prior, swappedOff >>

\* The rollback registers the old provider again, which its slot held until the swap.  The SDK shuts the failed provider
\* down when no slot holds it any more.  When another slot does the SDK leaves it, and the library decides.
SwapFail(i) ==
    LET d == domainOf[i]
        p == bound[d]
        old == prior[i]
        others == SlotsOf(p) \ {d}
        shut == IF others = {} THEN p \in registered
                ELSE p \in registered /\ LibraryShuts(i, p)
    IN
    /\ swapping = i
    /\ failedOn[i] = p
    /\ swapping' = None
    /\ prior' = [prior EXCEPT ![i] = None]
    /\ bound' = [bound EXCEPT ![d] = old]
    /\ registered' = (registered \ (IF shut THEN {p} ELSE {})) \cup {old}
    /\ userOwned' = userOwned \ (IF others = {} THEN {p} ELSE {})
    \* The old provider's registration is new when the library shut it down earlier, as Register has it.
    /\ shuts' = [q \in Providers |-> IF q = p /\ shut THEN shuts[q] + 1
                                     ELSE IF q = old /\ old \notin registered THEN 0 ELSE shuts[q]]
    /\ by' = [q \in Providers |-> IF q = p /\ shut THEN (IF others = {} THEN "sdk" ELSE "library")
                                  ELSE IF q = old /\ old \notin registered THEN "none" ELSE by[q]]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, everBound, failedOn, deciding, swappedOff >>

---------------------------------------------------------------------------
\* The user binds a provider of their own directly with the API, and the SDK holds it: an instance built on it later
\* holds it as Bound.Foreign.
UserBind(d, p) ==
    /\ apiOpen
    /\ bound[d] = None
    /\ p \notin everBound
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ userOwned' = userOwned \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ UNCHANGED << libVars, failedOn, deciding, prior, swappedOff >>

\* A failure marks itself before it looks at its siblings, in its own step, so two failures may interleave here: a
\* build's wait that timed out or a provider that failed, where the slot keeps p; or an async build's init deadline, once
\* per registration, where the instance stays open, Fatal.  That the provider never became ready is the lifecycle
\* model's (`everReady`), so a deadline may fire on any open instance.  An owner's failed build takes the API with it
\* on the scope close, which OwnerClose is.
FailMark(i) ==
    /\ apiOpen
    /\ phase[i] \in {"building", "open"}
    /\ phase[i] = "building" => owner # i
    /\ swapping # i
    /\ failedOn[i] # bound[domainOf[i]]
    /\ bound[domainOf[i]] \in registered
    /\ failedOn' = [failedOn EXCEPT ![i] = bound[domainOf[i]]]
    /\ deciding' = deciding \cup {i}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by,
                    everBound, prior, swappedOff >>

\* The decision: a build's failure then closes its scope.
FailDecide(i) ==
    /\ i \in deciding
    /\ deciding' = deciding \ {i}
    /\ phase' = IF phase[i] = "building" THEN [phase EXCEPT ![i] = "closed"] ELSE phase
    /\ ShutFailed(i, bound[domainOf[i]])
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, userOwned, everBound, failedOn, prior, swappedOff >>

---------------------------------------------------------------------------
\* Closing.

\* A non-owner's shutdown or scope close: its own state only.  The slot keeps its provider, which the API's owner tears
\* down with the API.
CloseInstance(i) ==
    /\ phase[i] = "open"
    /\ owner # i
    /\ swapping # i
    /\ i \notin deciding
    /\ phase' = [phase EXCEPT ![i] = "closed"]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    failedOn, deciding, prior, swappedOff >>

\* The owner's shutdown or scope close: every other instance on the API retires first (ApiInstances.retireSiblings),
\* then the API shuts every registered provider down, once each (the SDK's shutdown), and the API is gone.
OwnerClose ==
    /\ apiOpen
    /\ owner # None
    /\ phase[owner] = "open"
    /\ swapping = None
    /\ deciding = {}
    /\ lock = None
    /\ phase' = [i \in Instances |-> IF phase[i] = "open" THEN "closed" ELSE phase[i]]
    /\ apiOpen' = FALSE
    /\ owner' = None
    /\ bound' = [d \in Domains |-> None]
    /\ registered' = {}
    /\ userOwned' = {}
    /\ shuts' = [p \in Providers |-> shuts[p] + (IF p \in registered THEN 1 ELSE 0)]
    /\ by' = [p \in Providers |-> IF p \in registered THEN "sdk" ELSE by[p]]
    /\ UNCHANGED << domainOf, lock, swapping, swaps, everBound, failedOn, deciding, prior, swappedOff >>

Next ==
    \/ \E i \in Instances, d \in Domains : Lookup(i, d)
    \/ \E i \in Instances, d \in Domains : Check(i, d)
    \/ \E i \in Instances, p \in Providers, own \in BOOLEAN : Create(i, p, own)
    \/ \E i \in Instances : Complete(i)
    \/ \E i \in Instances, p \in Providers : SwapStart(i, p)
    \/ \E i \in Instances : SwapDone(i)
    \/ \E i \in Instances : SwapFailMark(i)
    \/ \E i \in Instances : SwapFail(i)
    \/ \E d \in Domains, p \in Providers : UserBind(d, p)
    \/ \E i \in Instances : FailMark(i)
    \/ \E i \in Instances : FailDecide(i)
    \/ \E i \in Instances : CloseInstance(i)
    \/ OwnerClose

Spec == Init /\ [][Next]_vars

ProviderSymmetry == Permutations(Providers)

---------------------------------------------------------------------------
\* Properties.

\* At most one instance owns the API, and it is live until the API is gone.
OneOwner ==
    /\ Cardinality({i \in Instances : owner = i}) <= 1
    /\ owner # None => apiOpen /\ Live(owner)

\* No provider is shut down twice, and once the API is gone every provider that was bound was shut down exactly once.
SharedShutDownOnce ==
    /\ \A p \in Providers : shuts[p] <= 1
    /\ ~apiOpen => \A p \in everBound : shuts[p] = 1

\* A registered provider is never shut down while the API is open: its state manager is the API's.
RegisteredNotShutDown ==
    apiOpen => \A p \in registered : shuts[p] = 0

\* A library shutdown never hits a provider the API has registered (a Foreign one): only the SDK shuts those down.
ForeignNeverShutByLibrary ==
    \A p \in Providers : by[p] = "library" => p \notin registered

\* The library never shuts down a provider the user registered, on any failure path.
UserProviderNeverShutByLibrary ==
    \A p \in userOwned : by[p] # "library"

\* A failure under one instance never takes the provider from a live sibling bound to it.
LiveSiblingKeepsProvider ==
    \A i \in Instances :
        Live(i) /\ bound[domainOf[i]] # None /\ failedOn[i] # bound[domainOf[i]] => by[bound[domainOf[i]]] # "library"

\* Failures do not each defer to the other: a wrapper whose every holder has failed on it and decided has been shut down.
\* Except one a swap that succeeded moved off: a sibling a failure deferred to may swap away, and then the failed
\* instance's slot keeps the wrapper for the API's owner, as a closed non-owner's does.
FailedWrapperNotAbandoned ==
    \A p \in (registered \ userOwned) \ swappedOff :
        LET holders == {i \in Instances : phase[i] \in {"building", "open", "closed"} /\ bound[domainOf[i]] = p}
        IN (holders # {} /\ \A j \in Instances : prior[j] # p)
           => \E i \in holders : failedOn[i] # p \/ i \in deciding \/ swapping = i

\* Concurrent getClient calls on one domain yield one instance: at most one promise per domain, ever.
OneInstancePerDomain ==
    \A d \in Domains : Cardinality({i \in Instances : phase[i] # "absent" /\ domainOf[i] = d}) <= 1

\* A non-owner's close changes no sibling and no slot.
SiblingsUnaffected ==
    [][\A i \in Instances :
        CloseInstance(i) => /\ bound' = bound
                            /\ \A j \in Instances \ {i} : phase'[j] = phase[j]]_vars

\* The slot of a closed non-owner keeps its provider until the owner closes the API.
ClosedNonOwnerKeepsSlot ==
    \A i \in Instances : phase[i] = "closed" /\ apiOpen => bound[domainOf[i]] # None

=============================================================================
