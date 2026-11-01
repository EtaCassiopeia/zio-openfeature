# Provider lifecycle: design record

How the provider lifecycle (`setProvider`, shutdown, the scope close, provider events, the init watchdog) is built,
why, what was tried before, and where it is going. [Lifecycle and events](../lifecycle.md) says what the lifecycle
does; this page says why it is built the way it is. It is kept current: a change to the lifecycle's design updates it.

## The problem the lifecycle solves

The OpenFeature Java SDK holds the provider. It binds a new provider to a slot before calling its `initialize()`,
runs that call on its own executor, shuts the replaced provider down only once the call returns, dispatches a
provider's events on a cached pool with no order between them, and reuses an existing registration when the same
provider is bound to a second domain. `FeatureFlags` has to turn that into a status an application can rely on, a
swap that either installs the new provider or leaves the old one serving, and a shutdown after which nothing moves.
Most of it happens on threads the library does not own.

## How it got here

Each row is a change to the design, the issues that forced it, and what it taught. Fixes that did not change the
design are not listed.

| Change | Issues | What it taught |
|:--|:--|:--|
| One swap lock serializes `setProvider` calls | #102 | Two swaps at once corrupt the status; one holder at a time. |
| Register without waiting, then wait with a bound; no rollback on a timeout | #433, #434 | `setProviderAndWait` holds the SDK's write lock across `initialize()`, so a hanging provider wedges every later swap and the API shutdown. A started swap runs to its outcome even if its caller is interrupted. |
| The instance's resources have one owner: the hub closes with the scope, `awaitReady` ends on shutdown | #435 | Teardown is one uninterruptible path. |
| `fromAcquireAsync` registers a wrapper that switches its delegate, never a second SDK registration | #512 | The library can register its own object. The SDK refuses evaluations while a slot is `NOT_READY`, so a swap that goes through the SDK always has a window. |
| The event bridge attaches before the provider is registered | #531 | A `READY` dispatched during registration is otherwise missed or doubled. |
| Every SDK and provider call on a lifecycle path is bounded, enforced by a source guard | #561 | A provider that cannot shut down must not hang the scope. |
| The status follows the SDK's state for the slot, read when the event is handled, not the event's type | #563, #601, #613, #617, #634 | Events have no order; the slot does. Status is a function of the slot plus the two sticky terminals, `Fatal` and shutdown. |
| Shutdown and the scope close take the swap lock | #565, #636 | A swap that settles after the final `NotReady` moves the status out of it. |
| A timed-out swap's previous provider is tracked until the new one settles | #564 | The SDK shuts the replaced provider down only if `initialize()` returns; on a timeout nothing else will. |
| Probe points and the interleaving grid; the TLA+ model, its mutants and trace validation; enforced and assumed preconditions told apart | #576, #582, #655 | Random timing finds races slowly and cannot replay them. The model's "assumed" preconditions were exactly the entry paths not yet under the lock. |
| A provider whose `initialize()` may still run is remembered, so no path initializes it again | #619, #635, #669 | The SDK runs `initialize()` on its executor and never says when it returns. Four structures ended up answering the same question. |
| The wait listens for the SDK's own `READY` to tell "failed" from "initialized, then reported an error"; a second handler for nameless providers; a reuse heuristic | #623, #629, #630 | An `ERROR` state has two causes the slot cannot tell apart. Each heuristic stood in for one unavailable fact: whether `initialize()` returned. |
| Refusals after shutdown and after the scope close, checked under the swap lock | #631, #665, #671 | A refusal checked outside the lock, or set by only one of the owners, is a revival. |
| Evaluations carry a swap generation and distrust an answer made across a swap | #658 | The SDK resolves the slot's provider per call, so an evaluation can be answered by a provider the swap has already rejected. |
| An API has one owner, which retires every instance on it | #652 | Layers sharing the global API clobbered each other's slot. |
| **Managed registrations and one lifecycle state** (in progress, #696) | #696, #697, #698, #699 | See below. |

The pattern in the second half of the table: each fix added one more fact and one more check at the point where the
previous fix stopped. By the end, "is this provider still initializing" had four answers, the registration wait had
four heuristics, and the refusal chain had four flags set by three owners. A catalogue of the 31 lifecycle issues from
#433 to #693 sorts 11 as misreadings of the SDK's state or events, 5 as orderings among the library's own actions, 4 as
missing refusals, 3 as bookkeeping leaks, 1 as an evaluation race and 7 as other. Three properties would have covered
24 of them: the status as a pure function of the SDK's slot state plus the two sticky terminals; one tracked record per
registered provider from bind to its single shutdown; one owner for every lifecycle action, including evaluation
versioning.

## The design

The library never learned directly whether a provider's `initialize()` returned, whether the SDK called its
`shutdown()`, or in what order it emitted its events. The SDK calls `initialize(ctx, domain)` and `shutdown()` on
whatever object is registered, and `EventProvider.emit` returns an `Awaitable`; so the library registers its own
object and observes all three.

**`ManagedProvider`** is what the library registers with the SDK: one per user provider, delegating every
`FeatureProvider` member. It counts `initialize()` calls in flight per binding and completes a promise when the call
returns or throws, with the real exception; it delegates `shutdown()` once per initialization cycle, so a provider is
shut down at most once by construction; and it forwards its delegate's events through one ordered lane, a thread that
emits and awaits each event in turn, so the slot's state writes follow the order in which the wrapper received them. The SDK's own `READY` and `ERROR` for an initialization still go
through its pool, but nothing depends on them.

**`Registration`** binds and then awaits that promise, bounded by `initTimeout`. One read of the manager's state right
after the bind tells a fresh initialization from one already running for the same binding and from a reused
registration that ran none. The `READY` watch, the error observer, the slot poll and the grace windows are gone, and no
blocking-pool thread is parked per swap. A timed-out swap's previous provider is settled by the same promise.

A provider the user already registered with the API, in the target slot or the default one, is the exception: wrapping
it would run its `initialize()` again, or fail on an `EventProvider` the SDK holds. The library binds that registration
as it is (`Bound.Foreign`), settles on the state the SDK reports for it, and gives it none of the wrapper's guarantees.

**`LifecycleState`** is one record, field for field the model's library variables (status, installed provider, lock,
gate, swap in flight, the registrations still initializing with what each replaced, orphans, shut down, closed), plus
ever-readiness, a deferred init deadline and the generation; and **`step`** is one pure function from a state and an
input to the next state, the status it moved to and the effects to run. Every refusal is decided in `step`, so no entry
path can skip it. The swap lock stays as the serializer of the multi-step sequences.

The status lives in the status ref, which the testkit may share and set directly; the rest of the record lives beside
it, and every step runs under the status ref's `modifyZIO`, whose semaphore makes both writes one step. The mirrors read
synchronously from SDK threads and the evaluator (the gate, the generation, the provider name) are written in the same
step; the effects run after it, never under the semaphore.

What stays: the probe points, the interleaving grid, the model and trace validation, which now check observed `inits`,
`ran` and `shuts` rather than assumed ones; the evaluation path and its generation check; the two-tier status rule.

### Options weighed

| Option | What it fixes | Why not |
|:--|:--|:--|
| An actor with a mailbox: one consumer fiber, commands for swap, shutdown, event, outcome | Orders the library's own actions; one owner for the state | It does not order the SDK's pool, where most misreadings came from. Evaluations stay outside the mailbox. Long work still forks and reports back, so the in-flight records remain. A priority lane reintroduces reordering. It would rewrite the harness and the code at once. |
| Poll and wake, in the style of Rust futures | Re-derives decisions from the current state on every wake-up | ZIO is already that runtime. Its rule is kept: apply the SDK's state read at handling time, not the event's type. |
| Keep the design and keep patching | Nothing structural | Each fix adds a flag or a grace window; the grace windows cap latency and stay flaky under load. |

The actor idea is kept where it is sound: an ordered inbox per provider at the one boundary where order was lost, the
provider's own emissions.

### Decisions and known limits

- `api.getProvider()` on an API the library owns returns the wrapper. `getMetadata` delegates, so names are unchanged.
  `fromAcquireAsync` already behaved this way.
- A provider already attached to another API fails at build time, not at registration.
- The lane orders the slot's state writes from the wrapper down. Two things it cannot order: two emissions a
  provider issues back to back without awaiting, which the SDK's cached emitter pool can swap before any wrapper
  sees them (a provider that needs order awaits its own emissions), and the handler bodies, which the SDK runs on
  its executor, so the bridge keeps reading the slot when a handler runs and the order in which `events` publishes
  them is as documented. The extras wrappers get the same lane (#700).
- A hanging `initialize()` is still not interruptible; the timeout semantics are unchanged.
- Two equal but distinct provider instances on one API are two registrations.

## Status

| Phase | Issue | State |
|:--|:--|:--|
| 1. `ManagedProvider` and the promise-based `Registration`; event-driven orphans | #697 | done |
| 2. `LifecycleState` and `step` | #698 | done |
| 3. `SwitchableProvider` merged into `ManagedProvider`; one wrapper per registry provider | #699 | planned |

## Changing the lifecycle

A change to the lifecycle code goes with the model: a pull request that touches it either changes
`formal/lifecycle/Lifecycle.tla` or says why not (`formal: unaffected because ...`). A new step gets a probe point
and a row in the interleaving grid's reach table; a fixed bug gets a mutant. A change to the design gets a row in the
table above. [Development](../development.md) has the commands.
