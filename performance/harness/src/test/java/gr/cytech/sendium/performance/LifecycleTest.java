package gr.cytech.sendium.performance;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {
    private RunConfig config(int drain) {
        return new RunConfig(1, "http", 100, 100, 2, 3, drain, true, 0, 100, 1000, 10, 100);
    }

    @Test
    void stopsNewTrafficBeforeBoundedDrainAndFinalizesBeforeClose() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScenario scenario = new FakeScenario(clock);
        scenario.completeAt = Long.MAX_VALUE;
        List<Lifecycle.Phase> phases = new ArrayList<>();
        Lifecycle.Outcome outcome = new Lifecycle(clock, phases::add).run(config(1), scenario);
        assertTrue(outcome.drainTimeoutReached());
        assertEquals(1_000_000_000L, outcome.drainNanos());
        assertEquals(List.of("ready", "WARMUP:0:2000000000", "stop:2000000000",
                "MEASUREMENT:2000000000:5000000000", "stop:5000000000", "finalize", "close"), scenario.events);
        assertEquals(List.of(Lifecycle.Phase.READINESS, Lifecycle.Phase.WARMUP,
                Lifecycle.Phase.MEASUREMENT, Lifecycle.Phase.DRAIN,
                Lifecycle.Phase.FINALIZATION, Lifecycle.Phase.FINISHED), phases);
    }

    @Test
    void stoppingGenerationDoesNotExtendTheAbsoluteDrainDeadline() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScenario scenario = new FakeScenario(clock) {
            @Override public void stopGeneration() {
                super.stopGeneration();
                if (clock.now == 5_000_000_000L) { clock.now += 700_000_000L; }
            }
        };
        scenario.completeAt = Long.MAX_VALUE;
        var outcome = new Lifecycle(clock, phase -> { }).run(config(1), scenario);
        assertTrue(outcome.drainTimeoutReached());
        assertEquals(6_000_000_000L, clock.now);
        assertEquals(1_000_000_000L, outcome.drainNanos());
    }

    @Test
    void drainEndsAsSoonAsOutstandingWorkCompletes() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScenario scenario = new FakeScenario(clock);
        scenario.completeAt = 5_100_000_000L;
        Lifecycle.Outcome outcome = new Lifecycle(clock, phase -> { }).run(config(10), scenario);
        assertFalse(outcome.drainTimeoutReached());
        assertEquals(100_000_000L, outcome.drainNanos());
    }

    @Test
    void readinessFailureClosesResourcesWithoutStartingTraffic() {
        FakeClock clock = new FakeClock();
        FakeScenario scenario = new FakeScenario(clock) {
            @Override public void awaitReady() { throw new IllegalStateException("not ready"); }
        };
        assertThrows(IllegalStateException.class,
                () -> new Lifecycle(clock, phase -> { }).run(config(1), scenario));
        assertEquals(List.of("close"), scenario.events);
    }

    @Test
    void interruptionStopsGenerationAndClosesResources() {
        FakeClock clock = new FakeClock();
        clock.interrupt = true;
        FakeScenario scenario = new FakeScenario(clock);
        assertThrows(InterruptedException.class,
                () -> new Lifecycle(clock, phase -> { }).run(config(1), scenario));
        assertEquals("stop:0", scenario.events.get(2));
        assertEquals("close", scenario.events.get(3));
    }

    private static class FakeClock implements Lifecycle.Clock {
        long now;
        boolean interrupt;
        public long nanoTime() { return now; }
        public void sleepNanos(long nanos) throws InterruptedException {
            if (interrupt) { throw new InterruptedException(); }
            now += nanos;
        }
    }

    private static class FakeScenario implements Scenario {
        final FakeClock clock;
        final List<String> events = new ArrayList<>();
        long completeAt;
        FakeScenario(FakeClock clock) { this.clock = clock; }
        public void awaitReady() { events.add("ready"); }
        public void startGeneration(Lifecycle.Phase phase, long start, long deadline) {
            events.add(phase + ":" + start + ":" + deadline);
        }
        public void stopGeneration() { events.add("stop:" + clock.now); }
        public boolean hasOutstandingWork() { return clock.now < completeAt; }
        public void finalizeAccounting() { events.add("finalize"); }
        public void close() { events.add("close"); }
    }
}
