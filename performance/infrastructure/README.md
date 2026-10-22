# AWS performance infrastructure

## Bootstrap (Tasks 4.2 and 4.7)

`bootstrap.yaml` creates persistent control-plane resources:

- Private, owner-enforced, AES256-encrypted S3 artifact bucket; TLS required.
  Temporary `runs/` objects expire after one day by default, with incomplete
  multipart uploads also expired. Versioning is not enabled.
- Account-level GitHub OIDC provider, or reuse of an existing provider ARN.
- Environment-bound GitHub orchestration, read-only diagnostics and deletion-only cleanup roles.
- A CloudFormation service role for run infrastructure and a permissions
  boundary required on the EC2 runtime roles.
- An expiry role and 14-day log group, plus a conditionally installed Lambda,
  retry configuration, enabled EventBridge schedule and invocation permission.
  Initial bootstrap without its package explicitly reports expiry uninstalled.
  Complete [expiry installation](../deployment/EXPIRY.md) before executing runs.

Benchmark networking, `t4g.small` EC2 instances, and `db.t4g.small` RDS are the
separate run templates in Tasks 4.3/4.4. Their sizing/storage contract is
[`../presets/aws-private-jvm-v1.json`](../presets/aws-private-jvm-v1.json).

### Install once

Use the intended AWS account/region and a maintainer's bootstrap identity with
permission to create IAM/OIDC, S3, logs, Lambda and EventBridge resources. The restricted orchestration
role intentionally cannot install or update its own bootstrap stack. No AWS
resources are created by repository validation commands.

```sh
aws cloudformation deploy \
  --region "$AWS_REGION" \
  --stack-name sendium-performance-bootstrap \
  --template-file performance/infrastructure/bootstrap.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    RepositoryOwner=cytechmobile \
    RepositoryName=sendium \
    GitHubEnvironment=sendium-performance
```

If the account already has `token.actions.githubusercontent.com`, supply
`ExistingOidcProviderArn=<same-account-provider-ARN>` in parameter overrides.
Do not attempt to create a second provider for that issuer. The existing provider
must include the selected audience; default `sts.amazonaws.com`, with an explicit
`sts.amazonaws.com.cn` option for accounts using that audience.

`ResourcePrefix` defaults to `sendium-perf` and is limited to 24 lowercase
characters. Named IAM resources are account-global: use a distinct prefix for
another repository/region installation and keep its preset/run namespace aligned
with the outputs. Stack names and role/profile/database names must honor those
prefixes. Run role names must also fit IAM's 64-character limit; do not blindly
concatenate maximum-length identifiers and verbose suffixes.

Configure the named GitHub environment's access/deployment protection according
to maintainer policy and allow the branches intended for manual testing. AWS
trust requires the exact repository/environment subject and audience, not a
branch-only or wildcard repository subject. This template does not create or
configure the GitHub environment.

Use `sendium-performance` for approval-gated provisioning/execution. Configure
`sendium-performance-recovery` (parameter `GitHubRecoveryEnvironment`) with the
appropriate branch restrictions and no required reviewers. Recovery uses the
read-only diagnostics and deletion-only cleanup roles, not the provisioning role.
This lets collection/deletion proceed automatically after success, failure or
cancellation without opening an unreviewed path to creating benchmark resources.

### Outputs used by deployment/workflow code

| Output | Consumer |
|--------|----------|
| `ArtifactBucketName` | Run-scoped private inputs, immutable bundles, SSM output and diagnostics |
| `OrchestratorRoleArn` | Protected preflight/provision/control/cleanup jobs |
| `DiagnosticsRoleArn` | Automatic post-run EC2/CloudWatch reads and output retrieval |
| `CleanupRoleArn` | Automatic deletion-only cleanup of owned run stacks and temporary artifacts |
| `CloudFormationExecutionRoleArn` | `CreateStack --role-arn`; persists for nested-stack deletion/expiry |
| `RuntimeBoundaryArn` | Mandatory boundary on app/harness roles in the run template |
| `ExpiryRoleArn`, `ExpiryFunctionName`, `ExpiryLogGroupName` | Persistent expiry and diagnostics |
| `ExpiryHandlerInstalled` | `false` for initial bootstrap, `true` with installed package; preflight also checks live configuration |
| `ExpiryFunctionArn`, `ExpiryScheduleArn`, `ExpiryScheduleName`, `ExpiryExpectedCodeSha256` | Exact installed implementation/target identity |
| `RunResourceTtlMinutes` | Installed lifetime, matching preset/staging metadata |
| `OidcProviderArn` | Reuse and account-level inventory |
| `RunStackPrefix`, `RuntimeRolePrefix`, `RunSecretPrefix` | Namespaced run resources |
| `RepositoryTag`, `GitHubEnvironmentName` | Ownership/access configuration |
| `GitHubRecoveryEnvironmentName` | Branch-restricted automatic diagnostics/cleanup environment |
| `TemporaryArtifactRetentionDays` | Effective installed S3 retention setting |

Read outputs with:

```sh
aws cloudformation describe-stacks --region "$AWS_REGION" \
  --stack-name sendium-performance-bootstrap
```

Configure environment-scoped region, role and bootstrap-stack values for later
workflows; the orchestration role can read its own bootstrap outputs. Do not
store static AWS access keys.

The [protected workflow configuration](../workflow/AWS_EXECUTION.md) names the
environment variables `AWS_REGION`, `AWS_ORCHESTRATOR_ROLE_ARN`,
`AWS_BOOTSTRAP_STACK` and the recovery environment's separate
`AWS_DIAGNOSTICS_ROLE_ARN` / `AWS_CLEANUP_ROLE_ARN`. It validates the actual assumed identity, installed expiry
and regional compatibility before creating run resources.

### Privilege and ownership contract

The orchestration role creates only namespaced run stacks, using the exact
CloudFormation service role. It does not directly launch EC2/RDS or create IAM
roles. Root stack creation requires:

```text
sendium:managed-by = performance-tests
sendium:repository = <owner>/<repository>
sendium:run-identity = gh-<runId>-<attempt>
sendium:expires-at = <UTC expiry timestamp>
```

Run templates must explicitly tag taggable resources with the managed-by and
repository values and propagate tags to instance volumes. Creation uses those
request tags; network mutation, instance termination and SSM commands require
owned-resource tags. RDS names use `RunStackPrefix`; role/profile names use
`RuntimeRolePrefix`; secrets use `RunSecretPrefix` plus run identity. New
runtime roles require `RuntimeBoundaryArn`; the service role cannot remove that
boundary, attach arbitrary managed policies, or rewrite bootstrap-role policies.
Instance launches explicitly deny public-address association. No SSH key/ingress
or public networking permissions are needed.

Runtime permissions are a ceiling, not a ready-to-attach instance policy. Task
4.4 supplies narrower per-role/per-run inline policies: SSM control channels,
specific S3 bundle/output keys, and only Sendium's database-secret access. There
is no account-wide Parameter Store read or `SendCommand` permission in the
runtime boundary. Secrets decryption is service-mediated. The RDS account-level
service-linked role may be created on first use and is not a run-owned role.

The diagnostic role can describe EC2/type/credit metadata, query CloudWatch metric
statistics, and read `runs/*/outputs/*`; it cannot provision, delete, control
instances, or retrieve database secrets. The expiry role lists/describes run
stacks and deletes only matching-prefix stacks with both ownership tags. Its
handler also checks root identity/expiry and scopes cleanup to that run. It can
read only control markers, not runtime bundles, database secrets or reports.

**Do not execute application builds, the load JVM, or offline report code under
the provisioning role.** Use unprivileged build/publishing jobs and runtime-owned
EC2 execution. Configuration generation requiring private database values belongs
in the node deployment stage under a run-limited runtime role; keep secret values
in private files/S3 inputs, not command arguments or downloadable Actions artifacts.

### Validation

```sh
python -m pip install -r performance/infrastructure/requirements-dev.txt
cfn-lint --template performance/infrastructure/bootstrap.yaml performance/infrastructure/network.yaml performance/infrastructure/run.yaml
python -m unittest discover -s performance/infrastructure -p 'test_*.py'
```

Policy tests cover positive/negative OIDC claims, existing-provider reuse,
runtime/provisioner/diagnostic separation, boundary requirements, ownership
conditions, retained storage, and IAM document-size limits at maximum parameter
lengths. Lint and these static checks do not prove live IAM authorization or
resource lifecycle behavior; actual creation, SSM connectivity, encryption,
rollback/deletion and expiry are verified in Tasks 4.5/6.1.

### Teardown

First stop/clean every run stack while its service/expiry roles still exist.
Then disable the schedule and remove the expiry implementation/bootstrap through maintainer
administration. Do not delete a boundary still used by runtime roles. S3, its TLS
bucket policy, and a newly created OIDC provider have explicit `Retain` policies: inventory their
physical IDs before deleting the stack. Empty/delete the retained bucket when no
artifacts are needed. Delete a retained provider only after confirming no other
repository/role uses it; an externally supplied provider was never stack-owned.
Run-prefix lifecycle expiration continues independently of run-job cancellation.

The [run cleanup command](../deployment/README.md#4-clean-up-a-completed-failed-or-cancelled-run)
validates root ownership, waits for CloudFormation deletion, then clears that run's
temporary object prefix and incomplete multipart uploads. Use it before bootstrap
teardown, including for partial/failed run provisioning. Its bucket-versioning and
multipart-list permissions are scoped to this bootstrap's exact artifact bucket;
delete/abort actions remain limited to run objects. Cleanup reports blocked or
partial deletion explicitly and can be rerun with the same identity.

## Per-run private network

`network.yaml` is a disposable child of the `run.yaml` per-run benchmark stack,
not shared bootstrap infrastructure. Upload it beneath the run's `templates/`
prefix. The parent must reference its outputs so CloudFormation deletes dependent
EC2/RDS resources before the network. Do not retain this child or delete it while
the benchmark resources still use its subnets/security groups.

### Inputs and ownership

- Pass `Repository` exactly as configured in bootstrap, `RunIdentity` as
  `gh-<runId>-<attempt>`, and `ExpiresAt` as a UTC timestamp. Apply the same four
  ownership/expiry tags shown above to both parent and child stacks. Resource tags
  are explicit; resources without tag support remain owned through CloudFormation.
- Resolve `PrimaryAvailabilityZone` from common EC2/RDS offerings and
  `SecondaryAvailabilityZone` from a different AZ in the selected region. A template
  rule rejects equal AZs. Place both EC2s and the Single-AZ RDS instance in the
  primary AZ; supply both subnets to the RDS subnet group.
- Pass bootstrap's artifact bucket as `ArtifactBucketName`. Resolve
  `S3PrefixListId` from the regional AWS-managed `com.amazonaws.<region>.s3`
  prefix list; its syntax alone does not establish its service/region identity.
  The protected preflight must check that identity and endpoint availability.
- All taggable resources carry the bootstrap service role's required ownership
  tags. The template uses its existing network create/mutate/delete permissions;
  no additional bootstrap permissions are needed. Live service authorization still
  requires AWS validation.

The isolated VPC uses `10.70.0.0/24`, with two `/26` private subnets matching the preset and
public-IP assignment disabled. Overlapping CIDRs across independent runs are
intentional: these VPCs have no peering/transit connections. A shared private route
table has only the VPC local route and the S3 gateway endpoint route. There is no
internet gateway, NAT, public address, IPv6 route, or SSH ingress.

### Connectivity and outputs

| Initiator | Destination | TCP ports |
|---|---|---|
| Harness | Sendium | 8080, 27777 |
| Sendium | Harness | 27779, 8081 |
| Sendium | PostgreSQL | 5432 |
| Both EC2 roles | Interface endpoints | 443 |
| Both EC2 roles | Regional S3 managed prefix list | 443 |

Each group-to-group connection has matching egress and ingress rules. Security
groups are stateful, so responses need no reverse connection permission. Database
and endpoint groups have an inert loopback-only egress rule to suppress EC2's
implicit allow-all outbound rule. Attach only the designated groups to benchmark
resources; do not attach the VPC's automatically created default security group.
VPC resolver DNS and Amazon Time Sync use AWS link-local facilities, rather than
internet egress.

The `ssm`, `ssmmessages`, and `secretsmanager` interface endpoints use private DNS
and primary-AZ ENIs. Interface endpoint service policies retain AWS defaults;
runtime IAM policies and their permissions boundary constrain API access. S3's
endpoint policy permits only object read/write/multipart abort under
`runs/<RunIdentity>/*` in the bootstrap bucket; it does not itself grant identity
permissions. No bucket listing is required for exact-key deployment/output copies.

Use a preinstalled, compatible SSM Agent with `ssmmessages` support and
`AWS-RunShellScript`. This network does not support SSM Agent updates, patching,
Distributor, public package repositories, or public runtime downloads. Those
operations would require additional destinations and permissions. Stage the JVM,
application, harness, and other required runtime files in the run artifact prefix.

Outputs are `VpcId`, `PrimarySubnetId`, `SecondarySubnetId`,
`PrimaryAvailabilityZone`, `HarnessSecurityGroupId`, `SendiumSecurityGroupId`, and
`DatabaseSecurityGroupId`. Keep these parent/child references run-local; there are
no cross-stack exports that can block concurrent-run cleanup. Expiry tags describe
ownership/lifetime; the installed independent handler enforces expiry separately.

Offline network tests check private topology, distinct-AZ placement, exact paired
traffic flows, absence of implicit broad egress, endpoint placement, run-scoped S3
access, ownership tags, and the output/deletion contract. CloudFormation lint also
checks references and dependency cycles. Live SSM, RDS and artifact connectivity
remain part of Tasks 4.5/6.1.

## Per-run benchmark stack

`run.yaml` owns the network child, two private EC2s, separate runtime IAM
roles/profiles, PostgreSQL subnet/parameter groups, a private RDS instance, and a
generated database secret. Create a fresh root stack named
`<ResourcePrefix>-run-<RunIdentity>` using bootstrap's CloudFormation execution
role and `CAPABILITY_NAMED_IAM`. Apply the four required stack tags. These are
create/run/delete stacks; the bootstrap orchestration policy intentionally does
not grant stack updates. Do not reuse names for simultaneous attempts.

### Resolved input contract

| Parameters | Source / requirements |
|---|---|
| `ResourcePrefix`, `Repository`, `RuntimeBoundaryArn`, `ArtifactBucketName` | Exact matching bootstrap configuration/outputs; never select a different permission boundary. |
| `RunIdentity`, `ExpiresAt` | Protected controller; use the exact identity and `expiresAt` from `staged.json` for parameters/root tags. Identity is limited to 27 characters so IAM role names fit even with a 24-character resource prefix. |
| `PrimaryAvailabilityZone`, `SecondaryAvailabilityZone`, `S3PrefixListId` | Protected regional preflight, as described for `network.yaml`. |
| `SendiumImageId`, `HarnessImageId` | Exact preflight-resolved AL2023 AMIs compatible with each selected instance's architecture, with SSM Agent and required OS tools present. |
| `SendiumInstanceType`, `HarnessInstanceType` | Default `t4g.small` each; preflight verifies memory, architecture, offerings and capacity assumptions. |
| `SendiumRootDeviceName`, `HarnessRootDeviceName` | Exact AMI `RootDeviceName`; no padded/hardcoded disk default. |
| `SendiumRootVolumeGiB`, `HarnessRootVolumeGiB` | Corresponding root EBS mapping's minimum `VolumeSize`, generally 8 GiB; validate integer values and sufficient space for staged files. |
| `DatabaseInstanceType` | Default `db.t4g.small`; validate PostgreSQL 17/gp3 support in the chosen AZ. |
| `DatabaseEngineVersion` | Exact available PostgreSQL 17 minor; template requires an explicit value and `postgres17` parameter family. |

The resource prefix must also be valid for RDS identifiers (single hyphen
separators). Reject unsupported additional AMI disks or other launch-time mappings
in preflight: this template overrides only the resolved root mapping. Record all
resolved AMI, type, storage, engine and artifact identities in the run manifest.
Upload the reviewed network template to the exact run key
`runs/<RunIdentity>/templates/network.yaml` before creating the parent; its URL is
constructed by the template. Enforce checksummed, immutable deployment inputs in
the controller rather than overwriting a template during provisioning.

### Runtime and storage

Both EC2s use the primary private subnet and their designated security groups,
IMDSv2 with hop limit 1, no SSH key, no public IP and no bootstrap download script.
Provisioning creates idle nodes; the [deployment controller](../deployment/README.md)
stages and starts the application through SSM. Each root disk is encrypted gp3 and deleted on termination; ownership tags
propagate to its volume. Known burstable families (`t2`, `t3`, `t3a`, `t4g`) use
Standard credits. Non-burstable overrides omit `CreditSpecification`. Preflight
must reject an unknown burstable family until its handling is defined and record
the observed mode rather than assuming this conditional proves effective state.

RDS is Single-AZ, in the primary AZ, with 20 GiB encrypted gp3, no storage
autoscaling, no automatic minor/major upgrades, no backups, enhanced monitoring or
Performance Insights, and no deletion protection. The parameter group requires
TLS. Deployment must stage the RDS CA bundle and use `sslmode=verify-full`; the
server parameter alone does not verify the client's peer. Both deletion and
replacement explicitly delete the database without a final snapshot. The database
name is `sendium_benchmark`, and its generated 32-character alphanumeric password
and `sendium_perf` username live in a run-named Secrets Manager secret. Dynamic
references supply RDS credentials; no credential values appear in stack outputs,
user data, or template parameters. Secret deletion is explicit and disposable.

Runtime role policies remain below the bootstrap boundary:

| Node | Reads | Writes |
|---|---|---|
| Both | Own `inputs/<node>/*`, shared `inputs/common/*`, managed SSM shell document | Own `outputs/<node>/*` |
| Sendium only | Exact database secret | `inputs/harness/generated/*` |

All object paths are beneath `runs/<RunIdentity>/` in the bootstrap bucket. The
shared input prefix is for non-secret dependencies/scripts such as runtime
archives and CA certificates. Neither node can overwrite shared inputs, templates,
or the other node's evidence, delete objects, provision resources, or issue SSM
commands. Region-scoped SSM/SSM Messages control-channel operations allow agent
registration and command receipt. The default AWS-managed Secrets Manager key
avoids introducing a customer-managed runtime decryption grant.

The Sendium deployment stage generates the existing paired configuration bundles
with the database secret locally, keeps the Sendium bundle private, and publishes
only the generated harness bundle beneath `inputs/harness/generated/`. This is a
deliberate one-way handoff for matching ingress/provider credentials. The harness
has no database network access or secret permission. Generated private bundles
must not enter downloadable report artifacts; upload sanitized settings instead.

### Outputs and lifecycle

Outputs include run identity/expiry, network stack/VPC/subnet IDs, primary AZ,
instance IDs and private addresses, database identifier/address/port/name, database
secret **ARN**, and the artifact run prefix. These identifiers let the protected
controller deploy via SSM and render inputs without retrieving secret values in
GitHub Actions. Successful stack creation is infrastructure readiness only;
SSM registration, staged runtime checks, application/database/provider readiness
and measurements belong to Task 4.5.

References keep the network child alive until its EC2/RDS dependents are deleted,
and the database alive before its generated secret can be removed. Temporary S3
objects remain outside the run stack for explicit cleanup/lifecycle expiration.
Deletion recovery for partial provisioning uses the deployment cleanup helper. A stack's expiry timestamp
does not itself perform deletion; execution requires the installed expiry handler
and matching live configuration. This template has only offline validation so far.
