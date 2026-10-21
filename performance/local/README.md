# AWS-like local deployment rehearsal

This is Task 5.5's required functional/lifecycle rehearsal before AWS validation.
It builds and runs Sendium and the harness on **separate Amazon Linux 2023
containers**, PostgreSQL 17 with certificate-verified TLS, and real local S3 and
Secrets Manager APIs. It uses production staging, compatibility resolution,
CloudFormation creation/polling, SSM controller, node installation/supervision,
sampling, diagnostic recovery, cleanup, expiry and report publication code through
an explicitly local transport adapter.

## One command

Requirements: Git, Python 3.9+, a Linux-container Docker daemon with
`volume-subpath` support (Docker 27+; verified on 28.5.2), and internet access for
public build images, Maven dependencies and Temurin downloads. About 6 GiB of
Docker memory and several GiB of free disk are recommended. Java, Maven, AWS CLI
and AWS credentials are **not** required on the host.

From the repository root:

```sh
python performance/local/rehearse.py --output local-load-results
```

Choose a **new** output directory beneath an existing parent. The launcher sends
only the required source files to Docker, excludes Git/build-target directories
and records the base Git SHA plus a working-tree fingerprint. Application and
harness compilation happens in a separate Temurin 25 build stage, without host
credential/home mounts. Images remain cached for subsequent runs. The default
runs are **HTTP with DLR and SMPP with DLR only**. They use the exact AWS workload:
**30 msg/s, worker TPS 0 (unlimited),
120-second warm-up, 600-second measurement, up to 120-second drain, and provider
delays 5/1000 ms**. Each offers 18,000 measured submissions after 3,600 warm-up
messages; allow roughly half an hour for both. Cold downloads/slower hosts can take
longer. `--scenarios load` is an alias for these same two DLR-enabled runs.
No-DLR cases run only when explicitly named, or when explicitly requesting the
entire matrix with `--scenarios all`.

```sh
python performance/local/rehearse.py --scenarios http-dlr,smpp-dlr --offered-rate 100 --output <new-output-directory>
```

An explicit `--offered-rate 100` changes only that run's offered rate, retaining
unlimited workers and all shared phase/delay settings. The current
[average-rate generator validation](AVERAGE_RATE_VALIDATION.md) records the
production-style Guava pacing policy and DLR-enabled 100/30-msg/s HTTP/SMPP runs,
including per-second input and downstream queueing evidence. The 30-msg/s runs
are regression checks of the shared default; the 100-msg/s runs validate the
requested higher-rate workload. Further workload validation uses DLR enabled.

Useful focused invocation:

```sh
python performance/local/rehearse.py --scenarios http-dlr,bad-tls,concurrent --output focused-results
```

`--skip-build` explicitly reuses existing `sendium-local:node` and
`sendium-local:control` images; their recorded build context, not later source
edits, is what runs. Leave it unset after code changes.

## Topology and execution boundaries

### Selected-branch regression comparisons

`build_application.py <git-ref>` builds only the committed application source and
inherits an existing control image's harness, runtime settings and preset. Use
`--expected-harness-sha256` to require the retained baseline generator, choose a
separate `--output-image`, then run `rehearse.py --skip-build --control-image <image>`.
Application and controller revisions are recorded separately. `compare_load.py`
checks matching workload, artifact/runtime identities and node/database settings
before comparing retained DLR-enabled reports. Keep branch-specific comparison
reports alongside their generated artifacts outside the repository.

```sh
python performance/local/build_application.py <git-ref> --base-image sendium-local:control --output-image sendium-local:candidate --expected-harness-sha256 <baseline-harness-sha256>
python performance/local/rehearse.py --scenarios http-dlr,smpp-dlr --offered-rate 100 --skip-build --control-image sendium-local:candidate --output <new-output-directory> --detach
python performance/local/rehearse.py --resume-session <printed-session> --output <same-output-directory>
python performance/local/compare_load.py <baseline-directory> <candidate-directory> --output <new-comparison.json>
```

### Node topology

```text
Local controller (Docker socket; only fixture service credentials)
    |-- LocalStack S3 + Secrets Manager, shared fixture APIs
    |-- private per-run management network: nodes → fixture APIs
    |-- private per-run traffic network: harness ↔ Sendium
    `-- private per-run database network: Sendium → PostgreSQL

Sendium AL2023 node: 2 CPUs / 2 GiB cgroup limit / 512 MiB JVM heap
Harness AL2023 node: 2 CPUs / 2 GiB cgroup limit / 512 MiB JVM heap
PostgreSQL 17: TLS required, SAN db, separate ephemeral data/certificate volumes
Publisher: no network, Docker socket, fixture credentials or private node inputs
```

No service ports are published to the host. Nodes are on internal Docker networks
and cannot reach the database network from the harness. Separate runs have
separate traffic/database/management networks. The fixture object service attaches
to each management network. Each node uses the production AWS CLI transport and
verified S3 bytes; a small local CLI adapter forces fixture endpoints and models
the expected runtime prefix/secret scope. The controller alone gets the Docker
socket to manage session-labeled resources. Application/harness containers do not.

The AL2023 node has no preinstalled JRE: the production installer downloads it
from staged local objects, verifies bytes and expands the exact Temurin Java 25
archive. Both node roles use one pinned release/build. PKI/passwords are generated
per run, kept in private volumes/files and excluded from exported artifacts.
Plaintext PostgreSQL connections are rejected. Positive cases observe actual TLS
connections, while `bad-tls` checks wrong-CA rejection before the failed application
readiness/benchmark outcome.

The publisher receives only curated reports/logs/evidence, a verified harness
build and the public JRE archive through a volume subpath. It has no access to the
controller's private state volume root, database passwords, PKI keys, AWS/fixture
credential environment or Docker management socket. It performs real offline JVM
finalization and schema/independent semantic validation.

## Matrix and assertions

| Scenario | Expected result |
|---|---|
| `http-dlr`, `http-no-dlr`, `smpp-dlr`, `smpp-no-dlr` | 18,000 measured submissions at 30/s for 600 seconds after 120 seconds warm-up; unlimited workers and identical AWS delays. Inspect completion, minute rates and backlog in `load-stability.json`. |
| `database-unavailable` | Stop PostgreSQL before application supervision; failed setup/reporting and successful cleanup. |
| `bad-tls` | Wrong trusted CA rejected by real TLS verification; failed setup/reporting and successful cleanup. |
| `app-crash` | Kill the actual JVM after measurement starts; preserve available observations/diagnostics and fail the pipeline. |
| `interrupted` | SIGTERM interrupts the actual controller during measurement; production handlers stop/retrieve and the pipeline fails. |
| `partial-stage` | Fail after recovery metadata/partial uploads; remove the prefix without needing a stack. |
| `partial-create` | Fail after real database/one-node creation; recover partial resources and retained inputs. |
| `abandoned` | Lose the modeled runner after creation; execute the real expiry sweeper with an advanced clock, confirming pending then deleted resources/prefix/multipart upload. |
| `concurrent` | Run a second live workload, deny cross-run inputs/traffic, clean the first without stopping the second or deleting its prefix, then finish and clean the witness. |

Every case runs idempotent cleanup again and asserts no remaining run-labeled
container/network/volume or object prefix. Reports pass versioned schemas and
independent accounting/window semantics. Exported artifacts are scanned for known
database/protocol passwords. An expected injected failure has pipeline exit 1
inside its report; the **rehearsal command exits 0** only when every expected
outcome and cleanup assertion passed.

## Artifacts and recovery

The output contains `rehearsal.json`, `session-cleanup.json`, and per-scenario
`results.json`, `summary.md`, `workflow-outcome.json`, available resource/credit
evidence and allowlisted diagnostic logs. The final host cleanup inventories
session-labeled containers/networks/volumes and records zero remaining objects.
Assertion failures preserve already-public diagnostics before teardown as well.
The final export filter redacts any detected known fixture values and fails the
rehearsal rather than copying those values to the host, including failure evidence.
Images/build cache are retained; unrelated Docker resources are never pruned.

If the attaching host process is lost, the controller can continue in Docker.
Find its name with:

```sh
docker ps -a --filter label=sendium.local.session --format '{{.Names}}'
python performance/local/rehearse.py --resume-session <timestamp-hex> --output recovered-results
```

Use the session suffix from `sendium-local-control-<session>`. Resume verifies the
ownership label, waits for completion, copies public artifacts and cleans only
that session. It never removes containers or volumes merely because their names
look similar.

## What is real versus modeled

**Real:** separate OS/process/container state, cgroup limits, Temurin builds and
JREs, exact artifact bytes/checksums, S3/Secrets API storage, TLS authentication,
PostgreSQL-backed DLR, HTTP/SMPP traffic, process sampling, signal handling,
report finalization, object/multipart deletion and physical Docker teardown.

**Modeled:** EC2/AMI/RDS/CloudFormation metadata and control-plane transitions,
SSM delivery via Docker exec, systemd via a bounded watchdog, runtime IAM CLI scope,
EventBridge's scheduled clock and instance/volume identifiers. LocalStack is used
for its actual S3/Secrets services; it is not assumed to provision EC2/RDS or run
SSM agents like AWS. The CLI wrapper is not an IAM security boundary against
hostile root code. Port-level security groups, IMDS, AWS-managed endpoints/TLS,
IAM/OIDC admission and cancellation scheduling remain AWS checks.

Reports use `region=local`, `execution=container-jvm`, explicit simulation notes,
`local.small` / `db.local.small` fixture classes and a working-tree fingerprint.
They are **functional rehearsal evidence, not AWS capacity findings**. gp3,
encryption and AMI metadata are simulated; host `/proc` CPU/memory describes the
Docker Linux VM even though each node is cgroup-constrained. No burst-credit model
is claimed. AWS presets/defaults remain unchanged. Test ARM64/native AWS behavior
and actual service authorization/lifecycle in Task 6.1 after this rehearsal.

## Lightweight checks

```sh
python -m unittest discover -s performance/local -p 'test_*.py'
```

These cover endpoint/scope confinement, exact secret selection, ownership-only
session cleanup, source-context boundaries and failed-export redaction. They supplement, rather than
replace, the real Docker matrix.

See [VERIFICATION.md](VERIFICATION.md) for the completed matrix and remaining AWS
checks.

## Git policy

`performance/local/` contains reusable test source, Docker definitions, adapters
and documentation; keep it versioned so another developer or CI can reproduce
the rehearsal. Generated outputs belong outside that source directory. The
default root folders `local-load-results/` and `local-rehearsal-results/`, and the
documented `local-no-dlr-results/`, are ignored by Git. Docker state is kept in
session-owned containers/volumes and removed by cleanup. Custom output folders
should be outside the checkout or explicitly ignored; reports, keys, runtime
archives and transient state are not test source.
