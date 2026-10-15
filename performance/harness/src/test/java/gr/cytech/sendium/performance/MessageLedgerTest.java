package gr.cytech.sendium.performance;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class MessageLedgerTest {
    private final AtomicLong time = new AtomicLong();

    private MessageLedger ledger(boolean dlr, int requests, int tracked) {
        return new MessageLedger(new RunConfig(1, "http", 100, 100, 0, 1, 1,
                dlr, 0, 0, 1000, requests, tracked), "test", time::get, 1_000_000_000L);
    }

    @Test
    void dispatchTimestampExcludesConstructionAndCanOnlyBeSetOnce() {
        MessageLedger ledger = ledger(false, 1, 1);
        String token = ledger.attempt(true);
        time.set(10_000_000);
        ledger.markDispatched(token);
        time.set(11_000_000);
        ledger.event(token, PROVIDER_ARRIVAL);
        assertEquals(1.0, ledger.latencies().get(MessageLedger.Latency.SUBMISSION_TO_PROVIDER).p50Millis(), 0.01);
        assertThrows(IllegalStateException.class, () -> ledger.markDispatched(token));
    }

    @Test
    void concurrentProtocolCallbacksPreserveUniqueMessageTotals() throws Exception {
        MessageLedger ledger = ledger(false, 100, 100);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) {
                String token = ledger.attempt(true);
                assertNotNull(token);
                futures.add(executor.submit(() -> {
                    ledger.event(token, PROVIDER_ARRIVAL);
                    ledger.event(token, PROVIDER_RESPONSE);
                    ledger.admission(token, ACCEPTED);
                }));
            }
            for (var future : futures) { future.get(); }
        }
        assertEquals(100, ledger.snapshot().accepted());
        assertEquals(100, ledger.snapshot().acceptedProviderCompleted());
        assertEquals(0, ledger.snapshot().violations());
        assertFalse(ledger.hasOutstandingWork());
    }

    @Test
    void providerAndReceiptCanCompleteBeforeAdmissionAcknowledgement() {
        MessageLedger ledger = ledger(true, 10, 10);
        String token = ledger.attempt(true);
        time.set(1_000_000);
        ledger.event(token, PROVIDER_ARRIVAL);
        time.set(2_000_000);
        ledger.event(token, PROVIDER_RESPONSE);
        time.set(3_000_000);
        ledger.event(token, RECEIPT_GENERATED);
        time.set(4_000_000);
        ledger.event(token, RECEIPT_FORWARDED);
        ledger.admission(token, ACCEPTED);
        var counts = ledger.snapshot();
        assertEquals(1, counts.acceptedProviderCompleted());
        assertEquals(1, counts.acceptedReceiptForwarded());
        assertEquals(0, counts.pendingAdmission());
        assertEquals(0, counts.violations());
        assertFalse(ledger.hasOutstandingWork());
        var latency = ledger.latencies().get(MessageLedger.Latency.RECEIPT_FORWARDING);
        assertEquals(1.0, latency.p50Millis(), 0.01);
    }

    @Test
    void warmupRemainsTrackedButExcludedFromMeasurementCounts() {
        MessageLedger ledger = ledger(false, 1, 1);
        String warmup = ledger.attempt(false);
        assertNull(ledger.attempt(true));
        ledger.admission(warmup, ACCEPTED);
        ledger.event(warmup, PROVIDER_ARRIVAL);
        ledger.event(warmup, PROVIDER_RESPONSE);
        var counts = ledger.snapshot();
        assertEquals(1, counts.scheduled());
        assertEquals(1, counts.missed());
        assertEquals(0, counts.attempted());
        assertEquals(0, counts.providerResponses());
        assertFalse(ledger.hasOutstandingWork());
    }

    @Test
    void acceptedBacklogBlocksGenerationWithoutEvictingUnfinishedEntries() {
        MessageLedger ledger = ledger(true, 1, 1);
        String token = ledger.attempt(true);
        ledger.admission(token, ACCEPTED);
        ledger.event(token, PROVIDER_ARRIVAL);
        ledger.event(token, PROVIDER_RESPONSE);
        assertNull(ledger.attempt(true));
        assertEquals(1, ledger.activeSize());
        assertEquals(0, ledger.snapshot().acceptedAwaitingProvider());
        assertEquals(1, ledger.snapshot().acceptedAwaitingReceipt());
    }

    @Test
    void timeoutsRemainAmbiguousEvenWhenProviderResponds() {
        MessageLedger ledger = ledger(false, 1, 2);
        String token = ledger.attempt(true);
        ledger.admission(token, TIMED_OUT);
        ledger.event(token, PROVIDER_ARRIVAL);
        ledger.event(token, PROVIDER_RESPONSE);
        assertEquals(1, ledger.snapshot().providerObservedWithoutAcceptance());
        assertEquals(0, ledger.snapshot().accepted());
        assertEquals(1, ledger.snapshot().timedOut());
        assertTrue(ledger.hasOutstandingWork());
        assertEquals(0, ledger.snapshot().violations());
    }

    @Test
    void duplicateAndContradictoryEventsFailAccountingWithoutDoubleCounting() {
        MessageLedger ledger = ledger(false, 2, 2);
        String token = ledger.attempt(true);
        ledger.admission(token, ACCEPTED);
        ledger.event(token, PROVIDER_ARRIVAL);
        ledger.event(token, PROVIDER_RESPONSE);
        ledger.event(token, PROVIDER_ARRIVAL);
        ledger.admission(token, ACCEPTED);
        String rejected = ledger.attempt(true);
        ledger.admission(rejected, REJECTED);
        ledger.event(rejected, PROVIDER_ARRIVAL);
        assertEquals(1, ledger.snapshot().providerArrivals());
        assertEquals(1, ledger.snapshot().duplicateProviderRequests());
        assertEquals(3, ledger.snapshot().violations());
        assertEquals(1, ledger.snapshot().rejected());
    }

    @Test
    void retiredHistoryIsBoundedAndExpiredEventsAreExplicitlyLimited() {
        MessageLedger ledger = ledger(false, 1, 1);
        String first = ledger.attempt(true);
        ledger.admission(first, REJECTED);
        String second = ledger.attempt(true);
        ledger.admission(second, REJECTED);
        assertEquals(1, ledger.retiredSize());
        assertEquals(1, ledger.retiredEvictions());
        ledger.event(first, PROVIDER_ARRIVAL);
        assertEquals(1, ledger.eventsBeyondRetention());
        ledger.event("foreign:1", PROVIDER_ARRIVAL);
        assertEquals(1, ledger.snapshot().violations());
        time.set(2_000_000_000L);
        assertEquals(0, ledger.retiredSize());
    }

    @Test
    void finalizationFreezesMutationAndPreservesAccountingEquations() {
        MessageLedger ledger = ledger(false, 2, 2);
        String first = ledger.attempt(true);
        ledger.admission(first, ACCEPTED);
        ledger.attempt(true);
        assertNull(ledger.attempt(true));
        ledger.freeze();
        assertThrows(IllegalStateException.class, () -> ledger.event(first, PROVIDER_ARRIVAL));
        var counts = ledger.snapshot();
        assertEquals(counts.scheduled(), counts.attempted() + counts.missed());
        assertEquals(counts.attempted(), counts.accepted() + counts.rejected() + counts.failed()
                + counts.timedOut() + counts.pendingAdmission());
        assertEquals(counts.accepted(), counts.acceptedProviderCompleted() + counts.acceptedAwaitingProvider());
    }
}
