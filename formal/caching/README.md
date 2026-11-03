# Cache model

`Caching.tla` is a PlusCal model of `CachingProvider` (`extras/.../CachingProvider.scala`) over zio-cache 0.2.8, for one
flag key and one context. It checks that an invalidation holds against every order of lookup start, write-back, epoch
bump and joiner, that a failed or `DEFAULT` result is not kept, and that a caller is not handed another lookup's failure.
It is also a small model to learn from: about 80 lines of PlusCal, with the rest of the file its translation.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar pcal.trans -nocfg Caching.tla   # after editing the algorithm
java -cp tla2tools.jar tlc2.TLC -workers auto -config Caching.cfg Caching.tla
python3 witness.py tla2tools.jar
python3 mutants.py tla2tools.jar
```

The PlusCal algorithm is in the comment at the top of `Caching.tla`, and its translation, between `BEGIN TRANSLATION` and
`END TRANSLATION`, is checked in. After editing the algorithm, run `pcal.trans` and commit both. TLC's deadlock check stays
**on**: every process ends, so a state with no step that is not the end is an error.

## Actions and the code

| Action | Code |
|:--|:--|
| `Change` | the delegate changes its configuration, then emits `PROVIDER_CONFIGURATION_CHANGED`, which queues the event |
| `IQueued` | the delegate's pool runs `onDelegateEvent` for that event, in any order, and handlers may overlap |
| `IBump` | `generation.incrementAndGet()` in `invalidateAll` |
| `IClear` | `cache.invalidateAll`, zio-cache's `map.clear()`. The ghost `invalidatedAt` records that the invalidation completed |
| `CStart` | `generation.get()`, when `cached` builds its `CacheKey` |
| `CGet` | zio-cache's `getUnsafe`: a hit takes the entry, an absent key gets a `Pending`, a `Pending` is joined |
| `CEval` | the `Lookup`'s `ck.evaluate()`: the thunk flips `ran` and calls the delegate |
| `CWrite` | zio-cache's `lookupValueOf`: the unconditional `map.put`, then `promise.done` |
| `CAwait` | a joiner's `promise.await` |
| `CTail` | `onError` and `tap` in `cached`, which `cache.invalidate(ck)`, then the check for a failure with `ran` still false. A caller not retrying returns here (`Done`) |
| `CRetry` | `cache.invalidate(ck)` before the retry |
| `CRetryEval` | `Exit.attempt(ck.evaluate())`: the delegate, called directly |

`CWrite` joins `map.put` and `promise.done` into one step. No behaviour is lost: a joiner's wake-up is its own step
(`CAwait`), which may come arbitrarily later.

The issue that added this model names its variables `cache`, `inflight` and `callers`. Here `map` follows zio-cache's own
structure and holds the pending entries too, `promise` holds the finished lookups, and each caller's state is the
`Caller` process's variables.

## What it checks

`Caching.cfg`: three callers and one configuration change. Three callers are what the retry bug below needs. `witness.py`
adds two overlapping changes, with two callers.

| Property | Kind | Meaning |
|:--|:--|:--|
| `NoStaleAfterInvalidate` | invariant | A caller that started once an invalidation had completed never returns a value older than the version that invalidation covers. |
| `NoCachedFailure` | invariant | A failed or `DEFAULT` result is in the map only until the next step of the caller whose lookup wrote it, which removes it. |
| `JoinersGetARun` | invariant | A caller that returns a failure ran the delegate itself. |
| deadlock | TLC's check | No caller waits forever. |

`witness.py` checks that the documented limits stay reachable, each with exactly the expected verdict:

| Witness | Expected |
|:--|:--|
| `InFlightNeverStale` | violated. A caller already in flight across an invalidation may return its own lookup's older value. This is allowed: it overlapped the invalidation |
| `MapNeverHoldsBad` | violated. zio-cache writes a result back before the caller removes it, so another caller can hit the entry in that window. A hit on a failure is retried. A hit on a `DEFAULT` result is the class doc's concurrent-window caveat |
| `NoStrandedWrite` | violated. The epoch does not stop a lookup in flight from writing: it strands the write under a key no later read uses, until TTL or eviction drops it |
| `NoJoinerRetries` | violated. The retry is reachable, so `JoinersGetARun` is not vacuous |
| Clear-then-bump, with the invalidation complete after both steps | no error: see below |
| Two callers and two changes, with overlapping handlers | no error |

`mutants.py` puts in each bug the design excludes:

| Bug | Caught by |
|:--|:--|
| The epoch is dropped from the key | `NoStaleAfterInvalidate`: the documented race |
| A failure is not invalidated | `NoCachedFailure` |
| A `DEFAULT` result is not invalidated | `NoCachedFailure` |
| A joiner is not retried | `JoinersGetARun` |
| The retry goes back through the shared cache | `JoinersGetARun` |
| Clear-then-bump, for a caller that starts after the clear | `NoStaleAfterInvalidate` |

## Where this differs from the issue that added it

**The retry was a real bug, found by this model.** The retry was `run(cache.invalidate(ck) *> lookup)`, which goes back
through the shared cache. With three callers, a caller's `lookup` could hit or join another caller's failing lookup
between the two steps, and return that failure without having called the delegate. Against the real class, with a delegate
that always throws, about 1 to 3% of the throws came from a thread that never called it. The retry now calls the delegate
directly, and `CachingJoinedLookupSpec` has the regression test. The old code stays as a mutant.

**`NoCachedFailure` is not "the map never holds a failed or `DEFAULT` result".** That is false of the code: zio-cache's
write-back comes first, and `cached` removes the entry afterwards. The invariant states the strongest form that holds, that
the entry lasts only until the writer's next step, so no caller leaves one behind. `MapNeverHoldsBad` keeps the literal
form as a witness.

**`JoinersGetARun` is stronger than the issue's parenthesis.** The issue glosses it as "no joiner returns another lookup's
failure as its own success", which nothing could violate here. The invariant is its main clause: a joiner of a failed
lookup gets its own run.

**The order of the invalidation's two steps does not matter to `NoStaleAfterInvalidate`.** The issue expected to find one
order wrong. Once both steps have run, the epoch alone protects a new caller, whichever came first: the old epoch's keys
are unreachable. The clear is what frees their memory. The order matters to a sharper claim, the code comment's, about a
caller that starts once the map is cleared. With clear-then-bump that caller still reads the old epoch, and a lookup in
flight can write under it first, so it can return a value older than the clear. With bump-then-clear it reads the new
epoch. So the mutant counts the invalidation from its clear, and the witness with both steps is the control.

**"Under 100 lines" applies to the PlusCal.** The checked-in translation takes the file past it.

## What is outside the model

- **TTL expiry.** Time is not modelled. An expired entry only ever makes a later read miss.
- **The context fingerprint.** There is one flag key and one context, since the epoch is the interesting dimension.
- **zio-cache's capacity eviction.** Like expiry, it only removes entries. It can also end a stranded write's stay.
- **A retry's interrupted thread.** A caller whose own thread is interrupted does not retry and takes the failure as its
  answer. `JoinersGetARun` is about the other callers.
- **Fiber interruption of a lookup.** zio-cache's `isInterruptedOnly` path removes the entry and interrupts the promise.
  Nothing interrupts the fiber `runtime.unsafe.run` starts here. A thread interrupt reaches the lookup as an ordinary
  failure, which the model has.
- **A delegate that emits before it applies its change.** `Change` comes first. A delegate that emits early can be read
  at its old version after the invalidation, and no cache could tell.
- **`shutdown` and direct `invalidateAll` calls.** They run the same two steps as `Invalidator`, whose `covers` reads the
  version at the first step, so a call with no change is a weaker case of the one modelled.
