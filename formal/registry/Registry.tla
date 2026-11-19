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

Phases == {"absent", "checking", "building", "open", "closed", "failed", "evicted"}

VARIABLES
    \* The library
    phase,       \* each instance: absent (not asked for), checking (looking the domain up under the semaphore),
                 \* building (its promise inserted, the build running outside the semaphore), open, closed, failed
                 \* (a registry build that failed, its promise not yet removed), evicted (that promise removed: the
                 \* domain has no instance, and a retry is another instance)
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
    swappedOff,  \* ghost: providers a swap that succeeded, or timed out, moved off a slot
    builtOn,     \* each instance: the provider its build bound, or None
    replaced,    \* each provider: the provider whose registration replaced it in a slot after a failed registry build,
                 \* or in a swap that timed out, or None; the SDK shuts it down once that one's initialize() succeeds
                 \* (shutDownOld)
    pending,     \* providers whose initialize(), started by a swap that timed out, is still running
    orphans,     \* the providers the owner's orphan list will shut down unless the SDK does: replaced in a swap of the
                 \* owner's that timed out, and held then by no slot the library can see (OrphanedProviders)
    leftBehind,  \* the library's wrappers a registry client's timed-out swap replaced: the registry releases each one
                 \* still running at its close, after the API's shutdown (FeatureFlagRegistry.releaseLeftBehind)
    abandoned    \* ghost: providers the user registered that the SDK let go when the provider replacing them failed to
                 \* initialize, with no slot holding them: theirs to shut down, as for any provider the SDK replaces

vars == << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
           failedOn, deciding, prior, swappedOff, builtOn, replaced, pending, orphans, leftBehind, abandoned >>
libVars == << phase, domainOf, owner, apiOpen, lock, swapping, swaps >>
timeoutVars == << pending, orphans, leftBehind, abandoned >>

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
    /\ builtOn \in [Instances -> Providers \cup {None}]
    /\ replaced \in [Providers -> Providers \cup {None}]
    /\ pending \subseteq Providers
    /\ orphans \subseteq Providers
    /\ leftBehind \subseteq Providers
    /\ abandoned \subseteq Providers

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
    /\ builtOn = [i \in Instances |-> None]
    /\ replaced = [p \in Providers |-> None]
    /\ pending = {}
    /\ orphans = {}
    /\ leftBehind = {}
    /\ abandoned = {}

\* The slots that hold p.
SlotsOf(p) == {d \in Domains : bound[d] = p}

\* A provider gets a state manager when it is bound while it has none; a provider shut down earlier and bound again is
\* a new registration, whose shutdown count starts over (as `shuts` does at SdkBind in Lifecycle.tla).
Register(p) ==
    IF p \in registered THEN UNCHANGED << shuts, by >>
    ELSE shuts' = [shuts EXCEPT ![p] = 0] /\ by' = [by EXCEPT ![p] = "none"]

\* The slots the library can see besides the build's own and the default: those of the instances built on the API.
Visible(p) == \E j \in Instances : domainOf[j] # None /\ bound[domainOf[j]] = p

\* The provider the registry builds domain d's client with: the domain's own, or the default slot's (the registry's
\* fallback to its default provider, which the default slot binds).
Live(i) == phase[i] \in {"building", "open"}

\* p waits on the initialize() a timed-out swap left running: the SDK shuts it down if that call succeeds.
Waiting(p) == replaced[p] \in pending

\* A provider a slot the library can see holds, the default one or an instance's: one the user registered there, or
\* the library's wrapper a sibling serves (OrphanedProviders.heldByVisibleSlot).
HeldVisibly(p) == bound[Default] = p \/ Visible(p)

---------------------------------------------------------------------------
\* The registry: getClient(d) / defaultClient.  The semaphore guards the map of promises, never a build: the first
\* caller finds no promise for the domain and inserts one under the semaphore, then builds outside it, and completes
\* the promise under it again; later callers find the promise and await it.

\* The domain has an instance (its promise is in the map): the caller awaits it and gets that instance, open or closed:
\* the registry keeps a client's promise until it closes itself, so a client shut down on its own is what later callers
\* get; a failed build's promise until it is evicted.  No state changes; the action is here so the README can name it.
Lookup(i, d) ==
    /\ apiOpen
    /\ phase[i] = "absent"
    /\ \E j \in Instances : j # i /\ phase[j] \notin {"absent", "evicted"} /\ domainOf[j] = d
    /\ UNCHANGED vars

\* No promise for d (none yet, or a failed build's evicted: a retry): the caller takes the semaphore to look the domain
\* up ...
Check(i, d) ==
    /\ apiOpen
    /\ phase[i] = "absent"
    /\ lock = None
    /\ ~\E j \in Instances : phase[j] \notin {"absent", "evicted"} /\ domainOf[j] = d
    /\ lock' = i
    /\ phase' = [phase EXCEPT ![i] = "checking"]
    /\ domainOf' = [domainOf EXCEPT ![i] = d]
    /\ UNCHANGED << owner, apiOpen, swapping, swaps, bound, registered, userOwned, shuts, by, everBound, failedOn, deciding, prior,
                    swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* ... inserts its promise, releases the semaphore and runs the build.  The build registers the domain's provider in
\* the slot: the SDK reuses the state manager of a provider that is registered already (the instance holds the same
\* wrapper, or, for a provider the user registered, Bound.Foreign), and creates one otherwise.  The first instance built on the API may own it (a sole-owner factory);
\* a registry's client never does (ownsApi = false): both are allowed here, and which provider the domain gets is left
\* open (the registry's own map, with the default provider as its fallback, is a choice of p).
\* A retry after a failed registry build finds the slot holding the failed build's provider q, which the registry left
\* running so a retry can initialize it again (ProviderRegistration.retryFailedInitialization).  A retry with another
\* provider replaces q in the slot; q keeps its registration, which the SDK ends once p's initialize() succeeds
\* (shutDownOld, in Complete), and which the library ends when p fails too (ReleaseReplaced).
Create(i, p, own) ==
    LET d == domainOf[i] IN
    /\ phase[i] = "checking"
    /\ lock = i
    /\ own => owner = None
    \* A registry client's timed-out swap is the registry topology's: no instance owns that API (SwapTimeout).
    /\ own => leftBehind = {}
    \* A provider whose initialize() a timed-out swap left running, or one waiting on that call: outside the model
    \* (README).
    /\ p \notin pending /\ ~Waiting(p)
    /\ lock' = None
    /\ \/ bound[d] \in {None, p}
       \/ /\ \E j \in Instances : domainOf[j] = d /\ phase[j] = "evicted"
          /\ \A j \in Instances \ {i} : domainOf[j] = d => phase[j] = "evicted"
          /\ bound[d] \notin userOwned
    \* A provider the user bound under a domain no instance is on is wrapped and initialized again: outside the model
    \* (README).
    /\ p \in userOwned => bound[d] = p \/ bound[Default] = p \/ Visible(p)
    /\ phase' = [phase EXCEPT ![i] = "building"]
    /\ owner' = IF own THEN i ELSE owner
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ swappedOff' = swappedOff \ {p}
    /\ builtOn' = [builtOn EXCEPT ![i] = p]
    /\ replaced' = IF bound[d] \notin {None, p} THEN [replaced EXCEPT ![bound[d]] = p] ELSE replaced
    /\ UNCHANGED << domainOf, apiOpen, swapping, swaps, userOwned, failedOn, deciding, prior >>
    /\ UNCHANGED timeoutVars

\* The build ends and the promise is completed under the semaphore (taken and released in this step); the instance is
\* open.  Its provider initialized, so the SDK shuts down each provider it replaced in the slot that no slot holds any
\* more (shutDownOld).
Complete(i) ==
    LET p == bound[domainOf[i]]
        old == {q \in Providers : replaced[q] = p /\ p # None}
        shut == {q \in old : SlotsOf(q) = {} /\ q \in registered}
    IN
    /\ phase[i] = "building"
    /\ lock = None
    /\ failedOn[i] = None
    /\ phase' = [phase EXCEPT ![i] = "open"]
    /\ registered' = registered \ shut
    /\ userOwned' = userOwned \ shut
    /\ shuts' = [q \in Providers |-> IF q \in shut THEN shuts[q] + 1 ELSE shuts[q]]
    /\ by' = [q \in Providers |-> IF q \in shut THEN "sdk" ELSE by[q]]
    /\ replaced' = [q \in Providers |-> IF q \in old THEN None ELSE replaced[q]]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, everBound, failedOn, deciding, prior, swappedOff,
                    builtOn >>
    /\ UNCHANGED timeoutVars

\* A registry build fails (its wait for readiness): the promise is settled with the error, and evicted in a step of
\* its own (Evict), so callers retry.  Its provider is left running and in its slot, unmarked, for a retry to
\* initialize again.  When the build replaced a failed build's provider no slot holds any more, it releases that one
\* first (ReleaseReplaced): the SDK shuts a replaced provider down only on the replacing one's success.
BuildFail(i) ==
    /\ phase[i] = "building"
    /\ owner # i
    /\ failedOn[i] = None
    /\ i \notin deciding
    /\ ~\E q \in Providers : /\ replaced[q] = builtOn[i]
                            /\ q \in registered
                            /\ q \notin userOwned
                            /\ SlotsOf(q) = {}
    /\ phase' = [phase EXCEPT ![i] = "failed"]
    \* Its provider never initialized, so the SDK shuts down nothing it replaced.
    /\ replaced' = [q \in Providers |-> IF replaced[q] = builtOn[i] THEN None ELSE replaced[q]]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    failedOn, deciding, prior, swappedOff, builtOn >>
    /\ UNCHANGED timeoutVars

\* The failed build's promise is removed under the semaphore (runBuild's lock.withPermit(remove *> promise.fail)): the
\* domain has no instance, and the next getClient builds another.
Evict(i) ==
    /\ phase[i] = "failed"
    /\ lock = None
    /\ phase' = [phase EXCEPT ![i] = "evicted"]
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

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

\* A retry with another provider failed as well: the domain's previous failed client releases the provider its build
\* bound, which the retry replaced (FeatureFlagRegistry.releaseReplacedFailure, the previous client's
\* shutDownFailedBuild), deciding as a failed build does (LibraryShuts).  Before the retry's BuildFail; or after a
\* deadline or failure under the retry closed it (FailDecide), which its failed wait for readiness then follows.
ReleaseReplaced(i) ==
    LET q == builtOn[i] IN
    /\ apiOpen
    /\ phase[i] = "evicted"
    /\ q # None
    /\ replaced[q] # None
    /\ \E j \in Instances : /\ domainOf[j] = domainOf[i]
                            /\ phase[j] \in {"building", "closed"}
                            /\ builtOn[j] = replaced[q]
    /\ ShutFailed(i, q)
    /\ replaced' = [replaced EXCEPT ![q] = None]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, userOwned, everBound, failedOn, deciding,
                    prior, swappedOff, builtOn >>
    /\ UNCHANGED timeoutVars

\* A swap at the registry grain.  The SDK binds the new provider before its initialize() runs, so a sibling may reuse its
\* registration from then on.  A swap is refused when a sibling's initialize() of p has started by the time it binds; one
\* whose SDK task the sibling's task overtakes still shares that call, and then the SDK may shut the provider it replaces
\* down before the swap's outcome (SwapOldShut).
SwapStart(i, p) ==
    LET d == domainOf[i] IN
    /\ apiOpen
    /\ phase[i] = "open"
    /\ swapping = None
    /\ swaps < MaxSwaps
    /\ p # bound[d]
    /\ i \notin deciding
    /\ p \in userOwned => bound[Default] = p \/ Visible(p)
    \* A swap to a provider still initializing after a timed-out swap, or to one waiting on that call, is refused until
    \* that call settles (refuseSwap); a swap off one is outside the model (README).
    /\ p \notin pending /\ ~Waiting(p)
    /\ bound[d] \notin pending
    /\ swapping' = i
    /\ swaps' = swaps + 1
    /\ prior' = [prior EXCEPT ![i] = bound[d]]
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ failedOn' = [failedOn EXCEPT ![i] = None]
    /\ swappedOff' = swappedOff \ {p}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, userOwned, deciding, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

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
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, bound, everBound, deciding, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* A sibling's binding of the swap's provider shares its registration, and its initialize() started first: the swap's
\* SDK task finds the call under way, returns at once and shuts the provider the swap replaced down when no slot holds
\* it (shutDownOld), before the swap's outcome.  A rollback then registers that provider again, as a new registration.
SwapOldShut(i) ==
    LET d == domainOf[i]
        old == prior[i]
    IN
    /\ swapping = i
    /\ old # None
    /\ old \in registered
    /\ SlotsOf(old) = {}
    /\ \E e \in Domains \ {d} : bound[e] = bound[d]
    /\ registered' = registered \ {old}
    /\ userOwned' = userOwned \ {old}
    /\ shuts' = [shuts EXCEPT ![old] = @ + 1]
    /\ by' = [by EXCEPT ![old] = "sdk"]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, everBound, failedOn, deciding, prior,
                    swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* The new provider failed to initialize: the instance counts as failed on it for its siblings from here on.
SwapFailMark(i) ==
    /\ swapping = i
    /\ failedOn[i] # bound[domainOf[i]]
    /\ failedOn' = [failedOn EXCEPT ![i] = bound[domainOf[i]]]
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,
                    deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

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
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, everBound, failedOn, deciding, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* The new provider did not initialize within initTimeout: the swap ends with it bound and its initialize() still running
\* (runSwap's timeout branch), status Error, and no rollback. The previous provider q is held only by that call, and
\* the SDK shuts it down if the call succeeds (`replaced`). An instance that owns its API records q in its orphan list
\* (recordOrphan), which shuts q down if the call fails or the instance closes first, unless a slot the library can
\* see held q when it was recorded, one the user registered or a sibling's wrapper: that slot's, and the SDK's, to shut
\* down. A registry client records nothing; FeatureFlagRegistry.setProvider keeps q for the registry's close
\* (`leftBehind`), which releases it if it is still running then. Outside the model (README): a non-owner on an API an
\* instance owns, whose close releases nothing; and a swap's provider another slot shares.
SwapTimeout(i) ==
    LET d == domainOf[i]
        p == bound[d]
        q == prior[i]
    IN
    /\ swapping = i
    /\ failedOn[i] # p
    /\ owner = i \/ owner = None
    /\ SlotsOf(p) = {d}
    /\ replaced[q] = None
    /\ swapping' = None
    /\ prior' = [prior EXCEPT ![i] = None]
    /\ pending' = pending \cup {p}
    /\ replaced' = [replaced EXCEPT ![q] = p]
    /\ orphans' = IF owner = i /\ ~HeldVisibly(q) THEN orphans \cup {q} ELSE orphans
    /\ leftBehind' = IF owner # i /\ q \notin userOwned THEN leftBehind \cup {q} ELSE leftBehind
    \* It moved off q, as a swap that succeeded does: a slot that still holds q keeps it for the API's shutdown.
    /\ swappedOff' = swappedOff \cup {q}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swaps, bound, registered, userOwned, shuts, by, everBound,
                    failedOn, deciding, builtOn, abandoned >>

\* The initialize() of p, a timed-out swap's provider, settles (ok: it returned). On success the SDK shuts down each
\* provider waiting on it that no slot holds (shutDownOld); the orphan list leaves one the SDK skipped because a slot
\* the library can see holds it. On failure the SDK shuts nothing down, and the orphan list shuts each of its orphans
\* down, unless a slot the library can see holds one by now.
\* A provider the user registered that nothing holds any more is the user's (`abandoned`); a registry client's wrapper
\* waits for the registry's close (`leftBehind`).
InitSettle(p, ok) ==
    LET waiting == {q \in Providers : replaced[q] = p}
        sdk == IF ok THEN {q \in waiting : SlotsOf(q) = {} /\ q \in registered} ELSE {}
        lib == IF ok THEN {q \in waiting \cap orphans : q \notin userOwned /\ q \notin sdk /\ q \in registered /\
                                                        ~HeldVisibly(q)}
               ELSE {q \in waiting \cap orphans : q \in registered /\ ~HeldVisibly(q)}
        ab == IF ok THEN {} ELSE {q \in waiting \ lib : q \in userOwned /\ SlotsOf(q) = {}}
        \* A shutdown of a provider a slot still holds leaves its registration in place, so the invariants see it.
        ended == sdk \cup ab \cup {q \in lib : SlotsOf(q) = {}}
    IN
    /\ apiOpen
    /\ p \in pending
    /\ pending' = pending \ {p}
    /\ registered' = registered \ ended
    /\ userOwned' = userOwned \ ended
    /\ shuts' = [q \in Providers |-> IF q \in sdk \cup lib THEN shuts[q] + 1 ELSE shuts[q]]
    /\ by' = [q \in Providers |-> IF q \in sdk THEN "sdk" ELSE IF q \in lib THEN "library" ELSE by[q]]
    /\ replaced' = [q \in Providers |-> IF q \in waiting THEN None ELSE replaced[q]]
    /\ orphans' = orphans \ waiting
    /\ abandoned' = abandoned \cup ab
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, everBound, failedOn, deciding, prior,
                    swappedOff, builtOn, leftBehind >>

---------------------------------------------------------------------------
\* The user binds a provider of their own directly with the API, and the SDK holds it: an instance built on it later
\* holds it as Bound.Foreign.  Or binds again one of theirs that only a timed-out swap's pending initialize() holds, in
\* a slot the library can see: an orphan list then leaves it to that slot.
UserBind(d, p) ==
    /\ apiOpen
    /\ bound[d] = None
    /\ \/ p \notin everBound
       \/ p \in userOwned /\ SlotsOf(p) = {} /\ (d = Default \/ \E j \in Instances : domainOf[j] = d)
    /\ bound' = [bound EXCEPT ![d] = p]
    /\ registered' = registered \cup {p}
    /\ userOwned' = userOwned \cup {p}
    /\ everBound' = everBound \cup {p}
    /\ Register(p)
    /\ UNCHANGED << libVars, failedOn, deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

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
    \* A timed-out swap's provider has no init deadline of its own here.
    /\ bound[domainOf[i]] \notin pending
    /\ failedOn' = [failedOn EXCEPT ![i] = bound[domainOf[i]]]
    /\ deciding' = deciding \cup {i}
    /\ UNCHANGED << phase, domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by,
                    everBound, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* The decision: a build's failure then closes its scope.
FailDecide(i) ==
    /\ i \in deciding
    /\ deciding' = deciding \ {i}
    /\ phase' = IF phase[i] = "building" THEN [phase EXCEPT ![i] = "closed"] ELSE phase
    /\ ShutFailed(i, bound[domainOf[i]])
    /\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, userOwned, everBound, failedOn, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

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
                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* What tearing the API down does, by its owner, an instance or the registry: every instance on the API retires
\* (ApiInstances.retireSiblings, retireAll), a build in flight included, then the API shuts every registered provider
\* down, once each (the SDK's shutdown), and the API is gone.  The SDK shuts down the providers its slots hold: one a
\* failed registry build's retry replaced is no longer the SDK's, and is left running unless released.  Unless the retry
\* has not settled (`replaced` still names its provider): that provider's initialize() task outlives the API's shutdown
\* on the SDK's executor and shuts the replaced one down on success (shutDownOld).  On failure nothing does, and the
\* registry's interrupted build releases nothing: outside the model (README).
RetireAll == phase' = [i \in Instances |-> IF Live(i) THEN "closed" ELSE phase[i]]

\* The providers the API's shutdown ends.  One waiting on a timed-out swap's initialize() counts too: the model takes
\* that call's success, as for a retry's.
ApiShuts == {p \in registered : SlotsOf(p) # {} \/ replaced[p] # None}

\* The API's shutdown, and `rel`, the providers the library shuts down with it: the owner's orphans, the registry's
\* left-behind wrappers.  A wrapper the SDK's shutdown reaches too absorbs the second call, so each counts once.
ShutApi(rel) ==
    /\ apiOpen' = FALSE
    /\ bound' = [d \in Domains |-> None]
    /\ registered' = {}
    /\ userOwned' = {}
    /\ shuts' = [p \in Providers |-> shuts[p] + (IF p \in ApiShuts \cup rel THEN 1 ELSE 0)]
    /\ by' = [p \in Providers |-> IF p \in rel THEN "library" ELSE IF p \in ApiShuts THEN "sdk" ELSE by[p]]

\* The owner's shutdown or scope close: the others retire first, then its own teardown takes the API down, and then
\* settles its orphan list (OrphanedProviders.resolveAll): each orphan still running is shut down, as no slot holds it
\* any more.
OwnerClose ==
    /\ apiOpen
    /\ owner # None
    /\ phase[owner] = "open"
    /\ swapping = None
    /\ deciding = {}
    /\ lock = None
    /\ RetireAll
    /\ ShutApi({q \in orphans : q \in registered})
    /\ owner' = None
    /\ UNCHANGED << domainOf, lock, swapping, swaps, everBound, failedOn, deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

\* The registry's finalizer: it owns its API but is no instance (no instance owns it), so its close is the API's.
\* Its clients' scopes close before it, each a CloseInstance; it then retires every instance on the API
\* (ApiInstances.retireAll) and shuts the API down (boundedApiShutdown).  Each retirement takes the instance's swap
\* lock, so a swap in flight settles first.  After the API's shutdown it releases each wrapper a client's timed-out swap
\* left behind that is still running (releaseLeftBehind): one whose replacement failed, which nothing else reaches.
RegistryClose ==
    /\ apiOpen
    /\ owner = None
    /\ swapping = None
    /\ deciding = {}
    /\ lock = None
    /\ RetireAll
    /\ ShutApi({q \in leftBehind : q \in registered /\ q \notin ApiShuts})
    /\ UNCHANGED << domainOf, owner, lock, swapping, swaps, everBound, failedOn, deciding, prior, swappedOff, builtOn, replaced >>
    /\ UNCHANGED timeoutVars

Next ==
    \/ \E i \in Instances, d \in Domains : Lookup(i, d)
    \/ \E i \in Instances, d \in Domains : Check(i, d)
    \/ \E i \in Instances, p \in Providers, own \in BOOLEAN : Create(i, p, own)
    \/ \E i \in Instances : Complete(i)
    \/ \E i \in Instances : BuildFail(i)
    \/ \E i \in Instances : Evict(i)
    \/ \E i \in Instances : ReleaseReplaced(i)
    \/ \E i \in Instances, p \in Providers : SwapStart(i, p)
    \/ \E i \in Instances : SwapDone(i)
    \/ \E i \in Instances : SwapOldShut(i)
    \/ \E i \in Instances : SwapFailMark(i)
    \/ \E i \in Instances : SwapFail(i)
    \/ \E i \in Instances : SwapTimeout(i)
    \/ \E p \in Providers, ok \in BOOLEAN : InitSettle(p, ok)
    \/ \E d \in Domains, p \in Providers : UserBind(d, p)
    \/ \E i \in Instances : FailMark(i)
    \/ \E i \in Instances : FailDecide(i)
    \/ \E i \in Instances : CloseInstance(i)
    \/ OwnerClose
    \/ RegistryClose

Spec == Init /\ [][Next]_vars

ProviderSymmetry == Permutations(Providers)

---------------------------------------------------------------------------
\* Properties.

\* At most one instance owns the API, and it is live until the API is gone.
OneOwner ==
    /\ Cardinality({i \in Instances : owner = i}) <= 1
    /\ owner # None => apiOpen /\ Live(owner)

\* No provider is shut down twice, and once the API is gone every provider that was bound was shut down exactly once,
\* but one of the user's the SDK let go when its replacement failed.
SharedShutDownOnce ==
    /\ \A p \in Providers : shuts[p] <= 1
    /\ ~apiOpen => \A p \in everBound \ abandoned : shuts[p] = 1

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
\* instance's slot keeps the wrapper for the API's owner, as a closed non-owner's does.  And except one a failed registry
\* build left in its slot: the registry keeps it running for a retry, unmarked, and its close shuts it down with the API.
FailedWrapperNotAbandoned ==
    \A p \in (registered \ userOwned) \ swappedOff :
        LET holders == {i \in Instances : phase[i] \in {"building", "open", "closed", "failed", "evicted"}
                                           /\ bound[domainOf[i]] = p}
        IN (holders # {} /\ \A j \in Instances : prior[j] # p)
           => \E i \in holders : failedOn[i] # p \/ i \in deciding \/ swapping = i

\* Concurrent getClient calls on one domain yield one instance: at most one promise per domain, ever.
OneInstancePerDomain ==
    \A d \in Domains : Cardinality({i \in Instances : phase[i] \notin {"absent", "evicted"} /\ domainOf[i] = d}) <= 1

\* Only a failed build's promise is evicted: a client a caller got keeps its promise, and a build still running too.
OnlyFailedBuildsEvicted ==
    [][\A i \in Instances : phase'[i] = "evicted" /\ phase[i] # "evicted" => phase[i] = "failed"]_vars

\* Once the API is gone no instance serves: every one built on it, or building, was retired with it.
ClosedApiServesNone ==
    ~apiOpen => \A i \in Instances : ~Live(i)

\* A non-owner's close changes no sibling and no slot.
SiblingsUnaffected ==
    [][\A i \in Instances :
        CloseInstance(i) => /\ bound' = bound
                            /\ \A j \in Instances \ {i} : phase'[j] = phase[j]]_vars

\* The slot of a closed non-owner keeps its provider until the owner closes the API.
ClosedNonOwnerKeepsSlot ==
    \A i \in Instances : phase[i] = "closed" /\ apiOpen => bound[domainOf[i]] # None

=============================================================================
