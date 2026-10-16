# AWS performance infrastructure

## Bootstrap (Task 4.2)

`bootstrap.yaml` creates persistent control-plane resources:

- Private, owner-enforced, AES256-encrypted S3 artifact bucket; TLS required.
  Temporary `runs/` objects expire after one day by default, with incomplete
  multipart uploads also expired. Versioning is not enabled.
- Account-level GitHub OIDC provider, or reuse of an existing provider ARN.
- Environment-bound GitHub orchestration and read-only diagnostic roles.
- A CloudFormation service role for run infrastructure and a permissions
  boundary required on the future EC2 runtime roles.
- An expiry Lambda execution role and 14-day log group. **The handler and
  EventBridge schedule are installed in Task 4.7. This bootstrap alone does not
  reap abandoned runs.** Complete that step before enabling AWS benchmark runs.

Benchmark networking, `t4g.small` EC2 instances, and `db.t4g.small` RDS are the
separate run templates in Tasks 4.3/4.4. Their sizing/storage contract is
[`../presets/aws-private-jvm-v1.json`](../presets/aws-private-jvm-v1.json).

### Install once

Use the intended AWS account/region and a maintainer's bootstrap identity with
permission to create IAM/OIDC, S3, and log resources. The restricted orchestration
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

### Outputs used by deployment/workflow code

| Output | Consumer |
|--------|----------|
| `ArtifactBucketName` | Run-scoped private inputs, immutable bundles, SSM output and diagnostics |
| `OrchestratorRoleArn` | Protected preflight/provision/control/cleanup jobs |
| `DiagnosticsRoleArn` | Protected post-run EC2/CloudWatch reads and output retrieval |
| `CloudFormationExecutionRoleArn` | `CreateStack --role-arn`; persists for nested-stack deletion/expiry |
| `RuntimeBoundaryArn` | Mandatory boundary on app/harness roles in the run template |
| `ExpiryRoleArn`, `ExpiryFunctionName`, `ExpiryLogGroupName` | Task 4.7's persistent expiry implementation |
| `ExpiryHandlerInstalled` | `false` until Task 4.7 installs/enables actual expiry; run preflight must require it |
| `OidcProviderArn` | Reuse and account-level inventory |
| `RunStackPrefix`, `RuntimeRolePrefix`, `RunSecretPrefix` | Namespaced run resources |
| `RepositoryTag`, `GitHubEnvironmentName` | Ownership/access configuration |
| `TemporaryArtifactRetentionDays` | Effective installed S3 retention setting |

Read outputs with:

```sh
aws cloudformation describe-stacks --region "$AWS_REGION" \
  --stack-name sendium-performance-bootstrap
```

Configure environment-scoped region, role and bootstrap-stack values for later
workflows; the orchestration role can read its own bootstrap outputs. Do not
store static AWS access keys.

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
future handler must additionally check root-stack identity and expiry, handle
partial/failed stacks, and scope S3 prefix cleanup to that run.

**Do not execute application builds, the load JVM, or offline report code under
the provisioning role.** Use unprivileged build/publishing jobs and runtime-owned
EC2 execution. Configuration generation requiring private database values belongs
in the node deployment stage under a run-limited runtime role; keep secret values
in private files/S3 inputs, not command arguments or downloadable Actions artifacts.

### Validation

```sh
python -m pip install -r performance/infrastructure/requirements-dev.txt
cfn-lint --template performance/infrastructure/bootstrap.yaml
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
Then remove the completed expiry implementation and bootstrap through maintainer
administration. Do not delete a boundary still used by runtime roles. S3, its TLS
bucket policy, and a newly created OIDC provider have explicit `Retain` policies: inventory their
physical IDs before deleting the stack. Empty/delete the retained bucket when no
artifacts are needed. Delete a retained provider only after confirming no other
repository/role uses it; an externally supplied provider was never stack-owned.
Run-prefix lifecycle expiration continues independently of run-job cancellation.
