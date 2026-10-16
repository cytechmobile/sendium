# Independent expiry installation and recovery

Expiry is a persistent bootstrap control-plane service, independent of GitHub
Actions runners and benchmark nodes. It uses Python 3.13 on ARM64, 256 MiB memory,
an 840-second timeout, no VPC attachment and no provisioned concurrency. EventBridge
invokes it every 15 minutes. Both event-delivery and Lambda asynchronous retries
are disabled: the next scheduled sweep retries unfinished work. Cleanup is
idempotent under duplicate invocations.

## Install after initial bootstrap

Use the maintainer installation identity, with Lambda/EventBridge permissions as
well as the existing bootstrap administration permissions. The orchestration role
cannot update its own bootstrap, the function, or persistent source packages.

The initial `bootstrap.yaml` deployment creates the bucket/roles/log group with
`ExpiryHandlerInstalled=false`. Once that bucket exists, build the package from
reviewed sources into a **new** directory:

```sh
python performance/deployment/package_expiry.py --output expiry-package
PACKAGE_KEY=$(python -c "import json; print(json.load(open('expiry-package/expiry-package.json'))['s3Key'])")
PACKAGE_FILE=$(python -c "import json; print(json.load(open('expiry-package/expiry-package.json'))['file'])")
PACKAGE_SHA=$(python -c "import json; print(json.load(open('expiry-package/expiry-package.json'))['codeSha256'])")
ARTIFACT_BUCKET=$(aws cloudformation describe-stacks --region "$AWS_REGION" \
  --stack-name sendium-performance-bootstrap \
  --query 'Stacks[0].Outputs[?OutputKey==`ArtifactBucketName`].OutputValue | [0]' --output text)
aws s3api put-object --region "$AWS_REGION" --bucket "$ARTIFACT_BUCKET" \
  --key "$PACKAGE_KEY" --body "expiry-package/$PACKAGE_FILE" --if-none-match '*' \
  --checksum-algorithm SHA256 --checksum-sha256 "$PACKAGE_SHA"
aws cloudformation deploy --region "$AWS_REGION" \
  --stack-name sendium-performance-bootstrap \
  --template-file performance/infrastructure/bootstrap.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ExpiryCodeKey="$PACKAGE_KEY" ExpiryCodeSha256="$PACKAGE_SHA"
python performance/deployment/check_expiry.py \
  --region "$AWS_REGION" --bootstrap-stack sendium-performance-bootstrap
```

An update retains existing parameters that are not overridden. Both package key
and base64 checksum must be supplied together. The deterministic ZIP contains only
`expiry.py`, `cleanup.py` and the transport/error module; there is no application,
harness or vendored dependency. Lambda supplies boto3. For later updates, package
into a new directory and upload its new digest-named key. If the exact asset is
already present, verify its stored SHA-256 and reuse it rather than overwrite it.

The persistent key is `bootstrap/expiry/<SHA-256>.zip`, outside the temporary
`runs/` lifecycle and outside the orchestration/node/expiry roles' write grants.
Keep installed code available for bootstrap recovery. Bootstrap owns the actual
Lambda, retry configuration, enabled schedule and account/rule-scoped invocation
permission. The configured package enables `ExpiryHandlerInstalled=true` only in
that deployment; execution also requires the completed stack and live checks.

`RunResourceTtlMinutes` defaults to 240 and must match the preset. Staging writes
`runs/<identity>/control/run.json` **before** large inputs, containing exact run
identity, repository, stack/region and UTC creation/expiry. Provision using the
exact `expiresAt` from `staged.json`; do not recompute the root tag/parameter later.
Even partial staging can therefore be recovered without a live root stack.
Cleanup removes the control marker last and preserves it after partial artifact
deletion/abort failure, so later sweeps retain the original expiry identity.

## Sweep behavior

1. Discover live matching-prefix CloudFormation stacks and prioritize their
   billed infrastructure before S3 discovery. Require exact managed-by,
   repository/run-identity tags, root identity, attached role and account/region.
   Validate expiry again immediately before deletion; preserve active, nested and
   unowned stacks.
2. Reuse the shared cleanup engine. Request/observe deletion, report it as pending
   and let CloudFormation finish server-side instead of holding the function open
   waiting for RDS/network teardown. A later sweep confirms absence before purging
   that run's objects and incomplete uploads. Surface an existing `DELETE_FAILED`
   even when requesting a retry.
3. Discover abandoned object and multipart prefixes. For absent roots, validate
   control-marker identity/lifetime and expire only elapsed attempts. Malformed
   markers are reported without falling back to an age-only purge.
4. For legacy prefixes without markers, wait until the latest object modification
   or multipart initiation is older than the configured TTL. Require a complete
   multipart scan before concluding that there are no recent unfinished writes.
   Include incomplete-upload-only prefixes, not just completed objects.
5. Preserve available candidates after partial discovery failures. Failed S3
   discovery does not block deletion of already discovered expired infrastructure.
   Pagination has explicit progress/page bounds; each sweep shares a time budget
   with a reserve before Lambda shutdown. Defer work that cannot start safely.

Only root-stack operations and exact run-prefix cleanup are supported. The expiry
role can read the bootstrap/control markers and list bucket metadata, but cannot
read private runtime bundles, database secrets or reports; issue SSM commands;
provision EC2/RDS/IAM; or modify itself/the installed implementation. Invocation
payloads cannot select targets, override expiry or force deletion.

## Observe and recover

`ExpiryLogGroupName` contains per-run `expiry_run` JSON records and an
`expiry_summary`. Pending CloudFormation deletion is normal. Failed scans,
malformed expiry, failed cleanup and existing `DELETE_FAILED` produce visible
Lambda Errors with sanitized issue records. Inspect those logs and stack events,
resolve the reported cause, and let the next sweep retry or run `cleanup.py`
manually with the original identity. No raw AWS stderr, marker body, configuration
or credential values are logged.

`check_expiry.py` is read-only. It verifies actual function state/update, code hash,
role/environment, concurrency, asynchronous retry settings, enabled schedule,
exact target and account/rule-scoped invoke permission. The protected execution
controller runs the same check; a disabled, modified, disconnected or throttled-to-
zero handler blocks new benchmark execution. This proves matching configuration,
not successful scheduled invocation or live IAM/deletion behavior.

The complete local rehearsal in Task 5.5 remains required before Task 6.1, which
observes actual AWS scheduled recovery, endpoints, permissions and resource
deletion. Repository checks do not deploy AWS resources. Keep the bootstrap
service/control roles and expiry installed until all runs are clean; disable the
schedule before retiring the bootstrap and inventory its retained bucket/code.
