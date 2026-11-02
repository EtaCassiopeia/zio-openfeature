-------------------------------- MODULE Lane --------------------------------
(***************************************************************************)
(* The ordered event lane (OrderedEmitter) of a wrapping provider, and the *)
(* OpenFeature SDK's API lock (1.23.0).                                    *)
(*                                                                         *)
(* A delegate emits; its own pool hands each emission to the wrapper's     *)
(* forwarder in any order unless the delegate awaits it.  The forwarder    *)
(* enqueues on the lane and returns.  The lane runs one emission at a      *)
(* time: owner.emit, which submits a task to the wrapper's cached emitter  *)
(* pool, then await().  Each such task takes the API's read lock, writes   *)
(* the slot state and wakes the lane.  Writers take the write lock:        *)
(* setProvider for its bind, a handler registration, and shutdown's        *)
(* prepare step; shutdown then closes the lane outside the lock, waiting   *)
(* a bounded time.  setProviderAndWait holds the write lock across an      *)
(* initialize() that awaits its own emission; the library never calls it.  *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    N,                               \* how many events the delegate emits
    DelegateAwaits,                  \* the delegate awaits each emission before the next
    LibraryCallsSetProviderAndWait,  \* a writer holds the write lock across initialize() and the old provider's shutdown
    InitAwaitsEmission               \* that initialize() emits and awaits the emission

Ev(k) == "e" \o ToString(k)          \* the delegate's k-th emission
Init0 == "init"                      \* the emission an initialize() under setProviderAndWait makes and awaits
Emissions == {Ev(k) : k \in 1..N} \cup {Init0}
Writers == {"setProvider", "addHandler"} \cup (IF LibraryCallsSetProviderAndWait THEN {"setProviderAndWait"} ELSE {})

Range(s) == {s[k] : k \in DOMAIN s}

(* --algorithm Lane
variables
    issued = <<>>,         \* the delegate's emissions, in the order it issued them
    inflight = {},         \* issued, not yet handed to the forwarder by the delegate's pool
    received = <<>>,       \* the order the forwarder received them in: the lane's enqueue order
    queue = <<>>,          \* the lane's FIFO
    laneCur = Init0,       \* the emission the lane's thread is running
    open = TRUE,           \* the lane accepts emissions (OrderedEmitter's state is not Closed)
    task = [e \in Emissions |-> "none"],  \* each emission's emitter task: "none", "lock", "done"
    slotWrites = <<>>,     \* the slot state writes, in order
    late = <<>>,           \* emissions enqueued once the lane was closed: a no-op, by design
    cutOff = <<>>,         \* emissions still queued when close()'s bound elapsed (shutdownNow)
    readers = 0,           \* holders of the API's read lock
    writer = "none",       \* the holder of the write lock
    waiting = {};          \* writers queued for the write lock

define
    \* ReentrantReadWriteLock, non-fair: a reader also waits while a writer is queued first.
    CanRead == writer = "none" /\ waiting = {}
    CanWrite == writer = "none" /\ readers = 0
    \* The lane cannot progress: its emission waits for the lock, whose holder is itself waiting.  Only then does close()'s
    \* bound elapse: the bound is long next to an uncontended emission (README.md, "What is outside the model").
    Stalled == /\ pc["lane"] = "LAwait" /\ pc[laneCur] = "ERead" /\ ~CanRead
               /\ writer = "setProviderAndWait" /\ pc[writer] \in {"WInit", "WClose"}
end define;

\* The delegate emits 1..N, awaiting each one if DelegateAwaits.
fair process Delegate = "delegate"
variable i = 1;
begin
  DIssue:
    while i <= N do
      issued := Append(issued, Ev(i));
      inflight := inflight \cup {Ev(i)};
  DAwait:
      await DelegateAwaits => Ev(i) \notin inflight;
      i := i + 1;
    end while;
end process;

\* The delegate's pool runs the forwarder for any emission it holds: the forwarder enqueues on the lane and returns.  An
\* enqueue on a closed lane is a no-op.
fair process Forwarder = "forwarder"
begin
  FForward:
    while pc["delegate"] # "Done" \/ inflight # {} do
      with e \in inflight do
        inflight := inflight \ {e};
        received := Append(received, e);
        if open then queue := Append(queue, e) else late := Append(late, e) end if;
      end with;
    end while;
end process;

\* The lane's single thread: one emission at a time, owner.emit then await().  It stops once closed and drained.
fair process Lane = "lane"
begin
  LTake:
    while open \/ queue # <<>> do
      await queue # <<>>;
      laneCur := Head(queue);
      queue := Tail(queue);
  LEmit:
      task[laneCur] := "lock";
  LAwait:
      await task[laneCur] = "done";
    end while;
end process;

\* The wrapper's cached emitter pool: each emission's task takes the read lock, writes the slot state, submits the
\* handlers, releases the lock and wakes the lane.  A task never submitted ends unused once the lane has stopped.
fair process Emitter \in Emissions
begin
  EStart:
    either
      await task[self] = "lock";
    or
      await task[self] = "none" /\ pc["lane"] = "Done";
      goto Done;
    end either;
  ERead:
    await CanRead;
    readers := readers + 1;
  EWrite:
    slotWrites := Append(slotWrites, self);
    readers := readers - 1;
    task[self] := "done";
end process;

\* A writer of the API lock.  setProviderAndWait holds it across initialize(), which may emit and await the emission,
\* and across shutDownOld: the replaced wrapper's shutdown() closes its lane, waiting a bounded time.
fair process Writer \in Writers
begin
  WQueue:
    waiting := waiting \cup {self};
  WTake:
    await CanWrite;
    writer := self;
    waiting := waiting \ {self};
  WBody:
    if self = "setProviderAndWait" then
      if InitAwaitsEmission then
        received := Append(received, Init0);
        if open then queue := Append(queue, Init0) else late := Append(late, Init0) end if;
  WInit:
        await task[Init0] = "done" \/ Init0 \in Range(late) \/ Init0 \in Range(cutOff);
      end if;
  WClose:
      open := FALSE;
      either
        await queue = <<>> /\ pc["lane"] \in {"LTake", "Done"};
      or
        await Stalled;
        cutOff := cutOff \o queue;
        queue := <<>>;
      end either;
    end if;
  WRelease:
    writer := "none";
end process;

\* OpenFeatureAPI.shutdown: the prepare step under the write lock, then, outside it, the wrapper's shutdown(), which
\* closes the lane: it waits for the lane to drain, or, once its bound elapses, drops what is still queued.
fair process Shutdown = "shutdown"
begin
  SQueue:
    waiting := waiting \cup {"shutdown"};
  STake:
    await CanWrite;
    writer := "shutdown";
    waiting := waiting \ {"shutdown"};
  SRelease:
    writer := "none";
  SClose:
    open := FALSE;
  SWait:
    either
      await queue = <<>> /\ pc["lane"] \in {"LTake", "Done"};
    or
      await Stalled;
      cutOff := cutOff \o queue;
      queue := <<>>;
    end either;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
VARIABLES issued, inflight, received, queue, laneCur, open, task, slotWrites, 
          late, cutOff, readers, writer, waiting, pc

(* define statement *)
CanRead == writer = "none" /\ waiting = {}
CanWrite == writer = "none" /\ readers = 0


Stalled == /\ pc["lane"] = "LAwait" /\ pc[laneCur] = "ERead" /\ ~CanRead
           /\ writer = "setProviderAndWait" /\ pc[writer] \in {"WInit", "WClose"}

VARIABLE i

vars == << issued, inflight, received, queue, laneCur, open, task, slotWrites, 
           late, cutOff, readers, writer, waiting, pc, i >>

ProcSet == {"delegate"} \cup {"forwarder"} \cup {"lane"} \cup (Emissions) \cup (Writers) \cup {"shutdown"}

Init == (* Global variables *)
        /\ issued = <<>>
        /\ inflight = {}
        /\ received = <<>>
        /\ queue = <<>>
        /\ laneCur = Init0
        /\ open = TRUE
        /\ task = [e \in Emissions |-> "none"]
        /\ slotWrites = <<>>
        /\ late = <<>>
        /\ cutOff = <<>>
        /\ readers = 0
        /\ writer = "none"
        /\ waiting = {}
        (* Process Delegate *)
        /\ i = 1
        /\ pc = [self \in ProcSet |-> CASE self = "delegate" -> "DIssue"
                                        [] self = "forwarder" -> "FForward"
                                        [] self = "lane" -> "LTake"
                                        [] self \in Emissions -> "EStart"
                                        [] self \in Writers -> "WQueue"
                                        [] self = "shutdown" -> "SQueue"]

DIssue == /\ pc["delegate"] = "DIssue"
          /\ IF i <= N
                THEN /\ issued' = Append(issued, Ev(i))
                     /\ inflight' = (inflight \cup {Ev(i)})
                     /\ pc' = [pc EXCEPT !["delegate"] = "DAwait"]
                ELSE /\ pc' = [pc EXCEPT !["delegate"] = "Done"]
                     /\ UNCHANGED << issued, inflight >>
          /\ UNCHANGED << received, queue, laneCur, open, task, slotWrites, 
                          late, cutOff, readers, writer, waiting, i >>

DAwait == /\ pc["delegate"] = "DAwait"
          /\ DelegateAwaits => Ev(i) \notin inflight
          /\ i' = i + 1
          /\ pc' = [pc EXCEPT !["delegate"] = "DIssue"]
          /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                          task, slotWrites, late, cutOff, readers, writer, 
                          waiting >>

Delegate == DIssue \/ DAwait

FForward == /\ pc["forwarder"] = "FForward"
            /\ IF pc["delegate"] # "Done" \/ inflight # {}
                  THEN /\ \E e \in inflight:
                            /\ inflight' = inflight \ {e}
                            /\ received' = Append(received, e)
                            /\ IF open
                                  THEN /\ queue' = Append(queue, e)
                                       /\ late' = late
                                  ELSE /\ late' = Append(late, e)
                                       /\ queue' = queue
                       /\ pc' = [pc EXCEPT !["forwarder"] = "FForward"]
                  ELSE /\ pc' = [pc EXCEPT !["forwarder"] = "Done"]
                       /\ UNCHANGED << inflight, received, queue, late >>
            /\ UNCHANGED << issued, laneCur, open, task, slotWrites, cutOff, 
                            readers, writer, waiting, i >>

Forwarder == FForward

LTake == /\ pc["lane"] = "LTake"
         /\ IF open \/ queue # <<>>
               THEN /\ queue # <<>>
                    /\ laneCur' = Head(queue)
                    /\ queue' = Tail(queue)
                    /\ pc' = [pc EXCEPT !["lane"] = "LEmit"]
               ELSE /\ pc' = [pc EXCEPT !["lane"] = "Done"]
                    /\ UNCHANGED << queue, laneCur >>
         /\ UNCHANGED << issued, inflight, received, open, task, slotWrites, 
                         late, cutOff, readers, writer, waiting, i >>

LEmit == /\ pc["lane"] = "LEmit"
         /\ task' = [task EXCEPT ![laneCur] = "lock"]
         /\ pc' = [pc EXCEPT !["lane"] = "LAwait"]
         /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                         slotWrites, late, cutOff, readers, writer, waiting, i >>

LAwait == /\ pc["lane"] = "LAwait"
          /\ task[laneCur] = "done"
          /\ pc' = [pc EXCEPT !["lane"] = "LTake"]
          /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                          task, slotWrites, late, cutOff, readers, writer, 
                          waiting, i >>

Lane == LTake \/ LEmit \/ LAwait

EStart(self) == /\ pc[self] = "EStart"
                /\ \/ /\ task[self] = "lock"
                      /\ pc' = [pc EXCEPT ![self] = "ERead"]
                   \/ /\ task[self] = "none" /\ pc["lane"] = "Done"
                      /\ pc' = [pc EXCEPT ![self] = "Done"]
                /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                                open, task, slotWrites, late, cutOff, readers, 
                                writer, waiting, i >>

ERead(self) == /\ pc[self] = "ERead"
               /\ CanRead
               /\ readers' = readers + 1
               /\ pc' = [pc EXCEPT ![self] = "EWrite"]
               /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                               open, task, slotWrites, late, cutOff, writer, 
                               waiting, i >>

EWrite(self) == /\ pc[self] = "EWrite"
                /\ slotWrites' = Append(slotWrites, self)
                /\ readers' = readers - 1
                /\ task' = [task EXCEPT ![self] = "done"]
                /\ pc' = [pc EXCEPT ![self] = "Done"]
                /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                                open, late, cutOff, writer, waiting, i >>

Emitter(self) == EStart(self) \/ ERead(self) \/ EWrite(self)

WQueue(self) == /\ pc[self] = "WQueue"
                /\ waiting' = (waiting \cup {self})
                /\ pc' = [pc EXCEPT ![self] = "WTake"]
                /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                                open, task, slotWrites, late, cutOff, readers, 
                                writer, i >>

WTake(self) == /\ pc[self] = "WTake"
               /\ CanWrite
               /\ writer' = self
               /\ waiting' = waiting \ {self}
               /\ pc' = [pc EXCEPT ![self] = "WBody"]
               /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                               open, task, slotWrites, late, cutOff, readers, 
                               i >>

WBody(self) == /\ pc[self] = "WBody"
               /\ IF self = "setProviderAndWait"
                     THEN /\ IF InitAwaitsEmission
                                THEN /\ received' = Append(received, Init0)
                                     /\ IF open
                                           THEN /\ queue' = Append(queue, Init0)
                                                /\ late' = late
                                           ELSE /\ late' = Append(late, Init0)
                                                /\ queue' = queue
                                     /\ pc' = [pc EXCEPT ![self] = "WInit"]
                                ELSE /\ pc' = [pc EXCEPT ![self] = "WClose"]
                                     /\ UNCHANGED << received, queue, late >>
                     ELSE /\ pc' = [pc EXCEPT ![self] = "WRelease"]
                          /\ UNCHANGED << received, queue, late >>
               /\ UNCHANGED << issued, inflight, laneCur, open, task, 
                               slotWrites, cutOff, readers, writer, waiting, i >>

WClose(self) == /\ pc[self] = "WClose"
                /\ open' = FALSE
                /\ \/ /\ queue = <<>> /\ pc["lane"] \in {"LTake", "Done"}
                      /\ UNCHANGED <<queue, cutOff>>
                   \/ /\ Stalled
                      /\ cutOff' = cutOff \o queue
                      /\ queue' = <<>>
                /\ pc' = [pc EXCEPT ![self] = "WRelease"]
                /\ UNCHANGED << issued, inflight, received, laneCur, task, 
                                slotWrites, late, readers, writer, waiting, i >>

WInit(self) == /\ pc[self] = "WInit"
               /\ task[Init0] = "done" \/ Init0 \in Range(late) \/ Init0 \in Range(cutOff)
               /\ pc' = [pc EXCEPT ![self] = "WClose"]
               /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                               open, task, slotWrites, late, cutOff, readers, 
                               writer, waiting, i >>

WRelease(self) == /\ pc[self] = "WRelease"
                  /\ writer' = "none"
                  /\ pc' = [pc EXCEPT ![self] = "Done"]
                  /\ UNCHANGED << issued, inflight, received, queue, laneCur, 
                                  open, task, slotWrites, late, cutOff, 
                                  readers, waiting, i >>

Writer(self) == WQueue(self) \/ WTake(self) \/ WBody(self) \/ WClose(self)
                   \/ WInit(self) \/ WRelease(self)

SQueue == /\ pc["shutdown"] = "SQueue"
          /\ waiting' = (waiting \cup {"shutdown"})
          /\ pc' = [pc EXCEPT !["shutdown"] = "STake"]
          /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                          task, slotWrites, late, cutOff, readers, writer, i >>

STake == /\ pc["shutdown"] = "STake"
         /\ CanWrite
         /\ writer' = "shutdown"
         /\ waiting' = waiting \ {"shutdown"}
         /\ pc' = [pc EXCEPT !["shutdown"] = "SRelease"]
         /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                         task, slotWrites, late, cutOff, readers, i >>

SRelease == /\ pc["shutdown"] = "SRelease"
            /\ writer' = "none"
            /\ pc' = [pc EXCEPT !["shutdown"] = "SClose"]
            /\ UNCHANGED << issued, inflight, received, queue, laneCur, open, 
                            task, slotWrites, late, cutOff, readers, waiting, 
                            i >>

SClose == /\ pc["shutdown"] = "SClose"
          /\ open' = FALSE
          /\ pc' = [pc EXCEPT !["shutdown"] = "SWait"]
          /\ UNCHANGED << issued, inflight, received, queue, laneCur, task, 
                          slotWrites, late, cutOff, readers, writer, waiting, 
                          i >>

SWait == /\ pc["shutdown"] = "SWait"
         /\ \/ /\ queue = <<>> /\ pc["lane"] \in {"LTake", "Done"}
               /\ UNCHANGED <<queue, cutOff>>
            \/ /\ Stalled
               /\ cutOff' = cutOff \o queue
               /\ queue' = <<>>
         /\ pc' = [pc EXCEPT !["shutdown"] = "Done"]
         /\ UNCHANGED << issued, inflight, received, laneCur, open, task, 
                         slotWrites, late, readers, writer, waiting, i >>

Shutdown == SQueue \/ STake \/ SRelease \/ SClose \/ SWait

(* Allow infinite stuttering to prevent deadlock on termination. *)
Terminating == /\ \A self \in ProcSet: pc[self] = "Done"
               /\ UNCHANGED vars

Next == Delegate \/ Forwarder \/ Lane \/ Shutdown
           \/ (\E self \in Emissions: Emitter(self))
           \/ (\E self \in Writers: Writer(self))
           \/ Terminating

Spec == /\ Init /\ [][Next]_vars
        /\ WF_vars(Delegate)
        /\ WF_vars(Forwarder)
        /\ WF_vars(Lane)
        /\ \A self \in Emissions : WF_vars(Emitter(self))
        /\ \A self \in Writers : WF_vars(Writer(self))
        /\ WF_vars(Shutdown)

Termination == <>(\A self \in ProcSet: pc[self] = "Done")

\* END TRANSLATION

-----------------------------------------------------------------------------
(* Properties *)

IsPrefix(s, t) == Len(s) <= Len(t) /\ SubSeq(t, 1, Len(s)) = s

\* The slot is written in the order the forwarder received the emissions.
SlotOrderFollowsReceipt == \A k \in 1..Len(slotWrites) : \A m \in 1..Len(slotWrites) :
    k < m => \E a, b \in DOMAIN received : a < b /\ received[a] = slotWrites[k] /\ received[b] = slotWrites[m]

\* The forwarder never waits: whenever the delegate's pool holds an emission, the forwarder can take it.
ForwardNeverBlocks == inflight # {} => ENABLED Forwarder

\* Witness: the delegate's own order survives to the slot.  Expected to fail when the delegate does not await.
DelegateOrderKept == \A k \in 1..Len(slotWrites) : \A m \in 1..Len(slotWrites) :
    k < m => \E a, b \in DOMAIN issued : a < b /\ issued[a] = slotWrites[k] /\ issued[b] = slotWrites[m]

\* Every emission the forwarder received is written, or was enqueued once the lane had closed, or was cut off by the
\* close's bound.
EmissionsLand == \A e \in Emissions :
    (e \in Range(received)) ~> (e \in Range(slotWrites) \/ e \in Range(late) \/ e \in Range(cutOff))

\* No emission the lane accepted is cut off by its close.  Expected to fail under setProviderAndWait.
NoDrop == cutOff = <<>>

\* Every writer that queues for the lock gets it.
WritersProceed == \A w \in Writers \cup {"shutdown"} : (w \in waiting) ~> (w \notin waiting)
=============================================================================
