package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Schema-v1 JSON and Markdown from frozen accounting and explicit resource evidence. */
public final class ResultReport {
    public record Artifact(String name, String sha256) {
        public Artifact {
            if (name == null || name.isBlank() || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid artifact identity");
            }
        }
    }
    public record Provenance(String branch, String applicationCommit, String harnessRevision,
            String workflowRunUrl, String runIdentity, List<Artifact> artifacts) {
        public Provenance {
            if (branch == null || branch.isBlank() || applicationCommit == null || !applicationCommit.matches("[0-9a-f]{40}")
                    || harnessRevision == null || !harnessRevision.matches("[0-9a-f]{40}") || runIdentity == null || runIdentity.isBlank()) {
                throw new IllegalArgumentException("Missing exact run provenance");
            }
            artifacts = List.copyOf(artifacts);
        }
    }
    /** Supply sanitized public configuration, never runtime credential bundles. */
    public record Context(Provenance provenance, ObjectNode application, ObjectNode infrastructure, List<String> diagnostics) {
        public Context {
            java.util.Objects.requireNonNull(provenance);
            application = application.deepCopy(); infrastructure = infrastructure.deepCopy(); diagnostics = List.copyOf(diagnostics);
            if (!application.has("jvmArguments") || !application.has("connections") || !application.has("dlrStorage")
                    || !infrastructure.has("preset") || !infrastructure.has("region") || !infrastructure.has("sendium")
                    || !infrastructure.has("harness") || !infrastructure.has("database")) {
                throw new IllegalArgumentException("Effective application and infrastructure settings are required");
            }
        }
    }
    public record Report(ObjectNode json, String markdown) { }
    private final ObjectMapper mapper = new ObjectMapper();

    public Report build(RunConfig config, MessageLedger ledger, Context context,
            List<FixedRateGenerator.Statistics> generatorPhases, boolean drainTimeoutReached) {
        return build(config, ledger, context, generatorPhases, drainTimeoutReached, ResourceEvidence.empty(), null, List.of());
    }

    public Report build(RunConfig config, MessageLedger ledger, Context context,
            List<FixedRateGenerator.Statistics> generatorPhases, boolean drainTimeoutReached,
            ResourceEvidence evidence, ResourceEvidence.Window resourceWindow, List<RunValidity.Reason> observations) {
        if (!ledger.isFrozen()) { throw new IllegalStateException("Freeze accounting before publishing results"); }
        var timeline = ledger.timeline();
        var counts = ledger.snapshot();
        if (timeline == null && counts.scheduled() != 0) { throw new IllegalStateException("Measurement interval origin was not recorded"); }
        long duration = timeline == null ? 0 : timeline.durationNanos();
        List<MeasurementTimeline.EventInterval> events = timeline == null ? List.of() : timeline.events();
        long arrivals = 0, responses = 0, receipts = 0;
        for (var interval : events) {
            if (interval.phase().equals("measurement")) {
                arrivals += interval.providerArrivals(); responses += interval.providerResponses(); receipts += interval.receiptsForwarded();
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.set("provenance", mapper.valueToTree(context.provenance()));
        ObjectNode configuration = root.putObject("configuration");
        configuration.set("workload", mapper.valueToTree(config));
        configuration.set("application", context.application()); configuration.set("infrastructure", context.infrastructure());
        root.set("overall", measurements(counts, ledger.latencies(), duration, config.offeredMessagesPerSecond(), arrivals, responses, receipts));
        var cohorts = root.putArray("cohortIntervals");
        if (timeline != null) {
            for (var cohort : timeline.cohorts()) {
                // Cohort completions include drain; these are not event-time sending rates.
                ObjectNode value = measurements(cohort.counts(), cohort.latencies(), cohort.observedNanos(),
                        config.offeredMessagesPerSecond(), null, null, null);
                value.put("startOffsetSeconds", seconds(cohort.startOffsetNanos()));
                value.put("endOffsetSeconds", seconds(cohort.endOffsetNanos()));
                cohorts.add(value);
            }
        }
        var intervals = root.putArray("eventIntervals");
        for (var interval : events) {
            ObjectNode value = intervals.addObject();
            value.put("phase", interval.phase()); value.put("startOffsetSeconds", seconds(interval.startOffsetNanos()));
            value.put("durationSeconds", seconds(interval.durationNanos()));
            value.put("providerArrivals", interval.providerArrivals()); value.put("providerResponses", interval.providerResponses());
            value.put("receiptsGenerated", interval.receiptsGenerated()); value.put("receiptsForwarded", interval.receiptsForwarded());
            ObjectNode rates = value.putObject("rates");
            rates.put("providerArrivalsPerSecond", rate(interval.providerArrivals(), interval.durationNanos()));
            rates.put("providerResponsesPerSecond", rate(interval.providerResponses(), interval.durationNanos()));
            rates.put("receiptsForwardedPerSecond", rate(interval.receiptsForwarded(), interval.durationNanos()));
            value.set("outstanding", mapper.valueToTree(interval.outstanding()));
        }
        ObjectNode drain = root.putObject("drain");
        drain.put("durationSeconds", timeline == null ? 0 : seconds(timeline.drainNanos()));
        drain.put("timeoutReached", drainTimeoutReached);
        var finalBacklog = events.isEmpty() ? new MeasurementTimeline.Backlog(counts.pendingAdmission(),
                counts.acceptedAwaitingProvider(), counts.acceptedAwaitingReceipt()) : events.getLast().outstanding();
        drain.put("unfinishedAccepted", config.dlrEnabled() ? finalBacklog.acceptedAwaitingReceipt() : finalBacklog.acceptedAwaitingProvider());
        drain.put("pendingAdmission", finalBacklog.pendingAdmission());
        drain.put("unfinishedAllPhases", ledger.activeSize()); drain.put("pendingAdmissionAllPhases", ledger.outstandingRequests());
        drain.put("allPhaseCountsBoundary", "accounting-finalization");

        long overflow = ledger.latencies().values().stream().mapToLong(LatencyHistogram.Snapshot::overflowCount).sum();
        ObjectNode metadata = root.putObject("measurementMetadata");
        metadata.put("histogramAlgorithm", "HdrHistogram");
        metadata.putObject("histogramPrecision").put("significantDigits", 3).put("resolutionMicros", 1).put("maximumMicros", LatencyHistogram.MAX_MICROS);
        metadata.put("histogramOverflowCount", overflow);
        metadata.put("duplicateDetectionHorizonSeconds", seconds(ledger.retentionNanos()));
        metadata.put("retiredHistoryEvictions", ledger.retiredEvictions()); metadata.put("eventsBeyondRetention", ledger.eventsBeyondRetention());
        metadata.put("intervalBucketBudget", timeline == null ? MeasurementTimeline.DEFAULT_MAX_INTERVALS : timeline.intervalBudget());
        metadata.set("inputTiming", mapper.valueToTree(ledger.inputTiming()));
        var phases = metadata.putArray("generatorPhases");
        for (var phase : generatorPhases) {
            ObjectNode value = mapper.valueToTree(phase);
            value.put("achievedInputPerSecond", phase.achievedInputPerSecond());
            value.put("saturated", phase.saturated());
            phases.add(value);
        }
        metadata.put("outsideWindowEvents", timeline == null ? 0 : timeline.outsideWindowEvents());
        var limitations = metadata.putArray("limitations");
        limitations.add("Provider response completion is simulator-side local write completion, not application consumption.");
        limitations.add("Cohort completions include drain; throughput rates use only the stated event-time observation window.");
        limitations.add("Duplicate detection is bounded by age and capacity; configured retention is an upper bound, not guaranteed coverage.");
        limitations.add("A short run does not establish sustainable throughput or long-term burstable-instance CPU-credit capacity.");
        limitations.add("Process CPU uses one logical core as 100 percent; host CPU uses aggregate machine capacity.");
        limitations.add("Resource windows use synchronized UTC timestamps; workload latencies use the harness monotonic clock.");
        limitations.add("CloudWatch CPU-credit samples are coarse period observations and may lag; absent values are not zero.");
        limitations.add("Average-rate pacing retains delayed targets and permits recovery using up to one second of stored Guava permits; it is not a strict per-second cap or a production arrival distribution.");
        limitations.add("Input dispatch timing marks the start of the adapter transport call, not completed socket writes; provider arrivals remain separate evidence.");
        root.set("resources", evidence.json(mapper, resourceWindow, timeline));
        root.set("validity", mapper.valueToTree(RunValidity.assess(config, ledger, context, generatorPhases, evidence, resourceWindow, observations)));
        root.set("diagnostics", mapper.valueToTree(context.diagnostics()));
        return new Report(root, markdown(root));
    }

    private ObjectNode measurements(MessageLedger.Counts counts, Map<MessageLedger.Latency, LatencyHistogram.Snapshot> latencies,
            long duration, double requestedRate, Long arrivals, Long responses, Long receipts) {
        ObjectNode value = mapper.createObjectNode();
        value.put("durationSeconds", seconds(duration)); value.set("counts", mapper.valueToTree(counts));
        value.withObject("/counts").put("missingReceipts", counts.acceptedAwaitingReceipt());
        ObjectNode rates = value.putObject("rates");
        rates.put("requestedInputPerSecond", duration == 0 ? null : requestedRate);
        rates.put("attemptedInputPerSecond", rate(counts.attempted(), duration));
        rates.put("providerArrivalsPerSecond", arrivals == null ? null : rate(arrivals, duration));
        rates.put("providerResponsesPerSecond", responses == null ? null : rate(responses, duration));
        rates.put("receiptsForwardedPerSecond", receipts == null ? null : rate(receipts, duration));
        ObjectNode distributions = value.putObject("latencies");
        latencies.forEach((key, sample) -> distributions.set(latencyName(key), mapper.valueToTree(sample)));
        return value;
    }

    /** Offline resource enrichment: immutable workload measurements/reasons are never recomputed. */
    public Report finalizeResources(ObjectNode initial, ResourceEvidence evidence) throws Exception {
        if (initial.path("schemaVersion").asInt() != 1
                || initial.at("/measurementMetadata/resourceFinalizationVersion").asInt() != 1) {
            throw new IllegalArgumentException("Report does not support resource finalization");
        }
        ObjectNode result = initial.deepCopy();
        var metadata = result.withObject("/measurementMetadata");
        long duration = metadata.path("observedDurationNanos").asLong();
        ResourceEvidence.Window window = metadata.path("measurementStartUtc").isTextual()
                ? new ResourceEvidence.Window(java.time.Instant.parse(metadata.path("measurementStartUtc").asText()),
                    java.time.Instant.parse(metadata.path("measurementStartUtc").asText()).plusNanos(duration)) : null;
        var configuration = result.path("configuration");
        Context context = new Context(mapper.treeToValue(result.path("provenance"), Provenance.class),
                (ObjectNode) configuration.path("application"), (ObjectNode) configuration.path("infrastructure"),
                mapper.convertValue(result.path("diagnostics"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
        List<MeasurementTimeline.EventInterval> intervals = new java.util.ArrayList<>();
        for (var interval : result.path("eventIntervals")) {
            intervals.add(new MeasurementTimeline.EventInterval(interval.path("phase").asText(),
                    Math.round(interval.path("startOffsetSeconds").asDouble() * 1_000_000_000),
                    Math.round(interval.path("durationSeconds").asDouble() * 1_000_000_000),
                    interval.path("providerArrivals").asLong(), interval.path("providerResponses").asLong(),
                    interval.path("receiptsGenerated").asLong(), interval.path("receiptsForwarded").asLong(),
                    mapper.treeToValue(interval.path("outstanding"), MeasurementTimeline.Backlog.class)));
        }
        List<RunValidity.Reason> retained = new java.util.ArrayList<>();
        for (var reason : result.path("validity").path("reasons")) {
            if (!reason.path("scope").asText().equals("resources")) { retained.add(mapper.treeToValue(reason, RunValidity.Reason.class)); }
        }
        retained.addAll(RunValidity.resourceReasons(context, duration, evidence, window));
        result.set("resources", evidence.jsonForIntervals(mapper, window, intervals));
        result.set("validity", mapper.valueToTree(RunValidity.classify(retained)));
        metadata.put("resourcesFinalized", true);
        return new Report(result, markdown(result));
    }

    public void write(Path directory, Report report) throws Exception {
        Files.createDirectories(directory);
        // A publication mistake must not silently replace an earlier run's results.
        if (Files.exists(directory.resolve("results.json")) || Files.exists(directory.resolve("summary.md"))) {
            throw new java.nio.file.FileAlreadyExistsException("Report files already exist");
        }
        Files.writeString(directory.resolve("results.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.json()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("summary.md"), report.markdown(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String latencyName(MessageLedger.Latency latency) {
        return switch (latency) {
            case ACKNOWLEDGEMENT -> "acknowledgement";
            case SUBMISSION_TO_PROVIDER -> "submissionToProvider";
            case PROVIDER_RESPONSE_COMPLETION -> "providerResponseCompletion";
            case RECEIPT_FORWARDING -> "receiptForwarding";
            case SIMULATOR_RECEIPT_DELAY -> "simulatorReceiptDelay";
        };
    }
    private static double seconds(long nanos) { return nanos / 1_000_000_000.0; }
    private static Double rate(long count, long nanos) { return nanos == 0 ? null : count / seconds(nanos); }
    private static String number(com.fasterxml.jackson.databind.JsonNode node) { return node.isNull() || node.isMissingNode() ? "n/a" : String.format(Locale.ROOT, "%.3f", node.asDouble()); }
    private static String text(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'"); }

    static String markdown(ObjectNode root) {
        StringBuilder out = new StringBuilder("# Sendium performance results\n\n");
        var provenance = root.path("provenance");
        out.append("**Status:** ").append(root.path("validity").path("status").asText()).append("\n\n")
                .append("Branch: `").append(text(provenance.path("branch").asText())).append("` · Application: `")
                .append(provenance.path("applicationCommit").asText()).append("` · Harness: `").append(provenance.path("harnessRevision").asText()).append("`\n\n");
        var workload = root.path("configuration").path("workload");
        out.append("Ingress: ").append(text(workload.path("ingressMode").asText())).append(" · DLR requested: ")
                .append(workload.path("dlrEnabled").asBoolean()).append(" · Worker TPS: ")
                .append(workload.path("workerTps").asLong() == 0 ? "unlimited (0)" : workload.path("workerTps").asText())
                .append(" · Run: ").append(text(provenance.path("runIdentity").asText())).append("\n\n");
        for (var reason : root.path("validity").path("reasons")) { out.append("- **").append(text(reason.path("code").asText())).append(":** ").append(text(reason.path("message").asText())).append('\n'); }
        var overall = root.path("overall"); var counts = overall.path("counts"); var rates = overall.path("rates");
        out.append("\n## Overall\n\n| Metric | Value |\n|---|---:|\n");
        for (String field : List.of("scheduled", "attempted", "missed", "accepted", "rejected", "failed", "timedOut", "pendingAdmission",
                "providerArrivals", "providerResponses", "receiptsGenerated", "receiptsForwarded", "missingReceipts", "duplicateReceipts",
                "acceptedAwaitingProvider", "acceptedAwaitingReceipt", "providerObservedWithoutAcceptance", "peakOutstanding", "peakTracked")) {
            out.append('|').append(field).append('|').append(counts.path(field).asLong()).append("|\n");
        }
        for (String field : List.of("requestedInputPerSecond", "attemptedInputPerSecond", "providerArrivalsPerSecond", "providerResponsesPerSecond", "receiptsForwardedPerSecond")) {
            out.append('|').append(field).append('|').append(number(rates.path(field))).append("|\n");
        }
        out.append("\nRates exclude drain completions; admission alone is not sustainable sending throughput.\n\n");
        if (!root.path("measurementMetadata").path("generatorPhases").isEmpty()) {
            out.append("### Generator phases (warm-up then measurement)\n\n| Requested/s | Achieved/s | Scheduled | Attempted | Scheduling misses | Capacity misses | Max lateness ms | Stopped early |\n|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (var phase : root.path("measurementMetadata").path("generatorPhases")) {
                out.append('|').append(number(phase.path("requestedPerSecond"))).append('|').append(number(phase.path("achievedInputPerSecond")));
                for (String field : List.of("scheduled", "attempted", "missedScheduling", "missedCapacity")) { out.append('|').append(phase.path(field).asLong()); }
                out.append('|').append(String.format(Locale.ROOT, "%.3f", phase.path("maxLatenessNanos").asLong() / 1_000_000.0))
                        .append('|').append(phase.path("stoppedEarly").asBoolean()).append("|\n");
            }
            for (var phase : root.path("measurementMetadata").path("generatorPhases")) {
                if (phase.has("pacingMode")) {
                    out.append("\nPacing: ").append(text(phase.path("pacingMode").asText()))
                            .append("; targets delayed by at least one nominal interval: ")
                            .append(phase.path("delayedByAtLeastOnePeriod").asLong())
                            .append("; send lateness p99 ms: ")
                            .append(number(phase.path("submissionLateness").path("p99Millis"))).append(".\n\n");
                }
            }
        }
        var inputTiming = root.path("measurementMetadata").path("inputTiming");
        if (inputTiming.isObject()) {
            out.append("\n### Actual input timing\n\n").append(text(inputTiming.path("dispatchBoundary").asText()))
                    .append(". Measurement dispatch starts: ").append(inputTiming.path("dispatchesDuringMeasurement").asLong())
                    .append("; drain dispatch starts: ").append(inputTiming.path("dispatchesDuringDrain").asLong())
                    .append(". Submission-to-dispatch p99 ms: ")
                    .append(number(inputTiming.path("submissionToDispatch").path("p99Millis")))
                    .append(". Per-second submission/dispatch counts are in JSON inputTiming.seconds; seconds are aligned to measurement start.\n");
        }
        latencyTable(out, "Overall latency (milliseconds)", overall.path("latencies"));
        out.append("\n## One-minute submission cohorts\n\nCounts follow scheduled-time cohorts through drain; cohort completion rates are intentionally not reported.\n\n")
                .append("| Scheduled interval (s) | Observed (s) | Attempted | Accepted | Rejected | Failed | Timed out | Missed | Accepted awaiting provider completion | Accepted awaiting forwarded DLR |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var interval : root.path("cohortIntervals")) {
            out.append('|').append(number(interval.path("startOffsetSeconds"))).append('–').append(number(interval.path("endOffsetSeconds")))
                    .append('|').append(number(interval.path("durationSeconds")));
            for (String field : List.of("attempted", "accepted", "rejected", "failed", "timedOut", "missed", "acceptedAwaitingProvider", "acceptedAwaitingReceipt")) { out.append('|').append(interval.path("counts").path(field).asLong()); }
            out.append("|\n");
        }
        out.append("\n<details><summary>Per-cohort latency percentiles</summary>\n\n");
        for (var interval : root.path("cohortIntervals")) { latencyTable(out, "Cohort " + number(interval.path("startOffsetSeconds")) + "–" + number(interval.path("endOffsetSeconds")) + " seconds", interval.path("latencies")); }
        out.append("\n</details>\n\n## Processing rates and outstanding work\n\n")
                .append("Rates describe events during each interval; outstanding counts describe its end. The provider is simulated, and forwarded DLRs are received by the test tool.\n\n")
                .append("During drain, no new messages are generated: responses and DLRs for previously submitted messages can still arrive. Drain is a bounded completion period, not another sustained-load test.\n\n")
                .append("### Processing rates during each interval\n\n")
                .append("| Phase | Start (s) | Duration (s) | Messages reaching simulated provider/s | Simulated provider responses completed/s | Forwarded DLRs received by test tool/s |\n|---|---:|---:|---:|---:|---:|\n");
        for (var interval : root.path("eventIntervals")) {
            out.append('|').append(interval.path("phase").asText()).append('|').append(number(interval.path("startOffsetSeconds")))
                    .append('|').append(number(interval.path("durationSeconds")));
            for (String field : List.of("providerArrivalsPerSecond", "providerResponsesPerSecond", "receiptsForwardedPerSecond")) { out.append('|').append(number(interval.path("rates").path(field))); }
            out.append("|\n");
        }
        out.append("\n### Outstanding work at each interval's end\n\n")
                .append("These counts are inferred from observed message progress, not direct readings of Sendium's internal queues. Provider completion means the simulator completed its response write, not proof that Sendium consumed it.\n\n")
                .append("| Phase | Interval end (s) | Requests awaiting Sendium acknowledgement | Accepted messages awaiting provider completion | Accepted messages awaiting forwarded DLR |\n|---|---:|---:|---:|---:|---:|\n");
        for (var interval : root.path("eventIntervals")) {
            double end = interval.path("startOffsetSeconds").asDouble() + interval.path("durationSeconds").asDouble();
            out.append('|').append(interval.path("phase").asText()).append('|').append(String.format(Locale.ROOT, "%.3f", end));
            for (String field : List.of("pendingAdmission", "acceptedAwaitingProvider", "acceptedAwaitingReceipt")) { out.append('|').append(interval.path("outstanding").path(field).asLong()); }
            out.append("|\n");
        }
        out.append("\n## Drain\n\nDuration: ").append(number(root.path("drain").path("durationSeconds"))).append(" seconds; timeout: ")
                .append(root.path("drain").path("timeoutReached").asBoolean()).append("; unfinished accepted: ")
                .append(root.path("drain").path("unfinishedAccepted").asLong()).append("; unfinished across all phases at finalization: ")
                .append(root.path("drain").path("unfinishedAllPhases").asLong()).append("; pending receipt acknowledgements: ")
                .append(root.path("drain").has("pendingReceiptAcknowledgements") ? root.path("drain").path("pendingReceiptAcknowledgements").asLong() : "n/a").append(".\n\n")
                .append("## Resources\n\nProcess CPU: 100% is one logical core. Host CPU: 100% is aggregate host capacity.\n\n")
                .append("| Source | Process CPU avg % | Process CPU max % | Host CPU avg % | Peak RSS MiB | Peak host used MiB | CPU coverage s |\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (String source : List.of("sendium", "harness")) {
            resourceRow(out, source, root.path("resources").path("summary").path("overall").path(source));
        }
        out.append("\n<details><summary>Per-minute resource measurements</summary>\n\n")
                .append("| Phase / interval / source | Process CPU avg % | Process CPU max % | Host CPU avg % | Peak RSS MiB | Peak host used MiB | CPU coverage s |\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (var interval : root.path("resources").path("summary").path("intervals")) {
            for (String source : List.of("sendium", "harness")) {
                resourceRow(out, interval.path("phase").asText() + " / " + number(interval.path("startOffsetSeconds")) + "s / " + source, interval.path(source));
            }
        }
        out.append("\n</details>\n\n### Instance CPU-credit observations\n\n| Source | Instance | Type | Mode | Balance min | Balance max | Samples |\n|---|---|---|---|---:|---:|---:|\n");
        for (var credit : root.path("resources").path("cpuCredits")) {
            Double minimum = null, maximum = null;
            for (var sample : credit.path("samples")) {
                if (sample.path("balanceAverage").isNumber()) {
                    double value = sample.path("balanceAverage").asDouble();
                    minimum = minimum == null ? value : Math.min(minimum, value);
                    maximum = maximum == null ? value : Math.max(maximum, value);
                }
            }
            out.append('|').append(text(credit.path("source").asText())).append('|').append(text(credit.path("instanceId").asText()))
                    .append('|').append(text(credit.path("instanceType").asText())).append('|').append(text(credit.path("mode").asText()))
                    .append('|').append(minimum == null ? "n/a" : String.format(Locale.ROOT, "%.3f", minimum))
                    .append('|').append(maximum == null ? "n/a" : String.format(Locale.ROOT, "%.3f", maximum))
                    .append('|').append(credit.path("samples").size()).append("|\n");
        }
        if (root.path("resources").path("cpuCredits").isEmpty()) { out.append("\nNo EC2 credit observations supplied. Local verification does not require them; AWS runs require matching instance credit metadata.\n"); }
        out.append("\n## Limitations\n\n");
        for (var limitation : root.path("measurementMetadata").path("limitations")) { out.append("- ").append(text(limitation.asText())).append('\n'); }
        return out.toString();
    }

    private static void resourceRow(StringBuilder out, String label, com.fasterxml.jackson.databind.JsonNode summary) {
        out.append('|').append(text(label)).append('|').append(number(summary.path("cpuPercentAverage")))
                .append('|').append(number(summary.path("cpuPercentMaximum"))).append('|').append(number(summary.path("hostCpuPercentAverage")))
                .append('|').append(mebibytes(summary.path("rssBytesMaximum"))).append('|').append(mebibytes(summary.path("hostMemoryUsedBytesMaximum")))
                .append('|').append(String.format(Locale.ROOT, "%.3f", summary.path("cpuCoverageNanos").asLong() / 1_000_000_000.0)).append("|\n");
    }
    private static String mebibytes(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isNumber() ? String.format(Locale.ROOT, "%.3f", value.asDouble() / 1_048_576.0) : "n/a";
    }

    private static void latencyTable(StringBuilder out, String heading, com.fasterxml.jackson.databind.JsonNode latencies) {
        out.append("### ").append(heading).append("\n\n| Boundary | Samples | p50 ms | p95 ms | p99 ms |\n|---|---:|---:|---:|---:|\n");
        for (String key : List.of("acknowledgement", "submissionToProvider", "providerResponseCompletion", "receiptForwarding", "simulatorReceiptDelay")) {
            var value = latencies.path(key);
            out.append('|').append(key).append('|').append(value.path("count").asLong()).append('|').append(number(value.path("p50Millis")))
                    .append('|').append(number(value.path("p95Millis"))).append('|').append(number(value.path("p99Millis"))).append("|\n");
        }
    }
}
