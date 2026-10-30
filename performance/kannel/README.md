# Kannel benchmark baseline

This directory contains the pinned Kannel build, standalone correctness probes,
and local benchmark controller. Kannel is selectable through the shared local
launcher. The manual AWS workflow also selects Kannel after the maintained
controller changes merge to `main`. See the [dispatch instructions](../workflow/README.md#running-kannel-after-merge)
and [AWS deployment profile](../deployment/README.md#kannel-aws-deployment-profile).

The shared benchmark runner and version-1 report contracts also accept explicitly
prepared Kannel execution inputs. Target-aware readiness, archive-based source
identity, `kannel` resource labels, and offline resource finalization are described
in [target-aware execution](../harness/EXECUTION.md#target-aware-execution).
Named process-group sampling measures bearerbox and smsbox together, with
per-member identities and contributions. The full local pipeline runs the shared
workload engine and finalizes both gateway and harness resource evidence.

The initial scenario is **HTTP ingress -> SMPP provider -> HTTP DLR callback**.
Kannel SMPP ingress is deferred. Sendium retains its existing HTTP/SMPP ingress
and JVM/native choices. AWS gateway and harness hosts remain `t4g.small`.

## Full local benchmark

Use the existing local launcher to run Kannel through the shared benchmark engine:

```sh
python performance/local/rehearse.py --benchmark-target kannel --output local-load-results
```

Defaults come from the shared preset: HTTP with DLR, 30 offered messages/sec,
worker TPS zero (unlimited), warm-up/measurement/drain budgets 120/600/120 seconds,
provider response delay 5 ms, and receipt delay 1000 ms. A shorter check crossing
the first minute boundary is:

```sh
python performance/local/rehearse.py --benchmark-target kannel --warmup-seconds 2 --measurement-seconds 65 --drain-seconds 5 --output local-load-results
```

The launcher builds and pins separate controller, harness and gateway images by
their actual IDs. Build-time metadata captures source/patch hashes, applied patch
digests, binary hashes, compiler/configure identity, the harness JAR and Java
identity, and the source revision plus a working-tree fingerprint. Reused images
retain their original metadata. Mixed source, sampler, supervisor or architecture
identities are rejected. Runtime workload containers have no Docker socket or AWS
credentials and communicate on an internal per-case network.

The controller starts the real harness listeners, then Kannel. A bounded local
readiness file ties the run identity and gateway process IDs to the completed
sampler baseline. The shared engine then runs warm-up, measurement and drain.
After the harness finishes, the controller stops Kannel's sampler before its
processes and imports complete resource evidence through the existing offline
finalizer. That finalizer sees only public evidence and has no network or Docker
socket. Workload counts and intervals are checked for preservation.

Canonical results are `<output>/http-dlr/results.json` and `summary.md`; initial
and finalized reports, raw samples, effective configuration and redacted logs are
retained alongside them. The normal pipeline keeps valid/inconclusive/failed
observation status and exposes incomplete drain or rejected traffic. Completion
checks include bounded drain; they are not sustained-throughput thresholds.
`http-no-dlr` is explicit; `app-crash` and `interrupted` verify failed-report
retention and cleanup. A passing fault rehearsal retains a **failed** benchmark
report. Detached execution and session collection are supported.

See the [local target/parameter reference](../local/README.md#select-sendium-or-kannel).
The benchmark uses PostgreSQL 17 DLR storage with certificate-verified TLS and
an ephemeral message spool. It does not reproduce AWS hardware or CPU credits.
The AWS path is implemented; native architecture CI and a live AWS run remain
the verification boundary before interpreting AWS results.

## Local startup and provider-bind smoke run

Requirements: Python 3.9+, a local Linux-container Docker daemon (including Docker
Desktop with host bind-mount support), and internet access for cold builds. Java
and Maven run inside Docker; no AWS account or credentials are needed.

From the repository root:

```sh
python performance/kannel/smoke.py --output performance/kannel/smoke-results
```

Use a new output directory beneath an existing parent. For a repeat using the
already-built smoke images:

```sh
python performance/kannel/smoke.py --skip-build --output performance/kannel/smoke-results-repeat
```

Leave `--skip-build` unset after changing the provider probe, harness, gateway
supervisor, or Dockerfiles. The launcher selects the Docker engine's native
architecture, builds the baseline and two smoke images, records their IDs, and
launches those exact IDs. Reused images may contain older source; their recorded
IDs, rather than the current working tree, identify the bytes being checked.

To also verify real messages and delivery reports:

```sh
python performance/kannel/smoke.py --traffic --output performance/kannel/smoke-results-http-dlr
```

The opt-in traffic check sends ten synthetic, single-part HTTP submissions using
the shared `HttpAdmissionClient`, with DLR callbacks handled by the shared
`HttpReceiptReceiver`. It uses explicit Kannel protocol settings, records provider
IDs in the shared ledger, and requires every admitted message to reach the
simulated provider and return a matching delivered receipt. A bounded drain waits
for outstanding messages and acknowledgement work. `traffic-result.json` contains
counts, protocol diagnostics, and the correctness outcome; it is preserved beside
the deployment outcome and redacted logs. It is a functional check, not a
throughput measurement or an archived benchmark report.

The run:

1. Starts `KannelBindProbe`, a small wrapper around the existing production
   `SmppProvider`, on an isolated internal Docker network.
2. Starts bearerbox and smsbox under a bounded supervisor in another container.
   Each container has 2 CPU / 2 GiB limits and no published host ports.
3. Checks the real `/sendsms` handler's known unauthenticated response and requires
   an authenticated SMPP 3.4 **transceiver** bind. DLR mode is enabled on the
   simulator, so a transmitter-only bind would be rejected.
4. Starts the shared Linux sampler for the two gateway PIDs and waits for its
   baseline before publishing gateway readiness. The group has one-second
   intervals, a 256-record budget, and a bounded smoke lifetime.
5. Requires the processes/session to remain live, then shuts down the probe and
   gateway. The supervisor stops the sampler first, retaining a final complete
   interval while both members are alive, then stops smsbox and bearerbox. Both
   containers and the network are removed; Docker images remain cached. Startup
   waits, process lifetimes, and cleanup commands have bounds.
6. Writes `outcome.json`, `kannel-resources.jsonl`, and bounded,
   credential-redacted provider/gateway logs.
   A failure or cleanup issue produces a failed outcome and nonzero exit status.

Generated credentials/configuration live in a temporary private directory and
are removed after the containers release it. The SMPP fixture password is eight
characters to respect Kannel's wire limit. `system-type` is explicit. Logging uses
warning/error verbosity; no access or protocol-payload logging is enabled.

Without `--traffic`, the outcome is explicitly **startup-and-bind-only** and
generates no SMS traffic. With `--traffic`, its scope is
**single-part-http-dlr-correctness**. Neither mode measures throughput. The smoke configuration uses
internal DLR storage and an ephemeral local spool for message storage; it is not
a persistence-equivalent Sendium comparison. Container CPU limits do not simulate
`t4g.small` CPU credits.

Group samples preserve each member's PID/start ticks, CPU and RSS. Group CPU/RSS
are sums; shared pages can be counted in both processes' RSS. Host metrics are
collected once per sample and describe the Docker Linux host/VM. The Java importer
and independent result validator check aggregation and stable member identities;
see [sampling semantics](../resources/README.md#named-process-groups-kannel-bearerbox-and-smsbox).

Local verification on 2026-10-30 passed on linux/amd64 with real Kannel and the
current harness simulator: ten attempts, ten acceptances, ten provider completions,
and ten correlated forwarded DLRs, with zero duplicates, outstanding messages,
or protocol/accounting problems. The same real Kannel run retained a null-CPU
group baseline and subsequent complete intervals for both processes; its five
sample records also imported through the Java resource finalizer without import
or identity errors. Startup failures encountered during configuration
verification retained diagnostics and cleaned up their containers/network. The
failure-path tests cover gateway readiness failure, provider shutdown timeout,
failed traffic accounting, evidence export failure, credential redaction, and
cleanup of both nodes and networking:

```sh
python -m unittest discover -s performance/kannel -p 'test_*.py'
```

The controller tests use the contract dependencies, available in its built image:

```sh
docker run --rm --network none --entrypoint python sendium-local:kannel-control -m unittest discover -s /workspace/performance/kannel -p 'test_*.py'
```

The Java harness unit suite (including default Sendium HTTP/SMPP adapters and
Kannel protocol cases) can also run entirely inside Docker:

```sh
docker build --file performance/kannel/Dockerfile.local --target verification --tag sendium-local:kannel-adapter-verification .
```

## Source identity

Use upstream Kannel **1.4.5**, retrieved from Debian's upstream-source mirror,
with exactly the two build patches below. This is not the complete Debian
`1.4.5-23` patch series or a Debian binary package.

| Input | Identity |
|---|---|
| Upstream source | <https://deb.debian.org/debian/pool/main/k/kannel/kannel_1.4.5.orig.tar.gz> |
| Source SHA-256 | `f279606ea8317fd2c8fa6b0f591c084fdfa954612f1e7c9cb667a5f6b77387f3` |
| Patch archive | <https://deb.debian.org/debian/pool/main/k/kannel/kannel_1.4.5-23.debian.tar.xz> |
| Patch archive SHA-256 | `3d8d54e272699a5226368738ee0246c8873d152a63220f978ac149285895f5be` |
| Checksum reference | <https://deb.debian.org/debian/pool/main/k/kannel/kannel_1.4.5-23.dsc> |
| Build base | `amazonlinux:2023@sha256:c04a1012acc483977f61f7f91c28624a39a621e56a59781319a75dec528be9b6` |

The upstream website was unavailable during baseline verification; the downloaded
source archive passed the SHA-256 check published in Debian's source descriptor.
The build checks both downloaded archives before extraction. It applies only:

1. `Update-bison-source-for-newer-version.patch`: fixes the parser interface for
   modern Bison. Without this patch the AL2023 build failed in `wsgram.c`.
2. `Fix-multiple-conflicting-defs-of-dispatch_to_wdp-wsp-push.patch`: fixes
   conflicting WAP dispatch definitions for GCC 10 and newer.

Patches are applied in that order, with zero fuzz and failure on errors. The
original source, selected patches, configuration outputs, and build package list
remain in the verification image. Do not identify these binaries as an unpatched
upstream build or as the full Debian package.

## Build verification

From the repository root, using Linux-container Docker:

```sh
docker build --platform linux/amd64 --file performance/kannel/Dockerfile.baseline --tag sendium-kannel-baseline:amd64 performance/kannel
docker build --platform linux/arm64 --file performance/kannel/Dockerfile.baseline --tag sendium-kannel-baseline:arm64 performance/kannel
```

Both builds compile, install, and execute `bearerbox --version` and
`smsbox --version`. The ARM64 probe can use Docker emulation on an x86-64 host;
this verifies build/startup compatibility, not Graviton performance. Prefer native
builders for repeated builds. The upstream makefiles have a parallel-build
dependency race in `utils`, so the baseline deliberately uses `make -j1`.

The configure options are:

```text
--prefix=/opt/kannel --disable-wap --disable-docs
--with-defaults=speed --with-malloc=native --enable-ssl --with-pgsql
```

HTTP keep-alive remains enabled. PostgreSQL support and the libpq client version
are recorded in build metadata. Database connections use verified TLS; the
existing simulated provider uses plain SMPP.

Verification on 2026-10-29 passed for **linux/amd64 and linux/arm64** using:

- Amazon Linux release `2023.12.20261021`.
- GCC `11.5.0-5.amzn2023.0.5` and Bison `3.7.4-2.amzn2023.0.2`.
- libxml2 `2.10.4-1.amzn2023.0.21` and OpenSSL `3.5.8-1.amzn2023.0.3`.

The full installed RPM inventory is `/opt/kannel/build-packages.txt` in each
image. The base digest fixes the base filesystem and its AL2023 release selector;
source/patch checksums fix their content. This is a source/build baseline, not a
claim of byte-for-byte reproducibility: upstream embeds build timestamps. Future
deployment bundles must record their actual binary, dependency, and image digests
and stage runtime dependencies before execution on isolated AWS instances.

## Verified HTTP and DLR differences

These findings are from the exact upstream archive above, not assumptions based
on Sendium's Kannel-compatible API. The local traffic check verifies the core
submission/provider-ID/receipt path; negative cases are exercised in adapter tests.

| Concern | Kannel 1.4.5 behavior | Integration requirement |
|---|---|---|
| Submission path | Defaults to `/cgi-bin/sendsms`; `sendsms-url` is configurable. | Set `/sendsms` explicitly or generate the target URI. |
| Acceptance | Delayed reply returns HTTP 202 with `0: Accepted for delivery` or `3: Queued for later delivery`, without an admission UUID. | Parse exact target-specific responses; never fabricate a gateway ID. |
| Admission boundary | `immediate-sendsms-reply = false` waits for bearerbox acknowledgement, not final delivery or provider acceptance. | Set false explicitly; keep admission distinct from provider completion. |
| Receipt request | `dlr-mask` selects events separately from `dlr-url`. | For the current delivered-only simulator, request `dlr-mask=1` and URL-encode the callback template. |
| Callback placeholders | `%d` is receipt status, `%I` is the internal message UUID, `%F` is the provider message ID; `%s` expands a word from message data. | Do not reuse Sendium's `messageId=%s` template. Carry the harness token literally and validate `%F` against the provider's recorded ID. |
| Callback response | `url_result_thread` treats HTTP 200 and 202 as success; the existing harness returns 204. | Add target-aware callback acknowledgement, preserving strict token/status/ID validation. |
| SMPP IDs | Omitting `msg-id-type` preserves C-string IDs on submission and receipt. Explicit values 0–3 perform numeric conversions. | Omit this directive for the simulator's `p-<base36Sequence>` IDs; verify end-to-end before changing the simulator. |
| SMPP concurrency | `max-pending-submits` defaults to 10. | Set it explicitly to the shared comparison window, initially 1000. |

The ledger must still prove that callback tokens were issued, provider IDs belong
to those tokens, and receipt statuses match the scenario. Admission success alone
must not infer provider acceptance. Kannel lacks Sendium's admission-ID equality
check, so document that difference rather than weakening Sendium validation.

Source references within the pinned archive:

- `gw/smsbox.c`: `delayed_http_reply`, `smsbox_req_sendsms`,
  `url_result_thread`, and smsbox configuration loading.
- `gw/urltrans.c`: `escape_to` cases `d`, `F`, `I`, and `s`.
- `gw/smsc/smsc_smpp.c`: `handle_dlr`, `submit_sm_resp` handling, and
  `msg-id-type` configuration.
- `doc/userguide/userguide.xml`: smsbox configuration, Send SMS CGI parameters,
  URL translation, SMPP settings, and external DLR storage.

## Storage boundary

For the first local protocol smoke scenario, use **explicit
`dlr-storage = internal`**, the built-in bearerbox-memory backend. No external
database is required to establish HTTP/SMPP/DLR interoperability. Set the message
store policy explicitly too: message storage (`store-type`/`store-location`) and
DLR correlation storage are separate features.

The full local benchmark instead provisions a separate PostgreSQL 17 container,
private database network, ephemeral data volume and CA/server certificates. It
sets `dlr-storage = pgsql` and libpq `sslmode=verify-full`, rejects plaintext
connections, and checks database version and schema before gateway readiness.
PostgreSQL stays provisioned for the no-DLR scenario too.

`dlr.sql` supplies the mapped correlation fields and an explicitly generated,
unique `oid` column. Kannel 1.4.5 uses this identifier when updating/deleting one
record; PostgreSQL 12+ no longer supplies legacy table OIDs automatically. This
schema compatibility provision preserves Kannel's existing SQL implementation.

`database-evidence.json` retains observed TLS sessions, pending row counts and
PostgreSQL insert/delete statistics. The controller checks storage activity
against workload accounting and waits for final database statistics after gateway
shutdown. These are functional storage checks, not AWS/RDS utilization metrics.
`database-unavailable` and `bad-tls` rehearse readiness rejection and cleanup.

Using the same database engine does not establish identical durability semantics
with Sendium. The message spool remains ephemeral, and restart/recovery guarantees
are not established by these runs. AWS database provisioning and resource
collection still require integration before the AWS comparison.

## License and packaging

Kannel uses the **Kannel Software License, Version 1.0**, included in the pinned
archive as `LICENSE`. The verification build installs it at
`/opt/kannel/share/licenses/Kannel-LICENSE`; future runtime bundles must retain it
and the relevant notices for included patches/dependencies.

This product includes software developed by the Kannel Group
(http://www.kannel.org/).
