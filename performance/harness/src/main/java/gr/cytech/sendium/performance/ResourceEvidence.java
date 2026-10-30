package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded samples imported from per-instance agents; no AWS credentials in the load JVM. */
public record ResourceEvidence(List<Sample> sendium, List<Sample> harness, List<Credits> cpuCredits,
        List<String> collectionIssues, int sampleBudget, RdsResourceEvidence database) {
    public static final int DEFAULT_SAMPLE_BUDGET = 4096;
    public record Window(Instant start, Instant end) {
        public Window { if (end.isBefore(start)) { throw new IllegalArgumentException("Invalid resource window"); } }
        public long nanos() { return Duration.between(start, end).toNanos(); }
    }
    public record Sample(String timestamp, String intervalStartTimestamp, Double intervalSeconds,
            Double cpuPercent, Double hostCpuPercent, Long rssBytes, Long hostMemoryUsedBytes,
            Long hostMemoryTotalBytes, Integer cpuCount, Long pid, Long processStartTicks,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<ProcessSample> processes) {
        public Sample {
            Instant end = instant(timestamp);
            if (intervalStartTimestamp != null && (!instant(intervalStartTimestamp).isBefore(end)
                    || intervalSeconds == null || !Double.isFinite(intervalSeconds) || intervalSeconds <= 0)) {
                throw new IllegalArgumentException("Invalid sample interval");
            }
            if (!nonnegative(cpuPercent) || !nonnegative(hostCpuPercent) || hostCpuPercent != null && hostCpuPercent > 100
                    || !nonnegative(rssBytes) || !nonnegative(hostMemoryUsedBytes) || !nonnegative(hostMemoryTotalBytes)
                    || hostMemoryUsedBytes != null && hostMemoryTotalBytes != null && hostMemoryUsedBytes > hostMemoryTotalBytes
                    || cpuCount != null && cpuCount < 1 || cpuPercent != null && intervalStartTimestamp == null
                    || pid != null && pid < 1 || !nonnegative(processStartTicks)) {
                throw new IllegalArgumentException("Invalid resource sample values");
            }
            if (processes != null) {
                processes = List.copyOf(processes);
                if (processes.size() < 2 || processes.size() > 16 || pid != null || processStartTicks != null) {
                    throw new IllegalArgumentException("Invalid process group or conflicting single-process identity");
                }
                var names = new java.util.HashSet<String>();
                var pids = new java.util.HashSet<Long>();
                long rss = 0; double cpu = 0;
                for (ProcessSample process : processes) {
                    if (!names.add(process.name()) || !pids.add(process.pid()) || Long.MAX_VALUE - rss < process.rssBytes()
                            || (cpuPercent == null) != (process.cpuPercent() == null)) {
                        throw new IllegalArgumentException("Invalid process group member or aggregate");
                    }
                    rss += process.rssBytes();
                    if (cpuPercent != null) { cpu += process.cpuPercent(); }
                }
                if (rssBytes == null || rss != rssBytes || !Double.isFinite(cpu)
                        || cpuPercent != null && Math.abs(cpuPercent - cpu) > Math.max(1e-12, Math.abs(cpu) * 1e-12)) {
                    throw new IllegalArgumentException("Process group aggregates disagree with members");
                }
            }
        }
        public Sample(String timestamp, String intervalStartTimestamp, Double intervalSeconds,
                Double cpuPercent, Double hostCpuPercent, Long rssBytes, Long hostMemoryUsedBytes,
                Long hostMemoryTotalBytes, Integer cpuCount, Long pid, Long processStartTicks) {
            this(timestamp, intervalStartTimestamp, intervalSeconds, cpuPercent, hostCpuPercent, rssBytes,
                    hostMemoryUsedBytes, hostMemoryTotalBytes, cpuCount, pid, processStartTicks, null);
        }
        java.util.Map<String, List<Long>> identities() {
            if (processes == null) { return null; }
            return processes.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ProcessSample::name,
                    process -> List.of(process.pid(), process.processStartTicks())));
        }
    }
    public record ProcessSample(String name, Long pid, Long processStartTicks, Double cpuPercent, Long rssBytes) {
        public ProcessSample {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}") || pid == null || pid < 1
                    || processStartTicks == null || processStartTicks < 0 || rssBytes == null || rssBytes < 0
                    || !nonnegative(cpuPercent)) {
                throw new IllegalArgumentException("Invalid named process sample");
            }
        }
    }
    public record CreditSample(String timestamp, Double balanceAverage, Double usageSum,
            Double surplusBalanceAverage, Double surplusChargedSum) {
        public CreditSample {
            instant(timestamp);
            if (!nonnegative(balanceAverage) || !nonnegative(usageSum) || !nonnegative(surplusBalanceAverage)
                    || !nonnegative(surplusChargedSum)) { throw new IllegalArgumentException("Invalid CPU credit sample"); }
        }
    }
    public record Credits(String source, String instanceId, String instanceType, Boolean burstable, String mode, int periodSeconds,
            String windowStart, String windowEnd, List<CreditSample> samples, List<String> collectionIssues) {
        public Credits {
            if (!List.of("sendium", "kannel", "harness").contains(source) || instanceId == null || instanceType == null
                    || !List.of("standard", "unlimited", "not-applicable", "unknown").contains(mode)
                    || periodSeconds < 1 || !instant(windowStart).isBefore(instant(windowEnd)) || samples.size() > 1440) {
                throw new IllegalArgumentException("Invalid instance credit evidence");
            }
            samples = List.copyOf(samples); collectionIssues = List.copyOf(collectionIssues);
        }
    }
    public record Summary(long cpuCoverageNanos, long hostCpuCoverageNanos, Double cpuPercentAverage, Double cpuPercentMaximum,
            Double hostCpuPercentAverage, Long rssBytesMaximum, Long hostMemoryUsedBytesMaximum, long memorySamples) { }

    public ResourceEvidence {
        if (sampleBudget < 1 || sendium.size() > sampleBudget || harness.size() > sampleBudget || cpuCredits.size() > 2) {
            throw new IllegalArgumentException("Resource evidence exceeds sample budget");
        }
        sendium = List.copyOf(sendium); harness = List.copyOf(harness); cpuCredits = List.copyOf(cpuCredits);
        collectionIssues = List.copyOf(collectionIssues);
    }

    public ResourceEvidence(List<Sample> sendium, List<Sample> harness, List<Credits> cpuCredits,
            List<String> collectionIssues, int sampleBudget) {
        this(sendium, harness, cpuCredits, collectionIssues, sampleBudget, null);
    }

    public static ResourceEvidence empty() { return new ResourceEvidence(List.of(), List.of(), List.of(), List.of(), DEFAULT_SAMPLE_BUDGET); }

    /** Reporting must retain available sources even when another diagnostic artifact is missing. */
    public static ResourceEvidence loadAvailable(Path sendiumFile, Path harnessFile, Path creditFile, int budget) {
        return loadAvailable(sendiumFile, harnessFile, creditFile, null, budget);
    }

    public static ResourceEvidence loadAvailable(Path sendiumFile, Path harnessFile, Path creditFile, Path databaseFile, int budget) {
        return loadAvailable(sendiumFile, harnessFile, creditFile, databaseFile, budget, BenchmarkTarget.SENDIUM);
    }

    public static ResourceEvidence loadAvailable(Path gatewayFile, Path harnessFile, Path creditFile, Path databaseFile,
            int budget, BenchmarkTarget target) {
        if (budget < 1) { throw new IllegalArgumentException("Invalid resource sample budget"); }
        ObjectMapper mapper = new ObjectMapper();
        List<String> issues = new ArrayList<>();
        List<Sample> sendium = availableSamples(target.id(), gatewayFile, budget, mapper, issues);
        List<Sample> harness = availableSamples("harness", harnessFile, budget, mapper, issues);
        List<Credits> credits = List.of();
        if (creditFile != null) {
            try {
                ResourceEvidence imported = load(null, null, creditFile, budget);
                credits = imported.cpuCredits(); issues.addAll(imported.collectionIssues());
            } catch (Exception exception) { issues.add("CPU_CREDIT_IMPORT_FAILED"); }
        }
        RdsResourceEvidence database = null;
        if (databaseFile != null) {
            try { database = RdsResourceEvidence.load(databaseFile); }
            catch (Exception exception) { issues.add("RDS_RESOURCE_IMPORT_FAILED"); }
        }
        return new ResourceEvidence(sendium, harness, credits, issues, budget, database);
    }

    private static List<Sample> availableSamples(String source, Path file, int budget, ObjectMapper mapper, List<String> issues) {
        try { return readSamples(file, budget, mapper); }
        catch (Exception exception) { issues.add(source + ":RESOURCE_IMPORT_FAILED"); return List.of(); }
    }

    public static ResourceEvidence load(Path sendiumFile, Path harnessFile, Path creditFile, int budget) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Credits> credits = new ArrayList<>(); List<String> issues = new ArrayList<>();
        if (creditFile != null) {
            if (Files.size(creditFile) > 2_097_152) { throw new IllegalArgumentException("Credit evidence exceeds byte budget"); }
            var document = mapper.readTree(creditFile.toFile());
            if (document.path("schemaVersion").asInt() != 1 || !document.path("instances").isArray() || document.path("instances").size() > 2) {
                throw new IllegalArgumentException("Unsupported credit evidence");
            }
            for (var item : document.path("instances")) { credits.add(mapper.treeToValue(item, Credits.class)); }
            for (var issue : document.path("collectionIssues")) { issues.add(issue.asText()); }
        }
        return new ResourceEvidence(readSamples(sendiumFile, budget, mapper), readSamples(harnessFile, budget, mapper), credits, issues, budget);
    }

    private static List<Sample> readSamples(Path file, int budget, ObjectMapper mapper) throws Exception {
        if (file == null) { return List.of(); }
        if (budget < 1 || Files.size(file) > budget * 8192L) { throw new IllegalArgumentException("Resource file exceeds byte budget"); }
        List<Sample> result = new ArrayList<>();
        try (var lines = Files.newBufferedReader(file)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (result.size() >= budget || line.length() > 8192) { throw new IllegalArgumentException("Resource sample budget exceeded"); }
                result.add(mapper.readValue(line, Sample.class));
            }
        }
        return List.copyOf(result);
    }

    public static Summary summarize(List<Sample> samples, Window window) {
        if (window == null || window.nanos() == 0) { return new Summary(0, 0, null, null, null, null, null, 0); }
        List<Sample> ordered = samples.stream().sorted(Comparator.comparing(sample -> instant(sample.timestamp()))).toList();
        long covered = 0, hostCovered = 0, memorySamples = 0;
        double weightedCpu = 0, weightedHost = 0;
        Double maximum = null; Long rss = null, hostMemory = null;
        Instant coveredUntil = window.start();
        for (Sample sample : ordered) {
            Instant end = instant(sample.timestamp());
            if (!end.isBefore(window.start()) && end.isBefore(window.end())) {
                if (sample.rssBytes() != null) { rss = rss == null ? sample.rssBytes() : Math.max(rss, sample.rssBytes()); memorySamples++; }
                if (sample.hostMemoryUsedBytes() != null) { hostMemory = hostMemory == null ? sample.hostMemoryUsedBytes() : Math.max(hostMemory, sample.hostMemoryUsedBytes()); }
            }
            if (sample.cpuPercent() == null || sample.intervalStartTimestamp() == null) { continue; }
            Instant from = instant(sample.intervalStartTimestamp());
            if (from.isBefore(coveredUntil)) { from = coveredUntil; }
            if (end.isAfter(window.end())) { end = window.end(); }
            if (!from.isBefore(end)) { continue; }
            long overlap = Duration.between(from, end).toNanos();
            covered += overlap; weightedCpu += sample.cpuPercent() * overlap;
            maximum = maximum == null ? sample.cpuPercent() : Math.max(maximum, sample.cpuPercent());
            if (sample.hostCpuPercent() != null) { weightedHost += sample.hostCpuPercent() * overlap; hostCovered += overlap; }
            coveredUntil = end;
        }
        return new Summary(covered, hostCovered, covered == 0 ? null : weightedCpu / covered,
                maximum, hostCovered == 0 ? null : weightedHost / hostCovered, rss, hostMemory, memorySamples);
    }

    public ObjectNode json(ObjectMapper mapper, Window window, MeasurementTimeline.Snapshot timeline) {
        return json(mapper, window, timeline, BenchmarkTarget.SENDIUM);
    }

    public ObjectNode json(ObjectMapper mapper, Window window, MeasurementTimeline.Snapshot timeline, BenchmarkTarget target) {
        return jsonForIntervals(mapper, window, timeline == null ? List.of() : timeline.events(), target);
    }

    public ObjectNode jsonForIntervals(ObjectMapper mapper, Window window, List<MeasurementTimeline.EventInterval> events) {
        return jsonForIntervals(mapper, window, events, BenchmarkTarget.SENDIUM);
    }

    public ObjectNode jsonForIntervals(ObjectMapper mapper, Window window, List<MeasurementTimeline.EventInterval> events,
            BenchmarkTarget target) {
        ObjectNode result = mapper.createObjectNode();
        result.set(target.id(), mapper.valueToTree(sendium)); result.set("harness", mapper.valueToTree(harness));
        result.set("cpuCredits", mapper.valueToTree(cpuCredits)); result.set("collectionIssues", mapper.valueToTree(collectionIssues));
        if (database != null) { result.set("database", database.json(mapper)); }
        result.put("sampleBudgetPerSource", sampleBudget).put("processCpuPercentBasis", "100 percent is one logical CPU core")
                .put("hostCpuPercentBasis", "100 percent is aggregate host CPU capacity");
        if (java.util.stream.Stream.concat(sendium.stream(), harness.stream()).anyMatch(sample -> sample.processes() != null)) {
            result.putObject("processGroupSemantics")
                    .put("cpuPercent", "Sum of member process CPU over the same monotonic interval; 100 percent is one logical core.")
                    .put("rssBytes", "Sum of member RSS snapshots; shared pages may be counted more than once, not unique physical memory.")
                    .put("hostMetrics", "One host observation per sample; host CPU and memory are not summed across members.");
        }
        if (window != null) { result.putObject("window").put("start", window.start().toString()).put("end", window.end().toString()); }
        ObjectNode summary = result.putObject("summary");
        ObjectNode overall = summary.putObject("overall");
        overall.set(target.id(), mapper.valueToTree(summarize(sendium, window)));
        overall.set("harness", mapper.valueToTree(summarize(harness, window)));
        var intervals = summary.putArray("intervals");
        if (window != null) {
            for (var event : events) {
                Window interval = new Window(window.start().plusNanos(event.startOffsetNanos()),
                        window.start().plusNanos(event.startOffsetNanos() + event.durationNanos()));
                ObjectNode value = intervals.addObject();
                value.put("phase", event.phase()).put("startOffsetSeconds", event.startOffsetNanos() / 1_000_000_000.0)
                        .put("durationSeconds", event.durationNanos() / 1_000_000_000.0);
                value.set(target.id(), mapper.valueToTree(summarize(sendium, interval)));
                value.set("harness", mapper.valueToTree(summarize(harness, interval)));
            }
        }
        return result;
    }

    static Instant instant(String timestamp) { return OffsetDateTime.parse(timestamp).toInstant(); }
    private static boolean nonnegative(Double value) { return value == null || Double.isFinite(value) && value >= 0; }
    private static boolean nonnegative(Long value) { return value == null || value >= 0; }
}
