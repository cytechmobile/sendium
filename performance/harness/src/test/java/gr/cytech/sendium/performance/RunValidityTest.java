package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class RunValidityTest {
    private final Instant origin = Instant.parse("2026-10-15T10:00:00Z");
    private final AtomicLong clock = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();
    private RunConfig config() { return new RunConfig(1, "http", 1, 100, 0, 2, 1, false, 0, 0, 1000, 10, 100); }
    private ResourceEvidence.Window window() { return new ResourceEvidence.Window(origin, origin.plusSeconds(2)); }
    private MessageLedger ledger() {
        clock.set(0);
        var ledger = new MessageLedger(config(), "validity", clock::get, TimeUnit.SECONDS.toNanos(30));
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(2));
        return ledger;
    }
    private void freeze(MessageLedger ledger) { clock.set(TimeUnit.SECONDS.toNanos(2)); ledger.endMeasurement(); ledger.freeze(); }
    private ResourceEvidence.Sample sample(int from, int to) {
        return new ResourceEvidence.Sample(origin.plusSeconds(to).toString(), origin.plusSeconds(from).toString(),
                (double) (to - from), 200.0, 100.0, 100_000_000L, 500_000_000L, 1_000_000_000L, 2, 7L, 42L);
    }
    private ResourceEvidence evidence() {
        var rows = List.of(sample(-1, 0), sample(0, 1), sample(1, 2), sample(2, 3));
        return new ResourceEvidence(rows, rows, List.of(), List.of(), 10);
    }
    private ResultReport.Context context(boolean aws) {
        var app = mapper.createObjectNode(); app.putArray("jvmArguments"); app.putObject("connections"); app.put("dlrStorage", "postgresql");
        var infra = mapper.createObjectNode(); infra.put("preset", "validity-test").put("region", aws ? "eu-west-1" : "local");
        infra.putObject("sendium").put("instanceId", "i-11111111").put("instanceType", "t3.small");
        infra.putObject("harness").put("instanceId", "i-22222222").put("instanceType", "m7i.large"); infra.putObject("database");
        return new ResultReport.Context(new ResultReport.Provenance("validity-fixture", "a".repeat(40), "b".repeat(40), null,
                "validity-fixture", List.of()), app, infra, List.of());
    }
    private void rejectTargets(MessageLedger ledger) {
        ledger.admission(ledger.attempt(true), REJECTED);
        clock.set(TimeUnit.SECONDS.toNanos(1)); ledger.admission(ledger.attempt(true), REJECTED);
    }
    private RunValidity.Classification assess(MessageLedger ledger, ResourceEvidence evidence, boolean aws, List<RunValidity.Reason> observations) {
        return RunValidity.assess(config(), ledger, context(aws), List.of(), evidence, window(), observations);
    }

    @Test
    void highUtilizationExpectedRejectionsAndUnfinishedAcceptedWorkAreNotPerformanceGates() {
        var ledger = ledger();
        ledger.admission(ledger.attempt(true), ACCEPTED); // Deliberately unfinished after drain.
        clock.set(TimeUnit.SECONDS.toNanos(1)); ledger.admission(ledger.attempt(true), REJECTED);
        freeze(ledger);
        var result = assess(ledger, evidence(), false, List.of());
        assertEquals("valid", result.status()); assertEquals(0, result.exitCode()); assertTrue(result.reasons().isEmpty());
        assertEquals(1, ledger.snapshot().acceptedAwaitingProvider());
    }

    @Test
    void missingSamplesOrCoverageAndResourceCollectionFailuresAreInconclusive() {
        var ledger = ledger(); rejectTargets(ledger); freeze(ledger);
        assertEquals("inconclusive", assess(ledger, ResourceEvidence.empty(), false, List.of()).status());
        var gap = List.of(sample(-1, 0), sample(1, 2));
        var evidence = new ResourceEvidence(gap, gap, List.of(), List.of("SAMPLER_FAILED"), 10);
        var result = assess(ledger, evidence, false, List.of());
        assertEquals("inconclusive", result.status()); assertEquals(0, result.exitCode());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.code().equals("RESOURCE_CPU_COVERAGE_GAP")));
    }

    @Test
    void insufficientOfferedLoadIsInconclusiveWhileExtraTargetsAndDuplicateEventsFail() {
        var under = ledger(); under.admission(under.attempt(true), REJECTED); freeze(under);
        assertEquals("inconclusive", assess(under, evidence(), false, List.of()).status());
        var extra = ledger(); rejectTargets(extra); extra.admission(extra.attempt(true), REJECTED); freeze(extra);
        assertEquals("failed", assess(extra, evidence(), false, List.of()).status());
        var duplicate = ledger();
        String token = duplicate.attempt(true); duplicate.admission(token, ACCEPTED);
        duplicate.event(token, PROVIDER_ARRIVAL); duplicate.event(token, PROVIDER_ARRIVAL);
        clock.set(TimeUnit.SECONDS.toNanos(1)); duplicate.admission(duplicate.attempt(true), REJECTED); freeze(duplicate);
        var result = assess(duplicate, ResourceEvidence.empty(), false, List.of());
        assertEquals("failed", result.status()); assertEquals(1, result.exitCode());
    }

    @Test
    void adapterDiagnosticsDistinguishHarnessSaturationFromProtocolSetupFailures() {
        var ledger = ledger(); rejectTargets(ledger); freeze(ledger);
        assertEquals("inconclusive", assess(ledger, evidence(), false,
                RunValidity.provider(Map.of(SmppProvider.Problem.SATURATION, 1L))).status());
        assertEquals("failed", assess(ledger, evidence(), false,
                RunValidity.httpAdmission(Map.of(HttpAdmissionClient.Problem.AUTHENTICATION_FAILURE, 1L))).status());
        assertEquals("inconclusive", assess(ledger, evidence(), false,
                RunValidity.smppAdmission(Map.of(SmppAdmissionClient.Problem.AMBIGUOUS_RECEIPT, 1L))).status());
        assertEquals("failed", assess(ledger, evidence(), false,
                RunValidity.httpReceipts(Map.of(HttpReceiptReceiver.Problem.CORRELATION_FAILURE, 1L))).status());
        assertEquals("failed", assess(ledger, evidence(), false,
                RunValidity.provider(Map.of(SmppProvider.Problem.IO_FAILURE, 1L))).status());
    }

    private ResourceEvidence.Credits credit(String source, String id, String type, String mode, boolean balance) {
        return new ResourceEvidence.Credits(source, id, type, type.startsWith("t3."), mode, 300, origin.minusSeconds(60).toString(), origin.plusSeconds(60).toString(),
                balance ? List.of(new ResourceEvidence.CreditSample(origin.toString(), 10.0, null, null, null)) : List.of(), List.of());
    }
    private ResourceEvidence awsEvidence(String id, String mode, boolean balance) {
        var local = evidence();
        return new ResourceEvidence(local.sendium(), local.harness(), List.of(credit("sendium", id, "t3.small", mode, balance),
                credit("harness", "i-22222222", "m7i.large", "not-applicable", false)), List.of(), 10);
    }

    @Test
    void burstableInstancesRequireMatchingIdentityModeAndObservedBalance() {
        var ledger = ledger(); rejectTargets(ledger); freeze(ledger);
        assertEquals("valid", assess(ledger, awsEvidence("i-11111111", "unlimited", true), true, List.of()).status());
        assertEquals("valid", assess(ledger, awsEvidence("i-11111111", "standard", true), true, List.of()).status());
        assertEquals("inconclusive", assess(ledger, awsEvidence("i-11111111", "unknown", true), true, List.of()).status());
        assertEquals("inconclusive", assess(ledger, awsEvidence("i-11111111", "unlimited", false), true, List.of()).status());
        assertEquals("failed", assess(ledger, awsEvidence("i-11111111", "not-applicable", false), true, List.of()).status());
        assertEquals("failed", assess(ledger, awsEvidence("i-99999999", "unlimited", true), true, List.of()).status());
        assertEquals("inconclusive", assess(ledger, evidence(), true, List.of()).status());
    }

    @Test
    void mismatchedResourceWindowOrProcessIdentityFailsRatherThanMixingMeasurements() {
        var ledger = ledger(); rejectTargets(ledger); freeze(ledger);
        assertEquals("failed", RunValidity.assess(config(), ledger, context(false), List.of(), evidence(),
                new ResourceEvidence.Window(origin, origin.plusSeconds(3)), List.of()).status());
        var context = context(false);
        context.infrastructure().withObject("/sendium").put("processId", 999);
        assertEquals("failed", RunValidity.assess(config(), ledger, context, List.of(), evidence(), window(), List.of()).status());
        var awsContext = context(true);
        awsContext.infrastructure().withObject("/sendium").put("creditMode", "standard");
        assertEquals("failed", RunValidity.assess(config(), ledger, awsContext, List.of(),
                awsEvidence("i-11111111", "unlimited", true), window(), List.of()).status());
    }

    @Test
    void reportIncludesOverallMinuteResourceSummariesAndSchemaCompatibleCreditObservations() throws Exception {
        var ledger = ledger(); rejectTargets(ledger); freeze(ledger);
        var writer = new ResultReport();
        var report = writer.build(config(), ledger, context(true), List.of(), false,
                awsEvidence("i-11111111", "unlimited", true), window(), List.of());
        assertEquals("valid", report.json().at("/validity/status").asText());
        assertEquals(200, report.json().at("/resources/summary/overall/sendium/cpuPercentAverage").asDouble());
        assertEquals(200, report.json().at("/resources/summary/intervals/0/harness/cpuPercentAverage").asDouble());
        assertEquals("unlimited", report.json().at("/resources/cpuCredits/0/mode").asText());
        assertTrue(report.markdown().contains("Process CPU avg %")); assertTrue(report.markdown().contains("t3.small|unlimited"));
        writer.write(Path.of("target/report-fixtures", "resources-" + UUID.randomUUID()), report);
    }
}
