# On-demand AWS performance tests

**Status:** Living document. Implementation in progress; updated after each task.
**Started:** 2026-10-14
**Owner:** To be confirmed.
**Planning doc:** [Issue #352](https://github.com/cytechmobile/sendium/issues/352)
**Companion docs:** Planned: `AwsPerformanceTestsArchitecture.md` (as-built).

---

## 1. Phase / Task tracker

| Phase | Task | Title | Size | Status | Notes |
|------:|-----:|-------|:----:|--------|-------|
| 1 | 1.1 | Define harness configuration and versioned result contracts | M | 🟢 done | Added `performance/contracts/` specifications and JSON schemas; validated defaults, schema references, setup-failure output, and invalid boundaries. |
| 1 | 1.2 | Package a standalone JVM harness with phase control | M | 🟢 done | Opt-in Maven module, executable validation CLI, and lifecycle controller; five tests and packaged-JAR smoke check pass. Protocol execution follows in Phase 2. |
| 1 | 1.3 | Implement bounded message accounting and latency collection | M | 🟢 done | Added synchronized token ledger, bounded active/retired history, outcome snapshots, and fixed-range HdrHistogram latency collection. All 15 harness tests pass. Minute aggregation follows in 3.2. |
| 2 | 2.1 | Add simulated SMPP provider responses and delivery receipts | M | 🟢 done | Added authenticated asynchronous Cloudhopper provider with bounded pending work, configurable delays, unique IDs, and successful receipts. Seven TCP/SMPP tests pass; all 22 harness tests pass. |
| 2 | 2.2 | Add HTTP submission and forwarded receipt reception | M | 🟢 done | Added asynchronous Kannel GET admission and bounded callback receiver, gateway-ID validation, race handling, and diagnostics. Ten HTTP tests pass; all 33 harness tests pass. Real Sendium/PostgreSQL integration remains 2.5. |
| 2 | 2.3 | Add SMPP submission and forwarded receipt reception | M | 🟢 done | Added bounded transceiver client, gateway-ID correlation, timestamp-preserving early-receipt buffer, and protocol diagnostics. Nine SMPP adapter tests pass; all 42 harness tests pass. |
| 2 | 2.4 | Generate Sendium configuration for both benchmark scenarios | M | 🟢 done | Added deployment input, isolated Sendium/harness bundles, sanitized configuration artifact, and `generate-config` CLI. All four mode/DLR combinations verified; all 50 harness tests and packaged CLI smoke check pass. |
| 2 | 2.5 | Add local packaged-JVM scenario verification | M | 🟢 done | Added opt-in Failsafe checks using generated configuration, actual packaged Sendium JVMs, and disposable PostgreSQL 17. All four scenarios and 50 unit/socket tests pass; verified database tracking/cleanup and warm-up exclusion. |
| 3 | 3.1 | Schedule fixed-rate traffic with bounded outstanding requests | M | 🟢 done | Added absolute-time open-loop scheduling shared by both adapters, scheduling/capacity misses, intended timestamps, rate/saturation statistics, and stop/join control. All 63 unit/socket and four packaged integration tests pass. |
| 3 | 3.2 | Produce overall and one-minute measurement reports | M | 🟢 done | Added bounded cohort/event minute aggregation, JSON/Markdown reports, drain backlog and latency percentiles. All 74 unit/socket and four packaged integration tests pass; generated reports validate against schema v1. Resource validity remains 3.3. |
| 3 | 3.3 | Collect resource utilization and classify run validity | M | 🟢 done | Added bounded Linux CPU/RSS/host agents, protected read-only AWS credit collection, resource summaries and validity/exit policy. 86 Java tests, eight Python tests, four packaged scenarios, schema checks and a live Linux sampler check pass. AWS API collection is mocked; live verification remains 6.1. |
| 3 | 3.4 | Verify measurement attribution and accounting failure behavior | M | 🟢 done | Added 19 cross-component boundary/race checks and six independent artifact-semantic tests. Fixed invalid receipt-time acceptance, token aliases, and same-tick finalization. All 105 Java tests, four packaged scenarios, 14 Python tests and generated-report validation pass. |
| 3 | 3.5 | Wire a runnable benchmark command and phase/report orchestration | M | 🟢 done | Added `run` and offline `finalize-report`, readiness/state files, sampler ownership, acknowledgement-aware drain, immutable cutoff and classified reports. 111 Java tests, eight packaged scenarios, 15 Python tests and artifact validation pass; Linux sampler graceful stop verified. |
| 4 | 4.1 | Define the AWS and workload preset | S | 🟢 done | Added `performance/presets/aws-private-jvm-v1.json`, deployment mapping/prerequisites, and static compatibility checks. Defines private run-owned networking, JVM/RDS sizing, sample/phase budgets, expiry and artifact retention; live AWS availability remains preflight/6.1 work. |
| 4 | 4.2 | Create the AWS bootstrap CloudFormation template | M | 🟢 done | Added `performance/infrastructure/bootstrap.yaml`: private temporary artifact bucket, optional/reused OIDC, orchestration/diagnostic/service roles, required runtime boundary, expiry role/logs and outputs. CloudFormation lint and seven policy tests pass; actual handler/schedule remains 4.7 and live authorization 6.1. |
| 4 | 4.3 | Create the private networking CloudFormation template | M | 🟢 done | Added disposable `network.yaml`: two private AZ subnets, role-paired security rules, three interface endpoints and run-scoped S3 gateway endpoint. Both templates lint; all 12 infrastructure tests pass. Parent integration follows in 4.4; live connectivity remains 4.5/6.1. |
| 4 | 4.4 | Create the per-run benchmark CloudFormation template | M | 🟢 done | Added `run.yaml`: network child, separate private EC2s/profiles, bounded per-node IAM, generated database secret, TLS-enforced private PostgreSQL and deployment outputs. Aligned network CIDRs to preset. Three templates lint; 21 infrastructure checks and preset validation pass. Live provisioning remains 4.5/6.1. |
| 4 | 4.5 | Implement artifact deployment and execution scripts | M | 🟢 done | Added immutable/checksummed staging, owned-resource preflight, SSM/systemd node control, local secret/config generation, sampling, redacted logs and allowlisted failure-aware retrieval. 15 offline checks and three real Linux/JVM/PostgreSQL scenarios pass. AWS resolution/stack creation integrates in 5.2; live service validation remains 6.1. |
| 4 | 4.6 | Implement run-owned stack and temporary artifact cleanup | M | 🟢 done | Added `cleanup.py`: verified root-ARN deletion, bounded partial/busy/failed-stack recovery, confirmed absence before exact run-prefix object/multipart purge, repeatable retries and explicit outcomes. 24 cleanup/transport checks (39 deployment total), 22 infrastructure checks and three-template lint pass. Workflow wiring follows in 5.3; local rehearsal 5.5 precedes AWS validation 6.1. |
| 4 | 4.7 | Implement independent expiry of abandoned benchmark runs | M | ⚪ not started | Scheduled expiry outside GitHub Actions; recover tagged expired stacks and temporary artifacts with narrowly scoped permissions; install through bootstrap CloudFormation. |
| 5 | 5.1 | Create the manual GitHub Actions performance-test workflow | M | ⚪ not started | Create `.github/workflows/performance-tests.yml`: manual inputs, validation, exact selected commit, architecture-compatible unprivileged builds, and artifact identities. Resolve input-count constraints without losing configurable settings. |
| 5 | 5.2 | Add protected CloudFormation and SSM execution jobs | M | ⚪ not started | Maintainer-controlled environment/OIDC, resolve supported AMI/JRE/engine/AZ inputs, verify operational expiry, deploy stacks, and invoke the staged SSM controller with per-run isolation; benchmark execution receives no provisioning credentials. |
| 5 | 5.3 | Publish workflow reports and add cleanup jobs | M | ⚪ not started | Overall and one-minute summary results, versioned JSON, diagnostic logs, explicit artifact retention, failure-result retrieval, and cleanup paths. |
| 5 | 5.4 | Verify workflow permissions and failure outcomes | M | ⚪ not started | Builds have no AWS provisioning access; validate input handling, protected environment usage, setup/accounting failures, overload reporting, and cancellation behavior. |
| 5 | 5.5 | Validate the complete deployment in an AWS-like local environment | L | ⚪ not started | Required before 6.1. Separate Linux Sendium/harness nodes, PostgreSQL 17 with verified TLS, private connectivity, production deployment/lifecycle code through local transport, all four protocol/DLR combinations, failure diagnostics, concurrent-run isolation and repeatable cleanup. Detailed acceptance criteria below. |
| 6 | 6.1 | Validate the complete AWS benchmark and resource lifecycle | M | ⚪ not started | Requires successful local rehearsal in 5.5 and configured AWS prerequisites. Both protocols, delivery reports on/off, actual resource measurements; provisioning/scenario failure, cancellation, partial creation, abandoned execution, and independent expiry. |
| 6 | 6.2 | Document AWS installation and maintainer benchmark operations | M | ⚪ not started | Bootstrap and networking installation, GitHub environment/role setup, preset selection, manual execution, results, retention, cost/lifetime boundaries, teardown, and recovery. |
| 6 | 6.3 | Write the as-built architecture companion document | S | ⚪ not started | Describe implemented components, trust boundaries, data flow, stack ownership, and resource lifecycle with diagrams. |

**Legend:** ⚪ not started · 🟡 working · 🟢 done · 🔴 blocked / needs decision
**Size:** XS · S · M · L · XL.

One task is one reviewable step. Describe and obtain approval before starting a task, mark it working before implementation, and update this document after verification. Commits require explicit user authorization and the repository's DCO trailer.

---

## 2. Goal

Implement issue #352 so maintainers can manually benchmark a selected Sendium branch through GitHub Actions whenever needed. Each run measures one exact application commit with configurable traffic and infrastructure, exercises HTTP or SMPP admission through outgoing SMPP submission, and includes PostgreSQL-backed delivery-report tracking and forwarding by default. Useful sending throughput, latency, resource utilization, saturation, and unfinished work must be visible in both overall and one-minute results. Completion means the issue's acceptance criteria are demonstrated, artifacts are downloadable, and temporary AWS resources are cleaned up reliably.

---

## 3. Architecture / approach

### Required architecture

```text
GitHub-hosted Actions runner
  |-- Build application and harness without AWS provisioning privileges
  |-- Provision/control through protected environment and OIDC
  |-- Retrieve results and publish summary/artifacts
  `-- Clean up resources owned by this run

AWS private test environment
  |-- Harness EC2
  |     HTTP/SMPP generators, SMPP provider simulator,
  |     HTTP/SMPP receipt receivers, accounting/reporting
  |-- Sendium EC2
  |     HTTP/SMPP admission -> routing -> outgoing SMPP
  |     PostgreSQL-backed delivery-report tracking/forwarding
  `-- Private RDS PostgreSQL
```

The harness runs separately from Sendium so load generation and simulation do not consume the application resource budget. Instances have no public IP requirement or publicly exposed benchmark ports. Systems Manager controls them; management services and deployment artifacts must remain reachable through VPC endpoints or outbound networking. Prefer the same Availability Zone for both EC2 instances and record database placement.

### Execution and configuration

- Select the application branch through **Run workflow → Use workflow from** and record the exact commit SHA. Record the harness revision separately.
- Start with packaged JVM execution, compatible with the chosen instance architecture.
- Workflow inputs: ingress mode, application/harness instance types, offered messages/sec, worker TPS, warm-up/measurement/drain durations, delivery reports enabled, and provider response/delivery delays.
- Defaults: 120-second warm-up, 600-second measurement, 120-second maximum drain, delivery reports enabled.
- Keep region, networking, RDS sizing, connections/windows, and JVM settings in a documented preset; record all effective settings in results.
- Verify Sendium, database, and SMPP readiness before traffic starts. Warm-up exercises the same sending and receipt paths as measurement.
- Isolate credentials, database state, resource names, and mutable data per run. Tag resources with workflow run identity.

### Measurement invariants

- Schedule new submissions at a fixed offered rate; slow responses must not silently reduce the target load.
- Bound outstanding requests and measurement bookkeeping. Count generation targets missed because limits or harness saturation prevent submission.
- Separate warm-up and measurement messages. Follow measurement messages through outcomes received during drain.
- Distinguish admission throughput from successful sending and forwarded delivery-report throughput. Show growing outstanding work.
- Correlate ingress messages, provider requests/responses, and generated/forwarded receipts; report unexpected duplicates and unfinished accepted messages.
- Collect overall and one-minute measurements. Exact interval attribution and latency timestamp definitions are deliverables of Task 1.1, not yet settled implementation choices.
- Report acknowledgement latency, submission-to-provider arrival, provider-response completion, and receipt-forwarding latency with p50/p95/p99. Separate simulated delivery delay from forwarding time.
- Report Sendium and harness CPU/memory, drain duration, and pending provider/receipt work.
- For burstable instances, record CPU-credit state and Standard/Unlimited mode. A short benchmark does not establish long-term credit sustainability.
- Mark results inconclusive when the harness cannot supply the intended load or otherwise bottlenecks execution.
- Setup failures and scenario-defined accounting violations fail execution; expected overload rejections are reported separately. No arbitrary performance thresholds.

### Results and lifecycle

Publish a concise GitHub Actions summary and a downloadable artifact containing versioned `results.json`, overall/interval measurements, effective configuration, application branch/SHA, run URL, harness revision, artifact identifiers, and diagnostic logs. Choose and document an explicit retention period. Generated results are not committed.

Cleanup must cover success, failure, and cancellation. An independent expiry mechanism must recover abandoned temporary resources. Workflow cleanup alone is insufficient if a runner disappears.

### CloudFormation deliverables and resource ownership

Use CloudFormation for AWS setup, with separate reviewable templates for:

1. **Bootstrap infrastructure:** persistent artifact storage, GitHub OIDC integration and provisioning roles, and the independently scheduled expiry mechanism. Installation is a documented maintainer operation, not part of application builds. Support accounts with an existing GitHub OIDC provider.
2. **Private networking:** VPC, subnets, security groups, and connectivity for Systems Manager, artifacts, and required runtime services. RDS requires suitable subnet coverage even when EC2 instances share one Availability Zone. ~~Stack lifetime remains to be selected.~~ Task 4.1 selects a run-owned network nested under the run parent stack.
3. **Per-run infrastructure:** application and harness EC2 instances, instance profiles, private RDS PostgreSQL, isolated credentials, run/expiry tags, and outputs consumed by deployment scripts.

Bootstrap resources survive individual runs. Benchmark resources and temporary artifacts have run-specific ownership and bounded lifetimes. Cleanup and expiry must handle partially created or failed stacks, not only successfully completed benchmarks. Persistent infrastructure teardown is documented separately from run cleanup.

Provisioning credentials stay with protected workflow orchestration. Application and harness builds do not receive them; EC2 execution roles provide only the runtime access required for deployment, diagnostics, and measurement. Instance metadata and credential exposure must be considered when deploying selected-branch application code.

### Default AWS/workload preset

`performance/presets/aws-private-jvm-v1.json` and its README define the input contract for Tasks 4.2–4.5. Region comes explicitly from the protected environment's `AWS_REGION`. ~~The initial proposal used `c7i.large`/1 GiB for Sendium, `m7i.xlarge`/2 GiB for the harness, and `db.m7g.large`.~~ The user selected low-cost temporary defaults: **`t4g.small` for both EC2s, 512 MiB heap per JVM, and private Single-AZ PostgreSQL 17 on `db.t4g.small` with 20 GiB gp3**, the minimum new PostgreSQL SSD allocation. EC2 root disks use the resolved AMI root-device minimum (typically 8 GiB), not a padded fixed allocation. Default offered rate and worker TPS are 100. The default EC2s are ARM64; separately resolved x86_64/arm64 overrides remain supported. Private AL2023 AMIs and checksummed Temurin Java 25 archives are staged before execution. Exact image/runtime/database versions, resolved disk sizes, and preset digest must be recorded per run.

The default network is per-run with two private subnets, endpoints for SSM/SSM Messages/Secrets Manager, and S3 gateway access; no public IP, NAT, or internet gateway. Both EC2s and requested RDS placement use the first selected AZ; the database subnet group spans both AZs. RDS uses verified TLS, encrypted storage, no automatic upgrade/storage resizing, and explicit delete policies without snapshots. Independent expiry uses an EventBridge-scheduled Lambda. Resource TTL is four hours, expiry checks every 15 minutes, fallback S3 expiration one day, and public workflow artifacts 30 days. Maximum phase durations fit sample, histogram, job, cleanup, and expiry budgets. These are setup/resource bounds, not measured-performance gates. Templates/resolution and live account verification remain later tasks.

### GitHub Actions workflow deliverables

Create `.github/workflows/performance-tests.yml` with `workflow_dispatch`. Separate unprivileged build jobs from protected AWS provisioning/control jobs. Record the dispatch commit rather than resolving a moving branch later. Validate inputs before provisioning, carry artifact identities through deployment, retrieve diagnostics on failures, and publish overall plus one-minute results through the summary and downloadable artifact.

The input layout must respect GitHub's supported dispatch-input count while retaining all settings required by the issue. Grouping the three phase durations into one validated timing input is a candidate if the supported limit requires it; confirm the current platform limit during Task 5.1. Document the final syntax and defaults.

Use run identity including the run attempt where necessary to prevent collisions during reruns. Cleanup jobs provide the immediate path; independently scheduled expiry provides recovery if cancellation or runner loss prevents those jobs from completing.

### Local deployment rehearsal — Task 5.5

Task 5.5 is a required dependency of the first AWS validation in Task 6.1. Extend the existing packaged-JVM and node checks into a reproducible local environment that exercises the complete deployment and lifecycle with an AWS-like topology.

**Scope:**

- Run Sendium and the harness on separate Linux container nodes with actual Java 25, the production deployment scripts, and the agreed memory/heap limits.
- Run PostgreSQL 17 separately, require TLS, and verify its certificate from Sendium.
- Reproduce private role-to-role connectivity: harness ↔ Sendium, Sendium → PostgreSQL, and controlled artifact/management access.
- Reuse production controller/node code through a local transport adapter for staging, checksums, configuration generation, readiness, phases, sampling, report retrieval and cleanup. Evaluate LocalStack for supported S3/control-plane operations; do not assume it reproduces EC2, RDS or SSM completely.

**Acceptance criteria:**

1. HTTP and SMPP each complete with DLR enabled and disabled.
2. Reports pass the versioned schemas and independent accounting/measurement-semantic validation.
3. Unavailable PostgreSQL, invalid TLS certificates, application crashes, interrupted execution and partial deployment preserve useful available diagnostics and explicit failure outcomes.
4. Cleanup is repeatable and removes the local run's resources/artifacts without affecting another concurrent run.
5. Private credentials and runtime configuration never appear in downloadable outputs.
6. A documented command starts the environment and runs the rehearsal, including teardown and failure-case verification.

Record which services are real, substituted or unsupported. Actual IAM authorization, VPC endpoints, managed RDS behavior, AWS SSM/systemd integration and ARM64 execution remain explicit Task 6.1 checks; successful local rehearsal establishes readiness to begin those checks.

### Acceptance-criteria coverage

| Issue requirement | Implementation tasks | Verification |
|-------------------|----------------------|--------------|
| Select a branch without workflow edits; identify exact commit and settings | 1.1, 4.1, 5.1, 5.3 | 5.4 and 6.1 verify dispatch/build/deployed identities and published configuration. |
| HTTP and SMPP single-part scenarios | 2.1–2.5 | Local scenario verification in 2.5; AWS execution in 6.1. |
| Delivery reports enabled by default and optional; private RDS tracking and forwarding | 1.1, 2.1–2.5, 4.4–4.5, 5.1 | Both modes and delivery-report settings in 2.5 and 6.1; inspect effective storage configuration and forwarding outcomes. |
| Configurable 120/600/120-second phases and bounded drain | 1.1–1.2, 3.1, 5.1 | Lifecycle and measurement verification in 3.4; complete runs in 6.1. |
| Sending throughput, latency percentiles, resource usage, and accounting | 1.3, 3.1–3.4 | Protocol integration, controlled measurement/accounting checks, and actual AWS resource samples. |
| Overall and minute results in both summary and artifact | 3.2, 5.3 | Verify both outputs and JSON schema, including outcomes arriving during drain. |
| Visible generator saturation and unfinished accepted work | 1.3, 3.1–3.4 | Controlled missed-load and bounded-drain cases in 3.4; validity reported in 5.3. |
| Setup/accounting failures fail execution; overload separately reported; no performance gates | 3.3–3.4, 4.5, 5.4 | Explicit exit-status and workflow-outcome verification; expected overload remains a reported outcome. |
| Maintainer-controlled OIDC access and isolated run resources | 4.2–4.4, 5.2, 5.4 | Permissions review, workflow checks, and isolated AWS runs in 6.1. |
| Reliable cleanup and independent expiry | 4.6–4.7, 5.3 | Failure, cancellation, partial creation, and abandoned-run verification in 6.1. |

Local checks are implemented alongside their respective tasks. Account-dependent validation is recorded separately and cannot be marked complete based solely on template or workflow inspection.

### Repository integration candidates

The repository currently uses Java 25, Maven, Quarkus, and Cloudhopper SMPP. `sendium-core/src/test/java/utils/NativeE2eSmoke.java` contains provider/client and receipt-handling examples. `.github/workflows/run_tests.yml` already verifies PostgreSQL-backed restart behavior. These are references; benchmark load generation and timing need dedicated implementations.

Original proposed direction: a standalone Java harness reusing the managed Cloudhopper version. ~~Module placement and packaging remain to be selected.~~ Task 1.2 adds `performance/harness` through an opt-in Maven `performance` profile and packages an executable shaded JAR. Cloudhopper integration follows with the protocol adapters. The specific expiry implementation remains open. CloudFormation is the agreed AWS provisioning approach.

---

## 4. Future work / out-of-scope

All entries below are explicitly deferred by issue #352. Their scheduling beyond V1 is not committed.

| Item | Status | Why |
|------|--------|-----|
| Automatic comparison with main or other versions | Out of scope | V1 measures one version per run. |
| Maximum sustainable-throughput search | Out of scope | V1 uses a configured offered rate. |
| Multipart messages and ingress reassembly | Out of scope | Initial scenarios use single-part messages. |
| Retries, rerouting, disconnections, and failure injection | Out of scope | Initial provider behavior is successful responses with fixed delays. |
| Multiple outgoing destinations | Out of scope | Initial scenarios use one destination. |
| Native-image benchmarks | Out of scope | Start with packaged JVM execution. |
| Extended endurance tests | Out of scope | Explicitly deferred by the issue. |
| Historical dashboards and permanent storage | Out of scope | V1 stores results with the workflow run. |
| Automatic execution on every pull request | Out of scope | Execution is maintainer-triggered. |
| Performance regression gates | Out of scope | No arbitrary performance pass/fail thresholds in V1. |

---

## 5. Decision log

### 2026-10-16 — Delete verified root stacks before purging temporary run artifacts

**Decision:** Add a standard-library cleanup command accepting either the original plan or staged manifest. Resolve the bootstrap bucket/namespace/role; validate root identity, account/region, ownership tags and attached role; pin deletion to the stack ARN. Observe in-progress deletion, wait out busy creation/rollback and retry failed deletion up to three accepted requests within a shared deadline. Confirm deletion before aborting multipart uploads and removing only the exact run prefix, then verify it is empty. Preserve partial failure/timeout/interruption outcomes and permit idempotent retries. Add only bucket-scoped versioning/multipart-list reads to the orchestration role; retain existing run-scoped delete/abort permissions.

**Rationale:** CloudFormation preserves resource dependency ordering, partial provisioning can be cleaned without application readiness or local build files, and diagnostic/template inputs survive unconfirmed stack deletion. Distinguish exact absence from authorization errors, detect same-name replacement, and avoid pagination skips after mutations. Independent expiry remains Task 4.7; final workflow cleanup wiring remains Task 5.3.

**Verification:** All 24 cleanup/transport checks pass (39 deployment checks including existing execution checks), all 22 infrastructure checks pass, and the three templates pass CloudFormation lint. Checks cover isolation, repeated/concurrent cleanup, missing/partial/failed stacks, bounded recovery, multi-page S3 objects/uploads, partial failures, interruption and large request payloads. No AWS resources deployed; Task 5.5 remains the required complete local rehearsal before live AWS lifecycle validation in 6.1.

### 2026-10-16 — Require an AWS-like local rehearsal before AWS validation

**Decision:** At the user's request, add Task 5.5 after workflow verification and make it a prerequisite of Task 6.1. Use separate Linux application/harness nodes, PostgreSQL 17 with verified TLS, private connectivity and production deployment/lifecycle code through a local transport. Cover all four protocol/DLR combinations, failure diagnostics, credential isolation, concurrent runs and repeatable cleanup, with a documented rehearsal command.

**Rationale:** Existing local checks validate important components, but a complete topology/lifecycle rehearsal should catch integration problems before spending time on AWS provisioning. Evaluate LocalStack's supported operations and record substitution boundaries; retain real AWS service/permission validation in Task 6.1.

### 2026-10-16 — Stage immutable inputs and supervise private-node execution

**Decision:** Add `performance/deployment/` with a protected byte-staging/SSM controller and a standard-library Linux node supervisor. Verify all artifact sizes/digests before execution, refuse input overwrites, and validate live stack/instance/database identities and lifetime before starting nodes. Require the bootstrap expiry-installed flag. Generate paired private configuration on Sendium with its exact database secret; transfer only harness settings through the bounded one-way input prefix. Start harness listeners before Sendium and delegate readiness/phases/drain to the existing executable harness. Use bounded systemd units, per-node/local sampling, bounded redacted process-log tails, explicit public output keys and best-effort stop/retrieval on failure/interruption. Keep AWS credit collection in the diagnostic role and finalization in an unprivileged publishing job.

**Rationale:** Selected application/harness code never executes under provisioning credentials. Private database values remain on Sendium, partial diagnostics survive other retrieval failures, and initial reports can be enriched without repeating traffic. Fresh run identities make partial staging/execution attempts explicit; deletion and independent recovery remain separate lifecycle tasks.

**Verification:** All 15 offline deployment checks pass. Disposable Linux verification passed HTTP/DLR-on, SMPP/DLR-off and missing-application readiness failure using actual Java 25 application/harness processes, PostgreSQL 17, archive installation, configuration generation, sampling, redaction and publication. The failure case retained failed JSON/Markdown reports. Test containers/network were removed. AWS object/secret transport is substituted locally; actual IAM, endpoints, SSM/systemd, ARM64 and verified RDS TLS remain Task 6.1. Task 5.2 must resolve provisioning inputs and verify operational expiry; no AWS resources deployed.

### 2026-10-16 — Own compute, database and credentials in a disposable parent stack

**Decision:** Add create/run/delete `run.yaml`, nesting the reviewed network template from the exact run artifact key. Use separate primary-AZ private EC2s with per-node profiles, required runtime boundaries, IMDSv2 and encrypted/delete-on-termination AMI-minimum root disks. Provision PostgreSQL 17 with required TLS, 20 GiB encrypted gp3, no backups/autoscaling/final snapshot and explicit delete policies. Resolve credentials through a generated per-run secret, accessible only to Sendium. Grant each node its own input/evidence paths; allow Sendium to publish only the generated harness configuration prefix as a one-way private credential handoff. Keep resolved image/storage/engine inputs explicit and runtime deployment in Task 4.5.

**Rationale:** Preserves the agreed low-cost defaults, separate resource budgets and privilege separation while making the parent responsible for dependency-ordered network teardown. Stack outputs expose identifiers and secret ARN only; the harness and GitHub controller do not need database-secret values. Bound run identity length to 27 characters so names fit IAM constraints at maximum resource-prefix length.

**Correction:** Task 4.3's initial CIDRs differed from the versioned preset. Aligned the template/README to `10.70.0.0/24` and `10.70.0.0/26`, `10.70.0.64/26`; added a direct preset-consistency check.

**Verification:** Three templates pass CloudFormation lint, all 21 offline infrastructure checks pass, and preset/schema compatibility validation passes. Checks cover private placement, storage/deletion, credit-family conditions, secret separation, runtime policy/boundary compatibility, maximum role-name lengths, ownership and cross-template interfaces. Live IAM/SSM/RDS lifecycle validation remains Tasks 4.5/6.1; no AWS resources deployed.

### 2026-10-16 — Isolate each run with endpoint-only networking

**Decision:** Add a disposable network child template with two private subnets in distinct AZs, primary-AZ interface endpoints, a run-prefix-scoped S3 gateway endpoint, and explicitly paired role-to-role TCP rules. Suppress implicit unrestricted group egress. Use parent/child output references rather than cross-stack exports, and propagate run identity/expiry to stacks and taggable resources.

**Rationale:** Matches the versioned private preset while keeping concurrent runs independent and teardown dependency-ordered. Preinstalled SSM and staged runtime bundles remove the need for public package/download connectivity.

**Verification:** Both infrastructure templates pass CloudFormation lint; all 12 offline infrastructure tests pass. Live IAM, routing and SSM verification remain Tasks 4.5/6.1. Parent integration is Task 4.4; independent expiry remains Task 4.7.

### 2026-10-16 — Bootstrap persistent control-plane roles before run infrastructure

**Decision:** Add a standalone bootstrap template with private encrypted temporary S3 storage, conditional GitHub OIDC creation/reuse, exact environment-bound web-identity trust, delegated CloudFormation provisioning, read-only diagnostics, and a required runtime permission boundary. Scope control/deletion to run prefixes and repository ownership tags; root creation also requires run identity and expiry. Prepare the expiry role/log group and explicitly output `ExpiryHandlerInstalled=false` until Task 4.7 supplies the real handler/schedule. Retain artifact storage/TLS policy and newly created account-level OIDC on bootstrap teardown.

**Why:** Infrastructure and workload code need distinct credentials and ownership contracts. Runtime roles must not read account-wide Parameter Store values or inherit provisioning access. Independent cleanup requires persistent permissions after a job disappears, but a placeholder function would falsely imply operational expiry. Shared OIDC and retained artifact resources need explicit installation/teardown ownership rather than accidental deletion.

**Alternatives considered:** Direct EC2/RDS privileges on the GitHub role bypass a stable service-role boundary. Reusing broad managed instance permissions introduces unnecessary runtime API access; the boundary allows control channels and the managed shell document instead. Static AWS keys are unnecessary with OIDC. Installing a no-op scheduled cleanup handler would hide the remaining expiry work. Running the configuration/report JVM under the provisioning role conflicts with privilege separation; node-side configuration and unprivileged offline publishing remain the integration contract.

**Verification:** CloudFormation lint passes and seven offline policy checks pass, covering exact positive/negative OIDC claims and provider reuse, provisioner/runtime/diagnostic separation, mandatory boundaries, owner/tag/expiry requirements, private encrypted retained storage, and IAM policy-size limits at maximum parameter lengths. Installation commands, outputs, naming constraints and teardown are documented in `performance/infrastructure/README.md`. No AWS deployment was performed; live resource/provider authorization, encryption, SSM access and rollback/deletion behavior remain Tasks 4.5/6.1.

### 2026-10-16 — Use small Graviton defaults and minimum temporary storage

**Decision:** Replace the initial larger defaults with `t4g.small` Sendium and harness instances and `db.t4g.small` RDS. Set both JVM heaps to 512 MiB and memory compatibility minima to 2048 MiB. Resolve each EC2 root disk from the selected AMI root-device mapping rather than reserving 20 GiB. Keep RDS at 20 GiB gp3, the API minimum for a new PostgreSQL general-purpose SSD instance, with autoscaling/backups/snapshots disabled.

**Why:** The user explicitly requested small, low-cost instances and the least possible storage for short-lived tests. The previous heap/memory limits would reject or overcommit the selected 2 GiB EC2s. Region/AMI-specific root sizes must be resolved instead of assuming every image fits a hardcoded 8 GiB.

**Alternatives considered:** The prior non-burstable defaults reserved more compute/memory than requested. A smaller RDS SSD allocation is unsupported by the CreateDBInstance API; legacy magnetic storage is no longer offered for new instances. Fixed larger EC2 root volumes are unnecessary for this temporary preset. Actual CPU-credit state remains part of burstable-instance diagnostics; RDS credit behavior is service-managed.

### 2026-10-16 — Define one explicit private JVM preset before infrastructure templates

**Decision:** Add versioned machine-readable `aws-private-jvm-v1` settings and deployment documentation. Use a run-owned endpoint-only VPC, non-burstable EC2 defaults with fixed heaps, separately resolved supported architectures, private PostgreSQL 17 sizing/TLS, and explicit workload/collection/lifetime bounds. Resolve rolling AMI/JRE/minor selectors once before provisioning and record exact identities/digests; optional pins support stricter repeat runs. Use Standard mode for selected burstable types and record actual state. Choose scheduled Lambda expiry and 30-day GitHub artifact retention.

**Why:** Templates and workflow jobs need one reproducible contract rather than scattered hidden defaults. Per-run networking avoids shared mutable state and idle endpoint resources. Region/account identity cannot be inferred safely from repository code, so the environment supplies them explicitly. Fixed JVM budgets need declared memory compatibility; sample/phase maxima must fit job/expiry timing. Database upgrades/storage changes must not silently alter a measured run.

**Alternatives considered:** Reusing persistent networking reduces provisioning time but adds shared setup/lifetime dependencies. NAT/public internet can fetch dependencies directly but is unnecessary when immutable artifacts are staged through S3. Hardcoding a region or guessing unavailable engine/instance replacements risks testing a different configuration. An entirely frozen image/runtime catalog is optional through pins; the default records exact resolved versions rather than claiming cross-run binary identity.

**Verification:** `performance/contracts/validate.py --preset performance/presets/aws-private-jvm-v1.json` passes workload-schema, subnet/connectivity, heap/window, sampling/histogram, and lifecycle compatibility checks. The existing six independent contract tests also pass. No Java/application behavior changed in this task. Instance/engine availability, quota, resolved runtime artifacts, and actual IAM/network behavior require protected preflight and later AWS validation; this task creates no AWS resources.

### 2026-10-16 — Run phases without AWS privileges and enrich resources offline

**Decision:** Add executable `run` and `finalize-report` commands. Run owns only harness services and the optional local sampler; deployment owns Sendium, PostgreSQL, and remote agents. Start listeners before application readiness and expose atomic state. Include pending acknowledgement writes during drain, seal accounting before teardown, and suppress intentional post-cutoff cancellations. Merge generated sanitized effective configuration into reports. Resource finalization replaces only resource evidence/reasons, retaining workload outcomes and failures.

**Why:** Reusable components alone were insufficient for deployment execution. External AWS diagnostics arrive after traffic and must not require provisioning credentials inside the workload/report JVM. Stopping on receipt arrival without awaiting acknowledgement can leave Sendium's database delivery incomplete. Teardown cancellations must not fabricate admission failures outside observation. HTTP timeouts must cover stalled bodies, not just connections/headers.

**Alternatives considered:** Running report code in a provisioning job weakens privilege separation; use an unprivileged publishing job after protected diagnostic retrieval. Re-running traffic to include resource artifacts changes the measurement. A fixed post-receipt delay hides acknowledgement races; track actual pending writes instead. Allowing caller-supplied metadata to omit effective generated settings loses reproducibility, so the runner loads and verifies its sanitized configuration copy.

**Verification:** All 111 Java unit/socket tests and eight packaged Sendium/PostgreSQL integration scenarios pass, including four separate executable-JAR runs covering both ingress modes and DLR settings. CLI tests verify setup failure reports, readiness timeout/listener cleanup, immutable offline enrichment, and post-cutoff cancellation; stalled-body admission timeout is checked separately. Fifteen Python tests pass. Actual CLI reports and synthetic reports pass schema/accounting/window validation. A live Linux check verifies graceful sampler termination with a final CPU interval. Windows functional CLI cases intentionally lack production resource evidence and remain inconclusive; live AWS orchestration/credit collection remains Phase 4/6 work.

### 2026-10-15 — Verify accounting independently and reject invalid transitions atomically

**Decision:** Add cross-component race/boundary verification and an independent Python semantic validator for result artifacts. Validate receipt/admission timestamps before state mutation, use canonical issued token spelling, and preserve same-tick updates within half-open observation windows. Reconcile overall/cohort/event counters and latency samples, including declared minute-level target capacity, before claiming valid measurements.

**Why:** New tests reproduced invalid receipt timestamps acknowledged as valid, malformed token aliases treated as expired history, and early same-tick finalization losing or misattributing already-recorded events. Atomic retained-entry lookup avoids forgetting known identity/duplicates between repeated expiry checks in one operation. Overall totals alone cannot reveal incorrect minute distribution or drain-inflated throughput.

**Alternatives considered:** Weakening boundary assertions would hide attribution errors. Moving absolute deadlines would change the configured workload; only early exclusive close advances by a single nanosecond when required to contain a previously observed update. Treating all numeric token spellings as interchangeable masks malformed correlation data. Reusing only producer logic to validate artifacts would miss shared aggregation defects.

**Verification:** All 105 Java unit/socket tests and four packaged Sendium/PostgreSQL scenarios pass. Nineteen new Java checks cover accepted/timed-out admission orderings, warm-up completions during drain, exact drain cutoff, invalid time/identity mutation, token history aliases, same-tick close, minute distribution, and failure precedence. Six independent semantic-validator tests plus eight resource-helper tests pass. Generated reports pass schema v1 and accounting/window validation, including coherent artifacts from failed runs. Task 3.5 remains the next step for runnable orchestration.

### 2026-10-15 — Classify resource evidence without performance utilization thresholds

**Decision:** Collect Linux process/host samples with standard-library Python agents and import bounded JSONL evidence into the harness. Keep AWS instance/credit queries in protected orchestration through a read-only CLI helper. Use actual instance-type burstability metadata and Standard/Unlimited state, preserving missing/partial CloudWatch values. Publish weighted overall/minute CPU and memory summaries with valid/inconclusive/failed classification and explicit exit status.

**Why:** Memory is not available from standard EC2 CloudWatch metrics, and process/host measurements need distinct CPU denominators. CPU calculations use local monotonic deltas; UTC aligns resource windows without introducing cross-host workload-latency subtraction. Missing evidence must not appear as zero utilization. High utilization, normal overload rejection, and unfinished accepted work are measured outcomes, not arbitrary performance gates. Application/harness execution must not receive AWS provisioning credentials.

**Alternatives considered:** Giving the harness AWS access expands its trust boundary unnecessarily. Treating one-core process CPU as host-wide percentage misstates multithreaded JVM utilization. Filling missing CPU-credit samples with zero creates false credit-exhaustion evidence. Applying CPU/rejection/latency thresholds would exceed the issue's V1 scope. Strict and best-effort resource import are both available so failed diagnostics do not erase other good sources.

**Verification:** All 86 Java unit/socket tests pass; all four packaged Sendium/PostgreSQL scenarios passed during integration regression checks. Eight Python tests cover proc parsing, CPU denominators, PID reuse, sample budgets, AWS metadata/modes, coarse windows, missing values, and partial failures. A Linux Docker check sampled a live busy process with stable identity, positive CPU counters, RSS and host metrics. Generated resource-bearing reports validate against schema v1. AWS API tests use controlled responses; real-account collection/permissions and remote agent orchestration remain validation work in Tasks 4.5/6.1, with CLI wiring in 3.5.

### 2026-10-15 — Separate cohort accounting from minute-window throughput in reports

**Decision:** Reuse shared counter/histogram updates for overall and bounded submission-cohort aggregates, preserving totals after correlation eviction. Record event-time minute output and backlog changes separately. Publish JSON schema v1 and Markdown with overall/interval counts, percentiles, drain work, and explicit unavailable-resource status. Anchor the lifecycle drain budget at the measurement deadline so stop/join time cannot silently extend it. Add an explicit pending Task 3.5 for runnable harness orchestration before AWS work.

**Why:** Counting drain completions as measurement throughput hides backlog. Timestamp-preserving early receipt replay must not create a negative historical accepted-message backlog. Retaining per-message event history would grow with traffic volume. The existing plan covered reusable components but did not explicitly own the executable command that connects them.

**Alternatives considered:** Cohort completion rates are misleading as sustainable sending rates, so provider/receipt rate fields in that view are null and actual rates appear in event intervals. Reconstructing intervals from retained message history loses completed/evicted messages. An unlimited number of per-minute histograms risks memory growth; the default explicit window budget is 256 and is recorded/configurable. Resource placeholders are labeled unavailable/inconclusive rather than published as zero utilization or valid measurements.

**Verification:** All 74 unit/socket tests and all four packaged Sendium/PostgreSQL integration scenarios pass. Ten report tests cover partial/exact minute boundaries, bulk missed-target attribution, drain completions, original SMPP receipt timestamps, post-eviction totals, setup failure, memory budget, early termination, duplicate/out-of-window diagnostics, stable freeze, and overwrite refusal. A lifecycle regression check proves delayed stop does not extend the drain deadline. Generated Java reports were validated with the Draft 2020-12 version 1 schema. Task 3.3 owns resource sampling/full classification; Task 3.5 will wire production report execution.

### 2026-10-15 — Preserve offered load with absolute deadlines and explicit missed targets

**Decision:** Schedule targets against absolute monotonic deadlines, independently of admission completions. Skip expired backlog rather than replaying catch-up batches. Count scheduling and capacity misses separately, preserving original scheduled timestamps in the ledger. Use one bounded phase worker with explicit stop/join and expose requested/achieved rates, lateness, saturation, and early termination. Include the final quiet interval in the input-rate denominator.

**Why:** Response-paced traffic understates offered load when Sendium slows down. Catch-up batches change the workload after harness stalls. A shortened denominator after the final submission overstates achieved rate. The generator must stop and join before phase changes without interrupting outstanding adapter work needed for drain.

**Alternatives considered:** Sleeping a fixed period after each submission accumulates drift. Waiting for acknowledgements turns the generator into a closed-loop client. Enqueuing every overdue target grows memory and creates artificial bursts. A caller-cancellable internal phase future could permit new phases to queue while traffic still runs, so the public future is an isolated result copy.

**Verification:** All 63 unit/socket tests and all four packaged Sendium/PostgreSQL scenarios pass. Thirteen scheduler tests cover exact/half-open and fractional slots, late starts, slow dispatch, bounded outstanding work, warm-up exclusion, early stop, cancellation/overlap protection, zero-duration phases, adapter failures, lifecycle stop-before-drain, and actual HTTP/SMPP adapters with slow responses. Reporting and validity integration remain Tasks 3.2/3.3.

### 2026-10-15 — Verify packaged Sendium against disposable PostgreSQL before AWS integration

**Decision:** Add an explicit `performance-integration` Maven profile running four local packaged-JVM scenarios. Reuse generated runtime bundles and harness adapters, with isolated databases/ports/credentials per case and a suite-owned PostgreSQL 17 container. Verify storage operation metrics and final table state as well as protocol outcomes. Keep these functional checks separate from fixed-rate performance measurement.

**Why:** Adapter tests alone cannot establish compatibility with Sendium's actual configuration loading, routing, PostgreSQL tracking, and receipt forwarding. Database metrics prove DLR-enabled runs exercise storage even when completed rows are deleted before inspection. The opt-in profile keeps ordinary harness checks Docker-independent.

**Alternatives considered:** Embedded or mocked Sendium would not verify the packaged application boundary. Testing against shared local database state risks contamination. Running benchmarks as functional assertions would introduce premature performance thresholds. An application container is unnecessary for this JVM check: a separate Java process consumes the actual Quarkus package and generated working directory.

**Verification:** Built `sendium-app` with `./mvnw -DskipTests -pl sendium-app -am package`; then `./mvnw -Pperformance,performance-integration -pl performance/harness verify` passed all 50 unit/socket tests and all four integration scenarios. DLR-enabled scenarios recorded five provider acceptances/resolutions and successful downstream completions, no storage errors, and empty final tracking/correlation tables. DLR-disabled scenarios emitted no receipts or tracking writes. Measurement accounting included three messages while excluding two warm-up messages. Application processes and the test database were stopped after execution.

### 2026-10-15 — Generate private runtime bundles and sanitized effective settings

**Decision:** Generate Sendium configuration, selected ingress credentials, one provider route, harness endpoints/credentials, and a separate public effective-configuration artifact from resolved workload/deployment inputs. Refuse existing output directories. Preserve the application's build-time PostgreSQL subsystem in both DLR modes; disable receipt requests/callbacks at the workload level. Set explicit worker TPS, windows, timeouts, one outgoing transceiver, no provider-response retries, and reduced per-message logging.

**Why:** Packaged Sendium enables DLR persistence during Quarkus augmentation; disabling it at runtime would change or break the tested application boundary. Consistent generated credentials/endpoints prevent hand-edited scenarios from diverging. Runtime secrets must not enter downloadable results. V1 excludes retry workloads, and logging settings affect benchmark reproducibility.

**Alternatives considered:** Rebuilding a different application for DLR-disabled runs changes the tested artifact. Publishing runtime configuration verbatim leaks credentials. Reusing a mutable output directory risks mixing settings and database state across runs. Leaving provider-response retry defaults enabled can create duplicate traffic when the harness saturates.

**Verification:** All 50 harness tests pass, including eight configuration tests covering four ingress/DLR combinations, YAML credential parsing, properties/JSON round trips, runtime credential consistency, sanitized publication, independent credentials, invalid input/capacity, file ownership boundaries, and CLI overwrite refusal. The executable JAR also generated a fresh bundle successfully. POSIX permission assertions are conditional and were not exercised on this Windows host. Packaged Sendium/PostgreSQL execution remains Task 2.5.

### 2026-10-15 — Preserve arrival timestamps while buffering early SMPP receipts

**Decision:** Use one transceiver session with bounded virtual-thread admission tasks. Require the local window to cover outstanding request capacity. Buffer gateway-ID-only receipts until acknowledgement mapping is available, bounded by tracked-message capacity, preserving original arrival timestamps and duplicates. Distinguish unmatched receipts from mapping limitations caused by history eviction or ambiguous admission, including warm-up.

**Why:** Sendium's forwarded SMPP receipt has a gateway ID but does not echo the payload token. An early receipt cannot be mapped immediately, and recording its replay time would incorrectly include acknowledgement wait in forwarding latency. Unknown mappings after transport timeouts do not prove invalid traffic.

**Alternatives considered:** Assuming receipt text echoes the token contradicts Sendium's implementation. Rejecting early receipts manufactures retry traffic. Unbounded buffering violates harness memory requirements. A smaller local window introduces generator-side waiting that can be misrepresented as application latency.

**Verification:** All 42 harness tests pass, including nine SMPP adapter tests over real sockets: reports enabled/disabled, early/duplicate receipt timestamps, rejection versus malformed acknowledgements, single-slot timeout/window cleanup, bounded unknown-receipt buffering, retained-history and ambiguous-admission limitations, invalid receipt states, bind failure, and pending-request cancellation. Full Sendium/PostgreSQL verification remains Task 2.5.

### 2026-10-14 — Correlate HTTP callbacks with token and gateway acknowledgement identity

**Decision:** Include the run-local token plus Sendium's `%s` gateway ID and `%d` delivery status in each callback template. Validate callback identity against the admission response even when receipt reception precedes acknowledgement. Keep a bounded gateway-ID index to detect reuse. Use bounded asynchronous HTTP requests and a fixed-worker, bounded-queue callback receiver; expose saturation. Start latency timing after request construction.

**Why:** Callback delivery can race with HTTP response consumption. Waiting for acknowledgement mapping before accepting a valid callback would create artificial retries. Token correlation preserves early outcomes while later identity validation detects mismatches. Body/query limits and bounded callbacks preserve the harness memory budget.

**Alternatives considered:** Gateway-ID-only lookup loses early callbacks unless another queue is introduced. A blocking submission loop reduces offered load when admission slows. Following redirects obscures API errors and exposes credentials beyond the configured endpoint. Treating server errors as confirmed rejection discards potentially admitted work.

**Verification:** All 33 harness tests pass, including ten HTTP adapter tests for encoded parameters, callback-before-acknowledgement, delivery reports disabled, overload/authentication/server/redirect outcomes, timeout ambiguity, request limits, malformed/oversized response bodies, duplicate/mismatched callback identities, queue saturation, expired-history limitations, and cancellation. A ledger test additionally verifies the dispatch latency boundary. These controlled-endpoint tests do not substitute for packaged Sendium and PostgreSQL verification in Task 2.5.

### 2026-10-14 — Execute provider delays outside network event loops

**Decision:** Reuse the repository-managed Cloudhopper version and JitPack repository. Respond asynchronously from permit-bounded virtual-thread tasks; each permit covers response delay/write, receipt delay, and bounded receipt acknowledgement. Authenticate isolated binds and validate single-part known correlation tokens. Diagnose simulator saturation separately from Sendium overload. Delivery-report runs require transceiver binds.

**Why:** Sleeping in a request callback serializes unrelated traffic on the Netty event loop and creates an artificial provider bottleneck. Unlimited per-message tasks would hide saturation through memory growth. The simulator must keep responding during drain and report protocol failures without claiming a forwarded receipt was received merely because its upstream acknowledgement succeeded.

**Alternatives considered:** Event-loop sleeping is simple but distorts throughput. An unbounded delayed-task queue does not meet bounded bookkeeping requirements. Separate transmitter/receiver session pairing adds connection-routing complexity unnecessary for the initial single-provider scenario.

**Verification:** All 22 harness tests pass. Seven real TCP/SMPP tests cover delay boundaries, receipt ID/address/status, disabled delivery reports, unique response IDs, invalid/multipart/unknown submissions, bound authentication, pending-work saturation, negative receipt acknowledgements, and cancellation/resource release. Packaged-JAR configuration smoke verification also passes. Cloudhopper response writing waits for local channel-write completion, matching the documented simulator-side latency boundary.

### 2026-10-14 — Bound active correlation and retired duplicate history separately

**Decision:** Use `MessageLedger` to serialize asynchronous event updates, retaining unfinished/ambiguous messages within `maxTrackedMessages` and bounding admission requests independently. Generate `runToken:base36Sequence` payload identities. Retain completed entries in a separately capped, age-limited duplicate history; expose evictions and events beyond retention. Use HdrHistogram at three significant digits, microsecond resolution, and a fixed one-hour maximum with explicit overflow counts.

**Why:** Slow downstream processing must produce visible missed generation rather than unbounded memory or lost unfinished work. Provider and receipt events can precede ingress acknowledgements. Bounded duplicate history cannot guarantee an unlimited detection horizon; reporting must disclose coverage limitations instead of silently overstating correctness.

**Alternatives considered:** Keeping every completed message grows with test volume. Evicting unfinished work corrupts drain accounting. Storing individual latency samples also grows with volume. Inferring acceptance from provider arrival hides ambiguous transport outcomes.

**Verification:** All 15 harness tests pass, including concurrent callbacks, pre-acknowledgement completion, warm-up exclusion, request/tracking bounds, timeout ambiguity, duplicate/contradictory events, history expiry, frozen accounting, percentile accuracy, and histogram overflow. Repeated packaging now recreates the unshaded JAR before shading to avoid ingesting a previous shaded artifact.

### 2026-10-14 — Package the harness through an opt-in Maven profile

**Decision:** Add `performance/harness` to the reactor only under `-Pperformance`. Package an executable shaded JVM JAR. Provide strict resolved-configuration validation and a lifecycle controller with injectable monotonic time and a protocol scenario interface. Protocol execution is added in Phase 2.

**Why:** This reuses repository Java/dependency management without adding benchmark work to ordinary builds. Deterministic lifecycle tests exercise bounded drain, completion, readiness failure, and interrupted generation without waiting through full phases.

**Alternatives considered:** Always adding the module increases routine build scope. A separate unrelated Maven project duplicates managed versions. Real-clock lifecycle tests would be slower and less deterministic.

**Verification:** `./mvnw -Pperformance -pl performance/harness verify` passed all five tests; the shaded JAR successfully validated the fixture via `validate-config`. Readiness, generation, and finalization hooks are implemented by later protocol/accounting tasks; the current executable does not claim to run a benchmark.

### 2026-10-14 — Define workload and measurement contracts before runtime code

**Decision:** Store version 1 contracts in `performance/contracts/`. Use the harness monotonic clock for workload latencies, submission-time cohorts for accounting, and separate event-time intervals for actual completion throughput. Preserve ambiguous admission outcomes and their provider observations. Treat setup/accounting failures as failures and harness limitations as inconclusive; unfinished work alone is not a performance gate.

**Why:** Generator, simulator, and receipt receivers reside on one harness instance, so latency calculation does not require synchronized cross-host clocks. Cohort-only throughput could hide backlog and drain completion. Explicit contracts prevent orchestration and reporting from interpreting outcomes differently.

**Alternatives considered:** Cross-host wall-clock timestamps introduce avoidable synchronization error. Attributing all completions to their submission minute without event-time output hides delayed work. Treating transport timeouts as confirmed rejection loses potentially accepted messages.

**Verification:** Draft 2020-12 schema checks and `performance/contracts/validate.py` pass, including external workload reference resolution, valid setup-failure output, and invalid rate, duration, capacity, type, count, and latency boundaries. Arithmetic invariants and contextual configuration consistency require runtime validation in later tasks.

### 2026-10-14 — Make CloudFormation and workflow deliverables explicit

**Decision:** Use CloudFormation for AWS setup and split bootstrap, networking, and per-run resources into named tracker deliverables. Explicitly create `.github/workflows/performance-tests.yml`, with separate build, protected execution, publication, and verification tasks. Add scenario configuration, local integration, measurement correctness, lifecycle verification, and acceptance coverage to the plan.

**Why:** The user identified that the initial plan obscured AWS setup and workflow creation. The user approved the proposed corrections after review against issue #352.

**Alternatives considered:** Retaining generic provisioning/workflow tasks and leaving the infrastructure tool open would make completion and ownership harder to review. CloudFormation now replaces that unresolved tool choice; detailed networking lifetime and expiry design remain open.

### 2026-10-14 — Use issue #352 as the authoritative V1 specification

**Decision:** Adopt the issue's goals, scope, required architecture, defaults, acceptance criteria, and deferrals without asking the user to reconfirm them.

**Why:** The user explicitly confirmed that the need is on-demand performance testing and requested that questions already answered by the issue not be repeated.

**Alternatives considered:** Broader capacity-investigation or future-regression-tool framings were proposed and were unnecessary; the issue already defines the intended product.

### 2026-10-14 — Create the living document before implementation

**Decision:** Create this initial plan on `feat/352-aws-performance-tests` for user review before implementing the first task.

**Why:** The work spans harness, application configuration, AWS infrastructure, and workflow execution; the user requested the document first.

**Alternatives considered:** Starting implementation from the chat-only task list would leave decisions and progress without a durable repository record.

---

## 6. Open questions

These are implementation details absent from the issue. Resolve repository-discoverable details through inspection before asking the user.

- Available AWS account, region, OIDC role, protected GitHub environment, and any account-specific restrictions needed for an actual AWS validation run.
- Confirm actual regional AMI/JRE/database resolution and availability against the selected account before execution (Tasks 4.5/6.1).
- Diagnostic artifact assembly/redaction and repository retention-policy compatibility require workflow verification (Tasks 5.3/5.4); the preset requests 30 days.
- Verify release automation updates the opt-in harness module/parent versions before final workflow validation (Tasks 5.1/6.2).

---

## 7. Lessons learned

### Phase 1 — Contracts and harness foundation

- Provider completion may precede the ingress acknowledgement, so accounting must tolerate that ordering without losing completion counters.
- Bounded completed-message retention requires explicit coverage limitations as well as duplicate counters.
- Repeated shaded builds need the original application JAR recreated before shading; otherwise Maven can shade an already shaded artifact.
- Phase 1 establishes reusable runtime components; actual protocol traffic, minute reporting, AWS infrastructure, and workflow execution remain tracked in later phases.

### Phase 2 — Protocol scenarios and packaged application verification

- Generated credentials, callback addresses, and provider namespaces work with the actual packaged application for both ingress modes.
- DLR-disabled traffic does not require a different application build: the standalone PostgreSQL subsystem remains configured while message-level receipt requests are omitted.
- Successful forwarding deletes tracking rows quickly. Storage operation metrics plus empty final tables provide evidence of the full database-backed path without depending on a transient row observation.
- A separate packaged JVM and disposable database are sufficient for local functional verification; fixed-rate scheduling, minute reports, resource sampling, and AWS execution remain subsequent work.

### Phase 3 — Measurement, reporting, and executable orchestration

- Cross-component boundary tests found defects that isolated counters/schema checks could not: invalid timestamp acceptance, token aliases, and same-tick finalization.
- Receipt arrival is a measurement boundary, but an acknowledgement still needs to finish before network teardown; drain must observe both.
- Sealing accounting before cleanup prevents deliberate cancellation from changing pending admissions into synthetic transport failures.
- Resource diagnostics can be enriched after traffic without re-execution when their reason scope and observation identity are explicit. Workload failures remain immutable.
- Separate executable-JAR checks now cover the complete generated configuration/phase/report path before AWS provisioning work begins.
