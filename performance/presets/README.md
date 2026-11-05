# AWS/test presets

`aws-private-jvm-v1.json` is the versioned input contract for the forthcoming
CloudFormation templates and deployment resolver. It is not a resolved harness
input and does not provision anything by itself. Tasks 4.2–4.5 implement that
integration. Static compatibility is checked with:

```sh
python -m pip install -r performance/contracts/requirements-dev.txt
python performance/contracts/validate.py --preset performance/presets/aws-private-jvm-v1.json
```

## Starting configuration

| Setting | Default |
|---------|---------|
| Region | Required `AWS_REGION` in the maintainer-controlled environment; no guessed/fallback region |
| GitHub environment | `sendium-performance` |
| Sendium EC2 | `t4g.small` — ARM64, 2 GiB RAM; minimum 2048 MiB for this preset |
| Harness EC2 | `t4g.small` — ARM64, 2 GiB RAM; minimum 2048 MiB for this preset |
| EC2 architecture | Resolve each selected type independently; `x86_64` and `arm64` supported |
| OS | Amazon Linux 2023 standard AMI, region/architecture resolved before provisioning |
| JVM | Temurin Java 25 JRE, G1; fixed 512 MiB heap for each JVM |
| Root disks | Encrypted gp3 at the resolved AMI root-device minimum, typically 8 GiB; deleted with their instances |
| Burstable mode | Standard when supported; omit the EC2 credit specification for non-burstable types |
| Database | Private, encrypted RDS PostgreSQL 17, `db.t4g.small`, Single-AZ, 20 GiB gp3 (minimum new SSD allocation) |
| Database authentication/TLS | Run-owned Secrets Manager credentials; verified TLS using a staged RDS CA bundle |
| DLR subsystem | PostgreSQL stays configured in both message-level DLR modes |
| Workload | HTTP, 30 offered messages/sec, worker TPS 0 (unlimited), DLR enabled |
| Phases | 120-second warm-up, 600-second measurement, 120-second maximum drain |
| Simulator | Successful single-part responses after 5 ms; delivered receipt after 1000 ms |
| Concurrency | 1000 outstanding admissions; 100,000 tracked entries; explicit 1000-entry SMPP windows |
| Sampling | One second, 8192 samples/source; 256 minute-window budget |
| Result retention | GitHub artifact: 30 days; temporary S3 objects: one-day fallback expiration |

These are low-cost temporary-run defaults, not throughput targets, regression gates, or a
claim that a particular offered rate is sustainable. EC2 types, ingress, rate,
worker TPS, durations, DLR flag, and simulator delays remain dispatch inputs.
The fixed JVM settings make cross-instance runs interpretable; smaller-memory
experiments need a separate preset rather than silently changing heap sizes.
Memory minimums are setup compatibility bounds, not performance thresholds.

## Private, run-owned networking

Each run gets a dedicated VPC and network stack. Cleanup removes the endpoints
as well as compute/database resources, avoiding idle endpoint charges and shared
mutable configuration. The default `/24` has two non-overlapping `/26` private
subnets in two available AZs. The first AZ is the first common offering for both
selected EC2 types and the database class; the second is a different available AZ.
Both EC2s use the first AZ. The RDS subnet group
spans both AZs; the Single-AZ database is requested in the first AZ and its
actual placement is recorded.

There is no public IP, internet gateway, NAT gateway, SSH ingress, or public
benchmark port. Private DNS is enabled for interface endpoints `ssm`,
`ssmmessages`, and `secretsmanager`; a gateway endpoint provides S3 access.
Endpoints live in the first AZ with the clients. Current AL2023/SSM Agent support
for `ssmmessages` is a preflight requirement; the preset does not depend on the
legacy `ec2messages` service. CPU-credit queries run on the Actions runner, so
the workload instances do not need a CloudWatch API endpoint. Diagnostics and
SSM output use S3 rather than a CloudWatch logging agent.

Role-to-role security-group rules:

| Initiator | Destination | TCP ports |
|-----------|-------------|-----------|
| Harness | Sendium | 8080 HTTP/readiness, 27777 SMPP ingress |
| Sendium | Harness | 27779 simulated provider, 8081 HTTP receipt receiver |
| Sendium only | RDS | 5432 PostgreSQL |
| Both EC2s | Management endpoint group | 443 |
| Both EC2s | Regional S3 prefix list | 443 |

DNS uses the VPC resolver. The harness has no database access requirement.
Instances use IMDSv2 with hop limit 1 and run-scoped S3/SSM permissions, never
provisioning permissions. Networking is a separate template for review but is
owned/deleted by the run's parent stack.
Runtime roles need the agent's control-channel operations, not account-wide
Parameter Store reads. Public AMI-parameter lookup belongs to protected preflight;
database-secret access is limited to the Sendium run's secret.
Resolve the regional S3 managed prefix-list ID during preflight and pass it to
the networking template. Non-burstable roles record `creditMode=not-applicable`;
the preset's Standard setting applies only when burstability metadata is true.

## Resolve before running

Deployment preflight must verify:

1. The intended account, explicit region, and two available AZs; selected EC2
   types are offered there, support a listed Linux architecture, and meet the
   preset's memory budget. Do not silently substitute another type or AZ policy.
2. Exact AL2023 AMI IDs from the named public SSM parameters, or explicit `amiIds`
   pins. Record parameter versions, image IDs, architecture, AZ names/IDs, and
   OS/SSM-agent versions. Resolve the AMI's `RootDeviceName` against its
   `BlockDeviceMappings` and use that device's `Ebs.VolumeSize` for root storage;
   fail preflight if the size is unavailable rather than guessing or padding it.
   Record the resulting size separately for each instance. Check `python3`, AWS
   CLI v2, `tar`, and `gzip` before use.
3. Exact Java 25 release/archive per selected architecture and its checksum.
   Null `release` selects the current Java 25 GA release during the unprivileged
   build stage. Stage immutable, checksummed runtime artifacts in the run's S3
   prefix. Do not download mutable runtime packages or install dependencies on
   isolated instances during warm-up/measurement.
4. An available PostgreSQL 17 minor and orderable `db.t4g.small` configuration in
   the region/AZ. Null `engineVersion` resolves the latest available minor within
   major 17 before provisioning. No engine/class fallback. Pass the exact resolved
   version into CloudFormation and record it in results.
   Automatic minor/major upgrades and storage autoscaling are disabled for the run.
5. The RDS CA bundle is included in deployment artifacts with a digest, and the
   JDBC URL uses `sslmode=verify-full` plus the preset's CA path. Set RDS
   `rds.force_ssl=1`. Credentials are separate from public JDBC configuration.

RDS PostgreSQL requires at least **20 GiB** for a new gp2/gp3 instance; a shorter
run does not lower that minimum. See the AWS [CreateDBInstance storage
constraints](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBInstance.html).
Storage autoscaling, retained backups, and final snapshots stay disabled for
these temporary databases. Both EC2 defaults are burstable, so their actual
Standard mode and CPU-credit observations must be recorded. RDS burstable credit
behavior is service-managed, not set through the EC2 credit specification.

The preset's rolling AMI/JRE/minor selectors are resolved once per run, not during
measurement. Every result must identify their resolved values/digests. Maintainers
can copy the preset under a new ID and set explicit pins for strict repeat runs.
Never overwrite an existing preset identity with materially different settings
without preserving the resolved settings and preset file digest in results.

## Resolver outputs

The deployment resolver merges only essential dispatch overrides, validates all
bounds before creating temporary resources, and produces:

- **RunConfig:** the `workload` object, with requested inputs applied. Default
  warm-up/measurement/drain and DLR settings match issue #352.
- **BenchmarkDeployment:** `deployment` values plus run identity, preset ID,
  actual private hosts, and database JDBC/authentication/pool settings. All ports,
  windows, concurrency, worker threads, timeouts, and callback settings are explicit.
- **ExecutionSettings:** readiness/retention/interval budgets, sampler settings,
  exact branch/SHA/run URL/artifact identities, and actual JVM/infrastructure
  metadata. Reader/agent sample budgets must agree. UTC clock synchronization uses
  Amazon Time Sync Service; record clock setup before generation.
- **CloudFormation parameters:** selected types, independently resolved
  architectures/AMIs/burstability, zone/subnet data, database version/class/storage,
  lifecycle tags, and runtime artifact identities. Workload instances receive no
  OIDC/provisioning credentials.

The resolver belongs to Task 4.5; templates consume this contract in Tasks
4.2–4.4. The current validator checks workload schema, private subnet layout,
heap/window compatibility, maximum-duration sample/histogram budgets, and
execution/expiry timing. It does not establish live AWS availability.

## Lifetime and bootstrap

Default limits allow 0–600 seconds warm-up, 1–3600 seconds measurement, and
0–600 seconds drain. These keep V1 out of extended-endurance scope. Generator
rates cannot exceed nanosecond schedule resolution. Provider response delay
stays below its application request timeout. No measured-performance thresholds
are applied.

Provisioning has a 45-minute budget, readiness three minutes, and retrieval
15 minutes. The execution job gets 180 minutes; separate cleanup gets 30 minutes.
Run resources expire after 240 minutes, with an independent EventBridge-scheduled
Lambda checking expiry every 15 minutes. Maximum phase/sample budgets fit those bounds. Partial creation and
failed stacks remain expiry-owned. RDS has no backups, retained snapshots, or
deletion protection: CloudFormation deletion/update-replacement policies are
explicitly `Delete`. Test state is never production state.

Bootstrap resources are persistent: OIDC integration/roles, private artifact
storage, and independent expiry scheduling/permissions. Temporary runtime/config
objects live under the run prefix; immediate cleanup is primary and one-day S3
expiration is fallback. Public result artifacts are uploaded to Actions for
30 days, not permanent storage. Database secrets are run-owned, 32-character
alphanumeric values to avoid configuration interpolation punctuation.

Before first execution, a maintainer installs bootstrap CloudFormation, configures
the protected GitHub environment/reviewers and region/role/bucket settings, and
provides quota/capacity for the selected resources. Reuse an existing account-level
GitHub OIDC provider when present. Keep AWS preflight/provision/diagnostic jobs
separate from application builds/execution and offline publishing. Actual account
prerequisites and permissions are verified during AWS lifecycle testing, not
assumed from these static settings.
