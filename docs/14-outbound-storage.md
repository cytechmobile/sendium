# Outbound storage contracts

This describes the initial contracts for [#338](https://github.com/cytechmobile/sendium/issues/338),
under [#337](https://github.com/cytechmobile/sendium/issues/337). The stage contracts are library APIs,
and standalone profile selection, early validation, and startup logging are implemented. The coordinator,
memory stage implementations, and end-to-end production wiring are subsequent tasks. Existing ingress
and queue behavior remains non-durable.

## Ownership model

```text
accepted pending source
    | bounded, eligible, priority-aware select-and-stage
    v
selected router work ---- take/release ---- router execution
    | record one destination assignment, then mark routed
    v
destination work ------- take/release ---- worker execution
    |                                    | forward: transfer to one successor
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
several source IDs, allowing later multipart integration without treating an aggregate as a new
independent admission. Detailed multipart admission and reconstruction are owned by #343.

There is at most one active destination work item per selection. Forwarding transfers that ownership
to a successor with a new work ID under the same selection; it does not create simultaneous destinations.

## Copied routing boundary

The existing `+vendor`/`copied` routing feature and its public API remain available on the existing
routing path. The new storage abstraction does not support copied routing. Its routing integration
must reject a copied-route request as `UNSUPPORTED` before assignment or dispatch, even if the
lookup happens to produce only one destination. It must not silently choose one result, strip the
copy flag, or emulate fan-out using several assignments or forwarding calls.

This guard belongs to the upcoming routing integration task; this profile-selection step does not change
existing routing behavior. The single-destination scope is the owner's revision to the copied-route
requirements previously described in #337/#338. Those issue bodies have not been edited here.

## Components

The public APIs are grouped by responsibility under `gr.cytech.sendium.core`:

```text
outbound/
  OutboundCoordinator.java
  OutboundWork.java
storage/
  OutboundStage.java
  PendingMessageStore.java
  SelectedRouterStore.java
  RoutedWorkStore.java
  OutboundStorageException.java
```

Memory implementations will live under `storage.memory` when introduced. Standalone configuration
assembly lives separately in `sendium-app`, under `gr.cytech.sendium.app.storage`:

- `SmsStorageProfile` is an immutable validated configuration value. Its constructor is the single
  supported-profile validator; it accepts only `memory/memory/memory`.
- `StandaloneSmsStorage` produces that profile as a CDI singleton from runtime configuration and
  requires it in an early startup observer. The observer logs the effective profile and non-durable warning.

These classes are absent from the `sendium-core` artifact; they do not activate inside an embedding application.

| Contract | Responsibility |
|---|---|
| `PendingMessageStore<M>` | Record accepted source state under a stable caller-supplied identity and complete sources idempotently. |
| `SelectedRouterStore<M>` | Own bounded select-and-stage, exclusive runtime takes, return-to-router, and retained selected state. |
| `RoutedWorkStore<M>` | Record one destination, own its scheduling, transfer responsibility on forwarding, and report terminal source ownership. |
| `OutboundCoordinator<M>` | Application-facing admission, transitions, required handoff, source completion, and lifecycle coordination. |
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
mutations are intentional execution state. They continue through transitions, returns, and forwarding;
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

## Completion and retry boundaries

- Admission retries reuse the same source ID and do not overwrite the accepted state while the
  source remains pending. A new ID is a new admission; protocol-level deduplication and deduplication
  after terminal removal are not implied.
- Returning taken work includes its updated execution projection, preserving current-process filter
  and retry mutations without silently changing earlier-stage state. The take's identities and
  destination cannot change on return. This does not promise durable retry counters or timing.
- `record` publishes a single assignment before its work can be taken. Retrying the same selection
  returns its original work ID without reactivating completed/transferred work. Conflicting source
  identities or destinations fail as `INVALID_TRANSITION`, rather than creating another assignment.
- Marking selected work routed stops router scheduling but retains the selected record until terminal
  source cleanup. This leaves room for a future durable router with memory-backed routed work.
- `transfer` atomically moves responsibility to one successor before that successor can be taken.
  Retrying with the previous ID returns the original successor; a conflicting successor destination
  fails as `INVALID_TRANSITION`. Completion of the previous work is ignored, even after its successor
  completes. A late previous-worker callback must never complete the successor.
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
coordinator implementation. There is no dependency-bundle type or public copier contract. The
coordinator remains an interface at this step; the concrete constructor and wiring arrive with its
implementation. Constructors must have no activation side effects. The standalone profile is assembled
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
and a `NON-DURABLE` warning. The concrete stage stores are not constructed at this intermediate step.

This milestone includes actionable errors and startup profile/non-durable logging. Metrics,
readiness endpoints, and broader observability are deferred. Contract-level status is not a health endpoint.
