package gr.cytech.sendium.performance;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

public final class Lifecycle {
    public enum Phase { READINESS, WARMUP, MEASUREMENT, DRAIN, FINALIZATION, FINISHED }

    public interface Clock {
        long nanoTime();
        void sleepNanos(long nanos) throws InterruptedException;
    }

    public record Outcome(boolean drainTimeoutReached, long drainNanos) { }

    private static final long POLL_NANOS = Duration.ofMillis(20).toNanos();
    private final Clock clock;
    private final Consumer<Phase> observer;

    public Lifecycle(Consumer<Phase> observer) {
        this(new Clock() {
            public long nanoTime() { return System.nanoTime(); }
            public void sleepNanos(long nanos) throws InterruptedException {
                TimeUnit.NANOSECONDS.sleep(nanos);
            }
        }, observer);
    }

    public Lifecycle(Clock clock, Consumer<Phase> observer) {
        this.clock = clock;
        this.observer = observer;
    }

    public Outcome run(RunConfig config, Scenario scenario) throws Exception {
        // Adapter readiness operations must use bounded transport/database timeouts.
        try (scenario) {
            observer.accept(Phase.READINESS);
            scenario.awaitReady();
            generate(scenario, Phase.WARMUP, config.warmupSeconds());
            generate(scenario, Phase.MEASUREMENT, config.measurementSeconds());
            observer.accept(Phase.DRAIN);
            long start = clock.nanoTime();
            long budget = Duration.ofSeconds(config.drainTimeoutSeconds()).toNanos();
            while (scenario.hasOutstandingWork() && clock.nanoTime() - start < budget) {
                clock.sleepNanos(Math.max(0, Math.min(POLL_NANOS, budget - (clock.nanoTime() - start))));
            }
            boolean timedOut = scenario.hasOutstandingWork();
            long elapsed = clock.nanoTime() - start;
            observer.accept(Phase.FINALIZATION);
            scenario.finalizeAccounting();
            observer.accept(Phase.FINISHED);
            return new Outcome(timedOut, elapsed);
        }
    }

    private void generate(Scenario scenario, Phase phase, int seconds) throws Exception {
        observer.accept(phase);
        long start = clock.nanoTime();
        long duration = Duration.ofSeconds(seconds).toNanos();
        try {
            if (duration > 0) {
                scenario.startGeneration(phase, start, start + duration);
                while (clock.nanoTime() - start < duration) {
                    clock.sleepNanos(Math.max(0, Math.min(POLL_NANOS, duration - (clock.nanoTime() - start))));
                }
            }
        } finally {
            scenario.stopGeneration();
        }
    }
}
