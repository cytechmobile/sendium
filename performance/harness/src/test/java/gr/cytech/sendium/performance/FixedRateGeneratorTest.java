package gr.cytech.sendium.performance;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.Lifecycle.Phase.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.ACCEPTED;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class FixedRateGeneratorTest {
    private RunConfig config(double rate, int outstanding) {
        return new RunConfig(1, "http", rate, 100, 1, 1, 1, false, 200, 0, 2000, outstanding, 100);
    }
    private MessageLedger ledger(RunConfig config, Lifecycle.Clock clock) {
        return new MessageLedger(config, "schedule", clock::nanoTime, TimeUnit.SECONDS.toNanos(5));
    }
    private static class Clock implements Lifecycle.Clock {
        long now;
        public long nanoTime() { return now; }
        public void sleepNanos(long nanos) { now += nanos; }
    }

    @Test
    void usesAbsoluteHalfOpenSlotsAndIncludesTheQuietTailInInputRate() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 100);
        MessageLedger ledger = ledger(config, clock);
        List<Long> slots = new ArrayList<>();
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            slots.add(scheduled);
            String token = ledger.attempt(measured, scheduled);
            assertEquals(scheduled, ledger.scheduledNanos(token));
            return true;
        }, clock)) {
            var stats = generator.generate(MEASUREMENT, 0, 1_000_000_000L, new AtomicBoolean());
            assertEquals(10, stats.scheduled());
            assertEquals(10, stats.attempted());
            assertEquals(0L, slots.getFirst());
            assertEquals(900_000_000L, slots.getLast());
            assertEquals(1_000_000_000L, stats.elapsedNanos());
            assertEquals(10.0, stats.achievedInputPerSecond());
            assertFalse(stats.saturated());
            assertEquals(10, ledger.snapshot().scheduled());
        }
    }

    @Test
    void slowSubmissionWorkSkipsExpiredSlotsInsteadOfSendingACatchUpBatch() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 100);
        MessageLedger ledger = ledger(config, clock);
        List<Long> slots = new ArrayList<>();
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            slots.add(scheduled);
            ledger.attempt(measured, scheduled);
            clock.now += 350_000_000L;
            return true;
        }, clock)) {
            var stats = generator.generate(MEASUREMENT, 0, 1_000_000_000L, new AtomicBoolean());
            assertEquals(List.of(0L, 300_000_000L, 700_000_000L), slots);
            assertEquals(3, stats.attempted());
            assertEquals(7, stats.missedScheduling());
            assertEquals(50_000_000L, stats.maxLatenessNanos());
            assertEquals(10, ledger.snapshot().scheduled());
            assertEquals(7, ledger.snapshot().missed());
            assertTrue(stats.saturated());
        }
    }

    @Test
    void boundedOutstandingWorkDoesNotReduceTheOfferedSchedule() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 2);
        MessageLedger ledger = ledger(config, clock);
        try (var generator = new FixedRateGenerator(config, ledger,
                (measured, scheduled) -> ledger.attempt(measured, scheduled) != null, clock)) {
            var stats = generator.generate(MEASUREMENT, 0, 1_000_000_000L, new AtomicBoolean());
            assertEquals(10, stats.scheduled());
            assertEquals(2, stats.attempted());
            assertEquals(8, stats.missedCapacity());
            assertEquals(0, stats.missedScheduling());
            assertEquals(2, ledger.snapshot().pendingAdmission());
            assertEquals(stats.scheduled(), stats.attempted() + stats.missedCapacity() + stats.missedScheduling());
            assertEquals(10, ledger.snapshot().scheduled());
            assertEquals(8, ledger.snapshot().missed());
        }
    }

    @Test
    void lateStartAccountsForMissedTargetsAndNeverDispatchesAtOrAfterDeadline() throws Exception {
        Clock clock = new Clock();
        clock.now = 450_000_000L;
        RunConfig config = config(10, 100);
        MessageLedger ledger = ledger(config, clock);
        List<Long> slots = new ArrayList<>();
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            slots.add(scheduled); return ledger.attempt(measured, scheduled) != null;
        }, clock)) {
            var stats = generator.generate(MEASUREMENT, 0, 1_000_000_000L, new AtomicBoolean());
            assertEquals(4, stats.missedScheduling());
            assertEquals(400_000_000L, slots.getFirst());
            assertEquals(900_000_000L, slots.getLast());
            assertEquals(6, stats.attempted());
            slots.clear();
            var expired = generator.generate(MEASUREMENT, 0, 1_000_000_000L, new AtomicBoolean());
            assertEquals(10, expired.missedScheduling());
            assertTrue(slots.isEmpty());
        }
    }

    @Test
    void warmupBacklogCanLimitMeasurementWithoutContaminatingItsCounters() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 1);
        MessageLedger ledger = ledger(config, clock);
        try (var generator = new FixedRateGenerator(config, ledger,
                (measured, scheduled) -> ledger.attempt(measured, scheduled) != null, clock)) {
            assertEquals(9, generator.generate(WARMUP, 0, 1_000_000_000L, new AtomicBoolean()).missedCapacity());
            assertEquals(0, ledger.snapshot().scheduled());
            var measurement = generator.generate(MEASUREMENT, 1_000_000_000L, 2_000_000_000L, new AtomicBoolean());
            assertEquals(10, measurement.missedCapacity());
            assertEquals(10, ledger.snapshot().scheduled());
            assertEquals(0, ledger.snapshot().attempted());
            assertTrue(ledger.hasOutstandingWork());
        }
    }

    @Test
    void fractionalRateAndFutureStartHaveNoExtraBoundarySubmission() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(0.7, 100);
        MessageLedger ledger = ledger(config, clock);
        try (var generator = new FixedRateGenerator(config, ledger,
                (measured, scheduled) -> ledger.attempt(measured, scheduled) != null, clock)) {
            var stats = generator.generate(MEASUREMENT, 1_000_000_000L, 11_000_000_000L, new AtomicBoolean());
            assertEquals(7, stats.scheduled());
            assertEquals(7, stats.attempted());
            assertEquals(10_000_000_000L, stats.elapsedNanos());
            assertEquals(0.7, stats.achievedInputPerSecond(), 0.0001);
        }
    }

    @Test
    void earlyStopAccountsForUnsentTargetsAndMarksPartialObservation() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 100);
        MessageLedger ledger = ledger(config, clock);
        AtomicBoolean stopped = new AtomicBoolean();
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            ledger.attempt(measured, scheduled);
            if (scheduled == 100_000_000L) { stopped.set(true); }
            return true;
        }, clock)) {
            var stats = generator.generate(MEASUREMENT, 0, 1_000_000_000L, stopped);
            assertEquals(2, stats.attempted());
            assertEquals(8, stats.missedScheduling());
            assertEquals(100_000_000L, stats.elapsedNanos());
            assertTrue(stats.stoppedEarly());
        }
    }

    @Test
    void asynchronousStopJoinsGenerationAndCancelledResultCannotQueueOverlappingPhases() throws Exception {
        RunConfig config = config(1, 100);
        MessageLedger ledger = new MessageLedger(config, "async", System::nanoTime, 0);
        CountDownLatch submitted = new CountDownLatch(1);
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            ledger.attempt(measured, scheduled); submitted.countDown(); return true;
        })) {
            long start = System.nanoTime();
            var completion = generator.start(MEASUREMENT, start, start + TimeUnit.SECONDS.toNanos(10));
            assertTrue(submitted.await(3, TimeUnit.SECONDS));
            completion.cancel(false);
            assertThrows(IllegalStateException.class, () -> generator.start(WARMUP, start, start + 10));
            var stats = generator.stop();
            assertEquals(1, stats.attempted());
            assertEquals(9, stats.missedScheduling());
            assertTrue(stats.stoppedEarly());
            long next = System.nanoTime();
            assertEquals(0, generator.start(WARMUP, next, next).get(3, TimeUnit.SECONDS).scheduled());
            assertEquals(10, ledger.snapshot().scheduled());
        }
    }

    @Test
    void invalidPhasesAndUnrepresentableRatesAreRejected() throws Exception {
        Clock clock = new Clock();
        RunConfig config = config(10, 100);
        MessageLedger ledger = ledger(config, clock);
        try (var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> false, clock)) {
            assertThrows(IllegalArgumentException.class, () -> generator.start(DRAIN, 0, 100));
            assertThrows(IllegalArgumentException.class, () -> generator.start(WARMUP, 100, 0));
            var zero = generator.generate(MEASUREMENT, 0, 0, new AtomicBoolean());
            assertEquals(0, zero.scheduled());
            assertNull(zero.achievedInputPerSecond());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new FixedRateGenerator(config(1_000_000_001, 1), ledger, (measured, scheduled) -> false));
    }

    @Test
    void targetFailurePropagatesWithoutLeavingTheGeneratorThreadRunning() throws Exception {
        RunConfig config = config(10, 100);
        MessageLedger ledger = new MessageLedger(config, "failure", System::nanoTime, 0);
        var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            throw new IllegalStateException("Admission adapter failed");
        });
        long start = System.nanoTime();
        var completion = generator.start(MEASUREMENT, start, start + TimeUnit.SECONDS.toNanos(1));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> completion.get(3, TimeUnit.SECONDS)).getCause());
        assertThrows(ExecutionException.class, generator::close);
        assertThrows(IllegalStateException.class, () -> generator.start(MEASUREMENT, start, start + 1));
    }

    @Test
    void lifecycleJoinsEachTrafficPhaseBeforeDrainAndAccountingFinalization() throws Exception {
        RunConfig config = config(2, 100);
        MessageLedger ledger = new MessageLedger(config, "lifecycle", System::nanoTime, 0);
        var generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> {
            String token = ledger.attempt(measured, scheduled);
            ledger.admission(token, MessageLedger.Admission.REJECTED);
            return true;
        });
        List<FixedRateGenerator.Statistics> phases = new ArrayList<>();
        Scenario scenario = new Scenario() {
            public void awaitReady() { }
            public void startGeneration(Lifecycle.Phase phase, long start, long deadline) { generator.start(phase, start, deadline); }
            public void stopGeneration() throws Exception { phases.add(generator.stop()); }
            public boolean hasOutstandingWork() { return ledger.hasOutstandingWork(); }
            public void finalizeAccounting() { ledger.freeze(); }
            public void close() throws Exception { generator.close(); }
        };
        var outcome = new Lifecycle(phase -> { }).run(config, scenario);
        assertFalse(outcome.drainTimeoutReached());
        assertEquals(2, phases.size());
        assertEquals(2, phases.get(0).scheduled());
        assertEquals(2, phases.get(1).scheduled());
        assertFalse(phases.get(1).saturated());
        assertEquals(2, ledger.snapshot().scheduled());
        assertEquals(2, ledger.snapshot().rejected());
        assertThrows(IllegalStateException.class, () -> ledger.attempt(true));
    }

    @Test
    void realHttpAdapterStaysOpenLoopWhenAdmissionResponsesSlowDown() throws Exception {
        RunConfig config = config(20, 1);
        MessageLedger ledger = new MessageLedger(config, "http-rate", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        server.setExecutor(worker);
        server.createContext("/sendsms", exchange -> {
            try (exchange) {
                String token = HttpReceiptReceiver.decodeQuery(exchange.getRequestURI().getRawQuery()).get("text");
                Thread.sleep(150);
                ledger.event(token, PROVIDER_ARRIVAL); ledger.event(token, PROVIDER_RESPONSE);
                byte[] body = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        server.start();
        try (var client = new HttpAdmissionClient(config, new HttpIngressSettings(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sendsms"), "user", "secret", "from", "to", null), ledger);
                var generator = new FixedRateGenerator(config, ledger,
                        (measured, scheduled) -> client.submit(measured, scheduled).isPresent())) {
            long start = System.nanoTime();
            var stats = generator.start(MEASUREMENT, start, start + 600_000_000L).get(3, TimeUnit.SECONDS);
            await(() -> !ledger.hasOutstandingWork());
            assertEquals(12, stats.scheduled());
            assertTrue(stats.missedCapacity() > 0);
            assertTrue(stats.saturated());
            assertEquals(stats.attempted(), ledger.snapshot().accepted());
            assertEquals(12, ledger.snapshot().scheduled());
            assertEquals(stats.missedCapacity() + stats.missedScheduling(), ledger.snapshot().missed());
            assertEquals(0, ledger.snapshot().violations());
        } finally { server.stop(0); worker.shutdownNow(); }
    }

    @Test
    void realSmppAdapterRemainsBoundedWhileProviderResponsesAreDelayed() throws Exception {
        RunConfig config = new RunConfig(1, "smpp", 40, 100, 0, 1, 1, false, 200, 0, 2000, 1, 100);
        MessageLedger ledger = new MessageLedger(config, "smpp-rate", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var provider = new SmppProvider(config, new ProviderSettings("127.0.0.1", port, "user", "secret", 1, 1, 10, 3000), ledger);
                var client = new SmppAdmissionClient(config, new SmppIngressSettings("127.0.0.1", port,
                        "user", "secret", "from", "to", 1, 3000), ledger);
                var generator = new FixedRateGenerator(config, ledger,
                        (measured, scheduled) -> client.submit(measured, scheduled).isPresent())) {
            provider.start(); client.connect();
            long start = System.nanoTime();
            var stats = generator.start(MEASUREMENT, start, start + 500_000_000L).get(3, TimeUnit.SECONDS);
            await(() -> !ledger.hasOutstandingWork());
            assertEquals(20, stats.scheduled());
            assertTrue(stats.missedCapacity() > 0);
            assertEquals(stats.attempted(), ledger.snapshot().providerResponses());
            assertEquals(20, ledger.snapshot().scheduled());
            assertEquals(1, ledger.snapshot().peakOutstanding());
            assertEquals(0, ledger.snapshot().violations());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertTrue(condition.getAsBoolean());
    }
}
