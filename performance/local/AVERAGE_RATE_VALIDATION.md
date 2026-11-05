# Average-rate generator verification

The shared generator uses production's Guava rate limiter with one permit per
target. Briefly overdue targets retain their nominal timestamps; recovery is
bounded by one second of stored permits, capacity limits and the fixed phase
deadline. Stop checks bound Guava's uninterruptible waits to 20 ms. No large
prebuilt batches or unbounded work queue are used.

Reports expose target lateness, per-second adapter submissions and dispatch
starts, and submission-to-dispatch queue time. Dispatch marks the start of the
adapter transport call, not socket-write completion or server arrival. Provider
arrivals and completions remain separate evidence.

## Completed local verification

Both protocols used DLR enabled, PostgreSQL with verified TLS, separate
2-CPU/2-GiB nodes, 512-MiB heaps, unlimited workers, a 120-second warm-up,
600-second measurement, up to 120-second drain, and 5/1000-ms provider delays.

| Check | HTTP 100/s | SMPP 100/s | HTTP 30/s | SMPP 30/s |
|---|---:|---:|---:|---:|
| Planned, submitted and accepted | 60,000 each | 60,000 each | 18,000 each | 18,000 each |
| Provider-completed and receipt-forwarded | 60,000 each | 60,000 each | 18,000 each | 18,000 each |
| Generator scheduling/capacity misses | 0 | 0 | 0 | 0 |
| Dispatch starts within measurement | 60,000 | 60,000 | 18,000 | 18,000 |
| Rejections, failures, timeouts, missing/duplicate receipts | 0 | 0 | 0 | 0 |

The 30-msg/s runs check the shared default after the pacing change. All runs
passed report schema/accounting, TLS/isolation and cleanup checks, leaving zero
owned containers, networks or volumes. Short-term variation was visible rather
than suppressed: at 100/s, aligned-second dispatch counts were 97–103 for HTTP
and 95–105 for SMPP. Downstream queueing remained, especially SMPP; complete
delivery during drain is not proof of stable 100-msg/s end-to-end throughput,
low latency or AWS capacity. These are single local observations.

Regression coverage includes pause recovery using Guava's actual algorithm and
a deterministic clock, long-pause recovery limits, deadlines, capacity exhaustion,
cancellation, phase attribution, callback/timeout/close races and dispatch in drain.
See [README.md](README.md) for reproduction and [VERIFICATION.md](VERIFICATION.md)
for lifecycle coverage. Raw reports, logs, profiling recordings and branch-specific
comparisons belong outside the repository.

The validated build was based on `a82e84d7ffc4f4bd223fb50a2bbf9f9f0f0347b0` with
working-tree fingerprint
`d60b78dfc88586d2efc13a67bbf73e2e5717c4f2498ef180739d14aeaee76bab`.
Later cleanup removed temporary incident/GC logging without changing pacing;
the harness regression suite passed again afterward.
