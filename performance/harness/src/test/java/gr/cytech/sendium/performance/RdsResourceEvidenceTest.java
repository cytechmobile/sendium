package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RdsResourceEvidenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> NAMES = List.of("CPUUtilization", "FreeableMemory", "SwapUsage", "DatabaseConnections",
            "ReadIOPS", "WriteIOPS", "ReadThroughput", "WriteThroughput", "ReadLatency", "WriteLatency", "DiskQueueDepth",
            "FreeStorageSpace", "CPUCreditBalance", "CPUCreditUsage", "CPUSurplusCreditBalance", "CPUSurplusCreditsCharged");
    @TempDir Path directory;

    private ObjectNode fixture(String warmup, String measurement, String drain, String end) {
        ObjectNode result = MAPPER.createObjectNode().put("schemaVersion", 1).put("namespace", "AWS/RDS")
                .put("runIdentity", "gh-12345-1").put("collectionStartedUtc", end);
        result.putObject("database").put("identifier", "sendium-perf-run-gh-12345-1").put("instanceClass", "db.t4g.small")
                .put("partition", "aws").put("region", "eu-west-1").put("accountId", "123456789012")
                .put("repository", "cytechmobile/sendium").put("runIdentity", "gh-12345-1").put("verified", true)
                .put("arn", "arn:aws:rds:eu-west-1:123456789012:db:sendium-perf-run-gh-12345-1");
        var phases = result.putObject("phases");
        phases.putObject("warmup").put("start", warmup).put("end", measurement);
        phases.putObject("measurement").put("start", measurement).put("end", drain);
        phases.putObject("drain").put("start", drain).put("end", end);
        result.putArray("collectionIssues");
        var metrics = result.putArray("metrics");
        for (String name : NAMES) {
            String unit = name.contains("Latency") ? "Seconds" : name.contains("Throughput") ? "Bytes/Second"
                    : name.endsWith("IOPS") ? "Count/Second" : List.of("FreeableMemory", "SwapUsage", "FreeStorageSpace").contains(name) ? "Bytes"
                    : name.equals("CPUUtilization") ? "Percent" : "Count";
            int period = name.contains("Credit") ? 300 : 60;
            boolean sums = List.of("CPUCreditUsage", "CPUSurplusCreditsCharged").contains(name);
            var from = Instant.ofEpochSecond(Math.floorDiv(Instant.parse(warmup).getEpochSecond(), period) * period);
            var to = Instant.ofEpochSecond(Math.floorDiv(Instant.parse(end).getEpochSecond(), period) * period);
            if (to.isBefore(Instant.parse(end))) { to = to.plusSeconds(period); }
            var metric = metrics.addObject().put("name", name).put("unit", unit).put("periodSeconds", period).put("applicable", true)
                    .put("queryStart", from.toString()).put("queryEnd", to.toString()).put("attempts", 1);
            var statistics = metric.putArray("statistics");
            if (sums) { statistics.add("Sum"); }
            else { List.of("Average", "Minimum", "Maximum", "SampleCount").forEach(statistics::add); }
            metric.putArray("attemptIssues"); metric.putArray("collectionIssues");
            var points = metric.putArray("datapoints");
            for (Instant timestamp = from; timestamp.isBefore(to); timestamp = timestamp.plusSeconds(period)) {
                var point = points.addObject().put("timestamp", timestamp.toString());
                if (sums) { point.put("sum", 3); }
                else {
                    double value = unit.equals("Seconds") ? 0.002 : unit.startsWith("Bytes") ? 1_048_576 : 20;
                    point.put("average", value).put("minimum", value / 2).put("maximum", value * 1.5).put("sampleCount", 2);
                }
            }
            metric.putObject("coverage").put("completeBuckets", 999); // Recomputed, never trusted.
        }
        return result;
    }

    private ObjectNode fixture() { return fixture("2026-10-23T12:00:00Z", "2026-10-23T12:01:00Z", "2026-10-23T12:04:00Z", "2026-10-23T12:05:00Z"); }
    private RdsResourceEvidence parse(ObjectNode input) throws Exception { return MAPPER.treeToValue(input, RdsResourceEvidence.class); }
    private ObjectNode metric(ObjectNode input, int index) { return (ObjectNode) input.path("metrics").get(index); }

    private ResultReport.Context context() {
        var app = MAPPER.createObjectNode(); app.putArray("jvmArguments"); app.putObject("connections"); app.put("dlrStorage", "postgresql");
        var infrastructure = MAPPER.createObjectNode().put("preset", "rds-synthetic").put("region", "eu-west-1");
        infrastructure.putObject("database").put("identifier", "sendium-perf-run-gh-12345-1").put("instanceType", "db.t4g.small")
                .put("resourceMetricsRequired", true).put("accountId", "123456789012");
        for (String source : List.of("sendium", "harness")) {
            infrastructure.putObject(source).put("instanceId", "i-" + source).put("instanceType", "c7g.large");
        }
        return new ResultReport.Context(new ResultReport.Provenance("rds-synthetic", "a".repeat(40), "b".repeat(40), null, "gh-12345-1", List.of()),
                app, infrastructure, List.of());
    }

    private ResourceEvidence evidence(RdsResourceEvidence database, ResourceEvidence.Window window) {
        var baseline = new ResourceEvidence.Sample(window.start().toString(), null, null, null, null, 100L, 200L, 1000L, 2, null, null);
        var sample = new ResourceEvidence.Sample(window.end().toString(), window.start().toString(), window.nanos() / 1e9,
                50.0, 25.0, 100L, 200L, 1000L, 2, null, null);
        var credits = List.of("sendium", "harness").stream().map(source -> new ResourceEvidence.Credits(source, "i-" + source, "c7g.large", false,
                "not-applicable", 300, window.start().toString(), window.end().toString(), List.of(), List.of())).toList();
        return new ResourceEvidence(List.of(baseline, sample), List.of(baseline, sample), credits, List.of(), 10, database);
    }

    private ResultReport.Report initial(RdsResourceEvidence source, ResultReport.Context context, ResourceEvidence.Window window) {
        int duration = (int) (window.nanos() / 1_000_000_000);
        int warmup = source == null ? 60 : (int) Duration.between(Instant.parse(source.phases().get("warmup").start()), Instant.parse(source.phases().get("warmup").end())).toSeconds();
        int drain = source == null ? 60 : (int) Duration.between(Instant.parse(source.phases().get("drain").start()), Instant.parse(source.phases().get("drain").end())).toSeconds();
        var config = new RunConfig(1, "http", 1, 0, warmup, duration, drain, true, 5, 1000, 10000, 100, 1000);
        AtomicLong clock = new AtomicLong();
        var ledger = new MessageLedger(config, "rds-synthetic", clock::get, 0);
        ledger.beginMeasurement(0, window.nanos());
        for (int second = 0; second < duration; second++) {
            clock.set(second * 1_000_000_000L);
            ledger.admission(ledger.attempt(true), MessageLedger.Admission.REJECTED);
        }
        clock.set(window.nanos()); ledger.endMeasurement(); clock.addAndGet(drain * 1_000_000_000L); ledger.finishDrain(); ledger.freeze();
        var report = new ResultReport().build(config, ledger, context, List.of(), false, evidence(null, window), window, List.of());
        report.json().withObject("/measurementMetadata").put("resourceFinalizationVersion", 1)
                .put("measurementStartUtc", window.start().toString()).put("observedDurationNanos", window.nanos());
        return report;
    }

    private ResultReport.Report finalizeAndSave(String label, RdsResourceEvidence source) throws Exception {
        var window = source.phases().get("measurement").window();
        var writer = new ResultReport();
        var initial = initial(source, context(), window);
        var finalReport = writer.finalizeResources(initial.json(), evidence(source, window));
        for (String field : List.of("provenance", "configuration", "overall", "cohortIntervals", "eventIntervals", "drain")) {
            assertEquals(initial.json().path(field), finalReport.json().path(field), field);
        }
        writer.write(Path.of("target/report-fixtures", "rds-" + label + "-" + UUID.randomUUID()), finalReport);
        return finalReport;
    }

    @Test
    void weightedAveragesUseSampleCountsAndExtremaUseTheReturnedCloudWatchStatistics() throws Exception {
        ObjectNode input = fixture();
        var points = metric(input, 0).withArray("datapoints");
        ((ObjectNode) points.get(1)).put("average", 10).put("minimum", 8).put("maximum", 12).put("sampleCount", 1);
        ((ObjectNode) points.get(2)).put("average", 30).put("minimum", 28).put("maximum", 32).put("sampleCount", 3);
        ((ObjectNode) points.get(3)).put("average", 20).put("minimum", 18).put("maximum", 22).put("sampleCount", 2);
        var report = finalizeAndSave("weighted", parse(input));
        var summary = report.json().at("/resources/database/metrics/0/summary/measurement");
        assertEquals("valid", report.json().at("/validity/status").asText());
        assertEquals(140.0 / 6, summary.path("average").asDouble(), 1e-12);
        assertEquals(8, summary.path("minimum").asDouble()); assertEquals(32, summary.path("maximum").asDouble());
        assertEquals(3, summary.path("containedBuckets").asInt()); assertEquals(0, summary.path("boundaryBuckets").asInt());
        assertEquals(3, summary.path("completeBuckets").asInt());
        assertTrue(report.markdown().contains("CPUUtilization|%|60|23.333|8.000|32.000|n/a|3/3|3|0|"));
        assertTrue(report.markdown().contains("ReadLatency|ms|60|2.000|1.000|3.000"));
        assertTrue(report.markdown().contains("FreeableMemory|MiB|60|1.000|0.500|1.500"));
    }

    @Test
    void boundaryBucketsRemainVisibleButDoNotContaminatePhaseOnlySummaries() throws Exception {
        var input = fixture("2026-10-23T12:00:10Z", "2026-10-23T12:01:10Z", "2026-10-23T12:03:20Z", "2026-10-23T12:03:50Z");
        for (int index : List.of(1, 3)) { ((ObjectNode) metric(input, 0).path("datapoints").get(index)).put("average", 90).put("minimum", 80).put("maximum", 100); }
        var report = finalizeAndSave("boundary", parse(input));
        var summary = report.json().at("/resources/database/metrics/0/summary/measurement");
        assertEquals(20, summary.path("average").asDouble()); assertEquals(3, summary.path("completeBuckets").asInt());
        assertEquals(1, summary.path("containedBuckets").asInt()); assertEquals(2, summary.path("boundaryBuckets").asInt());
        assertTrue(report.json().at("/resources/database/metrics/13/summary/measurement/sum").isNull());
        assertTrue(report.json().at("/resources/database/metrics/0/datapoints/1/crossesPhaseBoundary").asBoolean());
        assertFalse(report.json().at("/resources/database/metrics/0/datapoints/2/crossesPhaseBoundary").asBoolean());
        assertEquals(List.of("warmup", "measurement"), MAPPER.convertValue(report.json().at("/resources/database/metrics/0/datapoints/1/phases"), List.class));
        assertTrue(report.markdown().contains("Boundary buckets are excluded"));
    }

    @Test
    void creditUsageIsSummedOnlyAcrossFullyContainedFiveMinuteBuckets() throws Exception {
        var source = parse(fixture("2026-10-23T12:00:00Z", "2026-10-23T12:05:00Z", "2026-10-23T12:15:00Z", "2026-10-23T12:20:00Z"));
        var report = finalizeAndSave("credit-sums", source);
        var summary = report.json().at("/resources/database/metrics/13/summary/measurement");
        assertEquals(6, summary.path("sum").asDouble()); assertTrue(summary.path("average").isNull());
        assertEquals(2, summary.path("containedBuckets").asInt());
    }

    @Test
    void missingAndPartialBucketsAreInconclusiveAndNeverBecomeZeros() throws Exception {
        var input = fixture();
        metric(input, 0).withArray("datapoints").remove(2);
        ((ObjectNode) metric(input, 8).path("datapoints").get(2)).remove("average");
        var report = finalizeAndSave("partial", parse(input));
        assertEquals("inconclusive", report.json().at("/validity/status").asText());
        assertTrue(report.json().at("/validity/reasons").toString().contains("RDS_RESOURCE_INCOMPLETE"));
        var summary = report.json().at("/resources/database/metrics/0/summary/measurement");
        assertEquals(2, summary.path("completeBuckets").asInt()); assertEquals(3, summary.path("expectedBuckets").asInt());
        assertEquals("2026-10-23T12:02:00Z", summary.path("missingBucketStarts").get(0).asText());
        assertTrue(report.json().at("/resources/database/metrics/8/datapoints/2/average").isNull());
    }

    @Test
    void wrongDatabaseOrMeasurementWindowFailsInsteadOfAttributingOtherEvidence() throws Exception {
        var input = fixture();
        input.withObject("/database").put("identifier", "other-run-gh-12345-1").put("arn", "arn:aws:rds:eu-west-1:123456789012:db:other-run-gh-12345-1");
        var report = finalizeAndSave("wrong-identity", parse(input));
        assertEquals("failed", report.json().at("/validity/status").asText());
        assertTrue(report.json().at("/validity/reasons").toString().contains("RDS_IDENTITY_MISMATCH"));
        var source = parse(fixture());
        var expected = source.phases().get("measurement").window();
        var shifted = new ResourceEvidence.Window(expected.start().plusSeconds(1), expected.end().plusSeconds(1));
        var mismatched = new ResultReport().finalizeResources(initial(null, context(), shifted).json(), evidence(source, shifted));
        assertEquals("failed", mismatched.json().at("/validity/status").asText());
        assertTrue(mismatched.json().at("/validity/reasons").toString().contains("RDS_WINDOW_MISMATCH"));
    }

    @Test
    void unverifiedDatabaseHasNoUtilizationAndClassifiesMissingEvidence() throws Exception {
        var input = fixture();
        input.withObject("/database").put("verified", false).remove("arn");
        input.putArray("metrics"); input.withArray("collectionIssues").add("RDS_IDENTITY_UNAVAILABLE");
        var report = finalizeAndSave("unavailable", parse(input));
        assertEquals("inconclusive", report.json().at("/validity/status").asText());
        assertEquals(0, report.json().at("/resources/database/metrics").size());
    }

    @Test
    void missingSourceIsRequiredOnlyWhenTheContextOptsIntoRdsEvidence() throws Exception {
        var source = parse(fixture()); var window = source.phases().get("measurement").window();
        var report = new ResultReport().finalizeResources(initial(null, context(), window).json(), evidence(null, window));
        assertTrue(report.json().at("/validity/reasons").toString().contains("RDS_RESOURCES_MISSING"));
        assertTrue(report.markdown().contains("Utilization is **n/a**, not zero"));
        var optional = context(); optional.infrastructure().withObject("/database").put("resourceMetricsRequired", false);
        var legacy = new ResultReport().finalizeResources(initial(null, optional, window).json(), evidence(null, window));
        assertEquals("valid", legacy.json().at("/validity/status").asText());
        assertFalse(legacy.markdown().contains("## Database resources"));
    }

    @Test
    void corruptSourceCannotDiscardAvailableProcessEvidence() throws Exception {
        Path invalid = directory.resolve("invalid-rds.json"); Files.writeString(invalid, "{\"password\":\"do-not-publish\"}");
        var good = evidence(null, parse(fixture()).phases().get("measurement").window());
        Path samples = directory.resolve("retained-samples.jsonl");
        StringBuilder lines = new StringBuilder();
        for (var sample : good.sendium()) { lines.append(MAPPER.writeValueAsString(sample)).append('\n'); }
        Files.writeString(samples, lines);
        var evidence = ResourceEvidence.loadAvailable(samples, samples, null, invalid, 10);
        assertNull(evidence.database()); assertEquals(List.of("RDS_RESOURCE_IMPORT_FAILED"), evidence.collectionIssues());
        assertEquals(2, evidence.sendium().size()); assertEquals(2, evidence.harness().size());
        assertFalse(evidence.jsonForIntervals(MAPPER, null, List.of()).toString().contains("do-not-publish"));
    }

    @Test
    void fileImportRejectsScalarCoercionAndTrailingDocuments() throws Exception {
        Path file = directory.resolve("coerced-rds.json");
        var input = fixture();
        ((ObjectNode) metric(input, 0).path("datapoints").get(0)).put("average", "20");
        MAPPER.writeValue(file.toFile(), input);
        assertThrows(Exception.class, () -> RdsResourceEvidence.load(file));
        input = fixture(); metric(input, 0).put("periodSeconds", 60.5);
        MAPPER.writeValue(file.toFile(), input);
        assertThrows(Exception.class, () -> RdsResourceEvidence.load(file));
        Files.writeString(file, fixture().toString() + " {}");
        assertThrows(Exception.class, () -> RdsResourceEvidence.load(file));
    }

    @Test
    void highUtilizationAndObservedZerosAreMeasurementsNotPerformanceFailures() throws Exception {
        var input = fixture();
        for (var point : metric(input, 0).path("datapoints")) {
            ((ObjectNode) point).put("average", 100).put("minimum", 100).put("maximum", 100);
        }
        for (var point : metric(input, 2).path("datapoints")) {
            ((ObjectNode) point).put("average", 0).put("minimum", 0).put("maximum", 0);
        }
        var report = finalizeAndSave("high-and-zero", parse(input));
        assertEquals("valid", report.json().at("/validity/status").asText());
        assertEquals(100, report.json().at("/resources/database/metrics/0/summary/measurement/maximum").asDouble());
        assertEquals(0, report.json().at("/resources/database/metrics/2/summary/measurement/maximum").asDouble());
    }

    @Test
    void nonBurstableCreditsAreNotMissingAndUnknownFamiliesStayInconclusive() throws Exception {
        var input = fixture();
        input.withObject("/database").put("instanceClass", "db.m7g.large");
        for (int index = 12; index < 16; index++) {
            metric(input, index).put("applicable", false).put("attempts", 0).putArray("datapoints");
        }
        var source = parse(input); var context = context();
        context.infrastructure().withObject("/database").put("instanceType", "db.m7g.large");
        assertEquals(List.of(), source.reasons(context, source.phases().get("measurement").window()));
        assertEquals(0, source.json(MAPPER).at("/metrics/12/coverage/expectedBuckets").asInt());
        input.withObject("/database").put("instanceClass", "db.t9.small");
        context.infrastructure().withObject("/database").put("instanceType", "db.t9.small");
        for (int index = 12; index < 16; index++) { metric(input, index).putNull("applicable"); }
        source = parse(input);
        assertTrue(source.reasons(context, source.phases().get("measurement").window()).stream().anyMatch(reason -> reason.code().equals("RDS_RESOURCE_INCOMPLETE")));
    }

    @Test
    void inputRejectsWrongUnitsDuplicateBucketsAndNonfiniteValues() {
        for (int mutation = 0; mutation < 4; mutation++) {
            var input = fixture();
            switch (mutation) {
                case 0 -> metric(input, 0).put("unit", "Bytes");
                case 1 -> metric(input, 0).withArray("datapoints").add(metric(input, 0).path("datapoints").get(0).deepCopy());
                case 2 -> ((ObjectNode) metric(input, 0).path("datapoints").get(0)).put("average", Double.NaN);
                default -> ((ObjectNode) metric(input, 0).path("datapoints").get(0)).put("sampleCount", 0);
            }
            assertThrows(Exception.class, () -> parse(input));
        }
    }

    @Test
    void offlineCliImportsTheRdsFileThroughResourceInputsAndPreservesWorkload() throws Exception {
        var input = fixture(); var source = parse(input); var window = source.phases().get("measurement").window();
        var initial = initial(null, context(), window); Path original = directory.resolve("original.json"); MAPPER.writeValue(original.toFile(), initial.json());
        Path rds = directory.resolve("rds.json"); MAPPER.writeValue(rds.toFile(), input);
        var complete = evidence(source, window);
        Path samples = directory.resolve("samples.jsonl");
        StringBuilder lines = new StringBuilder(); for (var point : complete.sendium()) { lines.append(MAPPER.writeValueAsString(point)).append('\n'); }
        Files.writeString(samples, lines);
        Path credits = directory.resolve("credits.json");
        var creditsJson = MAPPER.createObjectNode().put("schemaVersion", 1); creditsJson.set("instances", MAPPER.valueToTree(complete.cpuCredits())); creditsJson.putArray("collectionIssues");
        MAPPER.writeValue(credits.toFile(), creditsJson);
        Path resources = directory.resolve("resource-inputs.json");
        MAPPER.writeValue(resources.toFile(), new ExecutionSettings.ResourceFiles(1, "samples.jsonl", "samples.jsonl", "credits.json", 10, "rds.json"));
        Path output = directory.resolve("finalized");
        assertEquals(0, HarnessMain.run(new String[]{"finalize-report", original.toString(), resources.toString(), output.toString()}));
        var result = MAPPER.readTree(output.resolve("results.json").toFile());
        assertEquals(MAPPER.readTree(original.toFile()).path("overall"), result.path("overall")); assertEquals(16, result.at("/resources/database/metrics").size());
        assertTrue(Files.readString(output.resolve("summary.md")).contains("## Database resources (RDS)"));
    }
}
