# Private AWS deployment and execution

`deploy.py` runs in the protected controller with AWS CLI v2 and bootstrap's
orchestration role. It stages bytes and controls SSM; it never starts application,
harness, configuration-generation or report JVM code in that credential context.
`node.py` runs on each private Linux EC2 under its bounded runtime instance role.
The Python scripts use the standard library except for the controller's existing
JSON-schema dependency:

```sh
python -m pip install -r performance/contracts/requirements-dev.txt
```

## 1. Stage exact artifacts before provisioning

Prepare `plan.json` with real selected/build revisions and SHA-256 digests:

```json
{
  "schemaVersion": 1,
  "region": "eu-west-1",
  "repository": "cytechmobile/sendium",
  "bootstrapStack": "sendium-performance-bootstrap",
  "runStack": "sendium-perf-run-gh-12345-1",
  "runIdentity": "gh-12345-1",
  "provenance": {
    "branch": "selected-branch",
    "applicationCommit": "<exact application SHA>",
    "harnessRevision": "<exact harness SHA>",
    "workflowRunUrl": "https://github.com/cytechmobile/sendium/actions/runs/12345"
  },
  "artifacts": {
    "application": {"path": "application.tar.gz", "sha256": "<SHA-256>"},
    "harness": {"path": "harness.jar", "sha256": "<SHA-256>"},
    "sendiumJre": {"path": "sendium-jre.tar.gz", "sha256": "<SHA-256>"},
    "harnessJre": {"path": "harness-jre.tar.gz", "sha256": "<SHA-256>"},
    "rdsCa": {"path": "global-bundle.pem", "sha256": "<SHA-256>"}
  },
  "workload": {"ingressMode": "http", "dlrEnabled": true}
}
```

Artifact paths are relative to the invoking directory. Workload overrides merge
with the preset and pass the existing workload schema and compatibility/budget
checks. Each input must be nonempty, match its declared digest, and fit the 1 GiB
per-object transport limit. V1 network/ports, runtime paths and fixed database
engine/storage/CA settings must match the reviewed templates; a custom preset
cannot silently change those deployment contracts. The application archive must contain the **complete**
`quarkus-app/` directory, not just `quarkus-run.jar`. For example, package with
`tar -czf application.tar.gz -C sendium-app/target quarkus-app` in the unprivileged
build job. Each Java 25 Linux JRE archive must have one top-level runtime directory
containing `bin/java`, matching its node's resolved architecture. Runtime and RDS CA
downloads/checksums are resolved before staging, outside the private VPC.

```sh
python performance/deployment/deploy.py stage \
  --plan plan.json \
  --preset performance/presets/aws-private-jvm-v1.json \
  --output staged.json
```

The controller verifies the bootstrap repository/namespace and matching TTL,
writes an immutable `control/run.json` expiry marker before large inputs, then uploads run-scoped
artifacts, trusted node/transport/sampler scripts, the reviewed network template,
and the effective preset. All input writes use `If-None-Match: *`: a duplicate run
cannot silently replace bytes already selected for execution. Local paths are
removed from `staged.json`; exact keys, sizes and digests remain. Use a new workflow
attempt/identity after partial staging or an execution attempt.

Use the exact `expiresAt` emitted in `staged.json` for the root stack parameter and
tag. It is also in the recovery marker, so abandoned staging can expire without a
stack. Do not extend the deadline by recomputing it after artifact upload.

Create `run.yaml` with the resolved parameters and tags described in
[`../infrastructure/README.md`](../infrastructure/README.md). Its network child URL
now exists at the staged run key. AMI/engine/JRE resolution and protected stack
creation are handled by the [protected workflow controller](../workflow/AWS_EXECUTION.md); this command does not
provision or infer a default AWS region.

## 2. Execute a create-complete run

```sh
python performance/deployment/deploy.py execute \
  --staged staged.json --output execution-artifacts
```

The output directory must be new. Execution checks the bootstrap's installed
expiry flag, exact root-stack role/ownership, identity/expiry outputs, sufficient
remaining TTL within the preset bound, distinct private running instances,
supported architecture/memory, IMDSv2, encrypted gp3/delete-on-termination root
disks, and private/available primary-AZ PostgreSQL 17 with minimum storage and no
backups. Observed instance/AMI/root-volume/database details enter the public
execution context. An `ExpiryHandlerInstalled=false` bootstrap intentionally
blocks execution until [independent expiry is installed](EXPIRY.md); offline
staging can run earlier. Preflight also verifies live function/code/role/TTL,
enabled schedule/target and invocation permission, rather than trusting the flag.

The controller then:

1. Waits up to five minutes for both Linux SSM Agents (3.3.40.0 or newer) online.
2. Installs both nodes using `AWS-RunShellScript`. Bootstrap commands contain only
   identifiers, exact S3 keys and digests. Scripts/documents are checksum-verified
   before use, as are every node's downloaded artifact. No secret values are in
   SSM arguments or controller output.
3. Safely expands bounded archives, reserves 512 MiB of remaining disk capacity,
   discovers/checks Java 25, and stages the RDS CA at the preset's path. Private
   run/install trees are new and root-only; existing trees are not overwritten.
4. On Sendium only, reads the exact database secret and invokes `generate-config`
   locally. It retains the application/environment bundle locally and publishes
   only the three harness configuration files to its permitted one-way input
   prefix. The generated JDBC URL uses `sslmode=verify-full` and the staged CA.
5. Starts a bounded systemd harness supervisor, waits for `listenersReady`, then
   starts the Sendium supervisor. Neither JVM inherits AWS environment credentials
   or externally supplied application/JVM option overrides. Both use the declared
   preset JVM arguments. Node roles still provide bounded runtime access via IMDS;
   neither role has provisioning or `SendCommand` privileges.
6. Lets the existing harness enforce HTTP/PostgreSQL/provider/SMPP readiness and
   warm-up/measurement/drain. The harness owns local sampling; Sendium's supervisor
   owns remote sampling. During measurement the supervisor polls local state;
   it does not continuously upload status over S3.
7. Stops requested supervisors on completion/failure/interruption, allowing the
   remote sampler's final complete interval before stopping Sendium. Systemd also
   bounds abandoned execution; independent resource deletion remains separate.
8. Retrieves every available allowlisted public output, even when setup, scenario,
   or another source's retrieval fails.

Supervisors retain a bounded 4 MiB process-log tail and redact known database and
protocol credentials before uploading it. No recursive working-directory upload
is used. Private generated configuration and database environment files never
enter the public output prefixes. Failed scenarios retain their failed reports
and available logs; failures before a harness report exists retain deployment
outcomes/command IDs and whatever evidence is available.

`deployment-outcome.json` records controller issues, per-node SSM command IDs,
retrieved/missing files and exit code. Exit **0** means the harness completed with
exit 0 and deployment/retrieval checks succeeded; exit **1** means setup,
execution, stop confirmation, or required evidence retrieval failed. The harness
report remains authoritative for valid/inconclusive/failed workload classification.
Initial AWS reports are normally resource-inconclusive until remote and credit
evidence are merged offline. A successful stack or controller exit is not a claim
of sustainable sending capacity.

SIGINT/SIGTERM trigger best-effort stop/retrieval. Hard runner/host loss cannot
guarantee this path. Use the cleanup command below for stack/artifact deletion;
independent abandoned-run expiry uses the [installed sweeper](EXPIRY.md). The execution command does not
delete the stack or artifacts.

## 3. Retrieve again and finalize without AWS privileges

To retry retrieval, use another new output directory:

```sh
python performance/deployment/deploy.py retrieve \
  --staged staged.json --output retrieved-again
```

Retrieval uses exact public keys, preserving successes if other files are absent.
Its `retrieval.json` lists missing evidence explicitly; absent samples are null in
`resource-inputs.json`. For the automatic execution retrieval these files live
under `execution-artifacts/retrieved/`:

```text
harness/results.json, summary.md, state.json, lifecycle.jsonl,
        harness-resources.jsonl, process.log, node-status.json
sendium/sendium-resources.jsonl, process.log, node-status.json
resource-inputs.json
retrieval.json
```

Run `../resources/collect_cpu_credits.py` under the **separate diagnostics role**
after measurement. Read the measurement UTC origin and exact duration from the
retrieved report; preserve collector issues and absent/lagging credit points.
Set `resource-inputs.json`'s `cpuCredits` to that file's relative path. A separate
unprivileged publishing job invokes `finalize-report` as documented in
[`../harness/EXECUTION.md`](../harness/EXECUTION.md), then validates report schemas
and independent accounting semantics. The workflow enforces these job/artifact boundaries;
the protected controller never invokes finalization code. The implemented
[reporting jobs](../workflow/REPORTING.md) wire diagnostic collection, unprivileged
finalization and immediate cleanup in that order.

## 4. Clean up a completed, failed or cancelled run

Retrieve and preserve available reports first, then run cleanup under bootstrap's
orchestration role after provisioning/execution producers have stopped:

```sh
python performance/deployment/cleanup.py \
  --run staged.json --output cleanup-outcome.json --timeout-seconds 1800
```

The **original `plan.json` also works**, even if staging failed before `staged.json`
was written or no stack was created. Cleanup needs only region, bootstrap/root
stack names, repository and run identity; it does not require local build
artifacts, preset/schema packages, execution outputs, a healthy application, or
the installed expiry flag. The output file must be new and its parent must exist.
Apply the updated bootstrap policy before using this helper: cleanup additionally
reads bucket versioning and lists multipart uploads on the exact artifact bucket.
Object delete/abort permissions remain limited to `runs/*`; no bucket deletion or
object-version deletion permission is added.

Cleanup follows this order:

1. Resolve the exact bucket, run namespace and CloudFormation service role from
   the specified bootstrap. Validate the requested root stack's name, account,
   region, managed-by/repository/run-identity tags and attached role. A nested
   stack is never treated as a cleanup root.
2. Request deletion by the verified stack **ARN**, reusing its attached service
   role. CloudFormation handles dependency ordering: EC2/RDS dependents before
   the network child, and the database before its secret. Creating/rolling-back
   stacks may be deleted directly or observed until a busy operation settles.
   Existing deletion is observed; `DELETE_FAILED` is retried with at most three
   accepted delete requests. No force deletion, retained-resource flags, or
   ad-hoc EC2/RDS/IAM deletion is used.
3. Confirm deletion/absence before any S3 cleanup. Generic AWS/authentication
   errors are not interpreted as absence. Exact missing-stack responses are
   confirmed twice; after observing a stack ARN, recheck the name to detect a
   replacement. A still-present or unverified stack keeps its temporary artifacts.
4. Abort incomplete multipart uploads and delete completed objects only beneath
   `runs/<RunIdentity>/`. Re-list the first page after each mutation rather than
   following continuation markers that can skip shifted entries. Validate every
   returned key against the exact prefix; preserve other attempts/runs. Large
   batch requests use temporary JSON parameter files to avoid OS argument limits.
5. Verify that neither objects nor multipart uploads remain. The bootstrap's
   retained, unversioned bucket stays installed. An already absent bucket/prefix
   is clean; an enabled/suspended versioning configuration is reported as
   unsupported rather than declaring delete markers to be erased artifacts.

All polling, retries and CLI requests share a total deadline of at most 1,800
seconds. Stop signals record an interrupted outcome where possible; accepted
CloudFormation deletion continues server-side even if the client exits. A second
cleanup invocation can resume it. A duplicate cleanup can observe another
invocation's completed stack/object/upload deletion safely. Always use a new run
attempt for new provisioning; cleanup does not provide a lock against a producer
reusing the same identity while deletion is in progress.

`cleanup-outcome.json` includes `stackDeleted`, `artifactsDeleted`, the observed
stack ARN/status, acknowledged deletion counters, issue codes and `exitCode`.
Exit **0** requires both stack and artifacts confirmed gone with no recorded
issues. Exit **1** records timeout, interruption, ownership/configuration mismatch,
AWS rejection, partial object deletion or an unconfirmed resource/artifact state.
Known AWS error codes are retained; raw stderr and credentials are not published.

For a failed cleanup, inspect the outcome and CloudFormation stack events, resolve
the reported cause, and rerun the **same plan/identity** with a new outcome path.
If stack deletion fails/times out, its S3 inputs/evidence are retained. If only S3
cleanup fails, the outcome distinguishes the deleted stack from remaining data.
The one-day S3 lifecycle remains a fallback for temporary objects/incomplete
uploads; the [independent sweeper](EXPIRY.md) recovers abandoned expired runs.

The workflow's dedicated cleanup job invokes the same engine after success,
failure or cancellation, preserving outcomes even when another job failed. Keep the
bootstrap service/control roles installed until all run cleanup has completed.

## Verification

```sh
python -m unittest discover -s performance/deployment -p 'test_*.py'
```

Offline checks cover tampered artifacts, immutable staging, actual-resource
preflight, missing expiry, private metadata separation, SSM payloads, scenario
failure stop/retrieval, unavailable evidence, inherited credentials/options,
bounded extraction and changed downloads. Existing infrastructure policy tests
also verify that node object/secret grants fit the runtime boundary.

Cleanup/transport checks additionally cover absent and partially created stacks,
busy operations, failed/repeated/concurrent deletion, same-name replacement,
ownership/role/account isolation, authentication failures, bounded timeouts,
multi-page object/upload deletion, partial failures and retry, interrupted cleanup,
wrong-prefix responses, versioning drift, outcome-file behavior and large batches.
These use a stateful AWS substitute; the complete local rehearsal is Task 5.5,
followed by real AWS lifecycle/authorization validation in Task 6.1.

Opt-in Linux verification uses built application/harness artifacts and a disposable
PostgreSQL 17 container. It substitutes a local object/secret transport, but runs
actual archive installation, configuration generation, application/harness JVMs,
samplers, supervision, publication and secret checks. It covers HTTP with DLR,
SMPP without DLR, and an application-readiness failure that must still publish a
failed JSON/Markdown report. It uses fixture credentials/local PostgreSQL without
TLS; AWS TLS, ARM64 binaries, SSM/systemd and IAM/endpoint behavior require Task 6.1.

```sh
docker build -f performance/deployment/Dockerfile.verification \
  -t sendium:node-deployment-verification performance/deployment
docker network create sendium-node-verification-352
docker run -d --name sendium-node-postgres-352 --network sendium-node-verification-352 \
  -e POSTGRES_PASSWORD=node-test-password postgres:17-alpine
docker run --rm --network sendium-node-verification-352 \
  --mount "type=bind,source=$PWD,target=/workspace,readonly" \
  -e POSTGRES_HOST=sendium-node-postgres-352 sendium:node-deployment-verification
docker rm -f sendium-node-postgres-352
docker network rm sendium-node-verification-352
```
## Kannel AWS deployment profile

Prepared deployment plans can select `benchmarkTarget: "kannel"`. The shared
CloudFormation topology, run-scoped IAM roles, private transport and cleanup
remain the same: gateway and harness use `t4g.small`, with PostgreSQL 17 on RDS.
Sendium remains the default when the target field is absent. Workflow dispatch
selection is a separate integration step.

Kannel plans require HTTP ingress, `provenance.applicationCommit: null`, and a
`kannelBuild` object from the runtime bundle's `build-metadata.json`. The controller
validates the reviewed archive/patch identity, PostgreSQL support and resolved
gateway architecture before staging. Artifacts are `application` (Kannel runtime
archive), `harness`, `harnessJre`, and `rdsCa`; the C gateway needs no JRE.

Build the pinned baseline for the selected architecture, then export its runtime:

```sh
docker build --platform linux/arm64 --file performance/kannel/Dockerfile.baseline --tag sendium-kannel-baseline:arm64 performance/kannel
docker build --platform linux/arm64 --file performance/kannel/Dockerfile.aws --build-arg KANNEL_BASE_IMAGE=sendium-kannel-baseline:arm64 --target export --output type=local,dest=kannel-runtime .
```

The export contains `kannel.tar.gz` and `build-metadata.json`. It includes binaries,
non-glibc shared libraries (including libpq), configuration/supervision helpers,
schema, licenses and a per-file manifest. The runtime uses the AL2023 node's glibc
and loader. No package downloads or compilation occur on the private gateway.
The `verification` Docker target tests installation and version probes on a
minimal AL2023 image; prefer native ARM builders for Graviton packaging.

The gateway retrieves its database secret, connects with libpq
`sslmode=verify-full`, and initializes `dlr.sql`. Credentials are kept off the
harness except for its own simulator/HTTP fixture passwords. The supervisor
checks PostgreSQL 17, TLS and schema, starts bearerbox/smsbox and establishes the
group sampler baseline. Only then does it publish public `gateway-ready.json`
through `inputs/harness/generated/`. The harness atomically receives that marker
and verifies run/process/database identities before workload phases, including
zero-warm-up runs.

Existing transport-role paths and CloudFormation outputs retain the name
`sendium`; on a Kannel run this is the gateway **role**, not the measured software.
Reports use `configuration.application.runtime: kannel`, `infrastructure.kannel`
and `resources.kannel`. Retrieval writes `gatewaySamples` pointing at
`sendium/kannel-resources.jsonl` for offline finalization. RDS metrics remain
required for AWS observations. The message spool is run-local and ephemeral;
the shared PostgreSQL engine does not imply equal recovery/durability semantics.

`performance/kannel/verify_aws_nodes.py` rehearses the actual install/configure/
supervise/retrieve code with local artifact/secret transport, a real packaged
gateway, the Java harness and TLS-enabled PostgreSQL 17. Its `node-verification`
image target requires the local harness verification image
`sendium-local:kannel-postgresql-verification`. Run it on an internal network
with PostgreSQL reachable as `db`, database `kannel_benchmark`, user `kannel_perf`,
and the ephemeral PKI from `performance/local/tls.py` mounted at `/tls` on both
containers. The database must use `/tls/password`, the server certificate/key and
TLS-only HBA file. It must be fresh: schema initialization deliberately rejects an
existing table. `KANNEL_NODE_EVIDENCE` optionally retains public outputs in a new
directory. The rehearsal checks 15 measured messages/DLRs, readiness handoff,
group samples, finalization, redaction and shutdown. It models transport only;
it does not verify AWS APIs, IAM/SSM behavior or Graviton performance.
