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
| 2 | 2.5 | Add local packaged-JVM scenario verification | M | ⚪ not started | Run Sendium, the harness, and PostgreSQL together; verify both ingress modes with delivery reports enabled and disabled before AWS integration. |
| 3 | 3.1 | Schedule fixed-rate traffic with bounded outstanding requests | M | ⚪ not started | Count scheduled, attempted, and missed submissions; expose achieved rate and harness saturation. |
| 3 | 3.2 | Produce overall and one-minute measurement reports | M | ⚪ not started | JSON and human-readable output; throughput, percentiles, admission outcomes, receipts, backlog, and drain results. |
| 3 | 3.3 | Collect resource utilization and classify run validity | M | ⚪ not started | Sendium and harness CPU/memory; burstable-instance CPU-credit balances and Standard/Unlimited mode; classify harness bottlenecks as inconclusive. |
| 3 | 3.4 | Verify measurement attribution and accounting failure behavior | M | ⚪ not started | Warm-up exclusion, measurement outcomes during drain, minute boundaries, bounded bookkeeping, duplicate detection, timeout reconciliation, missed load, expected overload, and unfinished work. |
| 4 | 4.1 | Define the AWS and workload preset | S | ⚪ not started | Record region, database size, instance architecture, JVM settings, connections/windows, traffic limits, and deployment prerequisites. |
| 4 | 4.2 | Create the AWS bootstrap CloudFormation template | M | ⚪ not started | Persistent artifact storage, GitHub OIDC integration/roles, expiry infrastructure permissions, and outputs; document one-time installation and reuse of an existing OIDC provider. |
| 4 | 4.3 | Create the private networking CloudFormation template | M | ⚪ not started | VPC, private subnets, restricted security groups, and management/artifact connectivity; include subnet placement required by RDS. Define stack ownership and lifetime. |
| 4 | 4.4 | Create the per-run benchmark CloudFormation template | M | ⚪ not started | Sendium/harness EC2, instance profiles, private RDS, isolated credentials, run identity/expiry tags, placement, and deployment outputs. |
| 4 | 4.5 | Implement artifact deployment and execution scripts | M | ⚪ not started | Upload artifacts, render scenario configuration, deploy through Systems Manager, check readiness, run phases, and retrieve results/logs even after scenario failure. |
| 4 | 4.6 | Implement run-owned stack and temporary artifact cleanup | M | ⚪ not started | Idempotent deletion after success, failure, cancellation, and partial provisioning; delete dependent resources in the correct order and surface cleanup failures. |
| 4 | 4.7 | Implement independent expiry of abandoned benchmark runs | M | ⚪ not started | Scheduled expiry outside GitHub Actions; recover tagged expired stacks and temporary artifacts with narrowly scoped permissions; install through bootstrap CloudFormation. |
| 5 | 5.1 | Create the manual GitHub Actions performance-test workflow | M | ⚪ not started | Create `.github/workflows/performance-tests.yml`: manual inputs, validation, exact selected commit, architecture-compatible unprivileged builds, and artifact identities. Resolve input-count constraints without losing configurable settings. |
| 5 | 5.2 | Add protected CloudFormation and SSM execution jobs | M | ⚪ not started | Maintainer-controlled environment, OIDC, stack deployment, per-run isolation, Systems Manager orchestration; benchmark execution receives no provisioning credentials. |
| 5 | 5.3 | Publish workflow reports and add cleanup jobs | M | ⚪ not started | Overall and one-minute summary results, versioned JSON, diagnostic logs, explicit artifact retention, failure-result retrieval, and cleanup paths. |
| 5 | 5.4 | Verify workflow permissions and failure outcomes | M | ⚪ not started | Builds have no AWS provisioning access; validate input handling, protected environment usage, setup/accounting failures, overload reporting, and cancellation behavior. |
| 6 | 6.1 | Validate the complete AWS benchmark and resource lifecycle | M | ⚪ not started | Both protocols, delivery reports on/off, actual resource measurements; provisioning/scenario failure, cancellation, partial creation, abandoned execution, and independent expiry. Requires configured AWS prerequisites. |
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
2. **Private networking:** VPC, subnets, security groups, and connectivity for Systems Manager, artifacts, and required runtime services. RDS requires suitable subnet coverage even when EC2 instances share one Availability Zone. Explicitly document whether this stack is reusable or run-owned before implementation.
3. **Per-run infrastructure:** application and harness EC2 instances, instance profiles, private RDS PostgreSQL, isolated credentials, run/expiry tags, and outputs consumed by deployment scripts.

Bootstrap resources survive individual runs. Benchmark resources and temporary artifacts have run-specific ownership and bounded lifetimes. Cleanup and expiry must handle partially created or failed stacks, not only successfully completed benchmarks. Persistent infrastructure teardown is documented separately from run cleanup.

Provisioning credentials stay with protected workflow orchestration. Application and harness builds do not receive them; EC2 execution roles provide only the runtime access required for deployment, diagnostics, and measurement. Instance metadata and credential exposure must be considered when deploying selected-branch application code.

### GitHub Actions workflow deliverables

Create `.github/workflows/performance-tests.yml` with `workflow_dispatch`. Separate unprivileged build jobs from protected AWS provisioning/control jobs. Record the dispatch commit rather than resolving a moving branch later. Validate inputs before provisioning, carry artifact identities through deployment, retrieve diagnostics on failures, and publish overall plus one-minute results through the summary and downloadable artifact.

The input layout must respect GitHub's supported dispatch-input count while retaining all settings required by the issue. Grouping the three phase durations into one validated timing input is a candidate if the supported limit requires it; confirm the current platform limit during Task 5.1. Document the final syntax and defaults.

Use run identity including the run attempt where necessary to prevent collisions during reruns. Cleanup jobs provide the immediate path; independently scheduled expiry provides recovery if cancellation or runner loss prevents those jobs from completing.

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

- Harness module placement and packaging resolved in Task 1.2; runtime protocol wiring remains tracked in Phase 2.
- Networking stack lifetime and ownership; whether to support existing networking as well as the dedicated CloudFormation network.
- Available AWS account, region, OIDC role, protected GitHub environment, and any account-specific restrictions needed for an actual AWS validation run.
- Independent expiry mechanism and how its persistent bootstrap infrastructure is installed and operated.
- Exact preset values: RDS size, instance architecture/AMI/JDK, connection windows, JVM settings, request limits, and bounded accounting capacity.
- Correlation token encoding, bounded history, and histogram precision resolved in Task 1.3. Protocol-ID mapping is implemented with the adapters; interval publication remains Task 3.2.
- Precise scenario-defined accounting violations and how timeout/unknown admission outcomes reconcile with provider observations.
- Explicit artifact retention period and log redaction of isolated credentials.

---

## 7. Lessons learned

### Phase 1 — Contracts and harness foundation

- Provider completion may precede the ingress acknowledgement, so accounting must tolerate that ordering without losing completion counters.
- Bounded completed-message retention requires explicit coverage limitations as well as duplicate counters.
- Repeated shaded builds need the original application JAR recreated before shading; otherwise Maven can shade an already shaded artifact.
- Phase 1 establishes reusable runtime components; actual protocol traffic, minute reporting, AWS infrastructure, and workflow execution remain tracked in later phases.
