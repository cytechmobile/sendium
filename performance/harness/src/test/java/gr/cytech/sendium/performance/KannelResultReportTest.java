package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KannelResultReportTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void offlineFinalizationRetainsKannelLabelsSourceAndWorkloadWithoutInventingAnApplicationCommit() throws Exception {
        RunConfig config = new RunConfig(1, "http", 1, 0, 0, 1, 0, true, 5, 1000, 1000, 10, 10);
        AtomicLong time = new AtomicLong();
        MessageLedger ledger = new MessageLedger(config, "kannel-report", time::get, 0);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(1));
        ledger.admission(ledger.attempt(true), MessageLedger.Admission.REJECTED);
        time.set(TimeUnit.SECONDS.toNanos(1)); ledger.endMeasurement(); ledger.freeze();
        ResultReport writer = new ResultReport();
        var initial = writer.build(config, ledger, KannelFixtures.context("kannel-report"), List.of(), false);
        Instant origin = Instant.parse("2026-10-30T08:00:00Z");
        initial.json().withObject("/measurementMetadata").put("resourceFinalizationVersion", 1)
                .put("measurementStartUtc", origin.toString()).put("observedDurationNanos", 1_000_000_000L);
        writer.write(directory.resolve("initial"), initial);
        Path samples = directory.resolve("samples.jsonl");
        StringBuilder jsonl = new StringBuilder();
        StringBuilder harness = new StringBuilder();
        for (int i = 0; i <= 1; i++) {
            jsonl.append(mapper.writeValueAsString(new ResourceEvidence.Sample(origin.plusSeconds(i).toString(),
                    origin.plusSeconds(i - 1).toString(), 1.0, 50.0, 25.0, 100L, 200L, 1000L, 2, null, null,
                    List.of(new ResourceEvidence.ProcessSample("bearerbox", 11L, 123L, 20.0, 40L),
                            new ResourceEvidence.ProcessSample("smsbox", 12L, 124L, 30.0, 60L))))).append('\n');
            harness.append(mapper.writeValueAsString(new ResourceEvidence.Sample(origin.plusSeconds(i).toString(),
                    origin.plusSeconds(i - 1).toString(), 1.0, 50.0, 25.0, 100L, 200L, 1000L, 2, 21L, 456L))).append('\n');
        }
        Files.writeString(samples, jsonl);
        Files.writeString(directory.resolve("harness.jsonl"), harness);
        Path inputs = directory.resolve("resources.json");
        mapper.writeValue(inputs.toFile(), new ExecutionSettings.ResourceFiles(1, null, "harness.jsonl", null,
                10, null, "samples.jsonl"));
        assertEquals(0, HarnessMain.run(new String[]{"finalize-report", directory.resolve("initial/results.json").toString(),
                inputs.toString(), directory.resolve("final").toString()}));
        var result = mapper.readTree(directory.resolve("final/results.json").toFile());
        assertEquals("valid", result.at("/validity/status").asText());
        assertTrue(result.at("/provenance/applicationCommit").isNull());
        assertEquals("kannel", result.at("/configuration/application/benchmarkTarget").asText());
        assertTrue(result.path("resources").has("kannel"));
        assertFalse(result.path("resources").has("sendium"));
        assertEquals(50.0, result.at("/resources/summary/overall/kannel/cpuPercentAverage").asDouble());
        var persistedInitial = mapper.readTree(directory.resolve("initial/results.json").toFile());
        assertEquals(persistedInitial.path("overall"), result.path("overall"));
        assertEquals(persistedInitial.path("cohortIntervals"), result.path("cohortIntervals"));
        assertEquals(persistedInitial.path("eventIntervals"), result.path("eventIntervals"));
        String markdown = Files.readString(directory.resolve("final/summary.md"));
        assertTrue(markdown.startsWith("# Kannel performance results"));
        assertTrue(markdown.contains("Kannel version: **1.4.5**"));
        assertTrue(markdown.contains("Requests awaiting Kannel acknowledgement"));
        assertTrue(markdown.contains("|kannel|50.000|"));
        assertTrue(markdown.contains("shared pages may be counted more than once"));
        assertTrue(result.at("/resources/kannel/0/pid").isNull());
        assertEquals("smsbox", result.at("/resources/kannel/0/processes/1/name").asText());
        assertFalse(markdown.contains("Requests awaiting Sendium acknowledgement"));
        writer.write(Path.of("target/report-fixtures/kannel-" + java.util.UUID.randomUUID()),
                new ResultReport.Report((com.fasterxml.jackson.databind.node.ObjectNode) result, markdown));
    }

    @Test
    void kannelResourceIdentityChecksUseKannelInfrastructureAndImportsRejectLegacySendiumInput() {
        var context = KannelFixtures.context("resources-test");
        context.infrastructure().withObject("/kannel").put("processId", 11);
        Instant start = Instant.parse("2026-10-30T08:00:00Z");
        var sample = new ResourceEvidence.Sample(start.plusSeconds(1).toString(), start.toString(),
                1.0, 50.0, 25.0, 100L, 200L, 1000L, 2, 99L, 123L);
        var evidence = new ResourceEvidence(List.of(sample), List.of(), List.of(), List.of(), 10);
        assertTrue(RunValidity.resourceReasons(context, 1_000_000_000L, evidence,
                new ResourceEvidence.Window(start, start.plusSeconds(1))).stream()
                .anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_IDENTITY_MISMATCH")
                        && reason.message().startsWith("kannel")));
        var legacy = new ExecutionSettings.ResourceFiles(1, "samples.jsonl", null, null, 10);
        assertThrows(IllegalArgumentException.class, () -> legacy.load(directory, null, BenchmarkTarget.KANNEL));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionSettings.ResourceFiles(1,
                "old.jsonl", null, null, 10, null, "new.jsonl"));
    }
}
