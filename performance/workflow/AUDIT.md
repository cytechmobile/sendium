# Workflow permissions and failure-outcome audit — Task 5.4

## Scope and evidence

Reviewed the manual workflow, bootstrap IAM/OIDC policies, dispatch/artifact
validation, protected orchestration, SSM polling, node log handling, diagnostic
ownership checks, cleanup and unprivileged publication. Verification is offline:
declared GitHub conditions and AWS responses are modeled, not exercised against a
hosted workflow or AWS account.

Passing checks:

- 55 workflow checks on Linux, including packaged-JVM finalization.
- 66 deployment/control/expiry checks and 23 infrastructure policy checks.
- 6 independent result-semantic checks and 9 resource-helper checks.
- 111 Java harness tests, including accounting/protocol failures and overload.
- Workflow actionlint and lint for all three CloudFormation templates.
- Real Linux node smoke cases: HTTP/DLR-on, SMPP/DLR-off and readiness failure,
  including report publication, sampling, redaction and shutdown. Disposable
  PostgreSQL/container/network resources were removed afterward.

These targeted checks complement the complete topology/TLS/lifecycle rehearsal
in Task 5.5; they do not replace it.

## Concrete findings fixed

- `performance/workflow/run_aws.py:148` - [core-fail-fast] A caught interruption
  after reading a successful execution code could retain exit zero. Terminal
  controller exceptions/interruption now explicitly set failure before preserving
  available diagnostics.
- `performance/deployment/deploy.py:29` - [core-fail-fast] Polling treated
  authentication/authorization failures like expected propagation. Retry only
  explicit missing/transient codes; preserve sanitized S3 GetObject codes and
  list the exact status key before GET, avoiding ambiguous missing-key permission
  responses. Permanent/unknown polling failures now stop promptly.
- `performance/deployment/node.py:238` - [core-encapsulation] Log redaction relied
  on private configuration still existing after external code stopped. Capture
  known private values before starting each JVM; log redaction survives later
  configuration removal.
- `performance/workflow/publish.py:144` - [core-separation-of-concerns] An unreadable
  or oversized optional resource/log file could abort publication of otherwise
  validated observations. Omit that source with explicit evidence issues and
  continue finalization/publication, preserving workload data.
- `performance/workflow/publish.py:80` - [core-encapsulation] Resource-finalization
  checks retained original non-resource reasons but allowed additions/reordering.
  Require exact non-resource reason preservation as well as immutable metrics,
  metadata and failed workload classification.
- `performance/workflow/publish.py:21` - [core-fail-fast] The pre-JVM credential
  guard covered direct keys/OIDC but omitted named/file/container providers.
  Include configured profile, credential/config-file and container-credential
  environments. No finalizer process launches when these providers are present.
- `performance/workflow/recover.py:74` - [core-fail-fast] Instance checks identified
  the run but did not bind both sources to their role/type/region. Check source
  tags, declared type and report region before credit queries. Deployment also
  requires correct source tags and the requested instance type before SSM starts.

Regressions live in `test_audit.py`, `test_control_audit.py` and the existing
deployment/publication suites. Test credential failures use booleans in assertions
so real credential values cannot appear in an opt-in test failure message.

## Audited boundaries and outcome matrix

| Boundary/case | Verified behavior |
|---|---|
| Trigger/input handling | Manual-only, individual schema/budget-checked controls; inputs are JSON/environment data, not shell substitutions. |
| Application source | Exact dispatch SHA; maintained harness/controller SHA captured separately and pinned downstream. |
| Build isolation | Native hosted build runners, repository read only, no environment/OIDC/secrets, no persisted checkout credentials/shared Maven cache. |
| AWS jobs | Only execution, diagnostics and cleanup receive OIDC. Execution uses the approval-gated environment; diagnostics/cleanup use a branch-restricted automatic recovery environment with read-only/deletion-only roles. Trusted dependency installation precedes authentication. |
| Artifact boundary | Fixed names/paths, exact attempt/source/architecture and fresh size/SHA checks; protected jobs do not execute downloaded application/harness code. |
| AWS ownership | Scoped bootstrap roles plus code checks on account/region/repository/run/source/role; no ad-hoc deletion outside the owned root/prefix. |
| Publication | No environment/OIDC/cloud session; verified maintained JAR only, fixed evidence paths, immutable workload data/reasons and schema/semantic checks. |
| Valid/expected overload | Report and pipeline may complete; rejection alone is not a performance failure gate. |
| Accounting/setup failure | Failed report/pipeline; unavailable evidence is not represented as measured zero throughput. |
| Resource or auxiliary evidence loss | Observations survive; missing resources remain inconclusive and evidence-copy issues are explicit. |
| Cancellation after observation | Valid observations can remain valid while the pipeline records cancellation/failure. |
| Failed diagnostics/cleanup | Pipeline fails independently of observation validity; incomplete cleanup is never confirmed. |
| Lifecycle conditions | `always()` admits diagnostic recovery/cleanup after attempted AWS failure/cancellation; publication runs after failed predecessors when normalization succeeded. |
| Hard interruption | Accepted CloudFormation deletion can continue server-side; independent expiry is retained outside the Actions runner. |

## Required hosted/live confirmations

Environment admission and the workflow definition are part of the trust boundary.
GitHub dispatch selects workflow YAML from the chosen branch; pinned helper
checkouts do not authorize arbitrary unreviewed YAML. The maintained default
branch and protected environment must therefore follow the repository's
maintainer-controlled review/admission policy. This audit checks the committed
definition and exact environment-bound OIDC trust, not repository settings.

After Task 5.5, live validation must confirm actual OIDC/IAM enforcement,
environment admission, hosted ARM runner availability, server-side cancellation
and job scheduling, artifact availability/organization retention limits, SSM/VPC
endpoint behavior and scheduled expiry. The declared-condition model does not
prove GitHub will execute every cleanup step under hard cancellation or runner
loss; that is why independent expiry remains mandatory.
