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
(* (a provider bound to a second domain reuses its state manager, and the *)
(* library then holds it as Bound.Foreign, which it never shuts down).    *)
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
    \* Ghosts
    shuts,       \* how many times each provider's shutdown() ran
    by,          \* who ran it last: "none", "sdk" or "library"
    everBound    \* providers that were bound to some slot at some point

vars == << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>
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
    /\ shuts = [p \in Providers |-> 0]
    /\ by = [p \in Providers |-> "none"]
    /\ everBound = {}

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
    /\ UNCHANGED << owner, apiOpen, swapping, swaps, bound, registered, shuts, by, everBound >>

\* ... inserts its promise, releases the semaphore and runs the build.  The build registers the domain's provider in
\* the slot: the SDK reuses the state manager of a provider that is registered already (the library holds it as
\* Bound.Foreign), and creates one otherwise.  The first instance built on the API may own it (a sole-owner factory);
\* a registry's client never does (ownsApi = false): both are allowed here, and which provider the domain gets is left
\* open (the registry's own map, with the default provider as its fallback, is a choice of p).
Create(i, p, own) ==
    LET d == domainOf[i] IN
    /\ phase[i] = "checking"
    /\ lock = i
    /\ own => owner = None
    /\ lock' = None
    /\ phase' = [phase EXCEPT ![i] = "building"]
    /\ owner' = IF own THEN i ELSE owner
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ UNCHANGED << domainOf, apiOpen, swapping, swaps >>

\* The build ends and the promise is completed under the semaphore (taken and released in this step); the instance is
\* open.
Complete(i) ==
    /\ phase[i] = "building"
    /\ lock = None
    /\ phase' = [phase EXCEPT ![i] = "open"]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>

---------------------------------------------------------------------------
\* A swap at the registry grain: setProvider on an open instance rebinds its slot.  The old provider leaves
\* `registered` and is shut down by the SDK only when no slot holds it any more (shutDownOld's guard); a provider
\* another slot still holds keeps its state manager.  A swap to a provider that fails to initialize rolls back: the
\* failed provider is shut down by the library only when it was the library's own registration (Bound.Managed), never
\* when another slot holds it (Bound.Foreign).

SwapStart(i, p) ==
    /\ apiOpen
    /\ phase[i] = "open"
    /\ swapping = None
    /\ swaps < MaxSwaps
    /\ p # bound[domainOf[i]]
    /\ swapping' = i
    /\ swaps' = swaps + 1
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, bound, registered, shuts, by, everBound >>

SwapDone(i, p) ==
    LET d == domainOf[i]
        old == bound[d]
    IN
    /\ swapping = i
    /\ p # old
    /\ swapping' = None
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = (registered \cup {p}) \ (IF SlotsOf(old) = {d} THEN {old} ELSE {})
    /\ shuts' = [q \in Providers |-> IF q = old /\ SlotsOf(old) = {d} THEN shuts[q] + 1
                                     ELSE IF q = p /\ p \notin registered THEN 0 ELSE shuts[q]]
    /\ by' = [q \in Providers |-> IF q = old /\ SlotsOf(old) = {d} THEN "sdk"
                                  ELSE IF q = p /\ p \notin registered THEN "none" ELSE by[q]]
    /\ everBound' = everBound \cup {p}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps >>

\* The new provider failed to initialize: the slot keeps the old provider (the rollback re-registers it).  The failed
\* provider never reached the slot; the library shuts it down when it registered it fresh (Managed), and leaves it
\* alone when another slot holds it (Foreign): the SDK's, when that slot lets go of it.
SwapFail(i, p) ==
    /\ swapping = i
    /\ p # bound[domainOf[i]]
    /\ swapping' = None
    /\ IF p \in registered
       THEN UNCHANGED << shuts, by >>
       ELSE /\ shuts' = [shuts EXCEPT ![p] = 1]  \* a fresh registration, shut down once
            /\ by' = [by EXCEPT ![p] = "library"]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, bound, registered, everBound >>

---------------------------------------------------------------------------
\* Closing.

\* A non-owner's shutdown or scope close: its own state only.  The slot keeps its provider, which the API's owner tears
\* down with the API.
CloseInstance(i) ==
    /\ phase[i] = "open"
    /\ owner # i
    /\ swapping # i
    /\ phase' = [phase EXCEPT ![i] = "closed"]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>

\* The owner's shutdown or scope close: every other instance on the API retires first (ApiInstances.retireSiblings),
\* then the API shuts every registered provider down, once each (the SDK's shutdown), and the API is gone.
OwnerClose ==
    /\ apiOpen
    /\ owner # None
    /\ phase[owner] = "open"
    /\ swapping = None
    /\ lock = None
    /\ phase' = [i \in Instances |-> IF phase[i] = "open" THEN "closed" ELSE phase[i]]
    /\ apiOpen' = FALSE
    /\ owner' = None
    /\ bound' = [d \in Domains |-> None]
    /\ registered' = {}
    /\ shuts' = [p \in Providers |-> shuts[p] + (IF p \in registered THEN 1 ELSE 0)]
    /\ by' = [p \in Providers |-> IF p \in registered THEN "sdk" ELSE by[p]]
    /\ UNCHANGED << domainOf, lock, swapping, swaps, everBound >>

Next ==
    \/ \E i \in Instances, d \in Domains : Lookup(i, d)
    \/ \E i \in Instances, d \in Domains : Check(i, d)
    \/ \E i \in Instances, p \in Providers, own \in BOOLEAN : Create(i, p, own)
    \/ \E i \in Instances : Complete(i)
    \/ \E i \in Instances, p \in Providers : SwapStart(i, p)
    \/ \E i \in Instances, p \in Providers : SwapDone(i, p)
    \/ \E i \in Instances, p \in Providers : SwapFail(i, p)
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
