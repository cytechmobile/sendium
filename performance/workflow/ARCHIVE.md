# Permanent performance evidence

The archive infrastructure is defined in `../infrastructure/bootstrap.yaml`.
The workflow packages public results offline and uploads them in a separate,
restricted archive job after publication, including when publication reports failure.

## Setup

1. Update the existing bootstrap stack to create the retained archive bucket and
   its dedicated role. This does not change temporary artifact retention.
2. In the existing `sendium-performance-recovery` GitHub environment, set
   `AWS_ARCHIVE_ROLE_ARN` to the bootstrap output `ArchiveRoleArn`. Keep the same
   `AWS_REGION` and `AWS_BOOTSTRAP_STACK` as diagnostics/cleanup. No bucket variable
   or permanent AWS access key is needed; the job verifies bootstrap outputs and
   the actual assumed role/account/repository/region before uploading.
3. Run the performance workflow. Open **Archive permanent performance evidence**
   to find the permanent manifest location and upload status in its job summary.

Archive failures make the workflow fail independently of benchmark validity.
The `performance-archive-outcome-<run-id>-<attempt>` artifact retains upload status;
the original `performance-results-<run-id>-<attempt>` remains available for recovery
within GitHub's retention period. No archive success is claimed for missing output,
failed authentication, checksum conflicts, or partial uploads. This job has a
20-minute bound and does not delay instance/database cleanup.

## Package published results

Use the normalized `request.json` and the **public published results** directory
produced by `publish.py`, not its private work directory or application bundles:

```sh
python performance/workflow/archive_package.py \
  --request request.json --source published --output archive-package
```

The output directory must be new and outside the source directory. Packaging
validates the report against the request and existing result-schema/semantic
contracts. Failed and inconclusive reports are retained as observations with
their original status. Missing optional Markdown/workflow files are disclosed
in the manifest; a valid `results.json` is required. No traffic is repeated and
no measurement, configuration, provenance, RDS series, or diagnostic is rewritten.

## Layout and manifest version 1

The eventual S3 prefix is `runs/<owner>/<repository>/<run-id>/<attempt>/`:

```text
manifest.json
results.json
summary.md
workflow-outcome.json
evidence/
  rds-metrics.json
  sendium-resources.jsonl
  harness-resources.jsonl
  cpu-credits.json
diagnostics/
  ...available published diagnostics...
evidence.tar.gz
```

Only the three named top-level publication files and files under `evidence/` and
`diagnostics/` are copied. `_work/`, generated runtime settings, binaries, and
other unpublished inputs are excluded. Links and unsafe archive paths are rejected.
Budgets are 512 files, 64 MiB per file, and 256 MiB total input. Packaging failures
return nonzero with a sanitized error and never indicate archival success.

`manifest.json` contains:

- `schemaVersion: 1` for the archive envelope, independent of the result schema.
- Repository, run ID/attempt, run identity, intended prefix, and packaging timestamp.
- Original report provenance, including the workflow URL and source revisions.
- Result schema version, validity status, and whether an observation is available.
- Application runtime (`jvm` for legacy results without the field) and ingress mode.
- Missing optional top-level files.
- `files`: original relative paths, byte lengths, SHA-256 digests and content types.
- `bundle`: the compressed evidence bundle's path, size, SHA-256 and content type.

Readable files and tar members have identical original bytes. The bundle contains
all listed evidence files, excluding itself and the manifest; this avoids circular
checksums. Its timestamps and metadata are normalized so the same evidence yields
the same bundle checksum on retry. The manifest itself records packaging time and
therefore can differ between invocations. Native compiler/build metadata and the
complete RDS datapoints/coverage remain in the original `results.json` and raw evidence.

Future consumers should discover completed runs through their manifest and use
the original JSON as the measurement source of truth, rather than parse Markdown.

## Upload protocol and retries

The uploader rechecks package identity, all file/bundle checksums, and result
metadata after the artifact boundary. It sends S3 SHA-256 checksums with conditional
create-only writes and verifies stored lengths/checksums with `HeadObject`. Existing
matching objects are reused; a conflicting object fails without overwriting it.
`manifest.json` is written **last** as the completion marker. Partial prefixes without
a manifest are not complete archived runs. Matching repackaged evidence can reuse an
existing manifest even if the packaging timestamp changed; its content and stored
checksum are independently checked. Historical manifests are never replaced.

Retrying the uploader with the same normalized request can resume matching partial
uploads. A new GitHub attempt has a new prefix, preserving previous attempts. Hard
cancellation can interrupt the archive job; until it completes, retain the GitHub
results artifact. There is no automatic backfill of historical runs in this change.

## Retrieve evidence

Use an operator/read-only AWS identity with `s3:GetObject` access to the archive.
The archive upload role is for the workflow and is not a public download mechanism.
For example, using the bucket name from the workflow summary:

```sh
aws s3 cp s3://BUCKET/runs/cytechmobile/sendium/RUN_ID/ATTEMPT/manifest.json manifest.json
aws s3 cp s3://BUCKET/runs/cytechmobile/sendium/RUN_ID/ATTEMPT/results.json results.json
aws s3 cp s3://BUCKET/runs/cytechmobile/sendium/RUN_ID/ATTEMPT/evidence.tar.gz evidence.tar.gz
```

Verify downloaded files against the manifest's SHA-256 and size before use. A future
dashboard can read JSON directly; listing/discovery access (`s3:ListBucket`) belongs
to that future consumer's role, not the upload role. The bucket is private and does
not generate expiring presigned URLs for permanent references.
