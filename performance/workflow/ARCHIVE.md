# Permanent performance evidence

The archive infrastructure is defined in `../infrastructure/bootstrap.yaml`.
Packaging is offline; automatic S3 upload is a following integration task.

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
