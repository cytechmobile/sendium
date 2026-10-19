# Protected AWS execution

The `aws-execution` job consumes verified build inputs through `run_aws.py`. It
checks out the exact maintained controller SHA, installs dependencies and downloads
artifacts before assuming AWS credentials. Application/harness builds remain on
separate unprivileged GitHub-hosted runners.

## Protected environment configuration

Install/update [bootstrap](../infrastructure/README.md) and its
[independent expiry package](../deployment/EXPIRY.md). The updated orchestration
policy includes the regional `ec2:DescribeVpcEndpointServices` preflight read.
Configure environment **`sendium-performance`** with maintainer-controlled access:

| Environment variable | Value |
|---|---|
| `AWS_REGION` | Explicit benchmark/bootstrap region; no inferred default |
| `AWS_ORCHESTRATOR_ROLE_ARN` | Bootstrap output `OrchestratorRoleArn` |
| `AWS_BOOTSTRAP_STACK` | Installed bootstrap stack name |
| `AWS_DIAGNOSTICS_ROLE_ARN` | Bootstrap output `DiagnosticsRoleArn`, used by the separate read-only job |

The job requests `id-token: write` and `contents: read`, then assumes the configured
role using GitHub OIDC. Its 10,800-second session matches the bootstrap role limit
and 180-minute execution-job timeout. No permanent AWS keys or application build
secrets are supplied. Trust remains bound to the exact repository/environment
subject; build jobs have neither an environment nor an OIDC grant.

## Resolution and deployment

1. Re-normalize the dispatch from GitHub context and the pinned controller preset.
   Reject altered source/settings/request artifacts. Recheck all four downloaded
   binaries/manifests and compare them to the independently consolidated inputs.
   Downloaded application/harness artifacts are data, never executable tools here.
2. Match bootstrap repository/environment/TTL/role/region and require the exact
   assumed orchestration-role identity. Verify live expiry configuration. Require
   an unversioned artifact bucket and an absent root name: create-only, not reuse.
3. Resolve EC2 architecture, memory, on-demand/EBS/HVM/boot support and AZ offerings.
   Resolve exact compatible AL2023 AMIs from public SSM parameters or preset pins,
   and take root size from the single EBS mapping. Defaults remain `t4g.small`
   each and the AMI minimum, generally 8 GiB; known burstable types use Standard.
4. Resolve the latest available numeric PostgreSQL 17 minor, or validate a preset
   pin. Require that exact version/class (`db.t4g.small` by default) to support
   encrypted 20 GiB gp3. Do not silently downgrade an unavailable engine version.
   Intersect both EC2, RDS and required interface-endpoint AZs. Choose the first
   common AZ and another available regional AZ for the database subnet group;
   resolve the AWS-owned regional IPv4 S3 prefix list.
5. Resolve Temurin Java 25 Linux JRE metadata through Adoptium, pinning one exact
   release/build across architectures. Fetch once for identical architectures.
   Verify official SHA-256/size during bounded HTTPS download. Fetch the official
   RDS global PEM CA bundle and record its SHA-256. Approved hosts/redirects and
   byte/time budgets are checked; archives are not extracted or executed here.
6. Select matching application/harness builds. Persist `cleanup-plan.json`, exact
   `resolution.json` and local deployment inputs. Stage immutable bytes, reviewed
   root/network templates and the first-write recovery marker. Root parameters/
   tags use the exact staging expiry, not a newly computed deadline.
7. Call `CreateStack` with the exact service role, ownership/identity/expiry tags,
   run-derived idempotency token and immutable root-template URL. Creation has
   the preset's 45-minute bound and rolls back on failure. Poll the returned ARN,
   checking tags/role/identity; fail on rollback/timeout before attempting traffic.
8. Invoke the existing SSM controller for runtime installation, Sendium-local
   secret/config generation, readiness, phases, sampling and retrieval. EC2 roles
   receive no orchestration session/infrastructure privileges. Exact image,
   storage, database, Java/CA and source-resolution metadata enter report context.

Regional availability is a preflight observation, not a capacity reservation.
Launch-time capacity/authorization failures remain explicit setup outcomes.

## Evidence and failure handling

`controller-outcome.json` records last phase, exit code and sanitized issues.
Provisioning failures preserve available stack events and attempt exact-key
output retrieval. Event exports omit `ResourceProperties` and unstructured
provider messages, preventing dynamic secret inputs from entering artifacts.

An `always()` upload preserves an explicit allowlist under
`performance-controller-<runId>-<attempt>`:

```text
controller-outcome.json
cleanup-plan.json
staged.json
resolution.json
stack-creation.json
stack-events.json
execution/deployment-outcome.json
execution/retrieved/**
retrieved/**
```

Retrieval paths are already allowlisted by the deployment helper. Runtime
downloads, private node configuration and AWS session credentials are not
uploaded. Retention is 30 days. A provisioning failure may have controller
evidence without a harness report; a completed harness report remains resource-
inconclusive until diagnostic evidence is finalized separately.

The [reporting and cleanup jobs](REPORTING.md) perform read-only credit collection,
unprivileged finalization and immediate cleanup for success/failure/cancellation.
Independent expiry is also required before provisioning. Task 5.5's complete local
rehearsal remains required before AWS validation in 6.1.

## Offline verification

```sh
python -m pip install -r performance/workflow/requirements-dev.txt
python -m unittest discover -s performance/workflow -p 'test_*.py'
actionlint .github/workflows/performance-tests.yml
```

AWS metadata/creation/SSM are substituted. Checks cover numeric engine selection,
mixed architectures, AMI-minimum storage, endpoint AZ intersections, unavailable
settings, pagination bounds, real runtime-metadata formats, checksum/source
failures, exact staging expiry/template parameter alignment, assumed-role gating,
altered build inputs and failure retrieval. Hosted OIDC, IAM, capacity, SSM and
resource lifecycle behavior are verified separately after the local rehearsal.
