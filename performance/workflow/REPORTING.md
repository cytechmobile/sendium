# Reporting, diagnostics and immediate cleanup

The manual workflow finishes with four separate jobs:

```text
aws-execution (orchestration role)
    → diagnostics (read-only diagnostics role)
    → cleanup (dedicated deletion-only role)
    → publish (no environment, OIDC or AWS credentials)
    → archive (dedicated archive upload/read role)
```

Diagnostics and cleanup use `always()` after a validated request and an attempted
AWS job. Publication also runs after build/setup failures when a valid normalized
request exists. Invalid dispatch inputs fail validation before any AWS execution;
they are not converted into an apparently valid workload/report.

## Configuration

Keep required review on `sendium-performance`, used only by provisioning/execution.
Create **`sendium-performance-recovery`** (bootstrap parameter
`GitHubRecoveryEnvironment`) with branch restrictions but **no required reviewers**.
Both recovery jobs use it, so neither evidence collection nor deletion waits for
another human approval after the run. Configure these environment variables:

| Variable | Value |
|---|---|
| `AWS_REGION` | Same region as the bootstrap/provisioning environment |
| `AWS_BOOTSTRAP_STACK` | Same bootstrap stack name |
| `AWS_DIAGNOSTICS_ROLE_ARN` | Bootstrap output `DiagnosticsRoleArn` |
| `AWS_CLEANUP_ROLE_ARN` | Bootstrap output `CleanupRoleArn` |
| `AWS_ARCHIVE_ROLE_ARN` | Bootstrap output `ArchiveRoleArn`; uploads only permanent evidence |

Apply the updated bootstrap before running the updated workflow. Diagnostics is
read-only, with EC2/CloudWatch reads, bootstrap outputs and `runs/*/outputs/*`
object reads. Cleanup can describe/delete owned run stacks and list/delete/abort
temporary run objects/uploads. It cannot create infrastructure, pass roles, issue
SSM commands, read private objects/secrets or modify bootstrap resources. The
provisioning role trusts only the approval-gated environment; it is not exposed to
the automatic recovery environment. The diagnostics role also retains its old
environment subject to allow existing workflow attempts to finish during migration.

Both recovery jobs pin the maintained controller SHA, download the normalized
request and install trusted dependencies before OIDC authentication. They
re-normalize the dispatch, verify the actual assumed role/repository/region and
derive the exact bucket/root name from bootstrap. Recovery does not depend on the
orchestration runner uploading a controller artifact successfully.

## Diagnostics before deletion

`recover.py diagnostics` retries retrieval of the exact public output keys under
the run prefix, preserving any successful source when another is unavailable.
It checks report provenance/accounting and confirms that both instance IDs carry
the current run's ownership tags before querying credit metadata/metrics. It
never executes a downloaded JAR or accesses private runtime inputs.

The query uses the report's UTC origin and exact observed nanosecond duration.
Python's microsecond timestamps expand outward by less than two microseconds to
cover Java's nanosecond window; CloudWatch periods still round outward to 300
seconds. Partial/coarse/lagging credit evidence and collection issues remain in
`cpu-credits.json`; missing values are never substituted with zero. No measurement
window means an explicit unavailable-window outcome, not an invented query.

`performance-diagnostics-<runId>-<attempt>` preserves available CPU credits,
diagnostic outcome and allowlisted retrieved files even if collection fails.
The diagnostic job has a 15-minute bound.

New AWS reports also require RDS evidence. Diagnostics collect the standard
CloudWatch database metrics before cleanup and preserve `rds-metrics.json`.
Publication renders the RDS summary and retains full raw and enriched JSON;
missing/partial required values remain inconclusive rather than becoming zeros.

## Permanent archive after publication

The archive job runs after publication, including failed publication when an
artifact is available. It packages public results before authentication, then uses
the separate archive role in the recovery environment to upload only to the
retained archive bucket. The publisher remains credential-free, and cleanup has
already completed. The archive job's summary records its verified permanent
manifest location or an explicit failure; its outcome artifact is separate from
the benchmark's original workflow outcome and validity. See [ARCHIVE.md](ARCHIVE.md)
for configuration, layout, checksums, retries and retrieval.

## Immediate cleanup

Cleanup waits for diagnostic recovery, including a failed/cancelled diagnostic
job, then uses a fresh deletion-only cleanup-role session. `recover.py cleanup` rebuilds
the minimal identity from the validated request/bootstrap and invokes the shared
cleanup engine. It handles success, failure, cancellation and partial provisioning
without requiring a healthy application or a previously uploaded plan.

The deletion budget remains 30 minutes; the job allows 40 minutes for checkout,
authentication and outcome upload as well. It verifies ownership, deletes the
root by ARN, waits for CloudFormation, then clears only that run's S3 prefix and
multipart uploads. Failed/partial cleanup is not reported as confirmed. The
`performance-cleanup-<runId>-<attempt>` artifact is preserved with `always()`.
The publisher waits for cleanup so report processing does not extend VM lifetime.

Immediate jobs are best effort under hard cancellation, runner loss or unavailable
environment credentials. Independent expiry remains the outside-GitHub recovery
path; keep its service role/schedule installed until all runs are clean.

## Unprivileged finalization and publication

The publisher has only `contents: read`, no protected environment/OIDC and no AWS
authentication action. It consumes available controller/diagnostic/cleanup
artifacts and verifies the maintained x86_64 harness build's exact
revision/attempt/architecture/size/SHA-256 before calling `finalize-report`.
The helper also refuses finalization when direct, profile/file, container or OIDC
credential-provider environment variables are present. Hosted-job isolation is
the primary boundary; the variable guard is an additional misuse check.

Report and evidence paths are built from fixed public filenames, not downloaded
`resource-inputs.json` paths. Both schema and independent accounting/window checks
run before/after finalization. Workload provenance/configuration, overall/minute
counts, rates, histograms, drain observations, timing metadata and non-resource
failure reasons (including their exact sequence) must remain unchanged. Only resource evidence/classification is
enriched; cleanup/pipeline outcomes are kept separately in `workflow-outcome.json`.

- A failed workload/accounting observation stays failed.
- Missing or incomplete resource evidence remains inconclusive, not zero.
- Resource-finalization failure preserves validated workload observations, adds
  an explicit incomplete-resource reason and fails publication.
- Missing/rejected reports produce a schema-valid **unavailable-evidence failure**:
  no intervals, null rates/percentiles, empty resource evidence and explicit
  limitations. Required zero counters are not displayed as measured throughput.
- Cleanup or diagnostic-job failure fails the pipeline independently of a valid
  performance observation; it does not rewrite measurements as failures.
- Expected overload, rejection or generator/resource inconclusiveness alone adds
  no arbitrary performance threshold gate.

The publisher appends overall, latency, one-minute cohort/event-time output,
resource/drain results and workflow/cleanup status to the Actions summary. Final
`performance-results-<runId>-<attempt>` artifacts retain for 30 days:

```text
results.json                  versioned, validated observation or failure report
summary.md                    human-readable result and lifecycle status
workflow-outcome.json         job outcomes, cleanup confirmation, publication issues
evidence/                     available process samples and credit observations
diagnostics/                  allowlisted logs, state and control/cleanup outcomes
```

Private generated configuration, environment files, downloaded runtimes and
intermediate finalizer working files are excluded. Raw controller/diagnostic
artifacts remain separately available for recovery/re-publication.
Unreadable/oversized optional evidence and log copies are recorded in
`workflow-outcome.json`'s `evidenceIssues`; they do not erase valid measurements.

## Verification

```sh
python -m pip install -r performance/workflow/requirements-dev.txt
python -m unittest discover -s performance/workflow -p 'test_*.py'
python -m unittest discover -s performance/contracts -p 'test_*.py'
```

To additionally exercise the real packaged JVM finalizer, build the harness and
set `SENDIUM_PUBLISH_JAR` to its absolute primary JAR path before running tests.
The test uses an explicitly synthetic one-message observation, verifies unchanged
measurements and checks that missing resources stay inconclusive. It requires no
AWS credentials or resources. The complete local rehearsal remains Task 5.5,
before real AWS validation in 6.1.

See [the permissions/failure audit](AUDIT.md) for fixed findings, outcome coverage
and the remaining hosted/live confirmations.
