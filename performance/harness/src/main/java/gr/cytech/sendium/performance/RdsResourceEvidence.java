package gr.cytech.sendium.performance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standard RDS metrics imported offline; whole CloudWatch buckets, never SQL timings. */
public record RdsResourceEvidence(int schemaVersion, String namespace, String runIdentity, String collectionStartedUtc,
        Database database, Map<String, Phase> phases, List<Metric> metrics, List<String> collectionIssues) {
    private static final int MAX_POINTS = 1440;
    private static final List<String> PHASES = List.of("warmup", "measurement", "drain");
    private static final List<String> GAUGE = List.of("Average", "Minimum", "Maximum", "SampleCount");
    private static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("CPUUtilization", "Percent"), Map.entry("FreeableMemory", "Bytes"), Map.entry("SwapUsage", "Bytes"),
            Map.entry("DatabaseConnections", "Count"), Map.entry("ReadIOPS", "Count/Second"), Map.entry("WriteIOPS", "Count/Second"),
            Map.entry("ReadThroughput", "Bytes/Second"), Map.entry("WriteThroughput", "Bytes/Second"),
            Map.entry("ReadLatency", "Seconds"), Map.entry("WriteLatency", "Seconds"), Map.entry("DiskQueueDepth", "Count"),
            Map.entry("FreeStorageSpace", "Bytes"), Map.entry("CPUCreditBalance", "Count"), Map.entry("CPUCreditUsage", "Count"),
            Map.entry("CPUSurplusCreditBalance", "Count"), Map.entry("CPUSurplusCreditsCharged", "Count"));

    public record Database(String identifier, String instanceClass, String partition, String region, String accountId,
            String repository, String runIdentity, Boolean verified, String arn) {
        public Database {
            match(identifier, "[a-z][a-z0-9]*(?:-[a-z0-9]+)*"); match(instanceClass, "db\\.[a-z][a-z0-9]*\\.[a-z0-9]+");
            match(partition, "aws(?:-cn|-us-gov)?"); match(region, "[a-z0-9-]{3,32}"); match(accountId, "[0-9]{12}");
            match(repository, "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"); match(runIdentity, "gh-[0-9]+-[0-9]+");
            check(verified != null && identifier.length() <= 63 && identifier.endsWith("-run-" + runIdentity));
            check(!verified || ("arn:" + partition + ":rds:" + region + ":" + accountId + ":db:" + identifier).equals(arn));
        }
    }
    public record Phase(String start, String end) {
        public Phase { check(!instant(end).isBefore(instant(start))); }
        ResourceEvidence.Window window() { return new ResourceEvidence.Window(instant(start), instant(end)); }
    }
    public record Point(String timestamp, Double average, Double minimum, Double maximum, Double sampleCount, Double sum) {
        public Point {
            instant(timestamp);
            for (Double value : new Double[]{average, minimum, maximum, sampleCount, sum}) {
                check(value == null || Double.isFinite(value) && value >= 0 && value <= 1e100);
            }
            check(sampleCount == null || sampleCount > 0);
            check(minimum == null || average == null || minimum <= average);
            check(maximum == null || average == null || average <= maximum);
            check(minimum == null || maximum == null || minimum <= maximum);
        }
        boolean complete(boolean sums) { return sums ? sum != null : average != null && minimum != null && maximum != null && sampleCount != null; }
    }
    public record Attempt(int attempt, List<String> issues) {
        public Attempt { check(attempt >= 1 && attempt <= 3); issues = checkedIssues(issues); }
    }
    /** Collector coverage is recomputed from samples instead of trusting supplied counts. */
    @JsonIgnoreProperties("coverage")
    public record Metric(String name, String unit, int periodSeconds, List<String> statistics, Boolean applicable,
            String queryStart, String queryEnd, List<Point> datapoints, int attempts,
            List<Attempt> attemptIssues, List<String> collectionIssues) {
        public Metric {
            check(UNITS.containsKey(name) && UNITS.get(name).equals(unit));
            check(periodSeconds == (name.contains("Credit") ? 300 : 60));
            check((sums(name) ? List.of("Sum") : GAUGE).equals(statistics));
            var start = instant(queryStart); var end = instant(queryEnd);
            check(aligned(start, periodSeconds) && aligned(end, periodSeconds) && start.isBefore(end)
                    && Duration.between(start, end).getSeconds() <= MAX_POINTS * (long) periodSeconds);
            check(datapoints != null && datapoints.size() <= MAX_POINTS && attempts >= 0 && attempts <= 3
                    && attemptIssues != null && attemptIssues.size() <= 3);
            Instant previous = null;
            for (Point point : datapoints) {
                check(sums(name) ? point.average() == null && point.minimum() == null && point.maximum() == null && point.sampleCount() == null : point.sum() == null);
                var timestamp = instant(point.timestamp());
                check(!timestamp.isBefore(start) && timestamp.isBefore(end) && aligned(timestamp, periodSeconds)
                        && (previous == null || timestamp.isAfter(previous)));
                if (unit.equals("Percent")) {
                    check((point.average() == null || point.average() <= 100) && (point.minimum() == null || point.minimum() <= 100)
                            && (point.maximum() == null || point.maximum() <= 100));
                }
                previous = timestamp;
            }
            check(datapoints.isEmpty() || attempts > 0 && Boolean.TRUE.equals(applicable));
            datapoints = List.copyOf(datapoints); statistics = List.copyOf(statistics); attemptIssues = List.copyOf(attemptIssues);
            collectionIssues = checkedIssues(collectionIssues);
        }
    }

    public RdsResourceEvidence {
        check(schemaVersion == 1 && "AWS/RDS".equals(namespace) && database != null && database.runIdentity().equals(runIdentity));
        instant(collectionStartedUtc);
        check(phases != null && phases.keySet().equals(Set.copyOf(PHASES)) && metrics != null && metrics.size() <= UNITS.size());
        check(phases.get("warmup").end().equals(phases.get("measurement").start())
                && phases.get("measurement").end().equals(phases.get("drain").start()));
        check(phases.get("measurement").window().nanos() > 0);
        var start = instant(phases.get("warmup").start()); var end = instant(phases.get("drain").end());
        check(Duration.between(start, end).toNanos() <= (MAX_POINTS - 1) * 60L * 1_000_000_000);
        Set<String> names = new HashSet<>();
        String family = database.instanceClass().split("\\.")[1];
        for (Metric metric : metrics) {
            check(names.add(metric.name()));
            var expected = applicability(metric.name(), family);
            check(java.util.Objects.equals(metric.applicable(), expected));
            var first = bucketStart(start, metric.periodSeconds());
            var last = bucketStart(end, metric.periodSeconds());
            if (end.isAfter(last)) { last = last.plusSeconds(metric.periodSeconds()); }
            check(instant(metric.queryStart()).equals(first) && instant(metric.queryEnd()).equals(last));
        }
        check(database.verified() ? names.equals(UNITS.keySet()) : metrics.isEmpty());
        phases = Map.copyOf(phases); metrics = List.copyOf(metrics); collectionIssues = checkedIssues(collectionIssues);
    }

    public static RdsResourceEvidence load(Path file) throws Exception {
        check(Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= 8_388_608);
        return JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build().readValue(file.toFile(), RdsResourceEvidence.class);
    }

    public List<RunValidity.Reason> reasons(ResultReport.Context context, ResourceEvidence.Window window) {
        List<RunValidity.Reason> reasons = new ArrayList<>();
        var expected = context.infrastructure().path("database");
        boolean mismatch = !runIdentity.equals(context.provenance().runIdentity())
                || !database.identifier().equals(expected.path("identifier").asText())
                || !database.region().equals(context.infrastructure().path("region").asText())
                || !database.instanceClass().equals(expected.path("resolved").path("DBInstanceClass").asText(expected.path("instanceType").asText()))
                || expected.has("accountId") && !database.accountId().equals(expected.path("accountId").asText())
                || collectionIssues.contains("RDS_IDENTITY_MISMATCH");
        if (mismatch) { reasons.add(reason("RDS_IDENTITY_MISMATCH", "RDS evidence does not match the run database identity.", RunValidity.Severity.FAILED)); }
        var measurement = phases.get("measurement").window();
        if (window != null && (!coversWithinPrecision(measurement.start(), window.start(), true)
                || !coversWithinPrecision(measurement.end(), window.end(), false))) {
            reasons.add(reason("RDS_WINDOW_MISMATCH", "RDS evidence does not match the measurement window.", RunValidity.Severity.FAILED));
        }
        if (!database.verified() || !collectionIssues.isEmpty() || metrics.stream().anyMatch(metric ->
                !metric.collectionIssues().isEmpty() || metric.applicable() == null
                        || Boolean.TRUE.equals(metric.applicable()) && !missing(metric, fullWindow()).isEmpty())) {
            reasons.add(reason("RDS_RESOURCE_INCOMPLETE", "Required RDS resource evidence is missing or incomplete; absent metrics are not zero.", RunValidity.Severity.INCONCLUSIVE));
        }
        return reasons;
    }

    public ObjectNode json(ObjectMapper mapper) {
        ObjectNode result = mapper.valueToTree(this);
        var output = result.putArray("metrics");
        for (Metric metric : metrics) {
            ObjectNode item = mapper.valueToTree(metric);
            ObjectNode coverage = coverage(mapper, metric, fullWindow());
            var phaseCoverage = coverage.putObject("phases");
            var summaries = item.putObject("summary");
            for (String phase : PHASES) {
                var window = phases.get(phase).window();
                phaseCoverage.set(phase, coverage(mapper, metric, window));
                summaries.set(phase, summarize(mapper, metric, window));
            }
            item.set("coverage", coverage);
            var points = item.putArray("datapoints");
            for (Point point : metric.datapoints()) {
                ObjectNode sample = mapper.valueToTree(point);
                var from = instant(point.timestamp()); var to = from.plusSeconds(metric.periodSeconds());
                sample.put("bucketEnd", to.toString());
                var overlapping = sample.putArray("phases");
                boolean contained = false;
                for (String phase : PHASES) {
                    var window = phases.get(phase).window();
                    if (overlaps(from, to, window)) { overlapping.add(phase); }
                    contained |= contained(from, to, window);
                }
                sample.put("crossesPhaseBoundary", !contained);
                points.add(sample);
            }
            output.add(item);
        }
        return result;
    }

    private ResourceEvidence.Window fullWindow() { return new ResourceEvidence.Window(instant(phases.get("warmup").start()), instant(phases.get("drain").end())); }

    private static ObjectNode summarize(ObjectMapper mapper, Metric metric, ResourceEvidence.Window window) {
        ObjectNode summary = coverage(mapper, metric, window);
        double total = 0, weights = 0, sum = 0;
        Double minimum = null, maximum = null;
        int contained = 0, boundary = 0;
        boolean sums = sums(metric.name());
        if (Boolean.TRUE.equals(metric.applicable())) {
            for (Instant from = bucketStart(window.start(), metric.periodSeconds()); from.isBefore(window.end()) && window.nanos() > 0; from = from.plusSeconds(metric.periodSeconds())) {
                if (!contained(from, from.plusSeconds(metric.periodSeconds()), window)) { boundary++; }
            }
            for (Point point : metric.datapoints()) {
                var from = instant(point.timestamp());
                if (!point.complete(sums) || !contained(from, from.plusSeconds(metric.periodSeconds()), window)) { continue; }
                contained++;
                if (sums) { sum += point.sum(); }
                else {
                    total += point.average() * point.sampleCount(); weights += point.sampleCount();
                    minimum = minimum == null ? point.minimum() : Math.min(minimum, point.minimum());
                    maximum = maximum == null ? point.maximum() : Math.max(maximum, point.maximum());
                }
            }
        }
        summary.put("containedBuckets", contained).put("boundaryBuckets", boundary)
                .put("statisticsBasis", "complete buckets fully contained in phase; averages weighted by CloudWatch SampleCount");
        nullable(summary, "average", weights == 0 ? null : total / weights);
        nullable(summary, "minimum", minimum); nullable(summary, "maximum", maximum);
        nullable(summary, "sum", sums && contained > 0 ? sum : null);
        return summary;
    }

    private static ObjectNode coverage(ObjectMapper mapper, Metric metric, ResourceEvidence.Window window) {
        var missing = missing(metric, window);
        int expected = 0;
        if (Boolean.TRUE.equals(metric.applicable()) && window.nanos() > 0) {
            for (Instant from = bucketStart(window.start(), metric.periodSeconds()); from.isBefore(window.end()); from = from.plusSeconds(metric.periodSeconds())) { expected++; }
        }
        ObjectNode result = mapper.createObjectNode().put("expectedBuckets", expected).put("completeBuckets", expected - missing.size());
        result.set("missingBucketStarts", mapper.valueToTree(missing));
        return result;
    }

    private static List<String> missing(Metric metric, ResourceEvidence.Window window) {
        List<String> missing = new ArrayList<>();
        if (!Boolean.TRUE.equals(metric.applicable()) || window.nanos() == 0) { return missing; }
        Set<Instant> present = new HashSet<>();
        for (Point point : metric.datapoints()) { if (point.complete(sums(metric.name()))) { present.add(instant(point.timestamp())); } }
        for (Instant from = bucketStart(window.start(), metric.periodSeconds()); from.isBefore(window.end()); from = from.plusSeconds(metric.periodSeconds())) {
            if (!present.contains(from)) { missing.add(from.toString()); }
        }
        return missing;
    }

    private static Boolean applicability(String name, String family) {
        if (!name.contains("Credit")) { return true; }
        if (family.startsWith("t") && !List.of("t2", "t3", "t4g").contains(family)) { return null; }
        return name.startsWith("CPUSurplus") ? List.of("t3", "t4g").contains(family) : List.of("t2", "t3", "t4g").contains(family);
    }
    private static boolean sums(String name) { return List.of("CPUCreditUsage", "CPUSurplusCreditsCharged").contains(name); }
    private static boolean overlaps(Instant from, Instant to, ResourceEvidence.Window window) { return window.nanos() > 0 && from.isBefore(window.end()) && to.isAfter(window.start()); }
    private static boolean contained(Instant from, Instant to, ResourceEvidence.Window window) { return !from.isBefore(window.start()) && !to.isAfter(window.end()); }
    private static Instant bucketStart(Instant value, int period) { return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(), period) * period); }
    private static boolean aligned(Instant value, int period) { return value.getNano() == 0 && value.getEpochSecond() % period == 0; }
    private static Instant instant(String value) { check(value != null && value.length() <= 64); return ResourceEvidence.instant(value); }
    private static boolean coversWithinPrecision(Instant envelope, Instant exact, boolean start) {
        return start ? !envelope.isAfter(exact) && !envelope.isBefore(exact.minusNanos(2000))
                : !envelope.isBefore(exact) && !envelope.isAfter(exact.plusNanos(2000));
    }
    private static List<String> checkedIssues(List<String> issues) {
        check(issues != null && issues.size() <= 32);
        for (String issue : issues) { match(issue, "[A-Z][A-Z0-9_]{0,127}"); }
        return List.copyOf(issues);
    }
    private static void nullable(ObjectNode node, String field, Double value) { if (value == null) { node.putNull(field); } else { node.put(field, value); } }
    private static RunValidity.Reason reason(String code, String message, RunValidity.Severity severity) { return new RunValidity.Reason(code, message, severity, "resources"); }
    private static void match(String value, String pattern) { check(value != null && value.matches(pattern)); }
    private static void check(boolean condition) { if (!condition) { throw new IllegalArgumentException("Invalid RDS resource evidence"); } }
}
