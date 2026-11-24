# DLR Persistence

Sendium stores the state needed to correlate upstream delivery receipts (DLRs) and track terminal HTTP/SMPP delivery after an upstream provider outcome. PostgreSQL is the durable default. A single instance can instead use [local file storage](#local-file-storage), [process-local memory](#memory-and-disabled-handling), or explicit disabled handling.

This storage boundary does not make Sendium's message queues or all delivery processing durable. Review [Durability Boundaries](#durability-boundaries) before using restart recovery as a delivery guarantee.

## Application Boundary

The standalone `sendium-app` enables the DLR subsystem and requires its startup-selected handling policy to initialize successfully. `sendium.dlr.storage.backend` defaults to `postgresql`; `file`, `memory` and `disabled` are explicit alternatives. There is no automatic fallback. Applications that embed `sendium-core` default to no Sendium-owned DLR subsystem and can run without a DLR database.

The `sendium.dlr.persistence.enabled` build-time property controls this boundary. `sendium-core` leaves it undefined and an undefined property means disabled, so an embedding application opts in by declaring it as `true` itself before Quarkus augmentation. When it is not enabled, the DLR services, PostgreSQL datasource, Flyway migration, and storage readiness check are absent; partial or no-op persistence is not provided.

Message paths degrade rather than fail when the subsystem is absent. HTTP and downstream SMPP submissions are accepted and routed without gateway DLR state, undelivered downstream receipts fall back to the worker's in-memory retry, and provider receipts are not correlated, so Sendium emits no delivery receipts of its own. An application that embeds `sendium-core` without this subsystem is expected to supply its own `Tracker` and message store if it needs delivery receipts.

## Storage Model

The SQL rows below describe the durable backends. Memory mode uses bounded process-local ownership instead; it reserves capacity at ingress, before acceptance, rather than waiting for a provider response.

`sendium_dlr.dlr_message` contains one row per tracked gateway UUID. A row is created only when the upstream provider accepts or rejects the submission, never at HTTP or downstream SMPP ingress. It holds body-free return metadata, the provider outcome, the downstream delivery channel and status, the retry schedule, and the monotonically increasing attempt number used for fencing. SMS message bodies are never stored in this schema.

`sendium_dlr.provider_correlation` maps the exact `(provider_name, provider_message_id)` pair to the gateway UUID after provider acceptance. Multiple provider correlations, including multipart provider IDs, can point to one message. Provider rejection creates the terminal `dlr_message` row directly and does not create a correlation. Terminal resolution stores the resolving provider pair and outcome in `dlr_message`, removes every correlation for that gateway message, and retains the message row for HTTP or SMPP delivery.

The local file backend stores the same body-free records and provider correlations in one SQLite database inside its owned directory.

## Local File Storage

### Settings

Select file storage once at startup; there is no runtime switching and no fallback to another backend:

| Property | Default | Purpose |
| :--- | :--- | :--- |
| `sendium.dlr.storage.backend` | `postgresql` | Startup-only handling: `postgresql`, `file`, `memory` or `disabled`. Unknown values fail startup. |
| `sendium.dlr.storage.file.path` | None | Required for `file`. A directory owned exclusively by one Sendium process. It holds the database, its rollback journal and an ownership lock. |
| `sendium.dlr.storage.file.failed-retention` | `7D` | Positive time a receipt whose downstream delivery finally failed is kept, measured from that failure. |

The usual environment-variable mapping applies, for example `SENDIUM_DLR_STORAGE_BACKEND=file` and `SENDIUM_DLR_STORAGE_FILE_PATH=/var/lib/sendium/dlr`.

In file mode the PostgreSQL datasource and Flyway migration stay inactive and need no connection settings. Explicitly activating the PostgreSQL datasource while selecting `file` fails startup. Sendium opens one database connection itself and does not create a second datasource or pool.

### Deploying without a database service

This example runs the durable `file/file/file` outbound profile with file DLR storage and no PostgreSQL service. Each store owns its own directory on one retained volume. Add the arguments to your normal JVM or native deployment, keeping its configuration mounts and ports, and omit every `SENDIUM_DLR_POSTGRESQL_*` setting:

```sh
docker volume create sendium-data
docker run -d --name sendium \
  --mount type=volume,source=sendium-data,target=/work/data \
  -e SENDIUM_MESSAGE_PENDING_BACKEND=file \
  -e SENDIUM_MESSAGE_PENDING_PATH=/work/data/spool \
  -e SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND=file \
  -e SENDIUM_MESSAGE_ROUTED_WORK_BACKEND=file \
  -e SENDIUM_DLR_STORAGE_BACKEND=file \
  -e SENDIUM_DLR_STORAGE_FILE_PATH=/work/data/dlr \
  ...your configuration mounts, ports and image...
```

The two paths must be siblings; nesting one inside the other fails startup. File DLR storage works with every supported outbound profile, including the memory default, and does not make volatile outbound stages durable. Keep the volume across container replacements; a new empty volume starts with no receipt state. Wait for readiness to report `backend=file` before sending traffic.

The SQLite driver extracts its native library into the Java temporary directory and loads it from there. If `/tmp` is mounted `noexec`, for example a Docker `--tmpfs` with default options, startup fails with a native-library error. Use an executable temporary directory, or point the driver at one with `-Dorg.sqlite.tmpdir=<directory>`.

The directory contains `dlr.db`, `owner.lock` and, while a transaction is open or after a crash, `dlr.db-journal`. Records are body-free but include callback URLs and addresses, so restrict access to the volume and its backups. Run one Sendium process per directory; a second owner fails with `fileDlrOpen|error|reason:ownershipConflict`. Never delete `owner.lock` or the journal to get past an error: the journal is needed to roll back an interrupted transaction.

### Durability and recovery

Startup fails without changing existing data when:

- the path is missing or overlaps (equals, contains or lies inside) the `file` pending SMS path, `sendium.message.pending.path`;
- another process owns the directory;
- the directory is read-only;
- the database is empty, belongs to another application, has an unsupported schema version or is missing required tables or indexes;
- SQLite reports that the database is corrupt.

Sendium never resets, repairs or upgrades an existing database automatically. Use a local filesystem; network filesystems are not supported.

Each acknowledged storage operation commits one SQLite transaction with a rollback journal and full synchronization before success is reported. After a process crash or restart, committed provider correlations, pending deliveries, attempt numbers and delivery claims are recovered; uncommitted work is rolled back. Claims still expire after `sendium.dlr.delivery-claim-duration`.

> **Durability notice.** Transactional restart recovery does not protect against storage corruption. Damage to the database or journal from disk, filesystem or host faults, out-of-band edits, or an incomplete copy may be reported as corruption, but it is not guaranteed to be detected. Detected corruption stops startup without repair; undetected damage can lose or alter receipt state. Recover from a backup copied while Sendium was stopped, accepting the loss of later changes. Use PostgreSQL for critical or high-volume DLR production, where its operated backups and replication apply.

### File retention

Pending, retrying and claimed deliveries, and receipts still waiting for a provider outcome, are never deleted by age. A successfully delivered receipt is deleted immediately. A receipt whose delivery has finally failed is deleted after `failed-retention`; storage activity triggers cleanup at most once per hour, in transactions of up to 1,000 records. Deleted space is reused, but the file is not shrunk. Allow disk headroom for the highest expected backlog.

Expiry of failed receipts is one-way. Only a backup taken before the deletion can bring them back, together with the loss of everything that changed after it.

### Diagnosing failures

If a write cannot be committed or rolled back cleanly, the file store becomes unavailable until Sendium is restarted; readiness then reports `DOWN` with `backend=file` and `reason=unavailable`. Liveness is not affected, so an orchestrator stops routing traffic without restarting the process in a loop. The outcome of the failed write is uncertain: it may or may not have been committed. A provider outcome that could not be stored is not resubmitted, and a provider receipt that could not be stored is answered with `STATUS_SYSERR` so the provider retries it. Fix the cause, such as disk space or permissions, then restart; startup rolls back any interrupted transaction from its journal.

Health data and metric labels contain only the backend and a sanitized reason. Details stay in the application log, which uses these events:

| Log event | Meaning |
| :--- | :--- |
| `dlrStorage\|selected\|backend:file\|path:...\|failedRetention:...` | File storage opened at startup. |
| `dlrStorage\|error\|backend:file\|reason:missingPath` | No `sendium.dlr.storage.file.path` is configured. |
| `dlrStorage\|error\|backend:file\|reason:overlapsPendingPath` | The DLR directory equals, contains or lies inside the pending SMS path. |
| `dlrStorage\|error\|backend:file\|reason:postgresqlDatasourceActive` | The PostgreSQL datasource was explicitly activated while selecting `file`. |
| `dlrStorage\|error\|backend:...\|reason:unknownBackend` or `unsupported` | The selector is not a supported backend. |
| `fileDlrOpen\|error\|reason:ownershipConflict` | Another process owns the directory. |
| `fileDlrOpen\|error\|reason:foreignDatabase`, `unsupportedSchemaVersion`, `missingSchemaObject`, `emptyDatabaseFile` | The database is not a compatible Sendium DLR store. Use a compatible binary or restore a backup. |
| `fileDlrOpen\|error\|reason:sqliteCode26` or `sqliteCode11` | SQLite reports the file is not a database or is corrupt. Restore a stopped backup. |
| `fileDlrStorage\|error\|operation:...\|reason:sqliteCode13` | The disk is full. The store stays unavailable until restart. |
| `fileDlrRetention\|error\|reason:...` | A cleanup run failed. A storage failure also makes the store unavailable. |
| `dlrStorageReadiness\|error\|backend:file\|reason:unavailable` | Readiness found the store unavailable. |

### Stopped backup and restore

Only a stopped store can be backed up; copying files from a running process can produce an inconsistent copy that is not reliably detected.

1. Fence new HTTP and SMPP submissions, stop the Sendium process or container, and verify it has exited.
2. Copy the whole DLR directory, including `dlr.db-journal` if present, to storage outside the volume. Preserve permissions, protect the copy, and record the application version and configuration.
3. If you also use a file outbound spool, back it up in the same stopped window; see [stopped-instance backup](16-filesystem-pending-storage.md#stopped-instance-backup-and-restore). There is no joint snapshot with a PostgreSQL database.
4. To restore, stop every potential owner and copy the backup into a new, empty directory. Never merge it into an existing DLR directory or edit its files.
5. Start one instance with a binary that supports the backup's schema, pointing `sendium.dlr.storage.file.path` at the restored directory. Wait for readiness to report `backend=file` before reopening traffic.

A restore brings back the pending receipts, provider correlations, attempt numbers, delivery claims and failure times recorded at backup time. Anything after the backup is lost: provider receipts for later submissions cannot be correlated, and receipts already delivered downstream since the backup can be delivered again. Claims recorded in the backup still hold until they expire. Sendium has no online backup command and does not repair a damaged database.

### Upgrades, rollback and switching backends

Changing the backend does not migrate data in either direction. Switching starts with whatever the selected backend already holds. Follow the [drain-before-change procedure](#changing-handling-or-storage-modes) before switching; preserve the whole file directory for a later return. Merely stopping does not drain pending obligations. The current schema version is `1`. A binary refuses a database with a schema version it does not support, and Sendium never upgrades or downgrades a database automatically, so keep a compatible binary with a preserved directory.

## Memory and disabled handling

These are startup selections of the enabled DLR subsystem, not aliases for build-time exclusion.

| Selection | New DLR requests | Restart | Readiness |
| :--- | :--- | :--- | :--- |
| `postgresql` (default) | Accepted subject to ordinary admission | Committed correlations and deliveries retained | DOWN on storage failure |
| `file` | Accepted subject to ordinary admission | Committed state retained on the same volume | DOWN on storage failure |
| `memory` | Reserve capacity before acceptance; full/recovering requests receive HTTP 503 or SMPP `STATUS_THROTTLED` | All DLR state lost | UP when operational, including full/recovering capacity |
| `disabled` | Native/Kannel HTTP 400; SMPP `STATUS_SUBMITFAIL` | No DLR state maintained | UP when intentionally disabled |
| Build-time subsystem excluded | Legacy paths without gateway DLR tracking; not disabled-mode rejection | No Sendium-owned DLR state | No DLR storage check |

Initialization or internal storage failure is not an intentional disabled state: it fails startup/readiness without selecting another backend. Memory/disabled require no `SENDIUM_DLR_POSTGRESQL_*` settings and no DLR file volume. Explicitly activating the PostgreSQL datasource with a non-PostgreSQL selection is an error. Outbound SMS storage is configured independently.

### Memory settings and accounting

| Property | Environment variable | Default |
| :--- | :--- | :--- |
| `sendium.dlr.storage.backend` | `SENDIUM_DLR_STORAGE_BACKEND` | `postgresql` |
| `sendium.dlr.storage.memory.max-tracked-submissions` | `SENDIUM_DLR_STORAGE_MEMORY_MAX_TRACKED_SUBMISSIONS` | `100000` |
| `sendium.dlr.storage.memory.provider-correlation-ttl` | `SENDIUM_DLR_STORAGE_MEMORY_PROVIDER_CORRELATION_TTL` | `3D` |
| `sendium.dlr.storage.memory.pending-delivery-ttl` | `SENDIUM_DLR_STORAGE_MEMORY_PENDING_DELIVERY_TTL` | `7D` |
| `sendium.dlr.storage.memory.cleanup-interval` | `SENDIUM_DLR_STORAGE_MEMORY_CLEANUP_INTERVAL` | `1M` |

Run one process; replicas cannot share or recover this DLR state. Each accepted DLR-requesting ingress submission reserves one slot, including **each SMPP multipart part** and each separately accepted duplicate. Provider-side splits, submission retries and callback attempts add no slots. The entry limit is not a hard heap/RSS bound: provider fan-out, metadata, the SMS pipeline and JVM/native overhead also consume memory.

Reservations remain held while provider work, required receipt delivery/retries, or uncertain cleanup still require ownership. Successful downstream acknowledgement releases completed state once outbound ownership is also finished. A disconnected SMPP recipient can reconnect within the pending-delivery lifetime; a live HTTP receiver is retried using the [configured interval](#http-callback-retry-interval).

Correlation lifetime starts at its original provider acceptance; repeated acceptance handoffs do not refresh it. Terminal receipt lifetime starts at resolution/rejection; retrying or claiming delivery does not extend it. Expiry deliberately loses the ability to correlate or deliver the expired receipt, but cannot free capacity still needed by active outbound work. Bounded cleanup runs at the configured interval; expiry is not an exact-time delivery guarantee.

`sendium_dlr_storage_capacity_used{backend="memory"}` and `sendium_dlr_storage_capacity_maximum{backend="memory"}` show reserved ingress submissions and the configured limit. Observe these together with provider work, receipt state and storage errors, not just ingress queue depth. See the [measured capacity evidence](#measured-capacity-evidence); configured entries are not a heap/RSS or AWS-host-fit guarantee.

### Measured capacity evidence

The packaged JVM application reached the **100,000-submission default** within `-Xms512m -Xmx512m -XX:+UseG1GC` on Linux/amd64, Eclipse Adoptium OpenJDK `25.0.5+8-LTS` (2026-11-24, application source `7fbfbf5`). The fixture retained 99,000 unresolved provider correlations, 500 HTTP retry obligations and 500 disconnected SMPP receipts. SMS stages drained between bounded ingress batches; they did not need to hold 100,000 ordinary SMS bodies simultaneously.

| Live observation | Heap used bytes | Process RSS KiB |
| :--- | ---: | ---: |
| Baseline | 43,237,504 | 246,292 |
| 100,000 occupied slots | 224,264,592 | 875,040 |
| Fully delivered, zero occupied slots | 504,548,288 | 968,856 |

HTTP/SMPP overload was rejected, non-DLR traffic reached the provider while full, and actual receipt delivery drained capacity to zero. Cumulative observed GC pauses increased from 5 to 32, with total pause time increasing from 0.030 to 0.382 seconds. Heap values are live samples, not post-GC retained-memory measurements: garbage produced during draining can leave a high heap reading after ownership reaches zero. No OOM or progress assertion failed, and no fixed headroom percentage was assumed.

The separate isolated DLR fixture measured approximately 141 MiB incremental retained heap at 100,000 sources, using longer metadata and provider fan-out; it is not a substitute for the packaged result. Neither fixture is a worst-case workload bound. In particular, application RSS exceeds the Java heap, and this Docker/amd64 measurement does **not** qualify total host fit on the ARM/2 GiB AWS `t4g.small`.

Reproduce the full application proof with the [existing packaged smoke runner](../sendium-core/src/test/java/utils/NativeE2eSmoke.java), setting `SENDIUM_E2E_IMAGE` to a built JVM image and `SENDIUM_E2E_DLR_BACKEND=memory`, then running:

```sh
./mvnw -B -pl sendium-core -DskipTests test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.classpathScope=test -Dexec.mainClass=utils.NativeE2eSmoke
```

The `memoryCapacityAtDefault` scenario requires actual occupancy, overload rejection, non-DLR progress, network acknowledgements and final cleanup, not merely a successful process exit. Missing evidence or a failed assertion leaves the default unqualified for that run; do not silently raise the heap or lower capacity to obtain a pass.

### Accepted SMS recovery and disabled backlog

With file-backed outbound SMS, startup restores accepted DLR reservations before opening ingress when they all fit. With a reduced limit, accepted but unprotected obligations take priority over new DLR requests. Recovered groups acquire capacity atomically; an oversized group stays retained and requires an operator to restore sufficient capacity. Priority protects reservations; it does **not** require draining all old receipts before spare capacity can admit new work.

Disabled handling retains previously accepted durable DLR-requesting SMS without provider submission. Re-enable handling to resume it; do not delete it to unblock the queue. Eligible non-DLR SMS remains dispatchable, including beyond a blocked file prefetch window. Memory-full and disabled rejection do not by themselves reject non-DLR ingress.

### Changing handling or storage modes

No mode switch migrates or deletes durable backend data. There is no blanket startup prohibition on a retained SMS backlog, but changing modes with pending work can lose receipts or leave accepted SMS blocked.

Before changing DLR handling, capacity, or outbound storage modes:

1. Pause/fence new HTTP and SMPP ingress.
2. Drain **all** pending, selected/router-queue and routed SMS stages, including multipart groups.
3. Wait for in-flight provider submissions and their acceptance/correlation handoffs to finish.
4. Resolve or explicitly account for unresolved provider correlations and pending HTTP/SMPP deliveries, retries and active claims. Restore disconnected recipients where necessary.
5. Verify these states are empty, then stop, preserve durable stores, change configuration and restart. Verify the selected backend/readiness before reopening ingress.

An empty ingress queue or a stopped process does not demonstrate a drain. Reverting binaries or configuration cannot recover memory reservations, correlations, callback retries or disconnected-client receipts after process loss. If an emergency change accepts outstanding receipt loss, record that explicitly; preserving a file SMS volume preserves SMS work, not its previous memory DLR state.

## Quick Start PostgreSQL

The generated Quick Start runtime creates:

- A private `postgres:17-alpine` service with no published database port.
- A named Docker volume for `/var/lib/postgresql/data`.
- A generated 256-bit database password in `.sendium.env`, with mode `600` where the filesystem can enforce Unix permissions.
- A PostgreSQL health check that gates Sendium startup.
- A readiness check that verifies the DLR schema is available.

Run Quick Start normally:

```bash
sh quick-start.sh
```

`docker compose down` removes the containers and network but retains the PostgreSQL volume. Do not use `docker compose down --volumes` or manually delete the volume unless permanent database deletion is intended.

Quick Start preserves the local database password during `--force` regeneration, including while an external database is temporarily selected. PostgreSQL initialization variables cannot rotate the password of a role that already exists in a persistent data volume. If the saved local password is lost, Quick Start fails with instructions to delete both the volume and its generated Compose marker before a new password is generated.

## Upgrade From MVStore Builds

Older Sendium builds could store DLR state in `data/dlr-mvstore.db`. Current builds do not read or import that file. Before upgrading an MVStore-configured runtime, stop accepting submissions and allow pending provider correlations and terminal deliveries to drain, or explicitly accept that the remaining state will be unavailable after the upgrade. Stop Sendium and preserve the old file before starting the PostgreSQL-only build.

Provision PostgreSQL and require readiness to report `UP` with `backend=postgresql` before reopening traffic. State written to PostgreSQL is not available to an older MVStore build if the application is later downgraded.

## External PostgreSQL

To omit the local PostgreSQL service and connect Sendium to an operator-managed database, first export `SENDIUM_DLR_POSTGRESQL_PASSWORD` from an access-controlled secret source without placing its value in shell history. Then provide the URL and username when generating the runtime:

```bash
SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://db.example.com:5432/sendium?sslmode=verify-full' \
SENDIUM_DLR_POSTGRESQL_USERNAME='sendium' \
  sh quick-start.sh --directory sendium --provider local

unset SENDIUM_DLR_POSTGRESQL_PASSWORD
```

The three values are an all-or-nothing override. Partial configuration is rejected instead of mixing local and external settings.

For a manual deployment, supply a valid connection URL and the settings required by the database authentication method:

| Variable | Required value or example | Purpose |
| :--- | :--- | :--- |
| `SENDIUM_DLR_POSTGRESQL_JDBC_URL` | `jdbc:postgresql://db.example.com:5432/sendium` | JDBC connection URL. Add PostgreSQL JDBC TLS parameters for external networks. |
| `SENDIUM_DLR_POSTGRESQL_USERNAME` | `sendium` | Database role used by Sendium and Flyway when required by the authentication method. |
| `SENDIUM_DLR_POSTGRESQL_PASSWORD` | Secret value | Database password when required. Supply it through an access-controlled environment or secret mechanism. |

The database role must be able to connect to the database and create and manage the `sendium_dlr` schema. Flyway creates or validates the schema during startup. Keep migration privileges available for future application upgrades.

Use TLS with certificate verification when the database connection crosses an untrusted network. The exact JDBC parameters and certificate path depend on the PostgreSQL service. Protect database credentials from shell history, logs, source control, screenshots, and unauthorized container inspection.

## Pool Settings

These optional settings retain their shown defaults:

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `SENDIUM_DLR_POSTGRESQL_POOL_MIN_SIZE` | `0` | Minimum number of datasource connections. |
| `SENDIUM_DLR_POSTGRESQL_POOL_MAX_SIZE` | `10` | Maximum number of datasource connections. |
| `SENDIUM_DLR_POSTGRESQL_ACQUISITION_TIMEOUT` | `5S` | Maximum wait for a pooled connection. |

Size the pool against measured gateway concurrency and the database connection budget. Do not increase it beyond the database's safe connection capacity.

## Delivery Claim Settings

`SENDIUM_DLR_DELIVERY_CLAIM_DURATION` controls how long an active HTTP or SMPP delivery claim excludes other Sendium replicas. It defaults to `5M`. Configure a duration longer than the longest downstream delivery operation; an expired claim can be acquired by another replica while the original sender is still running.

## Provider Outcome And Receipt Batching

The standard tracker queues provider acceptance/rejection outcomes and terminal
receipts for a single background persistence processor. Events are processed in
enqueue order and share a transaction commit. SMPP network threads do not wait
for that database work. A matched terminal provider receipt receives a successful
`deliver_sm_resp` only after its resolution transaction commits; a failed batch
or exhausted queue produces `STATUS_SYSERR` so the provider can retry.

| Property | Environment variable | Default | Purpose |
| :--- | :--- | :--- | :--- |
| `sendium.dlr.batch.size` | `SENDIUM_DLR_BATCH_SIZE` | `100` | Maximum provider events per transaction, from 1 to 1,000. Acceptance, rejection and receipt events share the limit; this is not a count of complete SMS lifecycles. |
| `sendium.dlr.batch.max-delay` | `SENDIUM_DLR_BATCH_MAX_DELAY` | `500ms` | Maximum collection wait starting from the first event's enqueue time. A full batch flushes earlier. Must be positive. |
| `sendium.dlr.batch.queue-capacity` | `SENDIUM_DLR_BATCH_QUEUE_CAPACITY` | `10000` | Positive bound on queued, persisting and completing events together. Admission fails immediately when this capacity is exhausted. |

The collection delay is not an end-to-end latency bound: existing backlog,
database locks, database processing and protocol writes can add waiting time.
Configure provider request timeouts accordingly. Response writes run separately
from persistence, with their pending work included in the same capacity bound.

Each batch acquires distinct provider-correlation advisory locks in hash-key
order, locks affected existing message rows in UUID order, then reads correlation
owners using a fresh statement snapshot after any row-lock waits. This protects
provider-ID reuse and multipart correlation changes without a separate lock and
lookup sequence for every receipt. Events are then applied in order and committed
together. A SQL failure rolls back the entire batch. PostgreSQL deadlocks
(`40P01`) retry the rolled-back batch up to three total attempts; other failures
are returned without retrying an ambiguous commit or resending the SMS. An acceptance attempting to
overwrite an already-terminal row fails that event without undoing unrelated
valid events in the batch.

Queued events remain process-local until commit. A crash in this interval can
lose provider-outcome metadata, and a provider success cannot be rejected or
recovered by resending the SMS. Queue rejection or persistence failure is logged;
the original SMS is not automatically resent. A receipt already queued behind its
acceptance cannot overtake it; receipts handled by a different process before
the acceptance batch commits can still be unknown. The existing unknown-receipt
acknowledgement policy is unchanged; the in-memory batching interval can extend
that cross-process window.

Graceful destruction stops new admissions and flushes partial batches. Database
drain and response-completion drain each wait up to 30 seconds. A forced timeout
fails unfinished futures rather than reporting successful receipt persistence;
a late database commit can still leave a durable pending receipt for replay.
Custom embedded trackers keep their existing synchronous receipt handling unless
they explicitly opt into the asynchronous tracker methods.

## Startup And Monitoring

Check readiness rather than only checking whether the HTTP listener is open:

```bash
curl -fsS http://127.0.0.1:8080/q/health/ready
```

The `sendium-dlr-storage` readiness check reports the selected backend, `backend=postgresql` or `backend=file`. It returns `DOWN` with a sanitized `unavailable` reason when the PostgreSQL schema cannot be queried or the file store has become unavailable.

Inspect storage and datasource metrics with:

```bash
curl -fsS http://127.0.0.1:8080/q/metrics | grep -E 'sendium_dlr_storage|agroal'
```

Relevant metrics include storage-operation latency/counts tagged by backend, operation, and success or error outcome. Standard-tracker batched persistence uses operation `provider_batch`: its count is batch operations, not individual messages. `sendium.dlr.batch.pending` measures admitted events still queued, persisting or completing; `sendium.dlr.batch.rejected` counts capacity/shutdown admission rejections. The `sendium.dlr.batch.events` distribution records the number of events in each attempted batch. PostgreSQL pool metrics use the Agroal metric prefix.

The selected backend is required when the subsystem is enabled, and startup fails rather than selecting another backend. At runtime, HTTP and downstream SMPP ingress validation, acknowledgement, and routing do not write PostgreSQL and therefore are not blocked by a database outage. The DLR return metadata travels only with the in-memory outbound message until an upstream provider outcome. Readiness still reports `DOWN` while PostgreSQL is unavailable because new provider outcomes cannot be made durable.

Provider message IDs are correlated within the outbound provider namespace rather than globally. The worker instance name is the default namespace; workers connected to the same SMSC account can share `msg.hash.prefix` when that SMSC may deliver their receipts interchangeably. Different providers may therefore return the same message ID without overwriting each other's state. The namespace must remain stable while correlations are outstanding: changing `msg.hash.prefix` or renaming a worker using the default makes earlier receipts unresolvable.

Sendium requests final delivery receipts from upstream SMPP providers. A valid unsolicited `ACCEPTD` or `ENROUTE` receipt, including a receipt identified through its SMPP ESM class, is acknowledged successfully but is not forwarded and does not consume its provider correlation. The first terminal receipt consumes every correlation for the gateway message and produces the downstream DLR; later receipts for those provider message IDs cannot resolve it. Multipart submissions retain this first-terminal behavior and do not aggregate delivery states across every segment. A terminal persistence failure returns `deliver_sm_resp` with `STATUS_SYSERR` so the provider can retry. A successful provider acknowledgement confirms durable resolution only; it does not wait for downstream HTTP or SMPP delivery.

An accepted upstream `submit_sm_resp` with a provider message ID queues an atomic message-row/correlation write. It becomes durable when its batch commits. If admission or persistence fails, Sendium preserves the provider success and does not resend the SMS; the later DLR may be lost because it cannot be correlated. An accepted response without a usable provider message ID is also not persisted. A provider rejection queues terminal pending delivery directly so it can be returned downstream after commit without waiting for a provider receipt.

A terminal receipt remains in `sendium_dlr.dlr_message` while HTTP or SMPP delivery is pending or after HTTP delivery reaches terminal `FAILED` status. The delivery attempt number is a fencing token: a stale completion or failure cannot mutate a newer attempt. HTTP and SMPP use leased PostgreSQL claims across replicas.

### HTTP callback formats

Kannel submissions retain their GET callbacks, including `%I` (gateway message ID), `%F` (provider
message ID), and `%d` (Kannel outcome) URL substitution and receipt-mask filtering. Native
`/api/v1/messages` submissions use JSON POST callbacks independent of Kannel receipt masks:

```json
{
  "messageId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "delivered",
  "errorCode": null
}
```

`status` is `delivered` or `failed`, using the existing terminal provider-outcome mapping; provider
rejection also produces `failed`. `errorCode` carries the recorded provider error code, or null when
absent. Requests use `Content-Type: application/json`. The callback URL is used as supplied, without
Kannel placeholder substitution or callback-host preapproval. Receipt callbacks require DLR persistence
to be enabled. Both formats share the existing delivery policy: a 5-second request timeout, any 2xx
response completes the attempt, other responses/transport failures retry after the configured interval (one hour by default), and attempt
10 failure marks delivery `FAILED`. Redirects are not followed. Receivers must handle duplicate
callbacks idempotently by `messageId`.

The initial V1 schema includes `http_callback_format`, defaulting Kannel writers to `KANNEL_GET`;
native callbacks persist `JSON_POST`. The format survives provider-event batching, process restart,
and callback retries.

### HTTP callback retry interval

| Property | Environment variable | Default | Purpose |
| :--- | :--- | :--- | :--- |
| `sendium.dlr.http.retry-interval` | `SENDIUM_DLR_HTTP_RETRY_INTERVAL` | `1H` | Startup-configured delay before the next retry of a failed HTTP callback, for both Kannel GET and native JSON POST callbacks. |

For example, `SENDIUM_DLR_HTTP_RETRY_INTERVAL=5S` schedules subsequent retries five seconds after a failed attempt. The every-second dispatcher and in-flight work can add delay; this is not a guaranteed callback deadline. Short intervals increase callback traffic during receiver failures.

When HTTP callback processing is enabled, the interval must be positive, at least one millisecond, and representable as a future millisecond timestamp; invalid or overflowing values fail startup. Startup-disabled handling does not use this setting.

Changing the setting affects retry timestamps written after subsequent failures. It does not rewrite already scheduled receipt due times or reset attempts. The ten-attempt limit, five-second request timeout, per-host concurrency limits, attempt fencing and SMPP retry behavior are unchanged.

## Retention

This section describes PostgreSQL. File storage uses its own [retention policy](#file-retention).

The V1 retention thresholds are fixed application behavior, not environment settings:

| DLR lifecycle state | Eligible for cleanup after |
| :--- | :--- |
| Waiting for final provider DLR | 7 days from provider acceptance |
| Waiting to forward final DLR to the client | 7 days from receiving the final provider outcome |

Waiting for a final provider DLR includes both the message row and its provider-message correlations. These are separate database records with separate cleanup conditions, but both use the same seven-day threshold. After Sendium records a final provider DLR or provider rejection, it removes the correlations and retains the message row while forwarding the outcome to the HTTP or SMPP client. Successful client delivery deletes the row immediately; pending or failed forwarding remains eligible for cleanup seven days after provider resolution.

Cleanup is triggered by storage activity and runs no more than once per hour. These values are therefore eligibility thresholds, not exact physical deletion deadlines: idle records can remain in the database longer, and an active deployment can retain newly eligible state until the next cleanup pass. A provider receipt cannot be matched after its correlation has been removed. Making the thresholds or cleanup schedule configurable is outside the V1 storage replacement.

An HTTP row marked `FAILED` after attempt 10 remains eligible based on the original provider-resolution time, not the time of its final request. SMPP delivery has no fixed attempt cap, but a still-pending SMPP row is also eligible for cleanup seven days after resolution.

Cleanup is best-effort maintenance and is isolated from message handling. One caller at a time runs a pass while every other caller proceeds immediately, and a failed pass is logged and left until the next interval rather than rejecting the submission that triggered it.

## Durability Boundaries

The table describes PostgreSQL. File storage keeps the same ingress, batching, fencing and at-least-once boundaries for a single process, with the [durability notice](#durability-and-recovery) and [stopped backups](#stopped-backup-and-restore) in place of operated database backups.

| State or transition | PostgreSQL guarantee | Remaining limit |
| :--- | :--- | :--- |
| HTTP and downstream SMPP ingress | No PostgreSQL write occurs. Body-free DLR return metadata travels with the message through in-memory queues. Graceful shutdown fences protocol ingress, drains queued events, flushes multipart assembly, drains again, and then closes sessions. | A process crash can lose acknowledged or accepted outbound work because router and worker queues are in memory. |
| Gateway-to-provider message correlation | The `dlr_message` row and exact provider correlation are committed atomically after provider acceptance. Intermediate `ACCEPTD` and `ENROUTE` receipts leave the correlation intact. | Provider success is not retried if this write fails, so a later receipt may be unresolvable. Success without a provider ID is not tracked. The first terminal receipt consumes every correlation for the gateway message. |
| Provider-event batching | A matched terminal receipt is acknowledged successfully only after its batch commits. Events queued in acceptance/receipt order are processed in that order. | The pre-commit queue is in memory and bounded. A SQL failure rolls back the batch, capacity rejection fails immediately, and unpersisted provider outcomes cannot be reconstructed after a crash. |
| Provider submission rejection | A terminal pending-delivery row is committed directly without a correlation. | If this write fails, no durable downstream rejection exists. The outbound submission is not retried because the provider already returned an outcome. |
| Terminal HTTP/SMPP delivery | The common payload and exact provider outcome remain in one row until fenced completion. | Delivery is at-least-once; acknowledgement can be received before the final delete commits. |
| Active delivery attempt | The database attempt number fences stale completion, retry, and failure updates. HTTP batch claims and SMPP attempt starts use a database lease so active replicas cannot start the same delivery. | An attempt that outlives its lease can overlap a replacement. |
| Multipart submission | Segments and aggregate payloads remain in memory. After provider acceptance, the aggregate DLR row retains the original gateway part IDs needed for downstream receipt delivery. | Multipart assembly and its pending timers are process-local and are not reconstructed after restart. |
| HTTP DLR callback retry | Pending state, attempt count, next-attempt timestamp, and active lease are durable. Every second, the dispatcher checks for up to 500 due rows and atomically claims them with locked-row skipping. Batches do not overlap; each callback uses its own virtual thread, with at most 10 callbacks in flight per host. Failures retry after the configured interval (one hour by default) and attempt 10 failures become `FAILED`. | The batch limit is not a guaranteed delivery rate. A request accepted before a crash or failed completion update can be repeated. A running batch delays the next due-callback check, and an attempt that outlives its lease can overlap a replacement. |
| SMPP DLR delivery | One leased attempt covers every generated receipt part and completes only after matching successful `deliver_sm_resp` PDUs for all parts. Pending rows are enqueued when the same `system_id` binds. | Timeout, `generic_nack`, wrong/non-OK response, enqueue/send failure, or session closure releases the attempt. Replay is bind-driven rather than periodic, and partial success is not checkpointed. |
| Database files | The Quick Start named volume survives normal container replacement and `docker compose down`. | Volume deletion, host-disk loss, and disaster recovery require backups or external PostgreSQL replication managed by the operator. |

Downstream delivery uses bounded at-least-once attempt semantics, not exactly-once delivery. A crash or storage failure after an HTTP receiver accepts a callback, or after an SMPP client sends a successful `deliver_sm_resp`, can cause the receipt to be delivered again. Multipart SMPP replay can repeat already acknowledged parts. Consumers must be idempotent using the gateway or receipted message ID. HTTP retry limits, SMPP bind availability, and seven-day retention mean this is not an unlimited eventual-success guarantee.

These limits are intentional V1 boundaries. PostgreSQL provides provider-outcome correlation, DLR persistence, and delivery fencing; it is not outbound queue persistence, a general distributed worker coordinator, or a replacement for the router and worker queues.

## Related Documentation

- [Architecture Overview](01-architecture.md)
- [Docker Deployment](02-docker-deployment.md)
- [Monitoring And Observability](08-monitoring-observability.md)
- [Configuration Reference](09-configuration-reference.md)
- [Troubleshooting](10-troubleshooting.md)
