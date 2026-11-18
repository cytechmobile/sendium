# Filesystem Outbound Storage: Architecture and Operations

[Documentation map](DocumentationMap.md) · [Outbound lifecycle](14-outbound-storage.md) · [Source schema](15-pending-record-schema.md)

## What this profile guarantees

`file/memory/memory` retains accepted HTTP/SMPP source records across **process/container restart
when the same spool storage survives**. `memory/memory/memory` remains the default. Selection,
routing decisions and provider execution are volatile: retained sources are selected and routed again
after restart. Existing PostgreSQL provider-correlation and downstream DLR storage remain separate.

This is **at-least-once provider processing**, not exactly-once delivery, a durable retry scheduler,
or host-power-loss durability. There is no filesystem/DB/provider transaction. A crash after sending
to the provider but before source completion can repeat submission. A lost client response can also
cause a duplicate when the client retries. An admission error after publication does not prove that
no source was committed. Use application-level reconciliation where duplicates matter.

Only pending storage is durable in that profile. Opt-in `file/file/memory` also restores committed
selections; see [durable selection](#durable-selection-filefilememory). The `file/memory/file` and
`file/file/file` profiles additionally restore routed assignments. PostgreSQL outbound stages and
copied-route execution remain unsupported.

### Durable selection (`file/file/memory`)

Set `sendium.message.router-queue.backend=file` (or `SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND=file`)
alongside the file-pending configuration below. The selected store shares pending's root and exclusive
owner; do not provision a second selected directory or process. Defaults remain memory.

Each selected item is an immutable CBOR envelope with `schemaVersion=1`, canonical `selectionId`, the
complete `sources` UUID set and an explicit `message` mapping using the accepted message fields.
For multipart work this is the committed assembled result, not a pending readiness recipe. Originals
and duplicates remain accepted sources. Source membership and identities are validated on recovery.
Each item commits independently: a failed selection call can leave some items committed and others
eligible pending work. Startup reconciles them without assigning new IDs to committed selections.

Startup first replays authorized cleanup, validates surviving selected ownership and rebuilds disk-backed
source/scheduling references. It then restores a bounded routing window and reconstructs only unowned
multipart originals. Current routing rules run again on the restored selected payload; neither the
previous destination nor runtime release mutations/retry timing are restored. Router dequeue is volatile.
Completion requires all terminal processing and required DLR handoffs; its intent authorizes removal of
both accepted sources and their selected record and survives interrupted deletion. A crash before that
terminal boundary can repeat provider submission.

Workers retain their normal startup/reconnection behavior. No grace period or removed-destination
fallback is added. An absent worker can leave its in-process assignment retained and consuming capacity;
with memory routed storage, restart repeats routing rather than preserving that destination.

### Durable routing (`file/memory/file` and `file/file/file`)

Set `sendium.message.routed-work.backend=file` (or `SENDIUM_MESSAGE_ROUTED_WORK_BACKEND=file`)
with file pending. Selection may remain memory-backed or also use file storage. All stages share one
spool root and exclusive owner; memory remains the default for every selector.

An immutable `routed/<UUID-prefix>/<WorkId>.cbor` envelope contains `schemaVersion=1`, `workId`,
`selectionId`, the complete `sources` UUID set, the single `destination`, and explicit `message` fields.
This routing-time snapshot outranks earlier selected/pending scheduling. Startup rebuilds source,
selection and hashed-destination references on disk, not a whole-backlog payload/ownership map.
Only requested destinations load payloads: queued plus taken projections are bounded per destination
by `sendium.message.selection-batch-size`. Unopened destinations load no execution window and do not
block other destinations. Routed record capacity is the pending source-capacity setting; recovered
records above a reduced capacity are retained, and new assignments are rejected with `CAPACITY_EXCEEDED`
until cleanup frees capacity.

Runtime take/release mutations, retry schedules/counters and provider-part outcomes are not persisted.
A restart resumes the committed routed snapshot at its recorded destination, without rerouting it.
Normal worker startup/reconnection is unchanged. A removed destination retains its records and capacity
usage until a compatible worker can process them; there is no grace timer or automatic fallback route.

After terminal processing and required tracking/DLR handoffs succeed, a separate
`routed-terminal/<UUID-prefix>/<WorkId>.cbor` fact records schema version, work/selection IDs and sources,
without a message payload. Recovery resumes cleanup rather than sending this work. Cleanup deletes
accepted/selected records before forgetting the routed assignment and terminal fact; a journaled cleanup
intent's optional `routedWork` UUID array authorizes deletion through an interrupted final forget.
The terminal fact does not wait for a later handset DLR. Provider acceptance before terminal publication
can still be repeated after a crash; these profiles are not exactly-once delivery.

Do not downgrade to memory routing while any routed records remain, or to memory selection while
selected records remain. Startup rejects these downgrades without deletion or memory fallback. Drain
with a compatible binary, or preserve the entire spool and compatible binary for later resumption.

### Why stage payloads coexist

This is not limited to multipart traffic. For one ordinary SMS, `file/file/file` can retain an accepted
input, a selected execution snapshot and a routed snapshot at the same time. They may contain largely
identical payloads, but represent different committed decisions and share stable ownership identities.
They are not three independent messages.

Commit the next stage before suppressing earlier scheduling: if a crash follows routed publication,
recovery uses its selection/source IDs to resume the recorded destination rather than route or select
the earlier records again. Earlier full payloads remain until terminal cleanup under the implemented
ownership design. Once routed work is committed, its payload is sufficient for dispatch; retaining
earlier snapshots through completion is a deliberate disk/write tradeoff, not a universal durability
requirement. Required tracking/DLR handoff completion permits cleanup; a later handset receipt is
handled separately by the DLR subsystem.

## Components and ownership

```mermaid
flowchart LR
    ingress[HTTP / SMPP admission] --> coordinator[Shared outbound coordinator]
    coordinator --> pending[File pending store]
    pending --> spool[Exclusive spool: accepted sources and cleanup intents]
    pending --> indexes[Multipart indexes / source-only readiness recipes]
    coordinator --> recovery[One bounded multipart recovery poller]
    recovery --> pending
    coordinator --> selection[Capacity-limited selection]
    selection -->|read originals and assemble| pending
    selection --> selected[Memory selected backlog]
    selected --> routing[Routing / filters]
    routing --> routed[Memory routed work]
    routed --> provider[SMPP provider execution]
    provider --> handoff[Required tracking / DLR handoffs]
    handoff --> coordinator
```

The diagram above is `file/memory/memory`. In `file/file/memory`, replace the memory selected backlog
with `FileSelectedRouterStore`, which writes authoritative selected records under the same spool owner.
For file-routed profiles, replace memory routed work with `FileRoutedWorkStore` under that same owner.

- `StandaloneOutboundPipeline` constructs compatible stores and starts the coordinator before returning
  it to admission. Unsupported profiles, missing paths and unsafe storage fail without memory fallback.
- `DefaultOutboundCoordinator` owns store startup, one shared recovery component and coordinated source
  completion. Stopping an SMPP worker does not stop shared recovery; handlers only borrow it.
- `FilePendingMessageStore` retains accepted content, multipart identity and absolute deadlines.
  Mutable execution messages are detached from the accepted source snapshot.
- The selected and routed stores may each be memory or file-backed when pending is file-backed. Completion retains source ownership until every
  required provider-part/tracking/DLR handoff succeeds. A failed handoff is not terminal cleanup.
- Recovery shuts down outside the coordinator lock, before stores close, to avoid deadlock with a
  recovery callback. Closing stores preserves unfinished sources; it is not completion.

## Physical layout

Treat the entire directory as private application state, not a public file API:

```text
pending/
  owner.lock                         OS-held exclusive ownership; not a stale-lock flag
  format                             sendium-pending-spool:3 followed by newline
  accepted/
    3/<bucket>/<source-UUID>.cbor      high priority
    2/<bucket>/<source-UUID>.cbor      normal priority
    1/<bucket>/<source-UUID>.cbor      low priority
  terminal/<intent-UUID>.cbor         committed cleanup authorization
  selected/<bucket>/<selection-UUID>.cbor  authoritative selected results when configured
  routed/<bucket>/<work-UUID>.cbor     authoritative single-destination assignments
  routed-terminal/<bucket>/<work-UUID>.cbor  terminal processing facts without payloads
  closed-groups/<bucket>/<UUID>.closed
  derived/                           disposable, rebuilt while opening the spool
    multipart/
      groups/<group-UUID>/
        members/<source-order-key>   references to originals and duplicates
        ordinals/<ordinal>           file containing that ordinal's original source UUID
        due-key                      reference to the group's current scheduled time
      matching/<hash-prefix>/<matching-key-hash>/<group-order-key>
      due/<deadline-and-group-key>
      ready/
        3/<representative-order-key>
        2/<representative-order-key>
        1/<representative-order-key>
    publications/
      work/<publication-UUID>.cbor    source-only readiness recipe, never a message body
      bindings/<bucket>/<source-UUID>
    selected/
      owners/<bucket>/<source-UUID>   source-to-selection lookup references
      ready/<selection-order-key>    bounded directory-reference scheduling index
    routed/
      owners/<bucket>/<source-UUID>   source-to-selection/work references
      selections/<bucket>/<selection-UUID>  selection-to-work references
      destinations/<SHA-256>/ready/  bounded destination scheduling references, not payloads
      terminal/                      bounded terminal cleanup references
```

Each accepted bucket is the first two lowercase hexadecimal characters of its source UUID (`00`–`ff`);
only populated buckets need exist. An accepted source always stays in its admission-priority folder,
including after multipart assembly, selection or routing. Pending has no separate assembled-parent
payload file and no ordinary source, priority or ready-reference index; file selected/routed stages
write their own execution snapshots separately.

The derived tree above shows logical reference locations. Its `members`, `matching`, `due` and `ready`
indexes split large leaves into `children/<hex-pair>/…` directories; the full reference key remains the
leaf filename. Source-order keys are 32 hexadecimal characters encoding Java UUID comparison order,
not hyphenated UUID filenames. Matching references use 48-character group-order keys and deadline
references use 56-character time/group keys. These implementation details are not an external file API.

Accepted CBOR sources are authoritative. Each accepted multipart duplicate has its own source record;
the first ordinal payload remains the assembly owner. Cleanup intents and group-closure markers are
also authoritative: dropping them can resurrect terminal work or reopen an unfinished closed group.
Selected, routed and routed-terminal CBOR files are also authoritative; do not delete them to change a backend profile.
`derived/` is disposable to **the locked startup implementation**, not permission to edit a running spool.
It contains multipart lookup/deadline/ready references, source-only recipes/bindings and selected/routed-stage references.
No embedded database is used; folder references are reconstructed from the authoritative state.

### Example: two parts and a duplicate

These are three distinct acknowledged source files, even though they produce one provider message:

```text
accepted/2/ab/ab000000-0000-4000-8000-000000000001.cbor   part 1: "Hello "
accepted/1/cd/cd000000-0000-4000-8000-000000000002.cbor   part 2: "world"
accepted/3/ef/ef000000-0000-4000-8000-000000000003.cbor   duplicate of part 1
```

All three records embed the same `multipart.groupId`, matching key and fixed deadline. Their ordinals
are 1, 2 and 1. The first two have `originalSourceId` equal to their own source UUID; the duplicate's
`originalSourceId` points to `ab000000-0000-4000-8000-000000000001`. This embedded membership, not
their directories or a sender-provided multipart reference alone, identifies which group they belong to.
See the [membership schema](15-pending-record-schema.md#multipart-admission-and-membership).

Recovery registers a recipe containing the two original UUIDs in ordinal order and ownership of all
three source UUIDs. It does **not** write an assembled "Hello world" payload. Only when a router slot
is available does selection read the original parts and assemble that detached message in memory.
With file selected storage, selection then commits that assembled execution in `selected/`; file routed
storage additionally commits the routing-time payload in `routed/`. The recipe itself remains source-only.
Assembly preserves the first ordinal's priority (2 here), not the duplicate's priority or the highest
priority among the parts. Terminal completion removes all three owned source files. For an incomplete
expired group, each received original is forwarded independently with its duplicates, preserving its UDH.

Recipe schema 2 has `schemaVersion`, `publicationId`, `sources`, ordered `originals`, `priority` and
`assembled` fields—no `message` field. Recipes preserve same-process idempotency/ownership, not a durable
execution checkpoint. The file backend rejects generic `makeHeldReady(sources, replacementPayload)`
with `UNSUPPORTED`; its internal source-only readiness operation derives assembly or single-original
forwarding from accepted data. Memory-backend arbitrary-payload publication is unchanged.

Publication writes and closes a unique same-filesystem temporary file, then creates the committed name
using an atomic **non-replacing hard link**. Existing committed records are never overwritten. Successful
multipart admission also requires safe membership-index updates before protocol success. Ordinary
admission has no derived disk-index update. Known temporary names are
cleaned during locked recovery; unexpected committed filenames, unsupported formats and corrupt
committed records fail startup rather than being silently ignored. Symbolic links are not supported spool entries.
No `fsync`/power-loss guarantee is claimed.

The spool format marker is separate from accepted-record schema version 1 and recipe schema version 2.
Old spool markers or an old `sources/` tree are rejected before temporary cleanup or derived-state
rebuilding. There is no conversion, automatic deletion or migration of old spools. Preserve old data
and use its compatible binary to drain it; changing the marker or renaming directories is not migration.

Terminal completion first publishes a cleanup intent, then removes authorized sources, selected records and associated
references. Group-closure markers survive while siblings remain. If the process dies during deletion,
startup replays the committed intent before validating duplicate/original relationships. Intent schema 1
adds a `selections` UUID array for selected records covered by the same cleanup authorization.

## Startup and restart sequence

```mermaid
sequenceDiagram
    participant App
    participant Coordinator
    participant FileStore
    participant Recovery
    participant Ingress
    App->>Coordinator: start()
    Coordinator->>FileStore: open and acquire exclusive lock
    FileStore->>FileStore: validate layout and cleanup metadata
    FileStore->>FileStore: replay terminal intents
    FileStore->>FileStore: rebuild multipart references and validate every source/membership
    Coordinator->>Coordinator: open selected (memory or file) and memory routed stages
    Coordinator->>Recovery: initialize bounded reconstruction and one poller
    Coordinator-->>App: ready coordinator
    App->>Ingress: allow admission
    Ingress->>Coordinator: accept source / multipart part
    Coordinator->>FileStore: publish accepted record and any multipart references
    FileStore-->>Coordinator: confirmed handle
    Coordinator-->>Ingress: committed admission
    Ingress-->>Ingress: return HTTP 202 / SMPP STATUS_OK
```

Readiness means validated ownership and initialized safe recovery, **not an empty backlog**. Complete
unowned groups assemble again from original accepted parts. Incomplete groups retain their original absolute
deadline; a later part or timeout reconfiguration does not extend it. Expired originals become independent
routing work while duplicate source handles remain owned through completion.

Startup discards old recipes/bindings and rebuilds readiness from accepted membership rather than
resuming old pending-only assembly/routing decisions. With file selected, committed selections instead
take precedence over reconstruction of their sources. Payload assembly for new selections waits for selection capacity.
Current routing configuration/filters therefore apply again to retained sources. Plan configuration changes
accordingly; restart is not a promise to use the previous destination or provider retry timing.

## Deployment

Select the profile using startup-only application settings, not `smsg.properties`:

```properties
sendium.message.pending.backend=file
sendium.message.pending.path=/work/spool
sendium.message.router-queue.backend=memory
sendium.message.routed-work.backend=memory
```

For Docker, add these arguments to your normal JVM or native deployment, preserving its configuration,
ports and PostgreSQL settings:

```sh
--mount type=volume,source=sendium-pending,target=/work/spool \
-e SENDIUM_MESSAGE_PENDING_BACKEND=file \
-e SENDIUM_MESSAGE_PENDING_PATH=/work/spool \
-e SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND=memory \
-e SENDIUM_MESSAGE_ROUTED_WORK_BACKEND=memory
```

Create/provision the volume explicitly and ensure the runtime UID can create, link, read and delete
files/directories there. Restrict access: the spool contains SMS content, addressing and return metadata.
CBOR is an encoding, not encryption; protect the underlying disk/volume and backups accordingly.
Use a writable local filesystem with working hard links and exclusive file locks. Linux/ext4 and native
container behavior were verified; shared/network filesystems and multi-writer use are not supported
deployment guarantees. Do not use an ephemeral container layer, an in-memory mount, or a new empty
volume on every replacement. A volume still needs its own retention/backup policy and survives only the
failures its storage platform actually tolerates.

Run **one owner per spool**. A second process must fail with an ownership conflict. Never delete or
replace `owner.lock` to bypass a conflict; that can allow different processes to lock different inodes.
Verify the old process/container has exited and investigate the mount/owner instead.

## Bounds and capacity

- Pending capacity defaults to **10,000 retained sources**, including each multipart duplicate and sources
  already selected, routed or awaiting required handoffs. It is not a byte quota or free-disk reservation.
  Recovered sources are not discarded when capacity is lowered; new admission is blocked while full.
- Selection/recovery batch size defaults to **100**. Ordinary selection traverses accepted directories
  directly with persistent cursors, at most three directory streams and a **1,024-step** per-call scan
  budget. Three in-memory waiting counts skip known-empty priorities, and runtime active-source
  ownership prevents reselection for memory selected. File-selected ownership is resolved through disk
  references rather than a backlog-sized runtime source map. Accepted buckets are streamed, not sorted or
  limited to 128 records. Only the remaining derived reference indexes have **128-entry** leaf bounds.
- Selection prefers priorities **3, 2, 1**. It resumes within a priority rather than repeatedly choosing
  the smallest UUID; there is no FIFO promise or instantaneous preemption for concurrent admissions.
  A budget-limited scan may return no work before exhaustion; later selection calls continue the cursor.
- File multipart recovery uses one poller (100 ms interval) and disk deadlines, not one timer/payload
  map per group. It registers source-only readiness. Selection loads at most one group's original
  payloads at a time (protocol total at most 255), only after router capacity is available.
  Duplicate ownership sets and selected execution metadata still grow with owned handles, bounded by
  retained-source capacity; this is not a constant-byte-memory claim.
- Startup validates/reindexes all committed sources; its time and filesystem metadata cost grow with
  backlog. Payload sizes, disk bytes/inodes and filesystem latency need operational headroom. Watch
  storage metrics and startup duration; do not infer an unmeasured throughput or startup-time SLO.
- Ordered administrative `sourcesAfter` pages retain only the requested number of IDs but may scan the
  accepted tree for each page. File startup and ordinary selection do not repeatedly use that API.

See [configuration defaults](09-configuration-reference.md#outbound-message-storage-profile) and
[readiness/metrics](14-outbound-storage.md#storage-readiness-and-metrics). Disk/permission/index failures
can leave committed records retained while readiness/admission fail closed. Capacity rejection does not
itself mean corrupt storage. Diagnose the typed failure; do not make the service ready by deleting data.

## Stopped-instance backup and restore

1. Fence new HTTP/SMPP submissions. Stop the owning process/container and verify it has exited.
   Do not copy a live spool or use the presence/absence of the lock file to infer process ownership.
2. Back up the **entire spool root**, including `format`, `accepted`, `selected`, `routed`,
   `routed-terminal`, `terminal` and `closed-groups`.
   Preserve ownership, permissions and contents. Including `derived/` is safe; startup rebuilds it.
   Store the backup outside the spool, encrypt/protect it and record the application version/configuration.
3. Back up PostgreSQL DLR state under its separate operational procedure. There is no atomic joint
   filesystem/PostgreSQL snapshot: coordinate the recovery point and reconcile provider/DLR ambiguity.
4. Restore into a separate, empty destination while all potential owners are stopped. Do not merge
   source directories from different backups/runs, edit records, or overlay an active spool.
5. Restore suitable runtime ownership/permissions and start one format-compatible file-profile instance
   against that destination. Require `sendium-sms-storage` readiness and inspect recovery diagnostics
   before reopening ingress. Expect reconstruction of unowned sources and restoration of configured
   file stages. Memory routing repeats routing; file routing resumes recorded destinations.
   Provider submission can still repeat before committed terminal processing.

A successful restore replays retained work, not an exact external-provider history. Unknown-version or
corrupt committed data must be investigated from the preserved backup; there is no automatic migration,
quarantine or memory fallback. A plain restore of an older backup can replay messages completed since
that backup. Never test a restored copy against a live provider unintentionally.

## Rollback or switching back to memory

Changing the backend selector does **not** migrate or drain retained files. Choose one safe route:

- **Drain first:** fence new ingress while the current service can still process work. Allow complete/
  expired multipart work, provider outcomes and required handoffs to finish. Stop the owner, then verify
   there are no committed accepted, selected, routed or routed-terminal records and no unfinished
   cleanup intents. Zero accepted sources alone does not prove routed forget finished.
   Retain a backup and the separate PostgreSQL
  DLR state before switching. An empty pending spool does not mean PostgreSQL's downstream DLR queue
  is empty.
- **Preserve for later recovery:** stop the owner and preserve the full spool plus compatible application
  version/configuration. A temporary memory deployment must use its own lifecycle/storage; it cannot
  recover these files. Returning to the retained file spool can repeat earlier provider submissions.

Do not roll an unreadable spool forward by deleting committed files, and do not start an older binary
against an unsupported spool format. Reusing a preserved spool later requires an explicit reconciliation
plan; it is not a transparent code-only rollback.

An older binary that cannot read format 3 needs its own empty spool after draining; an empty format-3
directory does not become compatible with it merely because its message counts are zero.

In particular, `file/file/memory` is operationally one-way while selected records remain. Reopening the
same spool as `file/memory/memory` fails with `selectedRecovery|error|reason:outstandingSelectedWork|fallback:none`.
Do not bypass this check by deleting selected files or editing the format marker. If draining is blocked
by unavailable workers or required handoffs, preserve the compatible binary, configuration and entire
spool for later resumption. A separate memory deployment does not transfer or consume the preserved work.

Likewise, opening a spool with routed assignments or terminal facts under memory routing fails with
`routedRecovery|error|reason:outstandingRoutedWork|fallback:none`. This includes records waiting only
for final cleanup. A downgrade from file selected to memory selected is also rejected while selected
records remain, even if routed records currently suppress their scheduling.

## Verification and remaining limits

Tests cover immutable source ownership, folder bounds, codecs/corruption, permissions/capacity/locking,
multipart duplicates/deadlines, publication and terminal replay, shared recovery, protocol acknowledgement
ordering, readiness and bounded-label metrics. Forced child-JVM tests kill the writer without cleanup at
admission/selection/routing/provider-handoff boundaries. Live JVM/native container smokes use a retained
named volume and real HTTP/SMPP/PostgreSQL DLR fixtures, including a multipart group completed after
replacement. These are not power-loss or live-carrier exactly-once tests.

Selected/routed tests cover restored identities/payloads, multipart ownership exclusion, required-handoff
failure, startup readiness, bounded windows/metric labels and profile downgrades.
`ForcedStageRestartTest` exercises selection, routing, authorized partial cleanup and provider ambiguity;
`FileStageFaultTest` covers failed publication and a 140-record backlog reopened with capacity two.
The live matrix passed all five profiles on freshly built JVM and native images, including retained-volume
stage-identity probes and dispatch under changed routing for file-routed profiles. Provider observations
are simulator-side checks, not live-carrier or exactly-once guarantees. The four file profiles use retained
spool volumes; the memory case checks independent PostgreSQL DLR correlation/restart, not outbound memory durability.

The performance workflow can select either pending backend independently of JVM/native and HTTP/SMPP;
its [results record the profile](../performance/workflow/README.md#sendium-build-and-runtime-details).
Local deployment rehearsals verify that path, not AWS throughput conclusions. Copied execution remains
unsupported under the [stage contract](https://github.com/cytechmobile/sendium/issues/344), rather than
silently dropping branches.

### Measured t4g.small examples, not a capacity guarantee

User-run JVM/HTTP tests of commit `12c9442` used `file/memory/memory`, DLRs, an unlimited worker TPS
cap, 120 seconds of warm-up and 600 seconds of measurement. Both ran on `t4g.small` in Standard
credit mode and completed every accepted message and DLR without rejection, failure or timeout:

| Offered rate | Accepted/completed/DLRs | ACK p99 | Provider response completion p99 | Drain |
|---|---:|---:|---:|---:|
| [100/sec](https://github.com/cytechmobile/sendium/actions/runs/43851583336) | 60,000 each | 23.87 ms | 119.04 ms | 2.38 s |
| [200/sec](https://github.com/cytechmobile/sendium/actions/runs/43861573021) | 120,000 each | 28.16 ms | 123.78 ms | 1.67 s |

Neither run showed growing provider backlog. At 100/sec the observed Sendium credit balances rose;
at 200/sec they fell (2.23, 0.90, 0.38 in coarse samples). A complete five-minute bucket at 200/sec
used 3.33 credits versus the instance's 2-credit earnings for that duration. Therefore **200/sec was
demonstrated for ten minutes, not established as long-term credit-neutral throughput**. The user chose
not to extend this experiment. These results do not isolate filesystem-operation savings from other
changes or establish multipart/SMPP/native throughput, a maximum rate, or a 300/sec guarantee.
