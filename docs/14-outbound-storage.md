# Outbound storage contracts

This describes the initial contracts for [#338](https://github.com/cytechmobile/sendium/issues/338),
under [#337](https://github.com/cytechmobile/sendium/issues/337). The stage contracts are library APIs,
and standalone profile selection, early validation, and startup logging are implemented. All three
memory stage stores and the default coordinator are available as explicitly constructed library components.
HTTP/SMPP admission can bind to an explicitly supplied coordinator. Standalone activation remains deferred
until dispatch, completion, retry/rerouting, and shutdown are connected. The default runtime remains non-durable.

## Ownership model

```text
accepted pending sources
    | ready admission, or publishReady after held admission
    v
ready pending work
    | bounded, eligible, priority-aware select-and-stage
    v
selected router work ---- take/release ---- router execution
    | record one destination assignment, then mark routed
    v
destination work ------- take/release ---- worker execution
    | all provider parts terminal AND successful required handoffs
    v
current destination work terminal
    | complete pending sources
    | complete selected work
    v
forget routed terminal bookkeeping
```

Pending sources remain authoritative throughout. Taking work does not delete source or stage
ownership. Source IDs, selection IDs, and destination work IDs are distinct opaque UUID-backed types;
none is inferred from mutable message equality or a protocol serial. One selected item may retain
several source IDs. Held admission and ready-work publication retain those sources without treating
an aggregate as a new independent admission. Protocol wiring follows separately; detailed multipart
grouping, deduplication, recovery, and codec contracts are owned by #343.

Each routed selection has one destination and one work identity through execution, same-worker retry,
and terminal cleanup. Direct worker-to-worker forwarding is outside this abstraction's scope.

## Sendium retry integration boundary

The new library supports returning unfinished routing work through `returnToRouter(Selected)` and
retrying taken work at the same destination through `returnToDestination(Routed)`. The former cannot
be used after destination assignment and is not a worker-to-router transition.

Sendium's existing `AbstractOutWorker` failure policies can call `enqueueToRouter` to let routing choose
again. That production behavior remains intact. Its source-retaining lifecycle integration is planned
separately; it must be implemented before the new pipeline replaces that path. No direct worker-to-worker
API is substituted for it. Consumer-specific dispatchers, including mCore's queue worker, remain the
consumer team's responsibility and require separate approval for Sendium support.

## Copied routing boundary

The existing `+vendor`/`copied` routing feature and its public API remain available on the existing
routing path. The new storage abstraction does not support copied routing. The opt-in lifecycle
lookup rejects a matching copied-route rule as `UNSUPPORTED` before assignment or dispatch, even
if lookup would produce only one destination. It does not silently choose one result, strip the
copy flag, or emulate fan-out using several assignments for the same source.

This guard does not change existing routing behavior. The single-destination scope is the owner's revision to the copied-route
requirements previously described in #337/#338. Those issue bodies have not been edited here.

`StandardOutboundDispatch`, constructed explicitly with a coordinator and Sendium's routing manager,
can route one already selected item to one recorded destination and take that work for provider
processing. The caller retains the selected handle if recording the destination needs a retry.
A routing miss returns the selection to the router; a matched copied rule fails before
assignment and likewise returns the selection. `finishProviderParts` is called only after the provider
has reported an outcome for every part. It waits for each part's required DLR/tracking handoff before
invoking coordinated completion. While a part handoff is outstanding, its sources remain retained;
failure leaves them owned for completion-only retry with successful handoff stages. Duplicate terminal
callbacks do not release another source. A successful stage represents a part requiring no handoff.

### Coordinated SMPP provider processing

`submitToProvider` schedules an explicitly taken work item through
`SmppClientWorker.submitPreparedCoordinated`, which applies rate limiting, character mapping and
before-processing filters before `submitCoordinated` generates provider requests. It registers every
request before sending and uses typed request references for provider callbacks.
The session handler recognizes these references for submit responses, request expiry and recoverable
PDU errors before applying its legacy message casts. Ordinary message references retain their existing behavior.

Each request is claimed once, so duplicate callbacks cannot start another retry or tracking handoff.
Provider acceptance and rejection use the tracker's completion-stage handoff boundary; asynchronous
trackers can return a stage for the actual handoff. The standard rejection handoff propagates an
interrupted DLR router enqueue instead of classifying it as success. A failed handoff stays pending
and is retried through `ProviderExecution.retryPendingHandoffs`, without resending an accepted SMS.
Terminal store cleanup can be retried through `retryTerminalCleanup` without repeating provider processing.

The production multipart retry payload choices are retained: the first request references the full
original message and later requests reference individual cloned parts. A first-send exception aborts
the remaining sends and retries the full payload; a later send exception retries that part. Negative
message IDs on cloned later parts retain the existing no-tracking behavior. All request outcomes and
replacement attempts remain under the parent work, without new pending admissions or copied-route branches.

For retry responses, the worker executes its existing failure-filter/retry-counter/delay-policy logic
inside a scoped scheduling capture. Worker, router and delayed queue actions are redirected to tracked
replacement attempts. Router retries perform lifecycle routing lookup, including copied-route rejection;
same-worker retries reuse that worker. The scope is removed before replacement work begins and does
not change scheduling for ordinary legacy messages. Parent sources complete only once the original
outstanding requests, their replacement attempts, and required tracking handoffs all finish.

The parent's recorded destination is its **initial assignment**, not an assertion that every later
attempt uses that provider. `ProviderExecution.attempts()` exposes ordered runtime attempt snapshots
with their resolved destination, state and preparation/routing failure. Each attempt is scheduled
explicitly, including same-worker replacements. Router retries resolve their own destination without
overwriting the parent record or re-admitting its sources. This lets a replacement finish at provider B
while an original multipart request remains outstanding at provider A.

Paused or disconnected workers leave attempts queued; stopped workers and failed routing/preparation
leave attempts failed but unfinished. `retryPendingHandoffs()` also reschedules those failed attempts,
without re-running successfully submitted attempts. Provider tracking failures remain inside the
submission's part-handoff bookkeeping. Concurrent preparation/submission is limited by the worker's
configured thread count when its scheduling slots are first established; required tracking completion
does not hold a sending slot. Before-processing filter drops finish without sending; retry/re-enqueue
outcomes use the existing end-retry policy, including the same-worker fallback when no router exists.

The default dispatcher owns a two-thread scheduled executor. Assembly may instead supply a
`ScheduledExecutorService`; the dispatcher does not close that application-owned executor.
Graceful shutdown uses the following application-owned ordering:

1. Call dispatcher `quiesce()`. It quiesces the coordinator, stopping admission, selection and takes,
   and prevents new provider submissions. An already-taken unassigned selection presented to the
   dispatcher is returned to routing without recording a destination.
2. Keep destination workers, provider callbacks and the execution scheduler running. Call
   `awaitProviderDrain(timeout)` to await active provider executions, including replacement attempts,
   required handoffs and terminal cleanup. A timeout returns `false`; a failed completion throws an
   actionable exception. Neither result releases ownership or stops the scheduler. Retry pending
   handoffs/attempts or terminal cleanup through the existing execution handles and await again.
3. After a successful drain, stop/join application routing and destination execution loops and workers.
   Application-wide worker stop retains chosen worker queues; runtime worker removal still uses its
   existing return-to-router policy.
4. Close the dispatcher, then the coordinator. Dispatcher close refuses outstanding/failed executions
   and never shuts down an application-owned scheduler. Coordinator close returns unsubmitted taken
   work to its recorded destination and taken unassigned work to routing before closing stages.

Stopping provider workers before drain can prevent callbacks or replacement attempts from finishing.
Unresolved shutdown must retain the live ownership and report failure rather than requeue a parent
while multipart callbacks are active. Memory closure remains non-durable and clears memory state;
returning ownership during shutdown does not promise restart recovery.

This provider boundary is explicitly assembled and is not automatically wired into the standalone
router/worker loops. Standalone activation of this shutdown ordering remains subsequent
integration work. Runtime attempt bookkeeping is non-durable; it does not
provide provider-outcome checkpoints or restart recovery.

## Components

The public APIs are grouped by responsibility under `gr.cytech.sendium.core`:

```text
outbound/
  OutboundCoordinator.java
  DefaultOutboundCoordinator.java
  OutboundWork.java
storage/
  OutboundStage.java
  PendingMessageStore.java
  SelectedRouterStore.java
  RoutedWorkStore.java
  OutboundStorageException.java
  memory/
    MemoryPendingMessageStore.java
    MemorySelectedRouterStore.java
    MemoryRoutedWorkStore.java
```

Memory implementations live under `storage.memory`. Standalone configuration
assembly lives separately in `sendium-app`, under `gr.cytech.sendium.app.storage`:

- `SmsStorageProfile` is an immutable validated configuration value. Its constructor is the single
  supported-profile validator; it accepts only `memory/memory/memory`.
- `StandaloneSmsStorage` produces that profile as a CDI singleton from runtime configuration and
  requires it in an early startup observer. The observer logs the effective profile and non-durable warning.

These classes are absent from the `sendium-core` artifact; they do not activate inside an embedding application.

| Contract | Responsibility |
|---|---|
| `PendingMessageStore<M>` | Admit ready or held sources, publish prepared work using held source IDs, and complete sources idempotently. |
| `SelectedRouterStore<M>` | Own bounded select-and-stage, exclusive runtime takes, return-to-router, and retained selected state. |
| `RoutedWorkStore<M>` | Record one destination, own same-destination scheduling/retry, and report terminal source ownership. |
| `OutboundCoordinator<M>` | Application-facing admission, transitions, required handoff, source completion, and lifecycle coordination. |
| `DefaultOutboundCoordinator<M>` | Executable coordinator for fresh non-durable stages, with owned transitions and retryable handoff/cleanup. |
| `OutboundWork` | Source/selection/work identities and typed execution, assignment, and completion values. |
| `OutboundStage` | Explicit open/close, backend identity, durability declaration, and lifecycle availability. |
| `OutboundStorageException` | Identify the failing stage and distinguish unavailable, capacity, ownership, unsupported, and invalid-transition failures. |

The selected-router implementation receives its source access and selection/preparation collaborators
through construction. It can use Sendium's pending implementation or application-owned queries and
transactions. The shared API does not expose a SQL query language or require the application to
materialize its entire backlog. A selected source is not eligible for another concurrent selection.
Stage implementations enforce state transitions; malformed arguments fail immediately, while invalid
ownership/state transitions use the categorized storage failure.

`OutboundWork.Assignment` carries exactly one `Destination`, and `Routed` identifies its execution
with a `WorkId`. The work values freeze source identity sets, not message objects. Worker and filter
mutations are intentional execution state. They continue through routing and same-worker returns;
they must not silently change a retained earlier-stage record through a shared mutable reference.

The contract requires consistent ownership and stored payload/stage, not a public copying service or
a copy on every operation. A backend may use independent snapshots, encoding/mapping, or explicit
ownership transitions to achieve that consistency. The concrete mechanism will be chosen with the
memory implementation. Custom message subtypes and their fields must be preserved; flattening a
subclass to `StandardMessage` is not acceptable. Codec/schema design comes later.

## Recovery follows recorded stage ownership

After reconciling an interrupted transition by stable identity, recovery resumes from the latest
safely recorded stage for each source:

| Surviving state | Resume behavior |
|---|---|
| Destination and execution payload recorded | Restore work at that destination's worker queue. Do not rerun routing or routing filters. |
| Selected work, without recorded destination work | Resume routing from the selected state. |
| Accepted pending source only | Repeat selection and routing. |

Earlier retained records must not independently schedule the same work. Keeping a pending record
through completion does not mean routing it alongside its recorded worker-stage message.

A stored payload must match the processing stage that will resume it. For example, silently replacing
a pre-routing payload with its routing-filter output while retaining the pre-routing stage could apply
the transformation twice. Correctly recorded routed work instead resumes with its saved destination
and execution payload, without running that routing filter again.

Backends may retain separate accepted/execution representations or explicitly advance payload and
stage together, provided they retain the recoverable source information required by the configured
profile and can reconcile interrupted transitions. This does not require permanently immutable
copies of every intermediate message. Work performed after the last recorded boundary may repeat;
provider-outcome checkpoints and exactly-once submission remain outside the guarantee.

These are requirements for the later durable implementations, not a restart guarantee for memory.

## Memory pending and selected-router implementation

These two classes are plain Java components with no CDI activation or background refill threads:

- `MemoryPendingMessageStore<M>` takes a positive maximum source count and a
  `UnaryOperator<M>` snapshot function. An optional `Function<? super M, Instant>` supplies absolute
  eligibility time when ready work is first published. Ordinary `admit` publishes a singleton immediately;
  `admitHeld` does not evaluate eligibility or put anything in the selection index. The default time policy
  makes published work immediately eligible. No new interpretation of HTTP deferred-delivery or validity
  fields is introduced by this library step.
- `MemorySelectedRouterStore<M>` takes that pending store, a positive routing capacity, and optionally
  a `Clock` (UTC by default). Open pending first, then the selected store. A pending instance accepts
  only one selected-router owner for its lifetime, including after that router closes.

The snapshot function is a concrete memory-backend constructor dependency, not a new public copier
interface. It must preserve the concrete subtype and all fields, detach mutable state, and not retain
references that a caller could later mutate. The backend rejects null, identity, and subtype-flattening
results. It cannot generically prove deep isolation of arbitrary application-specific objects; supplying
a correct snapshot function belongs to assembly. Functions run under the store lock and must be
side-effect-free, nonblocking, and must not reenter these stores. The standalone message mapper is
supplied when ingress is wired; this step does not serialize messages or define a durable codec.

Pending admission is idempotent by source ID while that source exists, even at capacity. Repeating either
admission method preserves the first accepted payload and admission disposition: an `admit` retry cannot
make a held source ready, and an `admitHeld` retry cannot withdraw ordinarily admitted work. New source
admission fails with `CAPACITY_EXCEEDED` when the bound is reached. Admission or snapshot failure
does not reserve a source slot. A single-source `find` returns an isolated value for backend consumers;
there is no all-backlog loading API. Pending completion remains the caller's terminal-processing
decision, not a side effect of selection, take, or routing.

Selection uses priority-indexed ordered sets maintained at ready publication. It inspects due candidates rather
than copying or sorting the whole backlog on each refill. Higher numeric priorities select first; within
a priority, earlier eligibility comes first, then ready-publication order (admission order for ordinary
singletons). Future work in a higher-priority group
does not block eligible work at a lower priority. A batch captures one clock instant. This is an
in-process scheduling policy, not a durable global-order guarantee.

`selectAndStage(limit)` publishes at most the smaller of the requested limit and free routing slots.
Each selected ready item is removed from the eligibility index while all its sources remain in pending storage.
A prepared aggregate occupies one routing slot, not one per source. Shared
locking makes staging and removal from eligibility indivisible per ready item. If snapshotting a later
item fails, the published prefix remains available and the failed item remains eligible; retry neither
duplicates selected sources nor loses accepted work.

The selected backlog schedules in publication order. Routing capacity counts both queued and taken
items, so returning taken work never requires another slot. `markRouted` releases the routing slot but
retains selected ownership; only after pending completion may selected `complete` remove the record.
Routed sources are not eligible for another batch. The caller invokes bounded refill as slots become
available; taking work does not implicitly select a new batch.

Take returns an isolated execution projection. Return the actual `Selected` value from that take after
updating its message; it serves as the local attempt handle, while its stable selection ID remains the
stage identity. Return snapshots the updated execution payload without changing accepted content.
Duplicate returns do not enqueue duplicates, and an older attempt cannot replace an active newer
take. Reconstructed or unrelated projections are rejected. This local handle is not a persisted dequeue
stage or a multi-process lease.

Blocking takes support zero-time polling, timeout, interruption, and wake-up when work is published
or either store closes. Close selected before pending after stopping execution. Closing is idempotent;
neither store can reopen after close. Closing a selected store does not complete its pending sources
or make its claimed sources eligible again; shutdown finishes by closing the pending store as well.
All state is instance-local. New instances start empty and provide no restart recovery. Counts bound
admission and active routing, not message byte size; required terminal bookkeeping is removed by
coordinated cleanup in the later integration tasks.

## Held-source admission and ready-work publication

The pending interface and coordinator expose two operations for the initial multipart ownership boundary:

```java
coordinator.admitHeld(firstSourceId, firstPart);
coordinator.admitHeld(secondSourceId, secondPart);

// The existing reassembler supplies this message and its accepted source IDs.
coordinator.publishReady(Set.of(firstSourceId, secondSourceId), assembledMessage);

coordinator.selectAndStage(batchLimit);
```

This example describes the library boundary used by the opt-in admission integration below. Successful held admission gives
the protocol integration an ownership boundary at which to acknowledge an individual part without
waiting for assembly. Held records consume pending source capacity but can never be selected on their
own, regardless of their priority or timestamp. A snapshot/admission failure must not be acknowledged.

`publishReady` accepts a nonempty set of already admitted, initially held source IDs and one prepared
execution message. It validates all sources and prepares the snapshot/eligibility metadata before binding
the whole set and exposing one candidate to normal selection. It does not enqueue directly to the router,
create a new accepted source, free source capacity, or overwrite the original accepted part payloads.
Publication therefore works even when the held sources fill pending capacity. Selection still observes
batch size, routing capacity, the prepared message's priority, and its eligibility time.

In memory, accepted-source records and ready scheduling entries are separate. Each source links to
its first ready entry; multiple sources can share one entry. The shared lock prevents a selector from
seeing a partially bound group. The ready-entry link remains after selection so retries do not publish
another candidate. The source snapshot remains available through `find(sourceId)` independently of
the prepared aggregate and its subsequent execution mutations.

While every source is retained, repeating the exact source set is a successful no-op: the first published
payload, priority, and eligibility time win, including after the item is selected, taken, or routed. Missing
or ordinarily admitted sources and overlapping/subset/superset regroupings fail as `INVALID_TRANSITION`.
All validation precedes binding, so failure cannot consume otherwise-unpublished sources. Snapshot or
eligibility failure leaves held sources available for a whole-publication retry. Once any source has been
removed during terminal cleanup, publishing that set is invalid; publication never recreates sources or
retains completed-publication tombstones forever.

The caller owns assembly and expiry decisions. To release an expired incomplete part independently,
publish a singleton source set and its execution message. This primitive introduces no UDH parsing,
group-key policy, ordinal deduplication, timer, persisted deadline, or codec. Those detailed contracts
remain #343 work; the admission integration reuses the existing reassembler.

`admitHeld` requires a running coordinator, just like ordinary admission. `publishReady` is an
existing-work transition and remains available while quiescing, so an assembler may release already
accepted work during shutdown. New selection/takes remain stopped. Held and prepared memory state
is instance-local and disappears on close/restart; no new durability guarantee is implied.

## HTTP/SMPP admission binding

`KannelResource` and the standard outgoing-worker factory resolve a default-qualified CDI
`OutboundCoordinator<StandardMessage>` when one is supplied by application assembly. HTTP calls
`admit` on that coordinator; the factory binds it to each standard SMPP server before worker startup.
Other explicitly assembled SMPP workers can use `setIngressCoordinator` with their own message type.
The SMPP binding cannot be changed after startup or any submission has begun.

No production coordinator producer is added at this intermediate step. With no coordinator binding,
the existing memory-queue admission path remains available. When a binding exists, resolution or
admission failure does not fall back to that queue. Applications must supply a long-lived, started
coordinator and connect selection, dispatch, completion, and retry policies before enabling the new
pipeline. The standard message snapshot mapper and standalone activation are subsequent integration work.

### HTTP

After authentication, parameter handling, gateway UUID generation, and DLR return-metadata creation,
HTTP admission calls `admit` with a typed source ID wrapping the same UUID as the gateway message ID.
It returns `202` only after admission succeeds; the response contains that gateway UUID as text.
The source ID remains a distinct lifecycle type so other applications can use their own protocol IDs.
Lifecycle failures map to:

| Failure | HTTP response |
|---|---|
| Capacity, unavailable storage/lifecycle, or ownership conflict | `503` with a retry-later response |
| Unsupported submission | `400` |
| Invalid internal transition or unexpected resolution/processing error | `500` |

Error responses do not expose storage exception details. The legacy interrupted-admission path also
returns `503` and preserves the thread's interrupt flag.

### SMPP and existing reassembly

Before-insert filters and gateway UUID/timestamp assignment still precede admission. A complete SMS
is admitted directly as ready work. A submission with a concatenation header must pass the existing
reassembler's supported-header check, then uses `admitHeld` before `STATUS_OK`. It enters the ingress
queue with its accepted source ID (the same UUID as that part's gateway message ID); it is not
independently selected for routing. In legacy mode, local
ingress-queue insertion now also precedes `STATUS_OK`.

Capacity rejection returns `STATUS_THROTTLED`; unsupported input returns `STATUS_SUBMITFAIL`;
other lifecycle/storage failures return `STATUS_SYSERR`. Rejections do not publish a successful response
or enter the legacy router queue. No new UDH syntax or detailed ordinal-validation policy is introduced.

The existing reassembler produces either an aggregate or individually expired parts. Their internal
`InEvent` values carry an immutable source-ID set and no `submitSm`; processing calls `publishReady`
instead of admitting a new source or enqueueing directly to the legacy router. Publication failure requeues
that prepared event with the same payload and source IDs. It does not reassemble again, issue another
client acknowledgement, or release accepted sources. Original part payloads remain in pending storage.

Per-worker identity-based metadata associates accepted raw part objects with source IDs until the
association is handed to a prepared event. The existing first-part-wins ordinal behavior remains; a
default-compatible `onDuplicateMessagePart` listener hook associates an ignored duplicate's held source
with the retained original. Its source therefore finishes with that message instead of leaking pending
capacity. This does not change duplicate response IDs or downstream receipt policy, which remain part
of the detailed multipart follow-up.

Part insertion and completion/expiry callbacks are serialized within the existing handler so source
associations cannot cross group removal. Delayed tasks capture the actual group instance: a cancelled
old timer cannot consume a newer group that reused the same reference. Expiry only publishes the
parts already in that group, not accepted parts still waiting in the ingress queue. Group-key rules,
body assembly, relative timeout settings, and first-part-wins policy otherwise remain unchanged.

These changes do not activate provider dispatch or implement worker-to-router retry. No mCore-specific
workflow or storage adapter is added.

## Memory routed-work implementation

`MemoryRoutedWorkStore<M>` is independently constructed with a positive selection capacity and a
`UnaryOperator<M>` snapshot function, with the same isolation/subtype requirements as the pending
memory backend. It has its own lock and does not depend on a concrete pending or selected store.
Coordination across stores belongs to `DefaultOutboundCoordinator`.

```text
record assignment → QUEUED → take → TAKEN
                        ↑             |
                        +-- release --+
                                      |
                                   complete
                                      |
                                  COMPLETED
                                      |
                           repeatable cleanup result
                                      |
                         coordinator finishes cleanup
                                      |
                                    forget
```

- `record` retains one assignment per selection. Matching retries return its original work ID and
  original assignment payload, even after completion, without re-enqueueing it. A
  changed source set/destination is invalid. A source cannot belong to two retained routed selections.
- The `record` result describes recorded work; it does not claim execution. Only `take`
  claims a work item. Each destination schedules in publication order. Take and return preserve one
  active taker and updated execution state; return the actual take result as its local attempt handle.
- `complete` requires taken work after the caller has established terminal processing and required
  handoff. It returns `Completed` directly; repeated completion returns the same selection/source set.
  There is no transferred-predecessor case or empty completion result.
- `forget` rejects nonterminal selections. After the coordinator completes pending/selected cleanup,
  it removes that selection's one work record and source claims, freeing capacity. Repeated
  forget is harmless. Unknown work IDs fail explicitly. Replay guarantees apply while records are
  retained; the coordinator must reject stale routing requests and absorb duplicate terminal
  callbacks after cleanup rather than recreating old work.

Capacity counts retained selections, including taken work and terminal selections awaiting cleanup.
Same-worker return reuses that selection's slot even at full capacity. The same entry is indexed by
selection ID and work ID; no successor chain or forwarding history is stored. The original assignment
payload is retained for record retries alongside current execution state. Selection count does not bound
message bytes. Sendium's retry/rerouting policy is integrated in the later worker task.

All required snapshots, including the returned result, are prepared before assignment publication.
If a snapshot fails, no partial assignment is published, and the current work remains
retryable. Takes are interruptible and wake on publication or close. Closure clears instance-local state
without manufacturing completion; closed instances cannot reopen and fresh instances recover nothing.

## Default lifecycle coordinator

Construct `DefaultOutboundCoordinator<M>` with the pending, selected-router, and routed-work stores
as three direct dependencies. Construction is passive. `start()` opens them in that order; failed startup
closes every attempted stage in reverse order, including a stage whose open failed, and preserves
cleanup failures as suppressed exceptions.

This implementation currently requires fresh, unopened, non-durable stages. Already-opened stages
are not silently adopted, and durable stages fail with `UNSUPPORTED` because startup recovery and
cross-backend reconciliation are not implemented yet. Non-durable embedding implementations can
use the interfaces directly; there is no requirement for their backend identity string to be `memory`.

The application must use the coordinator exclusively for scheduling and transitions. `admit` does not
implicitly refill the router; invoke bounded `selectAndStage` explicitly. Blocking takes poll the owned
store with a zero timeout and then wait on the coordinator's publication/quiescence condition.
They support interruption and timeouts and wake when coordinated work is published or quiescence begins.

### Owned transitions

The coordinator tracks live selections, source ownership, and destination executions. It validates the
actual take projection when returning or routing work. Recording a destination precedes marking the
selection routed. A failed route retains its original destination intent; retry the same `route` operation,
not the routing/filter lookup. While that transition is unresolved, its source cannot return to routing
or be dispatched at the destination. Other existing destination work can still complete and free capacity.

A backend that committed an assignment before reporting a failure can be reconciled using the same selection/work identities;
observing its published take associates it with the retained intent. An unexposed take whose return
failed is retained for a return retry rather than disappearing from ownership.

### Required handoff and cleanup

`complete(workId, requiredHandoff)` begins terminal processing for a taken destination. `discard`
does the equivalent for a taken, unassigned routing item. Concurrent duplicate reports share the active
completion attempt. No source is removed while the handoff is pending, failed, or cancelled. Retry a
failed handoff explicitly with another completion call; the work cannot meanwhile be returned for
provider redispatch.

Once the handoff succeeds, the coordinator remembers that success and performs:

```text
destination complete (when routed)
    → verify returned selection/source identities
    → pending sources complete
    → selected record complete
    → routed bookkeeping forget (when routed)
    → remove coordinator ownership
```

Each successful cleanup step is remembered. A failed step is retried through `complete`/`discard`
without awaiting another handoff or dispatching provider work. Partial multi-source deletion retries
the full source set idempotently. Duplicate admission of a still-owned source does not recreate a
source record during an unfinished cleanup. The source identity must represent the same admission;
after all ownership is forgotten, long-term admission deduplication is not promised.

Completion results are read-only stages: cancelling a caller's `toCompletableFuture()` view cannot
cancel the owned handoff or cleanup. Returned-stage listeners run outside the coordinator lock.
Handoff or cleanup failures settle that attempt exceptionally and retain ownership for retry.

To avoid retaining completed-ID tombstones forever, terminal reports for unknown/no-longer-owned
work or selections are harmless no-ops. They never recreate work, remove another source, or wait on
an unrelated supplied handoff. Route and return requests still require live ownership and
reject stale IDs/projections. Unknown-ID errors raised for an operation on live owned work propagate;
they are not treated as successful cleanup.

### Quiescence and close

`quiesce()` stops new admission, selection, and takes while allowing existing transitions, returns,
handoff completion, and cleanup retries. The application stops/joins its processors before `close`.
Close returns taken routing work to the router and taken destination work to that same destination,
then closes routed, selected, and pending stores. It never completes sources merely to shut down.

Outstanding handoff attempts, unfinished routing intents, and failed terminal cleanup must be
resolved/retried before close can finish. A refused close leaves the coordinator quiescing and its
stores open. A return failure also leaves ownership available for a subsequent close retry. Once
draining succeeds, close attempts every store and preserves close failures; closure is idempotent and
restart is forbidden. This library lifecycle does not stop application executors or provide a forced
shutdown policy. Actual ingress/worker activation and application shutdown ordering remain integration work.

## Completion and retry boundaries

- Admission retries reuse the same source ID and do not overwrite the accepted state while the
  source remains pending. A new ID is a new admission; protocol-level deduplication and deduplication
  after terminal removal are not implied.
- Returning taken work includes its updated execution projection, preserving current-process filter
  and retry mutations without silently changing earlier-stage state. The take's identities and
  destination cannot change on return. This does not promise durable retry counters or timing.
- `record` publishes a single assignment before its work can be taken. Retrying the same selection
  returns its original work ID without reactivating completed work. Conflicting source
  identities or destinations fail as `INVALID_TRANSITION`, rather than creating another assignment.
- Marking selected work routed stops router scheduling but retains the selected record until terminal
  source cleanup. This leaves room for a future durable router with memory-backed routed work.
- Provider multipart is aggregated by provider-processing integration: all parts must have terminal
  outcomes and all required handoffs must succeed before completing the one destination work item.
  Part submissions are not represented as multiple routed destinations.
- `complete(work, requiredHandoff)` is called after all provider parts have terminal outcomes. The supplied
  `CompletionStage<Void>` represents successful acceptance by the required DLR/tracking subsystem,
  not eventual delivery of the downstream receipt. Use an already-successful stage when no handoff
  is required. For multipart, it covers all required part handoffs. Failure/cancellation retains ownership;
  a later retry supplies another handoff attempt.
- Source cleanup occurs only when the active destination work satisfies that rule. Retry failed source cleanup without
  dispatching terminal provider work again in the active process. The routed store retains a repeatable
  completion result until cleanup succeeds and it can be forgotten. The coordinator must suppress
  duplicate terminal callbacks, including callbacks arriving after store bookkeeping was forgotten.
- A terminal filter drop before routing uses `discard` with the same handoff requirement. A routing
  miss uses return-to-router; it is not a terminal drop. Assignments without a destination are invalid.

These contracts do not assume atomic transactions across independently selected stores. Physical
backends and the coordinator must reconcile interrupted transitions by stable identity. Refinement
of durable batch/transition semantics belongs to #344. No durable provider-outcome checkpoint or
exactly-once provider submission is promised.

## Embedding and lifecycle

An application assembles compatible `PendingMessageStore<M>`, `SelectedRouterStore<M>`, and
`RoutedWorkStore<M>` instances and passes them directly as three constructor dependencies to its
coordinator implementation. There is no dependency-bundle type or public copier contract.
`DefaultOutboundCoordinator` provides that constructor and the initial non-durable lifecycle. Constructors
have no activation side effects. The standalone profile is assembled
through CDI in `sendium-app`; the reusable lifecycle/storage APIs require no CDI annotations or container.

For example, mCore's `Message extends StandardMessage`, billing-aware batch preparation,
asynchronous committed tracking, and own lifecycle orchestration can be adapted without importing
its domain classes into Sendium. An application's tracker returning after an in-memory enqueue is
not a committed handoff; its completion signal must correspond to the promised storage acceptance.
mCore adapters and adoption are owned by its team, not #338.

Graceful shutdown is two-phase: `quiesce()` stops new admission, selection, and takes; the application
stops and joins execution, resolves or drains in-flight provider operations, and returns unfinished
work; `close()` then closes owned stages. Completion and return operations remain available during
quiescence. Unrouted work returns to the router; already-routed work retains its destination. Runtime
worker removal is a separate policy, not an alias for application shutdown. Closure is idempotent,
cannot mark unfinished work terminal, and does not imply that memory survives a restart.

## Standalone profile selection

The standalone application reads these selectors once during startup:

```properties
sendium.sms.pending.backend=memory
sendium.sms.router-queue.backend=memory
sendium.sms.routed-work.backend=memory
```

| Pending / router / routed | Availability | Restart behavior |
|---|---|---|
| `memory/memory/memory` | Only selectable profile; stage implementations/coordinator wiring follow in #338 | Non-durable. |
| `file/memory/memory` | #339 | Recover accepted work; reselect and reroute. |
| `file/file/memory` | #345 | Recover selected work; reroute. |
| `file/memory/file` | #345 | Reselect not-yet-routed work; restore recorded destinations. |
| `file/file/file` | #345 | Restore selected work and recorded destinations. |
| PostgreSQL profiles | #333 | Only explicitly implemented combinations will be supported. |

Standalone validation accepts only this profile and rejects unsupported requests before
admission, without falling back to memory. A custom backend identity in the library does not register
a supported standalone profile. DLR storage remains independent of outbound stage selection.
Future durable guarantees require surviving storage and remain at least once.

Missing selectors default to `memory`. Explicit blanks, unknown values, and every unimplemented
combination fail startup with the requested profile and supported choice. The values are case-sensitive
and are read from Quarkus runtime configuration, not hot-reloaded `smsg.properties` worker settings.
Use the corresponding environment variables `SENDIUM_SMS_PENDING_BACKEND`,
`SENDIUM_SMS_ROUTER_QUEUE_BACKEND`, and `SENDIUM_SMS_ROUTED_WORK_BACKEND`, or JVM `-D`
properties. Changes require a restart; they do not require rebuilding the application.

The startup observer uses `Interceptor.Priority.PLATFORM_BEFORE`, ahead of the existing file watchers
(`LIBRARY_BEFORE`) and router/worker observers (`APPLICATION`). Injecting the singleton profile into
that observer forces validation before those later observers run. Successful startup logs the profile
and a `NON-DURABLE` warning. The library memory stores are not yet constructed by this startup observer.

This milestone includes actionable errors and startup profile/non-durable logging. Metrics,
readiness endpoints, and broader observability are deferred. Contract-level status is not a health endpoint.
