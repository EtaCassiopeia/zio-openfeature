# Event lane model

`Lane.tla` is a PlusCal model of `OrderedEmitter` (`core/.../internal/OrderedEmitter.scala`), the single-thread lane
that `ManagedProvider`, `CachingProvider` and `CircuitBreakerProvider` re-emit their events through. Its environment is
the OpenFeature Java SDK's (1.23.0) emitter pools and API lock. It checks the lane's two claims: the slot's state is
written in the order the wrapper received the events, and the lane cannot deadlock with the SDK's lock.

```sh
curl -sSLo tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
java -cp tla2tools.jar pcal.trans -nocfg Lane.tla   # after editing the algorithm
java -cp tla2tools.jar tlc2.TLC -workers auto -config Lane.cfg Lane.tla
python3 witness.py tla2tools.jar
python3 mutants.py tla2tools.jar
```

The PlusCal algorithm is in the comment at the top of `Lane.tla`, and its translation, between `BEGIN TRANSLATION`
and `END TRANSLATION`, is checked in. After editing the algorithm, run `pcal.trans` and commit both. TLC's deadlock
check stays **on**, unlike in the lifecycle model: a state where some thread waits forever is an error here.

## Processes and the code

| Process | Code |
|:--|:--|
| `Delegate` | the wrapped provider's `emit`. With `DelegateAwaits` it awaits each emission before the next |
| `Forwarder` | the callback `EventProviderAccess.attach` registers on the delegate. The delegate's own emitter pool runs it, for any emission it holds, in any order. `OrderedEmitter.enqueue` submits to the lane's executor and returns. Once the lane is closed it is a no-op |
| `Lane` | `OrderedEmitter`'s single daemon thread: `emitAndAwait`, which is `owner.emit(event, details).await()`, one emission at a time |
| `Emitter` (one per emission) | the task `EventProvider.emit` submits to the wrapper's cached `emitterExecutor` (`EventProvider.java:109`). It takes the attachment's read lock, which is the API's lock (`:112`), writes the slot state (`onEmit`), submits the handlers (`runHandlersForProvider`, `OpenFeatureAPI.java:524`), releases the lock and calls `awaitable.wakeup()` (`:120`) |
| `Writer` | the API's write-lock holders: `setProvider` for its bind (`OpenFeatureAPI.java:222`, `:240`; `initialize()` runs on the SDK's task executor, outside the lock), and handler registration (`on`, `removeHandler`, `addHandler`, `:476-:501`) |
| `Writer` `"setProviderAndWait"` | only with `LibraryCallsSetProviderAndWait`; the library never calls it. `setProviderAndWait` holds the write lock across `initialize()` (`:262`, `:284`), which with `InitAwaitsEmission` emits and awaits that emission |
| `Writer` `"setProviderAndWait"`, then | its `shutDownOld`, still under the write lock: the replaced wrapper's `shutdown()` closes its lane |
| `Shutdown` | `OpenFeatureAPI.shutdown`: the prepare step under the write lock (`:418`), then, outside it, the providers' `shutdown()` (`:425`, "to avoid deadlock"), which calls `OrderedEmitter.close()`. That drains the lane, or cuts off what is still queued once its bound (3 s, `EventSupport.SHUTDOWN_TIMEOUT_SECONDS`) has elapsed |

The API lock is the SDK's `AutoCloseableReentrantReadWriteLock`, a non-fair `ReentrantReadWriteLock`. A reader waits
while a writer holds it, and also while a writer is queued (`CanRead`). That second rule is what makes a lane that
held a lock while waiting deadlock.

## What it checks

`Lane.cfg`: three delegate emissions, a delegate that does not await, the library's lock use.

| Property | Kind | Meaning |
|:--|:--|:--|
| `SlotOrderFollowsReceipt` | invariant | The slot is written in the order the forwarder received the emissions. |
| `ForwardNeverBlocks` | invariant | Whenever the delegate's pool holds an emission, the forwarder can take it. |
| `NoDrop` | invariant | No emission the lane accepted is cut off by its close. Emissions enqueued once the lane has closed are no-ops by design, and are kept apart (`late`). This rests on the time abstraction: the bound elapses only when `Stalled == pc["lane"] = "LAwait" /\ pc[laneCur] = "ERead" /\ ~CanRead /\ writer = "setProviderAndWait" /\ pc[writer] \in {"WInit", "WClose"}`, that is, the lane's emission waits for the lock and the lock's holder is itself waiting. |
| deadlock | TLC's check | Every state either has a step or has every thread finished. |
| `EmissionsLand` | under fairness | Every emission the forwarder received is written, or arrived once the lane had closed. |
| `WritersProceed` | under fairness | Every writer that queues for the lock gets it. |

`witness.py` checks that the documented limits stay reachable, each with exactly the expected verdict:

| Witness | Expected |
|:--|:--|
| A delegate that does not await its own emissions (`Lane.cfg` plus `DelegateOrderKept`) | `DelegateOrderKept` violated: two of its emissions reach the slot swapped, before any lane sees them |
| The same delegate, awaiting them (control) | no error |
| `Witness.cfg`: `setProviderAndWait`, whose `shutDownOld` closes the replaced wrapper's lane under the write lock | `NoDrop` violated: the lane's emission waits for the read lock, the close's bound elapses, and the queue is cut off |
| `Witness.cfg` with `InitAwaitsEmission`, `NoDrop` left out | a deadlock: the writer holds the write lock while its `initialize()` waits for an emission that needs the read lock |

`mutants.py` puts in each bug the lane's design excludes:

| Bug | Caught by |
|:--|:--|
| Two lane threads per wrapper | `SlotOrderFollowsReceipt` |
| The lane holds the write lock while it waits | deadlock: the emitter task's read request waits for the lane, and the lane waits for the task |
| The lane holds the read lock while it waits | deadlock: a queued writer blocks the emitter task's read request under the non-fair lock, and waits for the lane's read lock |
| `shutdown` waits for the lane, without a bound, while it holds the write lock | deadlock |
| The forwarder waits for the lane to go idle | `ForwardNeverBlocks` |
| The library calls `setProviderAndWait` | `NoDrop` |

The issue expected that last mutant to deadlock. It is caught by `NoDrop` instead, because `Lane.cfg` keeps
`InitAwaitsEmission = FALSE`: `setProviderAndWait` itself never waits on an emission there. Its `shutDownOld` closes the
lane under the write lock, finds it stalled, and cuts the queue off. With `InitAwaitsEmission` it can also deadlock, as
`witness.py` checks. TLC may then report either, since a `shutdown` that closed the lane first turns the
`initialize()`'s emission into a late no-op and leaves only the cut-off.

The two "holds a lock while it waits" mutants are the negation of the class comment's claim, "the lane thread holds no
lock while it waits". The issue that added this model proposed "the lane takes the write lock", which was ill-posed.
Taking it around the submission only, and releasing it before the wait, is harmless: TLC finds no error, since nothing
waits on the lane while holding a lock and only one lane emission runs at a time. Holding either lock across the wait
deadlocks: the write lock always, the read lock only once a writer has queued.

## What is outside the model

- **Time.** `close()`'s bound elapses only while the lane is stalled: its emission waits for the lock, and the lock's
  holder is itself waiting (`Stalled`). This assumes the bound is long next to one uncontended emission. Under that
  assumption the library's lock use never cuts an emission off (`NoDrop`), and only `setProviderAndWait` does. In real
  time, a lane that is slow but not stalled, with a long queue or a slow handler submission, can still be cut off at
  3 s.
- **Handler bodies.** The SDK submits them to another pool, after the lock is released. Their contents and order are
  not modelled.
- **The delegate's pool size.** It is unbounded, one thread per emission, as the SDK's cached pools are.
- **Interrupts.** `Awaitable.await()` ignores them, which is why the lane's thread is a daemon. `close()`'s
  `shutdownNow()` cannot cut a stalled wait short, and the model has no interrupt step.
