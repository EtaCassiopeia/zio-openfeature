# Registry and shared-API model

`Registry.tla` is a TLA+ model of one `OpenFeatureAPI` shared by several `FeatureFlagsLive` instances on several
domains: the registry topology (`FeatureFlagRegistry`, one client per domain, the registry owning the API) and the
shared-API topology (several layers on one API, one of them owning it). It checks the ownership rules the design
record lists (`docs/design/lifecycle.md`, #652): who tears the API down, which provider is shut down by whom and how
often, one client per domain under the registry's semaphore, and that a provider bound to a second domain is one
registration the library never shuts down.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar tlc2.TLC -workers auto -deadlock -config Registry.cfg Registry.tla
python3 mutants.py tla2tools.jar
```

`Registry.cfg` has two instances, three domains (the default slot and two more, so a build can reuse a provider from a
slot that is neither its own nor the default), two providers and two swaps: 181356
distinct states, a few seconds. CI runs it, and the mutants, in the `tlc` job of `formal.yml` on every pull request
that touches the model or the code it maps. `-deadlock` turns off TLC's deadlock check, as for the lifecycle model: a
registry whose clients have all closed, or an API torn down, has no action enabled and is not an error.

## Composition with the lifecycle model

The model is coarse on purpose. Each instance's swap protocol (the gate, the outcome, the rollback, the orphans, the
bridge) is abstracted to a phase, `absent`, `checking`, `building`, `open` or `closed` (and, for a registry build that
failed, `failed`, then `evicted`), and a swap to its steps
(`SwapStart`, then `SwapDone`, or `SwapFailMark` and `SwapFail`, or `SwapTimeout` and, later, `InitSettle`). That protocol is checked for one instance by `formal/lifecycle`, whose
per-instance invariants hold for each instance here by that check; this model adds what one instance cannot show: the
API's single owner, the SDK's one state manager per provider across slots, the registry's one promise per domain,
and who shuts down the provider a timed-out swap replaced, of an instance that owns its API or of a registry's client.
The two models compose by assumption, not by TLC: nothing here re-checks a swap's internals, and nothing there has a
second instance.

## Actions and the code

| Action | Code |
|:--|:--|
| `Lookup(i, d)` | `FeatureFlagRegistryLive.memoizedClient`: the promise for `d` is in `clients` (or `defaultRef`), so the caller awaits it. The registry keeps a client's promise until the registry closes, so a client shut down on its own is what later callers get; a failed build's until `Evict` |
| `Check(i, d)` | `memoizedClient`: `lock.withPermit`, no promise for `d` (none yet, or a failed build's evicted: a retry, which is another instance): insert one. The semaphore is held from the lookup to the insert, which is what makes two callers of one domain one build. The insert and the fork of the build are one uninterruptible section, so no step ends between `Check` and `Create`: an interrupt of the caller lands while it waits for the semaphore, before `Check`, or on the promise, after `Create`, and is a stutter in both places |
| `Create(i, p, own)` | the build, outside the semaphore: `buildClient` picks the domain's provider (`providers(d)`, or the default provider), `ProviderRegistration.register` binds it to the slot. The SDK reuses the state manager of a provider it has registered (`ProviderRepository.isStateManagerRegistered`), so a second domain holds the same wrapper, still `Bound.Managed` and guarded by `ApiInstances.heldByAnother`; only a provider the user registered, in the target or default slot, is `Bound.Foreign` (`Bound.of`, `ProviderStateAccess.boundIn`). `own`: `FeatureFlagsLive.ownsApi`, true for the sole-owner factories, false for a registry client (`addShutdownFinalizer = false`) and for a layer on a shared API. A retry finds the slot holding the failed build's provider, left running for it: the same provider is initialized again first (`ProviderRegistration.retryFailedInitialization`) and bound as before; another one replaces it in the slot (`replaced`), and the replaced one keeps its registration until the SDK or the library ends it |
| `Complete(i)` | `runBuild`: `lock.withPermit(... promise.succeed(client))`. Its provider initialized, so the SDK's `shutDownOld` shuts down a provider it replaced in the slot that no slot holds any more |
| `BuildFail(i)` | `buildClient`: `awaitReady` failed; `releaseReplacedFailure` ran first (`ReleaseReplaced`, its guard here), then the failed client is kept in `lastFailed` and its child scope closes. Its provider is left running and in its slot, unmarked, so a retry can initialize it again |
| `Evict(i)` | `runBuild`'s typed-failure branch: `lock.withPermit(remove *> promise.fail(err))` |
| `ReleaseReplaced(i)` | `releaseReplacedFailure`: a retry with another provider failed too, so the domain's previous failed client (`lastFailed`) runs `shutDownFailedBuild` on its own provider, which the retry replaced, deciding as a failed build does (`LibraryShuts`). The SDK would shut it down only on the replacing provider's success. The registry's close releases it too (`leftBehind`), so this step only makes the release prompt, which no property checks: no mutant drops it |
| `SwapStart(i, p)` | `FeatureFlagsLive.setProvider`, or `FeatureFlagRegistry.setProvider(d, p)` on the domain's client; one swap per instance, as each instance holds its own swap lock: swaps of different instances, and so of different domains of a registry, overlap. The SDK binds `p` before its `initialize()` runs, so a sibling built on `p` from here shares the attempt. A swap that reaches its binding after a sibling's `initialize()` of `p` has started is refused (`ProviderRegistration.StillInitializing`), and one whose SDK task the sibling's task overtakes still shares it |
| `SwapDone(i)` | the swap succeeded. The SDK's `shutDownOld` shuts the replaced provider down only when no slot holds its state manager any more; one another slot still holds keeps it |
| `SwapOldShut(i)` | a sibling's binding of the swap's provider shares its registration and its `initialize()` started first: the swap's SDK task finds the call under way, returns at once, and shuts the replaced provider down (`shutDownOld`) when no slot holds it, before the swap's outcome. A rollback then registers it again, as a new registration. Only before the outcome: one after a rollback would shut down a provider the rollback had just registered again |
| `SwapFailMark(i)` | `runSwap`, the failure branch, setting `failedRegistration`: the instance no longer counts as using `p` (a timed-out swap is not marked: `SwapTimeout`) |
| `SwapFail(i)` | the swap's rollback: the slot gets its provider back. With no other slot on `p` the SDK shuts it down (`formal/lifecycle` has which of the two does); with one, the SDK skips it and `rollBack`, through `ApiInstances.boundElsewhere`, decides as a failed build does. A rollback that fails too (`cleanupUnboundFailedProvider`) makes the same decision and is not a separate action |
| `SwapTimeout(i)` | `runSwap`'s timeout branch: no rollback, the new provider stays bound with its `initialize()` running (`pending`), status `Error`. The previous provider `q` is held only by that call, whose success makes the SDK shut it down (`shutDownOld`, `replaced`). An instance that owns its API records `q` (`recordOrphan`, `OrphanedProviders.record`); the orphan list will shut it down (`orphans`) unless a slot the library can see held it when it was recorded (`heldWhenRecorded`): one the user registered, or the library's wrapper a sibling serves. An instance that does not own its API, a registry client or not, records nothing and leaves `q`, the library's wrapper, to the API's owner (`ApiInstances.leaveBehind`, `leftBehind`), whose close releases it if it is still running then. A later build or swap of either provider is refused until the call settles (`refuseSwap`; `Waiting` here) |
| `InitSettle(p, ok)` | the timed-out swap's provider's `initialize()` settles, and `OrphanedProviders.settle` with it. Returned: the SDK shuts down each provider waiting on it that no slot holds; an orphan of the library's wrapper the SDK skipped, the orphan list shuts down after its wait, unless a slot the library can see holds it (`heldByVisibleSlot`), which is the only reason the SDK skips one here. Threw: the orphan list shuts each orphan down unless a slot the library can see holds one by now (`heldByVisibleSlot`); a user's provider nothing holds is theirs (`abandoned`, `docs/providers.md`); a non-owner's wrapper waits for the close of the API's owner, an instance or the registry (`leftBehind`) |
| `UserBind(d, p)` | the user's own `api.setProvider`, outside the library: an instance built on that provider later holds it as `Bound.Foreign`. Also of one of theirs that only a timed-out swap's pending `initialize()` holds, in a slot the library can see: the orphan list then leaves it |
| `FailMark(i)` | the start of `FeatureFlagsLive.shutDownFailedRegistration`, setting `failedRegistration`: from a build's `failedBuild` (`FeatureFlagsFactories`), after a wait that timed out or a provider that failed, or an async build's init deadline (`escalateInitTimeout`), where the instance stays open, `Fatal`. That the provider never became ready is the lifecycle model's `everReady`, so a deadline may fire on any open instance. An owner's failed build goes through `OwnerClose`, on its scope close |
| `FailDecide(i)` | the rest of it: `LibraryShuts` is `Bound.Managed` and not `ApiInstances.heldByAnother`, whose `serves` skips an instance that failed on the provider. A build's failure then closes its scope; a retry is another instance here |
| `CloseInstance(i)` | a non-owner's `shutdown` or scope close: `FeatureFlagsLive.teardown(tearDownApi = false)`, its own state only (the long comment there) |
| `OwnerClose` | the owner's `shutdown` or scope close: `ApiInstances.retireSiblings` (every other instance on the API retires, `FeatureFlagsLive.retire`), then `ProviderRegistration.boundedApiShutdown`, the SDK's `shutdown`, which shuts every registered provider down once, then `OrphanedProviders.resolveAll`, which shuts down each orphan whose replacement has not settled, then `ApiInstances.releaseLeftBehind`, which shuts down each wrapper a non-owner's timed-out swap replaced that is still running: one whose replacement failed |
| `RegistryClose` | the registry's layer finalizer, which owns the API with no instance in the owner's role: `ApiInstances.retireAll(api) *> boundedApiShutdown(api)`; every live client, a building one included, is retired, then the API's shutdown shuts every provider its slots hold down once. A provider a retry replaced is no longer in a slot: the SDK's shutdown skips it, so one nobody released leaks (`SharedShutDownOnce`). A timed-out swap's provider's `initialize()` task outlives the API's shutdown and shuts the provider waiting on it down on success (`ApiShuts`). Then `ApiInstances.releaseLeftBehind` shuts down each wrapper a client's timed-out swap, or a retry with another provider, replaced that is still running: one whose replacement failed, or whose build the close interrupted (`buildClient` leaves the domain's last failed wrapper there when a retry with another provider starts) |

## The variables and the code

| Variable | Code |
|:--|:--|
| `phase[i]` | whether `i`'s promise is in the registry's map and whether its build returned; `closed` is `FeatureFlagsLive.teardown` having run |
| `domainOf[i]` | the instance's `domain`; the default slot's client, whose `domain` is `None` in the code, has `Default` here |
| `owner` | the instance with `ownsApi`; `None` once it tore the API down, or when none owns it (the registry: see `OwnerClose`) |
| `apiOpen` | the API has not been shut down |
| `lock` | the registry's `Semaphore(1)`: who holds it |
| `swapping` | the instances whose `setProvider` is in flight. Each instance holds its own swap lock, so swaps on different instances of one API overlap; one instance's swaps never do |
| `bound[d]` | the SDK slot: `api.getProvider(d)` |
| `registered` | the providers with a state manager: `ProviderRepository.isStateManagerRegistered`. One exception: a wrapper a failure shut down leaves it, though the SDK keeps its manager in the slot, because the wrapper absorbs the SDK's later `shutdown()`. So here it means a registration with a shutdown still to come, and `ForeignNeverShutByLibrary` says nothing about these paths: the two invariants below do |
| `shuts[p]`, `by[p]` | ghosts: how many times `p`'s `shutdown()` ran in its current registration, and whether the SDK or the library ran it last. A provider registered again after a shutdown is a new registration, and its count starts over, as `shuts` does at `SdkBind` in the lifecycle model |
| `everBound` | ghost: the providers some slot held at some point |
| `userOwned` | the registered providers the user bound outside the library: the instance's `Bound.Foreign` |
| `failedOn[i]` | `FeatureFlagsLive.failedRegistration`: the provider a failure under `i` marked, until a swap starts |
| `deciding` | ghost: the instances between marking a failure and deciding about the shutdown |
| `prior[i]` | the provider `i`'s swap in flight replaced: what `runSwap` keeps for the rollback |
| `swappedOff` | ghost: the providers a successful swap moved off a slot |
| `builtOn[i]` | the provider `i`'s build bound (`buildClient`'s `provider`) |
| `replaced[q]` | the provider whose binding after a failed registry build, or in a swap that timed out, replaced `q` in its slot, until its `initialize()` settles or `q` is released: the SDK's `shutDownOld` of `q` pending on that call |
| `pending` | the providers a timed-out swap left initializing: the `initialize()` an orphan's watcher awaits (`Registration.initOutcome`) |
| `orphans` | the owner's `OrphanedProviders` entries that the list will shut down: those not `heldWhenRecorded` |
| `leftBehind` | `ApiInstances.leftBehind` for the API: only the library's wrappers are left there (`leaveBehind`) |
| `abandoned` | ghost: providers the user registered that the SDK let go when their replacement failed to initialize, with no slot holding them: the user's to shut down, as `docs/providers.md` says |

## What it checks

| Invariant | Claim |
|:--|:--|
| `OneOwner` | at most one instance owns the API, and the owner is checking, building or open until it tears the API down |
| `SharedShutDownOnce` | no registration is shut down twice, and once the API is gone every provider that was bound was shut down exactly once, but one of the user's the SDK let go (`abandoned`) |
| `RegisteredNotShutDown` | while the API is open, a provider with a state manager has not been shut down: its state manager is the API's, and only the API's shutdown, or the SDK's `shutDownOld` when the last slot lets go of it, shuts it down |
| `ForeignNeverShutByLibrary` | a shutdown the library ran never left a provider registered: a failure's shutdown, a rollback's included, ends the wrapper's registration |
| `UserProviderNeverShutByLibrary` | no failure path shuts down a provider the user registered and the API still holds |
| `LiveSiblingKeepsProvider` | a failure under one instance never shuts down a wrapper a live sibling that has not failed on it is bound to |
| `FailedWrapperNotAbandoned` | failures do not each defer to the other: a wrapper every holder has failed on and decided about has been shut down, unless a swap may still return to it or a successful one moved off it, where the slot keeps it for the API's owner |
| `OneInstancePerDomain` | at most one promise per domain at a time (an evicted one is gone): concurrent `getClient` calls on one domain yield one client |
| `ClosedNonOwnerKeepsSlot` | the slot of a closed non-owner keeps its provider until the owner closes the API |
| `ClosedApiServesNone` | once the API is gone, no instance is live: an owner's or the registry's close retires them all |
| `SiblingsUnaffected` (an action property) | a non-owner's close changes no slot and no sibling's phase |
| `OnlyFailedBuildsEvicted` (an action property) | only a failed build's promise is evicted: a client a caller got, or a build still running, keeps its promise |

`Registry.cfg` (two instances, two providers, two swaps) and `Concurrent.cfg` (the same with a third provider, about 900k states) check them all on every run of the `tlc` job. Swaps of different instances overlap in both: the model once allowed one swap per API, and recorded runs of instances swapping at once were rejected until it allowed one per instance.

## Mutants

`mutants.py` puts each ownership bug into the model and requires each property named for it to catch it, checking
them one at a time: a mutant usually breaks several, and in one run TLC reports whichever its workers reach first.

| Mutant | Caught by |
|:--|:--|
| A non-owner tears the API down (#652: layers sharing the global API clobbered each other's slot) | `ClosedNonOwnerKeepsSlot`, `SiblingsUnaffected` |
| A domain client shuts its provider down directly (the double shutdown `FeatureFlagsLive.teardown`'s comment warns about) | `RegisteredNotShutDown`, `ForeignNeverShutByLibrary`, `SharedShutDownOnce` |
| A non-owner's close retires its siblings | `SiblingsUnaffected` |
| `getClient` looks the domain up and inserts its promise without the semaphore | `OneInstancePerDomain` |
| The library shuts a provider a live sibling holds down on a rollback (#728, #800) | `LiveSiblingKeepsProvider` |
| A rollback leaves a failed sibling's wrapper to the SDK, which skips it (#800) | `FailedWrapperNotAbandoned` |
| A failed build or an init deadline shuts down a provider the user registered (#796) | `UserProviderNeverShutByLibrary` |
| A failed build or an init deadline shuts down a wrapper a live sibling is bound to (#796) | `LiveSiblingKeepsProvider` |
| A failure defers to a sibling that has failed on the provider too, so with no owner nothing shuts it down | `FailedWrapperNotAbandoned` |
| The registry closes without retiring its clients | `ClosedApiServesNone` |
| The registry's close shuts every provider ever bound, a double shutdown | `SharedShutDownOnce` |
| The registry closes during a swap | `SharedShutDownOnce` |
| A live client's promise is evicted | `OnlyFailedBuildsEvicted` |
| A registry closed while a retry with another provider initializes leaves the provider the retry replaced running (`buildClient` leaves nothing behind) | `SharedShutDownOnce` |
| A registry client's timed-out swap whose provider then fails leaves the provider it replaced running (`releaseLeftBehind` dropped, the code before it) | `SharedShutDownOnce` |
| A non-owner's timed-out swap on an API an instance owns whose provider then fails leaves the provider it replaced running (`releaseLeftBehind` dropped from the owner's close) | `SharedShutDownOnce` |
| An owner's timed-out swap records no orphan | `SharedShutDownOnce` |
| The orphan list shuts down a provider of the user's that a slot the library can see holds | `UserProviderNeverShutByLibrary`, `RegisteredNotShutDown` |

## What is outside the model

- **The per-instance swap protocol**: `formal/lifecycle`. A swap here is start and outcome, or a timeout and the
  settling of the call it left running; its gate, its rollback's own registration and a timed-out rollback's orphan are
  there.
- **Around a timed-out swap.** `SwapTimeout` and `InitSettle` cover what the recorded runs do: an owner's timed-out
  swap off its own wrapper or off a provider the user registered, settled by a failure or by the owner's close, and a
  non-owner's, a registry client's or not, released at the close of the API's owner. Left out, each by a guard:
  - *A swap's provider another slot shares, and a build or swap of a provider still initializing or waiting on that
    call* (`SwapTimeout`, `Create`, `SwapStart`): the SDK shares one `initialize()` across those slots, which the
    abstraction to `pending` does not follow; `setProvider` refuses most of them anyway.
  - *A second timed-out swap off a provider a first one left waiting*, from another slot (`SwapTimeout`:
    `replaced[q] = None`): the provider then waits on two `initialize()` calls, where `replaced` holds one.
  - *A swap off a timed-out swap's provider while it initializes*, and an init deadline on it (`SwapStart`,
    `FailMark`): the lifecycle model's (`SwapFailNoRollback`).
  - *The user binding a waiting provider of theirs under a domain no instance is on* (`UserBind`): as below.
- **A provider the user bound under a domain no instance is on.** The library cannot see that slot, so it wraps the
  provider and initializes it again (`Bound.of`, `docs/providers.md`), and a failure shuts it down. `Create` and
  `SwapStart` exclude it. Upstream `OpenFeatureAPI` has no accessor for a provider's domains.
- **The registry's provider map.** `setProvider(d, p)` on a domain without a client records `p` for the build to pick
  up; the model lets `Create` choose any provider, which covers the map and the default fallback alike.
- **`fromAcquireAsync`.** Its switch is not an SDK registration (`docs/design/lifecycle.md`); on a shared API it is an
  instance like any other here.
- **Time**, as in the lifecycle model.
- **What the recorded runs cannot reach** (see Trace validation, and `trace-map.md` for the labeler's reasons):
  - *A provider the user bound under a domain no instance is on.* The library wraps it and binds the wrapper, so the
    SDK holds two registrations of one provider, raw and wrapper; the probe keys a provider by its raw identity, so the
    two registrations' shutdowns cannot be told apart, and `Create` and `SwapStart` exclude it (`docs/providers.md`).
  - *The user bound a provider the library had bound as its wrapper.* The same two registrations, the other way round.
  - *Two shared-API layers on one domain.* A documented misuse (`FeatureFlagsFactories.warnIfSlotBound` warns): the second
    build replaces the first's provider in the slot. Covering it would need `Create` to replace a slot's provider
    outside a registry retry, and a flag telling the two topologies apart; `OneInstancePerDomain` states the one
    instance per domain the registry guarantees.
  - *A retry of a failed registration.* The test's own `ProviderRegistration.retryFailedInitialization` of a provider
    the library already shut down (a failed build's decision), raced against a swap's reconcile. It is no step of the
    library's: a new `initialize()` cycle of an ended registration, outside any binding, which only the lifecycle
    model's reconcile tells apart.
  - *A registry's retry after a failure's decision closed its build*, and *a failure's decision interrupted by the
    registry's close*: `trace-map.md`.

## Trace validation

The runs of `ProviderOwnershipShutdownSpec` and `FeatureFlagRegistrySpec` (core), `RegistryFaultSpec` and
`BoundDomainSpec` (testkit) are checked against the model: every instance on the API records its probe points into one
sequence, tagged with the instance (`RegistryRecording`, `TraceProbe.forInstance`), and the test records its own steps
on the API. `trace_labels.py` turns a run into the model's actions (`trace-map.md` maps each to the code and its probe
point), and TLC searches `RegistryTrace.tla` for a behaviour of the model that takes them, deciding each failure as the
run did, and ending with as many shutdowns of each provider as the run counted. The model's constants are the run's:
its instances, domains, providers and swaps.

```sh
ZOF_TRACE=1 ZOF_TRACE_DIR=$PWD/traces sbt "core/testOnly zio.openfeature.ProviderOwnershipShutdownSpec \
  zio.openfeature.FeatureFlagRegistrySpec" "testkit/testOnly zio.openfeature.testkit.RegistryFaultSpec \
  zio.openfeature.testkit.BoundDomainSpec"
python3 validate.py tla2tools.jar CommunityModules-deps.jar traces/registry --reached
python3 validate.py tla2tools.jar CommunityModules-deps.jar --selftest traces
python3 preconditions.py tla2tools.jar CommunityModules-deps.jar
```

It needs TLC 1.8 and the community modules' `Json`, as `formal/lifecycle`'s check does. `--reached` fails when an action
other than `Lookup` (unprobed: the memoized `getClient`) is taken by no accepted run. The selftest requires each fixture
under `traces/accept` accepted and each under `traces/reject` rejected, and `preconditions.py` that deleting each guard
the check rests on lets its reject fixture through. A run the model does not cover is skipped, and `validate.py` counts
the skips per spec and reason (`trace-map.md` lists them). CI runs all of it in the `registry-traces` job of
`formal.yml`.

## Generated behaviours

`Generate.tla` walks the model at random for a replay through the real code, the other direction of the trace
check: each step labelled by the ghost `act`, every state written as one line of ndjson. It restricts `Next` to what
one replay can drive, so every behaviour it writes is still a behaviour of the model:

- **One topology per behaviour.** `registry` is one `FeatureFlagRegistry`: its API is private, so no instance owns it,
  no user binds a provider and there is no owner close; its default slot is built from the provider the registry was
  constructed with. `shared` is instances built on one shared API: no promise map, so no `Lookup`, `BuildFail`,
  `Evict`, `ReleaseReplaced` or `RegistryClose`. `FailMark`, a failure under a shared API's build, and the
  `FailDecide` after it, are the shared topology's.
- **One `initialize()` per provider at a time.** No build or swap binds a provider whose call is in flight, and only a
  call the instance started can fail under it. A binding starts a call when no slot holds the provider, which leaves
  the SDK no registration of it to reuse, and for a registry retry on the slot that holds its own failed provider
  (`StartsCall`); a binding of one another slot holds reuses that registration, whatever its state. `ready` is a
  registration a slot still holds whose `initialize()` returned. No build starts a call on a provider a swap in flight
  binds either: the code's swap ended on the registration it found. The ghosts `inFlight` and `ready` track this.
- **Any provider binds again**, whatever ended its last registration: the library resets the stale attachment of its
  wrapper as it binds it (`ProviderStateAccess.bindReclaiming`). What `BindOk` still leaves out is where the model
  abstracts the SDK: a provider another slot holds whose shutdown the library's wrapper absorbed (that registration is
  reused, which `registered` does not follow); a build onto a registration another slot holds that is not ready (the
  build reads its ERROR); and anything (a build, a swap, the user, or a second swap off it) binding a provider whose
  shutdown the SDK has queued, one a swap in flight, a timed-out swap or a retry's binding replaced: the SDK decides
  that shutdown at the binding, where the model decides when the swap or the late call ends (#1014). For the same
  absorption, a failure's decision that may shut a provider down waits while a swap in flight would roll back to it.
- **Timed-out swaps** (`AllowTimeouts` is true): the replay fires the swap's deadline through `ScriptedInitDeadline`,
  and settles the late `initialize()` when the behaviour takes `InitSettle`.
- **Steps the code takes in one go stay adjacent** (the ghost `then`). A failed build is evicted next, and a release
  of the provider a retry replaced is followed by that retry's failed build: there is no probe point between them for
  a replay to stop at. A swap whose provider another slot shares, already initialized, is followed by the SDK's
  shutdown of the provider it replaced (`SwapOldShut`), which the SDK runs in the binding; a registry retry on a
  provider already ready is completed at once, the SDK shutting the provider it replaced down in the binding. A
  `Check` is likewise followed by its `Create`, and is taken only when `Create`'s guards admit
  a provider for that slot. Otherwise the semaphore would stay held for good, with nothing left to complete, fail or
  close: the registry builds its default slot from one provider, which a swap may have mid-`initialize()`. The
  invariants `ThenEnabled` (the step held to can be taken) and `LockProgresses` (a held semaphore is released by the
  next step) check this as TLC walks.
- **Closes come last**, from level `CloseFrom`. Then `Done` repeats, so every behaviour has `Depth` states.

The ghost `total` counts each provider's shutdowns over all its registrations, with `RegistryTrace.tla`'s `Counted`
rule (`shuts` restarts when a provider registers again), for the replay to compare with the code's own count.

```sh
python3 generate.py tla2tools.jar CommunityModules-deps.jar behaviours/registry --topology registry --num 2000 --depth 30
python3 generate.py tla2tools.jar CommunityModules-deps.jar behaviours/shared --topology shared --num 2000 --depth 30
python3 -m unittest test_generate
```

`generate.py` (the driver is `formal/generate.py`) fails when a behaviour is missing or short, when an action of
`Next` has no label in `Generate.tla`, or when an action is never generated, except those its topology leaves out by
design (`by_design`), each only while its reason still holds in `Generate.tla`, `Generate.cfg.in` and `Registry.tla`.
It then checks the shape of what TLC wrote, from the model's own variables rather than the ghosts that drive the
restrictions (`check_shape`): `total` counts every shutdown, each adjacent pair above is adjacent, no `Lookup`
repeats, the registry's default slot is built from its one provider, and no action the topology leaves out occurs. CI generates
2000 behaviours per topology from a fixed seed on every run, and 20000 from a rotating seed nightly, in the `traces`
job of `formal.yml`.

## Replay

`core/src/test/scala/zio/openfeature/RegistryReplaySpec.scala` drives each generated behaviour through the real
`FeatureFlagRegistry`, `FeatureFlagsLive` and SDK, and compares the code's state with the model's at every settled
state (no swap, decision or semaphore in flight, no step held to). Each behaviour gets fresh scripted providers: every
`initialize()` waits for the replay to settle it, ok or failing, and every `shutdown()` is counted. Every split the
model makes inside one code path is a hold at an existing probe point (`RegistryInsert`, `FailedRegistration`), so no
production probe point was added. A swap's deadline is fired through the injected `InitDeadline`
(`ScriptedInitDeadline`), which the registry and every build take; production passes the default JDK timer.
Read-only seams give it the state it compares: `FeatureFlagsLive.replayView` and `replayOrphans`, the registry's
`replayView`, and `ApiInstances.leftBehindRunning`. [`replay-map.md`](replay-map.md) maps each action to the step that drives it, and
lists the variables compared and skipped.

```sh
ZOF_REGISTRY_BEHAVIOURS=behaviours/registry,behaviours/shared sbt "core/testOnly zio.openfeature.RegistryReplaySpec"
```

A behaviour's topology is the nearest path segment naming it (`registry` or `shared`). Without behaviours the
generated suite is ignored, unless `ZOF_REGISTRY_BEHAVIOURS_REQUIRED` is set, as CI does. `ZOF_REGISTRY_MISMATCHES=<file>`
writes every mismatch, where the failure shows ten. `ZOF_REGISTRY_BEHAVIOURS_MAX` replays at most that many per
location. The replay runs about 8 behaviours a second, so CI replays all 2000 per topology on a pull request (about nine
minutes), and 5000 of the nightly 20000. The replay's own checks run on every build: a behaviour per
topology (`core/src/test/resources/registry-replay`) replays clean, an unknown action fails, and a planted bug per
compared field makes a behaviour fail.

### Targets and code mutants

Random walks reach the three leaks TLC found only by luck. `Targets.tla` states each as "this step never happens" on
`GSpec`. `targets.py` writes TLC's counterexample to each, a labelled behaviour of a few states, to
[`mbt-regressions/<topology>/`](mbt-regressions/README.md), which every replay reads after the generated set.
`codemutants.py` undoes each leak's fix in core and requires the replay to turn red, among all the behaviours and among
the targets alone. It runs on push and on demand: each mutant is a recompile and a replay of the targets and 150
generated behaviours per location, since a mismatch waits out the replay's deadline and a mutant mismatches up to a
quarter of the set.

```sh
python3 targets.py tla2tools.jar CommunityModules-deps.jar mbt-regressions
python3 codemutants.py behaviours/registry,behaviours/shared,formal/registry/mbt-regressions/registry,formal/registry/mbt-regressions/shared
```

A counterexample from checking `Registry.tla` has no `act` labels to replay. Its shape becomes a statement in
`Targets.tla` instead, and `targets.py` fails by name when `GSpec` cannot reach it
([`mbt-regressions/README.md`](mbt-regressions/README.md)).

### Outside the replay

- **Shared `initialize()` calls:** a build joining a swap's call in flight, or a failure on a provider already ready
  (the shapes of #728/#800). Trace validation and `SharedApiSwapRaceSpec` cover them.
- **`FailMark` on an open instance** (an async build's init deadline): the model does not track whether a provider ever
  became ready, so a deadline may fire on any open instance there.
- **The SDK's queued shutdown** of a provider bound again before the swap that queued it ends (#1014), and the
  registrations a library-absorbed wrapper leaves for the SDK to reuse, above: the model's `registered` does not
  follow either.

## When its code changes

`code-paths` lists the code this model describes. A pull request that changes one of those files without changing this
directory states why in its description, with a line `formal/registry: unaffected because <reason>`; the `Formal model`
workflow's `model-or-reason` job checks it.
