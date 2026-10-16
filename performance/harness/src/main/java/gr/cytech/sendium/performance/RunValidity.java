package gr.cytech.sendium.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Correctness and evidence classification, never a throughput or CPU-utilization performance gate. */
public final class RunValidity {
    public enum Severity { FAILED, INCONCLUSIVE }
    public record Reason(String code, String message, Severity severity) {
        public Reason {
            if (code == null || code.isBlank() || message == null || message.isBlank() || severity == null) {
                throw new IllegalArgumentException("Invalid validity reason");
            }
        }
    }
    public record Classification(String status, List<Reason> reasons, int exitCode) { }
    private RunValidity() { }

    public static Classification assess(RunConfig config, MessageLedger ledger, ResultReport.Context context,
            List<FixedRateGenerator.Statistics> phases, ResourceEvidence evidence, ResourceEvidence.Window window,
            List<Reason> observations) {
        List<Reason> reasons = new ArrayList<>(observations);
        var counts = ledger.snapshot(); var timeline = ledger.timeline();
        if (counts.violations() > 0 || counts.scheduled() != counts.attempted() + counts.missed()
                || counts.attempted() != counts.accepted() + counts.rejected() + counts.failed() + counts.timedOut() + counts.pendingAdmission()
                || counts.accepted() != counts.acceptedProviderCompleted() + counts.acceptedAwaitingProvider()
                || config.dlrEnabled() && counts.accepted() != counts.acceptedReceiptForwarded() + counts.acceptedAwaitingReceipt()) {
            failed(reasons, "ACCOUNTING_VIOLATION", "Scenario message accounting or duplicate/order checks failed.");
        }
        if (timeline == null || timeline.durationNanos() == 0) {
            failed(reasons, "MEASUREMENT_NOT_STARTED", "No measurement observation window completed.");
        } else {
            checkIntervals(counts, timeline, config, ledger, reasons);
            if (timeline.durationNanos() < java.util.concurrent.TimeUnit.SECONDS.toNanos(config.measurementSeconds())) {
                inconclusive(reasons, "PARTIAL_MEASUREMENT", "Measurement ended before its configured duration.");
            }
            long requested = BigDecimal.valueOf(config.offeredMessagesPerSecond()).multiply(BigDecimal.valueOf(config.measurementSeconds()))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
            if (counts.scheduled() < requested) { inconclusive(reasons, "REQUESTED_LOAD_NOT_SCHEDULED", "Fewer targets were scheduled than the configured workload requested."); }
            if (counts.scheduled() > requested) { failed(reasons, "SCHEDULED_TARGET_MISMATCH", "Scheduled targets exceed the configured workload."); }
            if (timeline.outsideWindowEvents() > 0) { inconclusive(reasons, "OUTSIDE_OBSERVATION_WINDOW", "Updates occurred outside the bounded observation window."); }
            if (timeline.events().stream().anyMatch(event -> event.outstanding().pendingAdmission() < 0
                    || event.outstanding().acceptedAwaitingProvider() < 0 || event.outstanding().acceptedAwaitingReceipt() < 0)) {
                failed(reasons, "BACKLOG_ACCOUNTING_VIOLATION", "A historical outstanding-work count was negative.");
            }
        }
        if (counts.missed() > 0 || phases.stream().anyMatch(FixedRateGenerator.Statistics::saturated)) {
            inconclusive(reasons, "GENERATOR_SATURATION", "The harness missed intended load targets through scheduling or capacity limits.");
        }
        if (phases.stream().anyMatch(FixedRateGenerator.Statistics::stoppedEarly)) {
            inconclusive(reasons, "GENERATOR_STOPPED_EARLY", "A workload phase was terminated early.");
        }
        if (ledger.latencies().values().stream().anyMatch(sample -> sample.overflowCount() > 0)) {
            inconclusive(reasons, "LATENCY_OVERFLOW", "Some latency samples exceeded the histogram range.");
        }
        if (ledger.eventsBeyondRetention() > 0) { inconclusive(reasons, "LIMITED_HISTORY", "Events arrived beyond retained correlation history."); }
        for (String issue : evidence.collectionIssues()) { inconclusive(reasons, "RESOURCE_COLLECTION_ISSUE", issue); }
        if (window == null) {
            inconclusive(reasons, "RESOURCE_WINDOW_MISSING", "The UTC measurement resource window is unavailable.");
        } else {
            if (timeline != null && window.nanos() != timeline.durationNanos()) {
                failed(reasons, "RESOURCE_WINDOW_MISMATCH", "Resource and workload observation durations disagree.");
            }
            checkSamples("sendium", evidence.sendium(), window, context, reasons);
            checkSamples("harness", evidence.harness(), window, context, reasons);
            if (!"local".equals(context.infrastructure().path("region").asText())) {
                checkCredits(evidence, context, window, reasons);
            }
        }
        List<Reason> unique = reasons.stream().distinct().toList();
        boolean failed = unique.stream().anyMatch(reason -> reason.severity() == Severity.FAILED);
        return new Classification(failed ? "failed" : unique.isEmpty() ? "valid" : "inconclusive", unique, failed ? 1 : 0);
    }

    private static void checkIntervals(MessageLedger.Counts counts, MeasurementTimeline.Snapshot timeline,
            RunConfig config, MessageLedger ledger, List<Reason> reasons) {
        List<java.util.function.ToLongFunction<MessageLedger.Counts>> additive = List.of(
                MessageLedger.Counts::scheduled, MessageLedger.Counts::attempted, MessageLedger.Counts::missed,
                MessageLedger.Counts::accepted, MessageLedger.Counts::rejected, MessageLedger.Counts::failed,
                MessageLedger.Counts::timedOut, MessageLedger.Counts::pendingAdmission,
                MessageLedger.Counts::providerArrivals, MessageLedger.Counts::providerResponses,
                MessageLedger.Counts::receiptsGenerated, MessageLedger.Counts::receiptsForwarded,
                MessageLedger.Counts::acceptedProviderCompleted, MessageLedger.Counts::acceptedAwaitingProvider,
                MessageLedger.Counts::acceptedReceiptForwarded, MessageLedger.Counts::acceptedAwaitingReceipt,
                MessageLedger.Counts::providerObservedWithoutAcceptance, MessageLedger.Counts::duplicateProviderRequests,
                MessageLedger.Counts::duplicateReceipts);
        if (additive.stream().anyMatch(metric -> timeline.cohorts().stream().mapToLong(cohort -> metric.applyAsLong(cohort.counts())).sum()
                != metric.applyAsLong(counts))) {
            failed(reasons, "COHORT_ACCOUNTING_VIOLATION", "Submission-cohort counters do not reconcile with overall accounting.");
        }
        if (timeline.cohorts().isEmpty() || timeline.cohorts().getLast().endOffsetNanos()
                != java.util.concurrent.TimeUnit.SECONDS.toNanos(config.measurementSeconds())) {
            failed(reasons, "MEASUREMENT_CONFIGURATION_MISMATCH", "Recorded cohort boundaries differ from the configured measurement duration.");
        }
        long planned = BigDecimal.valueOf(config.offeredMessagesPerSecond()).multiply(BigDecimal.valueOf(config.measurementSeconds()))
                .setScale(0, RoundingMode.CEILING).longValueExact();
        if (timeline.cohorts().stream().anyMatch(cohort -> cohort.counts().scheduled()
                > slotsBefore(cohort.endOffsetNanos(), planned, config.offeredMessagesPerSecond())
                    - slotsBefore(cohort.startOffsetNanos(), planned, config.offeredMessagesPerSecond()))) {
            failed(reasons, "COHORT_TARGET_MISMATCH", "A submission minute contains more targets than the declared fixed-rate schedule.");
        }
        var overallLatencies = ledger.latencies();
        for (var boundary : MessageLedger.Latency.values()) {
            long samples = timeline.cohorts().stream().mapToLong(cohort -> cohort.latencies().get(boundary).count()).sum();
            if (samples != overallLatencies.get(boundary).count()) {
                failed(reasons, "LATENCY_ACCOUNTING_VIOLATION", "Cohort latency samples do not reconcile for " + boundary + ".");
            }
        }
        if (timeline.outsideWindowEvents() == 0
                && (timeline.events().stream().mapToLong(MeasurementTimeline.EventInterval::providerArrivals).sum() != counts.providerArrivals()
                    || timeline.events().stream().mapToLong(MeasurementTimeline.EventInterval::providerResponses).sum() != counts.providerResponses()
                    || timeline.events().stream().mapToLong(MeasurementTimeline.EventInterval::receiptsGenerated).sum() != counts.receiptsGenerated()
                    || timeline.events().stream().mapToLong(MeasurementTimeline.EventInterval::receiptsForwarded).sum() != counts.receiptsForwarded())) {
            failed(reasons, "EVENT_ACCOUNTING_VIOLATION", "Event-time counters do not reconcile with overall accounting.");
        }
    }

    private static long slotsBefore(long boundaryNanos, long total, double rate) {
        double period = 1_000_000_000.0 / rate;
        long low = 0, high = total;
        while (low < high) {
            long middle = low + (high - low) / 2;
            long offset = middle == 0 ? 0 : (long) Math.ceil(middle * period);
            if (offset < boundaryNanos) { low = middle + 1; }
            else { high = middle; }
        }
        return low;
    }

    private static void checkSamples(String source, List<ResourceEvidence.Sample> samples, ResourceEvidence.Window window, ResultReport.Context context, List<Reason> reasons) {
        var summary = ResourceEvidence.summarize(samples, window);
        if (window.nanos() > 0 && (summary.cpuCoverageNanos() < window.nanos() || summary.hostCpuCoverageNanos() < window.nanos())) {
            inconclusive(reasons, "RESOURCE_CPU_COVERAGE_GAP", source + " process/host CPU samples do not cover the measurement window.");
        }
        if (summary.rssBytesMaximum() == null || summary.hostMemoryUsedBytesMaximum() == null) {
            inconclusive(reasons, "RESOURCE_MEMORY_MISSING", source + " process/host memory measurements are unavailable.");
        }
        java.time.Instant previous = null;
        Long firstPid = null, firstStart = null;
        for (var sample : samples) {
            var expectedPid = context.infrastructure().path(source).path("processId");
            if (sample.pid() != null) {
                if (expectedPid.isIntegralNumber() && sample.pid() != expectedPid.asLong()
                        || firstPid != null && !firstPid.equals(sample.pid())
                        || firstStart != null && !firstStart.equals(sample.processStartTicks())) {
                    failed(reasons, "RESOURCE_PROCESS_IDENTITY_MISMATCH", source + " resource evidence changed process identity or targets the wrong process.");
                    break;
                }
                firstPid = sample.pid(); firstStart = sample.processStartTicks();
            } else if (expectedPid.isIntegralNumber()) {
                inconclusive(reasons, "RESOURCE_PROCESS_IDENTITY_MISSING", source + " resource samples do not identify the expected process.");
            }
            var current = ResourceEvidence.instant(sample.timestamp());
            if (previous != null && !current.isAfter(previous)) {
                inconclusive(reasons, "RESOURCE_TIMESTAMPS_UNORDERED", source + " sample timestamps are duplicated or unordered.");
                break;
            }
            previous = current;
        }
    }

    private static void checkCredits(ResourceEvidence evidence, ResultReport.Context context, ResourceEvidence.Window window, List<Reason> reasons) {
        for (String source : List.of("sendium", "harness")) {
            String expectedId = context.infrastructure().path(source).path("instanceId").asText();
            var matching = evidence.cpuCredits().stream().filter(credit -> source.equals(credit.source())).toList();
            if (expectedId.isBlank() || matching.size() != 1) {
                inconclusive(reasons, "INSTANCE_CREDIT_METADATA_MISSING", source + " instance credit metadata is missing or ambiguous.");
                continue;
            }
            var credit = matching.getFirst();
            if (credit.burstable() == null) {
                inconclusive(reasons, "INSTANCE_BURSTABILITY_MISSING", source + " burstable-performance metadata is unavailable.");
            } else if (credit.burstable() && credit.mode().equals("not-applicable")
                    || !credit.burstable() && !List.of("not-applicable", "unknown").contains(credit.mode())) {
                failed(reasons, "CPU_CREDIT_MODE_INCONSISTENT", source + " credit mode contradicts its instance-type metadata.");
            }
            if (!expectedId.equals(credit.instanceId())) { failed(reasons, "INSTANCE_ID_MISMATCH", source + " credit evidence belongs to another instance."); }
            String expectedType = context.infrastructure().path(source).path("instanceType").asText();
            if (!expectedType.isBlank() && !expectedType.equals(credit.instanceType())) {
                failed(reasons, "INSTANCE_TYPE_MISMATCH", source + " credit evidence has the wrong instance type.");
            }
            String expectedMode = context.infrastructure().path(source).path("creditMode").asText();
            if (!expectedMode.isBlank() && !expectedMode.equals(credit.mode()) && !credit.mode().equals("unknown")) {
                failed(reasons, "CPU_CREDIT_MODE_MISMATCH", source + " credit mode differs from the effective preset.");
            }
            for (String issue : credit.collectionIssues()) { inconclusive(reasons, "CPU_CREDIT_COLLECTION_ISSUE", source + ": " + issue); }
            if (ResourceEvidence.instant(credit.windowStart()).isAfter(window.start())
                    || ResourceEvidence.instant(credit.windowEnd()).isBefore(window.end())) {
                inconclusive(reasons, "CPU_CREDIT_WINDOW_MISMATCH", source + " credit query does not cover this measurement.");
            }
            if (credit.mode().equals("unknown")) { inconclusive(reasons, "CPU_CREDIT_MODE_MISSING", source + " Standard/Unlimited mode is unknown."); }
            if (!List.of("not-applicable", "unknown").contains(credit.mode()) && credit.samples().stream().noneMatch(sample ->
                    sample.balanceAverage() != null && ResourceEvidence.instant(sample.timestamp()).isBefore(window.end())
                            && ResourceEvidence.instant(sample.timestamp()).plusSeconds(credit.periodSeconds()).isAfter(window.start()))) {
                inconclusive(reasons, "CPU_CREDIT_BALANCE_MISSING", source + " has no observed credit balance overlapping measurement.");
            }
        }
    }

    public static List<Reason> httpAdmission(Map<HttpAdmissionClient.Problem, Long> problems) {
        return diagnostics("HTTP_ADMISSION", problems, problem -> problem == HttpAdmissionClient.Problem.TRANSPORT_FAILURE ? Severity.INCONCLUSIVE : Severity.FAILED);
    }
    public static List<Reason> smppAdmission(Map<SmppAdmissionClient.Problem, Long> problems) {
        return diagnostics("SMPP_ADMISSION", problems, problem -> switch (problem) {
            case SATURATION, LIMITED_HISTORY, AMBIGUOUS_RECEIPT, TRANSPORT_FAILURE -> Severity.INCONCLUSIVE;
            default -> Severity.FAILED;
        });
    }
    public static List<Reason> provider(Map<SmppProvider.Problem, Long> problems) {
        return diagnostics("PROVIDER", problems, problem -> problem == SmppProvider.Problem.SATURATION ? Severity.INCONCLUSIVE : Severity.FAILED);
    }
    public static List<Reason> httpReceipts(Map<HttpReceiptReceiver.Problem, Long> problems) {
        return diagnostics("HTTP_RECEIPT", problems, problem -> switch (problem) {
            case SATURATION, LIMITED_HISTORY, IO_FAILURE -> Severity.INCONCLUSIVE;
            default -> Severity.FAILED;
        });
    }
    private static <E extends Enum<E>> List<Reason> diagnostics(String prefix, Map<E, Long> counts, Function<E, Severity> severity) {
        return counts.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> new Reason(prefix + "_" + entry.getKey().name(),
                        prefix + " reported " + entry.getValue() + " " + entry.getKey().name() + " outcomes.", severity.apply(entry.getKey()))).toList();
    }
    private static void failed(List<Reason> reasons, String code, String message) { reasons.add(new Reason(code, message, Severity.FAILED)); }
    private static void inconclusive(List<Reason> reasons, String code, String message) { reasons.add(new Reason(code, message, Severity.INCONCLUSIVE)); }
}
