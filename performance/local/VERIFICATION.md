# Local lifecycle verification coverage

Current workload results are recorded in
[AVERAGE_RATE_VALIDATION.md](AVERAGE_RATE_VALIDATION.md): DLR-enabled HTTP/SMPP
at 100 msg/s and the 30-msg/s default, with full-duration phase settings.
Superseded pre-fix raw artifacts and load-result documents have been removed.

The following summary retains Task 5.5's independently useful lifecycle/failure
coverage from the earlier functional rehearsal. It is not a current load
qualification. Reproduction tooling emits source fingerprints, validated reports
and cleanup inventories as described in [README.md](README.md).

| Case | Observation status | Pipeline exit | Rehearsal assertion |
|---|---|---:|---|
| Database unavailable | failed | 1 | Actual database stopped; setup failed and cleanup completed |
| Invalid CA | failed | 1 | Real certificate verification rejected the wrong CA |
| Application crash | inconclusive | 1 | Actual JVM killed after measurement began; observations retained and lifecycle failed |
| Controller interrupted | failed | 1 | SIGTERM during measurement reached production interruption/stop/retrieval paths |
| Partial staging | failed | 1 | Recovery metadata/partial uploads removed without a root stack |
| Partial provisioning | failed | 1 | Database/one-node creation failure recovered without leaked resources |
| Abandoned run | failed | 1 | Real expiry code reported pending, then confirmed deletion/purged prefix and incomplete upload |
| Concurrent run | valid | 0 | Other live workload survived cleanup; traffic and private input scopes stayed isolated |

All positive cases observed actual PostgreSQL TLS connections and rejected
plaintext; harness-to-database network/secret probes were denied. Concurrent
verification additionally denied cross-run traffic and input reads. Every cleanup
was repeated successfully. Each case's report passed schema and independent
accounting/window validation; known database/protocol values were absent from
public artifacts. The final session inventory reported **0 containers, 0 networks
and 0 volumes remaining**.

A later export-filter regression plus a positive HTTP/DLR smoke case also passed. The filter
redacts detected known values before host export and forces rehearsal failure;
this is an additional check, not a hostile-code security sandbox.

Real components include separate AL2023 nodes, Docker cgroup limits, Temurin Java
25, LocalStack S3/Secrets APIs, PostgreSQL 17/verified TLS, traffic, sampling,
reporting and physical Docker teardown. EC2/RDS/CloudFormation metadata, SSM,
systemd, IAM scope and scheduled time are explicitly modeled. Production presets
are unchanged. The local reports use local execution/type labels and do not claim
EBS/gp3/encryption, burst-credit, IAM, managed-endpoint or hosted-runner fidelity.

Task 6.1 still needs a configured AWS account/region, installed bootstrap/expiry,
protected GitHub environment and actual hosted/IAM/resource lifecycle validation.
This rehearsal satisfies the local prerequisite without deploying AWS resources.
