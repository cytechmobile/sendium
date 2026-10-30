package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcessGroupEvidenceTest {
    @TempDir Path directory;
    private final Instant origin = Instant.parse("2026-10-30T08:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    private List<ResourceEvidence.ProcessSample> members(long secondPid, long secondStart) {
        return List.of(new ResourceEvidence.ProcessSample("bearerbox", 11L, 123L, 20.0, 40L),
                new ResourceEvidence.ProcessSample("smsbox", secondPid, secondStart, 30.0, 60L));
    }

    private ResourceEvidence.Sample group(int second, List<ResourceEvidence.ProcessSample> members, Double cpu, long rss) {
        return new ResourceEvidence.Sample(origin.plusSeconds(second).toString(), origin.plusSeconds(second - 1).toString(),
                1.0, cpu, 25.0, rss, 200L, 1000L, 2, null, null, members);
    }

    private List<RunValidity.Reason> reasons(List<ResourceEvidence.Sample> samples, ResultReport.Context context) {
        return RunValidity.resourceReasons(context, 1_000_000_000L,
                new ResourceEvidence(samples, List.of(), List.of(), List.of(), 10),
                new ResourceEvidence.Window(origin, origin.plusSeconds(1)));
    }

    @Test
    void aggregatesAreVerifiedOnImportAndLegacySinglePidSamplesKeepTheirJsonShape() throws Exception {
        var sample = group(0, members(12, 124), 50.0, 100);
        Path file = directory.resolve("group.jsonl");
        Files.writeString(file, mapper.writeValueAsString(sample) + "\n");
        var imported = ResourceEvidence.load(file, null, null, 10).sendium().getFirst();
        assertEquals(sample, imported);
        assertNull(imported.pid());
        assertEquals(2, imported.processes().size());
        var legacy = new ResourceEvidence.Sample(origin.toString(), null, null, null, null,
                100L, 200L, 1000L, 2, 11L, 123L);
        assertFalse(mapper.valueToTree(legacy).has("processes"));
        assertEquals(legacy, mapper.readValue(mapper.writeValueAsString(legacy), ResourceEvidence.Sample.class));
        Files.writeString(file, mapper.writeValueAsString(sample).replace("\"cpuPercent\":50.0", "\"cpuPercent\":51.0"));
        var partial = ResourceEvidence.loadAvailable(file, null, null, null, 10, BenchmarkTarget.KANNEL);
        assertTrue(partial.sendium().isEmpty());
        assertEquals(List.of("kannel:RESOURCE_IMPORT_FAILED"), partial.collectionIssues());
    }

    @Test
    void invalidAggregationDuplicateMembersAndFabricatedBaselineCpuAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> group(0, members(12, 124), 51.0, 100));
        assertThrows(IllegalArgumentException.class, () -> group(0, members(12, 124), 50.0, 101));
        assertThrows(IllegalArgumentException.class, () -> group(0, members(11, 124), 50.0, 100));
        assertThrows(IllegalArgumentException.class, () -> group(0, List.of(members(12, 124).getFirst(), members(12, 124).getFirst()), 40.0, 80));
        assertThrows(IllegalArgumentException.class, () -> group(0, members(12, 124), null, 100));
        assertThrows(IllegalArgumentException.class, () -> group(0, List.of(), 0.0, 0));
        assertThrows(IllegalArgumentException.class, () -> group(0, java.util.Collections.nCopies(17, members(12, 124).getFirst()), 340.0, 680));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence.ProcessSample("smsbox", 12L, 124L, -1.0, 60L));
        var baseline = new ResourceEvidence.Sample(origin.toString(), null, null, null, null,
                100L, 200L, 1000L, 2, null, null,
                List.of(new ResourceEvidence.ProcessSample("bearerbox", 11L, 123L, null, 40L),
                        new ResourceEvidence.ProcessSample("smsbox", 12L, 124L, null, 60L)));
        assertNull(baseline.cpuPercent());
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvidence.Sample(origin.toString(), null, null, null, null,
                100L, 200L, 1000L, 2, 11L, 123L, baseline.processes()));
    }

    @Test
    void secondaryPidReuseMissingRolesAndUnexpectedMembersFailIdentityValidation() {
        var first = group(0, members(12, 124), 50.0, 100);
        for (var changed : List.of(group(1, members(12, 999), 50.0, 100), group(1, members(99, 124), 50.0, 100))) {
            assertTrue(reasons(List.of(first, changed), KannelFixtures.context("identity")).stream()
                    .anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_IDENTITY_MISMATCH") && reason.severity() == RunValidity.Severity.FAILED));
        }
        var wrongRole = group(0, List.of(first.processes().getFirst(),
                new ResourceEvidence.ProcessSample("other", 12L, 124L, 30.0, 60L)), 50.0, 100);
        assertTrue(reasons(List.of(wrongRole), KannelFixtures.context("roles")).stream()
                .anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_GROUP_MISMATCH")));
        var context = KannelFixtures.context("expected");
        var expected = context.infrastructure().withObject("/kannel").putArray("processes");
        expected.addObject().put("name", "bearerbox").put("pid", 11).put("processStartTicks", 123);
        expected.addObject().put("name", "smsbox").put("pid", 88).put("processStartTicks", 124);
        assertTrue(reasons(List.of(first), context).stream().anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_IDENTITY_MISMATCH")));
    }

    @Test
    void singleProcessKannelEvidenceIsInconclusiveAndSamplingModeCannotChange() {
        var single = new ResourceEvidence.Sample(origin.plusSeconds(1).toString(), origin.toString(),
                1.0, 50.0, 25.0, 100L, 200L, 1000L, 2, 11L, 123L);
        assertTrue(reasons(List.of(single), KannelFixtures.context("missing-group")).stream()
                .anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_GROUP_MISSING") && reason.severity() == RunValidity.Severity.INCONCLUSIVE));
        assertTrue(reasons(List.of(group(0, members(12, 124), 50.0, 100), single), KannelFixtures.context("mode")).stream()
                .anyMatch(reason -> reason.code().equals("RESOURCE_PROCESS_IDENTITY_MISMATCH")));
    }
}
