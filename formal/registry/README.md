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

`Registry.cfg` has two instances, two domains (the default slot and one more), two providers and two swaps: 13548
distinct states, under a second. CI runs it, and the mutants, in the `tlc` job of `formal.yml` on every pull request
that touches the model or the code it maps. `-deadlock` turns off TLC's deadlock check, as for the lifecycle model: a
registry whose clients have all closed, or an API torn down, has no action enabled and is not an error.

## Composition with the lifecycle model

The model is coarse on purpose. Each instance's swap protocol (the gate, the outcome, the rollback, the orphans, the
bridge) is abstracted to a phase, `absent`, `checking`, `building`, `open` or `closed`, and a swap to its steps
(`SwapStart`, then `SwapDone`, or `SwapFailMark` and `SwapFail`). That protocol is checked for one instance by `formal/lifecycle`, whose
per-instance invariants hold for each instance here by that check; this model adds what one instance cannot show: the
API's single owner, the SDK's one state manager per provider across slots, and the registry's one promise per domain.
The two models compose by assumption, not by TLC: nothing here re-checks a swap's internals, and nothing there has a
second instance.

## Actions and the code

| Action | Code |
|:--|:--|
| `Lookup(i, d)` | `FeatureFlagRegistryLive.memoizedClient`: the promise for `d` is in `clients` (or `defaultRef`), so the caller awaits it. The registry keeps a client's promise until the registry closes, so a client shut down on its own is what later callers get |
| `Check(i, d)` | `memoizedClient`: `lock.withPermit`, no promise for `d`: insert one. The semaphore is held from the lookup to the insert, which is what makes two callers of one domain one build |
| `Create(i, p, own)` | the build, outside the semaphore: `buildClient` picks the domain's provider (`providers(d)`, or the default provider), `ProviderRegistration.register` binds it to the slot. The SDK reuses the state manager of a provider it has registered (`ProviderRepository.isStateManagerRegistered`), so a second domain holds the same wrapper, still `Bound.Managed` and guarded by `ApiInstances.heldByAnother`; only a provider the user registered, in the target or default slot, is `Bound.Foreign` (`Bound.of`, `ProviderStateAccess.boundIn`). `own`: `FeatureFlagsLive.ownsApi`, true for the sole-owner factories, false for a registry client (`addShutdownFinalizer = false`) and for a layer on a shared API |
| `Complete(i)` | `runBuild`: `lock.withPermit(... promise.succeed(client))` |
| `SwapStart(i, p)` | `FeatureFlagsLive.setProvider`, or `FeatureFlagRegistry.setProvider(d, p)` on the domain's client; one swap at a time here, as each instance's swap lock and the registry's one client per domain make concurrent swaps independent. The SDK binds `p` before its `initialize()` runs, so a sibling built on `p` from here shares the attempt, and a swap admitted just before a sibling registers (`LifecycleProbe.Point.SwapBind`) does too |
| `SwapDone(i)` | the swap succeeded. The SDK's `shutDownOld` shuts the replaced provider down only when no slot holds its state manager any more; one another slot still holds keeps it |
| `SwapFailMark(i)` | `runSwap`, the failure branch, setting `failedRegistration`: the instance no longer counts as using `p` (a timed-out swap is not modelled here and is not marked) |
| `SwapFail(i)` | the swap's rollback: the slot gets its provider back. With no other slot on `p` the SDK shuts it down (`formal/lifecycle` has which of the two does); with one, the SDK skips it and `rollBack`, through `ApiInstances.boundElsewhere`, decides as a failed build does. A rollback that fails too (`cleanupUnboundFailedProvider`) makes the same decision and is not a separate action |
| `UserBind(d, p)` | the user's own `api.setProvider`, outside the library: an instance built on that provider later holds it as `Bound.Foreign` |
| `FailMark(i)` | the start of `FeatureFlagsLive.shutDownFailedRegistration`, setting `failedRegistration`: from a build's `failedBuild` (`FeatureFlagsFactories`), after a wait that timed out or a provider that failed, or an async build's init deadline (`escalateInitTimeout`), where the instance stays open, `Fatal`. That the provider never became ready is the lifecycle model's `everReady`, so a deadline may fire on any open instance. An owner's failed build goes through `OwnerClose`, on its scope close |
| `FailDecide(i)` | the rest of it: `LibraryShuts` is `Bound.Managed` and not `ApiInstances.heldByAnother`, whose `serves` skips an instance that failed on the provider. A build's failure then closes its scope; a retry is another instance here |
| `CloseInstance(i)` | a non-owner's `shutdown` or scope close: `FeatureFlagsLive.teardown(tearDownApi = false)`, its own state only (the long comment there) |
| `OwnerClose` | the owner's `shutdown` or scope close: `ApiInstances.retireSiblings` (every other instance on the API retires, `FeatureFlagsLive.retire`), then `ProviderRegistration.boundedApiShutdown`, the SDK's `shutdown`, which shuts every registered provider down once. For the registry it is its layer's finalizer: `ApiInstances.retireAll(api) *> boundedApiShutdown(api)`, with the registry in the owner's role |

## The variables and the code

| Variable | Code |
|:--|:--|
| `phase[i]` | whether `i`'s promise is in the registry's map and whether its build returned; `closed` is `FeatureFlagsLive.teardown` having run |
| `domainOf[i]` | the instance's `domain`; the default slot's client, whose `domain` is `None` in the code, has `Default` here |
| `owner` | the instance with `ownsApi`; `None` once it tore the API down, or when none owns it (the registry: see `OwnerClose`) |
| `apiOpen` | the API has not been shut down |
| `lock` | the registry's `Semaphore(1)`: who holds it |
| `swapping` | the instance whose `setProvider` is in flight |
| `bound[d]` | the SDK slot: `api.getProvider(d)` |
| `registered` | the providers with a state manager: `ProviderRepository.isStateManagerRegistered`. One exception: a wrapper a failure shut down leaves it, though the SDK keeps its manager in the slot, because the wrapper absorbs the SDK's later `shutdown()`. So here it means a registration with a shutdown still to come, and `ForeignNeverShutByLibrary` says nothing about these paths: the two invariants below do |
| `shuts[p]`, `by[p]` | ghosts: how many times `p`'s `shutdown()` ran in its current registration, and whether the SDK or the library ran it last. A provider registered again after a shutdown is a new registration, and its count starts over, as `shuts` does at `SdkBind` in the lifecycle model |
| `everBound` | ghost: the providers some slot held at some point |
| `userOwned` | the registered providers the user bound outside the library: the instance's `Bound.Foreign` |
| `failedOn[i]` | `FeatureFlagsLive.failedRegistration`: the provider a failure under `i` marked, until a swap starts |
| `deciding` | ghost: the instances between marking a failure and deciding about the shutdown |
| `prior[i]` | the provider `i`'s swap in flight replaced: what `runSwap` keeps for the rollback |
| `swappedOff` | ghost: the providers a successful swap moved off a slot |

## What it checks

| Invariant | Claim |
|:--|:--|
| `OneOwner` | at most one instance owns the API, and the owner is checking, building or open until it tears the API down |
| `SharedShutDownOnce` | no registration is shut down twice, and once the API is gone every provider that was bound was shut down exactly once |
| `RegisteredNotShutDown` | while the API is open, a provider with a state manager has not been shut down: its state manager is the API's, and only the API's shutdown, or the SDK's `shutDownOld` when the last slot lets go of it, shuts it down |
| `ForeignNeverShutByLibrary` | a shutdown the library ran never left a provider registered: a failure's shutdown, a rollback's included, ends the wrapper's registration |
| `UserProviderNeverShutByLibrary` | no failure path shuts down a provider the user registered and the API still holds |
| `LiveSiblingKeepsProvider` | a failure under one instance never shuts down a wrapper a live sibling that has not failed on it is bound to |
| `FailedWrapperNotAbandoned` | failures do not each defer to the other: a wrapper every holder has failed on and decided about has been shut down, unless a swap may still return to it or a successful one moved off it, where the slot keeps it for the API's owner |
| `OneInstancePerDomain` | at most one promise per domain, ever: concurrent `getClient` calls on one domain yield one client |
| `ClosedNonOwnerKeepsSlot` | the slot of a closed non-owner keeps its provider until the owner closes the API |
| `SiblingsUnaffected` (an action property) | a non-owner's close changes no slot and no sibling's phase |

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

## What is outside the model

- **The per-instance swap protocol**: `formal/lifecycle`. A swap here is start and outcome; its gate, its timeout, its
  rollback's own registration and the orphans are there.
- **A retry of a failed build.** The registry evicts the promise and the caller retries; here a failed instance stays
  closed and its domain is not built again.
- **A timed-out swap's orphans.** They are the lifecycle model's, for one instance owning its API. A foreign previous
  provider the default slot still holds is left alone (`OrphanedProviders`); one no slot holds is shut down, as nothing
  else would.
- **A provider the user bound under a domain other than the build's and the default.** The library cannot see that slot,
  so it wraps the provider and initializes it again (`Bound.of`, `docs/providers.md`), and a failure shuts it down.
  `Create` and `SwapStart` exclude it.
- **The registry's provider map.** `setProvider(d, p)` on a domain without a client records `p` for the build to pick
  up; the model lets `Create` choose any provider, which covers the map and the default fallback alike.
- **`fromAcquireAsync`.** Its switch is not an SDK registration (`docs/design/lifecycle.md`); on a shared API it is an
  instance like any other here.
- **Time**, as in the lifecycle model.
- **Trace validation.** The registry specs would need a recorder per instance; the lifecycle model's checker reads one
  instance's probe points. A follow-up.
