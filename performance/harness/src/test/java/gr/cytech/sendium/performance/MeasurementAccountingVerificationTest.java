package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

/** Cross-component invariants, deliberately independent of networking and OS scheduling speed. */
class MeasurementAccountingVerificationTest {
    private final AtomicLong clock = new AtomicLong();
    private RunConfig config(boolean dlr) { return new RunConfig(1, "http", 1, 100, 1, 120, 2, dlr, 0, 0, 1000, 10, 100); }
    private MessageLedger ledger(boolean dlr, long retention) {
        var ledger = new MessageLedger(config(dlr), "verify", clock::get, retention);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(120));
        return ledger;
    }
    private ResultReport.Context context() {
        var mapper = new ObjectMapper();
        var app = mapper.createObjectNode(); app.putArray("jvmArguments"); app.putObject("connections"); app.put("dlrStorage", "postgresql");
        var infrastructure = mapper.createObjectNode(); infrastructure.put("preset", "accounting-verification").put("region", "local");
        infrastructure.putObject("sendium"); infrastructure.putObject("harness"); infrastructure.putObject("database");
        return new ResultReport.Context(new ResultReport.Provenance("verification", "a".repeat(40), "b".repeat(40), null,
                "accounting-verification", List.of()), app, infrastructure, List.of());
    }
    private void finish(MessageLedger ledger) {
        clock.set(TimeUnit.SECONDS.toNanos(120)); ledger.endMeasurement();
        clock.set(TimeUnit.SECONDS.toNanos(122)); ledger.finishDrain(); ledger.freeze();
    }
    private void provider(MessageLedger ledger, String token, boolean dlr) {
        ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE);
        if (dlr) { ledger.event(token, RECEIPT_GENERATED); }
    }
    private void equations(MessageLedger.Counts counts, boolean dlr) {
        assertEquals(counts.scheduled(), counts.attempted() + counts.missed());
        assertEquals(counts.attempted(), counts.accepted() + counts.rejected() + counts.failed() + counts.timedOut() + counts.pendingAdmission());
        assertEquals(counts.accepted(), counts.acceptedProviderCompleted() + counts.acceptedAwaitingProvider());
        if (dlr) { assertEquals(counts.accepted(), counts.acceptedReceiptForwarded() + counts.acceptedAwaitingReceipt()); }
    }
    static Stream<Arguments> acknowledgementOrders() {
        return Stream.of(ACCEPTED, TIMED_OUT).flatMap(outcome -> IntStream.range(0, 5).mapToObj(position -> Arguments.of(outcome, position)));
    }

    @ParameterizedTest(name = "{0} admission at event position {1}")
    @MethodSource("acknowledgementOrders")
    void admissionRacesPreserveMinuteAttributionAndTimeoutAmbiguity(MessageLedger.Admission outcome, int position) {
        var ledger = ledger(true, TimeUnit.SECONDS.toNanos(300));
        clock.set(59_999_000_000L);
        String token = ledger.attempt(true);
        var events = List.of(PROVIDER_ARRIVAL, PROVIDER_RESPONSE, RECEIPT_GENERATED, RECEIPT_FORWARDED);
        int nextEvent = 0;
        for (int index = 0; index < 5; index++) {
            clock.addAndGet(1_000_000);
            if (index == position) { ledger.admission(token, outcome, outcome == ACCEPTED ? "gateway" : null); }
            else {
                var event = events.get(nextEvent++);
                if (event == RECEIPT_FORWARDED) { assertTrue(ledger.receipt(token, "gateway")); }
                else { ledger.event(token, event); }
            }
            equations(ledger.snapshot(), true);
        }
        finish(ledger);
        var counts = ledger.snapshot(); var timeline = ledger.timeline();
        assertEquals(outcome == ACCEPTED ? 1 : 0, counts.accepted());
        assertEquals(outcome == TIMED_OUT ? 1 : 0, counts.providerObservedWithoutAcceptance());
        assertEquals(outcome == TIMED_OUT, ledger.hasOutstandingWork());
        assertEquals(0, counts.violations());
        assertEquals(1, timeline.cohorts().getFirst().counts().receiptsForwarded());
        assertEquals(0, timeline.cohorts().get(1).counts().attempted());
        assertEquals(0, timeline.events().getFirst().providerArrivals());
        assertEquals(1, timeline.events().get(1).receiptsForwarded());
        assertEquals(outcome == ACCEPTED ? 1 : 0, ledger.latencies().get(MessageLedger.Latency.ACKNOWLEDGEMENT).count());
        assertFalse(RunValidity.assess(config(true), ledger, context(), List.of(), ResourceEvidence.empty(), null, List.of())
                .reasons().stream().anyMatch(reason -> reason.severity() == RunValidity.Severity.FAILED));
    }

    @Test
    void warmupCompletionDuringDrainNeverLeaksIntoMeasurementCountsRatesOrLatencies() {
        var ledger = ledger(true, 0);
        clock.set(-1_000_000_000L); String warmup = ledger.attempt(false); ledger.admission(warmup, ACCEPTED);
        clock.set(1_000_000_000L); String measured = ledger.attempt(true); ledger.admission(measured, ACCEPTED);
        clock.set(TimeUnit.SECONDS.toNanos(120)); ledger.endMeasurement();
        clock.set(TimeUnit.SECONDS.toNanos(121));
        for (String token : List.of(warmup, measured)) { provider(ledger, token, true); ledger.event(token, RECEIPT_FORWARDED); }
        finish(ledger);
        var report = new ResultReport().build(config(true), ledger, context(), List.of(), false);
        assertEquals(1, report.json().at("/overall/counts/providerResponses").asLong());
        assertEquals(1, report.json().at("/overall/latencies/receiptForwarding/count").asLong());
        assertEquals(0, report.json().at("/overall/rates/providerResponsesPerSecond").asDouble());
        assertEquals(1, report.json().at("/eventIntervals/2/receiptsForwarded").asLong());
        assertEquals(0, report.json().at("/drain/unfinishedAccepted").asLong());
    }

    @Test
    void exactDrainDeadlineIsExclusiveAndLeavesItsPreBoundaryOutstandingCountVisible() {
        var ledger = ledger(false, TimeUnit.SECONDS.toNanos(300));
        clock.set(1); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        clock.set(TimeUnit.SECONDS.toNanos(120)); ledger.endMeasurement();
        clock.set(TimeUnit.SECONDS.toNanos(122)); provider(ledger, token, false); ledger.finishDrain(); ledger.freeze();
        var report = new ResultReport().build(config(false), ledger, context(), List.of(), true);
        assertEquals(1, report.json().at("/overall/counts/providerResponses").asLong());
        assertEquals(0, report.json().at("/eventIntervals/2/providerResponses").asLong());
        assertEquals(1, report.json().at("/drain/unfinishedAccepted").asLong());
        assertTrue(report.json().at("/measurementMetadata/outsideWindowEvents").asLong() > 0);
    }

    @Test
    void invalidReceiptTimestampsAreRejectedWithoutPoisoningIdentityOrCompletionState() {
        var ledger = ledger(true, TimeUnit.SECONDS.toNanos(300));
        clock.set(1); String token = ledger.attempt(true);
        clock.set(10); provider(ledger, token, true);
        clock.set(20);
        assertEquals(MessageLedger.ReceiptResult.INVALID, ledger.receiveReceiptAt(token, "wrong-early-id", 9));
        assertEquals(MessageLedger.ReceiptResult.INVALID, ledger.receiveReceiptAt(token, "wrong-future-id", 21));
        assertEquals(0, ledger.snapshot().receiptsForwarded());
        assertEquals(0, ledger.latencies().get(MessageLedger.Latency.RECEIPT_FORWARDING).count());
        assertEquals(MessageLedger.ReceiptResult.RECORDED, ledger.receiveReceiptAt(token, "correct-id", 20));
        ledger.admission(token, ACCEPTED, "correct-id");
        assertEquals(1, ledger.snapshot().acceptedReceiptForwarded());
        assertEquals(2, ledger.snapshot().violations());
    }

    @Test
    void malformedAliasesOfIssuedTokensAreViolationsRatherThanHistoryLimitations() {
        var ledger = ledger(false, 0);
        String token = ledger.attempt(true); ledger.admission(token, REJECTED);
        for (String alias : List.of("verify:01", "verify:+1", "verify:0001")) { ledger.event(alias, PROVIDER_ARRIVAL); }
        assertEquals(3, ledger.snapshot().violations());
        assertEquals(0, ledger.eventsBeyondRetention());
        ledger.event(token, PROVIDER_ARRIVAL);
        assertEquals(1, ledger.eventsBeyondRetention());
    }

    @Test
    void sameTickFinishIncludesAlreadyRecordedDrainEventsInAnHonestExclusiveWindow() {
        var ledger = ledger(false, 0);
        clock.set(1); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        clock.set(TimeUnit.SECONDS.toNanos(120)); ledger.endMeasurement();
        clock.set(TimeUnit.SECONDS.toNanos(121)); provider(ledger, token, false); ledger.finishDrain(); ledger.freeze();
        var timeline = ledger.timeline();
        assertTrue(timeline.drainNanos() > TimeUnit.SECONDS.toNanos(1));
        assertEquals(1, timeline.events().getLast().providerResponses());
        assertEquals(0, timeline.events().getLast().outstanding().acceptedAwaitingProvider());
    }

    @Test
    void sameTickEarlyMeasurementStopDoesNotMisplacePreviouslyObservedEvents() {
        var ledger = ledger(false, 0);
        clock.set(1); String token = ledger.attempt(true); ledger.admission(token, ACCEPTED);
        clock.set(TimeUnit.SECONDS.toNanos(60)); provider(ledger, token, false); ledger.endMeasurement();
        clock.set(TimeUnit.SECONDS.toNanos(61)); ledger.freeze();
        var timeline = ledger.timeline();
        assertEquals(TimeUnit.SECONDS.toNanos(60) + 1, timeline.durationNanos());
        assertEquals(3, timeline.events().size());
        assertEquals(1, timeline.events().get(1).providerResponses());
        assertEquals(0, timeline.events().getLast().providerResponses());
    }

    @Test
    void invalidAcknowledgementTimeDoesNotPartiallyCommitAdmissionState() {
        var ledger = ledger(false, 0);
        clock.set(10); String token = ledger.attempt(true); ledger.markDispatched(token);
        clock.set(9); ledger.admission(token, ACCEPTED);
        assertEquals(1, ledger.snapshot().pendingAdmission());
        assertEquals(0, ledger.snapshot().accepted());
        assertEquals(0, ledger.latencies().get(MessageLedger.Latency.ACKNOWLEDGEMENT).count());
        clock.set(11); ledger.admission(token, ACCEPTED);
        assertEquals(0, ledger.snapshot().pendingAdmission());
        assertEquals(1, ledger.snapshot().accepted());
        assertEquals(1, ledger.snapshot().violations());
        equations(ledger.snapshot(), false);
    }

    @Test
    void correctGlobalVolumeCannotHideTargetsAssignedToTheWrongMinute() {
        var ledger = ledger(false, 0);
        for (int i = 0; i < 120; i++) { ledger.admission(ledger.attempt(true), REJECTED); }
        finish(ledger);
        var classification = RunValidity.assess(config(false), ledger, context(), List.of(), ResourceEvidence.empty(), null, List.of());
        assertEquals(120, ledger.snapshot().scheduled());
        assertEquals("failed", classification.status());
        assertTrue(classification.reasons().stream().anyMatch(reason -> reason.code().equals("COHORT_TARGET_MISMATCH")));
    }

    @Test
    void explicitRejectionAfterProviderProcessingFailsEvenWhenOtherEvidenceIsInconclusive() {
        var ledger = ledger(false, TimeUnit.SECONDS.toNanos(300));
        String token = ledger.attempt(true); provider(ledger, token, false); ledger.admission(token, REJECTED);
        finish(ledger);
        var classification = RunValidity.assess(config(false), ledger, context(), List.of(), ResourceEvidence.empty(), null,
                RunValidity.provider(java.util.Map.of(SmppProvider.Problem.SATURATION, 1L)));
        assertEquals("failed", classification.status()); assertEquals(1, classification.exitCode());
        assertTrue(classification.reasons().stream().anyMatch(reason -> reason.code().equals("ACCOUNTING_VIOLATION")));
        assertTrue(classification.reasons().stream().anyMatch(reason -> reason.code().equals("PROVIDER_SATURATION")));
    }
}
