package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class ResultReportTest {
    private final AtomicLong clock = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultReport writer = new ResultReport();
    @TempDir Path temporary;

    private RunConfig config(boolean dlr) { return new RunConfig(1, "http", 2, 100, 1, 135, 90, dlr, 0, 1000, 1000, 10, 100); }
    private MessageLedger ledger(RunConfig config) { return new MessageLedger(config, "report", clock::get, 0); }
    private void at(long seconds) { clock.set(TimeUnit.SECONDS.toNanos(seconds)); }
    private ResultReport.Context context() {
        var app = mapper.createObjectNode();
        app.putArray("jvmArguments"); app.putObject("connections").put("outgoingTransceivers", 1); app.put("dlrStorage", "postgresql");
        var infrastructure = mapper.createObjectNode();
        infrastructure.put("preset", "unit-fixture").put("region", "local");
        infrastructure.putObject("sendium"); infrastructure.putObject("harness"); infrastructure.putObject("database").put("engine", "postgresql");
        return new ResultReport.Context(new ResultReport.Provenance("test|<branch>", "a".repeat(40), "b".repeat(40), null,
                "report-fixture", List.of(new ResultReport.Artifact("application-fixture", "c".repeat(64)))), app, infrastructure, List.of("fixture.log"));
    }
    private void saveFixture(String name, ResultReport.Report report) throws Exception {
        writer.write(Path.of("target/report-fixtures", name + "-" + UUID.randomUUID()), report);
    }

    @Test
    void cohortsRetainDrainOutcomesWhileThroughputUsesTheActualEventWindow() throws Exception {
        RunConfig config = config(true);
        MessageLedger ledger = ledger(config);
        at(-2);
        String warmup = ledger.attempt(false); ledger.admission(warmup, ACCEPTED);
        ledger.event(warmup, PROVIDER_ARRIVAL); ledger.event(warmup, PROVIDER_RESPONSE);
        ledger.event(warmup, RECEIPT_GENERATED); ledger.event(warmup, RECEIPT_FORWARDED);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(59);
        String first = ledger.attempt(true); ledger.admission(first, ACCEPTED);
        at(60); ledger.event(first, PROVIDER_ARRIVAL);
        at(61); ledger.event(first, PROVIDER_RESPONSE);
        at(62); ledger.event(first, RECEIPT_GENERATED);
        at(120);
        String unfinished = ledger.attempt(true); ledger.admission(unfinished, ACCEPTED);
        at(135); ledger.endMeasurement();
        at(136); ledger.event(first, RECEIPT_FORWARDED);
        at(140); ledger.finishDrain(); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), true);
        var json = report.json();
        assertEquals(2, json.at("/overall/counts/accepted").asLong());
        assertEquals(1, json.at("/overall/counts/receiptsForwarded").asLong());
        assertEquals(0, json.at("/overall/rates/receiptsForwardedPerSecond").asDouble());
        assertEquals(1.0 / 135, json.at("/overall/rates/providerResponsesPerSecond").asDouble(), 1e-12);
        assertEquals(3, json.path("cohortIntervals").size());
        assertEquals(1, json.at("/cohortIntervals/0/counts/acceptedReceiptForwarded").asLong());
        assertEquals(15, json.at("/cohortIntervals/2/durationSeconds").asDouble());
        assertTrue(json.at("/cohortIntervals/0/rates/providerResponsesPerSecond").isNull());
        assertEquals(4, json.path("eventIntervals").size());
        assertEquals(0, json.at("/eventIntervals/0/providerArrivals").asLong());
        assertEquals(1, json.at("/eventIntervals/1/providerArrivals").asLong());
        assertEquals(1, json.at("/eventIntervals/0/outstanding/acceptedAwaitingProvider").asLong());
        assertEquals(2, json.at("/eventIntervals/2/outstanding/acceptedAwaitingReceipt").asLong());
        assertEquals("drain", json.at("/eventIntervals/3/phase").asText());
        assertEquals(0.2, json.at("/eventIntervals/3/rates/receiptsForwardedPerSecond").asDouble());
        assertEquals(1, json.at("/drain/unfinishedAccepted").asLong());
        assertEquals(0, ledger.retiredSize()); // Retired correlation eviction must not erase interval totals.
        assertTrue(report.markdown().contains("One-minute event-time output and backlog"));
        assertTrue(report.markdown().contains("Per-cohort latency percentiles"));
        assertTrue(report.markdown().contains("test\\|&lt;branch&gt;"));
        assertEquals("inconclusive", json.at("/validity/status").asText());
        saveFixture("drain", report);
    }

    @Test
    void bulkMissedTargetsAreDistributedAcrossMinutesWithoutPerTargetBookkeeping() throws Exception {
        RunConfig config = config(false);
        MessageLedger ledger = ledger(config);
        at(160);
        Lifecycle.Clock schedulerClock = new Lifecycle.Clock() {
            @Override public long nanoTime() { return clock.get(); }
            @Override public void sleepNanos(long nanos) { clock.addAndGet(nanos); }
        };
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            fail("Expired schedule must not generate traffic"); return false;
        }, schedulerClock)) {
            var statistics = generator.generate(Lifecycle.Phase.MEASUREMENT, 0, TimeUnit.SECONDS.toNanos(135), new AtomicBoolean());
            ledger.freeze();
            var report = writer.build(config, ledger, context(), List.of(statistics), false);
            assertEquals(270, report.json().at("/overall/counts/missed").asLong());
            assertEquals(120, report.json().at("/cohortIntervals/0/counts/missed").asLong());
            assertEquals(120, report.json().at("/cohortIntervals/1/counts/missed").asLong());
            assertEquals(30, report.json().at("/cohortIntervals/2/counts/missed").asLong());
            saveFixture("missed", report);
        }
    }

    @Test
    void earlySmppReceiptReplayUsesOriginalMinuteWithoutNegativeBacklog() throws Exception {
        RunConfig config = config(true);
        MessageLedger ledger = ledger(config);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(58); String token = ledger.attempt(true);
        ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE); ledger.event(token, RECEIPT_GENERATED);
        long arrival = TimeUnit.SECONDS.toNanos(59);
        at(61); ledger.admission(token, ACCEPTED, "gateway");
        ledger.receiveReceiptAt(token, "gateway", arrival);
        at(135); ledger.endMeasurement(); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals(1, report.json().at("/eventIntervals/0/receiptsForwarded").asLong());
        assertEquals(1, report.json().at("/eventIntervals/0/outstanding/pendingAdmission").asLong());
        assertEquals(0, report.json().at("/eventIntervals/0/outstanding/acceptedAwaitingReceipt").asLong());
        assertEquals(0, report.json().at("/eventIntervals/1/outstanding/pendingAdmission").asLong());
        assertEquals(0, report.json().at("/eventIntervals/1/outstanding/acceptedAwaitingReceipt").asLong());
        assertEquals(1000, report.json().at("/cohortIntervals/0/latencies/receiptForwarding/p50Millis").asDouble(), 1);
        saveFixture("replay", report);
    }

    @Test
    void completedMessagesCanBeEvictedWithoutGrowingIntervalStorageOrLosingTotals() throws Exception {
        RunConfig config = config(false);
        MessageLedger ledger = ledger(config);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(1);
        for (int i = 0; i < 1000; i++) {
            String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
            ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE);
        }
        at(135); ledger.endMeasurement(); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals(0, ledger.activeSize()); assertEquals(0, ledger.retiredSize());
        assertEquals(3, report.json().path("cohortIntervals").size());
        assertEquals(1000, report.json().at("/cohortIntervals/0/counts/providerResponses").asLong());
        assertEquals(1000, report.json().at("/cohortIntervals/0/latencies/submissionToProvider/count").asLong());
        assertEquals(0, report.json().at("/overall/counts/missingReceipts").asLong());
        saveFixture("retired", report);
    }

    @Test
    void setupFailureHasEmptyIntervalsAndNullRatesRatherThanFabricatedZeroLatency() throws Exception {
        RunConfig config = config(true);
        MessageLedger ledger = ledger(config);
        ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals("failed", report.json().at("/validity/status").asText());
        assertEquals(0, report.json().path("cohortIntervals").size());
        assertEquals(0, report.json().path("eventIntervals").size());
        assertTrue(report.json().at("/overall/rates/attemptedInputPerSecond").isNull());
        assertTrue(report.json().at("/overall/latencies/receiptForwarding/p99Millis").isNull());
        assertTrue(report.markdown().contains("n/a"));
        saveFixture("setup-failure", report);
    }

    @Test
    void duplicateReceiptsFailAccountingButDoNotInflateEventTimeThroughput() throws Exception {
        RunConfig config = config(true);
        MessageLedger ledger = new MessageLedger(config, "report", clock::get, TimeUnit.SECONDS.toNanos(300));
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(1); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE);
        ledger.event(token, RECEIPT_GENERATED); ledger.event(token, RECEIPT_FORWARDED);
        ledger.event(token, RECEIPT_FORWARDED);
        at(135); ledger.endMeasurement(); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals("failed", report.json().at("/validity/status").asText());
        assertEquals(1, report.json().at("/cohortIntervals/0/counts/duplicateReceipts").asLong());
        assertEquals(1, report.json().at("/eventIntervals/0/receiptsForwarded").asLong());
        at(1000); ledger.retiredSize();
        assertEquals(report.json(), writer.build(config, ledger, context(), List.of(), false).json());
        saveFixture("duplicate", report);
    }

    @Test
    void eventsOutsideTheBoundedWindowAreDisclosedInsteadOfInflatingRates() throws Exception {
        RunConfig config = config(false);
        MessageLedger ledger = ledger(config);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(1); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        at(135); ledger.endMeasurement();
        at(226); ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), true);
        assertEquals(1, report.json().at("/overall/counts/providerResponses").asLong());
        assertEquals(0, report.json().at("/overall/rates/providerResponsesPerSecond").asDouble());
        assertTrue(report.json().at("/measurementMetadata/outsideWindowEvents").asLong() > 0);
        assertEquals(1, report.json().at("/drain/unfinishedAccepted").asLong());
        assertTrue(report.markdown().contains("OUTSIDE_OBSERVATION_WINDOW"));
        saveFixture("outside-window", report);
    }

    @Test
    void intervalBudgetAndMissingOriginFailBeforePublishingMisleadingResults() throws Exception {
        RunConfig config = config(true);
        MessageLedger ledger = ledger(config);
        assertThrows(IllegalArgumentException.class, () -> ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135), 1));
        ledger.attempt(true); ledger.freeze();
        assertThrows(IllegalStateException.class, () -> writer.build(config, ledger, context(), List.of(), false));
        assertThrows(IllegalStateException.class, () -> writer.build(config, ledger(config), context(), List.of(), false));
    }

    @Test
    void finalizationAndReportWritingAreStableAndNeverOverwriteEarlierReports() throws Exception {
        RunConfig config = config(false);
        MessageLedger ledger = ledger(config);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(135); ledger.endMeasurement();
        at(140); ledger.finishDrain();
        at(150); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals(5, report.json().at("/drain/durationSeconds").asDouble());
        at(160); ledger.freeze();
        assertEquals(report.json(), writer.build(config, ledger, context(), List.of(), false).json());
        writer.write(temporary, report);
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> writer.write(temporary, report));
        assertEquals(report.markdown(), java.nio.file.Files.readString(temporary.resolve("summary.md")));
        assertEquals(mapper.readTree(report.json().toString()), mapper.readTree(temporary.resolve("results.json").toFile()));
    }

    @Test
    void earlyEndReanchorsDrainAndPreservesPartiallyObservedCohorts() throws Exception {
        RunConfig config = config(false);
        MessageLedger ledger = ledger(config);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(135));
        at(65); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        at(70); ledger.endMeasurement();
        at(71); ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE);
        at(75); ledger.freeze();
        var report = writer.build(config, ledger, context(), List.of(), false);
        assertEquals(70, report.json().at("/overall/durationSeconds").asDouble());
        assertEquals(10, report.json().at("/cohortIntervals/1/durationSeconds").asDouble());
        assertEquals(0, report.json().at("/cohortIntervals/2/durationSeconds").asDouble());
        assertEquals("drain", report.json().at("/eventIntervals/2/phase").asText());
        assertEquals(70, report.json().at("/eventIntervals/2/startOffsetSeconds").asDouble());
        assertEquals(0, report.json().at("/overall/rates/providerResponsesPerSecond").asDouble());
        saveFixture("early-end", report);
    }
}
