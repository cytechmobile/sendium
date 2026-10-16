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

The controller verifies the bootstrap repository/namespace, uploads run-scoped
artifacts, trusted node/transport/sampler scripts, the reviewed network template,
and the effective preset. All input writes use `If-None-Match: *`: a duplicate run
cannot silently replace bytes already selected for execution. Local paths are
removed from `staged.json`; exact keys, sizes and digests remain. Use a new workflow
attempt/identity after partial staging or an execution attempt.

Create `run.yaml` with the resolved parameters and tags described in
[`../infrastructure/README.md`](../infrastructure/README.md). Its network child URL
now exists at the staged run key. AMI/engine/JRE resolution and protected stack
creation are workflow integration inputs (Task 5.2); this command does not
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
blocks execution until Task 4.7 is operational; offline staging can run earlier.

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
guarantee this path. Stack/artifact cleanup is Task 4.6; independent abandoned-run
expiry is Task 4.7. This execution command does not delete the stack or artifacts.

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
and independent accounting semantics. Task 5.3 wires these job/artifact boundaries;
the protected controller never invokes finalization code.

## Verification

```sh
python -m unittest discover -s performance/deployment -p 'test_*.py'
```

Offline checks cover tampered artifacts, immutable staging, actual-resource
preflight, missing expiry, private metadata separation, SSM payloads, scenario
failure stop/retrieval, unavailable evidence, inherited credentials/options,
bounded extraction and changed downloads. Existing infrastructure policy tests
also verify that node object/secret grants fit the runtime boundary.

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
