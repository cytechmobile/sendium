package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourceEvidenceTest {
    @TempDir Path directory;
    private final Instant origin = Instant.parse("2026-10-15T10:00:00Z");

    private ResourceEvidence.Sample sample(int start, int end, double cpu, double host, long rss) {
        return new ResourceEvidence.Sample(origin.plusSeconds(end).toString(), origin.plusSeconds(start).toString(),
                (double) (end - start), cpu, host, rss, 500L, 1000L, 2, 7L, 42L);
    }

    @Test
    void summaryWeightsCpuByObservedOverlapAndKeepsMemorySnapshotsInsideWindow() {
        var rows = List.of(sample(-5, 0, 0, 0, 1), sample(0, 5, 100, 80, 2), sample(5, 10, 200, 100, 4));
        var summary = ResourceEvidence.summarize(rows, new ResourceEvidence.Window(origin, origin.plusSeconds(10)));
        assertEquals(10_000_000_000L, summary.cpuCoverageNanos());
        assertEquals(10_000_000_000L, summary.hostCpuCoverageNanos());
        assertEquals(150.0, summary.cpuPercentAverage());
        assertEquals(200.0, summary.cpuPercentMaximum());
        assertEquals(90.0, summary.hostCpuPercentAverage());
        assertEquals(2L, summary.rssBytesMaximum());
        assertEquals(2, summary.memorySamples());
        var clipped = ResourceEvidence.summarize(rows, new ResourceEvidence.Window(origin.plusSeconds(4), origin.plusSeconds(6)));
        assertEquals(150.0, clipped.cpuPercentAverage());
        assertEquals(2_000_000_000L, clipped.cpuCoverageNanos());
    }

    @Test
    void unavailableIntervalsStayNullAndOverlappingSamplesDoNotDoubleCountCoverage() {
        var summary = ResourceEvidence.summarize(List.of(sample(0, 5, 100, 80, 2), sample(2, 10, 200, 100, 4)),
                new ResourceEvidence.Window(origin, origin.plusSeconds(10)));
        assertEquals(10_000_000_000L, summary.cpuCoverageNanos());
        var missing = ResourceEvidence.summarize(List.of(), new ResourceEvidence.Window(origin, origin.plusSeconds(10)));
        assertNull(missing.cpuPercentAverage()); assertNull(missing.rssBytesMaximum());
        assertEquals(0, missing.cpuCoverageNanos());
    }

    @Test
    void invalidValuesAndUnboundedInputsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> sample(0, 0, 10, 10, 2));
        assertThrows(IllegalArgumentException.class, () -> sample(0, 1, Double.NaN, 10, 2));
        assertThrows(IllegalArgumentException.class, () -> sample(0, 1, 10, 101, 2));
        assertThrows(IllegalArgumentException.class, () -> sample(0, 1, 10, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence(List.of(sample(0, 1, 10, 10, 2)), List.of(), List.of(), List.of(), 0));
        Path file = directory.resolve("samples.jsonl");
        String row = new ObjectMapper().writeValueAsString(sample(0, 1, 10, 10, 2)) + "\n";
        Files.writeString(file, row + row);
        assertThrows(IllegalArgumentException.class, () -> ResourceEvidence.load(file, null, null, 1));
        Files.writeString(file, "x".repeat(8193));
        assertThrows(IllegalArgumentException.class, () -> ResourceEvidence.load(file, null, null, 1));
    }

    @Test
    void partialImportKeepsAvailableEvidenceAndSanitizesCollectionErrors() throws Exception {
        Path valid = directory.resolve("valid.jsonl");
        Files.writeString(valid, new ObjectMapper().writeValueAsString(sample(0, 1, 50, 25, 2)) + "\n");
        var evidence = ResourceEvidence.loadAvailable(directory.resolve("missing.jsonl"), valid,
                directory.resolve("missing-credits.json"), 10);
        assertTrue(evidence.sendium().isEmpty());
        assertEquals(1, evidence.harness().size());
        assertEquals(List.of("sendium:RESOURCE_IMPORT_FAILED", "CPU_CREDIT_IMPORT_FAILED"), evidence.collectionIssues());
        assertThrows(IllegalArgumentException.class, () -> ResourceEvidence.loadAvailable(null, valid, null, 0));
    }

    @Test
    void jsonlAndAwsCollectorEnvelopeRoundTripThroughBoundedImport() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path samples = directory.resolve("samples.jsonl");
        Files.writeString(samples, mapper.writeValueAsString(sample(0, 1, 50, 25, 2)) + "\n");
        Path credits = directory.resolve("credits.json");
        Files.writeString(credits, """
                {"schemaVersion":1,"instances":[{"source":"sendium","instanceId":"i-11111111",
                 "instanceType":"t3.small","mode":"unlimited","periodSeconds":300,
                 "windowStart":"2026-10-15T10:00:00Z","windowEnd":"2026-10-15T10:10:00Z",
                 "samples":[{"timestamp":"2026-10-15T10:00:00Z","balanceAverage":12,
                 "usageSum":null,"surplusBalanceAverage":null,"surplusChargedSum":null}],"collectionIssues":[]}],
                 "collectionIssues":[]}
                """);
        var evidence = ResourceEvidence.load(samples, samples, credits, 10);
        assertEquals(1, evidence.sendium().size());
        assertEquals(50.0, evidence.sendium().getFirst().cpuPercent());
        assertEquals("unlimited", evidence.cpuCredits().getFirst().mode());
        assertNull(evidence.cpuCredits().getFirst().samples().getFirst().usageSum());
    }
}
